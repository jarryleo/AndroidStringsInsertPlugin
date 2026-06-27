package cn.jarryleo.insert_strings.ai

sealed class AiAction {
    data class InsertStrings(
        val module: String?,
        val name: String,
        val translations: Map<String, String>
    ) : AiAction()

    data class AskUser(
        val question: String,
        val options: List<String> = emptyList()
    ) : AiAction()

    /**
     * AI 请求加载某个工具的详细使用文档（按需加载机制）。
     * 系统会把 [tool] 对应的完整文档注入为 tool 消息，AI 据此继续返回实际执行动作。
     * @param tool 工具名，对应 AITranslator.TOOL_DOCS 的 key
     */
    data class LoadToolDoc(
        val tool: String
    ) : AiAction()

    data class SheetsOperation(
        val operation: Operation,
        val spreadsheetId: String?,
        val sheetName: String?,
        val range: String?,
        val key: String?,
        val rowNumber: Int?,
        val rows: List<List<String>>?,
        val columnIndex: Int?,
        val columnHeader: String?,
        val columnValues: List<String>?,
        val freezeRowCount: Int? = null,
        val freezeColumnCount: Int? = null,
        val color: String? = null,
        val textColor: String? = null,
        val rowTextColors: List<List<String?>>? = null,
        val columnTextColors: List<String?>? = null,
        /**
         * 批量修改:仅当 [operation] == BATCH_MODIFY 时使用。
         * 一次工具调用执行多种修改(改值、填色、删行等),内部尽量合并为最少的 Google API 请求。
         */
        val batchEdits: List<BatchEdit>? = null
    ) : AiAction() {
        enum class Operation {
            READ,
            WRITE,
            SEARCH,
            LIST_SHEETS,
            INSERT_ROW,
            UPDATE_ROW,
            DELETE_ROW,
            APPEND_ROW,
            CLEAR_ROW,
            INSERT_COLUMN,
            APPEND_COLUMN,
            DELETE_COLUMN,
            CLEAR_COLUMN,
            UPDATE_COLUMN,
            CHECK_TRANSLATIONS,
            FIX_TRANSLATIONS,
            FREEZE_ROWS,
            FREEZE_COLUMNS,
            FILL_COLOR,
            CLEAR_COLOR,
            SET_TEXT_COLOR,
            CLEAR_TEXT_COLOR,
            /**
             * 批量修改:把多种操作(改值、填色、删行…)合并在一次工具调用里,
             * 后端会自动分组成最少的 Google API 请求,避免逐格操作触发工具调用次数上限。
             */
            BATCH_MODIFY
        }
    }

    /**
     * 批量修改的单个动作项,见 [SheetsOperation.Operation.BATCH_MODIFY]。
     *
     * 所有字段均按需使用(每个 type 只用其中一部分)。
     *
     * - [SET_VALUES]:    覆盖式写入任意矩形范围。 [range] + [rows](二维) 必填。
     * - [FILL_COLOR]:    在 [range] 上填充背景色, [color] 必填(hex 或命名色)。
     * - [CLEAR_COLOR]:   清除 [range] 的背景色。
     * - [SET_TEXT_COLOR]:在 [range] 上设置文字色, [color] 必填(hex 或命名色)。
     * - [CLEAR_TEXT_COLOR]:清除 [range] 的文字色。
     * - [UPDATE_ROWS]:   连续更新多行(从 [rowNumber] 起的 [rows] 行, 起点 1-based)。
     *                    [rowTextColors] / [rowBackgroundColors] 是与 [rows] 同形的二维矩阵,
     *                    null 元素表示该格不上色。
     * - [APPEND_ROWS]:   在工作表末尾追加多行, [rows] 二维数组, [rowTextColors] / [rowBackgroundColors] 可选。
     * - [INSERT_ROWS]:   在 [rowNumber] 位置插入多行(原行及之后下移), [rows] 二维数组。
     * - [DELETE_ROWS]:   删除 [rowNumbers] 指定的多个行(1-based 列表)。
     * - [CLEAR_ROWS]:    清空 [rowNumbers] 指定的多个行(保留行)。
     */
    data class BatchEdit(
        val type: BatchEditType,
        val range: String? = null,
        val rows: List<List<String>>? = null,
        val rowNumber: Int? = null,
        val rowNumbers: List<Int>? = null,
        val color: String? = null,
        val rowTextColors: List<List<String?>>? = null,
        val rowBackgroundColors: List<List<String?>>? = null
    )

