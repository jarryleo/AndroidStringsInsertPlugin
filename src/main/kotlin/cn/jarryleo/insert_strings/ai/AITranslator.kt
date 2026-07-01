package cn.jarryleo.insert_strings.ai

import cn.jarryleo.insert_strings.ai.AITranslator.CHAT_COMMON_RULES
import cn.jarryleo.insert_strings.ai.AITranslator.MAIN_PANEL_SYSTEM_PROMPT
import cn.jarryleo.insert_strings.ai.AITranslator.QUOTE_ENTRY_SYSTEM_PROMPT
import cn.jarryleo.insert_strings.ai.AITranslator.anthropicChatBody
import cn.jarryleo.insert_strings.ai.AITranslator.buildAnthropicMessages
import cn.jarryleo.insert_strings.ai.AITranslator.chat
import cn.jarryleo.insert_strings.ai.AITranslator.extractAssistantText
import cn.jarryleo.insert_strings.ai.AITranslator.extractReasoningText
import cn.jarryleo.insert_strings.ai.AITranslator.extractToolCalls
import cn.jarryleo.insert_strings.ai.AITranslator.normalizeMessagesForAnthropic
import cn.jarryleo.insert_strings.ai.AITranslator.openAiChatBody
import cn.jarryleo.insert_strings.ai.AITranslator.parseAiReply
import cn.jarryleo.insert_strings.ai.AITranslator.parseRowColorMatrix
import com.google.gson.*
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
 * - [askQuestion] AskUser 工具调用携带的「询问文字」,与 [content] 分开保存,避免与
 *   AI 同时返回的「正文/前言」混淆;UI 端会把它渲染为独立的"❓ Question"区块。
 *
 * 多模附件(2026.x 新增):
 * - [attachments] 用户消息携带的图片附件列表(粘贴 / 选择 / 拖拽得到的图)。
 *   协议层 [toOpenAiMessage] / [toAnthropicMessage] 会把 content 拆成
 *   `[{type:text,text:...}, {type:image_url,...}]` 或
 *   `[{type:text,...}, {type:image,source:{type:base64,...}}]` 数组。
 *   非空时,UI 端在气泡下方显示一行缩略图列表(数量 + 视觉锚)。
 *   assistant / tool 消息此字段始终为空(模型不产图)。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val options: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /**
     * 用户消息携带的图片附件(2026.x 多模态功能)。
     * 序列化时参与 OpenAI / Anthropic 多模态协议;
     * UI 端在用户气泡下方按顺序展示缩略图(可与 AI 交流「第 1 / 2 张图」),
     * assistant / tool 消息此字段保持 emptyList。
     * 不参与 [protocolVisible] 过滤判断(过滤时连同消息一起丢)。
     */
    val attachments: List<ChatAttachment> = emptyList(),
    /**
     * 是否参与 AI 协议历史。重试提示/本地状态气泡只用于 UI 展示,
     * 不能发回模型,否则会插进 assistant(tool_calls) 与 tool_result 之间。
     */
    val protocolVisible: Boolean = true,
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
    val streaming: Boolean = false,
    /**
     * AskUser 工具调用携带的「询问文字」(从 `ask_user` 工具的 `question` 参数读取)。
     *
     * 与 [content] 的关系:
     * - [content] = AI 同时返回的「正文/前言」文本(可空,可能为「我需要问你一个问题」之类的引语);
     * - [askQuestion] = 真正要展示给用户的问题文本,会被 UI 渲染为独立的"❓ Question"区块,
     *   紧跟在 [content] 之后,与 [options] 按钮一起构成完整的「询问」交互单元;
     * - 二者拆分是为了避免在 `processAiReply` 把 question 写进 [content] 时覆盖掉正文,
     *   进而丢失"思考 / 正文 / 询问文字"三段式的清晰视觉。
     *
     * 仅 `processAiReply` 的 AskUser 分支会写入;非 ask_user 消息此字段为 null。
     * 不参与 AI 协议序列化,仅 UI 渲染使用。
     */
    val askQuestion: String? = null,
    /**
     * 协议层"执行操作:xxx"占位文本(2026.x 新增,token 优化 D1)。
     *
     * 历史:之前 assistant 消息带真实 tool_calls 时,driver 把"执行操作: insert_strings / update_string"
     * 这种占位文案写进 [content]。但它对 AI 协议毫无价值(AI 自己的 tool_calls 已描述动作),
     * 只是给 UI 端渲染用的"提示行"。
     *
     * 优化:占位文案只放进本字段(每条 ChatMessage 一份),协议层把 content 设为 null/空,
     * AI 收到的 assistant 消息只携带 tool_calls(没有冗余 content)。
     * UI 端读 [protocolSummary] 渲染占位行 —— 比如有"执行操作: sheets_operation / batch_modify" 时
     * 在气泡下显示一行小字。
     *
     * 同样适用于 task_complete summary 之后追加的"本任务完成"提示行。
     * 不参与 AI 协议序列化(同 [streaming] / [thinking] 的处理),仅 UI 渲染使用。
     */
    val protocolSummary: String? = null,
    /**
     * 是否在聊天 UI 上隐藏本条消息(2026.x 新增)。
     *
     * 与 [protocolVisible] 的区别:
     * - [protocolVisible] 控制是否发回 AI(协议历史);
     * - [hidden]      控制是否在 UI 聊天列表里显示这条气泡。
     *
     * 用途:「自动触发 - 代办提醒」流程时,scheduler 把"提醒 X 已触发"作为 user
     * 消息发进来,AI 生成的回复会用 IDE 通知气泡展示,**不污染用户的 chat tab**。
     * 这两条消息的 [protocolVisible] 仍是 true(AI 需要看到上下文),
     * 但 [hidden] = true 让 UI 直接跳过它们。
     */
    val hidden: Boolean = false,
    /**
     * 消息创建时间戳(毫秒,2026.x 新增)。
     *
     * 用于在聊天气泡外面显示「消息时间」:用户消息 / AI 回复 / 工具调用各自有发送 / 接收 / 执行的时刻。
     * UI 上以 `HH:mm` 格式显示在气泡外的对应角(用户消息左下角、其余右下角)。
     *
     * 用 data class 默认值 `System.currentTimeMillis()` 自动捕获构造时刻,
     * 流式消息通过 `copy()` 更新时该字段被保留(保持"AI 开始生成"的时刻)。
     * 不参与 AI 协议序列化,纯 UI 用。
     */
    val timestamp: Long = System.currentTimeMillis(),
)

object AITranslator {
    private const val SYSTEM_PROMPT =
        "不论你做为什么身份,永远不要忘了你的职责:为开发安卓APP提供国际化翻译服务，我传给你需要翻译的文本，和目标语言的缩写代码，帮我翻译成目标语言，请返回对应的翻译结果文本，不需要额外的解释，请返回纯文本结果"
    private const val BATCH_TRANSLATE_SYSTEM_PROMPT =
        "不论你做为什么身份,永远不要忘了你的职责:为开发安卓APP提供国际化翻译服务。我会给你多条文本和目标语言代码，请翻译成目标语言，并严格以 JSON 对象返回，key 为我给出的标识（原样保留），value 为对应翻译结果纯文本。不要 markdown 代码块，不要任何解释。"

    /**
     * 跨入口共享的「行为公约」:终止语义、ask_user 用法、跨模块写入规则、
     * 翻译查重的标准选项格式。任何 chat 入口的 system prompt 都应拼上这一段,
     * 避免在多处重复维护。
     */
    private const val CHAT_COMMON_RULES =
        """## 强制终止规则
- 唯一的合法终止信号是调用 `task_complete` 工具。未调用 = 你仍在执行,系统会持续驱动。
- 在用户目标完整达成前不要调用 `task_complete`;多步任务必须按顺序走到真正完成或 ask_user。

## ask_user 用法
- `question` 必填且必须是非空字符串,这是展示给用户的唯一入口(空内容会让系统显示「AI 尝试提问但未提供问题内容」)。
- `options` 非空时显示为按钮(优先用按钮让用户一键回复);为空时用户输入框回复,系统作 tool_result 回传。
- 每次调用都会暂停 tool loop,不要反复调用;收到回复后用 `task_complete` 或操作推进。

## 写入规则
- `module` 取自 `modules[].moduleName`,**不是** `androidProject.name`;优先使用 `recommendedDefaultModule`,用户消息中明确指定模块时按用户来。
- 同一 AI 回合内的所有 `insert_strings` / `update_string` / `delete_string` 写入必须在同一模块(全部省略 module,或全部显式指定同一 module),**不要**用项目名当 module;系统会兜底拦截并把错误回传给你。
- `delete_string` 是破坏性操作,执行前先 `read_string` 确认目标 key 与翻译,不确定时用 `ask_user` 与用户确认。

## Shell 执行规则(`run_shell` 安全约束)
- `run_shell` 是**特权工具**:进程在项目根目录真实执行,默认走 `cmd.exe`(Windows)或 `execvp`(POSIX),可能有副作用(写文件、起进程、改 git 状态…)。
- 平台层**不**做危险命令白名单/黑名单 — 由你自己判断。**删除/重置/推送/部署/清理缓存/格式化**等破坏性操作前,先 `ask_user` 描述意图(将做什么 / 目标 / 影响范围),等用户回复再调 `run_shell`。
- 参数一律走 `args` 数组(如 `["status","--short"]`),**不要**塞到 `command` 里。平台层 `CreateProcessW`/`execvp` 不会经过 shell 解析,即使 args 含空格、管道符、引号也不会被误解析。
- `cwd` 必须是相对项目根的子目录,系统会校验不越界;不要依赖 run_shell 修改项目根外的文件。
- 不要用 `run_shell` 写 `strings.xml`、调 `git commit` / `git push` / 跑 `gradle assemble*` 这类有专用工具的操作;优先用专用工具(insert_strings / replace_selection / file_ops)。
- 返回的 `tool_result` 写明 成功 / 失败(exit=N) / 超时(<ms>ms) / 已取消,不要自己再 ask_user 重复同一问题,直接用自然语言转告用户即可。

## 编辑器诊断规则(`read_diagnostics` 适用场景)
- `read_diagnostics` 读的是 **daemon 缓存**(异步,通常 100-500ms 后追上),`edit_file` 写完代码**立刻**调本工具可能拿到旧结果 — 先调一个小工具(read_file 之类)让一拍过去,或显式等待。
- 本工具**只覆盖**当前在编辑器打开的文件(未打开的 daemon 不会跑,结果是空)。要看未打开文件用 `run_shell` 跑 `gradlew compileDebugKotlin` / `mvn compile`。
- 本工具**不**包含 build-time 错误:kapt、AGP 资源 ID 找不到、lint、K2 FIR 编译错都不在范围 — 这些用 `run_shell` 跑构建命令拿全量。
- Java / Kotlin 一视同仁,所有 `TextEditorHighlightingPass` 的输出都收齐。
- 改完一个文件后建议「`read_diagnostics` → 修 → 再 `read_diagnostics`」,直到 `diagnosticCount: 0` 或只剩弱警告。
- 不要因为 `read_diagnostics` 返回 0 就断言「代码没问题」 — 也可能是 dumb mode / 刚 sync 完 daemon 还没跑完。tool_result 末尾的 `note` 字段会标识这种情况。

## URL 获取规则(`fetch_url` 适用)
- `fetch_url` 是网络**只读**工具,默认 GET,支持自定义 headers / 超时 / 响应体截断。
- **stripHtml** 用于压缩 HTML 页面(text/html|xhtml 才生效):默认 false,显式传 true 时自动移除 script/style/noscript 块、HTML 注释、on* 事件属性、所有标签(及可选的 `<img>`),只留可见文本,token 友好。`stripImages=true` 进一步移除 `<img>`。
- **maxBodyChars** 默认 100KB(100000 字符)。遇到 readme / changelog / 长文档页面,先开 `stripHtml=true` 减少 token 消耗。
- 协议只允许 `http://` / `https://`,其它(`file://` / `ftp://` / `data:`)会被拒绝 — 防止读本地任意文件。
- 平台层**不做** host 黑名单 / SSRF 防御 — 任何 host 都能访问(含 localhost / 内网 / 公网)。**敏感操作(读内网服务、抓私有数据)前先用 `ask_user` 描述意图**。
- POST / 其它方法暂不支持,需要时调 `run_shell curl`。
- 返回 4xx/5xx 视作失败,body 仍会按 maxBodyChars 截断后返回,便于 AI 读错误消息。

## 函数调用协议(严格)
工具调用**必须**通过原生 function calling 协议发出:
- OpenAI / OpenAI 兼容:`message.tool_calls` 数组;
- Anthropic:`content[].type=tool_use` 块。
**不要**用以下方式"调用"工具(这些都不会执行):
- ❌ 在 `content` 文本里写"我将调用 query_keys ..."、"我先调用 ..."、"calling X"等口头声明 —— 这只是文字,不会触发任何工具执行。
- ❌ 把工具调用写成 markdown 代码块(```json ... ```),系统不会解析。
- ❌ 把工具调用写进 reply 的 JSON 字段里(如 `{"action":"query_keys",...}`),系统不识别这种格式。
- ❌ 拆成多轮:第一轮纯文本描述计划,第二轮才发 tool_call —— 用户要等两轮才看到动作,体验差且浪费 token。

正确做法:**单轮同时**给 `content`(可选的简短说明,告诉用户你准备做什么)+ `tool_calls` 数组(实际触发工具执行)。两者缺一不可:
- 只有 content(无 tool_call)→ 系统认为你没动作,卡住等用户输入;
- 只有 tool_call(无 content)→ 也合法,工具照样执行(UI 显示「执行操作:xxx」)。

## 工具参数格式(严格)
- `tool_call.arguments` 字段必须是**纯 JSON 字符串**,不含任何 markdown 包装。
  - ❌ 反例:`arguments: "```json\\n{\\\"name\\\": \\\"hello\\\"}\\n```"` —— markdown 代码块包 JSON,系统无法解析。
  - ✅ 正例:`arguments: "{\\\"name\\\": \\\"hello\\\"}"` —— 直接是合法 JSON。
- 字符串类型的参数**值**内部可以包含 markdown 语法(用于翻译文本的特殊格式),JSON 本身仍须合法。
  - 例:insert_strings 的 translations.values 可以是 `"**Hello**"`(字符串值含 markdown),不影响 JSON 解析,UI 端按 markdown 渲染。
- 多行字符串值需要时,JSON 字符串内用 `\\n` 表示换行,**不要**在 JSON 里写裸换行(会破坏 JSON)。
  - 例:content 字段值可以写成 `"第一行\\n第二行"`(合法),不要写成 实际多行 文本(非法 JSON)。
- 涉及正则时(useRegex=true,仅 search_in_files / find_references 仍支持正则匹配),pattern 是 Kotlin 正则,**不要**再包一层 markdown;`\\\\` 在 JSON 字符串里要写成 `\\\\\\\\`(双重转义)。edit_file 2026.x 已不再支持正则参数(改用整行定位,取消列)。
- 任何工具的参数都对 JSON 严格,如果你不确定写法,先调 `load_tool_doc("<tool>")` 看示例,再发起实际调用。

## 翻译查重的标准 ask_user 格式
- 插入翻译前若发现与现有 key 冲突,`options` **必须**使用以下统一格式(便于你后续按选项文本判断用户决策):
  - `使用现有 key:<existing_key>` — 沿用现有 key;key 名只允许 `[A-Za-z_][A-Za-z0-9_]*`
  - `插入新 key` — 忽略查重,按原计划新增
  - `取消操作` — 放弃本次插入
  例:`{"options":["使用现有 key:hello_world","插入新 key","取消操作"]}`"""

