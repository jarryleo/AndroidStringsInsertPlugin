package cn.jarryleo.insert_strings.sheets

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.GoogleUtils
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.ValueRange
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

object SheetsManager {
    private const val APPLICATION_NAME = "InsertStrings Plugin"
    private const val CLIENT_SECRET_FILE_NAME = "client_secret.json"
    private const val TOKENS_DIR_NAME = ".idea/insertStrings/tokens"
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 60_000
    private const val READ_TIMEOUT_MS = 60_000
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val SCOPES = listOf(SheetsScopes.SPREADSHEETS)

    private val cachedCredentials = mutableMapOf<String, Credential>()

    private val transport: NetHttpTransport by lazy { buildTransport() }

    private fun buildTransport(): NetHttpTransport {
        val builder = NetHttpTransport.Builder()
            .trustCertificates(GoogleUtils.getCertificateTrustStore())
        val proxy = resolveIdeProxy()
        if (proxy != null) {
            builder.setProxy(proxy)
        }
        return builder.build()
    }

    private fun resolveIdeProxy(): Proxy? {
        return try {
            ProxySelector.getDefault()
                .select(URI("https://www.googleapis.com"))
                .firstOrNull { it.type() != Proxy.Type.DIRECT }
        } catch (e: Exception) {
            null
        }
    }

    private fun timeoutInitializer(extra: HttpRequestInitializer? = null): HttpRequestInitializer =
        HttpRequestInitializer { request ->
            extra?.initialize(request)
            request.connectTimeout = CONNECT_TIMEOUT_MS
            request.readTimeout = READ_TIMEOUT_MS
        }

    private fun settings(project: Project): SheetsSettingsState =
        SheetsSettingsService.getInstance(project).state

    private fun clientSecretInputStream(): InputStream? {
        return SheetsManager::class.java.getResourceAsStream("/META-INF/$CLIENT_SECRET_FILE_NAME")
    }

