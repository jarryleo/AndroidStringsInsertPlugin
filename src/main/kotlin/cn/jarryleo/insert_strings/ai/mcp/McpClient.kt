package cn.jarryleo.insert_strings.ai.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?
)

data class McpToolResult(
    val success: Boolean,
    val content: String
)

class McpClient(
    private val command: String,
    private val arguments: List<String>,
    private val workingDir: String?,
    private val timeoutSeconds: Long
) : AutoCloseable {

    private val gson = Gson()
    private val requestId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, (String) -> Unit>()

    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readerThread: Thread? = null
    private var initialized = false

    @Synchronized
    fun connect(): Result<Unit> = runCatching {
        if (process?.isAlive == true && initialized) return@runCatching

        close()

        val pb = ProcessBuilder(listOf(command) + arguments)
        workingDir?.takeIf { it.isNotBlank() && File(it).isDirectory }?.let {
            pb.directory(File(it))
        }
        pb.redirectErrorStream(true)
        val newProcess = pb.start()
        process = newProcess
        writer = PrintWriter(OutputStreamWriter(newProcess.outputStream, Charsets.UTF_8), true)
        reader = BufferedReader(InputStreamReader(newProcess.inputStream, Charsets.UTF_8))

        startReaderThread()
        initialize()
    }

    private fun startReaderThread() {
        readerThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader?.readLine() ?: break
                    if (line.isBlank()) continue
                    handleLine(line)
                }
            } catch (_: InterruptedException) {
                // expected on close
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, "McpClient-Reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleLine(line: String) {
        try {
            val json = JsonParser.parseString(line)
            if (!json.isJsonObject) return
            val obj = json.asJsonObject
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asInt
            if (id != null) {
                pending.remove(id)?.invoke(line)
            }
        } catch (_: Exception) {
            // ignore malformed lines
        }
    }

    private fun initialize() {
        val params = JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "InsertStrings")
                addProperty("version", "1.0.0")
            })
        }
        sendRequest("initialize", params, timeoutSeconds = 20).getOrThrow()
        sendNotification("notifications/initialized", JsonObject())
        initialized = true
    }

    fun listTools(): Result<List<McpTool>> = runCatching {
        ensureConnected()
        val response = sendRequest("tools/list", JsonObject()).getOrThrow()
        val root = JsonParser.parseString(response).asJsonObject
        val resultObj = root.getAsJsonObject("result") ?: throw IllegalStateException("No result in tools/list")
        val toolsArray = resultObj.getAsJsonArray("tools") ?: return@runCatching emptyList()
        toolsArray.mapNotNull { parseTool(it) }
    }

    private fun parseTool(element: JsonElement): McpTool? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return McpTool(
            name = name,
            description = obj.get("description")?.takeIf { it.isJsonPrimitive }?.asString,
            inputSchema = obj.getAsJsonObject("inputSchema")
        )
    }

    fun callTool(name: String, arguments: JsonObject): Result<McpToolResult> = runCatching {
        ensureConnected()
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }
        val response = sendRequest("tools/call", params).getOrThrow()
        val root = JsonParser.parseString(response).asJsonObject
        val resultObj = root.getAsJsonObject("result") ?: throw IllegalStateException("No result in tool response")
        val contentArray = resultObj.getAsJsonArray("content")
        val text = contentArray?.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            if (obj.get("type")?.asString == "text") obj.get("text")?.asString else null
        }?.joinToString("\n") ?: ""
        val isError = resultObj.get("isError")?.asBoolean ?: false
        McpToolResult(success = !isError, content = text)
    }

    @Synchronized
    private fun ensureConnected() {
        if (process?.isAlive != true || !initialized) {
            connect().getOrThrow()
        }
    }

    private fun sendRequest(method: String, params: JsonObject, timeoutSeconds: Long = this.timeoutSeconds): Result<String> = runCatching {
        val id = requestId.incrementAndGet()
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }.toString()

        val latch = CountDownLatch(1)
        var response = ""
        pending[id] = { line ->
            response = line
            latch.countDown()
        }
        sendLine(message)
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            pending.remove(id)
            throw IllegalStateException("MCP request timeout: $method")
        }
        response
    }

    private fun sendNotification(method: String, params: JsonObject) {
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }.toString()
        sendLine(message)
    }

    @Synchronized
    private fun sendLine(line: String) {
        val w = writer ?: throw IllegalStateException("MCP client not connected")
        w.println(line)
        if (w.checkError()) {
            throw IllegalStateException("Failed to send MCP message")
        }
    }

    @Synchronized
    override fun close() {
        initialized = false
        readerThread?.interrupt()
        readerThread = null
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                } catch (_: Exception) {
                    p.destroyForcibly()
                }
            }
        }
        process = null
        writer = null
        reader = null
        pending.clear()
    }
}