    /** 批量修改的子操作类型。 */
    enum class BatchEditType {
        SET_VALUES,
        FILL_COLOR,
        CLEAR_COLOR,
        SET_TEXT_COLOR,
        CLEAR_TEXT_COLOR,
        UPDATE_ROWS,
        APPEND_ROWS,
        INSERT_ROWS,
        DELETE_ROWS,
        CLEAR_ROWS
    }

    /**
     * AI 声明任务完成。
     * 采用 function calling 后,这是唯一的「合法终止」信号 ——
     * 没有调用本动作就代表 AI 仍在执行,系统不会停止对话循环。
     *
     * @param summary 给用户看的最终总结
     * @param status 任务状态: success 完全达成 / partial 部分达成(目标被中断) / failed 执行失败
     * @param notes  可选的补充说明,例如「用户拒绝」或「缺少必要信息」
     */
    data class TaskComplete(
        val summary: String,
        val status: String,
        val notes: String? = null
    ) : AiAction()

    /**
     * 获取「当前时间」(2026.x 新增)。
     *
     * 用途:让 AI 在需要把"5 分钟后" / "明天下午 3 点" / "下周一 9 点" 等相对或自然语言时间
     * 翻译成 unix 毫秒时间戳时,拿到一个**新鲜的**当前时间。
     *
     * 为什么不只用 context 里的 `now`:
     * - context 里的 `now` 是发送 user 消息那一刻的时间;
     *   如果 tool loop 跑了几秒甚至几十秒(慢 AI / 重试 / 流式响应),`now` 就过时了。
     * - 这个工具每次调用都返回最新的 `System.currentTimeMillis()`,无任何副作用。
     *
     * 不需要任何参数;返回的内容等价于 context 里的 `now` 字段。
     */
    data class CurrentTime(
        val dummy: String? = null
    ) : AiAction()

    /**
     * 读取代办列表(主动发现 AI 上下文里没有的待办 / 提醒用户 / 在决策前查状态用)。
     *
     * 与 chat context 里注入的 `todos.active` 摘要的区别:
     * - 摘要只列 active 的前 N 条(避免 token 爆炸),适合让 AI 知道「有活儿要干」;
     * - 本工具可显式拉**全量 / completed / 指定 limit** 的列表,适合「逐条核对」或
     *   「我刚才提的 X 找到没」等需要精确信息的场景。
     *
     * @param filter 过滤模式:`active` / `completed` / `all`;默认 `active`。
     * @param limit  最大返回条数(默认 50);不传或为 null 时取 50,适合绝大多数场景。
     */
    data class TodoList(
        val filter: String,
        val limit: Int?
    ) : AiAction()

