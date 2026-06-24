package cn.jarryleo.insert_strings.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * function calling 协议下的一次工具调用(assistant 消息中)。
 * @param id        模型返回的 tool_call_id / tool_use_id,需要在 tool result 中回传以关联。
 * @param name      工具名。
 * @param arguments 工具参数的 JSON 字符串(由模型原样返回,driver 自行解析)。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * 聊天消息(驱动层 + UI 共享)。
 *
 * 多模字段(用于 function calling):
 * - [toolCalls]  助手消息携带的工具调用列表。
 * - [toolCallId] 工具结果消息关联的 tool_call_id(OpenAI 协议);Anthropic 协议下用同样的字段映射到 tool_use_id。
 * - [options]    UI 层 AskUser 按钮选项,与 AI 协议无关。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val options: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /**
     * 模型的「思考/推理」文本(流式累积)。
     *
     * 与 [content] 的区别:
     * - [thinking] 是 SSE 增量推送时实时累积的文本(模型在调用工具/给出最终答案之前的中间发言),
     *   UI 上以「Thinking」区块呈现,流式生成中持续滚动,生成完毕后折叠成可展开的细节区。
     * - [content] 是给用户看的最终答复(对应 task_complete 的 summary,或纯对话回合的全部文本)。
     *
     * 这个字段仅由 driver / UI 维护,不会随消息历史发到 AI 协议(toOpenAiMessage / toAnthropicMessage 都不读它)。
     */
    val thinking: String = "",
    /**
     * 标记本条消息是否正处于 SSE 流式生成中。
     * 期间 UI 应在 Thinking 区块左上角显示动态 loading,文本区持续滚动;
     * 流结束后 driver 会置为 false,UI 转为「可折叠的思考详情」形态。
     * 同上,本字段仅 UI 维护,不参与协议序列化。
     */
    val streaming: Boolean = false
)

object AITranslator {
    private const val SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
    private const val BATCH_TRANSLATE_SYSTEM_PROMPT =
        "你是一个专业的翻译，为开发安卓APP提供国际化翻译服务。我会给你多条文本和目标语言代码，请翻译成目标语言，并严格以 JSON 对象返回，key 为我给出的标识（原样保留），value 为对应翻译结果纯文本。不要 markdown 代码块，不要任何解释。"
    private const val CHAT_SYSTEM_PROMPT =
        """你是一个 Android 应用国际化字符串管理助手。通过 function calling 与系统协作:调用工具执行操作,调用 task_complete 结束任务。

## 关于默认语言 values 的强制约定(重要)
- 上下文 `availableLanguages` 可能由用户当前选中行的语言决定,如果用户没选英语行,系统会自动补上 `values`。
- 即使 availableLanguages 没有 `values`,你也必须始终在 `translations` 里包含 `values` 键(默认英语原义)。
  若 availableLanguages 和要插入的目标模块语言数量不一致,优先按模块内的语言种类翻译。
- 插入/全量覆盖 strings.xml 时,translations 必含 `values`,其他目标语言也必含。
- 当用户表述：插入翻译 时，默认需要你自动生成一个key，key长度不要超过40个字符，然后向currentModule模块插入这个模块所有语种的翻译，若currentModule不存在则插入行数最多的模块内，需要保证模块内每个语种都有对应的翻译。注意：只操作strings.xml文件不操作google sheet，插入前需检查key是否存在，若key已存在则提示用户是否覆盖。


## 强制终止规则(最重要)
- 唯一的「合法终止」信号是调用 task_complete 工具。
- 没有调用 task_complete = 你仍在执行 = 系统会持续驱动你继续调用工具。
- 在用户的目标完整达成前,不要调用 task_complete。即使用户消息只是「读取表格」,也要在拿到结果、给出总结后才算完成。
- 多步骤任务(如「检查 X 翻译,不准则修正」)必须按顺序执行每一步,直到真正完成或必须 ask_user 等待用户输入。

## 工具一览
### strings.xml 操作
- query_keys: 列出/搜索模块内的字符串 key(pattern 正则,可选 includeTranslations)。
- read_string: 读取指定 key 在所有语言的当前翻译。
- find_keys_by_text: 反查 — 通过翻译文本查找 key(exact/contains/regex,可选 module/language 限定)。
- insert_strings: 插入/全量覆盖翻译(translations 必含 values 默认英语并覆盖其他语言,适合新增 key)。
- update_string: 精准修改指定 key 的部分语言翻译,只动提供的语言,其他保持原样(适合「修一个语言」「修个别语言」场景)。
- delete_string: 精准删除指定 key 的部分语言翻译(languages 非空)或全部语言翻译(languages 空,整 key 被移除)。删除是破坏性操作,操作前先 read_string 确认目标 key 与翻译,必要时 ask_user 与用户确认范围。
- 主动发现流程:用户给的 key 不明确时,先用 query_keys 搜索,搜索模块优先为 currentModule,currentModule不存在时省略module参数,切勿使用项目名称作为模块参数;修改前先 read_string 确认原文;用 update_string 精准修改。看到一段翻译想反查 key,用 find_keys_by_text。

### Google 表格操作
- sheets_operation: 详见工具参数(枚举)。列操作需用户确认;修改/删除行前先 search 定位行号;全表检查/修正用 check_translations/fix_translations;填充/清除背景色用 fill_color / clear_color,需提供 range(A1 表示法)与 color(hex 或命名色);设置/批量改文字色用 set_text_color,或在写值时随 rows/columnValues 并列传 rowTextColors/columnTextColors 逐格上色。
- **批量修改 (batch_modify)**:一次工具调用执行多种操作(改值/填色/改文字色/删行/清空行/插入行等),后端自动分组成最少的 Google API 请求。当用户要求**对大量单元格**做混合修改(例:"把表头改成红色,把 B5:B100 标黄,把缺失翻译的整行改红底,顺便删掉第 50/51 行,再追加 3 行新翻译"),**必须用 batch_modify 一次完成**,绝不要逐格/逐行循环调用 fill_color / update_row / append_row —— 那会瞬间用光工具调用次数预算。详细字段见 load_tool_doc("sheets_batch_modify")。
- find_rows_by_text: 反查 — 在表格中按文本搜索行(exact/contains/regex,可选 column 限定)。

### 通用
- ask_user: 向用户提问并等待用户回复。options 非空时显示按钮供用户点击,请优先提供options参数,方便用户快速回复;options 为空时用户会在聊天输入框中输入回复,系统会作为 tool_result 回传。无论是否带 options,调用本工具都会暂停 tool loop 直到用户响应 — 因此不要反复调用本工具,收到回复后请用 task_complete 或执行操作结束本轮。
- load_tool_doc: 按需加载工具详细文档。
- task_complete: 结束对话,status 取 success / partial / failed。

## 行为规则
1. 操作必须通过工具调用完成,不要只在文字里描述。
2. 用户描述的选中或者选择的内容是currentKeys内的内容,不要重复询问。
3. 每次回复可以同时包含文字(给用户看)和多个工具调用。
4. 收到工具结果后,如果目标尚未达成,必须继续调用工具推进。
5. 区分 insert_strings / update_string / delete_string:新增/全量覆盖用 insert_strings;部分语言修改用 update_string;部分语言删除或整 key 删除用 delete_string。
6. 修改或删除前若不确定当前翻译,先 read_string 确认。delete_string 是破坏性操作,执行前最好 read_string 并在不确定时用 ask_user 确认范围。
7. module 必须是 Android 模块名,取上下文 modules[].moduleName(**不是** androidProject.name);若上下文有 currentModule 则默认用它。
8. 【重要】同一 AI 回合内的所有 insert_strings / update_string / delete_string 写入动作必须在同一模块:
   - 全部省略 module 参数(系统用 currentModule)
   - 或全部显式指定同一个 module,切勿使用项目名称作为模块参数
   - 不可一次 insert A 到 module1、insert B 到 module2 — 系统会整批拒绝并要求修正
   - 确实需要跨模块写入时,拆成多个 AI 回合
9. XML 特殊字符需转义:&amp; &lt; &gt; &quot; &apos;。
10. append_row 重复 key 由系统自动检测并询问用户,你无需自行检查。
11. sheets_operation 的 spreadsheetId/sheetName 可省略,默认用上下文 googleSheets 配置。
12. 若 googleSheets.configured 为 false,提示用户先配置,不要调用 sheets_operation。
13. 安全约束:禁止擅自增删列;写入前列对齐表头;全表检查/修正用 check_translations/fix_translations 而非 read 整表。"""

