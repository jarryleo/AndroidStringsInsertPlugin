package cn.jarryleo.insert_strings.ai

import cn.jarryleo.insert_strings.ai.AiAction.SheetsOperation
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 工具定义集合:把 [AiAction] 类型映射为 OpenAI / Anthropic 的 function calling schema。
 *
 * 工具列表设计原则:
 * 1. 每个 [AiAction] 子类对应一个 tool,模型通过原生 function calling 调用。
 * 2. `task_complete` 是唯一的「合法终止」信号:模型不调用它,系统就持续驱动工具循环。
 * 3. 复杂工具的详细用法(枚举值/约束/示例)由 `load_tool_doc` 按需注入,主 prompt 保持精简。
 */
object ToolDefinitions {

    /**
     * 每次 AI 调用时携带的 Google Sheets 上下文快照。
     * 注入到 sheets_operation / find_rows_by_text 的 description 里,
     * 让 AI 在调用前能直接看到「当前默认 sheet 是哪个、表格里还有哪些 sheet 可用」,
     * 避免它猜错 sheet(尤其是同名相似/带点带空格的 sheet)。
     */
    data class SheetContext(
        val defaultSheetName: String?,
        val availableSheets: List<String>,
    )

    /**
     * OpenAI / OpenAI 兼容协议的 tools 数组(放在 request body 的 `tools` 字段)。
     * 每次构建都重新生成,这样 [sheetContext] 能把当前 defaultSheetName / availableSheets
     * 拼到 sheets_operation / find_rows_by_text 的 description 里,AI 一眼看到当前工作表名;
     * [projectBase] 拼到所有文件操作工具的 description 后面,让 AI 知道相对路径怎么传。
     */
    fun openAiTools(
        sheetContext: SheetContext = SheetContext(null, emptyList()),
        projectBase: String? = null
    ): JsonArray = buildOpenAiTools(sheetContext, projectBase)

    /** Anthropic Messages API 的 tools 数组(放在 request body 的 `tools` 字段)。 */
    fun anthropicTools(
        sheetContext: SheetContext = SheetContext(null, emptyList()),
        projectBase: String? = null
    ): JsonArray = buildAnthropicTools(sheetContext, projectBase)

    // 2026.x 移除:openAiToolsQuoteEntry / anthropicToolsQuoteEntry 之前给 Ask AI 弹框
    // 用的精简工具集(只 strings.xml + replace_selection)。现在 Ask AI 与主面板权限一致,
    // 统一调用 [openAiTools] / [anthropicTools] 全集。保留旧 API 名称会导致误用,
    // 故直接删掉(无外部调用方,grep 已确认)。

    /** 工具名 → JSON Schema 的 properties 引用,供 driver 在解析 tool_call.arguments 时复用。 */
    val toolNames: List<String> = listOf(
        TOOL_INSERT_STRINGS,
        TOOL_UPDATE_STRING,
        TOOL_DELETE_STRING,
        TOOL_QUERY_KEYS,
        TOOL_READ_STRING,
        TOOL_FIND_KEYS_BY_TEXT,
        TOOL_SHEETS_OPERATION,
        TOOL_FIND_ROWS_BY_TEXT,
        TOOL_GET_EDITOR_FILE,
        TOOL_READ_FILE,
        TOOL_READ_FILES,
        TOOL_EDIT_FILE,
        TOOL_CREATE_FILE,
        TOOL_DELETE_FILE,
        TOOL_MOVE_FILE,
        TOOL_SEARCH_IN_FILES,
        TOOL_FIND_REFERENCES,
        TOOL_LIST_FILES,
        TOOL_FILE_INFO,
        TOOL_TODO_LIST,
        TOOL_TODO_ADD,
        TOOL_TODO_UPDATE,
        TOOL_TODO_DELETE,
        TOOL_CURRENT_TIME,
        TOOL_ASK_USER,
        TOOL_LOAD_TOOL_DOC,
        TOOL_REPLACE_SELECTION,
        TOOL_RUN_SHELL,
        TOOL_READ_DIAGNOSTICS,
        TOOL_TASK_COMPLETE,
    )

    const val TOOL_INSERT_STRINGS = "insert_strings"
    const val TOOL_UPDATE_STRING = "update_string"
    const val TOOL_DELETE_STRING = "delete_string"
    const val TOOL_QUERY_KEYS = "query_keys"
    const val TOOL_READ_STRING = "read_string"
    const val TOOL_FIND_KEYS_BY_TEXT = "find_keys_by_text"
    const val TOOL_SHEETS_OPERATION = "sheets_operation"
    const val TOOL_FIND_ROWS_BY_TEXT = "find_rows_by_text"
    const val TOOL_GET_EDITOR_FILE = "get_editor_file"
    const val TOOL_READ_FILE = "read_file"
    const val TOOL_READ_FILES = "read_files"
    const val TOOL_EDIT_FILE = "edit_file"
    const val TOOL_CREATE_FILE = "create_file"
    const val TOOL_DELETE_FILE = "delete_file"
    const val TOOL_MOVE_FILE = "move_file"
    const val TOOL_SEARCH_IN_FILES = "search_in_files"
    const val TOOL_FIND_REFERENCES = "find_references"
    const val TOOL_LIST_FILES = "list_files"
    const val TOOL_FILE_INFO = "file_info"
    const val TOOL_TODO_LIST = "todo_list"
    const val TOOL_TODO_ADD = "todo_add"
    const val TOOL_TODO_UPDATE = "todo_update"
    const val TOOL_TODO_DELETE = "todo_delete"
    const val TOOL_CURRENT_TIME = "current_time"
    const val TOOL_REPLACE_SELECTION = "replace_selection"
    const val TOOL_ASK_USER = "ask_user"
    const val TOOL_LOAD_TOOL_DOC = "load_tool_doc"
    const val TOOL_RUN_SHELL = "run_shell"
    const val TOOL_READ_DIAGNOSTICS = "read_diagnostics"
    const val TOOL_TASK_COMPLETE = "task_complete"