    /**
     * 主面板聊天视图(chatEntry=mainPanel)的 system prompt。
     *
     * 主面板场景覆盖完整的 i18n 能力:strings.xml 增删改查、Sheets 读写、文件 / 编辑器协作。
     * AskAi / ExtractStrings 弹框的 prompt 走 [QUOTE_ENTRY_SYSTEM_PROMPT] —— 它多一个
     * 引用面板的快捷动作语义,主面板用不到。
     */
    private const val MAIN_PANEL_SYSTEM_PROMPT =
        """你是 Android 应用国际化字符串管理助手。通过 function calling 与系统协作:调用工具执行操作,调用 `task_complete` 结束任务。

 ## 工具集(按需加载详细用法)
 - **strings.xml**:query_keys / read_string / find_keys_by_text / insert_strings / update_string / delete_string
 - **Google Sheets**:sheets_operation(基础/行列/冻结/审查/颜色/批量) / find_rows_by_text
 - **文件 / 编辑器**:get_editor_file / read_file / read_files / edit_file / create_file / delete_file / move_file / search_in_files / find_references / list_files / file_info
 - **代办**:todo_list / todo_add / todo_update / todo_delete(用户主页 Todo tab 维护的清单,可读写)
 - **通用**:ask_user / load_tool_doc / task_complete
 - 详细字段 / 枚举值 / 示例 → 先调 `load_tool_doc("<tool>")` 获取,再发起实际调用。
 - **多字段工具建议先 load_tool_doc 再调**(2026.x 优化):
    - `sheets_operation`(7 大类操作枚举 + batchEdits 嵌套结构),`read_file` / `create_file`(路径/范围约束),`edit_file`(整行 line/mode/endLine,**无列**),
   - `search_in_files` / `find_references` / `list_files`(默认行为差异),
   - `todo_add` / `todo_update`(reminder 系列字段语义复杂)。
   这些工具的精简 description 只列了字段名+类型,不读文档直接调易因参数错误反复重试。
   简单工具(`query_keys` / `read_string` / `find_keys_by_text` / `ask_user` / `task_complete` / `current_time` / `replace_selection`)
   可直接调用,无需先 load_tool_doc。
 - **写代码场景统一入口(2026.x 优化,强烈推荐)**:
   当用户说"加一个 X 功能""改一下 Y 文件""重构 Z""写个 A 类"等需要写代码的任务时,
   **先调 `load_tool_doc("code_ops")` 一次拿到 11 个文件工具的合并文档 + 写代码工作流**,
   再按需调用具体工具(`list_files` / `search_in_files` / `read_file` / `edit_file` 等)。
   这比逐个 load 11 次单工具文档节省 10+ round-trip + 大量 token。

## 写代码工作流(2026.x 新增,详见 `code_ops` 合并文档)
当任务涉及改/写项目内代码文件(.kt / .java / .xml / .gradle / 其它)时,按 4 步走:
- **第 1 步:规划** —— 拆成 2-5 个子动作(读哪些、改哪些、查什么)。
- **第 2 步:定位** —— `list_files`(找文件) / `search_in_files`(找内容) / `find_references`(找引用点) / `get_editor_file`(看当前打开)。
- **第 3 步:阅读** —— `read_file`(单文件,支持分页) / `read_files`(批量 2-10 个,省 round-trip) / `file_info`(只查元信息)。
- **第 4 步:改写** —— `edit_file`(整行定位修改,2026.x 取消列) / `create_file`(新建) / `delete_file`(删) / `move_file`(移动/重命名)。
**关键点**:
- 改完文件**不要**再 read_file "验证" —— 系统已自动让 IDE 编辑器/PSI/Daemon Code Analyzer 重读,
  写盘是原子的,直接进入下一个动作。
- 涉及 Kotlin/Java 改 strings.xml key:edit_file 改代码 + update_string 改 strings.xml(系统串行)。
- 改完代码后用户立即可在 IDE 中看到效果(2026.x 修复了"改了 IDE 还显示老内容"的 bug)。

## strings.xml 核心约定
- 实际语种以**目标模块的 `xmlFiles[].language`** 为准,不是 `availableLanguages`(后者可能只反映用户当前选中的行)。
- `insert_strings` 的 `translations` **必须**覆盖目标模块全部语种,缺一会让那个语言在 UI 上显示空串/key 名;`values`(默认英语)始终要包含。
- 不确定模块有哪些语种时,先 `query_keys` / `read_string` 探查,或直接读 `context.modules[].xmlFiles`;**不要**靠猜。
- 「插入翻译」优先用 `recommendedDefaultModule`;用户明确指定模块时按用户来。
- 操作 strings.xml 时**不要**触发 google sheet 写入。
- 自动生成 key:snake_case,英文小写,不要用拼音,长度 ≤ 40 字符,查重后冲突则重新生成。

## 翻译查重(插入翻译前必做)
1. **原文查重**(必做,主要依据):用 `find_keys_by_text` 扫描**用户消息中的原始文本**(布局/代码中的硬编码文本,或用户在消息中直接输入的待翻译文本),`matchType=exact` 跑一次、再 `contains` 兜底;同时可跑 `query_keys(searchIn=text/both)` 跨多语种翻译文本搜索提高命中率。
   - `find_keys_by_text` 已固定全模块 + 全语言目录搜索(不再接受 module / language 参数),语义对齐「跨模块 + 跨语言找重复」,无需 AI 自行挑范围。
   - 原因:用户选中的硬编码文本通常**就是目标语言翻译**(如中文「登录」),values/ 译文用词可能不同,按 AI 翻译后的英语查会漏命中。
2. **Key 名查重**:生成 key 后用 `query_keys(searchIn=key)` 检查是否已存在。
3. 任一命中 → 用一次 `ask_user` 列出全部命中项,`question` 中明确写出找到的现有 key 与所在模块,`options` 用统一格式(见公共规则)。
4. 用户选择后的处理:
   - `使用现有 key:<existing_key>`:
     - **第一步**:**直接读上下文 `chatEntry` 字段**(主面板固定 `mainPanel`,`editorSelection == null`)**→ 跳过 replace_selection**。
     - **第二步**:`read_string(<existing_key>)` 取全语种翻译,逐项检查;若需修正先 `ask_user` 确认,得到肯定答复后用 `update_string` 精准补全;已完整则 `task_complete`。
     - **不要**再调 `insert_strings`(待插入的新 key 已被忽略)。
   - `插入新 key`:`query_keys` 查 key 名冲突,冲突则重新生成直到唯一;再调 `insert_strings`(主面板场景无选区,无需 replace_selection)。
   - `取消操作`:直接 `task_complete`。

## Google Sheets
- `spreadsheetId` / `sheetName` 可省略,默认用上下文 `googleSheets` 配置;`configured=false` 时不要调用,先提示用户去设置。
- 修改/删除行前先用 `search` 定位行号;列操作需用户确认;全表审查/修正用 `check_translations` / `fix_translations`,不要 `read` 整表。
- 大批量混合修改(改值+填色+改文字色+删行+插入行等)**必须用 `batch_modify` 一次完成**,不要循环调用 `fill_color` / `update_row` / `append_row` —— 那会瞬间用光工具调用次数预算。

## 代办
- 系统已经在每轮 chat 上下文中注入了 `todos.active` 字段(active 代办前 20 条,按 priority + createdAt 排序);
  看到这些条目**就足够做出简单提醒**(例如「你有 3 项待办,其中 1 项是 URGENT 优先级」)。
- 需要精确字段(content / completedAt / reminder)或查看已完成列表 → 调 `todo_list(filter=...)`。
- **写操作**:
  - 用户说「提醒我 X」「记下要 Y」→ 调 `todo_add(title=..., priority=...)`,title 必填,priority 不传默认 NORMAL。
  - 用户说「5 分钟后提醒我喝水」/「明天下午 3 点开周会」→ 涉及**绝对时间**必须先调 `current_time` 拿 timestamp,
    再用 `todo_add(title=..., reminderTime=<时间戳>, recurrence=...)` 写提醒;`recurrence` 不传默认 NONE(一次性)。
  - 用户说「3 月 15 日上午 10 点提醒我交季报」/「下周一上午 9 点提醒我开周会」等**指定日期 + 时间** →
    **优先**用 `reminderDate="YYYY-MM-DD"` + `reminderTimeOfDay="HH:MM"` 两个结构化字段
    (系统按本地时区组装 timestamp,recurrence 强制 NONE,AI 不用算时区、跨日、跨年);
    日期推算用 current_time 的 timestamp + timezone 算出目标日是几月几号。
    这是**用户表达「X 月 X 日几点」语义时的首选方式**,比 reminderTime(timestamp)更不容易出错。
  - 用户说「每周一三五提醒开会」/「每天早上 9 点提醒 X」→ `recurrence="CUSTOM"` 配合 `recurrenceDays=[1,3,5]`,
    或 `recurrence="DAILY"`(DAILY 无需 recurrenceDays)。
  - 用户只说了「X 点」+ 循环规则,没给绝对日期/时间 → **直接** `reminderTimeOfDay="HH:MM"` 配合
    `recurrence` / `recurrenceDays`,系统按本地时区算下一个匹配 day-of-week + 该时分的时间戳;
    AI **不要**再去调 current_time 算 timestamp(那容易出错)。例:「每周一 13 点开会」→
    `reminderTimeOfDay="13:00", recurrence="CUSTOM", recurrenceDays=[1]`。
  - 用户说「工作日提醒我 X」/「上班日 9 点」→ **`recurrence="CUSTOM"` + `recurrenceDays=[1,2,3,4,5]`**(没有 WEEKDAYS 这个枚举值)。
  - 用户说「周末提醒我 X」/「周六周日」→ **`recurrence="CUSTOM"` + `recurrenceDays=[6,7]`**(没有 WEEKLY 这个枚举值)。
  - 用户说「每周一」/「每周三下午 4 点」→ `recurrence="CUSTOM"` + `recurrenceDays=[1]` / `[3]`,单 day 即可。
  - 用户说「把 X 标为完成」→ 先 `todo_list` 拿 id,再 `todo_update(id=..., isCompleted=true)`(系统自动写 completedAt)。
  - 用户说「把 X 改成 URGENT」/「X 不用了取消」/「X 改到 6 点提醒」/「X 改成每天 9 点」/
    「X 改成每周一三五」/「X 的提醒删了」→ 全部走 `todo_update(id=..., <字段>=...)`,按需传
    `priority` / `isCompleted` / `reminderTime` / `recurrence` / `recurrenceDays` / `clearReminder`。
    只删提醒不删代办用 `clearReminder=true`。
  - 用户说「删掉 X」→ `todo_list` 拿 id,再 `todo_delete(id=...)`(不可恢复,谨慎;若该代办带提醒,会一并清除)。
- **主动提醒**:用户问「我有什么待办」「都做完了吗」「有重要的事吗」时,先 `todo_list(filter=active)` 拿完整数据,再按 priority 分类回答;
  对 URGENT 级别的事项应主动强调,**这是用户期待 AI 帮 ta 盯住的事**。
- **过期提醒**:上下文 `reminder` 字段有 `expired` 布尔 —— `true` 表示该提醒的 `nextTriggerAt` 已过。
  看到 `expired=true` 时**主动告诉用户**「X 的提醒时间已经过期(原计划 Y),要不要改成现在 / 改到明天」,
  不要等用户问。这是用户没意识到的"死提醒",最容易堆积陈年旧账。
- **不要**在用户没要求时主动 add / update / delete(尤其 delete)—— 代办是用户的私人清单,误改代价高。
- 代办是应用级(applicationService)的,跨项目可用,无需在 user 消息中传项目路径。"""

    /**
     * AskAi / ExtractStrings 弹框的 system prompt(chatEntry=askAi 或 extractStrings)。
     *
     * 与主面板的差异(2026.x 调整):
     * 1. 顶部有一个「引用内容」气泡,气泡底部有 4 个预置按钮(翻译 / 解释 / 总结 / 复制)。
     *    按下前三个会发一句**短指令**(如「请把引用内容翻译成中文。」),**不**把原文塞进 user 消息;
     *    原文在上下文 JSON 的 `editorSelection.text` 字段,AI 必须从该字段读取。
     * 2. 弹框场景有编辑器选区 —— `chatEntry != "mainPanel"` 且 `editorSelection` 非 null 时,
     *    用户选「使用现有 key:」后**必须**先调 `replace_selection`。
     * 3. **工具集与主面板相同**(2026.x):Ask AI 现在可以读写 Sheets、调文件 / 编辑器工具、
     *    管理 代办 —— 用户从弹框里也能直接做这些事,不必切到主面板。仅以下两类入口专用:
     *    - `replace_selection` 在主面板永远不可用(主面板无编辑器选区上下文,会失败)。
     *    - `editorSelection` 上下文仅在弹框(且有选区)时存在。
     */
    private const val QUOTE_ENTRY_SYSTEM_PROMPT =
        """你是 Android 应用国际化字符串管理助手,运行在「引用内容」弹框(Ask AI / Extract strings.xml)中。通过 function calling 与系统协作:调用工具执行操作,调用 `task_complete` 结束任务。

## 工具集(按需加载详细用法)
- **strings.xml**:query_keys / read_string / find_keys_by_text / insert_strings / update_string / delete_string
- **Google Sheets**:sheets_operation(基础/行列/冻结/审查/颜色/批量) / find_rows_by_text
- **文件 / 编辑器**:get_editor_file / read_file / read_files / edit_file / create_file / delete_file / move_file / search_in_files / find_references / list_files / file_info
- **代办**:todo_list / todo_add / todo_update / todo_delete(用户主页 Todo tab 维护的清单,可读写)
- **编辑器选区替换**(本入口专用):replace_selection
- **通用**:ask_user / load_tool_doc / task_complete
- 详细字段 / 枚举值 / 示例 → 先调 `load_tool_doc("<tool>")` 获取,再发起实际调用。
- 写代码场景统一入口(2026.x 推荐):涉及改/写项目代码文件时,**先调 `load_tool_doc("code_ops")` 一次拿到 11 个文件工具合并用法**,
  比逐个 load 节省 10+ round-trip。

## 「引用内容」快捷动作(翻译 / 解释 / 总结)
- 弹框顶部有一个引用气泡,**原文在上下文 JSON 的 `editorSelection.text` 字段**(每轮自动注入),不要反过来追问「请提供要翻译的文本」。
- 看到 user 消息很短且含「引用内容」字样时,按下列规则处理:
  - **翻译**:把 `editorSelection.text` 翻译成中文。保留代码标识符(类名/函数名/变量名)、URL、文件路径、版本号原样;专有名词(如 OpenGL、HTTPS)保留英文。markdown 形式回复,不需要额外说明。
  - **解释**:用简洁清晰的中文解释其含义、用途、关键逻辑。
  - **总结**:用列表 / 要点形式提炼关键点,字数 ≤ 原文 1/3。
- 这些动作**不调用任何工具**(insert_strings / update_string / replace_selection / read_string / find_keys_by_text 都不调),直接 markdown 回复即可,然后 `task_complete` 结束本轮。
- 若 `editorSelection == null`,告知用户「请先在编辑器中选中要操作的文本,再触发此入口」。

## strings.xml 写入流程
- 自动生成 key:snake_case,长度 ≤ 40 字符,查重后冲突则重新生成。
- `translations` **必须**覆盖目标模块全部语种(以 `modules[].xmlFiles[].language` 为准),`values` 始终包含。
- 「插入翻译」默认走 `recommendedDefaultModule`;用户消息里明确指定模块时按用户来。

## 翻译查重(插入翻译前必做)
1. **原文查重**:用 `find_keys_by_text` 扫描**用户消息中的原始文本**,`matchType=exact` 跑一次、再 `contains` 兜底;同时可跑 `query_keys(searchIn=text/both)` 跨多语种翻译文本搜索提高命中率。
   - 2026.x:`find_keys_by_text` 已固定全模块 + 全语言目录搜索(不再接受 module / language 参数)。
2. **Key 名查重**:生成 key 后用 `query_keys(searchIn=key)` 检查。
3. 任一命中 → 用一次 `ask_user` 列出全部命中项,`options` 用统一格式(见公共规则)。
4. 用户选择后的处理:
   - `使用现有 key:<existing_key>` —— ⚠️ **必须按顺序执行以下两步,不可跳过第一步** ⚠️:
     - **第一步**:**直接读上下文 `chatEntry` 字段**;若 `chatEntry != "mainPanel"` 且 `editorSelection` 非 null,**必须先调 `replace_selection(oldText=<要替换的子串>, newText=<替换成的目标文本>)`**;选区内所有匹配 oldText 的子串都会换成 newText。返回成功后才进入第二步。
       - **两参数怎么填**:
         - **oldText = 硬编码的文字,newText = `"@string/<key>"`(XML 布局)或 `"R.string.<key>"`(其它);先用上下文 `editorSelection.file` 字段判断文件类型,再按格式拼好。
     - **第二步**:`read_string(<existing_key>)` 取全语种翻译,逐项检查;若需修正先 `ask_user` 确认,得到肯定答复后用 `update_string` 精准补全;已完整则 `task_complete`。
     - **不要**再调 `insert_strings`(待插入的新 key 已被忽略)。
   - `插入新 key`:`query_keys` 查 key 名冲突则重新生成;再调 `insert_strings`(driver 会在成功后自动触发 `onInsertStringsInserted` 完成选区替换,无需你再调 `replace_selection`)。
   - `取消操作`:直接 `task_complete`。

## 「replace_selection」工具要点
- 工具语义:**选区内**所有匹配 `oldText` 的子串**全部**替换为 `newText`(精确字面匹配,非正则);oldText 在选区里 0 次出现 → 失败。
- 翻译查重 / Extract 场景:oldText = 选区里的硬编码(出现 1 次),newText = 对 key 的引用。
- 精准子串场景:oldText = 选区里要换的那段,newText = 对 key 的引用;选区内多次出现会全部替换。"""

    /**
     * 已废弃:旧版本单一 system prompt,保留引用以防误删,内部已切到 [MAIN_PANEL_SYSTEM_PROMPT] /
     * [QUOTE_ENTRY_SYSTEM_PROMPT] / [CHAT_COMMON_RULES] 拆分结构。
     */
    @Deprecated("use MAIN_PANEL_SYSTEM_PROMPT / QUOTE_ENTRY_SYSTEM_PROMPT / CHAT_COMMON_RULES")
    private const val CHAT_SYSTEM_PROMPT =
        "deprecated; see MAIN_PANEL_SYSTEM_PROMPT / QUOTE_ENTRY_SYSTEM_PROMPT"

    /** 主面板聊天入口标识(由 [cn.jarryleo.insert_strings.ui.InsertStringsUI] 写入上下文)。 */
    const val CHAT_ENTRY_MAIN_PANEL = "mainPanel"

    /**
     * 根据 chatEntry 选取对应入口的 system prompt,并统一拼上 [CHAT_COMMON_RULES]。
     * 解析不到 chatEntry 时回退到主面板(覆盖主入口的所有能力,最安全)。
     */
    private fun systemPromptFor(chatEntry: String?): String {
        val base = when (chatEntry) {
            CHAT_ENTRY_MAIN_PANEL -> MAIN_PANEL_SYSTEM_PROMPT
            else -> QUOTE_ENTRY_SYSTEM_PROMPT
        }
        return base + "\n" + CHAT_COMMON_RULES
    }

