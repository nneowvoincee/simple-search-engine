import java.io.Serializable;
import java.time.Instant;
import java.util.Vector;

public class WebPageData implements Serializable {
    private String pageText;
    private String url; // this is the key (unique and will not be modified)
    private int pageID;  // will change when re-crawl
    private Vector<String> subLink; // child
    private Instant lastModified;

    WebPageData(String url, String pageText, Vector<String> subLink, Instant lastModified) {
        this.url = url;
        this.pageText = pageText;
        this.subLink = subLink;
        this.lastModified = lastModified;
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
}
