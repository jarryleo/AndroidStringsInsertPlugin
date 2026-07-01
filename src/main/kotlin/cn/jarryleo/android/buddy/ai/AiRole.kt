package cn.jarryleo.android.buddy.ai

import java.util.UUID

/**
 * 单条 AI 角色预设。
 *
 * 用途:在 AI 聊天场景下,启用后将 [prompt] 注入到 system 消息里,作为「角色扮演」的 system prompt。
 * 同一个时间点**最多只允许一条**角色处于「启用」状态(单选语义,UI 上体现为
 * 「启用的角色条目高亮、其它条目的按钮显示为 Enable」)。
 *
 * 字段约束:
 * - [id] 稳定标识,用于编辑/删除/启用时定位条目(基于 title 容易被重命名干扰);
 *   新建时由 controller 自动分配 UUID,UI 侧不应自行设置。
 * - [title] 人类可读的名称(列表显示用),允许为空(空时回退为 "(untitled)")。
 * - [prompt] 角色提示词,会拼到 system 消息里;允许为空(空时不注入任何内容)。
 *   实际生效的 system prompt 由 [cn.jarryleo.android.buddy.ai.AITranslator] 在每次 AI
 *   调用时按当前启用的角色动态拼装。
 * - [isEnabled] 当前是否处于「启用」状态。
 *   - true:角色被激活,提示词会随每次 AI 调用注入;
 *   - false:仅作为预设存在,不影响 AI 行为。
 *   启用是单选 — 启用某条会自动把其它已启用的改为 false。
 *
 * 字段全部用 `var`:IntelliJ 的 [com.intellij.openapi.components.PersistentStateComponent]
 * 通过 Bean 序列化(XmlSerializer)读写,需要 setter 入口;data class 的 `val` 属性
 * 没有 setter,会导致反序列化失败。
 * 这里手写 [equals] / [hashCode] / [toString] / [copy] 替代 data class 能力。
 */
class AiRole(
    var id: String = "",
    var title: String = "",
    var prompt: String = "",
    var isEnabled: Boolean = false,
) {

    fun copy(
        id: String = this.id,
        title: String = this.title,
        prompt: String = this.prompt,
        isEnabled: Boolean = this.isEnabled,
    ): AiRole = AiRole(id, title, prompt, isEnabled)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiRole) return false
        return id == other.id &&
            title == other.title &&
            prompt == other.prompt &&
            isEnabled == other.isEnabled
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + prompt.hashCode()
        result = 31 * result + isEnabled.hashCode()
        return result
    }

    override fun toString(): String =
        "AiRole(id=$id, title=$title, enabled=$isEnabled)"

    companion object {
        /**
         * 新建一个空白 role(id 自动分配),供「+ Add」按钮使用。
         */
        fun blank(): AiRole = AiRole(
            id = UUID.randomUUID().toString(),
            title = "",
            prompt = "",
            isEnabled = false,
        )
    }
}
