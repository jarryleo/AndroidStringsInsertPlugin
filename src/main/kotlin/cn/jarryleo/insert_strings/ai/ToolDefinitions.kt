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
    const val TOOL_REPLACE_SELECTION = "replace_selection"
    const val TOOL_ASK_USER = "ask_user"
    const val TOOL_LOAD_TOOL_DOC = "load_tool_doc"
    const val TOOL_TASK_COMPLETE = "task_complete"

    // region 工具描述文案(主 prompt 引用,这里集中维护)

    private const val DESC_INSERT_STRINGS =
        "向 Android strings.xml 插入或修改翻译字符串。" +
            "可同时调用多次以插入多个字符串。" +
            "translations 必须始终包含 \"values\"(默认英语),并覆盖模块内的所有语言 —— 一字不差对照 `recommendedDefaultModule.xmlFiles[].language`(或显式 module 的 `xmlFiles[].language`)。" +
            "module 优先级:用户在消息中**明确指定** > 用户在 UI 中**选中行所在的模块** > 省略让系统用 `recommendedDefaultModule`(优先 currentModule,偏弱时退回最强模块)。" +
            "若只想修改个别语言,请改用 update_string(部分语言更新,不覆写其他语言)。" +
            "**插入前的两道查重均由 AI 自助完成,系统不再自动跑**:" +
            "  1. **原文查重(必做,主要查重依据)**:用 find_keys_by_text 扫描**用户消息中的原始文本**" +
            "(用户从布局/代码中选中的硬编码文本,或用户在消息中直接输入的待翻译文本)——" +
            "**不传 language 参数**(跨所有语言目录搜,不限 values/),matchType=exact 跑一次、再 contains 兜底," +
            "看是否已有与该原文完全一致或高度相似的现有 key。" +
            "原因:用户选中的硬编码文本通常**就是目标语言翻译**(如中文「登录」)," +
            "若仅用 AI 自己翻译后的 values 译文(英语)查重,会漏掉「原文已存在、values/ 英语译文用词不同」的场景。" +
            "  2. **Key 名查重**:生成 key 后,用 query_keys(searchIn=key) 检查你打算用的 key 名是否已存在(避免插入后撞名)。" +
            "两道查重均命中时,**用一次 ask_user 列出全部命中项**,options 统一格式(便于你后续按选项文本判断用户决策):" +
            "  - **「使用现有 key:<existing_key>」** — key 名只允许 `[A-Za-z_][A-Za-z0-9_]*`;沿用现有 key,跳过本次写入。" +
            "  - **「插入新 key」** — 忽略查重,按原计划新增 key(可能产生重复文案的不同 key)。" +
            "  - **「取消操作」** — 放弃本次插入。" +
            "**用户选择后的处理流程(由 AI 自行驱动,系统不再自动触发替换)**:" +
            "  - 选「使用现有 key:<existing_key>」—— ⚠️ **强约束:必须按顺序执行以下两步,不可跳过第一步** ⚠️:" +
            "    - **第一步(必做)**:**直接读上下文 JSON 里的 `chatEntry` 字段,不要再自己「判断」**:" +
            "      - `chatEntry == \"extractStrings\"` 或 `chatEntry == \"askAi\"` 且 `editorSelection` 非 null" +
            " —— 入口已捕获用户从布局/代码中选中的硬编码文本," +
            "**必须先调用 replace_selection(key=<existing_key>)** 把该选区替换为" +
            "`@string/<existing_key>`(XML 布局)或 `R.string/<existing_key>`(其它文件);" +
            "工具返回成功后**才**进入第二步。" +
            "      - `chatEntry == \"mainPanel\"` 或 `editorSelection == null` —— 跳过本步。" +
            "      **常见错误:不要在 chatEntry=extractStrings/askAi 且 editorSelection 非 null 时" +
            "直接调 read_string 跳过 replace_selection** —— 这会让硬编码文本保留在文件中。" +
            "    - **第二步(必做)**:调用 read_string(<existing_key>) 取现有 key 的全语种翻译," +
            "逐项检查是否准确、是否缺漏;若有修正需求,先 ask_user 询问用户是否修正," +
            "得到肯定答复后用 update_string 精准补全;若已完整准确,直接 task_complete 结束。" +
            "    - **不要**再调用 insert_strings(那个待插入的新 key 已被忽略)。" +
            "  - 选「插入新 key」:用 query_keys 查你准备生成的 key 名是否已存在,若存在重新生成一个不冲突的 key(长度仍不超过 40 字符);" +
            "然后调用 insert_strings(若来自布局/代码选区,driver 在写完后会自动触发 onInsertStringsInserted 完成硬编码文本的替换,无需你再调用 replace_selection)。" +
            "  - 选「取消操作」:无需任何处理,直接 task_complete 即可。" +
            "一次插入多个 key 时,任一命中都要用一次 ask_user 列出全部命中项,不可只对部分 key 跳过查重。"

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
            "pattern 为空时列出所有 key;非空时按正则匹配。" +
            "searchIn 控制匹配范围:key(默认,只匹配 key 名)/ text(跨多语言翻译文本)/ both(并集)。" +
            "includeTranslations=true 时返回各语言当前翻译(消耗较多 token,谨慎使用)。" +
            "module 省略时按 recommendedDefaultModule → currentModule → 行数最多模块的优先级自动选。" +
            "适用场景:用户说「找一下关于房间的 key」「列出所有错误提示的 key」,或 AI 需要先发现 key 名再修改。" +
            "想反查某段翻译属于哪个 key 时,优先用 searchIn=text 而不是 find_keys_by_text —— 一次返回带 key 列表 + 全部语种。"

    private const val DESC_READ_STRING =
        "读取指定 key 在模块所有语言的当前翻译,返回 key+各语言文本+文件路径。" +
            "适用场景:用户说「看看 X 现在怎么翻译的」,AI 在修改前先确认原文,避免覆盖已有正确翻译。"

    private const val DESC_FIND_KEYS_BY_TEXT =
        "strings.xml 反查:通过翻译文本查找对应的 key。" +
            "支持 exact(完全相等)/ contains(子串,默认)/ regex(正则) 三种匹配模式。" +
            "可选限定 module(只查该模块)和 language(只查该语言目录,例 values-zh-rTW)。" +
            "适用场景:用户看到一段文字想反查是哪个 key,排查重复翻译,跨语言确认某文本对应哪个 key。"

    private const val DESC_FIND_ROWS_BY_TEXT =
        "Google Sheets 反查:在表格中按文本搜索行,返回行号+列名+整行内容。" +
            "支持 exact/ contains(默认)/ regex 三种匹配模式,可选 column(只查指定列名)。" +
            "适用场景:用户问「这个翻译对应表格里哪一行」,查重,定位某文案在 sheet 中的位置。" +
            "注意:sheetName 参数必须**原样**使用下方工作表名(包含点、空格等特殊字符,不要加单引号)," +
            "系统会按 Google Sheets A1 规则自动处理转义。"

    // region 文件操作域描述(2026 新增)

    private const val DESC_GET_EDITOR_FILE =
        "获取当前 IDE 编辑器中打开的文件信息:完整路径、文件名带后缀(用于判断文件类型,如 MainActivity.kt / activity_main.xml)、" +
            "当前选中的文字、选区起止行号、文件总行数。" +
            "适用场景:用户说「改一下我打开的这个文件」「解释我选中的代码」,AI 先调用本工具确认上下文;不传任何参数。" +
            "返回 fileType 后缀(kt/java/xml/...)和 language 分类,kotlin/java/xml 可直接读,其它后缀注意可能是配置/资源文件。"

    private const val DESC_READ_FILE =
        "读取当前 IDE 项目内任意文件的内容(相对项目根路径或项目内的绝对路径)。" +
            "适用场景:AI 拿到文件路径(从 get_editor_file / search_in_files / find_references / list_files 取得)后想看完整内容。" +
            "默认从第 0 行读到末尾,大文件会按 maxLines(默认 600)截断,可用 startLine / endLine 翻页继续读。" +
            "限制:单文件 > 1.5MB 会拒绝读取,改用 search_in_files 检索指定内容。"

    private const val DESC_EDIT_FILE =
        "精准修改项目内任意文件的内容。" +
            "两种模式(同时支持):" +
            "  - **文本模式**(默认,useRegex=false):用 oldText 全文精确匹配,匹配 1 处时直接替换;0 处报错(让 AI 用 read_file 复查);" +
            "    匹配 >1 处且 replaceAll=false 时也报错(让 AI 扩展 oldText 到唯一,或开启 replaceAll)。" +
            "  - **正则模式**(useRegex=true):用 oldText 作 Kotlin 正则;同样默认要求唯一匹配,replaceAll=true 时全文替换。" +
            "原子写:写失败不会污染原文件(临时文件 + rename)。" +
            "适用场景:用户说「把第 X 行的 foo 改成 bar」「把所有 TODO 替换成 FIXME」,AI 先 read_file 拿原文,再用本工具改。" +
            "限制:单文件 > 3MB 拒绝编辑,需拆为多次小范围操作。"

    private val DESC_CREATE_FILE =
        "在项目内创建新文件(支持嵌套目录,自动 mkdirs)。" +
            "参数:path 相对项目根或项目内绝对路径;content 文件内容;overwrite 文件已存在时是否覆盖(默认 false,防误覆盖)。" +
            "适用场景:用户说「新建一个 util 类」「生成 README」「在 build.gradle 加一段配置(先用 read_file 看现有内容,再用 edit_file 改,而不是用本工具整文件覆盖)」。"

    private const val DESC_SEARCH_IN_FILES =
        "在项目内文件中按文本 / 正则搜索,返回文件路径+行号+列号+匹配内容。" +
            "默认仅搜索 java / kt / xml 文件(也包括 gradle / kts / json / properties / txt / md),不搜图片/二进制。" +
            "适用场景:用户说「在项目里找一下 XXX 的用法」「看哪个文件调用了 getResult」,AI 在调用 edit_file 前先用本工具定位修改点。" +
            "可选 filePattern(glob,如 \"*.kt\")与 relativeDir(子目录,如 \"app/src/main\")缩小范围,避免大项目全量扫描慢。" +
            "限制:单次最多返回 200 条命中,超出请收紧 pattern / 限定目录。"

    private const val DESC_FIND_REFERENCES =
        "查找符号在项目中的引用点(Java/Kotlin/XML 文件)。" +
            "kind 取值:" +
            "  - **id**:匹配 R.id.xxx / @+id/xxx / @id/xxx(资源 id 引用)" +
            "  - **string**:匹配 R.string.xxx / @string/xxx(字符串资源引用)" +
            "  - **layout**:匹配 R.layout.xxx / @layout/xxx(布局引用)" +
            "  - **drawable**:匹配 R.drawable.xxx / @drawable/xxx(图标引用)" +
            "  - **color**:匹配 R.color.xxx / @color/xxx(颜色引用)" +
            "  - **class**:按标识符边界匹配类名" +
            "  - **general**(默认):按标识符边界匹配任意符号名" +
            "适用场景:用户说「这个 key / view id / 类名 在哪些地方被引用」,重构前评估影响面。" +
            "与 search_in_files 的区别:本工具是「符号语义」搜索,自动适配资源引用的多种写法;search_in_files 是「字面文本」搜索。"

    private const val DESC_LIST_FILES =
        "列举项目内某目录下的文件 / 子目录,支持 glob 与递归。" +
            "参数:relativeDir 相对项目根的子目录(\".\" 或空表示项目根);pattern glob(默认 \"*\");" +
            "recursive 是否递归子目录(限制最多 10 层);includeDirs 是否包含目录。" +
            "适用场景:用户说「项目里有哪些 layout 文件」「app/src/main/java 下都有什么包」,AI 探索项目结构。" +
            "限制:单次最多返回 500 条;大目录请用 filePattern 限定(如 \"*.kt\")。"

    // endregion

    private const val DESC_SHEETS_OPERATION =
        "执行 Google 表格操作。operation 决定具体动作类型。" +
            "不确定用法时先调用 load_tool_doc(\"sheets_basic\"/\"sheets_row_ops\"/...) 获取详细文档。" +
            "需要一次性对大量单元格做混合修改(改值+填色+改文字色+删行…)时,优先用 batch_modify,把多个子操作合并到一次工具调用,后端自动分组成最少 Google API 请求,避免逐格调用触发工具调用次数上限。" +
            "安全约束:修改/删除行前先用 search 定位行号;列操作需用户确认;全表检查/修正用 check_translations/fix_translations。" +
            "重要:sheetName 参数必须**原样**使用下方工作表名(包含点、空格等特殊字符,不要加单引号)," +
            "系统会按 Google Sheets A1 规则自动处理转义。省略时使用默认工作表。"

    private const val DESC_ASK_USER =
        "向用户提问并等待用户回复。options 非空时显示按钮供用户点击;options 为空时用户可在聊天输入框中输入回复内容。" +
            "使用场景:关键参数缺失、风险操作确认、目标不明确需要澄清。" +
            "重要:每次调用都会暂停 tool loop 等待用户响应,不会自动继续 — 因此若想退出循环,必须在收到用户回复后调用 task_complete 或采取其他操作,不能连续反复调用本工具。"

    private const val DESC_REPLACE_SELECTION =
        "把当前 IDE 编辑器选中的硬编码文本替换为对指定 key 的引用。" +
            "XML 布局(res/layout* 等)文件替换为 `@string/<key>`,其它文件(Kotlin/Java/...)替换为 `R.string/<key>`。" +
            "在 EDT 上 WriteCommandAction 中执行;**执行后聊天视图保持打开**,AI 继续调用 read_string / ask_user / update_string 推进翻译查重的后续流程。" +
            "⚠️ **强约束:这是用户选「使用现有 key:<existing_key>」后必须执行的第一步** ⚠️ ——若插入来自布局/代码选区," +
            "AI **必须**先调用本工具把硬编码文本替换为对 key 的引用,再调用 read_string 校验现有翻译。" +
            "**绝不可**在用户选「使用现有 key」后跳过本工具直接调 read_string ——" +
            "这会让硬编码文本保留在文件中,违反用户提取字符串的初衷。" +
            "典型用法:" +
            "  - **翻译查重 +「使用现有 key」**:用户点选 ask_user 的「使用现有 key:<existing_key>」选项后,AI 用本工具触发实际替换(然后继续检查现有翻译是否需要修正)。" +
            "  - **AskAi 入口通用替换**:用户在 AskAi 弹框下选中一段硬编码文字,要求 AI 提取为 strings.xml 并替换为对 key 的引用。" +
            "限制:仅在 chat 入口(Extract / AskAi 弹框)有效;主面板聊天视图无编辑器上下文,会返回失败信息。"

    private const val DESC_LOAD_TOOL_DOC =
        "按需加载工具的详细使用文档(枚举值、参数约束、示例)。" +
            "返回的文档会作为工具结果回传给你,你据此继续返回实际执行动作,不要重复请求同一工具的文档。"

    private const val DESC_TASK_COMPLETE =
        "声明任务已完成,结束当前对话循环。" +
            "这是唯一的「合法终止」信号 — 没有调用本工具 = 你仍在执行,系统会持续驱动你继续。" +
            "status 取值: success(完全达成) / partial(部分达成,如用户拒绝) / failed(执行失败)。" +
            "调用本工具后不要在同一次回复中再调用其他工具。"

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
            add(anthropicTool(TOOL_ASK_USER, DESC_ASK_USER, openAiAskUserParams()))
            add(anthropicTool(TOOL_LOAD_TOOL_DOC, DESC_LOAD_TOOL_DOC, openAiLoadToolDocParams()))
            add(anthropicTool(TOOL_REPLACE_SELECTION, DESC_REPLACE_SELECTION, openAiReplaceSelectionParams()))
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
