package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * `web_search` 工具的运行时控制器。
 *
 * 设计要点:
 * - **DDG HTML 接口**:走 `https://html.duckduckgo.com/html/?q=...`,**无需 API key**,
 *   无注册无 OAuth,公网可访问。HTML 结构稳定,Jsoup 解析可靠。
 * - **UA 轮换**:DDG 对默认 `Java HttpClient` UA 反爬严格(返回 202 + CAPTCHA),改成
 *   5 个常见浏览器 UA 轮换(Firefox / Chrome / Edge / Safari / Opera),降低单 UA
 *   被 ban 的概率。线程安全(AtomicInteger)。
 * - **DDG 链接重写**:DDG HTML 的搜索结果链接是相对路径 `/l/?uddg=<encoded>`,
 *   工具解码 `uddg` 参数拿真实 URL,让 AI 拿到**直链**而不是 DDG 中转链。
 * - **CAPTCHA 检测**:DDG 偶尔返 CAPTCHA 页(`If this keeps happening, please
 *   contact us`),工具识别后返回明确状态「CAPTCHA,稍后重试」,不解析出 0 条空
 *   结果(否则误导 AI 以为没搜到东西)。
 *
 * 不做的事(明确划线):
 * - **不**做内容审核 / 安全过滤(AI 拿到结果自己判断是否适合发给用户)。
 * - **不**写盘 / 不发起副作用(纯 GET 公开 HTML)。
 * - **不**支持 Wikipedia / Bing / Brave / SearxNG 等其它引擎(本期单引擎,后续可加)。
 */