    // region 工具描述文案(主 prompt 引用,这里集中维护)
    // 设计原则:每个 description 压到 ≤ 80 字符,只留「这一句话必须知道的 + 指针」。
    // 详细枚举/约束/示例 → load_tool_doc("<name>") 加载。
    // 这套精简是 2026.x 优化,显著降低每轮 request body 的 tokens。

    private const val DESC_INSERT_STRINGS =
        "向 strings.xml 插入/全量覆盖翻译。" +
            "translations 必须含 values 并覆盖目标模块全部 xmlFiles 语种。" +
            " → load_tool_doc(\"insert_strings\")。"

    private const val DESC_UPDATE_STRING =
        "精准修改指定 key 的部分语言翻译,只动 translations 列出的语言,其余保持原样。" +
            " → load_tool_doc(\"update_string\")。"

    private const val DESC_DELETE_STRING =
        "删除指定 key 的翻译(破坏性)。languages 空=删全部语言;非空=只删指定语言。" +
            "操作前先 read_string 确认。→ load_tool_doc(\"delete_string\")。"

    private const val DESC_QUERY_KEYS =
        "列出/搜索模块内字符串 key。searchIn:key(默认)/text(跨多语种)/both(并集)。" +
            "module 省略时按 recommendedDefaultModule → currentModule 自动选。" +
            " → load_tool_doc(\"query_keys\")。"

    private const val DESC_READ_STRING =
        "读取指定 key 在模块所有语言的当前翻译(key+各语种文本+文件路径)。" +
            "修改/删除前必先 read_string 确认。→ load_tool_doc(\"read_string\")。"

    private const val DESC_FIND_KEYS_BY_TEXT =
        "strings.xml 反查:通过翻译文本找 key。固定全模块 + 全语言目录搜索(2026.x 简化)," +
            "exact/contains(默认)/regex。 → load_tool_doc(\"find_keys_by_text\")。"

    private const val DESC_FIND_ROWS_BY_TEXT =
        "Google Sheets 反查:按文本搜行,返回行号+列名+整行。" +
            "sheetName 必须原样(系统按 A1 规则自动转义)。" +
            " → load_tool_doc(\"find_rows_by_text\")。"

    // region 代办(2026.x 新增)

    private const val DESC_TODO_LIST =
        "读取代办列表(active/completed/all)。返回 id 供 todo_update/delete 定位。" +
            "上下文已注入前 5 条摘要,完整字段用本工具。→ load_tool_doc(\"todo_list\")。"

    private const val DESC_TODO_ADD =
        "新增一条代办。title 必填,priority(LOW/NORMAL/HIGH/URGENT)可选。" +
            "支持 reminderTime/timestamp 或 reminderDate+reminderTimeOfDay/recurrence/recurrenceDays。" +
            "「工作日」=CUSTOM+[1..5],「周末」=CUSTOM+[6,7](无 WEEKDAYS/WEEKLY)。" +
            " → load_tool_doc(\"todo_add\")。"

    private const val DESC_TODO_UPDATE =
        "按 id 更新代办,只改非 null 字段。isCompleted=true 勾选完成;clearReminder=true 清提醒。" +
            " → load_tool_doc(\"todo_update\")。"

    private const val DESC_TODO_DELETE =
        "按 id 删除代办(破坏性),连带删除其上提醒。id 不存在时返回错误。" +
            " → load_tool_doc(\"todo_delete\")。"

    private const val DESC_CURRENT_TIME =
        "获取当前时间(timestamp + formatted + timezone + offsetMinutes)。" +
            "「5 分钟后」「明天下午 3 点」类相对时间必须先调本工具拿最新 timestamp。"

    // endregion

    // region 文件操作域描述(2026 新增)

    private const val DESC_GET_EDITOR_FILE =
        "获取当前 IDE 编辑器中打开的文件:路径/后缀/选中文字/选区起止/总行数。无参。" +
            " → load_tool_doc(\"get_editor_file\")。"

    private const val DESC_READ_FILE =
        "读项目内任意文件。> 1.5MB 拒绝(改用 search_in_files)。maxLines 默认 600,startLine/endLine 翻页。" +
            " → load_tool_doc(\"read_file\")。"

    private const val DESC_READ_FILES =
        "批量读 2-10 个文件(一次 round-trip,省 N-1 次往返 + 共享 '--- begin content ---' 框架)。" +
            "每文件默认 maxLines=300(防合并爆 token)。" +
            " → load_tool_doc(\"read_files\")。"

    private const val DESC_EDIT_FILE =
        "精准修改项目内文件。useRegex=false 时 oldText 全文精确唯一匹配;true 时按 Kotlin 正则。" +
            "> 3MB 拒绝。原子写。IDE 缓存已自动重读。" +
            " → load_tool_doc(\"edit_file\")。"

    private val DESC_CREATE_FILE =
        "创建新文件(支持嵌套目录)。overwrite 默认 false(防误覆盖)。" +
            " → load_tool_doc(\"create_file\")。"

    private const val DESC_DELETE_FILE =
        "删除文件或空目录(破坏性)。非空目录会拒绝(需 AI 先 list_files + 逐个删)。" +
            "目标已打开的 tab 会自动关闭。→ load_tool_doc(\"delete_file\")。"