    /**
     * 新增一条代办。
     *
     * @param title    必填,代办的标题(trim 后非空);空时 driver 拒绝并返回错误 tool_result。
     * @param content  可选,详细描述(允许为空)。
     * @param priority 可选,优先级;为空时回退 NORMAL。容错大小写、空格、未知值。
     * @param reminderTime     可选,首次提醒时间(unix 毫秒时间戳);null = 不设置提醒。
     *                          AI 转换「5 分钟后」「明天下午 3 点」时,自己算好时间戳再传。
     *                          一次性提醒时 = 触发时间;循环提醒时 = 首次触发时间,
     *                          后续按 [recurrence] 滚动。
     *                          与 [reminderDate] 互斥:传了 [reminderDate] 时,本字段被忽略,
     *                          系统按"日期 + 时分"重新组装 timestamp。
     * @param reminderDate     可选,指定日期提醒(YYYY-MM-DD 字符串,本地日期)。
     *                          **仅一次性提醒生效**(recurrence=NONE);循环类型下传了也会被忽略。
     *                          配合 [reminderTimeOfDay] 一起用,系统按本地时区组装 timestamp,
     *                          AI 不用算时区、跨日、跨年。例:用户说「3 月 15 日上午 10 点提醒我」,
     *                          AI 传 reminderDate="2026-03-15", reminderTimeOfDay="10:00"。
     * @param reminderTimeOfDay 可选,时分(HH:MM 24h 字符串),仅与 [reminderDate] 配套使用。
     *                          缺省时默认 09:00。例:"09:30" / "15:00" / "23:45"。
     *                          不与 [reminderDate] 同传时本字段被忽略。
     * @param recurrence     可选,循环类型(NONE/DAILY/CUSTOM,无 WEEKDAYS/WEEKLY);null/未知 = NONE。
     *                        NONE 时,触发后自动清除提醒;
     *                        DAILY/CUSTOM 时,触发后滚动到下一次。
     *                        AI 转换「工作日」用 CUSTOM + [1,2,3,4,5],「周末」用 CUSTOM + [6,7]。
     * @param recurrenceDays 可选,自定义循环的星期几(1=周一,...,7=周日);
     *                        仅 [recurrence] = CUSTOM 时使用,其它类型忽略。
     *                        AI 转换「每周一三五」时传 [1, 3, 5];"工作日"传 [1,2,3,4,5];"周末"传 [6,7]。
     */
    data class TodoAdd(
        val title: String,
        val content: String,
        val priority: String,
        val reminderTime: Long? = null,
        val reminderDate: String? = null,
        val reminderTimeOfDay: String? = null,
        val recurrence: String? = null,
        val recurrenceDays: List<Int>? = null,
    ) : AiAction()

    /**
     * 更新一条已有代办(按 [id] 定位)。
     *
     * 任何字段为 null 表示「不改」,只更新非 null 字段;这样 AI 可以做"只勾选完成"
     * / "只改优先级" 等最小动作。
     *
     * @param id          必填,代办的稳定 id(由 [cn.jarryleo.insert_strings.ai.TodoService] 分配)。
     *                    AI 拿到 id 的途径:调用 [TodoList] 看返回字段。
     * @param title       新标题(null = 不改;trim 后空 = 校验失败)。
     * @param content     新描述(null = 不改;空串 = 清空描述)。
     * @param priority    新优先级(null = 不改;大小写不敏感 / 未知回退 NORMAL)。
     * @param isCompleted 新完成状态(null = 不改;true / false 直接赋值)。
     * @param reminderTime   提醒时间(同 [TodoAdd.reminderTime]);null = 不改;
     *                        配合 [clearReminder]=true 可实现「清除提醒」语义(此时 reminderTime/recurrence 等被忽略)。
     *                        与 [reminderDate] 互斥:传了 [reminderDate] 时,本字段被忽略,
     *                        系统按"日期 + 时分"重新组装 timestamp。
     * @param reminderDate     指定日期(YYYY-MM-DD 字符串),语义同 [TodoAdd.reminderDate];
     *                          null = 不改;**仅一次性提醒生效**。
     * @param reminderTimeOfDay 时分(HH:MM 字符串),语义同 [TodoAdd.reminderTimeOfDay];
     *                          null = 不改;**仅与 [reminderDate] 配套使用**,且仅一次性提醒生效。
     * @param recurrence     循环类型(同 [TodoAdd.recurrence]);null = 不改。
     * @param recurrenceDays 自定义循环的星期几(同 [TodoAdd.recurrenceDays]);null = 不改。
     * @param clearReminder  设为 true 时清除整条提醒(等价于把 TodoItem.reminder 置 null),
     *                       即便 reminderTime/recurrence 也同时传了也以 clearReminder 优先。
     */
    data class TodoUpdate(
        val id: String,
        val title: String?,
        val content: String?,
        val priority: String?,
        val isCompleted: Boolean?,
        val reminderTime: Long? = null,
        val reminderDate: String? = null,
        val reminderTimeOfDay: String? = null,
        val recurrence: String? = null,
        val recurrenceDays: List<Int>? = null,
        val clearReminder: Boolean = false,
    ) : AiAction()

