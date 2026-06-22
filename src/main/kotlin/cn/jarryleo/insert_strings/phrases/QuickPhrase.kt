package cn.jarryleo.insert_strings.phrases

/**
 * 一条用户自定义的 AI 快捷短语。
 *
 * 用于在 AI 聊天面板里以按钮形式显示,点击后把 [text] 作为用户消息发出。
 * - [id] 是稳定标识,用于编辑/删除/拖拽时定位条目(基于 title 容易被重命名干扰)。
 * - [color] 用于按钮和文字着色(便于用户在同一屏快速分辨不同类别的快捷短语),
 *   存的是 7 位的 hex 字符串(例:"#FF6B6B");空串或 null 表示沿用默认色(无染色)。
 * - [isDeletable] 控制 UI 上的删除按钮是否可点:true = 用户自建,可删;false = 内置默认,
 *   不可删(只能改 / 拖动)。`Reset Defaults` 时会把所有 isDeletable=false 的条目重置。
 *
 * 字段全部用 `var`:IntelliJ 的 [com.intellij.openapi.components.PersistentStateComponent]
 * 通过 Bean 序列化(XmlSerializer)读写,需要 setter 入口;data class 的 `val` 属性
 * 没有 setter,会让反序列化失败或无法在升级时迁移老数据。
 * 这里手写 [equals] / [hashCode] / [toString] / [copy] 替代 data class 能力。
 */
class QuickPhrase(
    var id: String = "",
    var title: String = "",
    var text: String = "",
    var color: String? = null,
    var isDeletable: Boolean = true,
) {
    fun copy(
        id: String = this.id,
        title: String = this.title,
        text: String = this.text,
        color: String? = this.color,
        isDeletable: Boolean = this.isDeletable,
    ): QuickPhrase = QuickPhrase(id, title, text, color, isDeletable)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuickPhrase) return false
        return id == other.id &&
            title == other.title &&
            text == other.text &&
            color == other.color &&
            isDeletable == other.isDeletable
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (color?.hashCode() ?: 0)
        result = 31 * result + isDeletable.hashCode()
        return result
    }

    override fun toString(): String =
        "QuickPhrase(id=$id, title=$title, text=$text, color=$color, isDeletable=$isDeletable)"
}