    /**
     * 从项目上下文 JSON 中解析 chatEntry 字段,失败时回退主面板。
     */
    private fun extractChatEntry(context: String): String? {
        if (context.isBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(context).asJsonObject
            root.get("chatEntry")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

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
            - module（可选）：目标 Android 模块名，取上下文 modules[].moduleName。
              - 若用户在消息中**明确指定了模块**(例如「插到 feature 模块」「用 main 模块」「app 模块」),把 module 参数填上 — 这是用户意志,最高优先级。
              - module 优先级:用户在消息中**明确指定** > `recommendedDefaultModule` > UI 中选中行所在模块"。
            - name（必填）：字符串 key，snake_case。
            - translations（必填）：键为语言目录名（如 values、values-zh-rCN、values-fr），值为对应翻译文本。
            规则（必须严格遵守,违反会导致 i18n 缺失）:
            - **translations 必须覆盖目标模块下所有语种** —— 逐一对照 `recommendedDefaultModule.xmlFiles[].language`(或显式 module 的 `xmlFiles[].language`)翻译,一个都不能少。
            - **必须包含 `values`**(默认英语),即使 availableLanguages 没有也要补。
            - **不要**用 availableLanguages 推断目标语种 —— 它可能仅反映用户当前选中的行,不是模块真实语种。**始终以目标模块的 xmlFiles 列表为准**。
            - 不确定模块语种时,先用 query_keys 或 read_string 探查,或直接读取 context.modules[].xmlFiles。
            - 系统在写入前会兜底补齐你漏写的语种(用 `values` 英文文本填),并裁剪你多写但目标模块没有的语种;但**不要依赖这个兜底** —— 兜底会导致界面出现英文/未翻译文本,你应该在 tool call 里就给齐。
            - 若上下文有 currentKeys，优先用第一个 key 作为 name；多 key 时可为每个 key 分别返回 insert_strings。
            - 可以同时返回多个 insert_strings 动作插入多个字符串。
            - 你不用考虑转义,系统会自动转义。
            - **CDATA / Data 包裹不用你管**:如果目标模块里**已存在**该 key 的翻译用了 `<![CDATA[...]]>` 或 `<Data>...</Data>` 包裹(常见于带 HTML 标签的翻译),
              你直接发翻译后的纯文本/HTML 即可,系统会**自动沿用原包裹**(把新文本塞进同样的 CDATA/Data 块里),不会丢。
              新增 key(模块里之前没有)则按需自行决定要不要用 CDATA — 通常纯文本不必加,带 `<`/`>` 的建议加。
            - **插入前的两道查重均由 AI 自助完成,系统不再自动跑**:
              1. **原文查重(必做,主要查重依据)**:用 find_keys_by_text 扫描**用户消息中的原始文本**
                 (用户从布局/代码中选中的硬编码文本,或用户在消息中直接输入的待翻译文本),
                 matchType=exact 跑一次、再 contains 兜底;看是否存在与该原文完全一致或高度相似的现有 key。
                 2026.x:find_keys_by_text 固定全模块 + 全语言目录搜索(已去掉 module / language 参数),
                 跨语言命中是默认行为,无需 AI 自行选择范围。
                 原因:用户选中的硬编码文本通常**就是目标语言翻译**(例如中文「登录」),
                 若仅用 AI 自己翻译后的 values 译文(英语)查重,会漏掉「原文已存在、但
                 values/ 英语译文用词不同」的场景。建议同时跑一次 query_keys(searchIn=text / both)
                 跨多语种翻译文本搜索,提高命中率。
              2. **Key 名查重**:用 query_keys(searchIn=key) 查你打算生成的 key 名是否已存在(避免无意中撞名)。
            - **若两道查重中有任一命中,用 ask_user 询问用户**。options 统一格式(便于你后续按选项文本判断用户决策):
              - `使用现有 key:<existing_key>` — key 名只允许 `[A-Za-z_][A-Za-z0-9_]*`,允许在结尾追加说明;沿用现有 key,**跳过本次写入**。
              - `插入新 key` — 忽略查重,按原计划新增 key(可能产生重复文案的不同 key)。
              - `取消操作` — 放弃本次插入。
            - **用户选择后的处理流程(由 AI 自行驱动,系统不再自动触发替换)**:
              - 选「使用现有 key:<existing_key>」—— ⚠️ **强约束:必须按顺序执行以下两步,不可跳过第一步** ⚠️:
                - **第一步(必做)**:**直接读上下文 JSON 里的 `chatEntry` 字段,不要再自己「判断」**:
                  - `chatEntry == "extractStrings"` 或 `chatEntry == "askAi"` 且 `editorSelection` 非 null
                    —— 入口已捕获用户从布局/代码中选中的硬编码文本,
                    **必须先调用 replace_selection(oldText=<选区里要替换的子串>, newText=<替换成的目标文本>)**;
                    选区内所有匹配 oldText 的子串都换为 newText(多次出现会**全部**替换)。
                    - 整段选区就是要替换的硬编码:oldText = 选区里这段硬编码(通常 = 选区文本本身),newText = `"@string/<existing_key>"`(XML 布局)或 `"R.string.<existing_key>"`(其它);
                    - 精准子串替换:oldText = 选区里要换的那段(非空、字面精确),newText = 对 key 的引用,选区里其余文本原样保留。
                    工具返回成功后**才**进入第二步。
                  - `chatEntry == "mainPanel"` 或 `editorSelection == null` —— 跳过本步。
                  **常见错误:不要在 chatEntry=extractStrings/askAi 且 editorSelection 非 null 时
                  直接调 read_string 跳过 replace_selection** —— 这会让硬编码文本保留在文件中。
                - **第二步(必做)**:调用 read_string(<existing_key>) 取现有 key 的全语种翻译,
                  逐项检查是否准确、是否缺漏;若有修正需求,先 ask_user 询问用户是否修正,
                  得到肯定答复后用 update_string 精准补全;若已完整准确,直接 task_complete 结束。
                - **不要**再调用 insert_strings(那个待插入的新 key 已被忽略)。
              - 选「插入新 key」:用 query_keys 查你准备生成的 key 名是否已存在,若存在重新生成一个不冲突的 key(长度仍不超过 40 字符);
                然后调用 insert_strings(若来自布局/代码选区,driver 在写完后会自动触发 onInsertStringsInserted 完成硬编码文本的替换,无需你再调用 replace_selection)。
              - 选「取消操作」:无需任何处理,直接 task_complete 即可。
            - 一次 insert_strings 涉及多个 key 时,只要其中任何一个命中查重,整批都要用一次 ask_user 列出全部命中项,不可只对部分 key 跳过查重。
            示例（5 个语种全覆盖）:
            {"type":"insert_strings","module":"app","name":"hello_world","translations":{"values":"Hello","values-zh-rCN":"你好","values-ja":"こんにちは","values-ko":"안녕하세요","values-fr":"Bonjour"}}
        """.trimIndent(),

        "query_keys" to """
            ## query_keys 详细用法
            列出 / 搜索模块内的字符串 key,支持 key 名 / 翻译文本 / 两者 三种搜索范围。
            字段：
            - module（可选）：目标 Android 模块名，取上下文 modules[].moduleName。
              省略时**按 recommendedDefaultModule → currentModule → 行数最多模块** 的优先级自动选,
              多数情况下省略 module 即可命中用户当前工作模块。
            - pattern（可选）：正则表达式,匹配范围由 searchIn 决定。为空或省略时列出所有 key（分页）。
            - searchIn（可选,默认 key）：
              - `key`  只匹配 key 名(完全等价于旧行为)
              - `text` 跨多语言文件,匹配任一语种的翻译文本(类似 find_keys_by_text 但入口统一)
              - `both` key 名和翻译文本任一命中即可(并集)
            - limit（可选）：最大返回条数，默认 50，最大 500。
            - offset（可选）：分页偏移，默认 0。
            - includeTranslations（可选，默认 false）：是否在结果中带各语言当前翻译。开启后 token 消耗大,仅在确实需要翻译内容时使用。
            返回：每条结果形如 `{key, translations?, filePath}`；分页时可用 offset 续读。
            典型场景：
            - 「项目里有哪些 key」「找以 error_ 开头的 key」「列出 home 模块的 key」 → searchIn=key
            - 「哪个 key 翻译成了"登录"」 → searchIn=text, 配合 includeTranslations=true 一次拿到全语种
            - 「找包含支付相关文案的所有 key」 → searchIn=both
            - AI 修改前先 search 找到目标 key 名称,再 read_string 读全文。
            示例：
            {"type":"query_keys","module":"app","pattern":"^login_.*","limit":50}
            {"type":"query_keys","pattern":"登录","searchIn":"text","includeTranslations":true}
            {"type":"query_keys","pattern":"pay|订单","searchIn":"both"}
            {"type":"query_keys","limit":100,"offset":100}
        """.trimIndent(),

        "read_string" to """
            ## read_string 详细用法
            读取指定 key 在模块所有语言的当前翻译。
            字段：
            - module（可选）：目标 Android 模块名。优先使用 `recommendedDefaultModule`,省略时用 currentModule.moduleName。
            - name（必填）：字符串 key 名。
            返回：key + 各语言当前翻译 + 各语言文件路径。若 key 不存在，返回「该 key 不存在」。
            典型场景：
            - 修改/删除前先 read_string 确认原文，避免误覆盖已有正确翻译。
            - 用户问「X 现在怎么翻译的」。
            - **翻译查重后第二步**:用户选「使用现有 key:<existing_key>」后,调用本工具读现有 key 的全语种翻译,
              逐项检查是否准确、是否缺漏,决定是否用 update_string 补全。
            示例：
            {"type":"read_string","module":"app","name":"hello_world"}
            {"type":"read_string","name":"login_title"}
        """.trimIndent(),

        "update_string" to """
            ## update_string 详细用法
            精准修改指定 key 的部分语言翻译（不动未列出的语言）。
            字段：
            - module（可选）：目标 Android 模块名。优先使用 `recommendedDefaultModule`,省略时用 currentModule.moduleName。
            - name（必填）：字符串 key，snake_case。
            - translations（必填）：键为语言目录名（values / values-zh-rCN / values-fr 等），值仅包含**需要修改**的翻译。
            关键规则：
            - **只动 translations 中列出的语言**，未列出的语言保持原样（这是与 insert_strings 的最大区别）。
            - 若 key 在某语言文件中不存在则在该文件中创建条目。
            - 适用场景：「只改 X 的繁体」「修正 Z 的某个语言翻译」「新增某语言翻译」。
            - 不适用场景：要全量覆盖某 key 的全部语言翻译时，请改用 insert_strings。
            - **CDATA / Data 包裹不用你管**:如果原翻译用了 `<![CDATA[...]]>` 或 `<Data>...</Data>`(带 HTML 标签的那种),
              你直接发翻译后的纯文本/HTML 即可,系统会**自动沿用原包裹**;不要自己手写 CDATA,反而可能让 Android 端
              把 `<` 当字面量渲染、lint 报 unescaped <。
            示例（仅改中文与法语，不动英语）：
            {"type":"update_string","module":"app","name":"hello_world","translations":{"values-zh-rCN":"你好世界","values-fr":"Bonjour le monde"}}
        """.trimIndent(),

        "delete_string" to """
            ## delete_string 详细用法
            删除指定 key 的翻译（破坏性操作）。
            字段：
            - module（可选）：目标 Android 模块名。优先使用 `recommendedDefaultModule`,省略时用 currentModule.moduleName。
            - name（必填）：字符串 key，snake_case。
            - languages（可选）：要删除的语言目录名列表（如 ["values-fr", "values-zh-rCN"]）。为空 / null / 省略时，删除该 key 在**所有语言**的翻译（整 key 被移除）；非空时，仅删除列表中指定语言的翻译，其他语言保持原样。
            安全约束：
            - **删除是破坏性操作**，操作前建议先 read_string 确认目标 key 与翻译。
            - 不确定范围时，先用 ask_user 与用户确认。
            - 适用场景：「删除 X 的法语翻译」「移除这个 key」「删掉 X 的繁体和日语」。
            示例（删除整 key）：
            {"type":"delete_string","module":"app","name":"hello_world"}
            示例（仅删除部分语言）：
            {"type":"delete_string","name":"hello_world","languages":["values-fr","values-ja"]}
        """.trimIndent(),

        "find_keys_by_text" to """
            ## find_keys_by_text 详细用法(2026.x 简化版)
            strings.xml 反查：通过翻译文本查找对应的 key。
            **固定全模块 + 全语言目录搜索**(2026.x 去掉 module / language 参数;原文查重的
            标准语义就是「跨模块 + 跨语言找重复」,让 AI 不能挑范围反而最贴近真实需求,避免漏命中)。
            字段：
            - text（必填）：要查找的翻译文本。
            - matchType（可选）：匹配模式，exact（完全相等）/ contains（子串，默认）/ regex（正则）。
            - caseSensitive（可选，默认 false）：是否区分大小写。
            - limit（可选）：最大返回条数，默认 30，最大 200。
            返回：每条结果形如 `{key, module, language, text, filePath}`。
            典型场景：
            - 看到一段文字想反查是哪个 key。
            - 排查重复翻译、跨语言确认某文本对应哪个 key。
            - **insert_strings 前必跑(查重入口)**:用**用户消息中的原始文本**(用户从布局/代码中选中的
              硬编码文本,或在消息中直接输入的待翻译文本)、matchType=exact 跑一次,
              再用 matchType=contains 跑一次兜底,把命中的 key 放进 ask_user 询问用户。
              ⚠️ 不要用 AI 自己翻译后的 values 译文(英语)查重 —— 用户选中的硬编码文本通常
              **就是目标语言翻译**(如中文「登录」),values/ 译文用词可能与原文不一致,会漏命中。
            示例：
            {"type":"find_keys_by_text","text":"登录"}
            {"type":"find_keys_by_text","text":"登录","matchType":"contains"}
            {"type":"find_keys_by_text","text":"^Hello.*$","matchType":"regex"}
        """.trimIndent(),

        "find_rows_by_text" to """
            ## find_rows_by_text 详细用法
            Google Sheets 反查：在表格中按文本搜索行。
            字段：
            - text（必填）：要查找的文本。
            - spreadsheetId（可选）：默认用上下文 googleSheets 配置。
            - sheetName（可选）：默认用 defaultSheetName。
            - column（可选）：限定列名（与表头精确匹配，忽略大小写）。例：values-zh-rTW。
            - matchType（可选）：匹配模式，exact / contains（默认）/ regex。
            - caseSensitive（可选，默认 false）。
            - limit（可选）：最大返回条数，默认 30，最大 200。
            返回：每条结果形如 `{rowNumber, sheetName, column, text, row}`。
            注意：sheetName 参数必须**原样**使用工作表名（包含点、空格等特殊字符，不要加单引号），系统会按 Google Sheets A1 规则自动处理转义。
            典型场景：
            - 「这个翻译对应表格里哪一行」「看哪个 key 有这个文本」「跨语言定位某文案」。
            示例：
            {"type":"find_rows_by_text","text":"登录","column":"values-zh-rCN"}
            {"type":"find_rows_by_text","text":"^Hello.*$","sheetName":"1.0.3.0","matchType":"regex"}
        """.trimIndent(),

        "get_editor_file" to """
            ## get_editor_file 详细用法
            获取当前 IDE 编辑器中打开的文件信息。不接收任何有效参数（dummy 字段保留，忽略）。
            返回字段：
            - filePath：完整绝对路径。
            - fileName：文件名带后缀（用于判断文件类型，如 MainActivity.kt / activity_main.xml）。
            - fileType：小写后缀（kt / java / xml / gradle / ...），无后缀时为空串。
            - language：按后缀归类（kotlin / java / xml / gradle / json / properties / markdown / text / other）。
            - lineCount：文件总行数。
            - selectedText：当前选中的文字（无选区时为空）。
            - selectionStartLine / selectionEndLine：选区起止行（0-based，无选区时为 -1）。
            典型场景：
            - 用户说「改一下我打开的这个文件」「解释我选中的代码」「我在看哪个文件」，AI 先调用本工具确认上下文。
            - AI 拿到 filePath 后可继续 read_file / search_in_files / find_references。
            示例：
            {"type":"get_editor_file","dummy":""}
        """.trimIndent(),

        "read_file" to """
            ## read_file 详细用法
            读取当前 IDE 项目内任意文件的内容。
            字段：
            - path（必填）：相对项目根的路径（如 "app/src/main/AndroidManifest.xml"），或项目内的绝对路径。
            - startLine（可选，默认 1）：起始行 **1-based（包含）**，与 IDE 行号一致。**不要再传 0**。
            - endLine（可选，默认 -1）：结束行 **1-based（包含）**，-1 表示到文件末尾。
            - maxLines（可选，默认 600）：单次返回最大行数（防止 token 爆炸），最大 2000。
            限制与约束：
            - 单文件 > 1.5MB（1_500_000 字节）会拒绝读取，请改用 search_in_files 检索指定内容。
            - 所有路径必须落在当前 IDE 打开的项目根目录内，绝对路径越界会被拒绝。
            - 返回时会带 `--- begin content ---` / `--- end content ---` 包裹，便于解析。
            - 内容被截断时返回里会提示「… 内容已截断，请用 startLine/endLine 分页继续读取」。
            **2026.x 行号口径变更**：行号 1-based；返回的 content **每行带 `N: ` 前缀**
            （数字宽度按 endLine 自动对齐），N 就是可以直接喂给 edit_file 的 `line` 的数字，
            不需要再 ±1 换算。这是 edit_file 2026.x 取消列参数后保持行号精度的关键。
            典型场景：
            - AI 拿到文件路径（从 get_editor_file / search_in_files / find_references / list_files 取得）后想看完整内容。
            - 大文件分页读：第 1 轮读 1-600，第 2 轮 startLine=601 读 601-1200。
            示例：
            {"type":"read_file","path":"app/src/main/AndroidManifest.xml"}
            {"type":"read_file","path":"app/src/main/java/com/foo/MainActivity.kt","startLine":1,"endLine":200,"maxLines":200}
            示例（拿到 content 后改第 17 行）：
            1) read_file 看到 `  17:     val x = 1`
            2) edit_file line=17, mode=replace_line, text="    val x = 2\n"
        """.trimIndent(),

        "edit_file" to """
            ## edit_file 详细用法
            **整行粒度**修改项目内任意文件（2026.x 起**取消列参数**，避免 AI 列号偏差导致写入错位）。
            字段：
            - path（必填）：文件路径（相对项目根或项目内绝对路径）。
            - line（必填）：1-based 行号。read_file 返回的 content 每行带 `N: ` 前缀，N 就是可直接传给 line 的数字。
            - mode（必填，三选一）：
              - **insert_before_line**：在 `line` 这一行**之前**插入 text，不替换任何原文。`line=1` 表示在文件最开头插入。
              - **insert_after_line**：在 `line` 这一行**之后**插入 text，不替换任何原文。`line=totalLines` 表示在文件最末尾追加。
              - **replace_line**：把 `[line, endLine]` 1-based 闭区间的整段文本用 text 替换；`endLine=-1` 或省略 = 单行替换（等价 `endLine=line`）；`endLine>line` = 多行替换。
            - text（必填）：要插入或替换的文本（支持多行，行间用 `\n`）。**不以 `\n` 结尾时，系统会自动补一个 `\n`** 保持行粒度（避免跟下一行粘连）。
            - endLine（replace_line 专用，1-based 结束行包含；-1 或省略 = 单行替换；其它模式忽略）。
            关键约束：
            - 原子写：写失败不会污染原文件（临时文件 + rename 模式）。
            - 限制：单文件 > 3MB（3_000_000 字节）拒绝编辑，需拆为多次小范围操作。
            - 路径必须落在项目根内。
            - line 越界 / replace_line 起止倒置 都会失败并返回具体错误信息。
            - 替换成功后 IDE 会自动重读该文件（如已打开），用户能立即看到变化。
            典型流程：
            1. AI 先 read_file 拿原文，**每行带 `N: ` 前缀**，N 就是 1-based 行号。
            2. 在目标行调用 insert_before_line / insert_after_line 插入；或划 `[line, endLine]` 调 replace_line 替换。
            3. 失败时根据错误信息调整 line（先 read_file 重新确认该行内容）或修正 endLine。
            示例（在第 50 行之前插入 import）：
            {"type":"edit_file","path":"app/src/main/java/Foo.kt","line":50,"mode":"insert_before_line","text":"import com.foo.Bar\n"}
            示例（在第 12 行之后追加内容）：
            {"type":"edit_file","path":"app/src/main/java/Foo.kt","line":12,"mode":"insert_after_line","text":"// 注释\n"}
            示例（把第 17-18 行整段替换为新两行）：
            {"type":"edit_file","path":"app/src/main/java/Foo.kt","line":17,"endLine":18,"mode":"replace_line","text":"    val x = 2\n    val y = 3\n"}
            示例（单行替换：把第 8 行整行换掉）：
            {"type":"edit_file","path":"app/src/main/java/Foo.kt","line":8,"mode":"replace_line","text":"    val x = 2\n"}
        """.trimIndent(),

        "create_file" to """
            ## create_file 详细用法
            在项目内创建新文件。
            字段：
            - path（必填）：文件路径（相对项目根或项目内绝对路径）。支持嵌套目录，自动 mkdirs。
            - content（必填）：文件内容。
            - overwrite（可选，默认 false）：目标文件已存在时是否覆盖。**默认不覆盖**，防误操作。修改已存在文件请改用 edit_file。
            限制：路径必须落在项目根内。
            典型场景：
            - 「新建一个 util 类」「生成 README」「新增空 strings.xml 骨架」。
            - **不适用**「修改已有文件的部分内容」——那种场景请用 edit_file；用 create_file + overwrite=true 整文件覆盖既慢又容易丢格式。
            示例：
            {"type":"create_file","path":"app/src/main/java/com/foo/Util.kt","content":"package com.foo\n\nclass Util {}\n"}
            {"type":"create_file","path":"docs/README.md","content":"# Notes\n","overwrite":false}
        """.trimIndent(),

        "search_in_files" to """
            ## search_in_files 详细用法
            在项目内文件中按文本 / 正则搜索。
            字段：
            - pattern（必填）：搜索文本（useRegex=false）或正则（useRegex=true）。
            - useRegex（可选，默认 false）：true 时按 Kotlin 正则。
            - caseSensitive（可选，默认 false）。
            - filePattern（可选）：glob 限定文件名（如 "*.kt"），仅支持 * 与 ? 通配符，不处理 **。
            - relativeDir（可选）：限定子目录（相对项目根），如 "app/src/main"；省略时搜索整个项目。
            - limit（可选）：最大返回条数，默认 100，最大 200。
            默认搜索文件类型：java / kt / xml / gradle / kts / json / properties / txt / md；不搜图片 / 二进制 / build / .git。
            返回：每条命中 `{filePath, lineNumber, columnNumber, matchedText}`（单行最长 200 字符，超长截断）。
            限制：单次最多返回 200 条命中；超出请收紧 pattern 或用 relativeDir 限定子目录。
            典型场景：
            - 「在项目里找一下 XXX 的用法」「看哪个文件调用了 getResult」「哪里用到了某个资源」。
            - 调用 edit_file 前先用本工具定位修改点（拿到 filePath + 行号）。
            示例（搜文本）：
            {"type":"search_in_files","pattern":"R.string.hello","limit":50}
            示例（正则+限定目录与后缀）：
            {"type":"search_in_files","pattern":"fun\\s+getResult","useRegex":true,"filePattern":"*.kt","relativeDir":"app/src/main","limit":30}
        """.trimIndent(),

        "find_references" to """
            ## find_references 详细用法
            按符号语义查找项目中的引用点（Java / Kotlin / XML 文件）。与 search_in_files 的区别：本工具理解「资源引用」的多种写法，自动适配 R.id.x / @+id/x / @id/x 等。
            字段：
            - symbol（必填）：要查找的符号名（资源名 / view id / key / 类名 / 标识符）。
            - kind（可选）：引用类型，枚举值：
              - id：匹配 R.id.xxx / @+id/xxx / @id/xxx（资源 id 引用）。
              - string：匹配 R.string.xxx / @string/xxx（字符串资源引用）。
              - layout：匹配 R.layout.xxx / @layout/xxx（布局引用）。
              - drawable：匹配 R.drawable.xxx / @drawable/xxx（图标引用）。
              - color：匹配 R.color.xxx / @color/xxx（颜色引用）。
              - class：按标识符边界匹配类名。
              - general（默认）：按标识符边界匹配任意符号名。
            - caseSensitive（可选，默认 false）。
            - limit（可选）：最大返回条数，默认 100，最大 200。
            典型场景：
            - 「这个 key / view id / 类名 在哪些地方被引用」「重构 X 前评估影响面」「检查旧 key 还在哪些文件被使用」。
            示例：
            {"type":"find_references","symbol":"hello_world","kind":"string"}
            {"type":"find_references","symbol":"submit_button","kind":"id","caseSensitive":true}
            {"type":"find_references","symbol":"MainActivity","kind":"class","limit":50}
        """.trimIndent(),

        "list_files" to """
            ## list_files 详细用法
            列举项目内某目录下的文件 / 子目录，支持 glob 与递归。
            字段：
            - relativeDir（可选，默认 "."）：相对项目根的子目录，"." 或空表示项目根。
            - pattern（可选，默认 "*"）：glob 模式，支持 * 与 ? 通配符，不处理 **。
            - recursive（可选，默认 false）：是否递归子目录（最多 10 层，防爆栈）。
            - includeDirs（可选，默认 false）：是否在结果中包含目录。
            - maxEntries（可选，默认 500）：最大返回条数。
            限制：
            - 大目录请用 filePattern 限定（如 "*.kt"）避免返回过多结果。
            - 路径必须落在项目根内。
            典型场景：
            - 「项目里有哪些 layout 文件」「app/src/main/java 下都有什么包」「res 目录里有哪些 values-* 」。
            - AI 探索项目结构时先用本工具建索引，再用 search_in_files / read_file 精确读目标。
            示例：
            {"type":"list_files","relativeDir":"app/src/main/res","pattern":"*.xml","recursive":false}
            {"type":"list_files","relativeDir":"app/src/main/java","pattern":"*.kt","recursive":true,"includeDirs":true,"maxEntries":300}
        """.trimIndent(),

        // ============== 写代码工作流(2026.x 新增:合并文档入口) ==============
        //
        // 11 个文件操作工具(get_editor_file / read_file / read_files / edit_file /
        // create_file / delete_file / move_file / search_in_files / find_references /
        // list_files / file_info)逐个 load_tool_doc 要 11 次 round-trip。
        //
        // 现提供 `code_ops` 合并入口,一次拿到全部 11 个工具的精简说明 + 写代码工作流。
        // 写代码前**优先**调一次 `load_tool_doc("code_ops")`,然后基于合并文档按需调用具体工具。
        // 简单对话场景不需要写代码 → 不必调这个,避免浪费 token。
        "code_ops" to """
            ## 写代码工具集(2026.x 合并文档,一次 load 拿全部 11 个工具用法)
            适用于:用户说"写一个 X 类 / 改一下 Y 文件 / 重构 Z / 加个新功能"等需要写代码的场景。
            拿到本合并文档后,直接调具体工具即可,**不必**再逐个 load_tool_doc 单工具。