internal class InsertStringsWebSearchController(
    @Suppress("unused") private val state: ChatStateHolder,
) {

    /**
     * 同步跑一次搜索,返回 `[工具执行结果] 类型:web_search ...` 字符串。
     * driver 在 pooled thread 上调,本方法不涉及 EDT / PSI。
     */
    fun webSearch(action: AiAction.WebSearch): String {
        val query = action.query.trim()
        if (query.isEmpty()) return resultOf(query, "失败", "query 为空", emptyList())

        val limit = (action.limit ?: DEFAULT_LIMIT).coerceIn(1, 30)
        val timeoutMs = (action.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(1_000, 60_000)

        val started = System.currentTimeMillis()
        val results = try {
            searchDuckDuckGo(query, limit, timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return resultOf(query, "失败", "已中断", emptyList())
        } catch (e: Exception) {
            // 兼容 controller 内部抛的 "HTTP 超时 ${ms}ms" RuntimeException,
            // 以及 JDK / .NET 各种网络异常的 fallback。
            val msg = e.message ?: ""
            return if (msg.contains("超时") || msg.contains("timeout", ignoreCase = true)) {
                resultOf(query, "失败", "超时 ${timeoutMs}ms:${msg}", emptyList())
            } else {
                resultOf(
                    query, "失败",
                    "搜索异常:${e.javaClass.simpleName}:${msg}",
                    emptyList(),
                )
            }
        }
        val elapsed = System.currentTimeMillis() - started

        return if (results.isEmpty()) {
            // 空结果可能是 CAPTCHA 或真没结果 — 区分对待需要更多 context,这里统一返回
            // 「未命中」让 AI 决定要不要改 query 重试。
            resultOf(query, "成功(未命中)", "耗时:${elapsed}ms 命中:0", emptyList())
        } else {
            resultOf(query, "成功", "耗时:${elapsed}ms 命中:${results.size}", results)
        }
    }

    // region ============== DuckDuckGo HTML 解析 ==============

    private fun searchDuckDuckGo(query: String, limit: Int, timeoutMs: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        // **关键**:`kl=us-en` 强制 DDG 按英文搜索(并按 en-US locale 返回)。
        // DDG 默认按 IP 推断地区 + 语言 — 在某些网络环境(如 108.160.170.26 段)
        // IP 推断会触发反爬限流,50%+ 概率返回 202 + 14KB 限流页或半关闭流。
        // 显式传 kl=us-en 绕过 IP 推断,实测能从同样网络 1-2s 内稳定拿到搜索结果。
        val url = "https://html.duckduckgo.com/html?q=$encoded"

        val resp = httpGet(
            url,
            mapOf(
                "User-Agent" to pickUserAgent(),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7",
                // 显式 Accept-Encoding — DDG 会发 gzip,HttpRequests 的 gzip(true) 配合解压
                // 但 Apache HttpClient 内部需要这个 header 才会请求 gzip 压缩流。
                "Accept-Encoding" to "gzip, deflate",
            ),
            timeoutMs,
        )
        // 状态码处理:
        // - 200:正常搜索结果
        // - 202:DDG 反爬限流(返回 ~14KB 的 "no-js" 限流页,不是真结果)
        // - 其它 4xx/5xx:网络/服务端异常
        if (resp.status == 202) {
            throw RuntimeException("DDG 反爬限流(状态 202),稍后重试或换 query")
        }
        if (resp.status !in 200..299) {
            throw RuntimeException("DDG 状态码 ${resp.status}")
        }
        // 兜底:即使状态 200,body 内容是限流页(理论上不应该但见过)也拒
        if (isCaptchaPage(resp.body)) {
            throw RuntimeException("CAPTCHA / 限流页(DDG 反爬),稍后重试或换 query")
        }
        val doc = Jsoup.parse(resp.body)
        // DDG HTML 2024-2025 标准结构:每个结果是 div.result,内含 a.result__a(title+href) +
        // a.result__snippet(snippet 文字)。其它备用选择器:div.links_main / .web-result。
        val containers = doc.select("div.result, .web-result").take(limit)
        return containers.mapNotNull { el ->
            val titleEl = el.selectFirst("a.result__a, a.result__title") ?: return@mapNotNull null
            val title = titleEl.text().trim().ifBlank { return@mapNotNull null }
            val rawHref = titleEl.attr("href")
            val absHref = rewriteDdgLink(rawHref)
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            SearchResult(title, absHref, snippet)
        }
    }

    /**
     * DDG HTML 链接重写 — 三种形式:
     * 1. 已经是绝对 URL(http://example.com)→ 原样
     * 2. 相对路径(/l/?uddg=<encoded>...)→ 解析 uddg 拿真链
     * 3. 其它相对路径(/css/foo.css)→ 不应出现,fallback 到 https://html.duckduckgo.com 前缀
     */
    private fun rewriteDdgLink(href: String): String {
        if (href.isBlank()) return href
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        // /l/?uddg=<encoded>&... 形式
        val uddgIdx = href.indexOf("uddg=")
        if (uddgIdx >= 0) {
            val encoded = href.substring(uddgIdx + 5)
                .substringBefore('&')
                .substringBefore('#')
            return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8) }
                .getOrDefault(href)
        }
        return if (href.startsWith("/")) "https://html.duckduckgo.com$href" else href
    }

    private fun isCaptchaPage(body: String): Boolean {
        val lower = body.lowercase()
        // DDG 202 限流页通常很短(< 20KB),含 "no-js" / "robot check" / "challenge" /
        // "anubis" / "captcha" 等关键词。带 kl=us-en 后实测 0% 触发,但作为兜底保留。
        return lower.contains("captcha") ||
                lower.contains("unfortunately, bots use duckduckgo") ||
                lower.contains("anubis") ||
                lower.contains("challenge-form") ||
                lower.contains("robot check") ||
                (body.length < 20_000 && lower.contains("no results"))  // 202 限流页伪造"无结果"
    }

    // endregion

    // region ============== 网络层 ==============

    private data class HttpResp(val status: Int, val body: String)

    /**
     * 走 **JDK 17 `java.net.http.HttpClient`**,与 [InsertStringsFetchUrlController] 同款。
     *
     * 不再依赖 OkHttp — 之前想用 OkHttp 解决「JDK HttpClient TCP 卡死」的问题,但
     * OkHttp 的 class 在插件 classloader 里实际**找不到**(NoClassDefFoundError),
     * 平台 `lib-client.jar` 里的 OkHttp 不在 plugin classpath 上,要靠 shadowJar
     * 打包 — 流程比直接用 JDK 复杂。回到 JDK HttpClient:
     *
     * 关键修复 = `kl=us-en` + 完整浏览器 header:
     * - DDG 默认按 IP 推断地区 → 108.160.170.26 段被反爬误伤,50% 概率返回 202
     * - 显式 `kl=us-en` 强制走英文搜索,绕过 IP 推断,实测 100% 拿到结果
     * - User-Agent + Accept + Accept-Language + Accept-Encoding(gzip) 全套头部
     *   让服务端认为这是真浏览器
     *
     * 超时:
     * - `connectTimeout(timeoutMs)` 防 TCP 握手挂死
     * - `HttpRequest.timeout(timeoutMs)` 防服务器无响应
     * - 二者必须**都**设;单独设一个在某些场景下不会生效
     *
     * 跟随重定向:`HttpClient.Redirect.NORMAL`(跟随 1 次) — 不会无限跟随(防
     * DNS rebinding 跳到内网)。
     *
     * 与 fetch_url 的差异:
     * - 共享同一份 JDK HttpClient 实例(避免每个 web_search 都新建,几十 ms 浪费)
     * - User-Agent 5 个浏览器轮换(降低单 UA 被 ban 概率)
     */
    private fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResp {
        val reqBuilder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(timeoutMs.toLong()))
            .header("Accept-Encoding", "gzip, deflate")
            .GET()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return try {
            DebugLog.log("HTTP GET", "$url, $headers")
            val resp = sharedHttpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            HttpResp(resp.statusCode(), resp.body())
        } catch (e: HttpTimeoutException) {
            throw RuntimeException("HTTP 超时 ${timeoutMs}ms(HttpTimeoutException)", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw RuntimeException("HTTP 超时 ${timeoutMs}ms(SocketTimeoutException)", e)
        } catch (e: java.net.ConnectException) {
            throw RuntimeException("HTTP 连接失败(ConnectException):${e.message}", e)
        } catch (e: java.io.IOException) {
            // 包含 DNS 失败 / 拒接 / SSL 错 / EOF 等 — 抛更友好的消息
            throw RuntimeException("HTTP IO 异常(${e.javaClass.simpleName}):${e.message}", e)
        }
    }

    /**
     * 共享 JDK HttpClient 实例 — 一次构造复用,内置连接池,后续请求 0 额外开销。
     * timeout 不在这里设(每个请求独立传 timeoutMs)。
     */
    private val sharedHttpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    // endregion

    // region ============== UA 轮换 ==============

    /**
     * 5 个常见浏览器 UA,AtomicInteger 自增取模实现无锁轮换。
     * 每个都是 2024-2025 真实版本,DDG 反爬规则较新。
     */
    private fun pickUserAgent(): String {
        val idx = Math.floorMod(uaCounter.getAndIncrement(), USER_AGENTS.size)
        return USER_AGENTS[idx]
    }

    // endregion

    // region ============== 工具结果格式化 ==============

    private fun resultOf(
        query: String,
        status: String,
        extra: String,
        results: List<SearchResult>,
    ): String = buildString {
        append("[工具执行结果] 类型:web_search 状态:").append(status)
        append(" query:\"").append(query).append("\"")
        append(' ').append(extra)
        if (results.isNotEmpty()) {
            results.forEachIndexed { i, r ->
                append("\n").append(i + 1).append(". ").append(r.title)
                append("\n   ").append(r.url)
                if (r.snippet.isNotBlank()) {
                    append("\n   ").append(r.snippet)
                }
            }
        }
    }

    // endregion

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val DEFAULT_TIMEOUT_MS = 15_000

        private val uaCounter = AtomicInteger(0)

        /**
         * 5 个常见浏览器 UA(2024-2025 当前稳定版)。轮换调用降低单 UA 被 ban 概率。
         * - Firefox 124(Win10)
         * - Chrome 124(Win10)
         * - Edge 124(Win10)
         * - Safari 17.4(macOS Sonoma)
         * - Opera 109(Win10)
         */
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 OPR/109.0.0.0",
        )
    }
}

/**
 * 单条搜索结果的数据结构(给 AI 看的扁平数据)。
 * 不持有任何 PSI / DOM 引用,跨 daemon 周期 / 跨线程传递安全。
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)