    /**
     * 按需加载的工具详细文档（key = tool 名，value = 完整说明）。
     * AI 通过 load_tool_doc action 请求加载对应文档，系统注入为 tool 消息。
     * 这样主聊天 system prompt 只含工具清单，按需加载详细用法，大幅减少每轮 token。
     */
    private val TOOL_DOCS: Map<String, String> = mapOf(
        "insert_strings" to """
            ## insert_strings 详细用法
            向 Android strings.xml 插入或修改翻译字符串。
            字段：
            - module（可选）：目标 Android 模块名，取上下文 modules[].moduleName(**不是** androidProject.name,也**不是** originalModuleName)。省略时用 currentModule.moduleName。
            - name（必填）：字符串 key，snake_case。
            - translations（必填）：键为语言目录名（如 values、values-zh-rCN、values-fr），值为对应翻译文本。
            规则：
            - translations 必须包含 `values`(默认英语),即使 availableLanguages 里没有。漏写会导致 values/strings.xml 被写空。
            - translations 必须覆盖 availableLanguages 中的所有其他语言,不能遗漏。
            - 若上下文有 currentKeys，优先用第一个 key 作为 name；多 key 时可为每个 key 分别返回 insert_strings。
            - 可以同时返回多个 insert_strings 动作插入多个字符串。
            - 翻译内容中 XML 特殊字符需转义：&amp; &lt; &gt; &quot; &apos;。
            示例：
            {"type":"insert_strings","module":"app","name":"hello_world","translations":{"values":"Hello","values-zh-rCN":"你好","values-fr":"Bonjour"}}
        """.trimIndent(),

        "sheets_basic" to """
            ## sheets_operation 基础操作（list_sheets / read / search / write）
            公共字段：spreadsheetId（可选，默认用设置中的表格ID）、sheetName（可选，默认用设置中的 defaultSheetName）。

            ### list_sheets
            列出表格文件中所有工作表名称及尺寸。不需要 range/key/rows/rowNumber/sheetName。
            场景：用户问「有哪些 sheet」「看一下表格结构」，或不确定目标工作表是否存在时。
            示例：{"type":"sheets_operation","operation":"list_sheets"}

            ### read
            读取数据。可指定 sheetName/range 精确读取某区域；可指定 key 在第一列查找匹配行。全省略则读默认工作表全部内容。
            场景：用户问「读一下表格」「表格里有什么」「看 hello 的翻译」。
            示例：{"type":"sheets_operation","operation":"read","sheetName":"1.0.3.0"}

            ### search
            在工作表第一列查找 key 匹配的行，返回 1-based 行号与整行内容。key 必填。
            场景：精确定位某 key 行号，以便后续 update_row/delete_row/insert_row。
            示例：{"type":"sheets_operation","operation":"search","key":"hello"}

            ### write
            向指定 range 整体写入数据（覆盖该范围已有内容）。rows 第一行通常为表头。
            注意：write 会覆盖范围内所有单元格，谨慎使用，不要覆盖已有翻译。
            示例：{"type":"sheets_operation","operation":"write","range":"Sheet1!A1:Z100","rows":[["key","values"],["hello","Hello"]]}
        """.trimIndent(),

        "sheets_row_ops" to """
            ## sheets_operation 行操作（append_row / insert_row / update_row / delete_row / clear_row）
            公共字段：spreadsheetId（可选）、sheetName（可选）。rowNumber 为 1-based。

            ### append_row（最常用、最安全的写入方式）
            在工作表末尾追加一行。rows 为单行数据的二维数组（外层只有一个元素）。
            场景：插入一条新翻译到表格末尾。
            示例：{"type":"sheets_operation","operation":"append_row","rows":[["hello","Hello","你好"]]}
            注：重复 key 由系统自动检测并询问用户覆盖/追加/取消，你无需自行检查。

            ### insert_row
            在 rowNumber 位置插入新行，原该行及之后下移。rows 为单行数据二维数组。
            场景：「插到第 5 行」「在表头下面插入一行」。
            示例：{"type":"sheets_operation","operation":"insert_row","rowNumber":5,"rows":[["hello","Hello","你好"]]}

            ### update_row
            精确更新 rowNumber 指定行的内容，不影响其它行。rows 为单行数据二维数组。
            场景：「修改第 5 行的翻译」「把 hello 这一行的中文改成你好」。建议先 search 定位行号。
            示例：{"type":"sheets_operation","operation":"update_row","rowNumber":7,"rows":[["hello","Hello","你好"]]}

            ### delete_row
            删除 rowNumber 指定的行，后续行上移。不需要 rows。
            示例：{"type":"sheets_operation","operation":"delete_row","rowNumber":5}

            ### clear_row
            清空 rowNumber 指定行的内容但保留空行。不需要 rows。
            示例：{"type":"sheets_operation","operation":"clear_row","rowNumber":5}

            安全约束：
            - 修改/删除/清空某行前必须先 search 定位行号，避免误操作其它行。
            - 禁止覆盖其它翻译行，插入新翻译优先用 append_row。
        """.trimIndent(),

        "sheets_column_ops" to """
            ## sheets_operation 列操作（insert_column / append_column / update_column / delete_column / clear_column）
            以下操作会改变表格列结构，系统执行前会弹确认对话框。用户拒绝则返回失败，请在 reply 中告知取消。
            公共字段：spreadsheetId（可选）、sheetName（可选）。columnIndex 为 1-based。
            安全约束：除非用户明确要求增删列，否则不要返回这些动作。操作前应先 read 表头确认列数与顺序。

            ### insert_column
            在 columnIndex 位置插入新列，原该列及之后右移。
            字段：columnIndex（必填）、columnHeader（可选）、columnValues（一维数组，首元素为表头，其余为各行值，必填）。
            示例：{"type":"sheets_operation","operation":"insert_column","columnIndex":3,"columnValues":["values-fr","Bonjour","Au revoir"]}

            ### append_column
            在末尾追加新列。
            字段：columnHeader（建议填写）、columnValues（一维数组，首元素为表头，必填）。columnValues[0] 应与 columnHeader 一致。
            示例：{"type":"sheets_operation","operation":"append_column","columnHeader":"values-ja","columnValues":["values-ja","こんにちは","さようなら"]}

            ### update_column
            精确更新 columnIndex 指定列内容，不影响其它列。
            字段：columnIndex（必填）、columnValues（一维数组，首元素为表头，必填）。
            示例：{"type":"sheets_operation","operation":"update_column","columnIndex":3,"columnValues":["values-fr","Bonjour","Au revoir"]}

            ### delete_column
            删除 columnIndex 指定列，后续列左移。不需要 columnValues。
            示例：{"type":"sheets_operation","operation":"delete_column","columnIndex":3}

            ### clear_column
            清空 columnIndex 指定列内容但保留空列。不需要 columnValues。
            示例：{"type":"sheets_operation","operation":"clear_column","columnIndex":3}
        """.trimIndent(),

        "sheets_freeze" to """
            ## sheets_operation 冻结行列（freeze_rows / freeze_columns）
            字段：spreadsheetId（可选）、sheetName（可选）。

            ### freeze_rows
            冻结表格顶部指定行数，滚动时保持可见。freezeRowCount >= 0，填 0 取消冻结。
            示例：{"type":"sheets_operation","operation":"freeze_rows","freezeRowCount":2}

            ### freeze_columns
            冻结表格左侧指定列数，滚动时保持可见。freezeColumnCount >= 0，填 0 取消冻结。
            示例：{"type":"sheets_operation","operation":"freeze_columns","freezeColumnCount":1}
        """.trimIndent(),

        "sheets_review" to """
            ## sheets_operation 批量翻译审查/修正（check_translations / fix_translations）
            这两个操作由系统在本地分批调用 AI 完成全表检查或修正，主聊天只收到最终总结报告，不会把整表塞进上下文，token 不会溢出。你只需返回这一个动作，系统自动读取整表、分批处理、汇总报告并回传。
            字段：sheetName（可选）、range（可选，不填则处理整个工作表）。

            ### check_translations
            检查所有行翻译质量，只报告有问题的条目并生成总结报告。
            场景：用户要求「检查全部翻译」「审查表格」。
            系统返回的工具结果即为审查总结报告（含问题清单与统计），据此在 reply 中向用户汇报。
            示例：{"type":"sheets_operation","operation":"check_translations"}

            ### fix_translations
            检查并直接修正所有行翻译，自动写入修正后的行，并生成总结报告。
            场景：用户要求「修正全部翻译」「补全并修正所有翻译」。
            系统自动执行修正写入并返回总结报告（含修正行数），据此汇报。不要在 actions 中再重复返回这些行的 update_row。
            示例：{"type":"sheets_operation","operation":"fix_translations"}

            重要：当用户要求检查/修正全部翻译时，优先用这两个操作，不要用 read 整表后逐行分析。
        """.trimIndent(),

        "sheets_color" to """
            ## sheets_operation 背景色操作（fill_color / clear_color）
            样式变更，非破坏性，执行前不弹用户确认。仅影响背景色，不动其他格式（字体/对齐/数字格式等）。
            公共字段：spreadsheetId（可选，默认用设置中的表格ID）、sheetName（可选，默认用设置中的 defaultSheetName）。

            ### fill_color
            在 A1 范围上填充背景色。range 必填，color 必填。
            字段：
            - range（必填）：A1 表示法，支持 "Sheet1!A1:D10"、"A1:Z1"、"B2"。sheet 前缀可省略，省略时使用上下文 defaultSheetName。
            - color（必填）：hex（例 "#FF0000"、"#f0a"，大小写不敏感）或命名色（red/green/blue/yellow/orange/purple/pink/gray/grey/white/black/light_gray/dark_gray/brown/cyan/magenta）。
            场景：把表头行涂成浅灰、把缺翻译的单元格涂成红色、按 key 行号高亮一行。
            示例：{"type":"sheets_operation","operation":"fill_color","range":"Sheet1!A1:Z1","color":"light_gray"}

            ### clear_color
            清除 A1 范围的背景色，恢复透明。range 必填，不需要 color。
            示例：{"type":"sheets_operation","operation":"clear_color","range":"B2:D10"}

            ### set_text_color
            设置 A1 范围上已有单元格的文字色，不改变内容。range 与 textColor 都必填。
            字段：
            - range（必填）：A1 表示法，传 "B2" 改单格，传 "A1:Z1" 改整行，传 "B1:B100" 改整列，传 "A1:D10" 改矩形。
            - textColor（必填）：颜色字符串（同 fill_color 的 color 格式）。
            场景：把某行标题涂成品牌色、把缺翻译的单元格文字涂红警示、整列文字加色便于阅读。
            示例：{"type":"sheets_operation","operation":"set_text_color","range":"A1:Z1","textColor":"#0F766E"}

            ### clear_text_color
            清除 A1 范围上已有单元格的文字色，恢复默认（与 clear_color 清背景对应）。range 必填，不需要 textColor。
            与 set_text_color 行为对称：只清前景色，粗体/斜体/下划线等其它文字格式保留。
            场景：发现某格文字色不合适想恢复默认、批量清除之前涂的颜色。
            示例：{"type":"sheets_operation","operation":"clear_text_color","range":"A1:Z1"}

            ## 写值时并行上色（per-cell）
            任意写值类操作（write / append_row / insert_row / update_row / insert_column / append_column / update_column）可同时传颜色参数，颜色只对刚写入的单元格生效，不会影响同范围其他单元格。
            颜色格式：hex（#RGB / #RRGGBB，大小写不敏感）或命名色（red/green/blue/yellow/orange/purple/pink/gray/grey/white/black/light_gray/dark_gray/brown/cyan/magenta）。
            - rowTextColors：与 rows 同形（二维数组，null 元素 = 该格不上色）。例：write/update_row/insert_row/append_row 时与 rows 对齐。
            - columnTextColors：与 columnValues 同形（一维数组，null 元素 = 该格不上色）。例：update_column/insert_column/append_column 时与 columnValues 对齐。
            示例 1（行写入+按格上色）：{"operation":"update_row","rowNumber":5,"rows":[["a","b","c"]],"rowTextColors":[["#FF0000",null,"#00FF00"]]}
            示例 2（列写入+按行上色）：{"operation":"update_column","columnIndex":3,"columnValues":["x","y","z"],"columnTextColors":[null,"#00FF00","#0000FF"]}
            示例 3（write 范围+逐格上色）：{"operation":"write","range":"A1:C2","rows":[["a","b","c"],["d","e","f"]],"rowTextColors":[["#FF0000",null,null],[null,"#00FF00",null]]}
        """.trimIndent(),

        "sheets_batch_modify" to """
            ## sheets_operation 批量修改（batch_modify）
            一次工具调用执行多种表格修改，后端自动分组成最少的 Google API 请求。
            适用于：批量改值、批量填色、批量改文字色、批量删行/清空行/插入行、混合操作等。
            优势：避免逐格调用 fill_color / set_text_color / update_row 等操作触发 AI 工具调用次数上限。

            公共字段：spreadsheetId（可选，默认上下文 googleSheets 配置）、sheetName（可选，默认 defaultSheetName）。
            主体字段：batchEdits — 动作项数组，每项一个子操作 type。

            ### 子操作类型

            #### set_values — 覆盖式写入任意矩形范围
            字段：range（必填，A1 表示法，如 "Sheet1!A1:D10"）、rows（必填，二维数组）。
            注意：会覆盖范围内所有单元格，谨慎使用，不要覆盖已有翻译。
            示例：{"type":"set_values","range":"Sheet1!A1:D1","rows":[["key","values","values-zh","values-fr"]]}

            #### fill_color — 填充背景色
            字段：range（必填，A1 表示法，支持单元格/行/列/矩形）、color（必填，hex 或命名色）。
            示例：{"type":"fill_color","range":"A1:Z1","color":"light_gray"}

            #### clear_color — 清除背景色
            字段：range（必填）。
            示例：{"type":"clear_color","range":"B2:D10"}

            #### set_text_color — 设置文字色
            字段：range（必填）、color（必填，颜色格式同上）。
            示例：{"type":"set_text_color","range":"A5:A100","color":"red"}

            #### clear_text_color — 清除文字色
            字段：range（必填）。
            示例：{"type":"clear_text_color","range":"A1:Z1"}

            #### update_rows — 连续更新多行
            字段：rowNumber（必填，1-based 起始行号）、rows（必填，二维数组）。
            可选：rowTextColors（二维矩阵，与 rows 同形，null 元素 = 该格不上文字色）、
            rowBackgroundColors（二维矩阵，与 rows 同形，null 元素 = 该格不上背景色）。
            示例：{"type":"update_rows","rowNumber":5,"rows":[["a","b","c"],["d","e","f"]],"rowTextColors":[["#FF0000",null,null],[null,"#00FF00",null]],"rowBackgroundColors":[[null,"#FFFF00",null],[null,null,null]]}

            #### append_rows — 末尾追加多行
            字段：rows（必填，二维数组）。
            可选：rowTextColors、rowBackgroundColors（同 update_rows）。
            示例：{"type":"append_rows","rows":[["k1","v1","v2"],["k2","v3","v4"]]}

            #### insert_rows — 在指定位置插入多行
            字段：rowNumber（必填，1-based，原行及之后下移）、rows（必填，二维数组）。
            可选：rowTextColors、rowBackgroundColors。
            示例：{"type":"insert_rows","rowNumber":3,"rows":[["a","b","c"],["d","e","f"]]}

            #### delete_rows — 批量删除多行
            字段：rowNumbers（必填，1-based 行号列表，会按降序删除以避免行号位移）。
            示例：{"type":"delete_rows","rowNumbers":[10,12,15]}

            #### clear_rows — 批量清空多行（保留行号）
            字段：rowNumbers（必填，1-based 行号列表）。
            示例：{"type":"clear_rows","rowNumbers":[10,12]}

            ### 完整示例（混合操作）
            一次调用把缺翻译的行涂红、修改 A1 表头、删除冗余行、追加新行：
            {
              "operation":"batch_modify",
              "sheetName":"1.0.3.0",
              "batchEdits":[
                {"type":"fill_color","range":"B5:B100","color":"#FFE4B5"},
                {"type":"set_text_color","range":"A1:Z1","color":"#0F766E"},
                {"type":"update_rows","rowNumber":2,"rows":[["key","values","values-zh-rCN"]]},
                {"type":"append_rows","rows":[["new_key","new value","新值"]]},
                {"type":"delete_rows","rowNumbers":[50,51,52]}
              ]
            }

            ### 行为约束
            - 所有子操作按 type 分组，后端自动用最少的 Google API 请求完成（一组值写入 + 一组格式 + 一组结构变更）。
            - 不会自动检测重复 key（与 append_row 不同）；append_rows 后由系统提示用户确认。
            - 颜色格式同 fill_color / set_text_color（hex 或命名色）。
            - 优先用 batch_modify 而不是循环调用 fill_color/set_text_color/update_row，避免工具调用次数累积。
        """.trimIndent()
    )