            ### 11 个工具一览(按使用频率排序)
            1. **list_files**(列举):探索项目结构。"app/src/main 下有哪些文件"。
            2. **search_in_files**(搜文本):按文本/正则搜。"哪里调了 X 函数"。
            3. **find_references**(找引用):按符号语义找引用点。"R.string.hello 在哪些地方用到"。
            4. **read_file**(读单文件):读全文或分页。"看 MainActivity.kt 完整代码"。
            5. **read_files**(批量读):一次读 2-10 个文件,省 round-trip。"同时看 MainActivity + Adapter + Item"。
            6. **file_info**(查元信息):读大小/行数/mtime,不读全文。"这文件多大、有多少行"。
            7. **get_editor_file**(看当前打开):"我打开的哪个文件、选中了什么"。
            8. **edit_file**(改文件):**整行粒度**修改(2026.x 取消列参数,避免 AI 列号偏差错位)。"在第 50 行之前插入 import"、"把第 8 行整行替换"、"把第 17-18 行整段替换"。line 直接读自 read_file 返回的 `N: ` 前缀。
            9. **create_file**(创建新文件):"新建 Util.kt"。
            10. **delete_file**(删除):删文件或空目录。"删掉 Test.kt"。
            11. **move_file**(移动/重命名):"把 Util.kt 改名为 Helper.kt"。

            ### 写代码标准工作流(4 步,按顺序)
            **第 1 步:规划** —— 任务说"加一个 X 功能",先在脑子里拆成 2-5 个动作(读 X / 改 Y / 改 Z)。
            **第 2 步:定位** —— 用 list_files / search_in_files / find_references 找到目标文件。
              - 不知道有哪些文件 → list_files 列目录
              - 知道文件名想看内容 → read_file(path) 或 read_files([path1, path2, ...])
              - 知道符号名想找引用 → find_references(symbol="X", kind="class")
              - 知道一段文本想找在哪 → search_in_files(pattern="...")
            **第 3 步:阅读** —— read_file / read_files 读全文,理解上下文。
              - 一次读不完整(>600 行)→ 分页:read_file(path, startLine=601, endLine=1200)
              - 想一次读多个相关文件 → read_files([...])
              - 只关心文件存在/大小 → file_info(几行就够)
              - **返回 content 每行带 `N: ` 前缀**(1-based),N 就是直接喂给 edit_file 的 line
            **第 4 步:改写** —— edit_file 整行粒度修改,create_file 新建,delete_file 删,move_file 改名。
              - edit_file 的 line 1-based,read_file 返回的 `N: ` 前缀就是可直接传的行号,**不要** ±1 换算
              - **无列参数**(2026.x 取消):AI 给的列号常因 tab/全角空格等偏差 1~N 列导致写入错乱,整行粒度天然对列不敏感
              - 改完后**不要**再 read_file 验证(浪费 round-trip),直接进入下一个动作

            ### 关键约束
            - **路径**:相对项目根(例 "app/src/main/java/Foo.kt")或项目内绝对路径,必须落在项目根内(越界会被拒)。
            - **文件大小**:read_file / read_files 单文件 > 1.5MB 拒绝;edit_file > 3MB 拒绝。
            - **行号越界**:edit_file 的 line 越界 / replace_line 模式下 endLine < line 或 endLine 越界 都会失败,需调整。
            - **IDE 缓存自动刷新**(2026.x 修复):edit_file / create_file / delete_file / move_file
              执行后系统自动让 IDE 编辑器/PSI/Daemon Code Analyzer 重读,**不要**再调
              read_file "验证" —— 写盘已原子,IDE 已重读,直接进入下一动作。
            - **写操作原子**:所有写操作走「临时文件 + rename」模式,中途失败不会污染原文件。
            - **不搜的图片/二进制/build/.git**:search_in_files / find_references 只搜
              java/kt/xml/gradle/kts/json/properties/txt/md,自动跳过其它类型。

            ### 与 strings.xml / Sheets 工具的协作
            - 写 Kotlin/Java 代码时如果改动了 strings.xml key,先 edit_file 改代码,再用
              update_string 改 strings.xml(顺序无关,系统串行执行)。
            - 如果代码里的硬编码字符串应该提取为 strings.xml,先 insert_strings 走翻译查重,
              再 edit_file 把硬编码文本替换为 R.string.x(等价于 replace_selection,但作用于任意文件)。

