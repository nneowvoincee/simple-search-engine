import IRUtilities.Porter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Webserver
 *
 * API:
 *   GET /api/search?q=<query>[&titleBoost=true]
 *
 * 查询语法：
 *   - 关键词查询 : java programming
 *   - 短语查询   : "information retrieval"
 *   - 排除词     : -coffee, -"data mining"
 *   - 混合查询   : java -coffee "information retrieval" -"data mining"
 *
 * 参数：
 *   titleBoost : true/false（默认 false），是否对标题命中额外加权
 */
public class Webserver {

    // ── 数据库索引映射 ─────────────────────────────────────────────────────
    private static DB db;
    private static Map<String, WebPageData>                 webPageDataMap;
    private static Map<String, Map<Integer, List<Integer>>> bodyInverted;
    private static Map<String, Map<Integer, List<Integer>>> titleInverted;
    private static Map<Integer, Map<String, Integer>>       bodyForward;
    private static Map<Integer, Map<String, Integer>>       titleForward;
    private static Map<Integer, Integer>                    bodyMaxTf;
    private static Map<Integer, Integer>                    titleMaxTf;
    private static Map<Integer, String>                     pageIdToUrl;

    // ── NLP 工具 ───────────────────────────────────────────────────────────
    private static Porter      porter;
    private static Set<String> stopWords;

    // ── 常量 ───────────────────────────────────────────────────────────────
    private static final Pattern WORD_PAT     = Pattern.compile("[A-Za-z0-9]+");
    private static final double  TITLE_WEIGHT = 5.0;
    private static final double  PHRASE_BONUS = 5.0;
    private static final int     MAX_RESULTS  = 50;
    private static final int     MAX_KW       = 10;
    private static final int     MAX_LINKS    = 10;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC"));

    // ======================================================================
    // ★ 查询解析结果容器（支持必须/排除词及短语）
    // ======================================================================
    static class ParsedQuery {
        final List<List<String>> requiredStemmedPhrases;   // 必须出现的词干短语列表
        final List<String>       requiredExactPhrases;     // 必须出现的引号短语原始文本
        final List<String>       excludedTerms;            // 必须不出现的单词词干（单字排除词）
        final List<String>       excludedExactPhrases;     // 必须不出现的引号短语原始文本

        ParsedQuery(List<List<String>> requiredStemmedPhrases,
                    List<String> requiredExactPhrases,
                    List<String> excludedTerms,
                    List<String> excludedExactPhrases) {
            this.requiredStemmedPhrases = requiredStemmedPhrases;
            this.requiredExactPhrases   = requiredExactPhrases;
            this.excludedTerms          = excludedTerms;
            this.excludedExactPhrases   = excludedExactPhrases;
        }
    }

    // ======================================================================
    // 启动入口
    // ======================================================================
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        db = DBMaker.fileDB("database.db")
                .readOnly()
                .make();

        webPageDataMap = (Map<String, WebPageData>)
                db.hashMap("WebPageData")
                        .keySerializer(Serializer.STRING)
                        .valueSerializer(Serializer.JAVA)
                        .open();

        bodyInverted = (Map<String, Map<Integer, List<Integer>>>)
                db.hashMap("BodyInverted")
                        .keySerializer(Serializer.STRING)
                        .valueSerializer(Serializer.JAVA)
                        .open();

        titleInverted = (Map<String, Map<Integer, List<Integer>>>)
                db.hashMap("TitleInverted")
                        .keySerializer(Serializer.STRING)
                        .valueSerializer(Serializer.JAVA)
                        .open();

        bodyForward = (Map<Integer, Map<String, Integer>>)
                db.hashMap("BodyForward")
                        .keySerializer(Serializer.INTEGER)
                        .valueSerializer(Serializer.JAVA)
                        .open();

        titleForward = (Map<Integer, Map<String, Integer>>)
                db.hashMap("TitleForward")
                        .keySerializer(Serializer.INTEGER)
                        .valueSerializer(Serializer.JAVA)
                        .open();

