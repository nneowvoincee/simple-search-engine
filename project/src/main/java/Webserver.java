
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
 *   GET /api/search?q=<query>
 *
 * 查询语法：
 *   - 关键词查询 : java programming
 *   - 短语查询   : "information retrieval"
 *   - 混合查询   : java "information retrieval"
 *
 * 注意：Crawler 与 Webserver 不可同时运行，
 *       MapDB 文件型数据库不支持多进程并发写入。
 *       请先运行 Crawler 爬取并提交数据库，再启动 Webserver。
 */
public class Webserver {

    // ── 数据库索引映射 ─────────────────────────────────────────────────────
    private static DB db;
    /** URL → 页面元数据 */
    private static Map<String, WebPageData>                 webPageDataMap;
    /** 词干 → (pageId → 正文中出现的位置列表) */
    private static Map<String, Map<Integer, List<Integer>>> bodyInverted;
    /** 词干 → (pageId → 标题中出现的位置列表) */
    private static Map<String, Map<Integer, List<Integer>>> titleInverted;
    /** pageId → (词干 → 正文词频) */
    private static Map<Integer, Map<String, Integer>>       bodyForward;
    /** pageId → (词干 → 标题词频) */
    private static Map<Integer, Map<String, Integer>>       titleForward;
    /** pageId → 正文最大词频（用于 TF 归一化） */
    private static Map<Integer, Integer>                    bodyMaxTf;
    /** pageId → 标题最大词频 */
    private static Map<Integer, Integer>                    titleMaxTf;
    /** pageId → URL */
    private static Map<Integer, String>                     pageIdToUrl;

    // ── NLP 工具 ───────────────────────────────────────────────────────────
    private static Porter      porter;
    private static Set<String> stopWords;

    // ── 常量 ───────────────────────────────────────────────────────────────
    private static final Pattern WORD_PAT     = Pattern.compile("[A-Za-z0-9]+");
    /** 标题命中的额外权重倍数 */
    private static final double  TITLE_WEIGHT = 2.0;
    /** 短语完整命中时每词每次的额外分值 */
    private static final double  PHRASE_BONUS = 5.0;
    private static final int     MAX_RESULTS  = 50;
    private static final int     MAX_KW       = 10;
    private static final int     MAX_LINKS    = 10;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC"));

    // ======================================================================
    // ★ 新增：查询解析结果容器
    //    stemmedPhrases  — 用于倒排索引候选集检索（词干化后的短语列表）
    //    exactPhrases    — 引号内的原始文本，用于对 pageText 做精确子串匹配
    // ======================================================================
    static class ParsedQuery {
        /** 全部短语的词干列表（单词查询也封装为单元素列表） */
        final List<List<String>> stemmedPhrases;
        /**
         * 仅引号短语的原始文本（小写、空白归一化），顺序与 stemmedPhrases 中
         * 对应的短语一致。非引号词不产生 exactPhrase 条目。
         */
        final List<String> exactPhrases;

        ParsedQuery(List<List<String>> stemmedPhrases, List<String> exactPhrases) {
            this.stemmedPhrases = stemmedPhrases;
            this.exactPhrases   = exactPhrases;
        }
    }

