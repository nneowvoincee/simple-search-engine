import IRUtilities.*;
import org.mapdb.DB;
import org.mapdb.Serializer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Indexer {
    final DB db;
    private final Porter porter;
    private final Set<String> stopWords;
    private final Map<String, Map<Integer, List<Integer>>> bodyInverted;
    private final Map<String, Map<Integer, List<Integer>>> titleInverted;
    private final Map<Integer, Map<String, Integer>> bodyForward;
    private final Map<Integer, Map<String, Integer>> titleForward;
    private final Map<Integer, Integer> bodyMaxTf;
    private final Map<Integer, Integer> titleMaxTf;
    private final Map<Integer, Set<String>> pageBodyTerms;
    private final Map<Integer, Set<String>> pageTitleTerms;
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z0-9]+");

    @SuppressWarnings("unchecked")
    public Indexer(DB _db) {
        this.db = _db;
        this.porter = new Porter();
        this.stopWords = loadStopWords();
        // term -> (pageId -> positions) for phrase search in body.
        this.bodyInverted = (Map<String, Map<Integer, List<Integer>>>) db.hashMap("BodyInverted")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        // term -> (pageId -> positions) for phrase search in title.
        this.titleInverted = (Map<String, Map<Integer, List<Integer>>>) db.hashMap("TitleInverted")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        // pageId -> (term -> tf), used by ranking/output.
        this.bodyForward = (Map<Integer, Map<String, Integer>>) db.hashMap("BodyForward")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        // pageId -> (term -> tf) in title.
        this.titleForward = (Map<Integer, Map<String, Integer>>) db.hashMap("TitleForward")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        // pageId -> max term frequency in body for tf normalization.
        this.bodyMaxTf = (Map<Integer, Integer>) db.hashMap("BodyMaxTf")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.INTEGER)
                .createOrOpen();
        // pageId -> max term frequency in title.
        this.titleMaxTf = (Map<Integer, Integer>) db.hashMap("TitleMaxTf")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.INTEGER)
                .createOrOpen();
        // pageId -> unique indexed terms, used to remove stale postings on recrawl.
        this.pageBodyTerms = (Map<Integer, Set<String>>) db.hashMap("PageBodyTerms")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        // pageId -> unique title terms, used to remove stale postings on recrawl.
        this.pageTitleTerms = (Map<Integer, Set<String>>) db.hashMap("PageTitleTerms")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
    }

    public void process(WebPageData webpage) {
        if (webpage == null) {
            return;
        }
        int pageId = webpage.getPageID();
        if (pageId <= 0) {
            return;
        }

        // If a page is re-crawled, clean old postings before inserting new ones.
        removeOldPosting(pageId, pageBodyTerms.get(pageId), bodyInverted);
        removeOldPosting(pageId, pageTitleTerms.get(pageId), titleInverted);

        Map<String, List<Integer>> bodyPositions = tokenizeAndStem(webpage.getPageText());
        Map<String, List<Integer>> titlePositions = tokenizeAndStem(webpage.getPageTitle());

        upsertInvertedIndex(pageId, bodyPositions, bodyInverted);
        upsertInvertedIndex(pageId, titlePositions, titleInverted);

        bodyForward.put(pageId, toFreqMap(bodyPositions));
        titleForward.put(pageId, toFreqMap(titlePositions));
        bodyMaxTf.put(pageId, findMaxTf(bodyPositions));
        titleMaxTf.put(pageId, findMaxTf(titlePositions));
        pageBodyTerms.put(pageId, new HashSet<>(bodyPositions.keySet()));
        pageTitleTerms.put(pageId, new HashSet<>(titlePositions.keySet()));
    }

    public void removePage(int pageId) {
        Set<String> bodyTerms = pageBodyTerms.get(pageId);
        if (bodyTerms != null) {
            removeOldPosting(pageId, bodyTerms, bodyInverted);
            pageBodyTerms.remove(pageId);
        }

        Set<String> titleTerms = pageTitleTerms.get(pageId);
        if (titleTerms != null) {
            removeOldPosting(pageId, titleTerms, titleInverted);
            pageTitleTerms.remove(pageId);
        }

        bodyForward.remove(pageId);
        titleForward.remove(pageId);
        bodyMaxTf.remove(pageId);
        titleMaxTf.remove(pageId);
    }

    private Map<String, List<Integer>> tokenizeAndStem(String text) {
        Map<String, List<Integer>> positions = new HashMap<>();
        if (text == null || text.isBlank()) {
            return positions;
        }

        // Normalize case, remove stopwords, then stem with Porter.
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        int pos = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (stopWords.contains(token)) {
                continue;
            }
            String stem = porter.stripAffixes(token);
            if (stem == null || stem.isBlank()) {
                continue;
            }
            // Position is stored so phrase queries can be evaluated later.
            positions.computeIfAbsent(stem, k -> new ArrayList<>()).add(pos);
            pos += 1;
        }
        return positions;
    }

    private void upsertInvertedIndex(int pageId,
                                     Map<String, List<Integer>> termPositions,
                                     Map<String, Map<Integer, List<Integer>>> inverted) {
        for (Map.Entry<String, List<Integer>> entry : termPositions.entrySet()) {
            String term = entry.getKey();
            Map<Integer, List<Integer>> posting = inverted.get(term);
            if (posting == null) {
                posting = new HashMap<>();
            } else {
                posting = new HashMap<>(posting);
            }
            posting.put(pageId, new ArrayList<>(entry.getValue()));
            inverted.put(term, posting);
        }
    }

    private void removeOldPosting(int pageId,
                                  Set<String> oldTerms,
                                  Map<String, Map<Integer, List<Integer>>> inverted) {
        if (oldTerms == null || oldTerms.isEmpty()) {
            return;
        }
        for (String term : oldTerms) {
            Map<Integer, List<Integer>> posting = inverted.get(term);
            if (posting == null) {
                continue;
            }
            Map<Integer, List<Integer>> updated = new HashMap<>(posting);
            updated.remove(pageId);
            if (updated.isEmpty()) {
                inverted.remove(term);
            } else {
                inverted.put(term, updated);
            }
        }
    }

    private Map<String, Integer> toFreqMap(Map<String, List<Integer>> positions) {
        Map<String, Integer> freq = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : positions.entrySet()) {
            freq.put(entry.getKey(), entry.getValue().size());
        }
        return freq;
    }

    private int findMaxTf(Map<String, List<Integer>> positions) {
        int max = 0;
        for (List<Integer> list : positions.values()) {
            if (list.size() > max) {
                max = list.size();
            }
        }
        return max;
    }

    private Set<String> loadStopWords() {
        Set<String> sw = new HashSet<>();
        try (InputStream in = Webserver.class.getClassLoader().getResourceAsStream("stopwords.txt")) {
            if (in != null) {
                readStopWords(in, sw);
                return sw;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.err.println("FATAL: stopwords.txt not found in classpath! Make sure it is in src/main/resources/");
        System.exit(1);
        return sw;
    }

    private void readStopWords(InputStream in, Set<String> stopwordSet) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase(Locale.ROOT);
                if (!word.isEmpty()) {
                    stopwordSet.add(word);
                }
            }
        }
    }
}