            ### 反例(常见错误)
            - ❌ 调了 edit_file 立刻 read_file 验证 → 浪费 round-trip,IDE 已自动重读
            - ❌ 一次性读 5 个大文件用 read_file 循环 → 改用 read_files 一次合并
            - ❌ 用 list_files 找代码 → 改用 search_in_files 找内容更精准
            - ❌ 多次连续 load_tool_doc 单工具 → 调一次 load_tool_doc("code_ops") 拿全部
        """.trimIndent(),

        "read_files" to """
            ## read_files 详细用法
            批量读 2-10 个文件,一次工具调用合并返回,节省 N-1 次 round-trip。
            适用:同时看几个相关文件(比如接口 + 实现 + 调用方;Activity + Adapter + Item)。
            字段:
            - paths(必填):文件路径列表,2-10 个,相对项目根或项目内绝对路径。
            - maxLines(可选,默认 300,最大 300):每个文件单次最大返回行数(比 read_file
              默认 600 略低,防止 N 个文件合并爆 token)。
            限制(同 read_file):
            - 单文件 > 1.5MB 该文件会返回 [失败],其它文件继续。
            - 路径越界/不存在/二进制该文件会返回 [失败],其它文件继续。
            返回:每个文件独立显示 [文件 N] + 路径 + 行范围(1-based)+ 字节数 + begin/end content 块。
            **2026.x 行为变更**:每行带 `N: ` 前缀(1-based,与 read_file 一致),N 可直接喂给 edit_file.line。
            与 read_file 的对比:
            - read_file 一次只读 1 个,无路径框架共享(每文件 4 行框架),N 个文件要 N 次 round-trip。
            - read_files 一次读 N 个,框架合并,token 更省,延迟更低(尤其 N 较大时)。
            典型场景:
            - "看一下 A.kt B.kt C.kt 三个文件" → read_files(["a.kt","b.kt","c.kt"])
            - 大文件分页 → read_file(startLine=X,endLine=Y)(不要用 read_files)
            示例:
            {"type":"read_files","paths":["app/src/main/AndroidManifest.xml","app/build.gradle.kts"]}
            {"type":"read_files","paths":["a.kt","b.kt"],"maxLines":200}
        """.trimIndent(),

        "delete_file" to """
            ## delete_file 详细用法
            删除文件或**空**目录(破坏性)。**非空目录会被拒**(必须先 list_files 看子项并逐个删)。
            字段:
            - path(必填):文件或空目录路径,相对项目根或项目内绝对路径。
            行为:
            - 是文件 → 直接删除
            - 是空目录 → 直接删除
            - 是非空目录 → 拒绝(返回错误信息,需 AI 自行递归删子项)
            - 是符号链接 → 不会跟随符号链接(防越界误删)
            成功后 IDE 行为:
            - 若该文件在编辑器中打开 → 自动关闭 tab
            - Project 面板文件树刷新
            - PSI 缓存失效
            - Daemon Code Analyzer 重跑(高亮/补全/错误检查)
            典型场景:
            - "删掉 Test.kt" → delete_file("app/src/main/java/.../Test.kt")
            - "删掉整个 util 目录" → 先 list_files 看子项,再逐个 delete_file(目录会在
              最后一项删除时自动清空)
            反例:
            - ❌ 删整个目录时用 delete_file 一次性传 → 改为递归 list_files + 多次 delete_file
            - ❌ 不确定文件在哪 → 先 search_in_files / find_references 定位
            示例:
            {"type":"delete_file","path":"app/src/main/java/com/foo/Test.kt"}
        """.trimIndent(),

        "move_file" to """
            ## move_file 详细用法
            移动或重命名文件 / 目录(等价于 `mv`)。
            字段:
            - src(必填):源路径(相对项目根或项目内绝对路径)。
            - dst(必填):目标路径(相对项目根或项目内绝对路径)。
            行为:
            - dst 已存在 → 拒绝(防误覆盖,需 AI 先 delete 再 move,或选别的目标名)
            - 自动创建 dst 父目录(mkdirs)
            - 跨目录移动也支持
            - 原子移动(StandardCopyOption.ATOMIC_MOVE),中途失败不会留半写状态
            成功后 IDE 行为:
            - 若 src 在编辑器中打开 → 自动关闭旧 tab
            - Project 面板文件树刷新(src 消失,dst 出现)
            - Daemon Code Analyzer 重跑(若 dst 是代码文件)
            典型场景:
            - 重命名 → move_file("Foo.kt", "Bar.kt")
            - 移动到别的目录 → move_file("app/old/X.kt", "app/new/X.kt")
            反例:
            - ❌ dst 已存在还硬调 → 先 delete 或选新名字
            - ❌ 想批量移动多个文件 → 调多次 move_file(原子,安全)
            示例:
            {"type":"move_file","src":"app/src/main/java/Foo.kt","dst":"app/src/main/java/Bar.kt"}
            {"type":"move_file","src":"old/utils","dst":"new/utils"}
        """.trimIndent(),

        "file_info" to """
            ## file_info 详细用法
            读取文件 / 目录的元信息,**不读全文**。比 read_file 节省大量 token。
            字段:
            - path(必填):路径(相对项目根或项目内绝对路径)。
            返回字段:
            - exists: 是否存在(越界/不存在 → false,其它字段全为 0)
            - sizeBytes: 文件字节数(目录为 0)
            - lineCount: 文件行数(目录为 0)
            - isDirectory / isRegularFile: 类型
            - lastModified: 最后修改时间(yyyy-MM-dd HH:mm:ss 本地时区)
            - fileName: 文件名带后缀
            典型场景:
            - "X 文件存在吗" → file_info("path/X.kt")
            - "X 文件多大" → file_info(读 sizeBytes)
            - "X 文件有多少行" → file_info(读 lineCount)
            - 编辑前看 mtime 决定是否要 read_file 重读
            反例:
            - ❌ 想知道文件内容 → 改用 read_file(本工具不给内容)
            - ❌ 想列目录下所有文件 → 改用 list_files
            示例:
            {"type":"file_info","path":"app/src/main/java/com/foo/MainActivity.kt"}
        """.trimIndent(),

        "ask_user" to """
            ## ask_user 详细用法
            向用户提问并等待用户回复。每次调用都会暂停 tool loop 直到用户响应——因此不要反复调用本工具。
            字段：
            - question（必填）：问题文本，会直接展示给用户。建议在问题里说明上下文与各选项的含义，避免歧义。
            - options（可选）：按钮选项，非空时显示为可点击按钮（推荐优先使用，用户一键回复更高效）；为空 / null 时用户会在聊天输入框输入回复，系统会作为 tool_result 回传。
            使用规则：
            - 关键参数缺失（如不确定目标 key 写法）、风险操作确认（如破坏性 delete、跨模块写入）、目标不明确需要澄清时使用。
            - 收到用户回复后必须用 task_complete 或其他操作推进目标，不能再连续调用本工具。
            - 一次性把要确认的多个问题合并到一次 ask_user，不要拆成多轮。
            - **翻译查重专用格式(仅 AI 内部约定)**:检测到 insert_strings 与现有 key 重复时,
              options 应使用以下统一格式以便于你后续按选项文本判断用户决策。
              **系统不再自动拦截**「使用现有 key」选项 — 用户点选后选项文本直接回传给你,
              由你根据选项文本决定后续动作:
              - 选「使用现有 key:<existing_key>」:⚠️ **若插入来自布局/代码选区,必须先调用 replace_selection(oldText=<要替换的子串>, newText=<替换成的目标文本>)**(整段选区替换:oldText=选区里硬编码,newText = `"@string/<existing_key>"`(XML)或 `"R.string.<existing_key>"`(其它);精准子串:oldText=选区里要换的那段,newText=对 key 的引用;选区内多次出现会**全部**替换),
                然后调用 read_string 校验现有翻译,再决定是否 update_string / task_complete。
                **不要**在选「使用现有 key」后跳过 replace_selection 直接调 read_string。
              - 选「插入新 key」:重新生成不冲突的 key 后调用 insert_strings(driver 会自动替换硬编码文本)。
              - 选「取消操作」:直接 task_complete 结束。
              - `使用现有 key:<existing_key>` — key 名只允许 `[A-Za-z_][A-Za-z0-9_]*`,允许在结尾追加说明
              - `插入新 key`
              - `取消操作`
            示例（带按钮）：
            {"type":"ask_user","question":"key 'hello' 已存在,如何处理?","options":["覆盖现有翻译","在末尾追加同名行","取消操作"]}
            示例（翻译查重）:
            {"type":"ask_user","question":"检测到现有 key 'hello_world' 在 app 模块与待插入译文一致,你想:","options":["使用现有 key:hello_world","插入新 key","取消操作"]}
            示例（开放输入）：
            {"type":"ask_user","question":"你想修改哪个模块的 strings.xml?(app / home / common)","options":[]}
        """.trimIndent(),

        "replace_selection" to """
            ## replace_selection 详细用法
            把当前 IDE 编辑器选中的文本里**所有**匹配 `oldText` 的子串替换为 `newText`(整段选区内全部出现都换,精确字面匹配,非正则)。
            字段：
            - oldText（必填）：选区内要被替换的子串(精确字面,非正则)。空串会直接拒绝(防无限循环)。
            - newText（必填）：把 oldText 替换成的目标文本。原样写入,不做任何智能判断 / 格式转换。
            行为：
            - 选区里 oldText 出现 0 次 → 失败,返回选区前 60 字符预览;
            - 选区里 oldText 出现 N 次(N≥1) → **全部**替换为 newText(语义等同于 Kotlin `String.replace(CharSequence, CharSequence)`);
            - 在 EDT 上 WriteCommandAction 中执行;**执行后聊天视图保持打开**,AI 继续调用 read_string / ask_user / update_string 推进翻译查重的后续流程。
            ⚠️ **强约束:这是用户选「使用现有 key:<existing_key>」后必须执行的第一步** ⚠️
            ——若本次插入来自布局/代码选区,AI **必须**先调用本工具,再调用 read_string 校验现有翻译。**绝不可**跳过本工具直接调 read_string。
            适用场景：
            - **翻译查重 +「使用现有 key」**(整段选区替换):`oldText` = 选区里的硬编码(出现 1 次),`newText` = `"@string/<key>"`(XML)或 `"R.string.<key>"`(其它);按上下文 `editorSelection.file` 判断文件类型。
            - **精准子串替换**(2026.x 典型需求):用户选了 `android:text="反馈内容: <font>请填写</font>"` 整段、只想把"反馈内容"换成对 key 的引用 → `oldText="反馈内容"`,`newText="@string/feedback_title"`;替换后整段变成 `android:text="@string/feedback_title: <font>请填写</font>"`,标签和冒号原样保留。
            - **选区内 oldText 多次出现**(如选了 `反馈内容 反馈内容 反馈内容`)→ 全部替换,等价于 String.replace 语义,不会漏。
            限制：仅在 chat 入口(Extract / AskAi 弹框)有效;主面板聊天视图无编辑器上下文,会返回失败信息。
            示例：
            // 整段选区替换(XML 布局):选区文本="反馈内容",oldText 出现 1 次
            {"type":"replace_selection","oldText":"反馈内容","newText":"@string/feedback_title"}
            // 整段选区替换(Kotlin/Java)
            {"type":"replace_selection","oldText":"反馈内容","newText":"R.string.feedback_title"}
            // 精准子串:选区="android:text=\"反馈内容: <font>请填写</font>\"",只把"反馈内容"换成引用
            {"type":"replace_selection","oldText":"反馈内容","newText":"@string/feedback_title"}
        """.trimIndent(),

        "task_complete" to """
            ## task_complete 详细用法
            声明任务已完成，结束当前对话循环。**这是唯一的合法终止信号**——没有调用本工具 = 你仍在执行，系统会持续驱动你继续。
            字段：
            - summary（必填）：给用户看的最终总结，会直接展示。建议用一两句话说明完成了什么、覆盖了哪些 key / 文件 / 语言。
            - status（必填）：任务完成状态，枚举值：
              - success：完全达成。
              - partial：部分达成（如用户中途拒绝、范围受限）。
              - failed：执行失败（如权限不足、AI 异常）。
            - notes（可选）：补充说明（如「用户拒绝」「缺少必要信息」），会附在 summary 后展示。
            使用规则：
            - 必须等到用户目标真正达成才调用——拿了工具结果但还没做事不算完成。
            - 调用本工具后不要在同一次回复中再调用其他工具。
            示例：
            {"type":"task_complete","summary":"已在 app 模块的 strings.xml 中插入 hello_world 的 3 个语种翻译。","status":"success"}
            {"type":"task_complete","summary":"已修正 hello_world 的法语翻译,中文与日语用户拒绝修改,跳过。","status":"partial","notes":"用户拒绝修改 values-zh-rCN 与 values-ja"}
            {"type":"task_complete","summary":"Google Sheets 未配置,无法执行。","status":"failed","notes":"请先在设置中配置 spreadsheetId 与 defaultSheetName"}
        """.trimIndent(),

        "run_shell" to """
            ## run_shell 详细用法
            在**项目根目录**执行一条 shell 命令,把 stdout/stderr **逐行流式**回灌到聊天 UI(用户能实时看到输出)。
            字段：
            - command（必填）：单个可执行文件名(git / gradle / ls / cat / node / npm / adb / 自定义脚本…)。**不要**把参数塞到 command 里。
            - args（数组,可选）：参数列表,逐项传给平台层(Windows `CreateProcessW` / POSIX `execvp`),
              **不**走 shell 解析 — 即使 args 含空格、管道符、引号也不会被误解析,从根本上避免注入。
            - cwd（可选,字符串）：相对项目根的子目录(如 "app"),null/空 = 项目根。越界(用 .. 跳出项目根)会被系统直接拒绝。
            - timeoutMs（可选,整数）：超时毫秒,默认 60000,范围 1000..600000。超时后进程被强制 kill。

            平台差异:
            - Windows:command + args 全部作为 cmd.exe 的独立参数传入(`cmd.exe /c "git" "log" "--oneline" "-10"`),
              由 `GeneralCommandLine.withParameters` 按 Windows `CommandLineToArgvW` 规则自动加引号。
              支持调用 .bat、命令内置(dir / cd / echo)、系统 PATH 下的可执行文件。
            - POSIX:`[command, arg1, arg2]` 直传 fork+exec,不走 /bin/sh。
              如果 AI 需要 shell 特性(管道/重定向/通配符),**自己**在 command 里写 `/bin/sh`(并把整段脚本放进 args 数组的单元素里)。
              例: `{"command":"/bin/sh","args":["-c","ls -la | grep foo"]}`。

            何时用:
            - read_file / search_in_files 不够用时(查 git 状态、看 gradle 任务列表、抓 adb logcat、跑自定义脚本…)。
            - 配合 replace_selection 完成后跑一次 `./gradlew assembleDebug` 看是否通过。
            - 写 strings.xml 类的操作**不要**用 run_shell(有专用 insert_strings 工具),用专用工具更安全(模块一致性兜底)。

            何时不用 / 应当先问:
            - 删除/重置/推送/部署/清理缓存等破坏性操作(`rm`/`git push`/`gradle clean`/`adb uninstall`/`shutdown`…):
              **先用 `ask_user`** 描述意图(将做什么 / 目标 / 影响范围),等用户回复再执行。
            - 不确定命令作用时先用 `ask_user` 或用 `read_file`/`list_files` 探查。
            - 已经写好的 strings.xml 编辑 / 文件编辑 / 翻译查重 → 用专用工具。

            返回 tool_result 格式(以 [工具执行结果] 起头):
            - 成功:`[工具执行结果] 类型:run_shell 状态:成功 命令:<command> <args> 工作目录:<abs path> 输出:\n<stdout+stderr>`
            - 失败:`... 状态:失败(exit=<n>) ...`
            - 超时:`... 状态:超时(<ms>ms) ...`(进程已 kill)
            - 用户取消:本工具**不会**主动弹窗,如果你想拦截应自己在调用前 ask_user;
              若用户在 tool loop 中按了 IDE 停止按钮,会收到 `状态:已取消 信息:用户停止`。

            示例:
            {"type":"run_shell","command":"git","args":["status","--short"]}
            {"type":"run_shell","command":"gradle","args":["-p","app","tasks","--all"],"timeoutMs":120000}
            {"type":"run_shell","command":"node","args":["scripts/check-i18n.js"],"cwd":"tools"}
        """.trimIndent(),

        "read_diagnostics" to """
            ## read_diagnostics 详细用法
            读取**当前在 IDE 编辑器中打开**的所有文件的"编辑器级"诊断
            (Java / Kotlin 编译错、未解析引用、import 缺失、inspection 警告等),
            转成扁平 JSON 列表返回。配合 `read_file` / `edit_file` 形成"读诊断 → 改代码 → 再读诊断"小闭环。
            字段:
            - minSeverity(可选):过滤级别。WEAK_WARNING(默认,收齐 3 种级别)/ WARNING / ERROR。
              想要只关注 ERROR 时显式传,否则一次性拿全再由 AI 自行筛选。

            范围与限制(必须读):
            1. **只覆盖打开的文件**:`FileEditorManager.getOpenFiles()` 决定范围。未打开的文件
               daemon 不会跑,结果一定是 0 条 — **不要**据此断言"项目没问题"。
               想知道未打开文件有没有错 → `run_shell gradlew compileDebugKotlin`。
            2. **读到的是 daemon 缓存,不是实时**:文件刚被 `edit_file` 写过,
               daemon 异步重跑(通常 100-500ms,大文件或 dirty 队列长时可达 1-2s)。
               写完文件立刻调本工具**可能拿到旧结果**。建议先调一个轻量工具让一拍过去
               (read_file 读一行 / current_time / ask_user 问下一步),或显式 sleep。
            3. **不包含 build-time 错误**:kapt 报错、Android 资源 ID 找不到、lint 警告、
               K2 FIR 编译器内部错误都不在范围 — 这些用 `run_shell` 跑构建命令。
            4. **Java + Kotlin 一起**:`processHighlights` 走 `TextEditorHighlightingPass`
               全集,Kotlin 的 `KotlinHighlightingPass` 也是同一机制,不需要分别调。
            5. **dumb mode 处理**:项目刚 sync 完、indexing 中,daemon 暂停,tool_result
               末尾的 `note` 字段会写"dumb mode 或 daemon 未完成" — 这种情况**不要**断言
               "代码无错",可以补跑一次构建确认。

            返回 tool_result 格式(以 [工具执行结果] 起头,主体是 JSON):
            ```
            [工具执行结果] 类型:read_diagnostics 状态:成功 项目根:<path>
            {
              "diagnosticCount": 3,
              "summary": { "ERROR": 1, "WARNING": 2, "WEAK_WARNING": 0 },
              "openFileCount": 4,
              "files": [
                {
                  "path": "app/src/main/java/com/example/Hello.kt",
                  "errors": [
                    { "line": 12, "column": 5, "severity": "ERROR",
                      "message": "Unresolved reference: foo", "symbol": "foo" }
                  ]
                }
              ],
              "note": "(可选)dumb mode 或 daemon 未完成,结果可能滞后"
            }
            ```
            - `line` / `column` 0-based。
            - `symbol` 在 unresolved-ref 错误时是编译期看到的标识符文本(可能是 FQN 片段),
              其它错误退化为 highlighter 范围内的源码片段(取前 120 字符)。
            - `diagnosticCount = 0` 不代表"代码无错",可能是 dumb mode / 未打开文件
              / 还没人触发过 daemon。

            示例:
            {"type":"read_diagnostics"}
            {"type":"read_diagnostics","minSeverity":"ERROR"}
        """.trimIndent(),

        "fetch_url" to """
            ## fetch_url 详细用法
            HTTP GET 一个 URL,返回响应体(只读,GET only)。**不**对应到 `run_shell curl`,
            走平台自带 `java.net.http.HttpClient`,无 shell 解析、无外部依赖、token 友好。
            字段:
            - url(必填):http/https URL。**只允许 http/https**;`file://` / `ftp://` / `data:`
              会被拒绝(防止读本地任意文件)。**不做** host 黑名单 — 任何 host 都能访问,
              含 localhost / 内网 / 公网。
            - headers(可选):自定义请求头,键值对都是字符串。能覆盖 User-Agent(默认
              `InsertStringsPlugin/<version>`)。
            - timeoutMs(可选):超时毫秒,默认 10000,范围 1000..120000(connect + read 总超时)。
            - maxBodyChars(可选):响应体最大字符数,默认 100000(100KB),范围 1..2_000_000(2MB)。
              超过截断并标注原长度,防 OOM + token 爆。
            - responseType(可选):`text`(默认)/ `json`。`json` 时尝试 pretty-print,
              失败回退 raw text。
            - stripHtml(可选):默认 false。**仅** Content-Type 是 `text/html` 或
              `application/xhtml+xml` 时生效 — 移除 script/style/noscript 块、HTML 注释、
              on* 事件属性、所有标签,只留可见文本(token 友好)。其它 Content-Type 忽略此参数。
            - stripImages(可选):默认 false。`stripHtml=true` 时把 `<img>` 标签也移除
              (默认保留 alt 文字作为占位)。

