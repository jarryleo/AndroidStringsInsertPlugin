package cn.jarryleo.android.buddy.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import cn.jarryleo.android.buddy.InsertStringsManager
import cn.jarryleo.android.buddy.ai.ChatAttachment
import cn.jarryleo.android.buddy.ai.ChatMessage
import cn.jarryleo.android.buddy.sheets.SheetsManager
import cn.jarryleo.android.buddy.xml.KeyedStringsInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 入口打开 chat 时捕获的「编辑器 + 选区」快照。
 *
 * 用于 AI 通过 [AiAction.ReplaceSelection] 工具 / 翻译查重时
 * `使用现有 key:<existing_key>` 选项触发的「把硬编码文本替换为 @string/key 或 R.string.key」操作。
 *
 * 主面板聊天视图此字段为 null(无编辑器上下文),`replace_selection` 工具在主面板场景下会
 * 返回失败信息。
 */
data class EditorSelectionContext(
    val editor: Editor,
    val file: VirtualFile?,
    val selectedText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * 聊天域共享的最小状态面。
 *
 * 原本聊天能力(InsertStringsChatDriver + 三个 controller + context builder)
 * 全部耦合在 [InsertStringsUI] 上,使得 AskAi 弹框等其它入口无法复用。
 * 抽出本接口后,主面板与 AskAi 弹框可以各自提供一份实现:
 * - [InsertStringsUI] 携带完整 table 状态,作为"完整"实现
 * - AskAi 弹框持有轻量实现,只为聊天提供 state 容器
 *
 * 不变量:实现必须保证与原 [InsertStringsUI] 一致的并发语义
 * (chatMessages / chatSending / stopRequested 跨 EDT 与后台线程访问)。
 */
internal interface ChatStateHolder {
    // ===== 通用 =====
    val project: Project
    /** 写文件 / 推 UI 刷新都走它,与主面板共用同一 project-scoped 实例。 */
    val insertStringsManager: InsertStringsManager

    // ===== 聊天状态(由 driver 直接读写) =====
    val chatMessages: SnapshotStateList<ChatMessage>
    var chatInput: String
    var chatSending: Boolean
    /**
     * 跨线程(后台 tool loop vs EDT)访问,实现必须保证内存可见性(Java volatile 语义)。
     * Kotlin interface 上不能直接加 @Volatile,因此实现在 override 时需要重新标注。
     */
    var stopRequested: Boolean
    var pendingAskUserToolCallId: String?
    var askUserCallCount: Int
    var toolDocLoadCount: Int
    var pendingSheetsInsert: PendingSheetsInsert?
    var showContextPopup: Boolean
    var chatContextText: String

    /**
     * 入口打开时捕获的编辑器选区(AskAi / ExtractStrings 弹框在打开时设置)。
     * null 表示当前 chat 入口没有可替换的编辑器选区(主面板聊天视图场景)。
     * 供 AI 通过 [AiAction.ReplaceSelection] 工具 / `onInsertStringsInserted` 回调
     * 触发「把硬编码文本替换为对 key 的引用」时使用。
     */
    var editorSelection: EditorSelectionContext?

    /**
     * 当前 chat 入口标识。由各 state 实现写入固定值,经 [cn.jarryleo.android.buddy.ui.InsertStringsChatContextBuilder]
     * 暴露给 AI,让 AI 在收到「使用现有 key:」选项时能可靠判断是否需要 replace_selection,
     * 不再依赖「自行判断」这种不可靠逻辑。
     *
     * 取值:
     *  - "mainPanel"        主面板聊天视图(InsertStringsUI)。无编辑器上下文。
     *  - "extractStrings"   Extract strings.xml 弹框。已捕获编辑器选中的硬编码文本。
     *  - "askAi"            Ask AI 弹框。若打开时编辑器有选区,会一并通过 [editorSelection] 暴露。
     */
    val chatEntry: String

    /**
     * 入口打开时携带的「引用内容」,由 chat 顶部以一个独立气泡展示给用户
     * (默认折叠成 3 行,可点击展开,会随着消息列表一起滚动)。
     *
     * 典型用途:
     *  - AskAi:用户在编辑器中选中的文本,不再自动以首条 user 消息发出,而是展示为引用,
     *    由用户自行在输入框中决定如何提问(避免「不管三七二十一先问 AI 解释选中文字」的隐式行为)。
     *  - ExtractStrings:被提取的硬编码文本。
     *
     * 实现要求:
     *  - 引用内容必须支持清空(`newChat` / `close` 时由实现自行决定是否清空,
     *    建议始终清空以避免污染下一次对话)。
     *  - 实现必须保证跨线程可见(引用由 EDT 写入,UI 由 Compose 在 EDT 读取即可)。
     */
    var quoteContent: String?

    /**
     * 标记「编辑器选区已被替换为对 key 的引用」,防止 android.buddy 触发的自动替换与
     * AI 后续显式调用 [AiAction.ReplaceSelection] 工具时发生双重替换。
     *
     * 由 [cn.jarryleo.android.buddy.ui.InsertStringsEditorOpsController] 在替换成功后
     * 写入,弹框场景(AskAi / ExtractStrings)有效;主面板始终为 false(无编辑器上下文)。
     * 实现必须保证跨线程(后台 tool loop vs EDT)访问的内存可见性(Java volatile 语义)。
     */
    var editorReplacementTriggered: Boolean

    // ===== 图片附件(2026.x 多模态) =====
    /**
     * 待发送的图片附件列表(粘贴 / 选择 / 拖拽进来的图,尚未随下一条 user 消息发出去)。
     *
     * 行为:
     *  - 用户点击「📎」选图 / Ctrl+V 粘贴 / 拖拽 → driver 写入本列表;
     *  - 用户消息发送时(见 [cn.jarryleo.android.buddy.ui.InsertStringsChatDriver.sendChat]),
     *    driver 把本列表的当前内容作为 attachments 字段挂到即将发出的 user 消息上,
     *    并清空本列表(下一次发送重新累计);
     *  - UI 端在聊天输入框上方以横向滚动的缩略图列表呈现,每张图右上角有 × 删除按钮;
     *  - 「New Topic」时由 driver 清空(避免污染下一会话)。
     *
     * 用 [SnapshotStateList] 是为了在 Compose 重组时自动通知缩略图 UI 变化。
     */
    val pendingImages: SnapshotStateList<ChatAttachment>

    // ===== 表格状态(由 controllers 读写) =====
    /**
     * 当前编辑的 key 列表。
     * - 主面板:指向自己的 mutableStateListOf(改动会反映到主表格)
     * - 弹框:一个独立的 MutableList,写操作不影响主面板(controllers 写入会被丢弃,符合"弹框不展示表格"语义)
     */
    val keyEntries: MutableList<KeyedStringsInfo>
    var selectedKeyIndex: Int
    val sheetsAvailableSheets: MutableList<SheetsManager.SheetInfo>
    /**
     * 主表当前选中 key 的多语言行,作为 buildSheetRows 在 keyEntries 为空时的兜底语言来源。
     * 弹框始终为空即可。
     */
    val rows: List<StringRow>

    // ===== 回调 =====
    /** 显示一个简短 toast;主面板走 actions controller,弹框可在 dialog 上自定义。 */
    fun showToast(message: String)

    /** 把当前编辑保存回 keyEntries;弹框可空实现。 */
    fun saveCurrentEdits() {}

    /** 刷新当前选中 key 的 rows;弹框可空实现。 */
    fun updateRowsForSelectedKey() {}

    /**
     * 当一次 [AiAction.InsertStrings] 实际写入到 strings.xml 后由 driver 回调,
     * 或 AI 通过 [AiAction.ReplaceSelection] 工具调用时由 driver 触发。
     * 用于 "Extract strings.xml" / "Ask AI" 这类需要把选区替换成 @string/key 或 R.string.key 的场景。
     * 默认空实现;只有 ExtractStringsChatHolder / AskAiChatHolder 等需要回填编辑器的入口会覆写。
     *
     * 行为:在 EDT 上执行 WriteCommandAction;XML 布局文件替换为 `@string/<key>`,
     * 其它文件替换为 `R.string/<key>`;**执行后聊天视图保持打开**(不调用 closeChatView),
     * AI 继续推进翻译查重的后续流程(调用 read_string / ask_user / update_string)。
     * 若 [editorSelection] 为 null 或选区已失效,默认实现为 no-op,
     * ExtractStrings / AskAi 实现应自行兜底并返回失败结果。
     *
     * @param key      要引用的字符串 key
     * @param module   目标模块(可能为 null,表示未指定模块)
     */
    fun onInsertStringsInserted(key: String, module: String?) {}
}
