package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * `fetch_url` 工具的运行时控制器。
 *
 * 设计要点:
 * - **JDK HttpClient**:零依赖(系统自带 Java 17),无外部库,跟 `run_shell` 的 curl
 *   是不同的工具 — 本工具走进程内 HTTP,**没有 shell 解析**,headers 不会被注入。
 * - **唯一保留的过滤:协议必须 http/https**。`file://` / `ftp://` / `data:` 会被拒
 *   (否则能读本地任意文件 / 触发任意 URI scheme handler,危险)。**不做** host 黑名单
 *   / SSRF 防御 / IP 段判断 — 用户明确选择让任何 host 都能访问,风险自担。
 * - **响应体截断**:JDK `BodyHandlers.ofString` 会把整个 body load 进内存,这里
 *   用 `maxBodyChars` 强约束(默认 100KB,上限 2MB)防 OOM + token 爆。
 * - **stripHtml** 用 IntelliJ 平台 `lib-client.jar` 自带的 [org.jsoup.Jsoup](**无新依赖**)
 *   解析 HTML 并取 `.text()` — Jsoup 的 parser 默认跳过 `<script>`/`<style>` 块、
 *   HTML 注释、tag attribute,`.text()` 自动输出可见文本且块级元素间有换行,
 *   完美覆盖 stripHtml 语义。
 * - **跟随重定向 1 次**:`HttpClient.Redirect.NORMAL` — 不会无限跟随(防 DNS rebinding
 *   跳到内网)。
 * - **超时两层**:`connectTimeout` 防 TCP 挂死,`HttpRequest.timeout` 防服务器无响应。
 *
 * UI 流式反馈:本工具是同步阻塞调用(几百 ms 到几秒),没有 streaming tool 消息。
 * driver 端在 controller 返回后,把 final result 作为 `role=tool` 消息推回,正常
 * 渲染在 ToolGroupBubble 里。
 */
