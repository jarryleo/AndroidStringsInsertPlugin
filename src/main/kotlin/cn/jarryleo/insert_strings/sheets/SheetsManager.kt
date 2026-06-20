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
import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.GridProperties
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.InsertDimensionRequest
import com.google.api.services.sheets.v4.model.RepeatCellRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.TextFormat
import com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest
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
        values: List<List<String>>,
        rowTextColors: List<List<String?>>? = null
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
            if (!rowTextColors.isNullOrEmpty()) {
                val (_, grid) = parseA1Range(service, spreadsheetId, range)
                    ?: throw IllegalArgumentException("Range '$range' refers to a sheet not found in spreadsheet.")
                val cells = matrixColorsToCells(
                    baseRow0 = grid.startRowIndex ?: 0,
                    baseCol0 = grid.startColumnIndex ?: 0,
                    matrix = rowTextColors
                )
                applyTextColors(service, spreadsheetId, grid.sheetId ?: 0, cells).getOrThrow()
            }
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
     * 文本匹配模式(与 StringsService.TextMatchType 对应,反查 sheet/strings.xml 共用)。
     */
    enum class TextMatchType { EXACT, CONTAINS, REGEX }

    /**
     * [findRowsByText] 单条结果。
     * @param rowNumber 命中行号(1-based,含表头偏移)
     * @param columnIndex 命中所在列的 0-based 列号(整行匹配时为 -1)
     * @param columnName 命中所在列的表头名(整行匹配时为空)
     */
    data class TextSearchResult(
        val rowNumber: Int,
        val columnIndex: Int,
        val columnName: String,
        val matchedText: String,
        val row: List<String>
    )

    /**
     * 反查:在 sheet 中按文本搜索行。
     * 整表读一次,在内存中按 [matchType] 匹配,返回 [limit] 条结果。
     * 适合单 sheet 几千行内的常见场景;更大表应配合 column 限定避免扫描所有单元格。
     *
     * @param column 限定列名(与表头精确匹配),为 null 时搜索所有列
     */
    fun findRowsByText(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        text: String,
        column: String?,
        matchType: TextMatchType,
        caseSensitive: Boolean,
        limit: Int
    ): Result<List<TextSearchResult>> {
        if (spreadsheetId.isBlank()) {
            return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        }
        if (sheetName.isBlank()) {
            return Result.failure(IllegalArgumentException("Sheet name is empty."))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Search text is empty."))
        }
        return runCatching {
            val readResult = readSheet(project, spreadsheetId, sheetName)
            val rows = readResult.getOrThrow()
            if (rows.isEmpty()) return@runCatching emptyList<TextSearchResult>()

            val header = rows.first()
            // 列筛选:匹配 column 名(忽略大小写)对应的列号
            val targetColIndices: List<Int> = if (column.isNullOrBlank()) {
                header.indices.toList()
            } else {
                val idx = header.indexOfFirst { it.equals(column, ignoreCase = true) }
                if (idx < 0) {
                    throw IllegalArgumentException("Column '$column' not found in sheet header.")
                }
                listOf(idx)
            }

            val needle = if (caseSensitive) text else text.lowercase()
            val regex = if (matchType == TextMatchType.REGEX) {
                runCatching { Regex(text) }.getOrNull()
            } else null
            val matches: (String) -> Boolean = buildRowMatcher(text, needle, matchType, caseSensitive, regex)

            val results = mutableListOf<TextSearchResult>()
            // 从第 2 行开始扫描(第 1 行为表头)
            for (rowIdx in 1 until rows.size) {
                val row = rows[rowIdx]
                for (colIdx in targetColIndices) {
                    val cell = row.getOrNull(colIdx).orEmpty()
                    if (matches(cell)) {
                        results.add(
                            TextSearchResult(
                                rowNumber = rowIdx + 1, // 1-based
                                columnIndex = colIdx,
                                columnName = header.getOrNull(colIdx).orEmpty(),
                                matchedText = cell,
                                row = row
                            )
                        )
                        if (results.size >= limit) return@runCatching results
                        break // 一行只记一次
                    }
                }
            }
            results
        }
    }

    private fun buildRowMatcher(
        rawText: String,
        needle: String,
        matchType: TextMatchType,
        caseSensitive: Boolean,
        regex: Regex?
    ): (String) -> Boolean {
        return when (matchType) {
            TextMatchType.EXACT -> if (caseSensitive) {
                fun m(v: String): Boolean = v == rawText
                ::m
            } else {
                fun m(v: String): Boolean = v.lowercase() == needle
                ::m
            }
            TextMatchType.CONTAINS -> if (caseSensitive) {
                fun m(v: String): Boolean = v.contains(rawText)
                ::m
            } else {
                fun m(v: String): Boolean = v.lowercase().contains(needle)
                ::m
            }
            TextMatchType.REGEX -> {
                val r = regex ?: throw IllegalArgumentException("Invalid regex: $rawText")
                fun m(v: String): Boolean = r.containsMatchIn(v)
                ::m
            }
        }
    }

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
        values: List<String>,
        rowTextColors: List<String?>? = null
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
            if (!rowTextColors.isNullOrEmpty()) {
                val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                    ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")
                val cells = linearColorsToCells(
                    baseRow0 = rowNumber - 1,
                    baseCol0 = 0,
                    colors = rowTextColors,
                    isRow = true
                )
                applyTextColors(service, spreadsheetId, sheetId, cells).getOrThrow()
            }
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
        values: List<String>,
        rowTextColors: List<String?>? = null
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
            if (!rowTextColors.isNullOrEmpty()) {
                val cells = linearColorsToCells(
                    baseRow0 = rowNumber - 1,
                    baseCol0 = 0,
                    colors = rowTextColors,
                    isRow = true
                )
                applyTextColors(service, spreadsheetId, sheetId, cells).getOrThrow()
            }
        }
    }

    /**
     * 在工作表末尾追加一行数据。
     */
    fun appendRow(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        values: List<String>,
        rowTextColors: List<String?>? = null
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to append."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val body = ValueRange().setValues(listOf(values))
            val response = service.spreadsheets().values()
                .append(spreadsheetId, "$safeSheet!A1", body)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()
            if (!rowTextColors.isNullOrEmpty()) {
                val updatedRange = response.updates?.updatedRange
                    ?: throw IllegalStateException("Append response missing updatedRange.")
                val (sheetNameResolved, grid) = parseA1Range(service, spreadsheetId, updatedRange, safeSheet)
                    ?: throw IllegalStateException("Cannot parse appended range '$updatedRange'.")
                val cells = linearColorsToCells(
                    baseRow0 = grid.startRowIndex ?: 0,
                    baseCol0 = grid.startColumnIndex ?: 0,
                    colors = rowTextColors,
                    isRow = true
                )
                applyTextColors(service, spreadsheetId, grid.sheetId ?: 0, cells).getOrThrow()
            }
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

    // ==================== 冻结行列 ====================

    /**
     * 冻结表格顶部指定行数，使其在滚动时保持可见。
     * rowCount 为 0 表示取消冻结行。
     */
    fun freezeRows(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        rowCount: Int
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (rowCount < 0) return Result.failure(IllegalArgumentException("Frozen row count must be >= 0."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")
            val updateRequest = Request().setUpdateSheetProperties(
                UpdateSheetPropertiesRequest()
                    .setProperties(
                        SheetProperties()
                            .setSheetId(sheetId)
                            .setGridProperties(
                                GridProperties()
                                    .setFrozenRowCount(rowCount)
                            )
                    )
                    .setFields("gridProperties.frozenRowCount")
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(updateRequest)))
                .execute()
        }
    }

    /**
     * 冻结表格左侧指定列数，使其在滚动时保持可见。
     * columnCount 为 0 表示取消冻结列。
     */
    fun freezeColumns(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        columnCount: Int
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnCount < 0) return Result.failure(IllegalArgumentException("Frozen column count must be >= 0."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")
            val updateRequest = Request().setUpdateSheetProperties(
                UpdateSheetPropertiesRequest()
                    .setProperties(
                        SheetProperties()
                            .setSheetId(sheetId)
                            .setGridProperties(
                                GridProperties()
                                    .setFrozenColumnCount(columnCount)
                            )
                    )
                    .setFields("gridProperties.frozenColumnCount")
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(updateRequest)))
                .execute()
        }
    }

    // ==================== 填充/清除背景色 ====================

    /**
     * 在 A1 范围上填充背景色。color 支持 hex("#RGB"/"#RRGGBB",大小写不敏感)或命名色
     * (red/green/blue/yellow/orange/purple/pink/gray/grey/white/black/light_gray/dark_gray/brown/cyan/magenta)。
     * 仅修改背景色,不动其他格式。range 若不含 sheet 前缀则用 [defaultSheetName]。
     */
    fun fillColor(
        project: Project,
        spreadsheetId: String,
        range: String,
        color: String,
        defaultSheetName: String? = null
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (range.isBlank()) return Result.failure(IllegalArgumentException("Range is empty."))
        if (color.isBlank()) return Result.failure(IllegalArgumentException("Color is empty."))
        val parsedColor = parseColor(color)
            ?: return Result.failure(IllegalArgumentException("Invalid color '$color'. Use hex like #FF0000 or named (red/green/blue/...)."))
        return runCatching {
            val service = createSheetsService(project)
            val (sheetName, gridRange) = parseA1Range(service, spreadsheetId, range, defaultSheetName)
                ?: throw IllegalArgumentException("Range '$range' refers to a sheet not found in spreadsheet.")
            val request = Request().setRepeatCell(
                RepeatCellRequest()
                    .setRange(gridRange)
                    .setCell(
                        CellData().setUserEnteredFormat(
                            CellFormat().setBackgroundColor(parsedColor)
                        )
                    )
                    .setFields("userEnteredFormat.backgroundColor")
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(request)))
                .execute()
            Unit
        }
    }

    /**
     * 清除 A1 范围的背景色,恢复默认(透明)。不影响其他格式。range 若不含 sheet 前缀则用 [defaultSheetName]。
     */
    fun clearColor(
        project: Project,
        spreadsheetId: String,
        range: String,
        defaultSheetName: String? = null
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (range.isBlank()) return Result.failure(IllegalArgumentException("Range is empty."))
        return runCatching {
            val service = createSheetsService(project)
            val (sheetName, gridRange) = parseA1Range(service, spreadsheetId, range, defaultSheetName)
                ?: throw IllegalArgumentException("Range '$range' refers to a sheet not found in spreadsheet.")
            // 用空 CellFormat 写入并限定 fields,只清背景色,其他格式保留
            val request = Request().setRepeatCell(
                RepeatCellRequest()
                    .setRange(gridRange)
                    .setCell(CellData().setUserEnteredFormat(CellFormat()))
                    .setFields("userEnteredFormat.backgroundColor")
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(request)))
                .execute()
            Unit
        }
    }

    /**
     * 在 A1 范围上设置文字色(只改 userEnteredFormat.textFormat.foregroundColor,不动其他格式)。
     * range 若不含 sheet 前缀则用 [defaultSheetName]。覆盖单格、行、列或任意矩形范围。
     */
    fun setTextColor(
        project: Project,
        spreadsheetId: String,
        range: String,
        textColor: String,
        defaultSheetName: String? = null
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (range.isBlank()) return Result.failure(IllegalArgumentException("Range is empty."))
        if (textColor.isBlank()) return Result.failure(IllegalArgumentException("Text color is empty."))
        val parsedColor = parseColor(textColor)
            ?: return Result.failure(IllegalArgumentException("Invalid textColor '$textColor'. Use hex like #FF0000 or named (red/green/blue/...)."))
        return runCatching {
            val service = createSheetsService(project)
            val (sheetName, gridRange) = parseA1Range(service, spreadsheetId, range, defaultSheetName)
                ?: throw IllegalArgumentException("Range '$range' refers to a sheet not found in spreadsheet.")
            val request = Request().setRepeatCell(
                RepeatCellRequest()
                    .setRange(gridRange)
                    .setCell(
                        CellData().setUserEnteredFormat(
                            CellFormat().setTextFormat(
                                TextFormat().setForegroundColor(parsedColor)
                            )
                        )
                    )
                    .setFields("userEnteredFormat.textFormat.foregroundColor")
            )
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(request)))
                .execute()
            Unit
        }
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
        values: List<String>,
        columnTextColors: List<String?>? = null
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
            if (!columnTextColors.isNullOrEmpty()) {
                val cells = linearColorsToCells(
                    baseRow0 = 0,
                    baseCol0 = columnIndex - 1,
                    colors = columnTextColors,
                    isRow = false
                )
                applyTextColors(service, spreadsheetId, sheetId, cells).getOrThrow()
            }
        }
    }

    /**
     * 在工作表末尾追加一个新列。values 第一个元素为表头，其余为该列各行的值。
     */
    fun appendColumn(
        project: Project,
        spreadsheetId: String,
        sheetName: String,
        values: List<String>,
        columnTextColors: List<String?>? = null
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
            if (!columnTextColors.isNullOrEmpty()) {
                val cells = linearColorsToCells(
                    baseRow0 = 0,
                    baseCol0 = newColumnIndex - 1,
                    colors = columnTextColors,
                    isRow = false
                )
                applyTextColors(service, spreadsheetId, sheetId, cells).getOrThrow()
            }
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
        values: List<String>,
        columnTextColors: List<String?>? = null
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (sheetName.isBlank()) return Result.failure(IllegalArgumentException("Sheet name is empty."))
        if (columnIndex < 1) return Result.failure(IllegalArgumentException("Column index must be >= 1."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to write."))
        return runCatching {
            val service = createSheetsService(project)
            val safeSheet = sanitizeSheetName(sheetName)
            writeColumnValues(service, spreadsheetId, safeSheet, columnIndex, values)
            if (!columnTextColors.isNullOrEmpty()) {
                val sheetId = resolveSheetId(service, spreadsheetId, safeSheet)
                    ?: throw IllegalStateException("Sheet '$safeSheet' not found in spreadsheet.")
                val cells = linearColorsToCells(
                    baseRow0 = 0,
                    baseCol0 = columnIndex - 1,
                    colors = columnTextColors,
                    isRow = false
                )
                applyTextColors(service, spreadsheetId, sheetId, cells).getOrThrow()
            }
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

    /**
     * 把 A1 表示的范围(支持 "Sheet1!A1:D10" / "A1" / "Sheet1!B2")解析为 (sheetName, GridRange)。
     * GridRange 端点为半开区间(startIndex 含,endIndex 不含),行/列 0-based。
     * 若 range 不含 sheet 前缀,且传入了 defaultSheetName,则用 defaultSheetName;否则仅靠 resolveSheetId 匹配。
     * @return null 表示工作表找不到
     */
    private fun parseA1Range(
        service: Sheets,
        spreadsheetId: String,
        range: String,
        defaultSheetName: String? = null
    ): Pair<String, GridRange>? {
        val (rawSheet, a1) = splitSheetAndA1(range)
        val targetSheet = rawSheet ?: defaultSheetName
            ?: return null
        val sheetId = resolveSheetId(service, spreadsheetId, targetSheet)
            ?: return null
        val (startRow, startCol, endRow, endCol) = parseA1Cells(a1)
        val grid = GridRange()
            .setSheetId(sheetId)
            .setStartRowIndex(startRow)
            .setEndRowIndex(endRow)
            .setStartColumnIndex(startCol)
            .setEndColumnIndex(endCol)
        return targetSheet to grid
    }

    /**
     * 逐格上色:每个三元组为 (row0, col0, color),其中坐标是 0-based 半开区间的起点。
     * 所有 cell 通过一次 batchUpdate 提交,每格一个 RepeatCellRequest 限定 fields=textFormat.foregroundColor。
     * 颜色无效立刻返回 Failure,不再继续。
     */
    private fun applyTextColors(
        service: Sheets,
        spreadsheetId: String,
        sheetId: Int,
        cells: List<Triple<Int, Int, String>>
    ): Result<Unit> {
        val requests = mutableListOf<Request>()
        for ((row0, col0, color) in cells) {
            val parsed = parseColor(color)
                ?: return Result.failure(IllegalArgumentException("Invalid color '$color' at row $row0 col $col0."))
            val grid = GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(row0)
                .setEndRowIndex(row0 + 1)
                .setStartColumnIndex(col0)
                .setEndColumnIndex(col0 + 1)
            requests.add(
                Request().setRepeatCell(
                    RepeatCellRequest()
                        .setRange(grid)
                        .setCell(
                            CellData().setUserEnteredFormat(
                                CellFormat().setTextFormat(
                                    TextFormat().setForegroundColor(parsed)
                                )
                            )
                        )
                        .setFields("userEnteredFormat.textFormat.foregroundColor")
                )
            )
        }
        if (requests.isEmpty()) return Result.success(Unit)
        return runCatching {
            service.spreadsheets()
                .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(requests))
                .execute()
            Unit
        }
    }

    /**
     * 把行/列 op 的 1D 颜色列表展开为逐格三元组。
     * 写操作完成后调用,baseRow0/baseCol0 是写入区域的 0-based 起点。
     */
    private fun linearColorsToCells(
        baseRow0: Int,
        baseCol0: Int,
        colors: List<String?>,
        isRow: Boolean
    ): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>(colors.size)
        for ((idx, color) in colors.withIndex()) {
            if (color.isNullOrBlank()) continue
            val (r, c) = if (isRow) baseRow0 to (baseCol0 + idx) else (baseRow0 + idx) to baseCol0
            out.add(Triple(r, c, color))
        }
        return out
    }

    /**
     * 把 2D 颜色矩阵展开为逐格三元组(用于 write 操作的 rowTextColors)。
     */
    private fun matrixColorsToCells(
        baseRow0: Int,
        baseCol0: Int,
        matrix: List<List<String?>>
    ): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        for ((r, row) in matrix.withIndex()) {
            for ((c, color) in row.withIndex()) {
                if (color.isNullOrBlank()) continue
                out.add(Triple(baseRow0 + r, baseCol0 + c, color))
            }
        }
        return out
    }

    /**
     * 拆分 "Sheet1!A1:D10" → ("Sheet1", "A1:D10")。sheet 名带单引号包裹时去壳。
     * 找不到 '!' 时返回 (null, range)。对特殊 sheet 名("'1.0.3.0'!A1")也兼容。
     */
    private fun splitSheetAndA1(range: String): Pair<String?, String> {
        val trimmed = range.trim()
        val bangIdx = trimmed.indexOf('!')
        if (bangIdx < 0) return null to trimmed
        val rawSheet = trimmed.substring(0, bangIdx).trim()
        val a1 = trimmed.substring(bangIdx + 1).trim()
        val sheet = rawSheet.trim('\'')
        return sheet to a1
    }

    /**
     * 解析单元格引用字符串(如 "A1", "A1:D10", "B2")为 0-based 半开区间
     * (startRow, startCol, endRow, endCol)。endRow/endCol 不含(GridRange 语义)。
     * 单格 "A1" → (0,0,1,1);"A1:D10" → (0,0,10,4);"C5" → (4,2,5,3)。
     * 起始可省略行号或列号(冒号单侧)时按另一端推断;解析失败则抛 IAE。
     */
    private fun parseA1Cells(a1: String): IntArray {
        val s = a1.replace("$", "").trim()
        if (s.isEmpty()) throw IllegalArgumentException("Empty A1 cell reference.")
        val parts = s.split(':')
        val (startRow, startCol) = parseA1Cell(parts[0])
        val (endRow, endCol) = when (parts.size) {
            1 -> startRow + 1 to startCol + 1
            2 -> {
                val (eR, eC) = parseA1Cell(parts[1])
                // 单侧省略时,使用起始端的对应值
                val resolvedEndRow = if (parts[1].any { it.isDigit() }) eR else startRow
                val resolvedEndCol = if (parts[1].any { it.isLetter() }) eC else startCol
                resolvedEndRow + 1 to resolvedEndCol + 1
            }
            else -> throw IllegalArgumentException("Invalid A1 range '$a1'.")
        }
        return intArrayOf(startRow, startCol, endRow, endCol)
    }

    /**
     * 解析单格(如 "A1"、"AB12")为 (rowIndex0Based, colIndex0Based)。
     */
    private fun parseA1Cell(ref: String): Pair<Int, Int> {
        val s = ref.trim()
        var i = 0
        var col = 0
        while (i < s.length && s[i].isLetter()) {
            col = col * 26 + (s[i].uppercaseChar() - 'A' + 1)
            i++
        }
        if (i == 0 || i == s.length) {
            throw IllegalArgumentException("Invalid A1 cell reference '$ref'.")
        }
        val row = s.substring(i).toInt()
        if (row < 1) throw IllegalArgumentException("Row must be >= 1 in '$ref'.")
        return (row - 1) to (col - 1)
    }

    /**
     * 把颜色字符串解析为 Google Sheets [Color](R/G/B 0-1 浮点)。
     * 支持 hex("#RGB"/"#RRGGBB",大小写不敏感)与命名色;不匹配返回 null。
     */
    private fun parseColor(raw: String): Color? {
        val s = raw.trim()
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            if (hex.length != 3 && hex.length != 6) return null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val r = full.substring(0, 2).toInt(16) / 255f
            val g = full.substring(2, 4).toInt(16) / 255f
            val b = full.substring(4, 6).toInt(16) / 255f
            return Color().setRed(r).setGreen(g).setBlue(b)
        }
        val named = NAMED_COLORS[s.lowercase()] ?: return null
        val r = (named shr 16) and 0xFF
        val g = (named shr 8) and 0xFF
        val b = named and 0xFF
        return Color().setRed(r / 255f).setGreen(g / 255f).setBlue(b / 255f)
    }

    // 常见命名色 RGB
    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "red" to 0xFF0000.toInt(),
        "green" to 0x00FF00,
        "blue" to 0x0000FF,
        "yellow" to 0xFFFF00,
        "orange" to 0xFFA500.toInt(),
        "purple" to 0x800080,
        "pink" to 0xFFC0CB.toInt(),
        "gray" to 0x808080,
        "grey" to 0x808080,
        "white" to 0xFFFFFF,
        "black" to 0x000000,
        "light_gray" to 0xD3D3D3.toInt(),
        "light_grey" to 0xD3D3D3.toInt(),
        "dark_gray" to 0x404040,
        "dark_grey" to 0x404040,
        "brown" to 0xA52A2A.toInt(),
        "cyan" to 0x00FFFF,
        "magenta" to 0xFF00FF
    )
}
