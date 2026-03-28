import java.io.Serializable;
import java.time.Instant;
import java.util.Vector;

public class WebPageData implements Serializable {
    private String pageText;
    private String pageTitle;
    private String url; // this is the key (unique and will not be modified)
    private int pageID;  // will change when re-crawl
    private Vector<String> subLink; // child
    private Instant lastModified;
    private int pageSize;

    WebPageData(String url, String pageTitle, String pageText, Vector<String> subLink, Instant lastModified, int pageSize) {
        this.url = url;
        this.pageTitle = pageTitle;
        this.pageText = pageText;
        this.subLink = subLink;
        this.lastModified = lastModified;
        this.pageSize = pageSize;
    }

    public int getPageID() {
        return pageID;
    }

    public void setPageID(int pageID) {
        this.pageID = pageID;
    }

    public Instant getLastModified(){
        return this.lastModified;
    }

    public Vector<String> getSubLink(){
        return this.subLink;
    }

    public String getPageText() {
        return pageText;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public String getUrl() {
        return url;
    }

    public int getPageSize() {
        return pageSize;
    }
}