    private const val DESC_MOVE_FILE =
        "移动/重命名文件或目录(等价 mv)。dst 已存在会拒绝(防误覆盖);自动 mkdirs 父目录。" +
            "IDE 已打开的 src tab 会自动关闭。→ load_tool_doc(\"move_file\")。"

    private const val DESC_SEARCH_IN_FILES =
        "在项目内按文本/正则搜索,返回 filePath+line+col+matchedText。" +
            "默认搜 java/kt/xml/gradle/json/properties/txt/md。单次最多 200 条。" +
            " → load_tool_doc(\"search_in_files\")。"

    private const val DESC_FIND_REFERENCES =
        "按符号语义找引用点(资源 id/string/layout/drawable/color/class/任意符号)。" +
            " → load_tool_doc(\"find_references\")。"

    private const val DESC_LIST_FILES =
        "列举项目内某目录的文件/子目录,支持 glob 与递归(最多 10 层)。" +
            " → load_tool_doc(\"list_files\")。"

    private const val DESC_FILE_INFO =
        "读文件元信息(大小/行数/mtime/是否目录),不读全文。" +
            "先看'文件存不存在 / 多大'时用这个,省 token。→ load_tool_doc(\"file_info\")。"

    // endregion

    private const val DESC_SHEETS_OPERATION =
        "Google Sheets 操作(operation 决定动作)。混合修改用 batch_modify 一次完成。" +
            "修改/删除行前先 search 定位。sheetName 原样传(系统自动转义)。" +
            " → load_tool_doc(\"sheets_basic\")。"

    private const val DESC_ASK_USER =
        "向用户提问并等待回复。options 非空=按钮(优先);空=输入框。" +
            "每次调用都暂停 tool loop,不要反复调用。"

    private const val DESC_REPLACE_SELECTION =
        "把选区里所有匹配 oldText 的子串替换为 newText(选区内全部出现都换,精确字面匹配)。" +
            "chatEntry=askAi/extractStrings 时「使用现有 key:」后**必须**先调本工具。" +
            " → load_tool_doc(\"replace_selection\")。"

    private const val DESC_RUN_SHELL =
        "在项目根目录执行 shell 命令并流式返回输出。args 数组逐项传入(避免注入)," +
            "cwd 相对项目根。删除/推送/部署前用 ask_user 描述意图。Windows 走 cmd.exe /c。" +
            " → load_tool_doc(\"run_shell\")。"

    private const val DESC_READ_DIAGNOSTICS =
        "读编辑器级 LSP/静态分析诊断:打开文件中的 ERROR/WARNING(Java/Kotlin 一视同仁)。" +
            "**只覆盖当前打开的文件**,不包含 build-time 错(资源 ID/kapt/lint 用 run_shell 跑 gradlew)。" +
            "写完文件 daemon 异步刷(~500ms),改后立刻读可能拿到旧结果。" +
            " → load_tool_doc(\"read_diagnostics\")。"

    private const val DESC_LOAD_TOOL_DOC =
        "按需加载工具详细文档(枚举值/参数约束/示例)。" +
            "连续 load_tool_doc 有次数上限,一次最多 1-2 个,拿到后立即调实际工具。"

    private const val DESC_TASK_COMPLETE =
        "声明任务完成,结束对话。唯一合法终止信号。status:success/partial/failed。" +
            "调用后不要再调其他工具。"

    // endregion

    /**
     * 把当前 Google Sheets 上下文拼到 description 后面。
     * 让 AI 在调用工具前能直接看到默认 sheet 和可用 sheet 列表,避免读错 sheet。
     */
    private fun appendSheetContext(base: String, ctx: SheetContext): String {
        if (ctx.defaultSheetName.isNullOrBlank() && ctx.availableSheets.isEmpty()) return base
        val parts = mutableListOf<String>()
        if (!ctx.defaultSheetName.isNullOrBlank()) {
            parts += "默认工作表:「${ctx.defaultSheetName}」"
        }
        if (ctx.availableSheets.isNotEmpty()) {
            val shown = ctx.availableSheets.take(20)
            val more = (ctx.availableSheets.size - shown.size).coerceAtLeast(0)
            val list = if (more > 0) shown.joinToString("、") + " 等 $more 个" else shown.joinToString("、")
            parts += "可用工作表:[$list]"
        }
        return "$base\n\n[当前 Google Sheets 上下文] ${parts.joinToString("; ")}。"
    }

    /**
     * 把当前 IDE 项目根拼到 description 后面,让 AI 在调文件操作工具时知道相对路径怎么传。
     * @param projectBase 当前项目根(可为 null,表示未打开项目)
     */
    private fun appendProjectContext(base: String, projectBase: String?): String {
        if (projectBase.isNullOrBlank()) {
            return "$base\n\n[项目根] 当前 IDE 未打开项目,所有文件工具都将失败 — 请先打开 Android/IntelliJ 项目。"
        }
        return "$base\n\n[项目根] $projectBase — 路径参数优先传相对项目根的路径(如 \"app/src/main/AndroidManifest.xml\")。"
    }

