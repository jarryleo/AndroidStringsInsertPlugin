package cn.jarryleo.insert_strings.sheets

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
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
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

object SheetsManager {
    private const val APPLICATION_NAME = "InsertStrings Plugin"
    private const val CLIENT_SECRET_FILE_NAME = "client_secret.json"
    private const val TOKENS_DIR_NAME = ".idea/insertStrings/tokens"
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val SCOPES = listOf(SheetsScopes.SPREADSHEETS)

    private val cachedCredentials = mutableMapOf<String, Credential>()

    private val transport: NetHttpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private fun settings(project: Project): SheetsSettingsState =
        SheetsSettingsService.getInstance(project).state

    private fun clientSecretInputStream(): InputStream? {
        return SheetsManager::class.java.getResourceAsStream("/META-INF/$CLIENT_SECRET_FILE_NAME")
    }

    /**
     * 根据 client_secret.json 里注册的 redirect_uri 选择回环地址。
     * Google OAuth 对桌面应用允许动态端口，但授权请求里的 host 必须与控制台注册的
     * redirect_uri 一致（如 http://localhost 或 http://127.0.0.1）。
     */
    private fun resolveLoopbackHost(clientSecrets: GoogleClientSecrets): String {
        val uris = clientSecrets.details.redirectUris.orEmpty()
        return when {
            uris.any { it.startsWith("http://127.0.0.1", ignoreCase = true) } -> "127.0.0.1"
            uris.any { it.startsWith("http://localhost", ignoreCase = true) } -> "localhost"
            else -> "localhost"
        }
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
            .build()

        // 优先使用已保存的 token/refresh token，避免每次重新弹浏览器
        flow.loadCredential("user")?.let { stored ->
            if (stored.accessToken != null || stored.refreshToken != null) {
                cachedCredentials[cacheKey] = stored
                return stored
            }
        }

        val loopbackHost = resolveLoopbackHost(clientSecrets)
        val receiver = LocalServerReceiver.Builder()
            .setHost(loopbackHost)
            .setPort(0)
            .build()

        val redirectUri = receiver.redirectUri
        val authorizationUrl = flow.newAuthorizationUrl()
            .setRedirectUri(redirectUri)
            .build()

        // 使用 IntelliJ 的 BrowserUtil 打开浏览器，兼容插件环境
        SwingUtilities.invokeLater {
            BrowserUtil.browse(authorizationUrl)
        }

        val code = try {
            waitForAuthorizationCode(receiver, timeoutMs = 180_000)
        } catch (e: Exception) {
            // 自动回环失败时，让用户手动粘贴授权码
            requestAuthorizationCodeFromUser(authorizationUrl)
        } finally {
            runCatching { receiver.stop() }
        }

        val tokenResponse = flow.newTokenRequest(code)
            .setRedirectUri(redirectUri)
            .execute()

        val credential = flow.createAndStoreCredential(tokenResponse, "user")
        cachedCredentials[cacheKey] = credential
        return credential
    }

    private fun waitForAuthorizationCode(receiver: LocalServerReceiver, timeoutMs: Long): String {
        val future = CompletableFuture<String>()
        val thread = Thread {
            try {
                future.complete(receiver.waitForCode())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        thread.isDaemon = true
        thread.start()
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException(
                "Authorization timed out. The browser did not redirect back to the plugin. " +
                        "You can paste the authorization code manually in the next dialog."
            )
        }
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
        return Sheets.Builder(transport, JSON_FACTORY, authorize(project))
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