    /**
     * 按 id 删除一条代办。id 不存在时 driver 返回错误 tool_result,不会静默成功。
     */
    data class TodoDelete(
        val id: String
    ) : AiAction()

    /**
     * 列出/搜索模块内的字符串 key(AI 主动发现能力)。
     *
     * @param module             目标模块名,省略时按 recommendedDefaultModule → currentModule → fallback 优先级
     * @param pattern            可选正则;为空时退化为全量列表
     * @param limit              最大返回条数,默认 50
     * @param offset             分页偏移,默认 0
     * @param includeTranslations 是否在结果中带各语言当前翻译(默认 false,节省 token)
     * @param searchIn           搜索范围: KEY=只匹配 key 名(默认,向后兼容);
     *                           TEXT=只匹配各语言翻译文本(类似 find_keys_by_text);
     *                           BOTH=任一命中即可(并集)
     */
    data class QueryKeys(
        val module: String?,
        val pattern: String?,
        val limit: Int?,
        val offset: Int?,
        val includeTranslations: Boolean,
        val searchIn: SearchIn = SearchIn.KEY
    ) : AiAction() {
        enum class SearchIn { KEY, TEXT, BOTH }
    }

    /**
     * 读取指定 key 在模块所有语言的当前翻译(AI 精确读取能力)。
     * @param module 目标模块名,省略时用 currentModule
     * @param name   必填,字符串 key
     */
    data class ReadString(
        val module: String?,
        val name: String
    ) : AiAction()

    /**
     * 部分语言更新指定 key 的翻译(精准修改,不覆写未提供的语言)。
     * 与 [InsertStrings] 的「全量覆盖」语义不同,本动作只动 [translations] 中列出的语言。
     *
     * @param module       目标模块名,省略时用 currentModule
     * @param name         必填,字符串 key
     * @param translations 键为语言目录名(例: "values-zh-rTW"),值为新翻译
     */
    data class UpdateString(
        val module: String?,
        val name: String,
        val translations: Map<String, String>
    ) : AiAction()

    /**
     * 删除指定 key 的翻译。
     * - [languages] 为空列表时,删除 key 在所有语言的翻译(整 key 被移除)。
     * - [languages] 非空时,仅删除列表中指定语言的翻译,其他语言保持原样。
     *
     * @param module    目标模块名,省略时用 currentModule
     * @param name      必填,字符串 key
     * @param languages 要删除的语言目录名列表(例: ["values-fr", "values-zh-rCN"]);空表示全语言
     */
    data class DeleteString(
        val module: String?,
        val name: String,
        val languages: List<String>
    ) : AiAction()

    /** 文本匹配模式(枚举值与 StringsService / SheetsManager 的 TextMatchType 对齐)。 */
    enum class TextMatchType { EXACT, CONTAINS, REGEX }

    /**
     * strings.xml 反查:通过翻译文本查找 key。
     *
     * @param text           必填,要查找的翻译文本
     * @param module         限定模块;为 null 时搜索项目中所有模块
     * @param language       限定语言目录(例: values-zh-rTW);为 null 时搜索所有语言
     * @param matchType      匹配模式(默认 CONTAINS)
     * @param caseSensitive  是否区分大小写(默认 false)
     * @param limit          最大返回条数(默认 30)
     */
    data class FindKeysByText(
        val text: String,
        val module: String?,
        val language: String?,
        val matchType: TextMatchType,
        val caseSensitive: Boolean,
        val limit: Int
    ) : AiAction()

    /**
     * Google Sheets 反查:通过文本查找行。
     *
     * @param text            必填,要查找的文本
     * @param spreadsheetId   可选,默认用上下文 googleSheets 配置
     * @param sheetName       可选,默认用 defaultSheetName
     * @param column          限定列名(例: values-zh-rTW);为 null 时搜索所有列
     * @param matchType       匹配模式(默认 CONTAINS)
     * @param caseSensitive   是否区分大小写(默认 false)
     * @param limit           最大返回条数(默认 30)
     */
    data class FindRowsByText(
        val text: String,
        val spreadsheetId: String?,
        val sheetName: String?,
        val column: String?,
        val matchType: TextMatchType,
        val caseSensitive: Boolean,
        val limit: Int
    ) : AiAction()