    /**
     * 获取工具详细文档。供 UI 层在处理 load_tool_doc action 时调用。
     * @param toolName 工具名（如 sheets_row_ops）
     * @return 对应的完整文档文本，若不存在返回 null
     */
    @JvmStatic
    fun getToolDoc(toolName: String): String? = TOOL_DOCS[toolName.trim()]

    /** 列出所有可加载文档的工具名（调试/展示用）。 */
    @JvmStatic
    fun availableToolDocs(): List<String> = TOOL_DOCS.keys.toList()

    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * 批量翻译审查的专用系统提示词（独立于主聊天，最小化 token 使用）。
     * 只要求模型输出有问题的行，避免回吐整张表。
     */
    private const val REVIEW_SYSTEM_PROMPT =
        """你是 Android 国际化翻译质量审查助手。输入格式：
表头(N列): 列1|列2|...|列N
行X: 值1|值2|...|值N
「行X:」是行号标注，非数据列，不计入列数或 values。竖线分隔的为数据列，第一列是 key，其余为翻译。
检查准确性、一致性、完整性、占位符(%s/%d)与 XML 转义。只返回纯 JSON，无 markdown，无额外说明。

check 模式：{"issues":[{"row":<行号>,"col":<0-based列号,0=key列>,"current":"<当前值>","suggested":"<建议值>","reason":"<原因>"}],"summary":"<中文总结>"}
- 只列有问题的条目，无问题返回空 issues。row 用「行X:」标注的行号原样回填。

fix 模式：{"fixes":[{"row":<行号>,"values":[<整行新值,列数同表头>]}],"summary":"<中文总结>"}
- 只返回需修正的行。values 只含数据列，长度等于表头列数，不含行号。
- 不修改 key 列原样保留。row 用「行X:」标注的行号。"""

