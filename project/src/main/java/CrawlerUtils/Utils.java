package CrawlerUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class Utils {
    public static boolean isWebPageURL(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            // Parse the URL without actually connecting
            URL parsedUrl = (new URI(url)).toURL();
            String protocol = parsedUrl.getProtocol();
            // Accept only HTTP and HTTPS as web page protocols
            return ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                    && parsedUrl.getHost() != null && !parsedUrl.getHost().isEmpty();
        } catch (MalformedURLException | URISyntaxException e) {
            // The URL is not well-formed
            return false;
        }
    }

    public static boolean isModified(String url, Instant storedLastModified) {
        if (storedLastModified == null) {
            return true;
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // 构造 HEAD 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();

        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() != 200) {
//                System.out.println("Headers:");
//                response.headers().map().forEach((name, values) -> {
//                    System.out.println(name + ": " + values);
//                });
                return true;
            }

            Optional<String> lastModifiedHeader = response.headers().firstValue("Last-Modified");
            if (lastModifiedHeader.isEmpty()) {
                return true;
            }

            Instant remoteLastModified = Instant.from(
                    DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedHeader.get())
            );

            return remoteLastModified.isAfter(storedLastModified);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("url: "+url);
            return true;
        }
    }

}
