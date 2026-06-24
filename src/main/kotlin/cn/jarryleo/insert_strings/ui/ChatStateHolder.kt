package cn.jarryleo.insert_strings.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import cn.jarryleo.insert_strings.InsertStringsManager
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
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
     * 标记「编辑器选区已被替换为对 key 的引用」,防止 insert_strings 触发的自动替换与
     * AI 后续显式调用 [AiAction.ReplaceSelection] 工具时发生双重替换。
     *
     * 由 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController] 在替换成功后
     * 写入,弹框场景(AskAi / ExtractStrings)有效;主面板始终为 false(无编辑器上下文)。
     * 实现必须保证跨线程(后台 tool loop vs EDT)访问的内存可见性(Java volatile 语义)。
     */
    var editorReplacementTriggered: Boolean

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
     * 写入类动作(insert/update/delete)完成后,主面板期望关闭 chat 切回表格以展示结果;
     * 弹框没有表格可切,默认空实现即可。
     */
    fun closeChatView() {}

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