    // region ============== 文件操作域(2026 新增) ==============
    //
    // 7 个新工具对应 IDE 内文件 / 内容 / 引用 操作,与 strings / sheets 域并列。
    // 拆分成独立工具类是为了:
    //  - 单次 round-trip 只解决一类问题,token 友好
    //  - 每个工具的安全约束独立(读操作自由,写操作只限定项目内)
    //  - AI 可按需组合(先 search_in_files 找目标,再 read_file 看上下文,再 edit_file 改)
    //
    // 共同约束(由 FileOpsService 兜底):
    //  - 所有路径必须落在当前 IDE 打开的项目根目录内,绝对路径越界会被拒绝
    //  - 写操作原子(临时文件 + rename),中途失败不会污染原文件
    //  - 大文件有字节/行数上限,避免 OOM 与 token 爆炸
    //  - search/find_references 只搜 java/kt/xml(必要时扩到 gradle/json/...)
    //

    /**
     * 获取当前 IDE 编辑器中打开的文件信息:路径、文件名带后缀、当前选中文字、选区起止行。
     * 主要用于 AI 「我在看什么文件?选中了什么?」自检。
     */
    data class GetEditorFile(
        val dummy: String? = null
    ) : AiAction()

    /**
     * 把当前 IDE 编辑器选中的硬编码文本替换为对指定 key 的引用。
     *
     * 典型用法:用户从布局/代码中选中一段硬编码文字,要求 AI 提取为 strings.xml。
     * AI 走完翻译查重 + insert_strings 流程后,driver 会回调 [ChatStateHolder.onInsertStringsInserted]
     * 触发本动作;或在「使用现有 key」场景下由 AI 显式调用本工具来触发同样的替换。
     *
     * 行为:XML 布局文件替换为 `@string/<key>`,其它文件替换为 `R.string/<key>`;
     * 在 EDT 上 WriteCommandAction 中执行;**执行后聊天视图保持打开**,AI 继续调用
     * read_string / ask_user / update_string 推进翻译查重的后续流程。
     * 无编辑器上下文(主面板聊天)或选区失效时,driver 会返回失败 tool_result。
     */
    data class ReplaceSelection(
        val key: String
    ) : AiAction()

    /**
     * 读取项目内任意文件的内容。
     *
     * @param path       相对项目根路径(如 "app/src/main/AndroidManifest.xml")或项目内的绝对路径
     * @param startLine  起始行 0-based(包含),默认 0
     * @param endLine    结束行 0-based(包含),-1 表示到文件末尾,默认 -1
     * @param maxLines   单次返回最大行数(防止大文件爆 token),默认 600
     */
    data class ReadFile(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val maxLines: Int
    ) : AiAction()

    /**
     * 精准编辑文件(同时支持 unique 模式 + regex 模式)。
     *
     * @param path        文件路径(相对项目根或项目内绝对路径)
     * @param oldText     唯一匹配文本(useRegex=false)或正则 pattern
     * @param newText     替换为的新文本
     * @param useRegex    true 时把 oldText 视为正则
     * @param replaceAll  true 时替换所有匹配;false 时要求唯一匹配(0/>1 处会失败)
     */
    data class EditFile(
        val path: String,
        val oldText: String,
        val newText: String,
        val useRegex: Boolean,
        val replaceAll: Boolean
    ) : AiAction()

    /**
     * 创建新文件(支持嵌套目录,自动 mkdirs)。
     *
     * @param path      文件路径(相对项目根或项目内绝对路径)
     * @param content   文件内容
     * @param overwrite 目标文件已存在时是否覆盖,默认 false(避免误覆盖)
     */
    data class CreateFile(
        val path: String,
        val content: String,
        val overwrite: Boolean
    ) : AiAction()

