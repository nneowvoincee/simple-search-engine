import CrawlerUtils.Utils;
import CrawlerUtils.WebPageExtractor;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class Crawler {
    private WebPageExtractor we;
    private final int numWorker = 10;
    private final DB db;
    private final Map<String, WebPageData> map;
    private Indexer indexer;

    @SuppressWarnings("unchecked")
    public Crawler(String databasePath) {
        this.db = DBMaker.fileDB(databasePath)
                .make();
        this.map = (Map<String, WebPageData>) db.hashMap("WebPageData")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();

        this.indexer = new Indexer(this.db);    // 现在什么都没有

        // close database before shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!db.isClosed()) {
                db.close();
            }
        }));
    }

    public boolean fetch(String url) {
        try {
            this.we = new WebPageExtractor(url);
        } catch (IOException e) {
            this.we = null;
            return false;
        }
        return true;
    }
    public void fetchPagesBFS(String entryPoint, int k) {
        int numFetched = 0;
        String currentURL = null;
        Set<String> fetchedPages = new LinkedHashSet<>();
        Queue<String> pendingPage = new ArrayDeque<>();

        pendingPage.add(entryPoint);

        while (numFetched < k) {
            if (pendingPage.isEmpty()) {
                break;
            }

            currentURL = pendingPage.remove();
            if (fetchedPages.contains(currentURL)) {
                continue;
            }

            WebPageData temp = (WebPageData) map.get(currentURL);
            if (temp == null || Utils.isModified(currentURL, temp.getLastModified())) {
                boolean success = this.fetch(currentURL);
                if (!success) {
                    continue;
                }
                temp = new WebPageData(currentURL, this.extractContent(), this.extractLinks(), this.getLastModified());
            }

            numFetched += 1;
            temp.setPageID(numFetched);
            map.put(currentURL, temp);
            fetchedPages.add(currentURL);
            pendingPage.addAll(temp.getSubLink());
        }

        // 1. 删除不在 fetchedPages 中的页面（使用 stream 收集待删除键）
        Set<String> toRemove = map.keySet().stream()
                .filter(key -> !fetchedPages.contains(key))
                .collect(Collectors.toSet());
        toRemove.forEach(map::remove);

        map.entrySet().stream()
                .filter(entry -> fetchedPages.contains(entry.getKey()))
                .forEach(entry -> {
                    WebPageData data = entry.getValue();
                    Vector<String> subLinks = data.getSubLink();
                    subLinks.removeIf(link -> !fetchedPages.contains(link));
                });

        db.commit();
    }

    public void printAllData() {
        for (Map.Entry<String, WebPageData> entry : this.map.entrySet()) {
            String url = entry.getKey();
            WebPageData data = (WebPageData) entry.getValue();
            System.out.println("URL: " + url);
            System.out.println("Data: " + data.getPageID());  // 确保 WebPageData 有合适的 toString()
            System.out.println("------------------------");
        }
    }
    public Vector<String> extractLinks() {
        if (this.we == null) {
            throw new NullPointerException("Extract before fetch (or failed fetching is not handled)");
        }
        return this.we.extractLinks();
    }

    public Vector<String> extractWords() {
        if (this.we == null) {
            throw new NullPointerException("Extract before fetch (or failed fetching is not handled)");
        }
        return this.we.extractWords();
    }

    public String extractContent() {
        if (this.we == null) {
            throw new NullPointerException("Extract before fetch (or failed fetching is not handled)");
        }
        return this.we.extractContent();
    }

    public String getCurrentURL() {
        if (this.we == null) {
            throw new NullPointerException("Get before fetch (or failed fetching was not handled)");
        }
        return this.we.getCurrentURL();
    }

    public Instant getLastModified() {
        if (this.we == null) {
            throw new NullPointerException("Get before fetch (or failed fetching was not handled)");
        }
        return this.we.getLastModified();
    }

    public static void main(String[] args) {
        Crawler crawler = new Crawler("database.db");

        crawler.fetchPagesBFS("http://www.cs.ust.hk/~dlee/4321/", 3);
        crawler.printAllData();

    }
}

	