    @JvmStatic
    fun translate(code: String, text: String): String {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return "Please configure the AI URL first."
        if (apiKey.isBlank()) return "Please configure the AI API key first."
        if (model.isBlank()) return "Please configure the AI model first."

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiTranslateBody(model, code, text)
                AiProtocol.ANTHROPIC -> anthropicTranslateBody(model, code, text)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
        }.getOrElse {
            it.message ?: "AI translate failed."
        }
    }

    /**
     * 批量翻译多个 key 的源文本到同一目标语言，单次 AI 调用完成。
     * 返回 key -> 翻译结果 的映射；翻译失败或未返回的 key 回退为空串。
     */
    fun translateBatch(code: String, items: List<Pair<String, String>>): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        if (items.size == 1) {
            val (key, text) = items.first()
            return mapOf(key to translate(code, text))
        }
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return items.associate { (k, _) -> k to "" }
        }

        val userContent = buildString {
            append("目标语言代码：").append(code).append("\n")
            append("请将以下每条文本翻译为目标语言，按 JSON 对象返回，key 保持原样，value 为翻译结果，不要任何额外说明或 markdown：\n")
            items.forEach { (key, text) ->
                append(key).append("：").append(text).append("\n")
            }
        }

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiBatchTranslateBody(model, userContent)
                AiProtocol.ANTHROPIC -> anthropicBatchTranslateBody(model, userContent)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            val responseText = when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
            parseBatchTranslateResult(responseText, items)
        }.getOrElse {
            items.associate { (k, _) -> k to "" }
        }
    }

    private fun parseBatchTranslateResult(responseText: String, items: List<Pair<String, String>>): Map<String, String> {
        val root = extractJsonObject(responseText)
        return try {
            if (root == null) throw IllegalStateException("No JSON object found in batch translate result")
            items.associate { (key, _) ->
                key to (root.get(key)?.extractText() ?: "")
            }
        } catch (e: Exception) {
            items.associate { (key, _) -> key to "" }
        }
    }

    private fun openAiBatchTranslateBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", BATCH_TRANSLATE_SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicBatchTranslateBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", BATCH_TRANSLATE_SYSTEM_PROMPT)
            addProperty("max_tokens", 4096)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    @JvmStatic
    fun chat(messages: List<ChatMessage>, context: String = ""): AiReply {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return AiReply("Please configure the AI URL first.", emptyList())
        if (apiKey.isBlank()) return AiReply("Please configure the AI API key first.", emptyList())
        if (model.isBlank()) return AiReply("Please configure the AI model first.", emptyList())

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiChatBody(model, messages, context)
                AiProtocol.ANTHROPIC -> anthropicChatBody(model, messages, context)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            // function calling 响应同时包含文本与 tool_calls,需解析完整 JSON。
            // 优先用专用解析;若失败,退化到抽取纯文本以便用户至少看到回复。
            parseAiReply(response.body())
        }.getOrElse {
            AiReply(it.message ?: "AI chat failed.", emptyList())
        }
    }

    /**
     * 流式版本的 chat。
     *
     * 与 [chat] 区别:向 OpenAI / Anthropic 发起 stream=true 请求,
     * 解析 SSE 事件流,每当累积到一段新的 assistant 文本就通过 [onPartialText] 回调推给调用方。
     * 调用方负责把增量文本转 EDT 后更新 UI,实现「打字机」效果,降低体感延迟。
     *
     * 回调约定:
     * - [onPartialText] 跑在**后台线程**(发请求的池化线程),每次有新 token 累计进来就触发一次,
     *   参数是「到目前为止的完整 assistant 文本」(不是 delta)。
     *   实现里应做去抖/节流(本函数内部已经按行+去 delta 推送,粒度较细;若调用方发现仍太密可自行节流)。
     * - [onPartialText] 可能被调用 0 次(模型只返回 tool_calls,没有文字)。
     * - 流结束后返回完整的 [AiReply](含 toolCalls / failedToolCalls),
     *   与非流式 [chat] 行为一致,可直接复用下游分发逻辑。
     *
     * 错误处理:任何 HTTP 错误 / 解析异常,都通过返回的 [AiReply].reply 暴露错误文案,
     *   并继续走完整流程(不抛异常),保证 driver 层不用 try/catch 包装。
     * 取消:调用方应通过 [stopRequested] 之类机制在外部取消;本函数不支持中途打断。
     *
     * @return 与 [chat] 同型的 [AiReply];若中途出错 reply 字段含错误描述。
     */
    @JvmStatic
    fun chatStream(
        messages: List<ChatMessage>,
        context: String = "",
        onPartialText: (String) -> Unit
    ): AiReply {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return AiReply("Please configure the AI URL first.", emptyList())
        if (apiKey.isBlank()) return AiReply("Please configure the AI API key first.", emptyList())
        if (model.isBlank()) return AiReply("Please configure the AI model first.", emptyList())

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiChatBody(model, messages, context, stream = true)
                AiProtocol.ANTHROPIC -> anthropicChatBody(model, messages, context, stream = true)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                // 非 2xx 时 body 是普通 JSON,按 error 格式读
                val errBody = response.body().readBytes().toString(Charsets.UTF_8)
                response.body().close()
                throw IllegalStateException("HTTP ${response.statusCode()}: ${errBody.limitForMessage()}")
            }
            val stream = response.body()
            try {
                val text = StringBuilder()
                val parser = SseStreamParser(protocol) { deltaText ->
                    if (deltaText.isNotEmpty()) {
                        text.append(deltaText)
                        onPartialText(text.toString())
                    }
                }
                parser.parse(stream)
                // 流结束后把累积的内容合成一个标准响应 JSON,复用 parseAiReply → toolCallToAction 全链路
                val syntheticBody = when (protocol) {
                    AiProtocol.OPENAI -> buildOpenAiSyntheticBody(text.toString(), parser.toolCalls)
                    AiProtocol.ANTHROPIC -> buildAnthropicSyntheticBody(text.toString(), parser.toolCalls)
                }
                parseAiReply(syntheticBody)
            } finally {
                stream.close()
            }
        }.getOrElse {
            AiReply(it.message ?: "AI chat failed.", emptyList())
        }
    }

    /**
     * 把流式累积的 OpenAI tool_call 片段组装成与 [openAiChatBody] 同型的完整响应 JSON,
     * 供 [parseAiReply] 复用,避免为流式场景单独写一套 tool_call 解析逻辑。
     */
    private fun buildOpenAiSyntheticBody(
        text: String,
        streamedToolCalls: List<SseStreamParser.StreamedToolCall>
    ): String {
        val toolCallsArray = JsonArray().apply {
            streamedToolCalls.forEach { tc ->
                add(JsonObject().apply {
                    addProperty("id", tc.id)
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", tc.name)
                        addProperty("arguments", tc.arguments)
                    })
                })
            }
        }
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            if (text.isNotEmpty()) addProperty("content", text)
            if (toolCallsArray.size() > 0) add("tool_calls", toolCallsArray)
        }
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", "stop")
        }
        val root = JsonObject().apply {
            add("choices", JsonArray().apply { add(choice) })
        }
        return root.toString()
    }

    /**
     * 把流式累积的 Anthropic content block 组装成与 [anthropicChatBody] 同型的完整响应 JSON。
     * text blocks 拼成单一 text block,tool_use blocks 保持原样。
     */
    private fun buildAnthropicSyntheticBody(
        text: String,
        streamedToolCalls: List<SseStreamParser.StreamedToolCall>
    ): String {
        val contentArray = JsonArray().apply {
            if (text.isNotEmpty()) {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            }
            streamedToolCalls.forEach { tc ->
                add(JsonObject().apply {
                    addProperty("type", "tool_use")
                    addProperty("id", tc.id)
                    addProperty("name", tc.name)
                    add("input", runCatching {
                        if (tc.arguments.isBlank()) JsonObject()
                        else JsonParser.parseString(tc.arguments)
                    }.getOrElse { JsonObject() })
                })
            }
        }
        val root = JsonObject().apply {
            add("content", contentArray)
            addProperty("stop_reason", if (streamedToolCalls.isNotEmpty()) "tool_use" else "end_turn")
        }
        return root.toString()
    }

    /**
     * 批量翻译审查/修正。使用独立的极简提示词与消息列表，不携带主聊天历史，
     * 以最小 token 完成对一段表格数据的检查或修正。
     *
     * @param header 表头行（含 key 列）。
     * @param batchRows 该批数据行（不含表头），每行包含 key 列在内的所有列。
     * @param startRowNumber batchRows 第一行在原表格中的 1-based 行号（含表头偏移）。
     * @param mode "check" 只报告问题；"fix" 返回需要修正的整行新值。
     * @param maxCellChars 单元格内容截断长度，控制 token。
     */
    fun reviewTranslations(
        header: List<String>,
        batchRows: List<List<String>>,
        startRowNumber: Int,
        mode: String,
        maxCellChars: Int = 200
    ): ReviewResult {
        if (header.isEmpty() || batchRows.isEmpty()) {
            return ReviewResult.empty("空数据，无需检查。")
        }
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return ReviewResult.empty("AI 未配置，无法审查。")
        }

        val compactData = buildCompactReviewData(header, batchRows, startRowNumber, maxCellChars)
        val modeDesc = if (mode == "fix") "修正模式（fix）" else "检查模式（check）"
        val userContent = "模式：$modeDesc\n表头列数：${header.size}\n数据行数：${batchRows.size}\n\n$compactData"

        return runCatching {
            val body = when (protocol) {
                AiProtocol.OPENAI -> openAiReviewBody(model, userContent)
                AiProtocol.ANTHROPIC -> anthropicReviewBody(model, userContent)
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            val responseText = when (protocol) {
                AiProtocol.OPENAI -> parseOpenAiText(response.body())
                AiProtocol.ANTHROPIC -> parseAnthropicText(response.body())
            }
            parseReviewResult(responseText)
        }.getOrElse {
            ReviewResult.empty("审查请求失败：${it.message ?: "unknown"}")
        }
    }

    private fun buildCompactReviewData(
        header: List<String>,
        batchRows: List<List<String>>,
        startRowNumber: Int,
        maxCellChars: Int
    ): String {
        val sb = StringBuilder()
        sb.append("表头(").append(header.size).append("列): ")
            .append(header.joinToString("|") { truncate(it, maxCellChars) })
            .append("\n")
        batchRows.forEachIndexed { index, row ->
            val rowNumber = startRowNumber + index
            sb.append("行").append(rowNumber).append(": ")
                .append(header.indices.joinToString("|") { colIdx ->
                    truncate(row.getOrNull(colIdx) ?: "", maxCellChars)
                })
                .append("\n")
        }
        return sb.toString()
    }

    private fun truncate(text: String, maxChars: Int): String {
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    private fun openAiReviewBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", REVIEW_SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicReviewBody(model: String, userContent: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", REVIEW_SYSTEM_PROMPT)
            addProperty("max_tokens", 2048)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userContent)
                    })
                }
            )
        }
        return root.toString()
    }

    private fun parseReviewResult(responseText: String): ReviewResult {
        val root = extractJsonObject(responseText)
        return try {
            if (root == null) throw IllegalStateException("No JSON object found in review result")
            val summary = root.get("summary")?.extractText() ?: ""
            val issues = root.getAsJsonArray("issues")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                ReviewIssue(
                    row = o.get("row")?.let { runCatching { it.asInt }.getOrNull() } ?: -1,
                    col = o.get("col")?.let { runCatching { it.asInt }.getOrNull() } ?: -1,
                    current = o.get("current")?.extractText().orEmpty(),
                    suggested = o.get("suggested")?.extractText().orEmpty(),
                    reason = o.get("reason")?.extractText().orEmpty()
                )
            }?.filter { it.row > 0 } ?: emptyList()
            val fixes = root.getAsJsonArray("fixes")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val row = o.get("row")?.let { runCatching { it.asInt }.getOrNull() } ?: -1
                if (row <= 0) return@mapNotNull null
                val valuesArray = o.getAsJsonArray("values") ?: return@mapNotNull null
                ReviewFix(row, valuesArray.map { it?.extractText().orEmpty() })
            } ?: emptyList()
            ReviewResult(issues, fixes, summary)
        } catch (e: Exception) {
            ReviewResult.empty("解析审查结果失败：${responseText.take(200)}")
        }
    }

    fun fetchModels(rawUrl: String, protocol: AiProtocol, apiKey: String): Result<List<String>> {
        if (rawUrl.isBlank()) return Result.failure(IllegalArgumentException("Please configure the AI URL first."))
        if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("Please configure the AI API key first."))

        return runCatching {
            val endpoint = AiEndpoint.completeModelsEndpoint(rawUrl, protocol)
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .applyAuthHeaders(protocol, apiKey.trim())
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().limitForMessage()}")
            }
            parseModelIds(response.body()).ifEmpty {
                throw IllegalStateException("No model id found in response.")
            }
        }
    }

    private fun HttpRequest.Builder.applyAuthHeaders(protocol: AiProtocol, apiKey: String): HttpRequest.Builder {
        return when (protocol) {
            AiProtocol.OPENAI -> header("Authorization", "Bearer $apiKey")
            AiProtocol.ANTHROPIC -> header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
        }
    }

    /**
     * 从项目上下文 JSON 中解析 googleSheets 段,抽出 [ToolDefinitions.SheetContext]。
     * 用于把当前默认工作表 / 可用工作表列表注入到 tools 的 description 里,
     * 避免 AI 在多 sheet 的表格里猜错工作表。
     */
    private fun extractSheetContext(context: String): ToolDefinitions.SheetContext {
        if (context.isBlank()) return ToolDefinitions.SheetContext(null, emptyList())
        return runCatching {
            val root = JsonParser.parseString(context).asJsonObject
            val sheets = root.getAsJsonObject("googleSheets") ?: return@runCatching ToolDefinitions.SheetContext(null, emptyList())
            val defaultName = sheets.get("defaultSheetName")?.takeIf { !it.isJsonNull }?.asString
            val available = sheets.getAsJsonArray("availableSheets")?.mapNotNull { el ->
                if (el.isJsonPrimitive) el.asString else null
            } ?: emptyList()
            ToolDefinitions.SheetContext(defaultName, available)
        }.getOrDefault(ToolDefinitions.SheetContext(null, emptyList()))
    }

    /**
     * 从项目上下文 JSON 中解析 projectBase 段(IDE 当前打开项目根路径)。
     * 注入到所有文件操作工具的 description 里,让 AI 知道相对路径怎么传。
     */
    private fun extractProjectBase(context: String): String? {
        if (context.isBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(context).asJsonObject
            root.get("projectBase")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun openAiChatBody(
        model: String,
        messages: List<ChatMessage>,
        context: String,
        stream: Boolean = false
    ): String {
        val sheetCtx = extractSheetContext(context)
        val projectBase = extractProjectBase(context)
        val root = JsonObject().apply {
            addProperty("model", model)
            add("tools", ToolDefinitions.openAiTools(sheetCtx, projectBase))
            if (stream) addProperty("stream", true)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", CHAT_SYSTEM_PROMPT)
                    })
                    if (context.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "## 当前项目上下文（JSON）\n$context")
                        })
                    }
                    messages.forEach { msg -> add(msg.toOpenAiMessage()) }
                }
            )
        }
        return root.toString()
    }

    private fun anthropicChatBody(
        model: String,
        messages: List<ChatMessage>,
        context: String,
        stream: Boolean = false
    ): String {
        val sheetCtx = extractSheetContext(context)
        val projectBase = extractProjectBase(context)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 4096)
            val systemParts = buildString {
                append(CHAT_SYSTEM_PROMPT)
                if (context.isNotBlank()) {
                    append("\n## 当前项目上下文（JSON）\n").append(context)
                }
            }
            addProperty("system", systemParts)
            add("tools", ToolDefinitions.anthropicTools(sheetCtx, projectBase))
            if (stream) addProperty("stream", true)
            add(
                "messages",
                JsonArray().apply { buildAnthropicMessages(messages).forEach { add(it) } }
            )
        }
        return root.toString()
    }

    /**
     * 把 [ChatMessage] 转换为 OpenAI Chat Completions 的 messages 元素。
     * 重点处理三种特殊情况:
     * - assistant 消息携带 tool_calls(原生 function calling)
     * - tool 消息携带 tool_call_id(回传给模型以关联结果)
     * - 普通 user/assistant 消息保持原样
     */
    private fun ChatMessage.toOpenAiMessage(): JsonObject {
        // tool result message
        if (role == "tool" || (role == "user" && toolCallId != null)) {
            return JsonObject().apply {
                addProperty("role", "tool")
                addProperty("tool_call_id", toolCallId.orEmpty())
                addProperty("content", content)
            }
        }
        // assistant 消息的协议文本 = 模型的真实发言。
        // UI 层把模型的流式文本放在 [thinking],把衍生的「执行操作:xxx」/task_complete summary
        // 放在 [content]。发回 AI 时必须用 [thinking](没有才退回 [content]),
        // 否则 AI 会看到自己没写过的「执行操作:」前缀,污染下一轮上下文。
        val protocolText = if (role == "assistant") thinking.ifEmpty { content } else content
        // assistant message with tool calls (function calling)
        if (role == "assistant" && toolCalls.isNotEmpty()) {
            return JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", protocolText)
                add("tool_calls", JsonArray().apply {
                    toolCalls.forEach { tc ->
                        add(JsonObject().apply {
                            addProperty("id", tc.id)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", tc.name)
                                addProperty("arguments", tc.arguments)
                            })
                        })
                    }
                })
            }
        }
        // plain text message
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", protocolText)
        }
    }

    /**
     * 把聊天消息列表转换为 Anthropic Messages API 的 messages 数组。
     *
     * 关键约束:Anthropic 要求 assistant 消息中的每个 tool_use 块,必须在**紧接其后**
     * 的同一条 user 消息中拥有对应的 tool_result 块;否则会 HTTP 400:
     *   `tool_use.id 'xxx' was found without a corresponding tool_result block
     *    immediately after`
     *
     * 因此本函数把连续的 tool 结果(role="tool" 或带 toolCallId 的 user)合并到
     * 同一个 user 消息里,让所有配对 tool_result 与上一条 assistant 的 tool_use
     * 在结构上"立即相邻"。
     *
     * 实际发送前会先调 [normalizeMessagesForAnthropic] 把每个 block 内的 tool_result
     * 集中放到非 tool 消息之前,以兜底用户在工具执行中插入文本消息导致的历史乱序。
     */
    private fun buildAnthropicMessages(messages: List<ChatMessage>): List<JsonObject> {
        val normalized = normalizeMessagesForAnthropic(messages)
        val result = mutableListOf<JsonObject>()
        val pendingToolResults = mutableListOf<JsonObject>()

        fun flushToolResults() {
            if (pendingToolResults.isEmpty()) return
            result.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply { pendingToolResults.forEach { add(it) } })
                }
            )
            pendingToolResults.clear()
        }

        normalized.forEach { msg ->
            if (msg.role == "tool" || (msg.role == "user" && msg.toolCallId != null)) {
                pendingToolResults.add(
                    JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", msg.toolCallId.orEmpty())
                        addProperty("content", msg.content)
                    }
                )
            } else {
                flushToolResults()
                result.add(msg.toAnthropicMessage())
            }
        }
        flushToolResults()
        return result
    }

    /**
     * 把聊天消息重排为满足 Anthropic 协议约束的顺序。
     *
     * 协议要求:assistant 消息中每个 tool_use 块,必须在**紧接其后**的同一条 user 消息
     * 中拥有对应的 tool_result 块;否则 HTTP 400。
     *
     * 这里"block"定义为:从某个含 tool_uses 的 assistant 消息开始,到下一个 assistant
     * 消息(或消息列表末尾)为止。本函数对每个 block 做:
     *   1) 找出未配对的 tool_use(没有对应 tool_result),合成一个占位 tool_result
     *   2) 重排 block:assistant → 全部 tool_result → 其它消息
     * 这样既补齐了缺失的 tool_result,又让 tool_result 不会被 block 内的 user 文本
     * 消息"截胡"到后面,违反"immediately after"约束。
     *
     * 注意:本函数**只用于 Anthropic API 调用的消息列表**,不动 UI 上的 chatMessages。
     */
    private fun normalizeMessagesForAnthropic(messages: List<ChatMessage>): List<ChatMessage> {
        fun isToolResult(msg: ChatMessage): Boolean =
            msg.role == "tool" || (msg.role == "user" && msg.toolCallId != null)

        val result = mutableListOf<ChatMessage>()
        var pendingBlock: MutableList<ChatMessage>? = null

        fun flushBlock() {
            val block = pendingBlock ?: return
            pendingBlock = null
            if (block.isEmpty()) return
            // 1) 补齐未配对的 tool_use
            val assistant = block.firstOrNull { it.role == "assistant" }
            if (assistant != null && assistant.toolCalls.isNotEmpty()) {
                val pairedIds = block
                    .asSequence()
                    .filter { isToolResult(it) }
                    .mapNotNull { it.toolCallId }
                    .toSet()
                assistant.toolCalls
                    .filter { it.id !in pairedIds }
                    .forEach { tc ->
                        block.add(
                            ChatMessage(
                                role = "tool",
                                content = "[自动补全] 类型:${tc.name} 状态:已取消 " +
                                    "信息:协议要求每个 tool_use 必须有 tool_result,系统自动补齐以满足协议。",
                                toolCallId = tc.id
                            )
                        )
                    }
            }
            // 2) 重排:assistant → 全部 tool_result → 其它
            val assistantMsg = block.first()
            val tail = block.drop(1)
            val toolResults = tail.filter { isToolResult(it) }
            val others = tail.filter { !isToolResult(it) }
            result.add(assistantMsg)
            result.addAll(toolResults)
            result.addAll(others)
        }

        messages.forEach { msg ->
            if (msg.role == "assistant" && msg.toolCalls.isNotEmpty()) {
                // 进入新 block
                flushBlock()
                pendingBlock = mutableListOf(msg)
            } else if (msg.role == "assistant") {
                // 普通 assistant:把当前 block 收尾,然后把这条直接送出
                flushBlock()
                result.add(msg)
            } else {
                // tool / user / 其它:并入当前 block(若没有活跃 block 则直接送出)
                val block = pendingBlock
                if (block != null) {
                    block.add(msg)
                } else {
                    result.add(msg)
                }
            }
        }
        flushBlock()
        return result
    }

    /**
     * 把 [ChatMessage] 转换为 Anthropic Messages API 的 messages 元素。
     * - assistant 消息含 toolCalls 时用 content 数组(text + tool_use 块)
     * - tool 的结果统一用 user 角色的 tool_result 块
     *
     * 注意:本函数只做单条消息的转换,不处理 tool_result 的合并。
     * 整批消息请使用 [buildAnthropicMessages],它会把连续的 tool 消息合并到
     * 同一条 user 消息中,以满足 Anthropic 协议对 tool_use/tool_result
     * "立即相邻"的硬约束。
     */
    private fun ChatMessage.toAnthropicMessage(): JsonObject {
        // tool result → user with tool_result block(s)
        if (role == "tool" || (role == "user" && toolCallId != null)) {
            return JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_result")
                        addProperty("tool_use_id", toolCallId.orEmpty())
                        addProperty("content", content)
                    })
                })
            }
        }
        // assistant 消息的协议文本 = 模型的真实发言:优先用 [thinking](流式累积的模型原文),
        // 没有(纯文本回合)时退回 [content]。详见 [toOpenAiMessage] 的注释。
        val protocolText = if (role == "assistant") thinking.ifEmpty { content } else content
        if (role == "assistant") {
            if (toolCalls.isNotEmpty()) {
                return JsonObject().apply {
                    addProperty("role", "assistant")
                    add("content", JsonArray().apply {
                        if (protocolText.isNotEmpty()) {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", protocolText)
                            })
                        }
                        toolCalls.forEach { tc ->
                            add(JsonObject().apply {
                                addProperty("type", "tool_use")
                                addProperty("id", tc.id)
                                addProperty("name", tc.name)
                                add("input", runCatching {
                                    JsonParser.parseString(tc.arguments)
                                }.getOrElse { JsonObject() })
                            })
                        }
                    })
                }
            }
            return JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", protocolText)
            }
        }
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", protocolText)
        }
    }

    private fun parseAiReply(responseText: String): AiReply {
        val root = runCatching { JsonParser.parseString(responseText).asJsonObject }.getOrNull()
            ?: return AiReply(responseText, emptyList(), emptyList(), emptyList())
        val text = extractAssistantText(root)
        val rawToolCalls = extractToolCalls(root)
        val actions = mutableListOf<AiAction>()
        val parsedToolCalls = mutableListOf<ToolCall>()
        val failedToolCalls = mutableListOf<ToolCall>()
        rawToolCalls.forEach { call ->
            val action = toolCallToAction(call)
            if (action != null) {
                actions.add(action)
                parsedToolCalls.add(call)
            } else {
                // 解析失败:tool_use 仍占据 assistant 消息的 tool_use 块,必须配 tool_result
                failedToolCalls.add(call)
            }
        }
        return AiReply(text, actions, parsedToolCalls, failedToolCalls)
    }

    /**
     * 从模型响应中提取助手文本内容。
     * 兼容三种格式:OpenAI chat.completions / Anthropic messages / 退化到 JSON 文本(老协议)。
     */
    private fun extractAssistantText(root: JsonObject): String {
        // OpenAI 格式
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.get("content")?.let { return it.extractText() }
        // Anthropic 格式
        root.getAsJsonArray("content")?.let { contentArray ->
            if (contentArray.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }) {
                return contentArray.joinToString("") { element ->
                    if (element.isJsonObject && element.asJsonObject.get("type")?.asString == "text") {
                        element.asJsonObject.get("text")?.asString.orEmpty()
                    } else ""
                }
            }
        }
        return ""
    }

    /**
     * 从模型响应中提取 tool_calls(统一成内部 [ToolCall] 格式)。
     */
    private fun extractToolCalls(root: JsonObject): List<ToolCall> {
        val result = mutableListOf<ToolCall>()
        // OpenAI: choices[0].message.tool_calls
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.getAsJsonArray("tool_calls")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString ?: return@forEach
                val functionObj = obj.getAsJsonObject("function") ?: return@forEach
                val name = functionObj.get("name")?.asString ?: return@forEach
                val arguments = functionObj.get("arguments")?.asString ?: "{}"
                result.add(ToolCall(id, name, arguments))
            }
        // Anthropic: content[].type=tool_use
        root.getAsJsonArray("content")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj = element.asJsonObject
            if (obj.get("type")?.asString != "tool_use") return@forEach
            val id = obj.get("id")?.asString ?: return@forEach
            val name = obj.get("name")?.asString ?: return@forEach
            val input = obj.get("input")
            val arguments = input?.toString() ?: "{}"
            result.add(ToolCall(id, name, arguments))
        }
        return result
    }

    /**
     * 解析 matchType 字符串(不区分大小写),失败回退 CONTAINS。
     */
    private fun parseMatchType(raw: String?): AiAction.TextMatchType {
        if (raw.isNullOrBlank()) return AiAction.TextMatchType.CONTAINS
        return runCatching {
            AiAction.TextMatchType.valueOf(raw.trim().uppercase())
        }.getOrDefault(AiAction.TextMatchType.CONTAINS)
    }

    /**
     * 把 rowTextColors 的 JSON 数组解析为 List<List<String?>>。
     * 外层每项是一行,内层每项是单格颜色(null 表示不上色)。允许 null 内层项。
     */
    private fun parseRowColorMatrix(arr: com.google.gson.JsonArray): List<List<String?>>? {
        if (arr.isEmpty()) return null
        return arr.map { rowElement ->
            if (rowElement.isJsonNull) emptyList()
            else if (!rowElement.isJsonArray) emptyList()
            else rowElement.asJsonArray.map { cellElement ->
                when {
                    cellElement.isJsonNull -> null
                    cellElement.isJsonPrimitive -> cellElement.asString.trim().takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
        }
    }

    /**
     * 把 columnTextColors 的 JSON 数组解析为 List<String?>。
     * 长度需与 columnValues 保持一致,由调用方在 SheetsManager 写入时按位置对应。
     */
    private fun parseColumnColorList(arr: com.google.gson.JsonArray): List<String?>? {
        if (arr.isEmpty()) return null
        return arr.map { el ->
            when {
                el.isJsonNull -> null
                el.isJsonPrimitive -> el.asString.trim().takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    /**
     * 把 batchEdits 的 JSON 数组解析为 List<[AiAction.BatchEdit]>。
     * 跳过无法识别 type 的项(记到上层由 AI 看到失败结果),保留其它字段尽量多。
     * rowTextColors / rowBackgroundColors 复用 [parseRowColorMatrix]。
     */
    private fun parseBatchEdits(arr: com.google.gson.JsonArray): List<AiAction.BatchEdit>? {
        if (arr.isEmpty()) return null
        val edits = mutableListOf<AiAction.BatchEdit>()
        for (element in arr) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val typeText = obj.get("type")?.asString ?: continue
            val type = runCatching {
                AiAction.BatchEditType.valueOf(typeText.uppercase())
            }.getOrNull() ?: continue
            val range = obj.get("range")?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val rowsArray = obj.getAsJsonArray("rows")
            val rows = rowsArray?.mapNotNull { rowElement ->
                if (!rowElement.isJsonArray) return@mapNotNull null
                rowElement.asJsonArray.map { it?.extractText().orEmpty() }
            }
            val rowNumber = obj.get("rowNumber")?.let {
                runCatching { it.asInt }.getOrNull()
            }?.takeIf { it != null && it > 0 }
            val rowNumbersArray = obj.getAsJsonArray("rowNumbers")
            val rowNumbers = rowNumbersArray?.mapNotNull { el ->
                if (el == null || el.isJsonNull) return@mapNotNull null
                runCatching { el.asInt }.getOrNull()?.takeIf { it > 0 }
            }
            val color = obj.get("color")?.asString?.trim()?.takeIf { it.isNotEmpty() }
            val rowTextColors = obj.getAsJsonArray("rowTextColors")?.let { parseRowColorMatrix(it) }
            val rowBackgroundColors = obj.getAsJsonArray("rowBackgroundColors")?.let { parseRowColorMatrix(it) }
            edits.add(
                AiAction.BatchEdit(
                    type = type,
                    range = range,
                    rows = rows,
                    rowNumber = rowNumber,
                    rowNumbers = rowNumbers,
                    color = color,
                    rowTextColors = rowTextColors,
                    rowBackgroundColors = rowBackgroundColors
                )
            )
        }
        return edits.takeIf { it.isNotEmpty() }
    }

    /**
     * 把单个 tool call 转换为 [AiAction]。
     * 解析失败时返回 null,由调用方决定如何兜底(通常在 reply 中提示用户)。
     */
    private fun toolCallToAction(call: ToolCall): AiAction? {
        val args: JsonObject = runCatching { JsonParser.parseString(call.arguments).asJsonObject }
            .getOrNull() ?: return null
        return when (call.name) {
            ToolDefinitions.TOOL_QUERY_KEYS -> {
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val pattern = args.get("pattern")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() }
                val offset = args.get("offset")?.let { runCatching { it.asInt }.getOrNull() }
                val includeTranslations = args.get("includeTranslations")?.let {
                    runCatching { it.asBoolean }.getOrNull()
                } ?: false
                AiAction.QueryKeys(module, pattern, limit, offset, includeTranslations)
            }
            ToolDefinitions.TOOL_READ_STRING -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                if (name.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                AiAction.ReadString(module, name)
            }
            ToolDefinitions.TOOL_UPDATE_STRING -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                if (name.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val translationsObj = args.getAsJsonObject("translations") ?: return null
                val translations = translationsObj.entrySet()
                    .associate { it.key to it.value.extractText() }
                    .filterValues { it.isNotEmpty() }
                if (translations.isEmpty()) return null
                AiAction.UpdateString(module, name, translations)
            }
            ToolDefinitions.TOOL_DELETE_STRING -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                if (name.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val languagesArray = args.getAsJsonArray("languages")
                val languages = languagesArray?.mapNotNull { element ->
                    element?.asString?.trim()?.takeIf { it.isNotEmpty() }
                } ?: emptyList()
                AiAction.DeleteString(module, name, languages)
            }
            ToolDefinitions.TOOL_FIND_KEYS_BY_TEXT -> {
                val text = args.get("text")?.asString?.trim() ?: return null
                if (text.isEmpty()) return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val language = args.get("language")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val matchType = parseMatchType(args.get("matchType")?.asString)
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30
                AiAction.FindKeysByText(text, module, language, matchType, caseSensitive, limit)
            }
            ToolDefinitions.TOOL_FIND_ROWS_BY_TEXT -> {
                val text = args.get("text")?.asString?.trim() ?: return null
                if (text.isEmpty()) return null
                val spreadsheetId = args.get("spreadsheetId")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val sheetName = args.get("sheetName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val column = args.get("column")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val matchType = parseMatchType(args.get("matchType")?.asString)
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30
                AiAction.FindRowsByText(text, spreadsheetId, sheetName, column, matchType, caseSensitive, limit)
            }
            ToolDefinitions.TOOL_INSERT_STRINGS -> {
                val name = args.get("name")?.asString?.trim() ?: return null
                val module = args.get("module")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val translationsObj = args.getAsJsonObject("translations") ?: return null
                val translations = translationsObj.entrySet().associate { it.key to it.value.extractText() }
                if (name.isNotEmpty() && translations.isNotEmpty()) {
                    AiAction.InsertStrings(module, name, translations)
                } else null
            }
            ToolDefinitions.TOOL_ASK_USER -> {
                val question = args.get("question")?.asString ?: return null
                val optionsArray = args.getAsJsonArray("options")
                val options = optionsArray?.mapNotNull {
                    it?.extractText()?.takeIf { o -> o.isNotEmpty() }
                } ?: emptyList()
                AiAction.AskUser(question, options)
            }
            ToolDefinitions.TOOL_LOAD_TOOL_DOC -> {
                val tool = args.get("tool")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                AiAction.LoadToolDoc(tool)
            }
            ToolDefinitions.TOOL_TASK_COMPLETE -> {
                val summary = args.get("summary")?.asString?.trim().orEmpty()
                val status = args.get("status")?.asString?.trim().orEmpty()
                if (summary.isEmpty() || status.isEmpty()) return null
                val notes = args.get("notes")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                AiAction.TaskComplete(summary, status, notes)
            }
            ToolDefinitions.TOOL_SHEETS_OPERATION -> {
                val operationText = args.get("operation")?.asString ?: return null
                val operation = runCatching {
                    AiAction.SheetsOperation.Operation.valueOf(operationText.uppercase())
                }.getOrNull() ?: return null
                val spreadsheetId = args.get("spreadsheetId")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val sheetName = args.get("sheetName")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val range = args.get("range")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val key = args.get("key")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val rowNumber = args.get("rowNumber")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val rowsArray = args.getAsJsonArray("rows")
                val rows = rowsArray?.mapNotNull { rowElement ->
                    if (!rowElement.isJsonArray) return@mapNotNull null
                    rowElement.asJsonArray.map { it?.extractText().orEmpty() }
                }
                val columnIndex = args.get("columnIndex")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it > 0 }
                val columnHeader = args.get("columnHeader")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val columnValuesArray = args.getAsJsonArray("columnValues")
                val columnValues = columnValuesArray?.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString
                        el.isJsonNull -> null
                        else -> el.extractText().ifEmpty { null }
                    }
                }
                val freezeRowCount = args.get("freezeRowCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it >= 0 }
                val freezeColumnCount = args.get("freezeColumnCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it != null && it >= 0 }
                val color = args.get("color")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val textColor = args.get("textColor")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val rowTextColors = args.getAsJsonArray("rowTextColors")?.let { parseRowColorMatrix(it) }
                val columnTextColors = args.getAsJsonArray("columnTextColors")?.let { parseColumnColorList(it) }
                val batchEdits = args.getAsJsonArray("batchEdits")?.let { parseBatchEdits(it) }
                AiAction.SheetsOperation(
                    operation,
                    spreadsheetId,
                    sheetName,
                    range,
                    key,
                    rowNumber,
                    rows,
                    columnIndex,
                    columnHeader,
                    columnValues,
                    freezeRowCount,
                    freezeColumnCount,
                    color,
                    textColor,
                    rowTextColors,
                    columnTextColors,
                    batchEdits
                )
            }
            // region ============== 文件操作域解析(2026 新增) ==============
            ToolDefinitions.TOOL_GET_EDITOR_FILE -> {
                AiAction.GetEditorFile()
            }
            ToolDefinitions.TOOL_READ_FILE -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                if (path.isEmpty()) return null
                val startLine = args.get("startLine")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
                val endLine = args.get("endLine")?.let { runCatching { it.asInt }.getOrNull() } ?: -1
                val maxLines = args.get("maxLines")?.let { runCatching { it.asInt }.getOrNull() } ?: 600
                AiAction.ReadFile(path, startLine, endLine, maxLines)
            }
            ToolDefinitions.TOOL_EDIT_FILE -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                val oldText = args.get("oldText")?.asString ?: return null
                val newText = args.get("newText")?.asString ?: return null
                if (path.isEmpty() || oldText.isEmpty()) return null
                val useRegex = args.get("useRegex")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val replaceAll = args.get("replaceAll")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                AiAction.EditFile(path, oldText, newText, useRegex, replaceAll)
            }
            ToolDefinitions.TOOL_CREATE_FILE -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                val content = args.get("content")?.asString ?: return null
                if (path.isEmpty()) return null
                val overwrite = args.get("overwrite")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                AiAction.CreateFile(path, content, overwrite)
            }
            ToolDefinitions.TOOL_SEARCH_IN_FILES -> {
                val pattern = args.get("pattern")?.asString ?: return null
                if (pattern.isEmpty()) return null
                val useRegex = args.get("useRegex")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val filePattern = args.get("filePattern")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val relativeDir = args.get("relativeDir")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 100
                AiAction.SearchInFiles(pattern, useRegex, caseSensitive, filePattern, relativeDir, limit)
            }
            ToolDefinitions.TOOL_FIND_REFERENCES -> {
                val symbol = args.get("symbol")?.asString?.trim() ?: return null
                if (symbol.isEmpty()) return null
                val kind = args.get("kind")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: "general"
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 100
                AiAction.FindReferences(symbol, kind, caseSensitive, limit)
            }
            ToolDefinitions.TOOL_LIST_FILES -> {
                val relativeDir = args.get("relativeDir")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: "."
                val pattern = args.get("pattern")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: "*"
                val recursive = args.get("recursive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val includeDirs = args.get("includeDirs")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val maxEntries = args.get("maxEntries")?.let { runCatching { it.asInt }.getOrNull() } ?: 500
                AiAction.ListFiles(relativeDir, pattern, recursive, includeDirs, maxEntries)
            }
            // endregion
            else -> null
        }
    }

    /**
     * 从 AI 回复文本中提取 JSON 对象，兼容以下常见格式：
     * 1. 纯 JSON 文本
     * 2. ```json ... ``` 或 ``` ... ``` 代码块包裹的 JSON（代码块前后可能有说明文字）
     * 3. 前后带有说明文字的裸 JSON
     * 通过匹配花括号定位完整的 JSON 对象，正确处理字符串内的花括号与转义字符。
     */
    private fun extractJsonObject(text: String): JsonObject? {
        val trimmed = text.trim()
        // 1. 直接尝试解析整段文本
        trimmed.parseAsJsonObject()?.let { return it }

        // 2. 逐个尝试从花括号位置匹配完整 JSON 对象
        //    覆盖代码块包裹、前后有说明文字等情况
        var searchFrom = 0
        while (true) {
            val startIdx = trimmed.indexOf('{', searchFrom)
            if (startIdx < 0) break
            val jsonStr = extractBalancedJson(trimmed, startIdx)
            if (jsonStr != null) {
                jsonStr.parseAsJsonObject()?.let { return it }
            }
            searchFrom = startIdx + 1
        }
        return null
    }

    /**
     * 从 startIdx 位置的 '{' 开始，匹配花括号找到完整的 JSON 对象字符串。
     * 正确处理字符串内的花括号和转义字符，避免误匹配。
     */
    private fun extractBalancedJson(text: String, startIdx: Int): String? {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun String.parseAsJsonObject(): JsonObject? {
        return try {
            JsonParser.parseString(this).asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun openAiTranslateBody(model: String, code: String, text: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", SYSTEM_PROMPT)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "目标语言代码：$code，需要翻译文本：$text")
                    })
                }
            )
        }
        return root.toString()
    }

    private fun anthropicTranslateBody(model: String, code: String, text: String): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("system", SYSTEM_PROMPT)
            addProperty("max_tokens", 1024)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "目标语言代码：$code，需要翻译文本：$text")
                    })
                }
            )
        }
        return root.toString()
    }

    private fun parseOpenAiText(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { return it }
        val message = root.getAsJsonArray("choices")
            ?.firstObject()
            ?.getAsJsonObject("message")
        return message?.get("content")?.extractText().orEmpty()
    }

    private fun parseAnthropicText(body: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { return it }
        return root.get("content")?.extractText().orEmpty()
    }

    private fun parseModelIds(body: String): List<String> {
        val root = JsonParser.parseString(body).asJsonObject
        root.errorMessage()?.let { throw IllegalStateException(it) }
        val data = root.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            when {
                element.isJsonObject -> element.asJsonObject.get("id")?.asString
                element.isJsonPrimitive -> element.asString
                else -> null
            }
        }.distinct()
    }

    private fun JsonObject.errorMessage(): String? {
        val error = get("error") ?: return null
        return when {
            error.isJsonObject -> error.asJsonObject.get("message")?.asString ?: error.toString()
            error.isJsonPrimitive -> error.asString
            else -> error.toString()
        }
    }

    private fun JsonElement.extractText(): String {
        return when {
            isJsonPrimitive -> asString
            isJsonArray -> asJsonArray.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString
                    element.isJsonObject -> element.asJsonObject.get("text")?.asString
                        ?: element.asJsonObject.get("content")?.extractText()
                    else -> null
                }
            }.joinToString("")
            isJsonObject -> asJsonObject.get("text")?.asString
                ?: asJsonObject.get("content")?.extractText()
                ?: toString()
            else -> ""
        }
    }

    private fun JsonArray.firstObject(): JsonObject? {
        return firstOrNull { it.isJsonObject }?.asJsonObject
    }

    private fun String.limitForMessage(): String {
        return if (length <= 500) this else take(500) + "..."
    }
}