    // ======================================================================
    // 启动入口
    // ======================================================================
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 以只读模式打开已有数据库，避免与 Crawler 冲突
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
        server.createContext("/api/keywords", Webserver::handleKeywords);  // 新增
        server.setExecutor(null);
        server.start();
        System.out.println("✅ 服务启动成功: http://localhost:8080");
        System.out.println("   搜索接口   : GET /api/search?q=<query>");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!db.isClosed()) db.close();
        }));
    }

    // ======================================================================
    // HTTP 请求处理
    // ======================================================================
    private static void handleSearch(HttpExchange ex) throws IOException {
        // CORS，允许前端跨域调用
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
            sendJson(ex, 400, "{\"error\":\"缺少查询参数 q\"}");
            return;
        }

        List<SearchResult> results = search(q.trim());
        sendJson(ex, 200, toJson(results));
    }

    private static void handleKeywords(HttpExchange ex) throws IOException {
        // CORS 头
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

        // 获取所有词干（去重）
        Set<String> allKeywords = new TreeSet<>();        // TreeSet 自动排序
        allKeywords.addAll(bodyInverted.keySet());
        allKeywords.addAll(titleInverted.keySet());

        // 构造 JSON
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

    // 简单的字符串转义方法（您已有的 js 方法可直接复用）
    private static String jsEscape(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ======================================================================
    // 核心检索逻辑
    // ======================================================================

    /**
     * 搜索流程：
     *   Step 1  解析查询 → 得到词干短语列表 + 原始精确短语列表
     *   Step 2  用词干倒排索引得到候选文档集合
     *   Step 3  ★ 若查询含引号短语，在候选集中过滤：
     *              只保留 pageText / pageTitle 原文包含所有精确短语的页面
     *   Step 4  对过滤后的候选集做 TF-IDF + 短语位置加分
     *   Step 5  按分值降序返回前 MAX_RESULTS 条
     */
    private static List<SearchResult> search(String rawQuery) {
        // Step 1: 解析查询
        ParsedQuery parsed = parseQuery(rawQuery);
        List<List<String>> phrases = parsed.stemmedPhrases;
        List<String>       exactPhrases = parsed.exactPhrases;

        if (phrases.isEmpty()) return Collections.emptyList();

        List<String> allTerms = phrases.stream()
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());

        int N = Math.max(webPageDataMap.size(), 1);

        // Step 2: 词干倒排索引 → 候选集
        Set<Integer> candidates = new HashSet<>();
        for (String term : allTerms) {
            Optional.ofNullable(bodyInverted.get(term))
                    .ifPresent(p -> candidates.addAll(p.keySet()));
            Optional.ofNullable(titleInverted.get(term))
                    .ifPresent(p -> candidates.addAll(p.keySet()));
        }
        if (candidates.isEmpty()) return Collections.emptyList();

        // ★ Step 3: 精确短语过滤
        //    对每个引号短语，要求候选页面的 pageText 或 pageTitle 中
        //    必须包含该短语的原始文本（大小写不敏感，空白归一化）。
        //    所有引号短语均须满足（AND 语义）。
        if (!exactPhrases.isEmpty()) {
            candidates.removeIf(pid -> !matchesAllExactPhrases(pid, exactPhrases));
            if (candidates.isEmpty()) return Collections.emptyList();
        }

        // Step 4: TF-IDF 打分 + 短语位置加分
        Map<Integer, Double> scores = new HashMap<>();
        for (int pid : candidates) {
            double score = tfidfScore(pid, allTerms, N);
            for (List<String> phrase : phrases) {
                if (phrase.size() > 1) {
                    score += phraseBonus(pid, phrase);
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

    // ── TF-IDF 打分 ────────────────────────────────────────────────────────

    /**
     * 计算单个文档对一组查询词的 TF-IDF 总分。
     *
     * 公式：
     *   bodyScore  = Σ_t [ tf(t,d)/maxTf_body(d)  × log₂(N/df_body(t))  ]
     *   titleScore = Σ_t [ tf(t,d)/maxTf_title(d) × log₂(N/df_title(t)) ]
     *   score      = bodyScore + TITLE_WEIGHT × titleScore
     */
    private static double tfidfScore(int pageId, List<String> terms, int N) {
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
        return bScore + TITLE_WEIGHT * tScore;
    }

    /**
     * 单个词项的 TF-IDF 值：(tf / maxTf) × log₂(N / df)
     * tf 或 df 为 0 时返回 0，避免除零和无意义的计算。
     */
    private static double singleTermTfIdf(int tf, int maxTf, int df, int N) {
        if (tf == 0 || df == 0) return 0.0;
        double normalizedTf = (double) tf / maxTf;
        double idf = Math.max(0.0, Math.log((double) N / df) / Math.log(2));
        return normalizedTf * idf;
    }

    // ── 短语加分 ───────────────────────────────────────────────────────────

    /**
     * 计算短语在文档中连续出现的加分。
     * 利用倒排索引中存储的位置信息进行严格的相邻性检查。
     */
    private static double phraseBonus(int pageId, List<String> phrase) {
        int bodyHits  = countPhraseOccurrences(pageId, phrase, bodyInverted);
        int titleHits = countPhraseOccurrences(pageId, phrase, titleInverted);
        return (bodyHits + TITLE_WEIGHT * titleHits) * PHRASE_BONUS * phrase.size();
    }

    /**
     * 在指定索引中，统计短语在给定页面中连续出现的次数。
     * 检查逻辑：phrase[0] 的某个位置 p 满足 phrase[i] 出现在位置 p+i（i=1,2,...）
     */
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

    /**
     * 判断某页面是否在其正文或标题中包含所有精确短语（AND 语义）。
     *
     * 匹配规则：
     *   - 大小写不敏感
     *   - 对短语和页面文本均进行空白归一化（连续空白压缩为单个空格），
     *     避免 HTML 解析产生的多余空白导致漏匹配
     *   - 子串匹配（短语出现在任意位置即可）
     *
     * @param pageId       待检查的页面 ID
     * @param exactPhrases 所有需要精确匹配的原始短语文本（已小写、空白归一化）
     * @return 所有短语均能在页面中找到时返回 true，否则返回 false
     */
    private static boolean matchesAllExactPhrases(int pageId, List<String> exactPhrases) {
        String url = pageIdToUrl.get(pageId);
        if (url == null) return false;
        WebPageData data = webPageDataMap.get(url);
        if (data == null) return false;

        // 将正文与标题拼接，统一转小写并压缩空白，作为搜索空间
        String bodyText  = data.getPageText()  != null ? data.getPageText()  : "";
        String titleText = data.getPageTitle() != null ? data.getPageTitle() : "";
        String fullText  = normalizeWhitespace(bodyText + " " + titleText)
                .toLowerCase(Locale.ROOT);

        for (String phrase : exactPhrases) {
            // exactPhrases 里的文本在 parseQuery() 时已经做过小写+空白归一化
            if (!fullText.contains(phrase)) {
                return false;   // 有一个短语不匹配，直接排除该页面
            }
        }
        return true;
    }

    /**
     * 将字符串中连续的空白字符（包括换行、制表符）压缩为单个空格，
     * 并去除首尾空白。
     */
    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // ======================================================================
    // 查询解析（分词 → 去停用词 → 词干化）
    // ======================================================================

    /**
     * 将原始查询字符串解析为 {@link ParsedQuery}。
     *
     * 示例：
     *   输入  : computer science "information retrieval"
     *   stemmedPhrases : [["comput"], ["scienc"], ["inform", "retriev"]]
     *   exactPhrases   : ["information retrieval"]   ← 仅引号内的原始文本
     *
     * 双引号内的短语同时产生：
     *   (a) 词干化后的短语（放入 stemmedPhrases，供倒排索引检索候选集）
     *   (b) 原始文本（放入 exactPhrases，供后续精确子串匹配）
     *
     * 非引号的单词只产生 (a)，不参与精确过滤。
     */
    private static ParsedQuery parseQuery(String raw) {
        List<List<String>> stemmedPhrases = new ArrayList<>();
        List<String>       exactPhrases   = new ArrayList<>();

        Matcher m = Pattern.compile("\"([^\"]+)\"|([A-Za-z0-9]+)")
                .matcher(raw.toLowerCase(Locale.ROOT));

        while (m.find()) {
            if (m.group(1) != null) {
                // ── 引号短语 ─────────────────────────────────────────────
                String originalText = normalizeWhitespace(m.group(1)); // 保留原文（已小写）
                if (!originalText.isEmpty()) {
                    // (b) 存入精确短语列表，用于后续 pageText 过滤
                    exactPhrases.add(originalText);
                }
                // (a) 词干化，用于倒排索引候选集检索
                List<String> stemmed = stemTokens(originalText);
                if (!stemmed.isEmpty()) {
                    stemmedPhrases.add(stemmed);
                }
            } else {
                // ── 普通单词 ─────────────────────────────────────────────
                String token = m.group(2);
                if (!stopWords.contains(token)) {
                    String stem = porter.stripAffixes(token);
                    if (stem != null && !stem.isBlank()) {
                        stemmedPhrases.add(Collections.singletonList(stem));
                    }
                }
            }
        }
        return new ParsedQuery(stemmedPhrases, exactPhrases);
    }

    /** 对一段文本做分词 + 去停用词 + 词干化，返回词干列表。 */
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

        // 按正文词频取前 MAX_KW 个关键词
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

    // ── 结果数据传输对象 ───────────────────────────────────────────────────
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
    // 轻量级 JSON 序列化（无外部依赖）
    // ======================================================================

    /**
     * 将搜索结果列表序列化为以下格式的 JSON 字符串：
     * {
     *   "count": 5,
     *   "results": [
     *     {
     *       "pageId": 1,
     *       "url": "...",
     *       "title": "...",
     *       "score": 3.141593,
     *       "lastModified": "2024-01-01 12:00:00",
     *       "pageSize": 4096,
     *       "topKeywords": [{"term":"java","tf":8}, ...],
     *       "childLinks": ["https://...", ...]
     *     }, ...
     *   ]
     * }
     */
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

            // 关键词列表
            sb.append("\"topKeywords\":[");
            for (int j = 0; j < r.topKeywords.size(); j++) {
                if (j > 0) sb.append(',');
                Map.Entry<String, Integer> kw = r.topKeywords.get(j);
                sb.append("{\"term\":").append(js(kw.getKey()))
                        .append(",\"tf\":").append(kw.getValue()).append('}');
            }
            sb.append("],");

            // 子链接列表
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

    /** 将 Java 字符串转换为合法的 JSON 字符串字面量，处理转义字符。 */
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

    /** 从 URL 查询字符串中提取指定参数的值（URL 解码）。 */
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
    // 停用词加载（与 Indexer 保持一致）
    // ======================================================================

    private static Set<String> loadStopWords() {
        Set<String> sw = new HashSet<>();
        // 优先从 classpath 加载（打包后路径）
        try (InputStream in = Webserver.class.getClassLoader()
                .getResourceAsStream("stopwords.txt")) {
            if (in != null) { readSW(in, sw); return sw; }
        } catch (IOException ignored) {}
        // 回退到源码目录（本地开发）
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