    private fun buildOpenAiTools(ctx: SheetContext, projectBase: String? = null): JsonArray {
        val descSheets = appendSheetContext(DESC_SHEETS_OPERATION, ctx)
        val descFindRows = appendSheetContext(DESC_FIND_ROWS_BY_TEXT, ctx)
        val descGetEditor = appendProjectContext(DESC_GET_EDITOR_FILE, projectBase)
        val descReadFile = appendProjectContext(DESC_READ_FILE, projectBase)
        val descReadFiles = appendProjectContext(DESC_READ_FILES, projectBase)
        val descEditFile = appendProjectContext(DESC_EDIT_FILE, projectBase)
        val descCreateFile = appendProjectContext(DESC_CREATE_FILE, projectBase)
        val descDeleteFile = appendProjectContext(DESC_DELETE_FILE, projectBase)
        val descMoveFile = appendProjectContext(DESC_MOVE_FILE, projectBase)
        val descSearch = appendProjectContext(DESC_SEARCH_IN_FILES, projectBase)
        val descList = appendProjectContext(DESC_LIST_FILES, projectBase)
        val descFileInfo = appendProjectContext(DESC_FILE_INFO, projectBase)
        return JsonArray().apply {
            add(openAiTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(openAiTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(openAiTool(TOOL_DELETE_STRING, DESC_DELETE_STRING, openAiDeleteStringParams()))
            add(openAiTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(openAiTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(openAiTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(openAiTool(TOOL_SHEETS_OPERATION, descSheets, openAiSheetsOperationParams()))
            add(openAiTool(TOOL_FIND_ROWS_BY_TEXT, descFindRows, openAiFindRowsByTextParams()))
            add(openAiTool(TOOL_GET_EDITOR_FILE, descGetEditor, openAiGetEditorFileParams()))
            add(openAiTool(TOOL_READ_FILE, descReadFile, openAiReadFileParams()))
            add(openAiTool(TOOL_READ_FILES, descReadFiles, openAiReadFilesParams()))
            add(openAiTool(TOOL_EDIT_FILE, descEditFile, openAiEditFileParams()))
            add(openAiTool(TOOL_CREATE_FILE, descCreateFile, openAiCreateFileParams()))
            add(openAiTool(TOOL_DELETE_FILE, descDeleteFile, openAiDeleteFileParams()))
            add(openAiTool(TOOL_MOVE_FILE, descMoveFile, openAiMoveFileParams()))
            add(openAiTool(TOOL_SEARCH_IN_FILES, descSearch, openAiSearchInFilesParams()))
            add(openAiTool(TOOL_FIND_REFERENCES, DESC_FIND_REFERENCES, openAiFindReferencesParams()))
            add(openAiTool(TOOL_LIST_FILES, descList, openAiListFilesParams()))
            add(openAiTool(TOOL_FILE_INFO, descFileInfo, openAiFileInfoParams()))
            // 代办域:用户在主页 代办 tab 维护的清单,AI 可读写
            add(openAiTool(TOOL_TODO_LIST, DESC_TODO_LIST, openAiTodoListParams()))
            add(openAiTool(TOOL_TODO_ADD, DESC_TODO_ADD, openAiTodoAddParams()))
            add(openAiTool(TOOL_TODO_UPDATE, DESC_TODO_UPDATE, openAiTodoUpdateParams()))
            add(openAiTool(TOOL_TODO_DELETE, DESC_TODO_DELETE, openAiTodoDeleteParams()))
            add(openAiTool(TOOL_CURRENT_TIME, DESC_CURRENT_TIME, openAiCurrentTimeParams()))
            add(openAiTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(openAiTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(openAiTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(openAiTool(TOOL_RUN_SHELL, DESC_RUN_SHELL, openAiRunShellParams()))
            add(openAiTool(TOOL_READ_DIAGNOSTICS, DESC_READ_DIAGNOSTICS, openAiReadDiagnosticsParams()))
            add(openAiTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    private fun buildAnthropicTools(ctx: SheetContext, projectBase: String? = null): JsonArray {
        val descSheets = appendSheetContext(DESC_SHEETS_OPERATION, ctx)
        val descFindRows = appendSheetContext(DESC_FIND_ROWS_BY_TEXT, ctx)
        val descGetEditor = appendProjectContext(DESC_GET_EDITOR_FILE, projectBase)
        val descReadFile = appendProjectContext(DESC_READ_FILE, projectBase)
        val descReadFiles = appendProjectContext(DESC_READ_FILES, projectBase)
        val descEditFile = appendProjectContext(DESC_EDIT_FILE, projectBase)
        val descCreateFile = appendProjectContext(DESC_CREATE_FILE, projectBase)
        val descDeleteFile = appendProjectContext(DESC_DELETE_FILE, projectBase)
        val descMoveFile = appendProjectContext(DESC_MOVE_FILE, projectBase)
        val descSearch = appendProjectContext(DESC_SEARCH_IN_FILES, projectBase)
        val descList = appendProjectContext(DESC_LIST_FILES, projectBase)
        val descFileInfo = appendProjectContext(DESC_FILE_INFO, projectBase)
        return JsonArray().apply {
            add(anthropicTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(anthropicTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(anthropicTool(TOOL_DELETE_STRING, DESC_DELETE_STRING, openAiDeleteStringParams()))
            add(anthropicTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(anthropicTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(anthropicTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(anthropicTool(TOOL_SHEETS_OPERATION, descSheets, openAiSheetsOperationParams()))
            add(anthropicTool(TOOL_FIND_ROWS_BY_TEXT, descFindRows, openAiFindRowsByTextParams()))
            add(anthropicTool(TOOL_GET_EDITOR_FILE, descGetEditor, openAiGetEditorFileParams()))
            add(anthropicTool(TOOL_READ_FILE, descReadFile, openAiReadFileParams()))
            add(anthropicTool(TOOL_READ_FILES, descReadFiles, openAiReadFilesParams()))
            add(anthropicTool(TOOL_EDIT_FILE, descEditFile, openAiEditFileParams()))
            add(anthropicTool(TOOL_CREATE_FILE, descCreateFile, openAiCreateFileParams()))
            add(anthropicTool(TOOL_DELETE_FILE, descDeleteFile, openAiDeleteFileParams()))
            add(anthropicTool(TOOL_MOVE_FILE, descMoveFile, openAiMoveFileParams()))
            add(anthropicTool(TOOL_SEARCH_IN_FILES, descSearch, openAiSearchInFilesParams()))
            add(anthropicTool(TOOL_FIND_REFERENCES, DESC_FIND_REFERENCES, openAiFindReferencesParams()))
            add(anthropicTool(TOOL_LIST_FILES, descList, openAiListFilesParams()))
            add(anthropicTool(TOOL_FILE_INFO, descFileInfo, openAiFileInfoParams()))
            // 代办域
            add(anthropicTool(TOOL_TODO_LIST, DESC_TODO_LIST, openAiTodoListParams()))
            add(anthropicTool(TOOL_TODO_ADD, DESC_TODO_ADD, openAiTodoAddParams()))
            add(anthropicTool(TOOL_TODO_UPDATE, DESC_TODO_UPDATE, openAiTodoUpdateParams()))
            add(anthropicTool(TOOL_TODO_DELETE, DESC_TODO_DELETE, openAiTodoDeleteParams()))
            add(anthropicTool(TOOL_CURRENT_TIME, DESC_CURRENT_TIME, openAiCurrentTimeParams()))
            add(anthropicTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(anthropicTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(anthropicTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(anthropicTool(TOOL_RUN_SHELL, DESC_RUN_SHELL, openAiRunShellParams()))
            add(anthropicTool(TOOL_READ_DIAGNOSTICS, DESC_READ_DIAGNOSTICS, openAiReadDiagnosticsParams()))
            add(anthropicTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    // 2026.x 移除 buildOpenAiToolsQuoteEntry / buildAnthropicToolsQuoteEntry(原引用入口精简工具集)。
    // Ask AI 弹框现在与主面板用同一份全集(由 [openAiTools] / [anthropicTools] 暴露),
    // 这两个函数无外部调用方,保留只会让 schema 维护成本翻倍。

    private fun openAiTool(name: String, description: String, parameters: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            })
        }
    }

    private fun anthropicTool(name: String, description: String, inputSchema: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("input_schema", inputSchema)
        }
    }

    // region 各工具的 JSON Schema (OpenAI 风格,Anthropic 共用同一份 input_schema)

    private fun openAiInsertStringsParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "取 modules[].moduleName(非 androidProject.name);省略时按 用户指定 > recommendedDefaultModule > UI选中行")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "snake_case key。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty("description", "键=语言目录,值=翻译文本。必须含 values 并覆盖目标模块全部 xmlFiles 语种(以 recommendedDefaultModule.xmlFiles[].language 为准)。")
                })
            })
            add("required", JsonArray().apply {
                add("name")
                add("translations")
            })
        }
    }

    private fun openAiQueryKeysParams(): JsonObject {
        val searchInEnum = JsonArray().apply {
            add("key")
            add("text")
            add("both")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,取 modules[].moduleName(非 androidProject.name);省略时按 recommendedDefaultModule → currentModule → 行数最多模块 选。")
                })
                add("pattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选正则;范围由 searchIn 决定。空/省略=列全部。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "默认 50,最大 500。")
                })
                add("offset", obj {
                    addProperty("type", "integer")
                    addProperty("description", "分页偏移,默认 0。")
                })
                add("includeTranslations", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否带各语言当前翻译。默认 false(token 消耗大)。")
                })
                add("searchIn", obj {
                    addProperty("type", "string")
                    add("enum", searchInEnum)
                    addProperty("description", "key(默认)/text(跨多语种)/both(并集)。")
                })
            })
        }
    }

    private fun openAiReadStringParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,取 modules[].moduleName;省略时用 recommendedDefaultModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key。")
                })
            })
            add("required", JsonArray().apply { add("name") })
        }
    }

    private fun openAiUpdateStringParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,取 modules[].moduleName;省略时用 recommendedDefaultModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,snake_case key。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty("description", "必填,键=语言目录,值=要改的翻译;未列出的语言保持原样。")
                })
            })
            add("required", JsonArray().apply {
                add("name")
                add("translations")
            })
        }
    }

    private fun openAiDeleteStringParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,取 modules[].moduleName;省略时用 recommendedDefaultModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,snake_case key。")
                })
                add("languages", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "可选,语言目录列表;空/null=删全部语言,非空=只删指定语言。")
                })
            })
            add("required", JsonArray().apply { add("name") })
        }
    }

    private fun openAiFindKeysByTextParams(): JsonObject {
        val matchTypeEnum = JsonArray().apply {
            add("exact")
            add("contains")
            add("regex")
        }
        // 2026.x 简化:去掉 module / language 参数。
        // 本工具固定全模块 + 全语言目录搜索(原文查重的标准语义,见 [AiAction.FindKeysByText] 注释)。
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("text", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,翻译文本。")
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "exact/contains(默认)/regex。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "默认 30,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("text") })
        }
    }

    private fun openAiFindRowsByTextParams(): JsonObject {
        val matchTypeEnum = JsonArray().apply {
            add("exact")
            add("contains")
            add("regex")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("text", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,查找文本。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认 googleSheets 配置。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认 defaultSheetName,原样传(系统按 A1 自动转义)。")
                })
                add("column", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,限定列名(表头精确匹配,忽略大小写)。")
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "exact/contains(默认)/regex。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "默认 30,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("text") })
        }
    }

    private fun openAiSheetsOperationParams(): JsonObject {
        val operationEnum = JsonArray().apply {
            SheetsOperation.Operation.entries.forEach { add(it.name.lowercase()) }
        }
        // 2026.x 精简:每个 property 只留 type + 一行提示,详细约束/枚举/示例 → load_tool_doc 加载。
        // 字段全集:operation / spreadsheetId / sheetName / range / key / rowNumber / rows /
        //   columnIndex / columnHeader / columnValues / freezeRowCount / freezeColumnCount /
        //   color / textColor / rowTextColors / columnTextColors / batchEdits
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("operation", obj {
                    addProperty("type", "string")
                    add("enum", operationEnum)
                    addProperty("description", "操作类型,决定后续字段。详见 load_tool_doc(\"sheets_basic\")。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认 googleSheets.defaultSpreadsheetId。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认 defaultSheetName。")
                })
                add("range", obj {
                    addProperty("type", "string")
                    addProperty("description", "A1 表示法,read/write/set_values/fill_color/clear_color 时使用。")
                })
                add("key", obj {
                    addProperty("type", "string")
                    addProperty("description", "search/read 时按 key 查找匹配行。")
                })
                add("rowNumber", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 行号,insert/update/delete/clear_row 必填;batch_modify 内 update_rows/insert_rows 起点。")
                })
                add("rows", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj { addProperty("type", "string") })
                    })
                    addProperty("description", "二维数组(每行一格字符串数组)。write/append/insert/update_row 用。")
                })
                add("columnIndex", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 列号,insert/update/delete/clear_column 必填。")
                })
                add("columnHeader", obj {
                    addProperty("type", "string")
                    addProperty("description", "新列的表头,append_column 建议填。")
                })
                add("columnValues", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "一维数组,首元素表头,其余各行值。insert/append/update_column 必填。")
                })
                add("freezeRowCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_rows 必填;0 取消冻结。")
                })
                add("freezeColumnCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_columns 必填;0 取消冻结。")
                })
                add("color", obj {
                    addProperty("type", "string")
                    addProperty("description", "背景色,fill_color 必填。hex(#FF0000/#f0a)或命名色(red/green/blue/...)。")
                })
                add("textColor", obj {
                    addProperty("type", "string")
                    addProperty("description", "文字色,set_text_color 必填。格式同 color。")
                })
                add("rowTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj { addProperty("type", "string") })
                    })
                    addProperty("description", "与 rows 同形二维矩阵,逐格文字色,null=不上色。")
                })
                add("columnTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "与 columnValues 同形,按行顺序对应;null=不上色。")
                })
                add("batchEdits", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "object")
                        add("properties", obj {
                            add("type", obj { addProperty("type", "string") })
                            add("range", obj { addProperty("type", "string") })
                            add("rows", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj { addProperty("type", "string") })
                                })
                            })
                            add("rowNumber", obj { addProperty("type", "integer") })
                            add("rowNumbers", obj {
                                addProperty("type", "array")
                                add("items", obj { addProperty("type", "integer") })
                            })
                            add("color", obj { addProperty("type", "string") })
                            add("rowTextColors", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj { addProperty("type", "string") })
                                })
                            })
                            add("rowBackgroundColors", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj { addProperty("type", "string") })
                                })
                            })
                        })
                    })
                    addProperty("description", "批量修改列表,仅 batch_modify 用,把多种操作合并到一次工具调用。→ load_tool_doc(\"sheets_batch_modify\")。")
                })
            })
            add("required", JsonArray().apply { add("operation") })
        }
    }

    private fun openAiAskUserParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("question", obj {
                    addProperty("type", "string")
                    addProperty("description", "问题文本,会直接展示给用户。")
                })
                add("options", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "可选的按钮选项,非空时显示为可点击按钮。")
                })
            })
            add("required", JsonArray().apply { add("question") })
        }
    }

    private fun openAiLoadToolDocParams(): JsonObject {
        val availableTools = JsonArray().apply {
            // strings.xml 操作
            add("insert_strings")
            add("query_keys")
            add("read_string")
            add("update_string")
            add("delete_string")
            add("find_keys_by_text")
            // Google Sheets 操作
            add("sheets_basic")
            add("sheets_row_ops")
            add("sheets_column_ops")
            add("sheets_freeze")
            add("sheets_review")
            add("sheets_color")
            add("sheets_batch_modify")
            add("find_rows_by_text")
            // 文件 / 内容 / 引用操作(2026.x 强化:11 个工具,推荐用 code_ops 一次拿全)
            add("code_ops")
            add("get_editor_file")
            add("read_file")
            add("read_files")
            add("edit_file")
            add("create_file")
            add("delete_file")
            add("move_file")
            add("search_in_files")
            add("find_references")
            add("list_files")
            add("file_info")
            // 通用
            add("ask_user")
            add("load_tool_doc")
            add("replace_selection")
            add("task_complete")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("tool", obj {
                    addProperty("type", "string")
                    add("enum", availableTools)
                    addProperty("description", "要加载详细文档的工具名。")
                })
            })
            add("required", JsonArray().apply { add("tool") })
        }
    }

    private fun openAiTaskCompleteParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("summary", obj {
                    addProperty("type", "string")
                    addProperty("description", "给用户看的最终总结,会直接展示。")
                })
                add("status", obj {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("success")
                        add("partial")
                        add("failed")
                    })
                    addProperty("description", "任务完成状态:success 完全达成 / partial 部分达成 / failed 执行失败。")
                })
                add("notes", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,补充说明(例如「用户拒绝」「缺少必要信息」)。")
                })
            })
            add("required", JsonArray().apply {
                add("summary")
                add("status")
            })
        }
    }

    // region 代办 JSON Schema(2026.x 新增)

    private fun openAiTodoListParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("filter", obj {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("active")
                        add("completed")
                        add("all")
                    })
                    addProperty("description", "过滤模式:active=未完成(默认) / completed=已完成 / all=全部。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "最大返回条数(默认 50);不传或 null 时取 50。")
                })
            })
        }
    }

    private fun openAiTodoAddParams(): JsonObject {
        val recurrenceEnum = JsonArray().apply {
            add("NONE")
            add("DAILY")
            add("CUSTOM")
        }
        val priorityEnum = JsonArray().apply {
            add("LOW")
            add("NORMAL")
            add("HIGH")
            add("URGENT")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("title", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,trim 后非空,空字符串会被拒。")
                })
                add("content", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,详细描述。")
                })
                add("priority", obj {
                    addProperty("type", "string")
                    add("enum", priorityEnum)
                    addProperty("description", "可选,空/未知回退 NORMAL。")
                })
                add("reminderTime", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,首次提醒 Unix 毫秒时间戳;与 reminderDate 互斥。")
                })
                add("reminderDate", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,YYYY-MM-DD 本地日期,仅一次性生效。")
                })
                add("reminderTimeOfDay", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,HH:MM;与 reminderDate 配套(缺省 09:00)或与 recurrence+recurrenceDays 配套。")
                })
                add("recurrence", obj {
                    addProperty("type", "string")
                    add("enum", recurrenceEnum)
                    addProperty("description", "NONE/DAILY/CUSTOM(无 WEEKDAYS/WEEKLY)。省略=NONE。")
                })
                add("recurrenceDays", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "integer") })
                    addProperty("description", "1-7 表示周一到周日,仅 recurrence=CUSTOM 生效。")
                })
            })
            add("required", JsonArray().apply { add("title") })
        }
    }

    private fun openAiTodoUpdateParams(): JsonObject {
        val recurrenceEnum = JsonArray().apply {
            add("NONE")
            add("DAILY")
            add("CUSTOM")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("id", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,代办的稳定 id(由 todo_list / todo_add 返回)。id 不存在时返回错误。")
                })
                add("title", obj {
                    addProperty("type", "string")
                    addProperty("description", "新标题(null = 不改;trim 后空 = 校验失败)。")
                })
                add("content", obj {
                    addProperty("type", "string")
                    addProperty("description", "新描述(null = 不改;空串 = 清空描述)。")
                })
                add("priority", obj {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("LOW")
                        add("NORMAL")
                        add("HIGH")
                        add("URGENT")
                    })
                    addProperty("description", "新优先级(null = 不改;未知回退 NORMAL)。")
                })
                add("isCompleted", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "新完成状态(null = 不改;true 自动写 completedAt)。")
                })
                add("reminderTime", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,新提醒 Unix 毫秒时间戳;与 reminderDate 互斥。")
                })
                add("reminderDate", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,新指定日期 YYYY-MM-DD,仅一次性生效。")
                })
                add("reminderTimeOfDay", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,新时分 HH:MM;与 reminderDate 配套或与 recurrence 配套。")
                })
                add("recurrence", obj {
                    addProperty("type", "string")
                    add("enum", recurrenceEnum)
                    addProperty("description", "NONE/DAILY/CUSTOM(无 WEEKDAYS/WEEKLY);null=不改。")
                })
                add("recurrenceDays", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "integer")
                    })
                    addProperty("description", "1-7 周一到周日,仅 recurrence=CUSTOM;null=不改。")
                })
                add("clearReminder", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "true=清除整条提醒(优先于 reminderTime/recurrence)。")
                })
            })
            add("required", JsonArray().apply { add("id") })
        }
    }

    private fun openAiTodoDeleteParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("id", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,代办的稳定 id。id 不存在时返回错误,不会静默成功。")
                })
            })
            add("required", JsonArray().apply { add("id") })
        }
    }

    private fun openAiCurrentTimeParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("dummy", obj {
                    addProperty("type", "string")
                    addProperty("description", "保留字段,本工具不接收任何参数,传空字符串或省略。")
                })
            })
        }
    }

    // endregion

    private fun openAiReplaceSelectionParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("oldText", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "必填,选区内要被替换的子串(精确字面匹配,非正则)。" +
                            "选区里所有匹配此字符串的位置**全部**替换为 newText(整段选区内的多次出现都换);" +
                            "选区里 0 次出现工具返回失败 + 选区前 60 字符预览。空串会直接拒绝(防止无限循环)。"
                    )
                })
                add("newText", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "必填,把 oldText 替换成的目标文本(原样写入,不做任何转换)。" +
                            "翻译查重 +「使用现有 key」场景:newText = \"@string/<key>\"(XML 布局)或 \"R.string.<key>\"(Kotlin/Java/...);" +
                            "精准子串替换场景:newText = \"@string/<key>\" / \"R.string.<key>\",oldText = 选区里要换的那段子串。"
                    )
                })
            })
            add("required", JsonArray().apply { add("oldText"); add("newText") })
        }
    }

    // region 文件操作域 JSON Schema(2026 新增)

    private fun openAiGetEditorFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("dummy", obj {
                    addProperty("type", "string")
                    addProperty("description", "保留字段,本工具不接收任何参数,传空字符串或省略。")
                })
            })
        }
    }

    private fun openAiReadFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("path", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,相对项目根的路径(如 \"app/src/main/AndroidManifest.xml\")或项目内的绝对路径。")
                })
                add("startLine", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,起始行 0-based(包含),默认 0。")
                })
                add("endLine", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,结束行 0-based(包含),-1 表示到文件末尾,默认 -1。")
                })
                add("maxLines", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,单次返回最大行数(防止 token 爆炸),默认 600,最大 2000。")
                })
            })
            add("required", JsonArray().apply { add("path") })
        }
    }

    private fun openAiEditFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("path", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,文件路径(相对项目根或项目内绝对路径)。")
                })
                add("oldText", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,匹配文本(useRegex=false)或正则 pattern(useRegex=true)。")
                })
                add("newText", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,替换为的新文本。")
                })
                add("useRegex", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,true 时把 oldText 视为 Kotlin 正则,默认 false(纯文本匹配)。")
                })
                add("replaceAll", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,true 时替换所有匹配;false 时要求唯一匹配(0/>1 处会失败,让 AI 调整),默认 false。")
                })
            })
            add("required", JsonArray().apply {
                add("path")
                add("oldText")
                add("newText")
            })
        }
    }

    private fun openAiCreateFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("path", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,文件路径(相对项目根或项目内绝对路径);支持嵌套目录,自动 mkdirs。")
                })
                add("content", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,文件内容。")
                })
                add("overwrite", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,目标文件已存在时是否覆盖,默认 false(防误覆盖)。修改已存在文件请用 edit_file。")
                })
            })
            add("required", JsonArray().apply {
                add("path")
                add("content")
            })
        }
    }

    private fun openAiSearchInFilesParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("pattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,搜索文本(useRegex=false)或正则(useRegex=true)。")
                })
                add("useRegex", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,true 时按正则,默认 false(按子串)。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否区分大小写,默认 false。")
                })
                add("filePattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,glob 限定文件名(例:\"*.kt\")。仅支持 * 与 ? 通配符,不处理 **。")
                })
                add("relativeDir", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,限定子目录(相对项目根),如 \"app/src/main\";省略时搜索整个项目。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,最大返回条数,默认 100,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("pattern") })
        }
    }

    private fun openAiFindReferencesParams(): JsonObject {
        val kindEnum = JsonArray().apply {
            add("id")
            add("string")
            add("layout")
            add("drawable")
            add("color")
            add("class")
            add("general")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("symbol", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,要查找的符号名(资源名 / view id / key / 类名 / 标识符)。")
                })
                add("kind", obj {
                    addProperty("type", "string")
                    add("enum", kindEnum)
                    addProperty("description", "引用类型:id(资源id)/string/layout/drawable/color/class/general(任意符号)。默认 general。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,最大返回条数,默认 100,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("symbol") })
        }
    }

    private fun openAiListFilesParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("relativeDir", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,相对项目根的子目录,\".\" 或空表示项目根,默认 \".\"。")
                })
                add("pattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,glob 模式,默认 \"*\"(列出所有)。支持 * 与 ? 通配符。")
                })
                add("recursive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否递归子目录(最多 10 层),默认 false。")
                })
                add("includeDirs", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否在结果中包含目录,默认 false。")
                })
                add("maxEntries", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,最大返回条数,默认 500。")
                })
            })
        }
    }

    private fun openAiFileInfoParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("path", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,文件/目录路径(相对项目根或项目内绝对路径)。")
                })
            })
            add("required", JsonArray().apply { add("path") })
        }
    }

    private fun openAiRunShellParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("command", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "必填,可执行文件名(如 git/gradle/ls/cat/node/npm)。**不要**把参数塞到 command 里。"
                    )
                })
                add("args", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty(
                        "description",
                        "可选,参数列表(逐项传入,平台层不走 shell,避免注入)。" +
                            "例:[\"status\", \"--short\"] / [\"-C\", \"app\", \"assembleDebug\"]。"
                    )
                })
                add("cwd", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,相对项目根的子目录(如 \"app\"),null/空 = 项目根。越界(用 .. 跳出)会被拒绝。"
                    )
                })
                add("timeoutMs", obj {
                    addProperty("type", "integer")
                    addProperty(
                        "description",
                        "可选,超时毫秒,默认 60000,范围 1000..600000。超时后进程被强制终止。"
                    )
                })
            })
            add("required", JsonArray().apply { add("command") })
        }
    }

    private fun openAiReadDiagnosticsParams(): JsonObject {
        val severityEnum = JsonArray().apply {
            add("ERROR")
            add("WARNING")
            add("WEAK_WARNING")
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("minSeverity", obj {
                    addProperty("type", "string")
                    add("enum", severityEnum)
                    addProperty(
                        "description",
                        "可选,过滤级别。WEAK_WARNING(默认,收齐 3 种级别)/ WARNING / ERROR。" +
                            "传 WEAK_WARNING 一次拿全,AI 按需筛选。"
                    )
                })
            })
        }
    }

    private fun openAiReadFilesParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("paths", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty("description", "必填,要读取的文件路径列表(2-10 个,相对项目根或项目内绝对路径)。")
                })
                add("maxLines", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,每个文件单次最大返回行数,默认 300,最大 300。")
                })
            })
            add("required", JsonArray().apply { add("paths") })
        }
    }

    private fun openAiDeleteFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("path", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,要删除的文件或空目录路径(相对项目根或项目内绝对路径)。")
                })
            })
            add("required", JsonArray().apply { add("path") })
        }
    }

    private fun openAiMoveFileParams(): JsonObject {
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("src", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,源路径(相对项目根或项目内绝对路径)。")
                })
                add("dst", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,目标路径(相对项目根或项目内绝对路径)。dst 已存在会拒绝。")
                })
            })
            add("required", JsonArray().apply {
                add("src")
                add("dst")
            })
        }
    }

    // endregion

    // endregion

    private fun obj(builder: JsonObject.() -> Unit): JsonObject = JsonObject().apply(builder)
}
