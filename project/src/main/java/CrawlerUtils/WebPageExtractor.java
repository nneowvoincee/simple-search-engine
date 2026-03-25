package CrawlerUtils;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.StringTokenizer;
import java.util.Vector;

/**
 * Extracts hyperlinks and plain text from a web page using Jsoup.
 * The page is fetched once during construction; all extractions are performed
 * on the cached document to avoid multiple HTTP requests.
 */
public class WebPageExtractor {
	private final Document doc;
	private Instant lastModified;

	public WebPageExtractor(String url) throws IOException {
		Connection.Response response = Jsoup.connect(url).execute();
		this.doc = response.parse();

		String lastModifiedStr = response.header("Last-Modified");
		if (lastModifiedStr != null && !lastModifiedStr.isEmpty()) {
			try {
				this.lastModified = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr));
			} catch (DateTimeParseException e) {
				this.lastModified = null;}
		} else {
			this.lastModified = null;
		}
	}


	/**
	 * Extracts all hyperlink URLs from the page.
	 *
	 * @return a Vector containing the absolute URLs of all links found
	 */
	public Vector<String> extractLinks() {
		Elements links = doc.select("a");
		Vector<String> result = new Vector<>();
		for (Element link : links) {
			String href = link.absUrl("href");
			if (!href.isEmpty()) {
				result.add(href);
			}
		}
		return result;
	}

	/**
	 * Extracts the plain text content of the page, excluding any hyperlink text.
	 * The boolean parameter is kept for compatibility with the original design
	 * but is ignored because this implementation does not embed link text.
	 *
	 * @return the plain text of the page body
	 */
	public String extractContent() {
		// Return plain text without any link markup
		return doc.body().text();
	}

	public Vector<String> extractWords() {
		// extract words in url and return them
		// use StringTokenizer to tokenize the result from StringBean
		String pageContent = this.extractContent();

		StringTokenizer st = new StringTokenizer(pageContent);
		Vector<String> vecWords = new Vector<>();
		while (st.hasMoreTokens()) {
			vecWords.add(st.nextToken());
		}
		return vecWords;
	}

	public String getCurrentURL() {
		return this.doc.location();
	}

	public Instant getLastModified() {
		return this.lastModified;
	}



	public static void main(String[] args) throws IOException {
		WebPageExtractor we = new WebPageExtractor("http://www.cs.ust.hk/");
		Vector<String> urls = we.extractLinks();
        for (String url : urls) {
            System.out.println(url);
        }
	}
}
