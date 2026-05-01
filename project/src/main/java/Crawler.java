import CrawlerUtils.Utils;
import CrawlerUtils.WebPageExtractor;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Crawler {
    private final int numWorker = 10;
    private final DB db;
    private final Map<String, WebPageData> map;
    private final Map<String, Integer> urlToPageId;
    private final Map<Integer, String> pageIdToUrl;
    private final Indexer indexer;

    @SuppressWarnings("unchecked")
    public Crawler(String databasePath) {
        this.db = DBMaker.fileDB(databasePath).make();
        this.map = (Map<String, WebPageData>) db.hashMap("WebPageData")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.JAVA)
                .createOrOpen();
        this.urlToPageId = (Map<String, Integer>) db.hashMap("UrlToPageId")
                .keySerializer(Serializer.STRING)
                .valueSerializer(Serializer.INTEGER)
                .createOrOpen();
        this.pageIdToUrl = (Map<Integer, String>) db.hashMap("PageIdToUrl")
                .keySerializer(Serializer.INTEGER)
                .valueSerializer(Serializer.STRING)
                .createOrOpen();
        this.indexer = new Indexer(this.db);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!db.isClosed()) {
                db.close();
            }
        }));
    }

    /**
     * Parallel BFS crawl that guarantees reproducible ordering across runs.
     *
     * Ordering rules:
     *   1. Process pages level by level (BFS).
     *   2. Within the same level, URLs are visited in the order their
     *      parent pages appear in the previous level.
     *   3. Within the same parent page, child links keep their original
     *      order as extracted from the HTML.
     *   4. When a URL is discovered for the first time, its position is
     *      preserved; subsequent duplicates are ignored.
     *
     * All HTTP requests are issued in parallel, but the assignment of
     * page IDs and all writes to the indexes are performed sequentially
     * (by the main thread), completely eliminating race conditions.
     */
    public void fetchPagesBFS(String entryPoint, int k) {
        ExecutorService executor = Executors.newFixedThreadPool(numWorker);
        try {
            // Track successfully crawled URLs (insertion order preserved)
            Set<String> fetchedPages = new LinkedHashSet<>();
            int numFetched = 0;

            // Current BFS layer – ordered list of URLs (no duplicates)
            List<String> currentLayer = new ArrayList<>();
            currentLayer.add(entryPoint);

            while (!currentLayer.isEmpty() && numFetched < k) {
                // Remove URLs that have already been crawled, preserving order
                List<String> toProcess = new ArrayList<>();
                for (String url : currentLayer) {
                    if (!fetchedPages.contains(url)) {
                        toProcess.add(url);
                    }
                }
                if (toProcess.isEmpty()) {
                    break;
                }

                // ─── Phase 1: parallel HTTP fetching ───
                List<Future<WebPageData>> futures = new ArrayList<>(toProcess.size());
                for (String url : toProcess) {
                    Future<WebPageData> future = executor.submit(() -> {
                        // Check local cache (MapDB reads are thread‑safe)
                        WebPageData existing = map.get(url);
                        boolean needFetch = (existing == null)
                                || Utils.isModified(url, existing.getLastModified());
                        if (needFetch) {
                            try {
                                return fetchAndExtract(url);   // performs real HTTP request
                            } catch (IOException e) {
                                return null;                   // network failure
                            }
                        } else {
                            return existing;                   // cache hit, no change
                        }
                    });
                    futures.add(future);
                }

                // ─── Phase 2: sequential processing (single‑threaded) ───
                List<String> nextLayer = new ArrayList<>();
                // Preserve insertion order and skip duplicates for the next layer
                Set<String> seenInNextLayer = new LinkedHashSet<>();

                for (int i = 0; i < toProcess.size() && numFetched < k; i++) {
                    String url = toProcess.get(i);
                    WebPageData data;
                    try {
                        data = futures.get(i).get();   // block until fetch completes
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException e) {
                        System.err.println("Unexpected error for " + url + ": " + e.getCause());
                        continue;
                    }

                    if (data == null) {
                        // Fetch failed – skip this URL without allocating a page ID
                        continue;
                    }

                    // Assign page ID strictly in BFS discovery order
                    numFetched++;
                    int pageId = numFetched;
                    data.setPageID(pageId);

                    // Update persistent storage (single‑threaded, no lock needed)
                    map.put(url, data);
                    urlToPageId.put(url, pageId);
                    pageIdToUrl.put(pageId, url);
                    indexer.process(data);
                    fetchedPages.add(url);

                    // Enqueue child links in their original extraction order
                    if (data.getSubLink() != null) {
                        for (String childUrl : data.getSubLink()) {
                            if (!fetchedPages.contains(childUrl) && seenInNextLayer.add(childUrl)) {
                                nextLayer.add(childUrl);
                            }
                        }
                    }
                }

                // If enough pages have been crawled, cancel any outstanding futures
                if (numFetched >= k) {
                    for (Future<WebPageData> f : futures) {
                        f.cancel(true);
                    }
                }

                currentLayer = nextLayer;
            }
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ─── Post‑processing: remove pages not covered by this crawl ───
        // All operations from here on are single‑threaded and thus race‑condition free.

        // Determine which pages were successfully crawled (page ID ≤ k)
        Set<String> validPages = map.keySet().stream()
                .filter(key -> urlToPageId.get(key) != null && urlToPageId.get(key) <= k)
                .collect(Collectors.toSet());

        map.keySet().removeIf(key -> !validPages.contains(key));
        urlToPageId.keySet().removeIf(key -> !validPages.contains(key));
        pageIdToUrl.keySet().removeIf(id -> !validPages.contains(pageIdToUrl.get(id)));

        // Clean stale child links so they only point to crawled pages
        for (WebPageData data : map.values()) {
            Vector<String> links = data.getSubLink();
            if (links != null) {
                links.removeIf(link -> !validPages.contains(link));
            }
        }

        db.commit();
    }

    /**
     * Stateless helper that fetches a URL and extracts all required data.
     * A new WebPageExtractor is created for every call, so there is no
     * shared mutable state that could cause race conditions.
     */
    private WebPageData fetchAndExtract(String url) throws IOException {
        WebPageExtractor we = new WebPageExtractor(url);
        String content = we.extractContent();
        String title = we.extractTitle();
        Vector<String> links = we.extractLinks();
        Instant lastModified = we.getLastModified();
        int pageSize = content.length();
        return new WebPageData(url, title, content, links, lastModified, pageSize);
    }

    // ─── Debugging output ─────────────────────────────────────────────────

    public void printAllData() {
        for (Map.Entry<String, WebPageData> entry : this.map.entrySet()) {
            System.out.println("URL: " + entry.getKey());
            System.out.println("Data pageID: " + entry.getValue().getPageID());
            System.out.println("------------------------");
        }
    }

    public static void main(String[] args) {
        Crawler crawler = new Crawler("database.db");
        crawler.fetchPagesBFS("https://www.cse.ust.hk/~kwtleung/COMP4321/testpage.htm", 300);
        crawler.printAllData();
    }
}