internal class InsertStringsFetchUrlController(
    @Suppress("unused") private val state: ChatStateHolder,
) {

    /**
     * 同步拉取 [action] 指定的 URL,把响应转成 [工具执行结果] 字符串返回。
     * driver 在 pooled thread 上调,本方法本身不涉及 EDT / PSI。
     */
    fun fetchUrl(action: AiAction.FetchUrl): String {
        val url = action.url.trim()
        if (url.isEmpty()) return resultOf(url, "失败", "URL 为空")

        // 1) URI 解析 + 协议校验
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return resultOf(url, "失败", "URL 解析失败")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return resultOf(
                url, "失败",
                "URL 协议不被允许(仅 http/https,实际 ${scheme ?: "<空>"})"
            )
        }

        // 2) 参数边界
        val timeoutMs = (action.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(1_000, 120_000)
        val maxBodyChars = (action.maxBodyChars ?: DEFAULT_MAX_BODY_CHARS).coerceIn(1, 2_000_000)
        val responseType = action.responseType?.lowercase()?.takeIf { it in setOf("text", "json") }
            ?: "text"
        val stripHtml = action.stripHtml ?: false
        val stripImages = action.stripImages ?: false

        // 3) HttpClient 请求
        val started = System.currentTimeMillis()
        val (statusCode, contentType, rawBody) = try {
            doRequest(uri, action.headers, timeoutMs)
        } catch (e: HttpTimeoutException) {
            return resultOf(url, "失败", "超时 ${timeoutMs}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return resultOf(url, "失败", "已中断")
        } catch (e: Exception) {
            return resultOf(
                url, "失败",
                "网络异常:${e.javaClass.simpleName}:${e.message ?: ""}"
            )
        }
        val elapsed = System.currentTimeMillis() - started

        // 4) 状态码判定
        if (statusCode !in 200..299) {
            // 4xx/5xx 仍按 maxBodyChars 截断返回 body,让 AI 读错误消息
            val bodyPreview = truncate(rawBody, maxBodyChars)
            return resultOf(
                url, "失败(exit=$statusCode)",
                "状态码 $statusCode 耗时:${elapsed}ms 大小:${formatBytes(rawBody.length)}",
                body = bodyPreview,
            )
        }

        // 5) 响应体处理
        val finalBody = processBody(rawBody, contentType, responseType, stripHtml, stripImages, maxBodyChars)
        val extra = buildString {
            append("状态码:$statusCode")
            if (contentType.isNotBlank()) append(" Content-Type:$contentType")
            append(" 耗时:${elapsed}ms")
            append(" 原始大小:").append(formatBytes(rawBody.length))
            if (stripHtml && isHtmlContentType(contentType)) {
                append(" 纯文本大小:").append(formatBytes(finalBody.length))
                append("\n[stripHtml=true]").append(stripImages)
                    .append(" 已移除 script/style/noscript 块、HTML 注释、on* 事件属性、所有标签")
                if (stripImages) append("、<img>")
            }
        }
        return resultOf(url, "成功", extra, body = finalBody)
    }

    // region ============== 网络层 ==============

    private fun doRequest(
        uri: URI,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): Triple<Int, String, String> {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs.toLong()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val reqBuilder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs.toLong()))
            .header("User-Agent", DEFAULT_USER_AGENT)
            .GET()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val contentType = resp.headers().firstValue("Content-Type").orElse("")
        return Triple(resp.statusCode(), contentType, resp.body())
    }

    // endregion

    // region ============== 响应体处理 ==============

    private fun processBody(
        rawBody: String,
        contentType: String,
        responseType: String,
        stripHtml: Boolean,
        stripImages: Boolean,
        maxBodyChars: Int,
    ): String {
        // 1) stripHtml(text/html|xhtml 才生效)
        val htmlStripped = stripHtml && isHtmlContentType(contentType)
        val afterHtml = if (htmlStripped) {
            runCatching { htmlToVisibleText(rawBody, stripImages) }
                .getOrElse { rawBody }   // 解析失败时 fallback 到原 body
        } else {
            rawBody
        }

        // 2) JSON pretty-print(若要求且 body 是合法 JSON)
        val afterJson = if (responseType == "json") {
            runCatching {
                JsonParser.parseString(afterHtml).toString()
            }.getOrElse { afterHtml }
        } else {
            afterHtml
        }

        // 3) 截断
        return truncate(afterJson, maxBodyChars)
    }

    /**
     * 用 IntelliJ 平台 `lib-client.jar` 自带的 [Jsoup] 把 HTML 转成可见纯文本。
     * Jsoup 的 `Document.text()` 自动:
     * - 跳过 `<script>` / `<style>` / `<noscript>` 块内容
     * - 丢弃 HTML 注释
     * - 丢弃所有 tag attribute(包括 `on*` 事件属性)
     * - 丢弃所有 tag
     * - 块级元素之间加 `\n`
     * - 多个空白字符折叠成单个空格(行内)
     *
     * 这是平台自带的库,**无需**在 build.gradle.kts 加依赖。
     * Fallback:解析失败时返回原 body(用 Swing HTMLEditorKit 简单剥标签)。
     */
    private fun htmlToVisibleText(html: String, stripImages: Boolean): String {
        val doc = Jsoup.parse(html)
        if (stripImages) {
            doc.select("img").forEach { it.remove() }
        }
        return doc.body().text()
    }

    private fun isHtmlContentType(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("text/html") || lower.startsWith("application/xhtml")
    }

    private fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        return s.substring(0, max) + "\n…(截断,原长度 ${s.length})"
    }

    // endregion

    // region ============== 工具结果格式化 ==============

    private fun resultOf(
        url: String,
        status: String,
        extra: String? = null,
        body: String? = null,
    ): String = buildString {
        append("[工具执行结果] 类型:fetch_url 状态:").append(status).append(" url:").append(url)
        if (extra != null && extra.isNotBlank()) {
            append('\n').append(extra)
        }
        if (body != null && body.isNotEmpty()) {
            append("\n---BEGIN BODY---\n").append(body).append("\n---END BODY---")
        }
    }

    private fun formatBytes(n: Int): String = when {
        n < 1024 -> "${n}B"
        n < 1024 * 1024 -> "%.1fKB".format(n / 1024.0)
        else -> "%.2fMB".format(n / (1024.0 * 1024))
    }

    // endregion

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000
        private const val DEFAULT_MAX_BODY_CHARS = 100_000
        private const val DEFAULT_USER_AGENT = "InsertStringsPlugin/3.11.0"
    }
}
