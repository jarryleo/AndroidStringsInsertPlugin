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

    /**
     * 引用入口(AskAi / ExtractStrings 弹框)的精简工具集 —— 只暴露 strings.xml 操作
     * + replace_selection + 通用工具。**不**包含 Google Sheets 与文件操作域(弹框场景
     * 用不到,避免污染 AI 的工具视野和上下文)。
     */
    fun openAiToolsQuoteEntry(
        sheetContext: SheetContext = SheetContext(null, emptyList())
    ): JsonArray = buildOpenAiToolsQuoteEntry(sheetContext)

    /** Anthropic 协议的引用入口工具集。 */
    fun anthropicToolsQuoteEntry(
        sheetContext: SheetContext = SheetContext(null, emptyList())
    ): JsonArray = buildAnthropicToolsQuoteEntry(sheetContext)

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
        TOOL_EDIT_FILE,
        TOOL_CREATE_FILE,
        TOOL_SEARCH_IN_FILES,
        TOOL_FIND_REFERENCES,
        TOOL_LIST_FILES,
        TOOL_TODO_LIST,
        TOOL_TODO_ADD,
        TOOL_TODO_UPDATE,
        TOOL_TODO_DELETE,
        TOOL_CURRENT_TIME,
        TOOL_ASK_USER,
        TOOL_LOAD_TOOL_DOC,
        TOOL_REPLACE_SELECTION,
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
    const val TOOL_EDIT_FILE = "edit_file"
    const val TOOL_CREATE_FILE = "create_file"
    const val TOOL_SEARCH_IN_FILES = "search_in_files"
    const val TOOL_FIND_REFERENCES = "find_references"
    const val TOOL_LIST_FILES = "list_files"
    const val TOOL_TODO_LIST = "todo_list"
    const val TOOL_TODO_ADD = "todo_add"
    const val TOOL_TODO_UPDATE = "todo_update"
    const val TOOL_TODO_DELETE = "todo_delete"
    const val TOOL_CURRENT_TIME = "current_time"
    const val TOOL_REPLACE_SELECTION = "replace_selection"
    const val TOOL_ASK_USER = "ask_user"
    const val TOOL_LOAD_TOOL_DOC = "load_tool_doc"
    const val TOOL_TASK_COMPLETE = "task_complete"

    // region 工具描述文案(主 prompt 引用,这里集中维护)

    private const val DESC_INSERT_STRINGS =
        "向 Android strings.xml 插入或全量覆盖翻译字符串。" +
            "可同时调用多次以插入多个字符串。**translations 必须始终包含 \"values\"(默认英语)," +
            "并覆盖目标模块的全部语言** —— 一字不差对照 `recommendedDefaultModule.xmlFiles[].language`" +
            "(或显式 module 的 `xmlFiles[].language`)。" +
            "module 优先级:用户在消息中**明确指定** > `recommendedDefaultModule` > UI 中选中行所在模块" +
            "若只想修改个别语言,请改用 update_string(部分语言更新,不覆写其他语言)。" +
            "插入前的两道查重(原文查重 + key 名查重)与「使用现有 key:」后的处理流程,见 system prompt 与 load_tool_doc(\"insert_strings\")。"

    private const val DESC_UPDATE_STRING =
        "精准修改指定 key 的部分语言翻译,只动 translations 中列出的语言,其他语言保持原样。" +
            "适用场景:用户说「把 X 的繁体改成 Y」「修正 Z 的某个语言翻译」,无需提供全部语言。" +
            "若 key 不存在则自动创建。"

    private const val DESC_DELETE_STRING =
        "删除指定 key 的翻译(破坏性操作)。" +
            "languages 为空/null/省略时,删除该 key 在所有语言的翻译(整 key 被移除);" +
            "languages 非空时,仅删除列表中指定语言的翻译,其他语言保持原样。" +
            "适用场景:用户说「删除 X 的法语翻译」「移除这个 key」「删掉 X 的繁体和日语」。" +
            "安全约束:删除是破坏性操作,操作前建议先 read_string 确认目标 key 与翻译;" +
            "如不能确定范围,可先 ask_user 与用户确认。"

    private const val DESC_QUERY_KEYS =
        "列出或搜索模块内的字符串 key。" +
            "pattern 正则(可空,空时列出全部);searchIn=key(默认,匹配 key 名)/ text(跨多语种翻译文本)/ both(并集);" +
            "includeTranslations=true 时返回各语言当前翻译(消耗较多 token,谨慎使用)。" +
            "module 省略时按 recommendedDefaultModule → currentModule → 行数最多模块 自动选。" +
            "想反查某段翻译属于哪个 key 时,优先用 searchIn=text 而不是 find_keys_by_text —— 一次返回 key 列表 + 全部语种。" +
            "详细字段 → load_tool_doc(\"query_keys\")。"

    private const val DESC_READ_STRING =
        "读取指定 key 在模块所有语言的当前翻译,返回 key+各语言文本+文件路径。" +
            "修改/删除前必先 read_string 确认原文,避免覆盖已有正确翻译。详细字段 → load_tool_doc(\"read_string\")。"

    private const val DESC_FIND_KEYS_BY_TEXT =
        "strings.xml 反查:通过翻译文本查找对应的 key。" +
            "支持 exact(完全相等)/ contains(子串,默认)/ regex 三种匹配模式;可选 module / language 限定。" +
            "插入翻译前查重:不传 language 参数,matchType=exact 跑一次、再 contains 兜底。" +
            "详细字段 → load_tool_doc(\"find_keys_by_text\")。"

    private const val DESC_FIND_ROWS_BY_TEXT =
        "Google Sheets 反查:在表格中按文本搜索行,返回行号+列名+整行内容。" +
            "支持 exact/ contains(默认)/ regex,可限定 column。sheetName 必须**原样**使用(系统按 A1 规则自动转义)。" +
            "详细字段 → load_tool_doc(\"find_rows_by_text\")。"

    // region 代办(2026.x 新增) — 用户在主页 Todo tab 维护的清单,AI 可读写并主动提醒

    private const val DESC_TODO_LIST =
        "读取代办列表(可限定 active / completed / all 过滤模式)。" +
            "返回的每条代办都带 id 字段,后续 todo_update / todo_delete 必须用这个 id 定位;" +
            "已设置提醒的代办还会带 reminder 子对象(nextTriggerAt / recurrence / timeOfDay / recurrenceDays)," +
            "用于核对闹钟与下一次触发时间。" +
            "系统已经在聊天上下文中注入了 active 代办的简要摘要(便于你主动提醒用户)," +
            "但用本工具可以拿到完整字段(content / createdAt / completedAt / reminder 等),用于核对细节。" +
            "详细字段 → load_tool_doc(\"todo_list\")。"

    private const val DESC_TODO_ADD =
        "新增一条代办;title 必填,content / priority(LOW/NORMAL/HIGH/URGENT,默认 NORMAL)可选。" +
            "新条目默认未完成,优先级高的会浮在 Todo tab 列表顶部。" +
            "**支持设置提醒**:可选 reminderTime(Unix 毫秒时间戳) + recurrence(NONE/DAILY/CUSTOM,无 WEEKDAYS/WEEKLY)" +
                " + recurrenceDays(1-7 数组,仅 CUSTOM 生效);" +
                "**「工作日」= CUSTOM + [1,2,3,4,5],「周末」= CUSTOM + [6,7]**,不要传 WEEKDAYS / WEEKLY。" +
                "到点会在 IDE 右下角弹非模态提醒框,用户可选「完成 / 1m / 5m / 10m」。" +
            "**典型场景**:用户说「提醒我周五前修 X」「5 分钟后提醒我喝水」「明天下午 3 点开周会」时," +
            "先调 current_time 拿时间戳,再 todo_add(title=..., reminderTime=..., recurrence=...);" +
            "循环提醒(每周一三五开会)用 recurrence=CUSTOM + recurrenceDays=[1,3,5]。" +
            "新增后系统会返回新条目的 id,你可以在下一轮用 todo_update / todo_delete 引用它。" +
            "详细字段 → load_tool_doc(\"todo_add\")。"

    private const val DESC_TODO_UPDATE =
        "按 id 更新一条已有代办;只需传要改的字段,其它字段保持原值。" +
            "**isCompleted = true** 即可「勾选完成」(系统自动写 completedAt 时间戳);" +
            "**isCompleted = false** 取消完成(系统清空 completedAt)。" +
            "**支持改提醒**:reminderTime 改触发时间戳、recurrence 改循环类型(NONE=改一次性)、" +
            "recurrenceDays 改 CUSTOM 周几列表;clearReminder=true 显式清掉整条提醒(用户说「这个不用再提醒了」时用)。" +
            "id 不存在时返回错误,先 todo_list 拿到正确 id 再更新。" +
            "详细字段 → load_tool_doc(\"todo_update\")。"

    private const val DESC_TODO_DELETE =
        "按 id 删除一条代办(破坏性操作,删除前建议先 todo_list 确认目标)。" +
            "**连带删除其上的提醒配置**(scheduler 会从 Timer 队列里摘掉),无需额外调 clearReminder。" +
            "id 不存在时返回错误,不会静默成功。" +
            "详细字段 → load_tool_doc(\"todo_delete\")。"

    private const val DESC_CURRENT_TIME =
        "获取当前时间(2026.x 新增)。不需要任何参数,返回:" +
            "**timestamp**(Unix 毫秒时间戳,直接拿去做相对时间运算如 now+5*60*1000);" +
            "**formatted**(本地时区 yyyy-MM-dd HH:mm:ss,人类可读);" +
            "**timezone**(如 Asia/Shanghai);" +
            "**offsetMinutes**(UTC 偏移分钟数)。" +
            "系统已经在聊天上下文里注入了 `now` 字段(发送消息那一刻的时间)," +
            "但**当 tool loop 跑了几十秒**(慢 AI / 流式响应)时该时间会过时," +
            "此时必须先调本工具拿最新的 timestamp 再算相对时间。" +
            "**典型用法**:用户说「5 分钟后提醒我喝水」时,先调 current_time 拿 timestamp," +
            "再调 todo_add(title='喝水', reminderTime=timestamp+5*60*1000, recurrence='NONE');" +
            "用户说「明天下午 3 点」时,先调 current_time 拿 timestamp + timezone," +
            "再算次日的 15:00 本地时间戳,调 todo_add。"

    // endregion

    // region 文件操作域描述(2026 新增)

    private const val DESC_GET_EDITOR_FILE =
        "获取当前 IDE 编辑器中打开的文件信息:路径、文件名后缀、选中文字、选区起止行、文件总行数。不接收参数。" +
            "AI 拿到 filePath 后可继续 read_file / search_in_files / find_references。" +
            "详细字段 → load_tool_doc(\"get_editor_file\")。"

    private const val DESC_READ_FILE =
        "读取项目内任意文件的内容(相对项目根或项目内绝对路径)。" +
            "默认从第 0 行读到末尾,大文件按 maxLines(默认 600)截断,可用 startLine / endLine 翻页。" +
            "限制:单文件 > 1.5MB 拒绝读取,改用 search_in_files。" +
            "详细字段 → load_tool_doc(\"read_file\")。"

    private const val DESC_EDIT_FILE =
        "精准修改项目内任意文件的内容。两种模式:" +
            "  - **文本模式**(默认,useRegex=false):oldText 全文精确匹配,默认要求唯一匹配(0 处或 >1 处报错)。" +
            "  - **正则模式**(useRegex=true):oldText 作 Kotlin 正则;replaceAll=true 全文替换。" +
            "原子写:写失败不会污染原文件。限制:单文件 > 3MB 拒绝编辑。" +
            "详细字段 → load_tool_doc(\"edit_file\")。"

    private val DESC_CREATE_FILE =
        "在项目内创建新文件(支持嵌套目录,自动 mkdirs)。" +
            "overwrite 默认 false(防误覆盖);修改已存在文件请改用 edit_file。" +
            "详细字段 → load_tool_doc(\"create_file\")。"

    private const val DESC_SEARCH_IN_FILES =
        "在项目内文件中按文本 / 正则搜索,返回文件路径+行号+列号+匹配内容。" +
            "默认搜索 java / kt / xml / gradle / kts / json / properties / txt / md,不搜图片/二进制。" +
            "可用 filePattern(glob)与 relativeDir 限定范围;单次最多返回 200 条。" +
            "详细字段 → load_tool_doc(\"search_in_files\")。"

    private const val DESC_FIND_REFERENCES =
        "按符号语义查找项目中的引用点(Java/Kotlin/XML)。" +
            "kind: id / string / layout / drawable / color / class / general(默认)。" +
            "与 search_in_files 的区别:本工具是「符号语义」搜索,自动适配资源引用的多种写法。" +
            "详细字段 → load_tool_doc(\"find_references\")。"

    private const val DESC_LIST_FILES =
        "列举项目内某目录下的文件 / 子目录,支持 glob 与递归(最多 10 层)。" +
            "relativeDir 默认 \".\"(项目根),pattern 默认 \"*\",maxEntries 默认 500。" +
            "详细字段 → load_tool_doc(\"list_files\")。"

    // endregion

    private const val DESC_SHEETS_OPERATION =
        "执行 Google 表格操作(operation 决定具体动作类型)。" +
            "大量单元格混合修改(改值+填色+改文字色+删行等)用 batch_modify 一次完成,避免循环调用触发工具调用次数上限。" +
            "安全约束:修改/删除行前先用 search 定位行号;列操作需用户确认;全表审查/修正用 check_translations / fix_translations。" +
            "sheetName 必须**原样**使用工作表名(系统按 A1 规则自动转义)。" +
            "详细字段 → load_tool_doc(\"sheets_basic\")。"

    private const val DESC_ASK_USER =
        "向用户提问并等待回复。options 非空时显示按钮(优先用);为空时用户在输入框回复。" +
            "每次调用都暂停 tool loop,不要反复调用;收到回复后用 task_complete 或操作推进。"

    private const val DESC_REPLACE_SELECTION =
        "把当前 IDE 编辑器选中的硬编码文本替换为对指定 key 的引用。" +
            "XML 布局 → `@string/<key>`,其它文件 → `R.string/<key>`;执行后聊天视图保持打开。" +
            "⚠️ 强约束:在 chatEntry=askAi/extractStrings 场景下,用户选「使用现有 key:」后**必须**先调本工具,再 read_string。" +
            "限制:仅在 chat 弹框入口有效(主面板无编辑器上下文,会失败)。" +
            "详细字段 → load_tool_doc(\"replace_selection\")。"

    private const val DESC_LOAD_TOOL_DOC =
        "按需加载工具的详细使用文档(枚举值、参数约束、示例)。" +
            "返回的文档作为 tool 消息回传;不要重复请求同一工具的文档。" +
            "系统对连续 load_tool_doc 有次数上限,一次最多加载 1-2 个,拿到后立即返回实际工具调用。"

    private const val DESC_TASK_COMPLETE =
        "声明任务已完成,结束当前对话循环。" +
            "唯一的合法终止信号;未调用 = 你仍在执行,系统会持续驱动。" +
            "status: success(完全达成) / partial(部分达成,如用户拒绝) / failed(执行失败)。" +
            "调用后不要在同一次回复中再调用其他工具。"

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
        val descEditFile = appendProjectContext(DESC_EDIT_FILE, projectBase)
        val descCreateFile = appendProjectContext(DESC_CREATE_FILE, projectBase)
        val descSearch = appendProjectContext(DESC_SEARCH_IN_FILES, projectBase)
        val descList = appendProjectContext(DESC_LIST_FILES, projectBase)
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
            add(openAiTool(TOOL_EDIT_FILE, descEditFile, openAiEditFileParams()))
            add(openAiTool(TOOL_CREATE_FILE, descCreateFile, openAiCreateFileParams()))
            add(openAiTool(TOOL_SEARCH_IN_FILES, descSearch, openAiSearchInFilesParams()))
            add(openAiTool(TOOL_FIND_REFERENCES, DESC_FIND_REFERENCES, openAiFindReferencesParams()))
            add(openAiTool(TOOL_LIST_FILES, descList, openAiListFilesParams()))
            // 代办域:用户在主页 Todo tab 维护的清单,AI 可读写
            add(openAiTool(TOOL_TODO_LIST, DESC_TODO_LIST, openAiTodoListParams()))
            add(openAiTool(TOOL_TODO_ADD, DESC_TODO_ADD, openAiTodoAddParams()))
            add(openAiTool(TOOL_TODO_UPDATE, DESC_TODO_UPDATE, openAiTodoUpdateParams()))
            add(openAiTool(TOOL_TODO_DELETE, DESC_TODO_DELETE, openAiTodoDeleteParams()))
            add(openAiTool(TOOL_CURRENT_TIME, DESC_CURRENT_TIME, openAiCurrentTimeParams()))
            add(openAiTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(openAiTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(openAiTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(openAiTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    private fun buildAnthropicTools(ctx: SheetContext, projectBase: String? = null): JsonArray {
        val descSheets = appendSheetContext(DESC_SHEETS_OPERATION, ctx)
        val descFindRows = appendSheetContext(DESC_FIND_ROWS_BY_TEXT, ctx)
        val descGetEditor = appendProjectContext(DESC_GET_EDITOR_FILE, projectBase)
        val descReadFile = appendProjectContext(DESC_READ_FILE, projectBase)
        val descEditFile = appendProjectContext(DESC_EDIT_FILE, projectBase)
        val descCreateFile = appendProjectContext(DESC_CREATE_FILE, projectBase)
        val descSearch = appendProjectContext(DESC_SEARCH_IN_FILES, projectBase)
        val descList = appendProjectContext(DESC_LIST_FILES, projectBase)
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
            add(anthropicTool(TOOL_EDIT_FILE, descEditFile, openAiEditFileParams()))
            add(anthropicTool(TOOL_CREATE_FILE, descCreateFile, openAiCreateFileParams()))
            add(anthropicTool(TOOL_SEARCH_IN_FILES, descSearch, openAiSearchInFilesParams()))
            add(anthropicTool(TOOL_FIND_REFERENCES, DESC_FIND_REFERENCES, openAiFindReferencesParams()))
            add(anthropicTool(TOOL_LIST_FILES, descList, openAiListFilesParams()))
            // 代办域
            add(anthropicTool(TOOL_TODO_LIST, DESC_TODO_LIST, openAiTodoListParams()))
            add(anthropicTool(TOOL_TODO_ADD, DESC_TODO_ADD, openAiTodoAddParams()))
            add(anthropicTool(TOOL_TODO_UPDATE, DESC_TODO_UPDATE, openAiTodoUpdateParams()))
            add(anthropicTool(TOOL_TODO_DELETE, DESC_TODO_DELETE, openAiTodoDeleteParams()))
            add(anthropicTool(TOOL_CURRENT_TIME, DESC_CURRENT_TIME, openAiCurrentTimeParams()))
            add(anthropicTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(anthropicTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(anthropicTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(anthropicTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    /**
     * 引用入口(AskAi / ExtractStrings 弹框)的精简工具集。
     * 只暴露 strings.xml 操作 + replace_selection + 通用工具,
     * 不包含 Google Sheets 与文件操作域(弹框场景用不到)。
     */
    private fun buildOpenAiToolsQuoteEntry(@Suppress("UNUSED_PARAMETER") ctx: SheetContext): JsonArray {
        return JsonArray().apply {
            add(openAiTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(openAiTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(openAiTool(TOOL_DELETE_STRING, DESC_DELETE_STRING, openAiDeleteStringParams()))
            add(openAiTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(openAiTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(openAiTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(openAiTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(openAiTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(openAiTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(openAiTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

    /** Anthropic 协议的引用入口工具集。 */
    private fun buildAnthropicToolsQuoteEntry(@Suppress("UNUSED_PARAMETER") ctx: SheetContext): JsonArray {
        return JsonArray().apply {
            add(anthropicTool(TOOL_INSERT_STRINGS, DESC_INSERT_STRINGS, openAiInsertStringsParams()))
            add(anthropicTool(TOOL_UPDATE_STRING, DESC_UPDATE_STRING, openAiUpdateStringParams()))
            add(anthropicTool(TOOL_DELETE_STRING, DESC_DELETE_STRING, openAiDeleteStringParams()))
            add(anthropicTool(TOOL_QUERY_KEYS, DESC_QUERY_KEYS, openAiQueryKeysParams()))
            add(anthropicTool(TOOL_READ_STRING, DESC_READ_STRING, openAiReadStringParams()))
            add(anthropicTool(TOOL_FIND_KEYS_BY_TEXT, DESC_FIND_KEYS_BY_TEXT, openAiFindKeysByTextParams()))
            add(anthropicTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
            add(anthropicTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(anthropicTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(anthropicTool(TOOL_TASK_COMPLETE, DESC_TASK_COMPLETE, openAiTaskCompleteParams()))
        }
    }

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
                    addProperty(
                        "description",
                        "目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。" +
                            "省略时按以下顺序自动选择:用户在消息中明确指定 > 用户在 UI 选中行所在模块 > `recommendedDefaultModule`(系统计算:优先 currentModule,currentModule 语种/行数偏弱时退回项目最强模块)。"
                    )
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "字符串 key,使用 snake_case。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty(
                        "description",
                        "键为语言目录名(values / values-zh-rCN / values-fr 等)," +
                            "值为对应翻译文本。必须始终包含 \"values\"(默认英语)," +
                            "并覆盖目标模块 xmlFiles 中的所有其他语言(以 `recommendedDefaultModule.xmlFiles[].language` 为准 — 不要用 availableLanguages 推断)。"
                    )
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
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时按 recommendedDefaultModule → currentModule → 行数最多模块的优先级自动选(默认即「推荐模块」)。")
                })
                add("pattern", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选正则表达式。匹配范围由 searchIn 决定:key 名 / 各语种翻译文本 / 两者。为空或省略时列出所有 key(分页)。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,最大返回条数,默认 50,最大 500。")
                })
                add("offset", obj {
                    addProperty("type", "integer")
                    addProperty("description", "可选,分页偏移,默认 0。")
                })
                add("includeTranslations", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "可选,是否在结果中带各语言当前翻译。默认 false。开启后 token 消耗大,谨慎使用。")
                })
                add("searchIn", obj {
                    addProperty("type", "string")
                    add("enum", searchInEnum)
                    addProperty(
                        "description",
                        "可选,搜索范围:key(默认,只匹配 key 名)/ text(跨多语种翻译文本,等价于 find_keys_by_text 但入口统一)/ both(key 名和翻译文本任一命中即可)。"
                    )
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
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key 名。")
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
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key,使用 snake_case。")
                })
                add("translations", obj {
                    addProperty("type", "object")
                    addProperty(
                        "description",
                        "必填,键为语言目录名(values/values-zh-rCN/values-fr 等),值仅包含需要修改的翻译。" +
                            "未列出的语言保持原样。"
                    )
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
                    addProperty("description", "可选,目标 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。")
                })
                add("name", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,字符串 key,使用 snake_case。")
                })
                add("languages", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty(
                        "description",
                        "可选,要删除的语言目录名列表(如 [\"values-fr\", \"values-zh-rCN\"])。" +
                            "为空/null/省略时,删除该 key 在所有语言的翻译(整 key 被移除);" +
                            "非空时,仅删除列表中指定语言的翻译,其他语言保持原样。"
                    )
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
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("text", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,要查找的翻译文本。")
                })
                add("module", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,限定 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时搜索项目中所有模块。")
                })
                add("language", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,限定语言目录(如 values-zh-rTW)。省略时搜索所有语言。"
                    )
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "匹配模式,默认 contains(子串匹配)。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "最大返回条数,默认 30,最大 200。")
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
                    addProperty("description", "必填,要查找的文本。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认用上下文 googleSheets 配置。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认用 defaultSheetName。")
                })
                add("column", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,限定列名(与表头精确匹配,忽略大小写)。例:values-zh-rTW。"
                    )
                })
                add("matchType", obj {
                    addProperty("type", "string")
                    add("enum", matchTypeEnum)
                    addProperty("description", "匹配模式,默认 contains(子串匹配)。")
                })
                add("caseSensitive", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "是否区分大小写,默认 false。")
                })
                add("limit", obj {
                    addProperty("type", "integer")
                    addProperty("description", "最大返回条数,默认 30,最大 200。")
                })
            })
            add("required", JsonArray().apply { add("text") })
        }
    }

    private fun openAiSheetsOperationParams(): JsonObject {
        val operationEnum = JsonArray().apply {
            SheetsOperation.Operation.entries.forEach { add(it.name.lowercase()) }
        }
        val batchEditTypeEnum = JsonArray().apply {
            AiAction.BatchEditType.entries.forEach { add(it.name.lowercase()) }
        }
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("operation", obj {
                    addProperty("type", "string")
                    add("enum", operationEnum)
                    addProperty("description", "操作类型,决定后续字段的取值。")
                })
                add("spreadsheetId", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认使用上下文 googleSheets.defaultSpreadsheetId。")
                })
                add("sheetName", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,默认使用 defaultSheetName 或上下文 availableSheets 中的某个值。")
                })
                add("range", obj {
                    addProperty("type", "string")
                    addProperty("description", "A1 表示法范围,如 \"Sheet1!A1:D10\"。read/write/set_values/fill_color/set_text_color/clear_color/clear_text_color 时使用。")
                })
                add("key", obj {
                    addProperty("type", "string")
                    addProperty("description", "search/read 时按 key 查找第一列匹配行。")
                })
                add("rowNumber", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 行号,insert_row / update_row / delete_row / clear_row 必填;batch_modify 内 update_rows/insert_rows 的起始行号。")
                })
                add("rows", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj { addProperty("type", "string") })
                    })
                    addProperty("description", "二维数组,外层每项是一行数据(单元格字符串数组)。append/insert/update_row 取首元素;batch_modify 内 set_values/update_rows/append_rows/insert_rows 用全部元素。")
                })
                add("columnIndex", obj {
                    addProperty("type", "integer")
                    addProperty("description", "1-based 列号,insert_column / update_column / delete_column / clear_column 必填。")
                })
                add("columnHeader", obj {
                    addProperty("type", "string")
                    addProperty("description", "新列的表头,append_column 建议填写。")
                })
                add("columnValues", obj {
                    addProperty("type", "array")
                    add("items", obj { addProperty("type", "string") })
                    addProperty(
                        "description",
                        "一维数组,首元素为表头,其余为各行值。insert_column / append_column / update_column / clear_column 必填。"
                    )
                })
                add("freezeRowCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_rows 必填。0 表示取消冻结。")
                })
                add("freezeColumnCount", obj {
                    addProperty("type", "integer")
                    addProperty("description", ">= 0,freeze_columns 必填。0 表示取消冻结。")
                })
                add("color", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,背景色。fill_color / batch_modify.fill_color 必填。支持 hex(例 #FF0000、#f0a)或命名色(red/green/blue/yellow/orange/purple/pink/gray/grey/white/black/light_gray/dark_gray/brown/cyan/magenta)。"
                    )
                })
                add("textColor", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "可选,文字色。set_text_color / batch_modify.set_text_color 必填。颜色格式同 color。"
                    )
                })
                add("rowTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "array")
                        add("items", obj {
                            addProperty(
                                "type",
                                "string"
                            )
                            addProperty("description", "颜色字符串,或 null 表示该格不上色。")
                        })
                    })
                    addProperty(
                        "description",
                        "可选,与 rows 同形(行操作为 [[c1,c2,...]],write 为 [[..],[..]])的二维数组。null 元素表示该格不上色。仅对当前写入的单元格生效。"
                    )
                })
                add("columnTextColors", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "string")
                        addProperty("description", "颜色字符串,或 null 表示该格不上色。")
                    })
                    addProperty(
                        "description",
                        "可选,与 columnValues 同形的一维数组,按行顺序逐个对应。null 元素表示该格不上色。"
                    )
                })
                add("batchEdits", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "object")
                        add("properties", obj {
                            add("type", obj {
                                addProperty("type", "string")
                                add("enum", batchEditTypeEnum)
                                addProperty("description", "批量子操作类型。")
                            })
                            add("range", obj {
                                addProperty("type", "string")
                                addProperty("description", "A1 表示法,set_values/fill_color/set_text_color/clear_color/clear_text_color 必填。")
                            })
                            add("rows", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj { addProperty("type", "string") })
                                })
                                addProperty("description", "二维数组(每行一格字符串数组)。set_values/update_rows/append_rows/insert_rows 必填。")
                            })
                            add("rowNumber", obj {
                                addProperty("type", "integer")
                                addProperty("description", "1-based 起始行号,update_rows/insert_rows 必填。")
                            })
                            add("rowNumbers", obj {
                                addProperty("type", "array")
                                add("items", obj { addProperty("type", "integer") })
                                addProperty("description", "1-based 行号列表,delete_rows/clear_rows 必填。")
                            })
                            add("color", obj {
                                addProperty("type", "string")
                                addProperty("description", "颜色(hex 或命名色),fill_color/set_text_color 必填。")
                            })
                            add("rowTextColors", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj {
                                        addProperty("type", "string")
                                        addProperty("description", "颜色字符串,或 null。")
                                    })
                                })
                                addProperty("description", "可选,与 rows 同形的二维矩阵,逐格文字色,null 表示该格不上色。")
                            })
                            add("rowBackgroundColors", obj {
                                addProperty("type", "array")
                                add("items", obj {
                                    addProperty("type", "array")
                                    add("items", obj {
                                        addProperty("type", "string")
                                        addProperty("description", "颜色字符串,或 null。")
                                    })
                                })
                                addProperty("description", "可选,与 rows 同形的二维矩阵,逐格背景色,null 表示该格不上色。")
                            })
                        })
                        add("required", JsonArray().apply { add("type") })
                    })
                    addProperty(
                        "description",
                        "批量修改列表,仅 batch_modify 使用。用于一次性把多种操作(改值/填色/改文字色/删行/清空行/插入行)合并到一次工具调用,后端会自动分组成最少的 Google API 请求。详细字段见 load_tool_doc(\"sheets_batch_modify\")。"
                    )
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
            // 文件 / 内容 / 引用操作
            add("get_editor_file")
            add("read_file")
            add("edit_file")
            add("create_file")
            add("search_in_files")
            add("find_references")
            add("list_files")
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
        return obj {
            addProperty("type", "object")
            add("properties", obj {
                add("title", obj {
                    addProperty("type", "string")
                    addProperty("description", "必填,代办的标题(trim 后非空)。空字符串会被拒绝并返回错误 tool_result。")
                })
                add("content", obj {
                    addProperty("type", "string")
                    addProperty("description", "可选,详细描述(多行,允许为空)。")
                })
                add("priority", obj {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("LOW")
                        add("NORMAL")
                        add("HIGH")
                        add("URGENT")
                    })
                    addProperty("description", "可选,优先级;为空/未知时回退 NORMAL。URGENT/HIGH 会浮在 Todo tab 列表顶部。")
                })
                add("reminderTime", obj {
                    addProperty("type", "integer")
                    addProperty(
                        "description",
                        "可选,首次提醒时间(Unix 毫秒时间戳)。" +
                            "用户说「5 分钟后提醒我喝水」时,先把 now + 5*60*1000 算出来再传;" +
                            "说「明天下午 3 点」时,用本地时区算出对应时间戳再传(系统按本地时区解析)。" +
                            "省略时 = 不设提醒。新增后会立即进入调度队列,到点触发右下角弹框。"
                    )
                })
                add("recurrence", obj {
                    addProperty("type", "string")
                    add("enum", recurrenceEnum)
                    addProperty(
                        "description",
                        "可选,循环类型:NONE(默认,一次性)/ DAILY(每天固定时间)/ " +
                            "CUSTOM(自定义星期几,配合 recurrenceDays)。" +
                            "**没有 WEEKDAYS / WEEKLY 这两个值** —— 用户说「工作日」请传 CUSTOM + recurrenceDays=[1,2,3,4,5]," +
                            "「周末」传 CUSTOM + recurrenceDays=[6,7]。" +
                            "省略 = NONE(一次性)。触发后:NONE 自动清除;DAILY/CUSTOM 自动滚动到下一次。"
                    )
                })
                add("recurrenceDays", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "integer")
                        addProperty(
                            "description",
                            "1=周一, 2=周二, ..., 7=周日。仅 recurrence=CUSTOM 时使用," +
                                "DAILY/NONE 忽略本字段。" +
                                "例:用户说「每周一三五提醒开会」 → [1, 3, 5];" +
                                "「工作日提醒我」 → [1, 2, 3, 4, 5];" +
                                "「周末提醒我」 → [6, 7]。"
                        )
                    })
                    addProperty(
                        "description",
                        "可选,自定义循环的星期几列表。仅 recurrence=CUSTOM 时生效。"
                    )
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
                    addProperty("description", "新优先级(null = 不改;大小写不敏感 / 未知回退 NORMAL)。")
                })
                add("isCompleted", obj {
                    addProperty("type", "boolean")
                    addProperty("description", "新完成状态(null = 不改;true / false 直接赋值;true 时系统自动写 completedAt 时间戳)。")
                })
                add("reminderTime", obj {
                    addProperty("type", "integer")
                    addProperty(
                        "description",
                        "可选,新提醒时间(Unix 毫秒时间戳)。" +
                            "null = 不改;省略 = 不改;不传 recurrence 时,语义 = 一次性提醒。" +
                            "配合 clearReminder=true 可显式清除提醒。"
                    )
                })
                add("recurrence", obj {
                    addProperty("type", "string")
                    add("enum", recurrenceEnum)
                    addProperty(
                        "description",
                        "新循环类型(null = 不改;其它语义同 todo_add.recurrence)。" +
                            "**没有 WEEKDAYS / WEEKLY 这两个值** —— 「工作日」改用 CUSTOM + recurrenceDays=[1,2,3,4,5]," +
                            "「周末」用 CUSTOM + recurrenceDays=[6,7]。"
                    )
                })
                add("recurrenceDays", obj {
                    addProperty("type", "array")
                    add("items", obj {
                        addProperty("type", "integer")
                        addProperty("description", "1-7 表示周一到周日,仅 recurrence=CUSTOM 时使用。")
                    })
                    addProperty("description", "新自定义循环的星期几(null = 不改)。")
                })
                add("clearReminder", obj {
                    addProperty("type", "boolean")
                    addProperty(
                        "description",
                        "可选,显式清除整条提醒(等价于把 TodoItem.reminder 置 null)。" +
                            "true 时,即便同时传了 reminderTime/recurrence 也以清除为准。" +
                            "用户说「把 X 的提醒删了」/「X 不用再提醒了」时设 true。"
                    )
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
                add("key", obj {
                    addProperty("type", "string")
                    addProperty(
                        "description",
                        "必填,要引用的字符串 key(snake_case)。系统会把当前 IDE 编辑器选中的硬编码文本替换为 `@string/<key>`(XML 布局)或 `R.string.<key>`(其它文件),并关闭聊天视图。"
                    )
                })
            })
            add("required", JsonArray().apply { add("key") })
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

    // endregion

    // endregion

    private fun obj(builder: JsonObject.() -> Unit): JsonObject = JsonObject().apply(builder)
}
