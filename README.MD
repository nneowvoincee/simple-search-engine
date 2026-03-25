# 网络爬虫与索引器项目概览

本项目实现了一个基于 BFS 的简单网络爬虫，能够从指定入口开始抓取网页，并支持增量更新（通过检查 Last-Modified 头判断是否需要重新抓取）。抓取的数据持久化存储在 MapDB 数据库中。此外，项目包含一个索引器框架（Indexer），为后续构建全文检索功能打下基础。

---

## 核心模块说明

### 1. **Crawler（主控类）**
- **职责**：协调整个爬取流程，管理多线程（通过 worker 数量定义，当前为 10），维护已抓取页面的内存与持久化存储。
- **主要方法**：
    - `fetchPagesBFS(String entryPoint, int k)`：以 BFS 方式抓取最多 k 个页面，自动检查页面是否修改，更新数据库，并移除不在本次 BFS 结果中的旧页面。
    - `fetch(String url)`：使用 `WebPageExtractor` 获取单个页面内容。
    - `printAllData()`：打印数据库中所有已存储页面的 URL 和 ID（调试用）。
- **存储**：使用 MapDB 的 `HTreeMap` 存储 `WebPageData` 对象，键为 URL。

### 2. **WebPageExtractor（网页解析器）**
- **职责**：使用 Jsoup 获取网页，提取超链接、纯文本内容、最后修改时间等。
- **关键输出**：
    - 网页中的绝对链接列表（`Vector<String>`）
    - 网页正文纯文本（`extractContent()`）
    - 页面最后修改时间（`Instant` 格式）

### 3. **WebPageData（数据实体）**
- **职责**：存储单个网页的元数据和内容，作为数据库中的值对象。
- **字段**：
    - `url`：网页 URL（也是 MapDB 的键）
    - `pageText`：网页纯文本内容
    - `subLink`：该页面中提取的子链接
    - `lastModified`：页面最后修改时间（用于增量更新）
    - `pageID`：本次爬取中分配的 ID（会随重新爬取变化）

### 4. **Utils（工具类）**
- **职责**：提供通用辅助方法。
- **主要方法**：
    - `isWebPageURL(String url)`：验证 URL 是否为有效 HTTP/HTTPS 页面。
    - `isModified(String url, Instant storedLastModified)`：通过发送 HEAD 请求检查页面是否已被修改（基于 Last-Modified 头）。

### 5. **Indexer（索引器框架）**
- **当前状态**：尚在开发中，仅有框架，未来将负责：
    - 加载停用词表（`stopwords.txt`）
    - 使用 Porter 词干提取器进行词干化
    - 为爬取的页面建立倒排索引，并存入 MapDB

### 6. **Porter（词干提取器）**
- **职责**：实现 Porter 词干提取算法，将单词还原为词干形式（用于后续索引）。

### 7. **stopwords.txt**
- **内容**：英文停用词列表，在索引阶段会被加载并过滤。

---

## 主要功能流程

1. **初始化**  
   创建 `Crawler` 实例时，会打开或创建一个 MapDB 数据库文件，并注册关闭钩子以确保数据安全写入。

2. **BFS 爬取**  
   调用 `fetchPagesBFS(entryPoint, k)`：
    - 从入口 URL 开始，使用队列进行 BFS。
    - 对每个新 URL，先检查数据库中是否已有且未修改（通过 `Utils.isModified` 判断）。若未修改，则复用旧数据；否则重新抓取。
    - 为每个成功抓取的页面分配一个自增 ID（`pageID`），并存入数据库。
    - 抓取完成后，清理数据库中不在本次 BFS 结果中的旧页面，并更新所有页面中子链接的引用（仅保留也在本次抓取结果中的链接）。

3. **数据持久化**  
   所有 `WebPageData` 对象通过 MapDB 持久化到文件（如 `database.db`），支持后续重启后继续使用。

4. **索引（待实现）**  
   `Indexer` 类将从 `stopwords.txt` 读取停用词，利用 Porter 算法处理词干，最终构建倒排索引并存入 MapDB 中。

---

## 依赖组件

- **Jsoup**：用于 HTML 解析和 HTTP 请求（在 `WebPageExtractor` 中使用）。
- **MapDB**：提供嵌入式数据库支持，用于持久化存储页面数据和后续索引。
- **Java 标准库**：包括 `java.net.http`、`java.time`、集合框架等。

---

## 注意事项

- 当前 `Crawler` 中的 `numWorker` 变量已定义但未实际用于多线程，如需并发爬取需进一步实现线程池逻辑。
- `Indexer` 目前仅有骨架，需要补全停用词加载、词干化和索引构建逻辑。
- 增量更新依赖服务器正确返回 `Last-Modified` 头，若服务器不支持，则每次都会重新抓取。

---

## 后续扩展方向

- 实现多线程并发爬取，提高抓取效率。
- 完成 `Indexer` 的索引构建功能，包括停用词过滤、词干化、倒排表存储。
- 添加查询接口，支持关键词检索并返回相关页面。
- 引入 robots.txt 遵守策略，增强爬虫的合规性。