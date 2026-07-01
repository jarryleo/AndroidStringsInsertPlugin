package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.ai.AiAction

/**
 * 系统发起的「重复 key 插入」询问,等待用户在气泡上选择覆盖/末尾追加/取消。
 *
 * 抽到顶层(原本是 [InsertStringsChatDriver] 的嵌套类)以便
 * [ChatStateHolder] 等接口可以安全地把该类型暴露给外界。
 */
data class PendingSheetsInsert(
    val actions: List<AiAction.SheetsOperation>,
    /**
     * 与 [actions] 平行对齐的 tool_call_id 列表。用户做出选择、执行后,
     * 用这些 id 把 tool result 回传给 AI。
     */
    val actionToolCallIds: List<String>,
    val duplicateKeys: Set<String>,
    val spreadsheetId: String,
    val sheetName: String,
    val context: String,
    val iteration: Int
)