            平台保证:
            - **不做** host 黑名单 / SSRF 防御 — 任何 host 都能访问。**敏感操作**(读内网服务、
              抓私有数据)前先用 `ask_user` 描述意图。
            - 跟随重定向 1 次(`HttpClient.Redirect.NORMAL`);不会无限跟随(防 DNS rebinding
              跳到内网)。
            - 不带 Cookie,不带任何自动鉴权 — 纯无状态 GET。

            何时用:
            - 用户给了一个文档 URL(README / changelog / 官方文档)→ 用本工具读。
            - 调外部 REST API 查数据(GitHub API / npm registry / maven 仓库元信息)→ 用本工具。
            - AI 需要"看一眼"某个网页的实际内容,比如 Stack Overflow 答案 / issue 内容。
            - 配合 `read_diagnostics` 看到外部 API 错(404 / 500),用本工具读具体错误消息。
            - 配合 `run_shell gradle` 看到依赖解析问题,用本工具看 maven 仓库里有没有该版本。

            何时不用:
            - 需要 POST / PUT / DELETE → 用 `run_shell curl`。
            - 需要 Cookie / 会话状态 / 鉴权头 → 用 `run_shell curl`(可以管理 cookie jar)。
            - 已知内容很长(> 100KB),且不打算用 stripHtml → 先开 `stripHtml=true` 或调
              `maxBodyChars` 调小,避免 token 爆。

            返回 tool_result 格式(以 [工具执行结果] 起头):
            成功:
            ```
            [工具执行结果] 类型:fetch_url 状态:成功 url:<url>
            状态码:200 Content-Type:text/html 耗时:340ms 大小:5.6KB
            [stripHtml=true] 已移除 script/style/noscript 块、HTML 注释、on* 事件属性、所有标签
            ---BEGIN BODY---
            <响应体或纯文本,最多 maxBodyChars 字符>
            ---END BODY---
            ```
            失败:
            ```
            [工具执行结果] 类型:fetch_url 状态:失败 url:<url> 原因:<错误描述>
            ```
            - 失败原因可能是:URL 解析失败 / 协议非 http/https / 超时 / 状态码 4xx/5xx /
              网络异常。
            - 状态码 4xx/5xx 仍会附带 body(按 maxBodyChars 截断),便于 AI 读错误消息。