    private fun tokensDirectory(project: Project): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, TOKENS_DIR_NAME)
    }

    fun isConfigured(project: Project): Boolean {
        return clientSecretInputStream() != null
    }

    @Synchronized
    private fun authorize(project: Project): Credential {
        if (SwingUtilities.isEventDispatchThread()) {
            throw IllegalStateException("Authorization must not be called on the EDT.")
        }

        val inputStream = clientSecretInputStream()
            ?: throw IllegalStateException(
                "Google Sheets credentials file not found in plugin resources."
            )

        val tokensDir = tokensDirectory(project)
            ?: throw IllegalStateException("Unable to determine project base path for storing tokens.")
        if (!tokensDir.exists()) {
            tokensDir.mkdirs()
        }

        val cacheKey = tokensDir.absolutePath
        val cached = cachedCredentials[cacheKey]
        if (cached != null && cached.accessToken != null && (cached.expiresInSeconds == null || cached.expiresInSeconds > 60)) {
            return cached
        }

        val clientSecrets = inputStream.use { stream ->
            GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(stream))
        }

        val flow = GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(FileDataStoreFactory(tokensDir))
            .setAccessType("offline")
            .setRequestInitializer(timeoutInitializer())
            .build()

        // 优先使用已保存的 token/refresh token，避免每次重新弹浏览器
        flow.loadCredential("user")?.let { stored ->
            if (stored.accessToken != null || stored.refreshToken != null) {
                cachedCredentials[cacheKey] = stored
                return stored
            }
        }

        val callback = startCallbackServer()
        val actualPort = callback.server.address.port
        val redirectUri = "http://$LOOPBACK_HOST:$actualPort"

        try {
            val authorizationUrl = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .build()

            SwingUtilities.invokeLater {
                BrowserUtil.browse(authorizationUrl)
            }

            val code = try {
                callback.codeFuture.get(180, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                requestAuthorizationCodeFromUser(authorizationUrl)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }

            val tokenResponse = try {
                flow.newTokenRequest(code)
                    .setRedirectUri(redirectUri)
                    .execute()
            } catch (e: java.io.IOException) {
                throw java.io.IOException(
                    "Failed to exchange authorization code with Google: ${e.message}. " +
                        "If you are behind a firewall/VPN, configure the proxy in " +
                        "IDE Settings > Appearance & Behavior > System Settings > HTTP Proxy.",
                    e
                )
            }

            val credential = flow.createAndStoreCredential(tokenResponse, "user")
            cachedCredentials[cacheKey] = credential
            return credential
        } finally {
            callback.server.stop(0)
        }
    }

    private data class CallbackServer(
        val server: HttpServer,
        val codeFuture: CompletableFuture<String>
    )

    private fun startCallbackServer(): CallbackServer {
        val codeFuture = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), 0)

        server.createContext("/") { exchange ->
            val params = parseQueryParams(exchange.requestURI.query)
            val code = params["code"]
            val error = params["error"]

            val html = when {
                code != null -> renderCallbackPage(
                    status = "success",
                    title = "Authorization Successful",
                    subtitle = "InsertStrings has been granted access to your Google Sheets.",
                    detail = "You can now close this tab and return to the IDE.",
                    accent = "#16a34a"
                )
                error != null -> renderCallbackPage(
                    status = "error",
                    title = "Authorization Failed",
                    subtitle = "Google returned an error and no access was granted.",
                    detail = escapeHtml(error),
                    accent = "#dc2626"
                )
                else -> renderCallbackPage(
                    status = "waiting",
                    title = "Waiting for Authorization",
                    subtitle = "Complete the sign-in flow in this browser to continue.",
                    detail = "This page will update automatically.",
                    accent = "#2563eb"
                )
            }

            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()

            if (code != null && !codeFuture.isDone) {
                codeFuture.complete(code)
            } else if (error != null && !codeFuture.isDone) {
                codeFuture.completeExceptionally(
                    java.io.IOException("Authorization failed: $error")
                )
            }
        }
        server.executor = null
        server.start()

        return CallbackServer(server, codeFuture)
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&")
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun renderCallbackPage(
        status: String,
        title: String,
        subtitle: String,
        detail: String,
        accent: String
    ): String {
        val icon = when (status) {
            "success" -> """<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>"""
            "error" -> """<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6L6 18"/></svg>"""
            else -> """<svg class="spin" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.2-8.5"/></svg>"""
        }
        val badgeClass = if (status == "waiting") "badge spin-badge" else "badge"
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>$title</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                min-height: 100vh;
                display: flex;
                align-items: center;
                justify-content: center;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: #1f2937;
                -webkit-font-smoothing: antialiased;
            }
            .card {
                width: 420px;
                max-width: calc(100vw - 48px);
                background: #ffffff;
                border-radius: 20px;
                padding: 48px 40px 40px;
                text-align: center;
                box-shadow: 0 24px 60px rgba(0, 0, 0, 0.28);
                animation: rise .5s cubic-bezier(.2,.8,.2,1) both;
            }
            @keyframes rise { from { opacity: 0; transform: translateY(18px) scale(.98); } to { opacity: 1; transform: none; } }
            .$badgeClass {
                width: 84px;
                height: 84px;
                border-radius: 50%;
                margin: 0 auto 28px;
                display: flex;
                align-items: center;
                justify-content: center;
                background: $accent;
                box-shadow: 0 12px 28px ${accent}55;
            }
            .badge svg { width: 44px; height: 44px; }
            .spin { animation: rot 1s linear infinite; }
            @keyframes rot { to { transform: rotate(360deg); } }
            h1 {
                font-size: 22px;
                font-weight: 700;
                margin-bottom: 14px;
                letter-spacing: -.2px;
            }
            .subtitle {
                font-size: 15px;
                line-height: 1.55;
                color: #4b5563;
                margin-bottom: 22px;
            }
            .detail {
                font-size: 13px;
                color: #6b7280;
                background: #f3f4f6;
                border-radius: 10px;
                padding: 12px 16px;
                word-break: break-word;
            }
            .footer {
                margin-top: 30px;
                font-size: 12.5px;
                color: #9ca3af;
                display: flex;
                align-items: center;
                justify-content: center;
                gap: 7px;
            }
            .dot { width: 7px; height: 7px; border-radius: 50%; background: $accent; }
            .brand { font-weight: 600; color: #6b7280; }
        </style>
        </head>
        <body>
            <div class="card">
                <div class="$badgeClass">$icon</div>
                <h1>$title</h1>
                <p class="subtitle">$subtitle</p>
                <div class="detail">$detail</div>
                <div class="footer"><span class="dot"></span><span class="brand">InsertStrings</span> &middot; Google Sheets</div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun requestAuthorizationCodeFromUser(authorizationUrl: String): String {
        val codeRef = AtomicReference<String>()
        SwingUtilities.invokeAndWait {
            Messages.showInfoMessage(
                "A browser window was opened for Google authorization.\n\n" +
                        "If the plugin did not receive the authorization automatically, " +
                        "please copy the authorization code from the browser and paste it below.\n\n" +
                        "Authorization URL:\n$authorizationUrl",
                "Google Sheets Authorization"
            )
            val code = Messages.showInputDialog(
                "Paste authorization code:",
                "Google Sheets Authorization",
                Messages.getQuestionIcon()
            )
            codeRef.set(code)
        }
        return codeRef.get()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authorization code is empty.")
    }

    fun invalidateCredential(project: Project) {
        val tokensDir = tokensDirectory(project) ?: return
        cachedCredentials.remove(tokensDir.absolutePath)
    }

    private fun createSheetsService(project: Project): Sheets {
        val credential = authorize(project)
        return Sheets.Builder(transport, JSON_FACTORY, timeoutInitializer(credential))
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    fun testConnection(project: Project, spreadsheetId: String? = null): Result<String> {
        return runCatching {
            authorize(project)
            val targetId = resolveSpreadsheetId(project, spreadsheetId)
            if (targetId.isNotBlank()) {
                val service = createSheetsService(project)
                val spreadsheet = service.spreadsheets().get(targetId).execute()
                "Connected to spreadsheet: ${spreadsheet.properties.title}"
            } else {
                "Authorized successfully. No default spreadsheet configured."
            }
        }
    }

    fun readRange(project: Project, spreadsheetId: String, range: String): Result<List<List<String>>> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        return runCatching {
            val service = createSheetsService(project)
            val response = service.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute()
            val values = response.getValues() ?: emptyList<List<Any?>>()
            val maxCols = values.maxOfOrNull { it.size } ?: 0
            values.map { row ->
                List(maxCols) { index ->
                    row.getOrNull(index)?.toString() ?: ""
                }
            }
        }
    }

    fun writeRange(
        project: Project,
        spreadsheetId: String,
        range: String,
        values: List<List<String>>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to write."))
        return runCatching {
            val service = createSheetsService(project)
            val body = ValueRange().setValues(values)
            service.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    fun appendRange(
        project: Project,
        spreadsheetId: String,
        range: String,
        values: List<List<String>>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to append."))
        return runCatching {
            val service = createSheetsService(project)
            val body = ValueRange().setValues(values)
            service.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    fun clearRange(project: Project, spreadsheetId: String, range: String): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        return runCatching {
            val service = createSheetsService(project)
            service.spreadsheets().values()
                .clear(spreadsheetId, range, ClearValuesRequest())
                .execute()
        }
    }

    /**
     * 在指定范围内搜索第一列匹配 key 的行，返回整行数据。
     */
    fun searchRowByKey(
        project: Project,
        spreadsheetId: String,
        range: String,
        key: String
    ): Result<Pair<Int, List<String>>> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("Search key is empty."))
        return readRange(project, spreadsheetId, range).mapCatching { rows ->
            val index = rows.indexOfFirst { it.firstOrNull()?.trim()?.equals(key, ignoreCase = true) == true }
            if (index == -1) {
                throw NoSuchElementException("Key '$key' not found in sheet.")
            }
            index + 1 to rows[index]
        }
    }

    fun defaultSpreadsheetId(project: Project): String = settings(project).defaultSpreadsheetId

    fun defaultSheetName(project: Project): String = settings(project).defaultSheetName.ifBlank { "Sheet1" }

    fun resolveSpreadsheetId(project: Project, spreadsheetId: String?): String {
        return spreadsheetId?.trim()?.takeIf { it.isNotBlank() } ?: defaultSpreadsheetId(project)
    }
}
