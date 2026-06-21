package cn.jarryleo.insert_strings.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import cn.jarryleo.insert_strings.InsertStringsManager
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import com.intellij.openapi.project.Project

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
}
