import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.stream.Collectors;

public class SpiderResultExporter {
    private static final String SEPARATOR = "--------------------------------------------------";
    private static final int MAX_PAGES = 30;
    private static final int MAX_KEYWORDS = 10;
    private static final int MAX_CHILD_LINKS = 10;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String dbPath = args.length >= 1 ? args[0] : "database.db";
        String outputPath = args.length >= 2 ? args[1] : "spider_result.txt";

        DB db = DBMaker.fileDB(dbPath).make();
        try {
            Map<String, WebPageData> webPageData = (Map<String, WebPageData>) db.hashMap("WebPageData")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.JAVA)
                    .createOrOpen();
            Map<Integer, Map<String, Integer>> bodyForward = (Map<Integer, Map<String, Integer>>) db.hashMap("BodyForward")
                    .keySerializer(Serializer.INTEGER)
                    .valueSerializer(Serializer.JAVA)
                    .createOrOpen();

            List<WebPageData> pages = webPageData.values().stream()
                    .filter(p -> p != null && p.getPageID() > 0)
                    .sorted(Comparator.comparingInt(WebPageData::getPageID))
                    .limit(MAX_PAGES)
                    .collect(Collectors.toList());

            writeResultFile(outputPath, pages, bodyForward);
            System.out.println("Export completed: " + outputPath);
        } catch (IOException e) {
            System.err.println("Failed to write spider result: " + e.getMessage());
        } finally {
            if (!db.isClosed()) {
                db.close();
            }
        }
    }

    private static void writeResultFile(String outputPath,
                                        List<WebPageData> pages,
                                        Map<Integer, Map<String, Integer>> bodyForward) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            for (int i = 0; i < pages.size(); i++) {
                WebPageData page = pages.get(i);
                writer.write(safe(page.getPageTitle()));
                writer.newLine();
                writer.write(safe(page.getUrl()));
                writer.newLine();
                writer.write(formatDateAndSize(page.getLastModified(), page.getPageSize()));
                writer.newLine();
                writer.write(formatTopKeywords(bodyForward.get(page.getPageID())));
                writer.newLine();

                Vector<String> childLinks = page.getSubLink();
                if (childLinks == null || childLinks.isEmpty()) {
                    writer.write("N/A");
                    writer.newLine();
                } else {
                    int limit = Math.min(MAX_CHILD_LINKS, childLinks.size());
                    for (int j = 0; j < limit; j++) {
                        writer.write(safe(childLinks.get(j)));
                        writer.newLine();
                    }
                }

                if (i < pages.size() - 1) {
                    writer.write(SEPARATOR);
                    writer.newLine();
                }
            }
        }
    }

    private static String formatTopKeywords(Map<String, Integer> termFreq) {
        if (termFreq == null || termFreq.isEmpty()) {
            return "N/A";
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(termFreq.entrySet());
        entries.sort((a, b) -> {
            int byFreq = Integer.compare(b.getValue(), a.getValue());
            if (byFreq != 0) {
                return byFreq;
            }
            return a.getKey().compareTo(b.getKey());
        });

        return entries.stream()
                .limit(MAX_KEYWORDS)
                .map(e -> safe(e.getKey()) + " " + (e.getValue() == null ? "N/A" : e.getValue()))
                .collect(Collectors.joining("; "));
    }

    private static String formatDateAndSize(Instant lastModified, int pageSize) {
        String date = lastModified == null
                ? "N/A"
                : LocalDate.ofInstant(lastModified, ZoneOffset.UTC).toString();
        String size = pageSize > 0 ? Integer.toString(pageSize) : "N/A";
        return date + ", " + size;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }
        return value;
    }
}
