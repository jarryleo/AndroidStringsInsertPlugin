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
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.InsertDimensionRequest
import com.google.api.services.sheets.v4.model.Request
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

    /**
     * 表格中单个工作表的元信息。
     */
    data class SheetInfo(
        val title: String,
        val sheetId: Int,
        val rowCount: Int,
        val columnCount: Int
    )

    /**
     * 获取指定表格文件中所有工作表的名称及尺寸。
     */
    fun listSheetNames(project: Project, spreadsheetId: String): Result<List<SheetInfo>> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        return runCatching {
            val service = createSheetsService(project)
            val spreadsheet = service.spreadsheets().get(spreadsheetId)
                .setIncludeGridData(false)
                .execute()
            spreadsheet.sheets?.map { sheet ->
                val props = sheet.properties
                val grid = props?.gridProperties
                SheetInfo(
                    title = props?.title ?: "",
                    sheetId = props?.sheetId ?: 0,
                    rowCount = grid?.rowCount ?: 0,
                    columnCount = grid?.columnCount ?: 0
                )
            } ?: emptyList()
        }
    }

    /**
     * 读取整个工作表的所有数据。
     */
    fun readSheet(
        project: Project,
        spreadsheetId: String,
        sheetName: String
    ): Result<List<List<String>>> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        val safeSheet = sanitizeSheetName(sheetName)
        return readRange(project, spreadsheetId, "$safeSheet!A1:Z100000")
    }

    /**
     * 精确更新某一行（1-based 行号）。仅写入给定行，不影响其他行。
     */
    fun updateRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        rowNumber: Int,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (rowNumber < 1) return Result.failure(IllegalArgumentException("Row number must be >= 1."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to write."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val endCol = columnIndexToLetter(values.size - 1)
            val range = "$safeSheet!A$rowNumber:$endCol$rowNumber"
            val body = ValueRange().setValues(listOf(values))
            service.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    /**
     * 在指定行号位置插入一个新行，原行及之后的数据整体下移。
     * rowNumber 为 1-based，例如 rowNumber=3 时，原第 3 行及之后内容下移到第 4 行起。
     */
    fun insertRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        rowNumber: Int,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (rowNumber < 1) return Result.failure(IllegalArgumentException("Row number must be >= 1."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to insert."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")

            // 1) 先在目标行位置插入一空行（0-based 索引）
            val insertRequest = Request().setInsertDimension(
                InsertDimensionRequest()
                    .setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("ROWS")
                            .setStartIndex(rowNumber - 1)
                            .setEndIndex(rowNumber)
                    )
                    .setInheritFromBefore(true)
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(insertRequest)))
                .execute()

            // 2) 再把数据写入刚刚插入的空行
            val endCol = columnIndexToLetter(values.size - 1)
            val range = "$safeSheet!A$rowNumber:$endCol$rowNumber"
            val body = ValueRange().setValues(listOf(values))
            service.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    /**
     * 在工作表末尾追加一行数据。
     */
    fun appendRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to append."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val body = ValueRange().setValues(listOf(values))
            service.spreadsheets().values()
                .append(spreadsheetId, "$safeSheet!A1", body)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
        }
    }

    /**
     * 删除指定行（1-based）。后续行整体上移。
     */
    fun deleteRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        rowNumber: Int
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (rowNumber < 1) return Result.failure(IllegalArgumentException("Row number must be >= 1."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")

            val deleteRequest = Request().setDeleteDimension(
                DeleteDimensionRequest()
                    .setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("ROWS")
                            .setStartIndex(rowNumber - 1)
                            .setEndIndex(rowNumber)
                    )
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest)))
                .execute()
        }
    }

    /**
     * 清空指定行的内容（保留空行，不改变行数）。
     */
    fun clearRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        rowNumber: Int,
        columnCount: Int = 26
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (rowNumber < 1) return Result.failure(IllegalArgumentException("Row number must be >= 1."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val endCol = columnIndexToLetter(columnCount - 1)
            val range = "$safeSheet!A$rowNumber:$endCol$rowNumber"
            service.spreadsheets().values()
                .clear(spreadsheetId, range, ClearValuesRequest())
                .execute()
        }
    }

    /**
     * 查找某一行：在 [sheetName] 的第一列中匹配 [key]，返回 1-based 行号与整行内容。
     */
    fun searchRowInSheet(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        key: String
    ): Result<Pair<Int, List<String>>> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("Search key is empty."))
        val safeSheet = sanitizeSheetName(sheetName)
        return searchRowByKey(project, spreadsheetId, "$safeSheet!A1:Z100000", key)
    }

    // ==================== 列操作 ====================

    /**
     * 在指定列号位置插入一个新列，原该列及之后的数据整体右移。
     * columnIndex 为 1-based。values 第一个元素为表头，其余为该列各行的值。
     */
    fun insertColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        columnIndex: Int,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnIndex < 1) return Result.failure(IllegalArgumentException("Column index must be >= 1."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to insert."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")

            val insertRequest = Request().setInsertDimension(
                InsertDimensionRequest()
                    .setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("COLUMNS")
                            .setStartIndex(columnIndex - 1)
                            .setEndIndex(columnIndex)
                    )
                    .setInheritFromBefore(true)
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(insertRequest)))
                .execute()

            writeColumnValues(service, spreadsheetId, safeSheet, columnIndex, values)
        }
    }

    /**
     * 在工作表末尾追加一个新列。values 第一个元素为表头，其余为该列各行的值。
     */
    fun appendColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to append."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")

            val currentColCount = readRange(project, spreadsheetId, "$safeSheet!A1:ZZ1")
                .getOrNull()?.firstOrNull()?.size ?: 0
            val newColumnIndex = currentColCount + 1

            val insertRequest = Request().setInsertDimension(
                InsertDimensionRequest()
                    .setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("COLUMNS")
                            .setStartIndex(currentColCount)
                            .setEndIndex(currentColCount + 1)
                    )
                    .setInheritFromBefore(currentColCount > 0)
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(insertRequest)))
                .execute()

            writeColumnValues(service, spreadsheetId, safeSheet, newColumnIndex, values)
        }
    }

    /**
     * 删除指定列（1-based）。后续列整体左移。
     */
    fun deleteColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        columnIndex: Int
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnIndex < 1) return Result.failure(IllegalArgumentException("Column index must be >= 1."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")

            val deleteRequest = Request().setDeleteDimension(
                DeleteDimensionRequest()
                    .setRange(
                        DimensionRange()
                            .setSheetId(sheetId)
                            .setDimension("COLUMNS")
                            .setStartIndex(columnIndex - 1)
                            .setEndIndex(columnIndex)
                    )
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest)))
                .execute()
        }
    }

    /**
     * 清空指定列的内容但保留空列，不改变列数。
     */
    fun clearColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        columnIndex: Int,
        rowCount: Int = 1000
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnIndex < 1) return Result.failure(IllegalArgumentException("Column index must be >= 1."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val colLetter = columnIndexToLetter(columnIndex - 1)
            val range = "$safeSheet!$colLetter" + "1:$colLetter$rowCount"
            service.spreadsheets().values()
                .clear(spreadsheetId, range, ClearValuesRequest())
                .execute()
        }
    }

    /**
     * 精确更新某一列（1-based）。values 第一个元素为表头，其余为该列各行的值。
     */
    fun updateColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        columnIndex: Int,
        values: List<String>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnIndex < 1) return Result.failure(IllegalArgumentException("Column index must be >= 1."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to write."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            writeColumnValues(service, spreadsheetId, safeSheet, columnIndex, values)
        }
    }

    /**
     * 获取指定工作表的列数（基于第一行的实际列数）。
     */
    fun columnCountOf(
        project: Project,
        spreadsheetId: String,
        sheetName: String
    ): Result<Int> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        return runCatching {
            val safeSheet = sanitizeSheetName(sheetName)
            readRange(project, spreadsheetId, "$safeSheet!A1:ZZ1")
                .getOrNull()?.firstOrNull()?.size ?: 0
        }
    }

    private fun writeColumnValues(
        service: Sheets,
        spreadsheetId: String,
        safeSheet: String,
        columnIndex: Int,
        values: List<String>
    ) {
        val colLetter = columnIndexToLetter(columnIndex - 1)
        val range = "$safeSheet!$colLetter" + "1:$colLetter${values.size}"
        val body = ValueRange().setValues(values.map { listOf(it) })
        service.spreadsheets().values()
            .update(spreadsheetId, range, body)
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private fun resolveSheetId(service: Sheets, spreadsheetId: String, sheetName: String): Int? {
        val spreadsheet = service.spreadsheets().get(spreadsheetId)
            .setIncludeGridData(false)
            .execute()
        return spreadsheet.sheets?.firstOrNull {
            it.properties?.title?.equals(sheetName, ignoreCase = true) == true
        }?.properties?.sheetId
    }

    private fun sanitizeSheetName(sheetName: String): String {
        val trimmed = sheetName.trim()
        if (trimmed.isEmpty()) return "Sheet1"
        // 包含空格或特殊字符时需要用单引号包裹
        return if (trimmed.any { it.isWhitespace() || it in "'!#$%" }) "'$trimmed'" else trimmed
    }

    private fun columnIndexToLetter(index: Int): String {
        if (index < 0) return "A"
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        }
        return sb.toString()
    }
}