        bodyMaxTf = (Map<Integer, Integer>)
                db.hashMap("BodyMaxTf")
                        .keySerializer(Serializer.INTEGER)
                        .valueSerializer(Serializer.INTEGER)
                        .open();

        titleMaxTf = (Map<Integer, Integer>)
                db.hashMap("TitleMaxTf")
                        .keySerializer(Serializer.INTEGER)
                        .valueSerializer(Serializer.INTEGER)
                        .open();

        pageIdToUrl = (Map<Integer, String>)
                db.hashMap("PageIdToUrl")
                        .keySerializer(Serializer.INTEGER)
                        .valueSerializer(Serializer.STRING)
                        .open();

        porter    = new Porter();
        stopWords = loadStopWords();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/search", Webserver::handleSearch);
        server.createContext("/api/keywords", Webserver::handleKeywords);
        server.setExecutor(null);
        server.start();
        System.out.println("webserver start at: http://localhost:8080");
        System.out.println("   search api   : GET /api/search?q=<query>&titleBoost=true");
        System.out.println("   keyword api : GET /api/keywords");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!db.isClosed()) db.close();
        }));
    }

    // ======================================================================
    // HTTP 请求处理
    // ======================================================================
    private static void handleSearch(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String q = getQueryParam(ex, "q");
        if (q == null || q.isBlank()) {
            sendJson(ex, 400, "{\"error\":\"no keyword q\"}");
            return;
        }

        // 读取 titleBoost 参数，默认 false
        boolean titleBoost = "true".equalsIgnoreCase(getQueryParam(ex, "titleBoost"));

        List<SearchResult> results = search(q.trim(), titleBoost);
        sendJson(ex, 200, toJson(results));
    }

    private static void handleKeywords(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        Set<String> allKeywords = new TreeSet<>();
        allKeywords.addAll(bodyInverted.keySet());
        allKeywords.addAll(titleInverted.keySet());

        StringBuilder json = new StringBuilder();
        json.append("{\"keywords\":[");
        Iterator<String> it = allKeywords.iterator();
        while (it.hasNext()) {
            json.append('"').append(jsEscape(it.next())).append('"');
            if (it.hasNext()) json.append(',');
        }
        json.append("]}");

        sendJson(ex, 200, json.toString());
    }

    private static String jsEscape(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static List<SearchResult> search(String rawQuery, boolean titleBoost) {
        // Step 1: 解析查询
        ParsedQuery parsed = parseQuery(rawQuery);
        List<List<String>> phrases = parsed.requiredStemmedPhrases;
        List<String>       requiredExact = parsed.requiredExactPhrases;
        List<String>       excludedTerms = parsed.excludedTerms;
        List<String>       excludedExact = parsed.excludedExactPhrases;

        if (phrases.isEmpty()) return Collections.emptyList();

        List<String> allTerms = phrases.stream()
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());

        int N = Math.max(webPageDataMap.size(), 1);

        // Step 2: 词干倒排索引 → 候选集（必须词干的并集）
        Set<Integer> candidates = new HashSet<>();
        for (String term : allTerms) {
            Optional.ofNullable(bodyInverted.get(term))
                    .ifPresent(p -> candidates.addAll(p.keySet()));
            Optional.ofNullable(titleInverted.get(term))
                    .ifPresent(p -> candidates.addAll(p.keySet()));
        }
        if (candidates.isEmpty()) return Collections.emptyList();

        // Step 3: 排除处理
        // 3-1 排除单词（通过倒排索引检查）
        if (!excludedTerms.isEmpty()) {
            for (String term : excludedTerms) {
                Set<Integer> termPages = new HashSet<>();
                Optional.ofNullable(bodyInverted.get(term))
                        .ifPresent(p -> termPages.addAll(p.keySet()));
                Optional.ofNullable(titleInverted.get(term))
                        .ifPresent(p -> termPages.addAll(p.keySet()));
                candidates.removeAll(termPages);
            }
            if (candidates.isEmpty()) return Collections.emptyList();
        }

        // 3-2 排除短语（精确原文子串匹配）
        if (!excludedExact.isEmpty()) {
            candidates.removeIf(pid -> matchesAnyExactPhrase(pid, excludedExact));
            if (candidates.isEmpty()) return Collections.emptyList();
        }

        // 3-3 必须短语精确匹配过滤
        if (!requiredExact.isEmpty()) {
            candidates.removeIf(pid -> !matchesAllExactPhrases(pid, requiredExact));
            if (candidates.isEmpty()) return Collections.emptyList();
        }

        // Step 4: TF-IDF + 短语加分（标题权重根据 titleBoost 动态决定）
        Map<Integer, Double> scores = new HashMap<>();
        for (int pid : candidates) {
            double score = tfidfScore(pid, allTerms, N, titleBoost);
            for (List<String> phrase : phrases) {
                if (phrase.size() > 1) {
                    score += phraseBonus(pid, phrase, titleBoost);
                }
            }
            if (score > 0) scores.put(pid, score);
        }

        // Step 5: 排序并构建结果
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(MAX_RESULTS)
                .map(e -> buildResult(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    //TF-IDF
    private static double tfidfScore(int pageId, List<String> terms, int N, boolean titleBoost) {
        Map<String, Integer> bFreq = bodyForward.getOrDefault(pageId,  Collections.emptyMap());
        Map<String, Integer> tFreq = titleForward.getOrDefault(pageId, Collections.emptyMap());
        int maxB = Math.max(bodyMaxTf.getOrDefault(pageId,  1), 1);
        int maxT = Math.max(titleMaxTf.getOrDefault(pageId, 1), 1);

        double bScore = 0.0, tScore = 0.0;
        for (String term : terms) {
            int dfBody  = Optional.ofNullable(bodyInverted.get(term))
                    .map(Map::size).orElse(0);
            int dfTitle = Optional.ofNullable(titleInverted.get(term))
                    .map(Map::size).orElse(0);
            bScore += singleTermTfIdf(bFreq.getOrDefault(term, 0), maxB, dfBody,  N);
            tScore += singleTermTfIdf(tFreq.getOrDefault(term, 0), maxT, dfTitle, N);
        }
        double titleMult = titleBoost ? TITLE_WEIGHT : 1.0;
        return bScore + titleMult * tScore;
    }

    private static double singleTermTfIdf(int tf, int maxTf, int df, int N) {
        if (tf == 0 || df == 0) return 0.0;
        double normalizedTf = (double) tf / maxTf;
        double idf = Math.max(0.0, Math.log((double) N / df) / Math.log(2));
        return normalizedTf * idf;
    }

    //短语加分
    private static double phraseBonus(int pageId, List<String> phrase, boolean titleBoost) {
        int bodyHits  = countPhraseOccurrences(pageId, phrase, bodyInverted);
        int titleHits = countPhraseOccurrences(pageId, phrase, titleInverted);
        double titleMult = titleBoost ? TITLE_WEIGHT : 1.0;
        return (bodyHits + titleMult * titleHits) * PHRASE_BONUS * phrase.size();
    }

    private static int countPhraseOccurrences(
            int pageId,
            List<String> phrase,
            Map<String, Map<Integer, List<Integer>>> index) {

        Map<Integer, List<Integer>> firstMap = index.get(phrase.get(0));
        if (firstMap == null) return 0;
        List<Integer> startPositions = firstMap.get(pageId);
        if (startPositions == null || startPositions.isEmpty()) return 0;

        int count = 0;
        outer:
        for (int startPos : startPositions) {
            for (int i = 1; i < phrase.size(); i++) {
                Map<Integer, List<Integer>> nextMap = index.get(phrase.get(i));
                if (nextMap == null) continue outer;
                List<Integer> nextPos = nextMap.get(pageId);
                if (nextPos == null || !nextPos.contains(startPos + i)) continue outer;
            }
            count++;
        }
        return count;
    }

    //精确文本匹配辅助方法
    private static boolean matchesAllExactPhrases(int pageId, List<String> exactPhrases) {
        String url = pageIdToUrl.get(pageId);
        if (url == null) return false;
        WebPageData data = webPageDataMap.get(url);
        if (data == null) return false;

        String bodyText  = data.getPageText()  != null ? data.getPageText()  : "";
        String titleText = data.getPageTitle() != null ? data.getPageTitle() : "";
        String fullText  = normalizeWhitespace(bodyText + " " + titleText)
                .toLowerCase(Locale.ROOT);

        for (String phrase : exactPhrases) {
            if (!fullText.contains(phrase)) {
                return false;
            }
        }
        return true;
    }

    /** 检查页面是否包含任意一个排除短语（用于排除过滤） */
    private static boolean matchesAnyExactPhrase(int pageId, List<String> exactPhrases) {
        String url = pageIdToUrl.get(pageId);
        if (url == null) return false;
        WebPageData data = webPageDataMap.get(url);
        if (data == null) return false;

        String bodyText  = data.getPageText()  != null ? data.getPageText()  : "";
        String titleText = data.getPageTitle() != null ? data.getPageTitle() : "";
        String fullText  = normalizeWhitespace(bodyText + " " + titleText)
                .toLowerCase(Locale.ROOT);

        for (String phrase : exactPhrases) {
            if (fullText.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // ======================================================================
    // 查询解析（增加排除词/短语识别）
    // ======================================================================
    private static ParsedQuery parseQuery(String raw) {
        List<List<String>> requiredPhrases = new ArrayList<>();
        List<String>       requiredExact   = new ArrayList<>();
        List<String>       excludedTerms   = new ArrayList<>();
        List<String>       excludedExact   = new ArrayList<>();

        // 匹配带可选前导减号的单词或引号短语
        // 模式解释：可选的 '-' 后跟一个非空引号短语，或一个字母数字单词
        Pattern tokenPattern = Pattern.compile("\\-?\"[^\"]+\"|\\-?[A-Za-z0-9]+");
        Matcher m = tokenPattern.matcher(raw.toLowerCase(Locale.ROOT));

        while (m.find()) {
            String tokenStr = m.group();
            boolean isExcluded = tokenStr.startsWith("-");
            if (isExcluded) {
                tokenStr = tokenStr.substring(1); // 去掉开头的 '-'
            }

            if (tokenStr.isEmpty()) continue;

            if (tokenStr.startsWith("\"")) {
                // ── 引号短语 ─────────────────────────────────────────────
                String rawPhrase = normalizeWhitespace(
                        tokenStr.substring(1, tokenStr.length() - 1));
                if (!rawPhrase.isEmpty()) {
                    if (isExcluded) {
                        excludedExact.add(rawPhrase);
                    } else {
                        requiredExact.add(rawPhrase);
                        List<String> stemmed = stemTokens(rawPhrase);
                        if (!stemmed.isEmpty()) {
                            requiredPhrases.add(stemmed);
                        }
                    }
                }
            } else {
                // ── 普通单词 ─────────────────────────────────────────────
                String word = tokenStr;
                if (stopWords.contains(word)) continue;
                String stem = porter.stripAffixes(word);
                if (stem == null || stem.isBlank()) continue;

                if (isExcluded) {
                    excludedTerms.add(stem);
                } else {
                    requiredPhrases.add(Collections.singletonList(stem));
                }
            }
        }

        return new ParsedQuery(requiredPhrases, requiredExact, excludedTerms, excludedExact);
    }

    private static List<String> stemTokens(String text) {
        List<String> result = new ArrayList<>();
        Matcher m = WORD_PAT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String token = m.group();
            if (!stopWords.contains(token)) {
                String stem = porter.stripAffixes(token);
                if (stem != null && !stem.isBlank()) result.add(stem);
            }
        }
        return result;
    }

    // ======================================================================
    // 构建结果对象
    // ======================================================================
    private static SearchResult buildResult(int pageId, double score) {
        String url = pageIdToUrl.get(pageId);
        if (url == null) return null;
        WebPageData data = webPageDataMap.get(url);
        if (data == null) return null;

        List<Map.Entry<String, Integer>> kws = bodyForward
                .getOrDefault(pageId, Collections.emptyMap())
                .entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(MAX_KW)
                .collect(Collectors.toList());

        List<String> links = data.getSubLink() == null
                ? Collections.emptyList()
                : data.getSubLink().stream().limit(MAX_LINKS).collect(Collectors.toList());

        return new SearchResult(pageId, url, data.getPageTitle(), score,
                data.getLastModified(), data.getPageSize(), kws, links);
    }

    static class SearchResult {
        final int                              pageId;
        final String                           url;
        final String                           title;
        final double                           score;
        final java.time.Instant                lastModified;
        final int                              pageSize;
        final List<Map.Entry<String, Integer>> topKeywords;
        final List<String>                     childLinks;

        SearchResult(int pageId, String url, String title, double score,
                     java.time.Instant lastModified, int pageSize,
                     List<Map.Entry<String, Integer>> topKeywords,
                     List<String> childLinks) {
            this.pageId       = pageId;
            this.url          = url;
            this.title        = title;
            this.score        = score;
            this.lastModified = lastModified;
            this.pageSize     = pageSize;
            this.topKeywords  = topKeywords;
            this.childLinks   = childLinks;
        }
    }

    // ======================================================================
    // JSON 序列化
    // ======================================================================
    private static String toJson(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(results.size()).append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            SearchResult r = results.get(i);
            sb.append('{')
                    .append("\"pageId\":").append(r.pageId).append(',')
                    .append("\"url\":").append(js(r.url)).append(',')
                    .append("\"title\":").append(js(r.title)).append(',')
                    .append("\"score\":").append(String.format(Locale.US, "%.6f", r.score)).append(',')
                    .append("\"lastModified\":").append(
                            js(r.lastModified != null ? DATE_FMT.format(r.lastModified) : "N/A"))
                    .append(',')
                    .append("\"pageSize\":").append(r.pageSize).append(',');

            sb.append("\"topKeywords\":[");
            for (int j = 0; j < r.topKeywords.size(); j++) {
                if (j > 0) sb.append(',');
                Map.Entry<String, Integer> kw = r.topKeywords.get(j);
                sb.append("{\"term\":").append(js(kw.getKey()))
                        .append(",\"tf\":").append(kw.getValue()).append('}');
            }
            sb.append("],");

            sb.append("\"childLinks\":[");
            for (int j = 0; j < r.childLinks.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(js(r.childLinks.get(j)));
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String js(String s) {
        if (s == null) return "null";
        return '"' + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + '"';
    }

    // ======================================================================
    // HTTP 工具方法
    // ======================================================================
    private static String getQueryParam(HttpExchange ex, String name) {
        String rawQuery = ex.getRequestURI().getRawQuery();
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                if (kv.length == 2 &&
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(name)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    // ======================================================================
    // 停用词加载
    // ======================================================================
    private static Set<String> loadStopWords() {
        Set<String> sw = new HashSet<>();
        try (InputStream in = Webserver.class.getClassLoader()
                .getResourceAsStream("stopwords.txt")) {
            if (in != null) { readSW(in, sw); return sw; }
        } catch (IOException ignored) {}
        File f = new File("src/main/java/stopwords.txt");
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) { readSW(in, sw); }
            catch (IOException ignored) {}
        }
        return sw;
    }

    private static void readSW(InputStream in, Set<String> set) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String w = line.trim().toLowerCase(Locale.ROOT);
                if (!w.isEmpty()) set.add(w);
            }
        }
    }
}