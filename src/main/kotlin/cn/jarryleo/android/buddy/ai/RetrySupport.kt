package cn.jarryleo.android.buddy.ai

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/**
 * AI 请求重试支持。
 *
 * 设计动机:插件通过 `java.net.http.HttpClient` 调 AI 服务,偶发遇到
 *  - SocketTimeout / ConnectTimeout / UnknownHost(网络抖动)
 *  - HttpTimeoutException(HttpClient 自身超时)
 *  - `access denied java.net.URLPermission`(JVM 安全策略拦截)
 *  - 5xx / 429 服务端暂时不可用
 * 等瞬时错误,本可重试成功;但现状是一次失败就报给用户、停止本轮对话,体验差。
 *
 * 行为:
 *  - 默认 3 次重试(即总共 4 次尝试)。
 *  - 退避:第 1 次重试前 1.5s,第 2 次 3s,第 3 次 4.5s(累加,每次 +1.5s)。
 *  - 可重试异常见 [isRetryable]。
 *  - HTTP 4xx(除 408/429)不重试 — 那是请求参数错,重试无意义。
 *  - 每次重试触发 [onRetry] 回调,driver 用它在 UI 推送"⏳ 网络异常,第 N 次重试(X 秒后)…"。
 *
 * 注意:
 *  - 重试一律跑在调用方的线程上(同步 sleep)。对一次性请求 OK;对 SSE 流式请求,
 *    只在「请求未开始流」前才安全重试(流已经开始则中途重试语义模糊,
 *    所以 [execute] 设计为流式调用方传入 `allowRetry: () -> Boolean` 钩子,流开始后由它主动拒绝后续重试)。
 *  - 用户已点 Stop 时,重试也应终止 — 同样通过该钩子实现。
 */
object RetrySupport {

    /** 默认重试总次数(不含首次尝试)。3 次 → 4 次尝试,总退避 1.5+3+4.5=9s。 */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /** 退避基数(秒)。第 N 次重试前 sleep = BASE * N。 */
    private const val BACKOFF_BASE_SECONDS: Long = 1L

    /** 步进(秒)。实际等待 = BACKOFF_BASE_SECONDS * (attempt + 1) 累加。 */
    private const val BACKOFF_STEP_SECONDS: Long = 1L

    /**
     * 同步执行带重试的阻塞调用。
     *
     * @param label     操作名(用于日志/UI 提示,如 "AI chat")
     * @param action    实际请求;首次与每次重试都会重新调一次(注意幂等性)
     * @param onRetry   (第几次重试 1-based, 等待秒数) -> Unit
     * @param shouldContinue  () -> Boolean,返回 false 时立即终止(用户点 Stop 时调用)
     * @return action 的结果
     * @throws Exception 当所有重试都失败时抛最后一次的异常
     */
    fun <T> execute(
        label: String,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        onRetry: (Int, Long) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
        action: () -> T,
    ): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            if (!shouldContinue()) {
                throw InterruptedException("RetrySupport[$label]: 用户已停止")
            }
            try {
                return action()
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxRetries) break
                if (!isRetryable(e)) break
                val waitSeconds = computeBackoffSeconds(attempt)
                onRetry(attempt + 1, waitSeconds)
                if (!sleepInterruptible(waitSeconds * 1000L, shouldContinue)) {
                    throw InterruptedException("RetrySupport[$label]: 用户在重试等待中已停止")
                }
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("RetrySupport[$label]: unknown failure")
    }

    /**
     * 是否可重试的异常判定。
     * - 网络/超时/DNS/连接异常:可重试
     * - SecurityException / AccessControlException(JVM 策略拦截 URLPermission):可重试
     * - IllegalStateException 携带 "HTTP 5xx" / "HTTP 429" / "HTTP 408" 关键词:可重试
     * - IllegalStateException 携带 "HTTP 4xx" 其他状态:不可重试(请求侧错)
     * - 其它:不可重试
     */
    fun isRetryable(e: Throwable): Boolean {
        return when (e) {
            is SocketTimeoutException,
            is ConnectException,
            is UnknownHostException,
            is HttpConnectTimeoutException,
            is HttpTimeoutException,
            is IOException -> true
            is SecurityException -> true
            is IllegalStateException -> {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("HTTP 5") -> true
                    msg.contains("HTTP 429") -> true
                    msg.contains("HTTP 408") -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    /**
     * 累加退避:第 0 次重试(1-based 第 1 次) → 1.5s,第 1 次重试 → 3s,第 2 次重试 → 4.5s。
     * 即 `BASE * (attempt + 1) + STEP * attempt` 秒。
     */
    fun computeBackoffSeconds(attemptIndex: Int): Long =
        BACKOFF_BASE_SECONDS * (attemptIndex + 1) + BACKOFF_STEP_SECONDS * attemptIndex

    /**
     * 可被打断的 sleep:每隔 100ms 醒一次检查 shouldContinue,
     * 避免用户在等待重试的 4.5s 期间点了 Stop 但要等满 4.5s 才生效。
     */
    private fun sleepInterruptible(millis: Long, shouldContinue: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (!shouldContinue()) return false
            try {
                Thread.sleep(100L.coerceAtMost(deadline - System.currentTimeMillis()))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return shouldContinue()
    }
}