    /**
     * 在项目内文件中按文本 / 正则搜索,返回文件路径+行号+匹配内容。
     * 默认仅搜索 java/kt/xml(也包括 gradle/json/properties/txt/md)。
     *
     * @param pattern        搜索文本/正则
     * @param useRegex       true 时按正则
     * @param caseSensitive  是否区分大小写(默认 false)
     * @param filePattern    可选 glob 限定文件名(例: "*.kt"),null 时不限制
     * @param relativeDir    限定子目录(相对项目根),null 时搜索整个项目
     * @param limit          最大返回条数(默认 100,最大 200)
     */
    data class SearchInFiles(
        val pattern: String,
        val useRegex: Boolean,
        val caseSensitive: Boolean,
        val filePattern: String?,
        val relativeDir: String?,
        val limit: Int
    ) : AiAction()

    /**
     * 查找符号在项目中的引用点(Java/Kotlin/XML 文件)。
     *
     * @param symbol   资源名/视图 id/key/类名/标识符
     * @param kind     引用类型:id/string/layout/drawable/color/class/general
     * @param caseSensitive 是否区分大小写
     * @param limit    最大返回条数(默认 100,最大 200)
     */
    data class FindReferences(
        val symbol: String,
        val kind: String,
        val caseSensitive: Boolean,
        val limit: Int
    ) : AiAction()

    /**
     * 列举项目内某目录下的文件/子目录,支持 glob 与递归。
     *
     * @param relativeDir  相对项目根的子目录,"." 或空表示项目根
     * @param pattern      glob 模式(默认 "*")
     * @param recursive    是否递归子目录(限制最多 10 层防止爆栈)
     * @param includeDirs  是否在结果中包含目录
     * @param maxEntries   最大返回条数(默认 500)
     */
    data class ListFiles(
        val relativeDir: String,
        val pattern: String,
        val recursive: Boolean,
        val includeDirs: Boolean,
        val maxEntries: Int
    ) : AiAction()

    // endregion
}

data class AiReply(
    val reply: String,
    val actions: List<AiAction>,
    /**
     * 与 [actions] 严格按下标一一对应的原始 tool_calls(只有解析成功的 tool_call
     * 才会同时出现在 actions 和这里)。UI 层在构造 tool result 消息时,
     * 直接按 [actions] 的下标取这里的 id 即可,不会错位。
     */
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 模型返回的 tool_calls 中,参数无法解析或工具名未知的子集。
     * 这些 tool_use 仍占据 assistant 消息的 tool_use 块,必须配对以 tool_result
     * 才能继续对话(否则 Anthropic 会 HTTP 400:
     * `tool_use.id 'xxx' was found without a corresponding tool_result block
     * immediately after`)。driver 层需要为这些 id 添加「解析失败」的 tool_result。
     */
    val failedToolCalls: List<ToolCall> = emptyList(),
    /**
     * 推理/思考文本(模型在产出 [reply] 之前的中间发言)。
     *
     * - 仅用于 UI 展示(driver 会写入 `ChatMessage.thinking`,渲染为可折叠的 Thought 区);
     * - **不**参与协议序列化(`toOpenAiMessage` / `toAnthropicMessage` 都不读它),
     *   下一轮发回 AI 时,assistant 消息只携带 [reply] 作为正文。
     * - 非推理模型(无 `reasoning_content` / `thinking` 块)始终为空串,UI 上
     *   Thinking 折叠区自然不出现。
     */
    val reasoning: String = ""
)

/**
 * 批量翻译审查的单条问题/修正项。
 */
data class ReviewIssue(
    val row: Int,
    val col: Int,
    val current: String,
    val suggested: String,
    val reason: String
)

/**
 * 批量翻译修正项：整行新值。
 */
data class ReviewFix(
    val row: Int,
    val values: List<String>
)

/**
 * 批量翻译审查结果。
 */
data class ReviewResult(
    val issues: List<ReviewIssue>,
    val fixes: List<ReviewFix>,
    val summary: String
) {
    companion object {
        fun empty(summary: String) = ReviewResult(emptyList(), emptyList(), summary)
    }
}