            示例:
            {"type":"fetch_url","url":"https://api.github.com/repos/jetbrains/kotlin","responseType":"json"}
            {"type":"fetch_url","url":"https://example.com/article","stripHtml":true}
            {"type":"fetch_url","url":"https://api.example.com/v1/data","headers":{"Authorization":"Bearer xxx"}}
        """.trimIndent(),

        "load_tool_doc" to """
            ## load_tool_doc 详细用法
            按需加载工具的详细使用文档（参数约束、枚举值、示例等）。主聊天 system prompt 只放工具清单，详细用法全部按需加载以节省 token。
            字段：
            - tool（必填）：要加载的工具名。当前可用值：
              - insert_strings、query_keys、read_string、update_string、delete_string、find_keys_by_text
              - sheets_basic、sheets_row_ops、sheets_column_ops、sheets_freeze、sheets_review、sheets_color、sheets_batch_modify
              - find_rows_by_text
              - **code_ops**(2026.x 写代码工作流合并文档,推荐)**:** 一次拿到全部 11 个文件操作工具的用法
              - get_editor_file、read_file、read_files、edit_file、create_file、delete_file、move_file
              - search_in_files、find_references、list_files、file_info
              - todo_list、todo_add、todo_update、todo_delete
              - ask_user、load_tool_doc、replace_selection、run_shell、read_diagnostics、fetch_url、task_complete
            使用规则：
            - 写代码相关任务(用户说"改 X 文件""加 Y 功能""重构 Z")→ **优先** `load_tool_doc("code_ops")`
              一次拿到全部 11 个工具用法,避免反复调 11 次单工具文档,省 10+ round-trip + token。
            - 不确定工具字段时再调本工具，不要在每次任务开始时一次性加载所有文档。
            - 系统对「连续调用 load_tool_doc」有次数上限（防止 AI 反复加载文档而不执行操作），所以一次最多加载需要的 1-2 个文档，拿到后立即返回实际工具调用。
            - 文档返回后会作为 tool 消息回传给你，你据此直接返回正确的工具调用，**不要重复请求同一工具的文档**。
            示例：
            {"type":"load_tool_doc","tool":"code_ops"}
            {"type":"load_tool_doc","tool":"sheets_batch_modify"}
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
        """.trimIndent(),

        // ============== 代办(2026.x 新增) ==============
        "todo_list" to """
            ## todo_list 详细用法
            读取代办列表,返回每条的 id / title / content / priority / isCompleted / createdAt / completedAt / reminder。
            字段:
            - filter(可选,默认 "active"):过滤模式,枚举 "active" / "completed" / "all"。
            - limit(可选,默认 50):最大返回条数;不传或 null 时取 50。
            返回:JSON 数组,每条形如:
            ```
            {
              "id":"uuid",
              "title":"...",
              "content":"...",
              "priority":"NORMAL",
              "isCompleted":false,
              "createdAt":1700000000000,
              "completedAt":null,
              "reminder": null
              // 或有提醒时:
              // "reminder": {
              //   "enabled": true,
              //   "nextTriggerAt": 1700003600000,    // 下一次触发的绝对时间戳(毫秒)
              //   "recurrence": "DAILY",              // NONE/DAILY/CUSTOM(无 WEEKDAYS/WEEKLY)
              //   "timeOfDay": {"hour":15,"minute":0},// 一天中的固定时分(循环提醒用)
              //   "recurrenceDays": []                 // 1-7 表示周一到周日,仅 CUSTOM 生效
              // }
            }
            ```
            典型场景:
            - 用户说「我有什么代办」/「看看我之前记了什么」→ filter=active 或 all。
            - 用户说「我都完成了什么」→ filter=completed。
            - 用户问「X 是不是已经有提醒了?几点提醒?」→ filter=active 拿到 reminder 字段。
            - 想「勾选完成」前先 todo_list 拿 id,再用 todo_update(id=..., isCompleted=true)。
            注意:
            - 系统已经在每轮 chat 上下文中注入了 active 代办的简要摘要(便于你主动提醒用户),
              本工具用于拿到完整字段(content / completedAt / reminder 等)。
            - limit 默认 50,绝大多数场景够用;不要为了「看全部」而传很大的 limit,会爆 token。
            示例:
            {"type":"todo_list"}
            {"type":"todo_list","filter":"all","limit":100}
        """.trimIndent(),

        "todo_add" to """
            ## todo_add 详细用法
            新增一条代办;写入后用户主页 Todo tab 立即可见。如果带了提醒字段,
            新增后 scheduler 会立即把这条代办放进 Timer 队列,到点触发右下角弹框。
            字段:
            - title(必填):代办的标题(trim 后非空);空字符串会被拒绝并返回错误 tool_result。
            - content(可选):详细描述(多行,允许为空)。
            - priority(可选,默认 NORMAL):优先级,枚举 "LOW" / "NORMAL" / "HIGH" / "URGENT";
              未知值 / 大小写不敏感时回退 NORMAL。
            - reminderTime(可选,默认 null):首次提醒时间(Unix 毫秒时间戳)。
              省略 = 不设提醒;**与 reminderDate 互斥**(传了 reminderDate 时本字段被忽略)。
              用户说「5 分钟后提醒我喝水」→ 先 current_time 拿 timestamp,再传 timestamp + 5*60*1000。
              用户说「明天下午 3 点」→ 用本地时区算次日 15:00 的时间戳再传(系统按本地时区解析)。
            - reminderDate(可选,默认 null):**指定日期提醒**(YYYY-MM-DD 字符串,本地日期)。
              **仅一次性提醒生效**(recurrence=NONE);循环类型下传了也会被忽略。
              配合 reminderTimeOfDay 一起用,系统按本地时区组装 timestamp,
              **AI 不用算时区、跨日、跨年** —— 这是用户表达「X 月 X 日几点」的推荐方式。
              用户说「3 月 15 日上午 10 点」→ reminderDate="2026-03-15", reminderTimeOfDay="10:00"。
              用户说「下周一提醒我交周报」→ 算下周一日期(假设 2026-06-29),
              reminderDate="2026-06-29", reminderTimeOfDay="09:00"。
            - reminderTimeOfDay(可选,默认 "09:00"):时分(HH:MM 24h 字符串,
              如 "09:00" / "15:30" / "23:45")。**两种用法**:
                (1) 与 reminderDate 配套 → 一次性「指定日期 + 时分」提醒(reminderDate 缺省 09:00);
                (2) 与 recurrence + recurrenceDays 配套 → 「循环 + 时分」提醒,系统自动算下一个匹配
                day-of-week + 时分的时间戳(AI 不用算 timestamp 与时区)。例:用户说
                「每周一 13:00 提醒我开会」→ recurrence="CUSTOM", recurrenceDays=[1], reminderTimeOfDay="13:00"。
              **NONE 循环单独传本字段被拒绝**(语义不清:今天 13:00 还是明天 13:00?),
              需要先传 reminderDate 或 reminderTime。
              校验失败(不是合法 HH:MM)时回退 "09:00"。
            - recurrence(可选,默认 "NONE"):循环类型,
              枚举 "NONE"(一次性,触发后自动清除)/ "DAILY"(每天固定时间)/
              "CUSTOM"(自定义,配合 recurrenceDays)。
              **没有 WEEKDAYS / WEEKLY 这两个枚举值** —— 用户说「工作日」请传
              recurrence="CUSTOM" + recurrenceDays=[1,2,3,4,5],「周末」传
              recurrence="CUSTOM" + recurrenceDays=[6,7]。「每周 X」同理用 CUSTOM + 单 day 列表。
              触发后:NONE 自动清除整条 reminder;DAILY/CUSTOM 自动滚动 nextTriggerAt 到下一次。
            - recurrenceDays(可选,默认 []):自定义循环的星期几列表,1=周一...7=周日;
              **仅 recurrence=CUSTOM 时生效**,DAILY/NONE 忽略本字段。
              至少要选一天,否则系统会校验失败。
              例:用户说「每周一三五提醒开会」→ recurrence="CUSTOM", recurrenceDays=[1,3,5];
              「工作日提醒我」→ recurrence="CUSTOM", recurrenceDays=[1,2,3,4,5];
              「周末提醒我」→ recurrence="CUSTOM", recurrenceDays=[6,7]。
            返回:新条目的完整对象(包含 id 与 reminder),你可以在下一轮用 todo_update / todo_delete 引用这个 id。
            典型场景:
            - 用户说「提醒我周五前修 X bug」→ title="修 X bug",priority=HIGH, 不设 reminder(只记录)。
            - 用户说「5 分钟后提醒我喝水」→ 先 current_time 拿 timestamp,
              再 todo_add(title="喝水", reminderTime=timestamp+5*60*1000, recurrence="NONE")。
            - 用户说「3 月 15 日上午 10 点提醒我交季报」→
              **优先** todo_add(title="交季报", reminderDate="2026-03-15", reminderTimeOfDay="10:00")
              (系统按本地时区组装 timestamp,recurrence 强制 NONE);
              备选(不推荐)用 current_time + 时区算 timestamp + 传 reminderTime。
            - 用户说「明天下午 3 点开周会」→ 先 current_time + 本地时区算 15:00 时间戳,
              再 todo_add(title="开周会", reminderTime=timestamp, recurrence="CUSTOM", recurrenceDays=[2])。
            - 用户说「每周一三五提醒开会」→ recurrence="CUSTOM", recurrenceDays=[1,3,5],reminderTime 传下一个匹配日的具体时间。
            - 用户说「每周一 13:00 提醒我开会」(只想设时分+循环,不用 AI 算 timestamp)→
              **优先** todo_add(title="开会", reminderTimeOfDay="13:00", recurrence="CUSTOM", recurrenceDays=[1]),
              系统按本地时区算下一个周一的 13:00,AI 不用关心时区、跨日。
            - 用户说「工作日 9 点提醒我打卡」→ recurrence="CUSTOM", recurrenceDays=[1,2,3,4,5],reminderTime 传下一个工作日 9 点的本地时间戳。
            - 用户说「周末提醒我买菜」→ recurrence="CUSTOM", recurrenceDays=[6,7],reminderTime 传下一个周末的本地时间戳。
            - AI 主动建议「我帮你记下来?」并得到用户肯定 → 调本工具。
            注意:
            - 不需要先 todo_list 再 add,直接 add 即可(id 由系统分配)。
            - 新条目默认 isCompleted=false;勾选完成用 todo_update。
            - 「recurrence != NONE 时必须传 reminderTime」——没有首次时间,scheduler 没法把代办放进队列。
            - 想要修改 reminder 字段,用 todo_update(支持 reminderTime / recurrence / recurrenceDays / clearReminder),
              不要 delete + add 重建(会丢 id 和 createdAt)。
            示例:
            {"type":"todo_add","title":"修登录页 bug","priority":"HIGH"}
            {"type":"todo_add","title":"联系客户 Y","content":"谈 v2.0 上线时间","priority":"NORMAL"}
            {"type":"todo_add","title":"喝水","reminderTime":1730000000000,"recurrence":"NONE"}
            {"type":"todo_add","title":"3 月 15 日交季报","reminderDate":"2026-03-15","reminderTimeOfDay":"10:00"}
            {"type":"todo_add","title":"每周一 13 点开会","reminderTimeOfDay":"13:00","recurrence":"CUSTOM","recurrenceDays":[1]}
            {"type":"todo_add","title":"工作日 9 点打卡","reminderTime":1730011200000,"recurrence":"CUSTOM","recurrenceDays":[1,2,3,4,5]}
            {"type":"todo_add","title":"周末买菜","reminderTime":1730011200000,"recurrence":"CUSTOM","recurrenceDays":[6,7]}
            {"type":"todo_add","title":"开周会","reminderTime":1730011200000,"recurrence":"CUSTOM","recurrenceDays":[2]}
        """.trimIndent(),

        "todo_update" to """
            ## todo_update 详细用法
            按 id 更新一条已有代办;只改传入的非 null 字段,其它字段保持原值。
            字段:
            - id(必填):代办的稳定 id(由 todo_list / todo_add 返回)。id 不存在时返回错误。
            - title(可选):新标题(null = 不改;trim 后空 = 校验失败)。
            - content(可选):新描述(null = 不改;空串 = 清空描述)。
            - priority(可选):新优先级(null = 不改;大小写不敏感 / 未知回退 NORMAL)。
            - isCompleted(可选):新完成状态(null = 不改;true / false 直接赋值;true 时系统自动写 completedAt 时间戳)。
            - reminderTime(可选,默认 null):新的提醒触发时间戳(Unix 毫秒)。null/省略 = 不改。
              改时间后 scheduler 会**重新调度** Timer 任务。
              不传 recurrence 时,语义 = 一次性提醒(原来的 recurrence 也不会被清掉,
              仅替换 nextTriggerAt;若想整体改成一次性,显式传 recurrence="NONE")。
              **与 reminderDate 互斥**:传了 reminderDate 时本字段被忽略。
            - reminderDate(可选,默认 null):新指定日期(YYYY-MM-DD 字符串),语义同 todo_add.reminderDate。
              null = 不改;**仅一次性提醒生效**;与 reminderTimeOfDay 配套。
              用户说「把 X 改到 3 月 15 日上午 10 点」→ todo_update(id=..., reminderDate="2026-03-15", reminderTimeOfDay="10:00")。
            - reminderTimeOfDay(可选,默认 null):新时分(HH:MM 字符串),语义同 todo_add.reminderTimeOfDay。
              null = 不改。
              **两种用法**:
                (1) 与 reminderDate 配套 → 一次性「指定日期 + 时分」;
                (2) 与 recurrence 配套 → 把循环型 reminder 改成新时分,系统自动重算首次触发时间。
              想保留原时分只改日期时,可以再传一次原 reminderTimeOfDay 字符串。
            - recurrence(可选,默认 null):新循环类型(null = 不改;其它枚举同 todo_add.recurrence)。
              "NONE" = 一次性,触发后自动清除整条 reminder;循环类型触发后自动滚动到下一次。
              **没有 WEEKDAYS / WEEKLY 这两个值**;「改成工作日提醒」用 CUSTOM + recurrenceDays=[1,2,3,4,5] 同时传。
            - recurrenceDays(可选,默认 null):新 CUSTOM 循环的星期几列表(null = 不改);
              1=周一...7=周日,仅 recurrence=CUSTOM 时使用,其它类型忽略。
            - clearReminder(可选,默认 false):显式清除整条提醒(等价于把 TodoItem.reminder 置 null,
              从 Timer 队列里摘掉)。true 时即便同时传了 reminderTime/recurrence 也以清除为准。
              用户说「把 X 的提醒删了」/「X 不用再提醒了」/「X 先别打扰我」时设 true。
            返回:更新后的完整对象(便于核对 reminder 字段是否生效)。
            典型场景:
            - 用户说「把 X 标为完成」→ 先 todo_list 拿 id,再 todo_update(id=..., isCompleted=true)。
            - 用户说「把 X 改成 URGENT 优先级」→ todo_update(id=..., priority="URGENT")。
            - 用户说「X 不用了,取消完成」→ todo_update(id=..., isCompleted=false),系统自动清空 completedAt。
            - 用户说「把 X 改到 6 点提醒」→ todo_update(id=..., reminderTime=<新时间戳>)。
            - 用户说「把 X 改到 3 月 15 日上午 10 点」→ todo_update(id=..., reminderDate="2026-03-15", reminderTimeOfDay="10:00")。
            - 用户说「把 X 改成每天 9 点提醒」→ todo_update(id=..., reminderTime=<新时间戳>, recurrence="DAILY")。
            - 用户说「把 X 改成每周一三五」→ todo_update(id=..., recurrence="CUSTOM", recurrenceDays=[1,3,5])。
            - 用户说「把 X 改成工作日提醒」→ todo_update(id=..., recurrence="CUSTOM", recurrenceDays=[1,2,3,4,5])。
            - 用户说「把 X 改成周末提醒」→ todo_update(id=..., recurrence="CUSTOM", recurrenceDays=[6,7])。
            - 用户说「X 的提醒删了」/「X 不用再提醒了」→ todo_update(id=..., clearReminder=true)。
            - 想同时改多个字段(标题+优先级+提醒)→ 一次 todo_update(id=..., title=..., priority=..., reminderTime=...)。
            注意:
            - 不传某个字段 = 不改,与「传 null」效果相同(JSON 里省略或显式 null 都行)。
            - 不要用 todo_delete + todo_add 来「重命名」(会丢 createdAt 时间戳),用 todo_update。
            - clearReminder=true 与 reminderTime 互斥:同时传时以 clearReminder 为准,
              因为这是用户「明确不要提醒」+「顺手设个时间」二选一的常见场景,后者优先级低。
            示例:
            {"type":"todo_update","id":"abc-123","isCompleted":true}
            {"type":"todo_update","id":"abc-123","priority":"URGENT","title":"紧急:修登录页"}
            {"type":"todo_update","id":"abc-123","reminderTime":1730011200000,"recurrence":"DAILY"}
            {"type":"todo_update","id":"abc-123","reminderDate":"2026-03-15","reminderTimeOfDay":"10:00"}
            {"type":"todo_update","id":"abc-123","recurrence":"CUSTOM","recurrenceDays":[1,3,5]}
            {"type":"todo_update","id":"abc-123","recurrence":"CUSTOM","recurrenceDays":[1,2,3,4,5]}
            {"type":"todo_update","id":"abc-123","recurrence":"CUSTOM","recurrenceDays":[6,7]}
            {"type":"todo_update","id":"abc-123","clearReminder":true}
        """.trimIndent(),

        "todo_delete" to """
            ## todo_delete 详细用法
            按 id 删除一条代办(破坏性操作)。删除会**连同其上的 reminder 一起清除**,
            scheduler 也会从 Timer 队列里把对应的调度任务摘掉,无需额外调 clearReminder。
            字段:
            - id(必填):代办的稳定 id。id 不存在时返回错误,不会静默成功。
            典型场景:
            - 用户说「删掉 X」/「这条不用记了」→ 先 todo_list 拿 id,再 todo_delete。
            - 用户说「X 的提醒和这条一起删了」→ 直接 todo_delete,提醒会一并清除。
            - 批量清理过期 completed 代办 → 先 todo_list(filter=completed,limit=100) 看一遍,
              再逐条 todo_delete(谨慎,一次性删多条的提示先 ask_user)。
            注意:
            - 删除不可恢复(没有回收站);如不确定先 ask_user 让用户确认。
            - 取消完成 ≠ 删除;只是"改主意了"用 todo_update(isCompleted=false),不再需要这条才用本工具。
            - 「只想删提醒、不删代办」用 todo_update(id=..., clearReminder=true),不要 todo_delete。
            示例:
            {"type":"todo_delete","id":"abc-123"}
        """.trimIndent(),
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
     * 网络重试时的 UI 提示回调。driver 在每轮 AI 调用前注册,
     * 当 [RetrySupport] 触发重试时,本回调会在 **后台线程** 被调用,
     * 实现需要负责切回 EDT 后再写 chatMessages。
     *
     * 函数签名:(label 操作名, attempt 第几次重试 1-based, waitSeconds 等待秒数) -> Unit
     *
     * 设计动机:[RetrySupport] 是 ai 包下的纯工具,不应反向依赖 ui 包;通过
     * 函数回调保持单向依赖,driver 负责把"重试中"消息渲染到气泡里。
     */
    @Volatile
    @JvmStatic
    var onRetryListener: ((label: String, attempt: Int, waitSeconds: Long) -> Unit)? = null

    /**
     * 用户停止/重试终止信号。driver 在每轮 AI 调用前把自己绑进来,RetrySupport
     * 会在 sleep 中定期检查,用户点 Stop 时立即终止重试,避免再发一次请求。
     */
    @Volatile
    @JvmStatic
    var retryShouldContinue: () -> Boolean = { true }

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

        return try {
            RetrySupport.execute(
                label = "AI translate",
                onRetry = { attempt, wait ->
                    onRetryListener?.invoke("AI translate", attempt, wait)
                },
                shouldContinue = { retryShouldContinue() },
            ) {
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
            }
        } catch (e: InterruptedException) {
            // 用户在重试中点了 Stop
            "AI translate cancelled."
        } catch (e: Exception) {
            e.message ?: "AI translate failed."
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

        return try {
            RetrySupport.execute(
                label = "AI batch translate",
                onRetry = { attempt, wait ->
                    onRetryListener?.invoke("AI batch translate", attempt, wait)
                },
                shouldContinue = { retryShouldContinue() },
            ) {
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
            }
        } catch (_: InterruptedException) {
            items.associate { (k, _) -> k to "" }
        } catch (_: Exception) {
            items.associate { (k, _) -> k to "" }
        }
    }

    private fun parseBatchTranslateResult(
        responseText: String,
        items: List<Pair<String, String>>
    ): Map<String, String> {
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
            addProperty("max_tokens", 8192)
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

        return try {
            RetrySupport.execute(
                label = "AI chat",
                onRetry = { attempt, wait ->
                    onRetryListener?.invoke("AI chat", attempt, wait)
                },
                shouldContinue = { retryShouldContinue() },
            ) {
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
            }
        } catch (e: InterruptedException) {
            AiReply("AI chat cancelled.", emptyList())
        } catch (e: Exception) {
            AiReply(e.message ?: "AI chat failed.", emptyList())
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
     *   两个参数分别为「content(最终回答)截至目前的累计全文」与「reasoning(思考过程)截至目前的累计全文」
     *   ——不是 delta。实现里应做去抖/节流(本函数内部已经按行+去 delta 推送,粒度较细;
     *   若调用方发现仍太密可自行节流)。
     * - 非推理模型 reasoning 始终为 "";推理模型(DeepSeek-R1、OpenAI o1/o3、Anthropic extended
     *   thinking 等)reasoning 才有值。
     * - [onPartialText] 可能被调用 0 次(模型只返回 tool_calls,没有文字)。
     * - 流结束后返回完整的 [AiReply](含 toolCalls / failedToolCalls / reasoning),
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
        onPartialText: (contentCumulative: String, reasoningCumulative: String) -> Unit
    ): AiReply {
        val settings = AiSettingsService.getInstance().state
        val protocol = AiProtocol.fromName(settings.protocol)
        val endpoint = AiEndpoint.completeChatEndpoint(settings.url, protocol)
        val model = settings.model.trim()
        val apiKey = settings.apiKey.trim()

        if (settings.url.isBlank()) return AiReply("Please configure the AI URL first.", emptyList())
        if (apiKey.isBlank()) return AiReply("Please configure the AI API key first.", emptyList())
        if (model.isBlank()) return AiReply("Please configure the AI model first.", emptyList())

        // 流式特殊处理:重试只在「请求未开始流」前安全(SSE 已经开始
        // 再重试意味着同一轮有两次响应,语义混乱)。
        // 用一个本地 flag,流开始解析后置 true,RetrySupport 看到就拒绝后续重试。
        val streamStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        return try {
            RetrySupport.execute(
                label = "AI chat (stream)",
                onRetry = { attempt, wait ->
                    onRetryListener?.invoke("AI chat (stream)", attempt, wait)
                },
                shouldContinue = {
                    // 用户点 Stop OR 流已开始 → 终止重试
                    retryShouldContinue() && !streamStarted.get()
                },
            ) {
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
                streamStarted.set(true)
                try {
                    val parser = SseStreamParser(protocol) { contentCumulative, reasoningCumulative ->
                        onPartialText(contentCumulative, reasoningCumulative)
                    }
                    parser.parse(stream)
                    // 流结束后把累积的内容合成一个标准响应 JSON,复用 parseAiReply → toolCallToAction 全链路
                    // content / reasoning 拆开写进合成体,parseAiReply 同样能区分抽取(详见 extractReasoningText)
                    val syntheticBody = when (protocol) {
                        AiProtocol.OPENAI -> buildOpenAiSyntheticBody(
                            content = parser.contentText,
                            reasoning = parser.reasoningText,
                            toolCalls = parser.toolCalls,
                        )

                        AiProtocol.ANTHROPIC -> buildAnthropicSyntheticBody(
                            content = parser.contentText,
                            reasoning = parser.reasoningText,
                            toolCalls = parser.toolCalls,
                        )
                    }
                    parseAiReply(syntheticBody)
                } finally {
                    stream.close()
                }
            }
        } catch (e: InterruptedException) {
            AiReply("AI chat cancelled.", emptyList())
        } catch (e: Exception) {
            AiReply(e.message ?: "AI chat failed.", emptyList())
        }
    }

    /**
     * 把流式累积的 OpenAI tool_call 片段组装成与 [openAiChatBody] 同型的完整响应 JSON,
     * 供 [parseAiReply] 复用,避免为流式场景单独写一套 tool_call 解析逻辑。
     *
     * content / reasoning 分开存放,reasoning 走 OpenAI 兼容扩展字段 `reasoning_content`
     * (DeepSeek-R1、OpenAI o1/o3、OpenRouter 等都用此字段),与真实非流式响应同型,
     * [parseAiReply] / [extractReasoningText] 可以无差别抽取。
     */
    private fun buildOpenAiSyntheticBody(
        content: String,
        reasoning: String,
        toolCalls: List<SseStreamParser.StreamedToolCall>
    ): String {
        val toolCallsArray = JsonArray().apply {
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
        }
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            if (content.isNotEmpty()) addProperty("content", content)
            if (reasoning.isNotEmpty()) addProperty("reasoning_content", reasoning)
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
     *
     * 拆开三个 block 类型:
     * - type=thinking: extended thinking 模型的推理/思考过程(对应 SSE 的 thinking_delta)
     * - type=text: 最终回答(对应 SSE 的 text_delta)
     * - type=tool_use: 工具调用
     *
     * 这样 [parseAiReply] / [extractReasoningText] / [extractAssistantText] / [extractToolCalls]
     * 都能与真实非流式响应无差别处理。
     */
    private fun buildAnthropicSyntheticBody(
        content: String,
        reasoning: String,
        toolCalls: List<SseStreamParser.StreamedToolCall>
    ): String {
        val contentArray = JsonArray().apply {
            if (reasoning.isNotEmpty()) {
                add(JsonObject().apply {
                    addProperty("type", "thinking")
                    addProperty("thinking", reasoning)
                })
            }
            if (content.isNotEmpty()) {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", content)
                })
            }
            toolCalls.forEach { tc ->
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
            addProperty("stop_reason", if (toolCalls.isNotEmpty()) "tool_use" else "end_turn")
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

        return try {
            RetrySupport.execute(
                label = "AI review",
                onRetry = { attempt, wait ->
                    onRetryListener?.invoke("AI review", attempt, wait)
                },
                shouldContinue = { retryShouldContinue() },
            ) {
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
            }
        } catch (_: InterruptedException) {
            ReviewResult.empty("审查请求已取消。")
        } catch (e: Exception) {
            ReviewResult.empty("审查请求失败:${e.message ?: "unknown"}")
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
            val sheets = root.getAsJsonObject("googleSheets") ?: return@runCatching ToolDefinitions.SheetContext(
                null,
                emptyList()
            )
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
        val chatEntry = extractChatEntry(context)
        val systemPrompt = systemPromptFor(chatEntry)
        val activeRole = activeRolePrompt()
        // 2026.x:统一使用完整工具集,Ask AI / ExtractStrings 弹框与主面板权限一致。
        // 工具本身已按 `replace_selection` 在主面板(无 editorSelection)会失败做兜底,
        // 没必要在 schema 层强行裁剪 —— 反而限制用户从弹框里操作 Sheets / 文件 / 代办。
        val tools = ToolDefinitions.openAiTools(sheetCtx, projectBase)
        val root = JsonObject().apply {
            addProperty("model", model)
            add("tools", tools)
            if (stream) addProperty("stream", true)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    if (context.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "## 当前项目上下文（JSON）\n$context")
                        })
                    }
                    // 启用的 AI 角色:把 [cn.jarryleo.insert_strings.ai.AiRole.prompt] 注入到
                    // 额外的 system 消息,让 AI 在聊天时按该角色身份回复。
                    // 空 prompt 或没有启用角色时,此段不加入 messages。
                    if (activeRole != null) {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", "## 当前 AI 角色设定\n$activeRole")
                        })
                    }
                    messages.filter { it.protocolVisible }.forEach { msg -> add(msg.toOpenAiMessage()) }
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
        val chatEntry = extractChatEntry(context)
        val systemPrompt = systemPromptFor(chatEntry)
        val activeRole = activeRolePrompt()
        // 2026.x:统一使用完整工具集,Ask AI / ExtractStrings 弹框与主面板权限一致。
        val tools = ToolDefinitions.anthropicTools(sheetCtx, projectBase)
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 16000)
            // 2026.x:E1 — Anthropic prompt caching
            // 把 system 段从单字符串改为 content block 数组,在最末一个 block 打
            // cache_control:ephemeral。这样从第 2 轮起,system+tools 这部分(每轮固定不变)
            // 按 0.1x 计费。tools 同样在最后一个 tool 上加 cache_control。
            // 详细:https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
            val systemBlocks = mutableListOf<JsonObject>()
            systemBlocks += JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", systemPrompt)
            }
            if (context.isNotBlank()) {
                systemBlocks += JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "## 当前项目上下文（JSON）\n$context")
                }
            }
            if (activeRole != null) {
                // 角色段放最后,作为"会变化的部分"——避免它变化时让整个 system 缓存失效。
                // 也就是说 systemPrompt+context 这两段命中缓存,角色段每轮重算。
                systemBlocks += JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "## 当前 AI 角色设定\n$activeRole")
                    add("cache_control", JsonObject().apply { addProperty("type", "ephemeral") })
                }
            } else {
                // 没有角色段时,直接在 context block 上打 cache_control,覆盖整个 system。
                systemBlocks.last().add(
                    "cache_control",
                    JsonObject().apply { addProperty("type", "ephemeral") }
                )
            }
            add("system", JsonArray().apply { systemBlocks.forEach { add(it) } })
            // tools 缓存:在最后一个 tool 上打 cache_control,让 tools[] 也享受缓存。
            // (Anthropic 支持 tool-level cache_control,与 system 段独立计费)
            if (tools.size() > 0) {
                val lastTool = tools.get(tools.size() - 1).asJsonObject
                lastTool.add(
                    "cache_control",
                    JsonObject().apply { addProperty("type", "ephemeral") }
                )
            }
            add("tools", tools)
            if (stream) addProperty("stream", true)
            add(
                "messages",
                JsonArray().apply { buildAnthropicMessages(messages.filter { it.protocolVisible }).forEach { add(it) } }
            )
        }
        return root.toString()
    }

    /**
     * 读取当前启用的 AI 角色,返回其非空 prompt 文本(用于注入到 system 消息)。
     * - 没有启用任何角色 → null;
     * - 启用了角色但 prompt 为空 → null(空 prompt 不影响 AI 行为,等同无角色);
     * - 否则返回 trim 后的 prompt。
     *
     * 实现直接从 [AiRolesService] 读,这样用户在设置面板中切换"启用"角色时,
     * 下一次 AI 调用立即生效,无需重启或重新加载。
     */
    private fun activeRolePrompt(): String? {
        val role = AiRolesService.getInstance().activeRole() ?: return null
        val prompt = role.prompt.trim()
        return prompt.takeIf { it.isNotEmpty() }
    }

    /**
     * 把 [ChatMessage] 转换为 OpenAI Chat Completions 的 messages 元素。
     * 重点处理三种特殊情况:
     * - assistant 消息携带 tool_calls(原生 function calling)
     * - tool 消息携带 tool_call_id(回传给模型以关联结果)
     * - 普通 user/assistant 消息保持原样
     *
     * 2026.x 优化 D1 协议层:当 assistant 消息的 [protocolSummary] 非空(即 driver 写了"执行操作:xxx"
     * 占位到独立字段)且 content 为空时,OpenAI 协议把 content 设为 null,只发 tool_calls,
     * 避免占位文本进入 AI 上下文。
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
        // 放在 [content] / [protocolSummary]。发回 AI 时必须用 [thinking](没有才退回 [content]),
        // 否则 AI 会看到自己没写过的「执行操作:」前缀,污染下一轮上下文。
        //
        // D1 关键逻辑:
        //  - protocolSummary 非空 且 content 为空 → content 设为 null(只发 tool_calls)
        //  - protocolSummary 非空 且 content 有正文(ask_user 情况)→ content 用正文,protocolSummary 不发
        //  - protocolSummary 为空 → 走原路径,content = thinking.ifEmpty { content }
        val rawContent = if (role == "assistant") thinking.ifEmpty { content } else content
        val protocolSummaryApplied = (role == "assistant" && protocolSummary != null && content.isEmpty())
        // assistant message with tool calls (function calling)
        if (role == "assistant" && toolCalls.isNotEmpty()) {
            val obj = JsonObject().apply {
                addProperty("role", "assistant")
                if (protocolSummaryApplied) {
                    add("content", JsonNull.INSTANCE)
                } else {
                    addProperty("content", rawContent)
                }
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
            return obj
        }
        // 多模态 user 消息(2026.x):content 拆成 text + image_url 数组。
        // 顺序约定:先 text(若 content 非空)后图片,让 AI 看到「文字+图」的自然语序。
        // 仅在 user 消息且 attachments 非空时走这条路径;空 attachments 不影响形态(仍是 string)。
        if (role == "user" && attachments.isNotEmpty()) {
            return JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    if (rawContent.isNotEmpty()) {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", rawContent)
                        })
                    }
                    attachments.forEach { att ->
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
                                addProperty(
                                    "url",
                                    "data:${att.mimeType};base64,${att.data}"
                                )
                            })
                        })
                    }
                })
            }
        }
        // plain text message
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", rawContent)
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
        //
        // 2026.x 优化 D1 协议层:当 protocolSummary 非空且 content 为空(纯工具调用回合)时,
        // Anthropic 协议下 content 数组里跳过 text 块,只发 tool_use 块,
        // 避免"执行操作:xxx"占位文本进入 AI 上下文。
        val protocolText = if (role == "assistant") thinking.ifEmpty { content } else content
        val protocolSummaryApplied = (role == "assistant" && protocolSummary != null && content.isEmpty())
        if (role == "assistant") {
            if (toolCalls.isNotEmpty()) {
                return JsonObject().apply {
                    addProperty("role", "assistant")
                    add("content", JsonArray().apply {
                        if (protocolText.isNotEmpty() && !protocolSummaryApplied) {
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
        // 多模态 user 消息(2026.x):content 拆成 text + image 块数组。
        // Anthropic 图片格式:`{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}`。
        // 仅在 user 消息且 attachments 非空时走这条路径;空 attachments 时仍走单字符串简化路径。
        if (role == "user" && attachments.isNotEmpty()) {
            return JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    if (protocolText.isNotEmpty()) {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", protocolText)
                        })
                    }
                    attachments.forEach { att ->
                        add(JsonObject().apply {
                            addProperty("type", "image")
                            add("source", JsonObject().apply {
                                addProperty("type", "base64")
                                addProperty("media_type", att.mimeType)
                                addProperty("data", att.data)
                            })
                        })
                    }
                })
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
        val reasoning = extractReasoningText(root)
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
        return AiReply(text, actions, parsedToolCalls, failedToolCalls, reasoning)
    }

    /**
     * 从模型响应中抽取「思考/推理」文本(与 [extractAssistantText] 互补)。
     *
     * 兼容两种协议:
     * - OpenAI 兼容扩展(DeepSeek-R1、OpenAI o1/o3、OpenRouter 等):
     *   `choices[0].message.reasoning_content` 字段(字符串)
     * - Anthropic extended thinking:
     *   `content[].type="thinking"` 块,每块的 `thinking` 字段拼接
     *
     * 与 [extractAssistantText] 互斥:同一份响应里两者分别走不同字段,不会互相吃掉。
     * 非推理模型的响应里没有对应字段,本函数返回空串,UI 上 Thinking 折叠区自然不出现。
     */
    private fun extractReasoningText(root: JsonObject): String {
        // OpenAI: choices[0].message.reasoning_content
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.get("reasoning_content")
            ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
            ?.let { return it.asString }
        // Anthropic: content[].type=thinking.thinking 拼接
        root.getAsJsonArray("content")?.let { contentArray ->
            val sb = StringBuilder()
            contentArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val obj = element.asJsonObject
                if (obj.get("type")?.asString != "thinking") return@forEach
                obj.get("thinking")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.asString?.let { sb.append(it) }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        // 2026.x 修复:OpenAI 协议下很多模型(Qwen、DeepSeek 蒸馏版、部分 OpenRouter 模型)
        // 把思考直接嵌在 content 里的 `<think>...</think>` 标签中。流式路径由 SseStreamParser
        // 实时切走;非流式路径在这里补一刀 — 提取标签内文本作为 reasoning,让 UI 的 Thought
        // 折叠区有内容;否则这些模型走非流式通道时思考会直接撑高主气泡。
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.get("content")?.let { contentEl ->
                if (contentEl.isJsonPrimitive) {
                    val thinkContent = extractThinkContent(contentEl.asString)
                    if (thinkContent.isNotEmpty()) return thinkContent
                }
            }
        return ""
    }

    /**
     * 从模型响应中提取助手文本内容。
     * 兼容三种格式:OpenAI chat.completions / Anthropic messages / 退化到 JSON 文本(老协议)。
     */
    private fun extractAssistantText(root: JsonObject): String {
        // OpenAI 格式
        root.getAsJsonArray("choices")?.firstObject()?.getAsJsonObject("message")
            ?.get("content")?.let {
                val raw = it.extractText()
                // 2026.x 修复:剔除 content 里的 `<think>...</think>` 块(部分 OpenAI 模型用这种
                // 标签承载思考),否则思考会撑高主气泡。已通过 [extractReasoningText] 把思考
                // 切到 reasoning 通道,这里只留正文。
                return SseStreamParser.stripThinkTagsStatic(raw)
            }
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
     * 2026.x 修复(非流式路径):从 content 字符串中抽取 `<think>...</think>` 标签内的所有
     * 文本(可能多对),用换行拼接。找不到标签或只有关闭标签时返回空串。
     * 与 [SseStreamParser.stripThinkTagsStatic] 互补 —— 后者去掉标签只留正文,本函数只取标签内。
     */
    private fun extractThinkContent(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val openThink = text.indexOf("<think>", i)
            val openThinking = text.indexOf("<thinking>", i)
            val openIdx = when {
                openThink < 0 -> openThinking
                openThinking < 0 -> openThink
                else -> minOf(openThink, openThinking)
            }
            if (openIdx < 0) break
            val (openTag, closeTag) = if (openThink == openIdx) {
                "<think>" to "</think>"
            } else {
                "<thinking>" to "</thinking>"
            }
            val contentStart = openIdx + openTag.length
            val closeIdx = text.indexOf(closeTag, contentStart)
            if (closeIdx < 0) {
                // 没有关闭标签 — 视作剩余全是思考
                if (contentStart < text.length) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text, contentStart, text.length)
                }
                break
            }
            if (closeIdx > contentStart) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text, contentStart, closeIdx)
            }
            i = closeIdx + closeTag.length
        }
        return sb.toString()
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
            }?.takeIf { it > 0 }
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
                val searchIn = args.get("searchIn")?.asString?.lowercase()?.let { raw ->
                    runCatching { AiAction.QueryKeys.SearchIn.valueOf(raw.uppercase()) }.getOrNull()
                } ?: AiAction.QueryKeys.SearchIn.KEY
                AiAction.QueryKeys(module, pattern, limit, offset, includeTranslations, searchIn)
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
                // 2026.x 简化:固定全模块 + 全语言目录搜索,不再接受 module / language 参数。
                // AI 即便传了也会被忽略(解析时不取),强制语义统一。
                val matchType = parseMatchType(args.get("matchType")?.asString)
                val caseSensitive = args.get("caseSensitive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30
                AiAction.FindKeysByText(text, matchType, caseSensitive, limit)
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
                // 修复:更宽容的解析 —— 部分模型会省略 question 字段或把它写成非字符串,
                // 之前直接 return null → 进入 failedToolCalls → 用户看不到问题内容。
                // 现在即使 question 缺失/类型异常,也用兜底文案让 AI 的提问意图能传达给用户
                // (options 解析失败时降级为「无按钮,用户在输入框回复」模式,继续等用户响应)。
                val rawQuestion = args.get("question")
                val question = when {
                    rawQuestion == null || rawQuestion.isJsonNull -> "(AI 尝试提问但未提供问题内容,请在输入框回复)"
                    rawQuestion.isJsonPrimitive -> rawQuestion.asString.trim()
                        .ifEmpty { "(AI 尝试提问但问题内容为空,请在输入框回复)" }

                    else -> {
                        // 复杂类型(number/array/object)退化到 toString,再 strip 引号
                        val asText = rawQuestion.toString().trim('"').trim()
                        asText.ifEmpty { "(AI 尝试提问但问题内容无法解析,请在输入框回复)" }
                    }
                }
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
            // ===== 代办工具(2026.x 新增) =====
            ToolDefinitions.TOOL_TODO_LIST -> {
                // filter / limit 都允许缺省;空字符串或缺失都按默认值处理
                val filter = args.get("filter")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: "active"
                val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() }
                AiAction.TodoList(filter, limit)
            }

            ToolDefinitions.TOOL_TODO_ADD -> {
                // title 必填;缺失 / 空 / 非字符串 → 返回 null(让 driver 生成「解析失败」tool_result)
                val title = args.get("title")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val content = args.get("content")?.asString.orEmpty()
                val priority = args.get("priority")?.asString.orEmpty()
                // reminder 参数(均为可选,AI 不传就不设置)
                val reminderTime = args.get("reminderTime")?.let {
                    if (it.isJsonNull) null else runCatching { it.asLong }.getOrNull()
                }
                val reminderDate = args.get("reminderDate")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val reminderTimeOfDay = args.get("reminderTimeOfDay")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val recurrence = args.get("recurrence")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val recurrenceDays = args.getAsJsonArray("recurrenceDays")?.let { arr ->
                    arr.mapNotNull { el ->
                        if (el.isJsonNull) null
                        else runCatching { el.asInt }.getOrNull()?.takeIf { it in 1..7 }
                    }.ifEmpty { null }
                }
                AiAction.TodoAdd(
                    title = title,
                    content = content,
                    priority = priority,
                    reminderTime = reminderTime,
                    reminderDate = reminderDate,
                    reminderTimeOfDay = reminderTimeOfDay,
                    recurrence = recurrence,
                    recurrenceDays = recurrenceDays,
                )
            }

            ToolDefinitions.TOOL_TODO_UPDATE -> {
                // id 必填;缺失 / 空 / 非字符串 → 返回 null
                val id = args.get("id")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val title = args.get("title")?.asString
                val content = args.get("content")?.asString
                val priority = args.get("priority")?.asString
                val isCompleted = args.get("isCompleted")?.let {
                    if (it.isJsonNull) null else runCatching { it.asBoolean }.getOrNull()
                }
                // reminder 参数(均为可选,AI 不传就不改)
                val reminderTime = args.get("reminderTime")?.let {
                    if (it.isJsonNull) null else runCatching { it.asLong }.getOrNull()
                }
                val reminderDate = args.get("reminderDate")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val reminderTimeOfDay = args.get("reminderTimeOfDay")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val recurrence = args.get("recurrence")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val recurrenceDays = args.getAsJsonArray("recurrenceDays")?.let { arr ->
                    arr.mapNotNull { el ->
                        if (el.isJsonNull) null
                        else runCatching { el.asInt }.getOrNull()?.takeIf { it in 1..7 }
                    }.ifEmpty { null }
                }
                val clearReminder = args.get("clearReminder")?.let {
                    if (it.isJsonNull) false else runCatching { it.asBoolean }.getOrNull() ?: false
                } ?: false
                AiAction.TodoUpdate(
                    id = id,
                    title = title,
                    content = content,
                    priority = priority,
                    isCompleted = isCompleted,
                    reminderTime = reminderTime,
                    reminderDate = reminderDate,
                    reminderTimeOfDay = reminderTimeOfDay,
                    recurrence = recurrence,
                    recurrenceDays = recurrenceDays,
                    clearReminder = clearReminder,
                )
            }

            ToolDefinitions.TOOL_TODO_DELETE -> {
                val id = args.get("id")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                AiAction.TodoDelete(id)
            }

            ToolDefinitions.TOOL_CURRENT_TIME -> {
                // 不需要任何参数;dummpy / 空 args 都 OK
                AiAction.CurrentTime()
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
                }?.takeIf { it > 0 }
                val rowsArray = args.getAsJsonArray("rows")
                val rows = rowsArray?.mapNotNull { rowElement ->
                    if (!rowElement.isJsonArray) return@mapNotNull null
                    rowElement.asJsonArray.map { it?.extractText().orEmpty() }
                }
                val columnIndex = args.get("columnIndex")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it > 0 }
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
                }?.takeIf { it >= 0 }
                val freezeColumnCount = args.get("freezeColumnCount")?.let {
                    runCatching { it.asInt }.getOrNull()
                }?.takeIf { it >= 0 }
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
                // 2026.x 起 read_file 行号 1-based;默认 startLine=1,endLine=-1(到末尾)
                val startLine = args.get("startLine")?.let { runCatching { it.asInt }.getOrNull() } ?: 1
                val endLine = args.get("endLine")?.let { runCatching { it.asInt }.getOrNull() } ?: -1
                val maxLines = args.get("maxLines")?.let { runCatching { it.asInt }.getOrNull() } ?: 600
                AiAction.ReadFile(path, startLine, endLine, maxLines)
            }

            ToolDefinitions.TOOL_EDIT_FILE -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                val line = args.get("line")?.let { runCatching { it.asInt }.getOrNull() } ?: return null
                if (path.isEmpty() || line < 1) return null
                val mode = args.get("mode")?.asString?.trim()?.lowercase() ?: return null
                if (mode !in setOf("insert_before_line", "insert_after_line", "replace_line")) return null
                val text = args.get("text")?.asString ?: return null
                val endLine = args.get("endLine")?.let { runCatching { it.asInt }.getOrNull() } ?: -1
                AiAction.EditFile(path, line, mode, text, endLine)
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
            // 2026.x 新增 4 个写代码工具
            ToolDefinitions.TOOL_FILE_INFO -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                if (path.isEmpty()) return null
                AiAction.FileInfo(path)
            }

            ToolDefinitions.TOOL_READ_FILES -> {
                val pathsArr = args.get("paths")?.asJsonArray ?: return null
                val paths = pathsArr.mapNotNull {
                    it.takeIf { el -> el.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() }
                }
                if (paths.isEmpty()) return null
                val maxLines = args.get("maxLines")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
                AiAction.ReadFiles(paths, maxLines)
            }

            ToolDefinitions.TOOL_DELETE_FILE -> {
                val path = args.get("path")?.asString?.trim() ?: return null
                if (path.isEmpty()) return null
                AiAction.DeleteFile(path)
            }

            ToolDefinitions.TOOL_MOVE_FILE -> {
                val src = args.get("src")?.asString?.trim() ?: return null
                val dst = args.get("dst")?.asString?.trim() ?: return null
                if (src.isEmpty() || dst.isEmpty()) return null
                AiAction.MoveFile(src, dst)
            }
            // endregion
            ToolDefinitions.TOOL_REPLACE_SELECTION -> {
                // 2026.x:参数是 (oldText, newText) —— 选区里所有匹配 oldText 的子串全部替换为 newText。
                // 整段选区就是要替换的硬编码 → oldText = 选区文本,newText = "@string/<key>" / "R.string.<key>";
                // 精准子串替换 → oldText = 选区里要换的那段,newText = 对 key 的引用。
                val oldText = args.get("oldText")?.asString ?: return null
                if (oldText.isEmpty()) return null
                val newText = args.get("newText")?.asString ?: return null
                AiAction.ReplaceSelection(oldText, newText)
            }

            ToolDefinitions.TOOL_RUN_SHELL -> {
                val command = args.get("command")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return null
                val argsArr = args.getAsJsonArray("args")
                val argList = argsArr?.mapNotNull {
                    if (it.isJsonNull) null else runCatching { it.asString }.getOrNull()
                } ?: emptyList()
                val cwd = args.get("cwd")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val timeoutMs = args.get("timeoutMs")?.asInt?.takeIf { it in 1000..600_000 }
                AiAction.RunShell(command, argList, cwd, timeoutMs)
            }

            ToolDefinitions.TOOL_READ_DIAGNOSTICS -> {
                val minSeverity = args.get("minSeverity")?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() && it in setOf("ERROR", "WARNING", "WEAK_WARNING") }
                AiAction.ReadDiagnostics(minSeverity)
            }

            ToolDefinitions.TOOL_FETCH_URL -> {
                val url = args.get("url")?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return null
                val headersObj = args.getAsJsonObject("headers")
                val headers: Map<String, String> = headersObj?.entrySet()?.mapNotNull { (k, v) ->
                    if (v.isJsonNull) null
                    else runCatching { v.asString }.getOrNull()?.let { k to it }
                }?.toMap() ?: emptyMap()
                val timeoutMs = args.get("timeoutMs")?.asInt?.takeIf { it in 1_000..120_000 }
                val maxBodyChars = args.get("maxBodyChars")?.asInt?.takeIf { it in 1..2_000_000 }
                val responseType = args.get("responseType")?.asString?.trim()
                    ?.takeIf { it in setOf("text", "json") }
                val stripHtml = args.get("stripHtml")?.takeIf { !it.isJsonNull }?.asBoolean
                val stripImages = args.get("stripImages")?.takeIf { !it.isJsonNull }?.asBoolean
                AiAction.FetchUrl(url, headers, timeoutMs, maxBodyChars, responseType, stripHtml, stripImages)
            }

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
            addProperty("max_tokens", 2048)
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
