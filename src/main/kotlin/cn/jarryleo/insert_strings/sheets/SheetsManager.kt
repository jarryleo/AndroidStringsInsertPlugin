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
import com.intellij.openapi.ui.Messages
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

object SheetsManager {
    private const val APPLICATION_NAME = "InsertStrings Plugin"
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val SCOPES = listOf(SheetsScopes.SPREADSHEETS)

    private var cachedCredential: Credential? = null

    private val transport: NetHttpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private val settings: SheetsSettingsState
        get() = SheetsSettingsService.getInstance().state

    private fun isConfigured(): Boolean {
        return settings.credentialsJsonPath.isNotBlank() && settings.tokensDirectoryPath.isNotBlank()
    }

    @Synchronized
    private fun authorize(): Credential {
        val cached = cachedCredential
        if (cached != null && cached.accessToken != null && (cached.expiresInSeconds == null || cached.expiresInSeconds > 60)) {
            return cached
        }

        if (!isConfigured()) {
            throw IllegalStateException("Please configure Google Sheets credentials and tokens directory first.")
        }

        if (SwingUtilities.isEventDispatchThread()) {
            throw IllegalStateException("Authorization must not be called on the EDT.")
        }

        val credentialsFile = File(settings.credentialsJsonPath)
        if (!credentialsFile.exists()) {
            throw IllegalStateException("Credentials file not found: ${settings.credentialsJsonPath}")
        }

        val tokensDir = File(settings.tokensDirectoryPath)
        if (!tokensDir.exists()) {
            tokensDir.mkdirs()
        }

        val clientSecrets = FileInputStream(credentialsFile).use { stream ->
            GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(stream))
        }

        val flow = GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(FileDataStoreFactory(tokensDir))
            .setAccessType("offline")
            .build()

        // 使用 127.0.0.1 避免 localhost 被解析到 IPv6 导致回调丢失
        val receiver = LocalServerReceiver.Builder()
            .setHost("127.0.0.1")
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
        cachedCredential = credential
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

    fun invalidateCredential() {
        cachedCredential = null
    }

    private fun createSheetsService(): Sheets {
        return Sheets.Builder(transport, JSON_FACTORY, authorize())
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    fun testConnection(spreadsheetId: String? = null): Result<String> {
        return runCatching {
            authorize()
            val targetId = resolveSpreadsheetId(spreadsheetId)
            if (targetId.isNotBlank()) {
                val service = createSheetsService()
                val spreadsheet = service.spreadsheets().get(targetId).execute()
                "Connected to spreadsheet: ${spreadsheet.properties.title}"
            } else {
                "Authorized successfully. No default spreadsheet configured."
            }
        }
    }

    fun readRange(spreadsheetId: String, range: String): Result<List<List<String>>> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        return runCatching {
            val service = createSheetsService()
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
        spreadsheetId: String,
        range: String,
        values: List<List<String>>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to write."))
        return runCatching {
            val service = createSheetsService()
            val body = ValueRange().setValues(values)
            service.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    fun appendRange(
        spreadsheetId: String,
        range: String,
        values: List<List<String>>
    ): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        if (values.isEmpty()) return Result.failure(IllegalArgumentException("No values to append."))
        return runCatching {
            val service = createSheetsService()
            val body = ValueRange().setValues(values)
            service.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }

    fun clearRange(spreadsheetId: String, range: String): Result<Unit> {
        if (spreadsheetId.isBlank()) return Result.failure(IllegalArgumentException("Spreadsheet ID is empty."))
        return runCatching {
            val service = createSheetsService()
            service.spreadsheets().values()
                .clear(spreadsheetId, range, ClearValuesRequest())
                .execute()
        }
    }

    /**
     * 在指定范围内搜索第一列匹配 key 的行，返回整行数据。
     */
    fun searchRowByKey(
        spreadsheetId: String,
        range: String,
        key: String
    ): Result<Pair<Int, List<String>>> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("Search key is empty."))
        return readRange(spreadsheetId, range).mapCatching { rows ->
            val index = rows.indexOfFirst { it.firstOrNull()?.trim()?.equals(key, ignoreCase = true) == true }
            if (index == -1) {
                throw NoSuchElementException("Key '$key' not found in sheet.")
            }
            index + 1 to rows[index]
        }
    }

    fun defaultSpreadsheetId(): String = settings.defaultSpreadsheetId

    fun defaultSheetName(): String = settings.defaultSheetName.ifBlank { "Sheet1" }

    fun resolveSpreadsheetId(spreadsheetId: String?): String {
        return spreadsheetId?.trim()?.takeIf { it.isNotBlank() } ?: defaultSpreadsheetId()
    }
}
