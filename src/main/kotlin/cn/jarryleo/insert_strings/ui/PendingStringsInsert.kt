package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction

/**
 * 系统发起的「strings.xml 重复 key」询问,等待用户在气泡上选择
 * 「使用现有翻译 / 插入新翻译 / 取消」。
 *
 * 与 [PendingSheetsInsert] 对称 — 一个对应 Google Sheets 写前的去重询问,
 * 一个对应 Android strings.xml 写前的去重询问。
 *
 * 抽到顶层(原本是 [InsertStringsChatDriver] 的嵌套类)以便
 * [ChatStateHolder] 等接口可以安全地把该类型暴露给外界。
 */
data class PendingStringsInsert(
    /**
     * 触发本次询问的 insert_strings 动作(同一批已通过模块一致性校验,
     * 全部指向同一 targetModule)。
     */
    val actions: List<AiAction.InsertStrings>,
    /**
     * 与 [actions] 平行对齐的 tool_call_id 列表。用户做出选择、执行后,
     * 用这些 id 把 tool result 回传给 AI。
     */
    val actionToolCallIds: List<String>,
    /**
     * 命中的现有 key 列表 — 形如 `[(key, moduleName, language, existingText)]`。
     * 这里用 `existingText` 是为了在 UI 上让用户看到"已经存在的翻译长什么样"，
     * 方便决定是否使用现有翻译。
     */
    val existingKeys: List<ExistingKeyMatch>,
    /**
     * 按待插入 action.name 记录的命中列表。保留这个映射后，
     * 用户选择「使用现有翻译」时可以为每个待插入 key 选中对应的现有 key，
     * 而不是从整批命中里随便取第一条。
     */
    val existingKeysByAction: Map<String, List<ExistingKeyMatch>> = emptyMap(),
    val targetModule: String,
    val context: String,
    val iteration: Int,
)

/**
 * 一次重复命中的详细信息:在哪个模块、哪个语言下,出现了与待插入
 * 翻译文本一致/相似的现有 key。
 */
data class ExistingKeyMatch(
    val key: String,
    val module: String,
    val language: String,
    val existingText: String,
)
