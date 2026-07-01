package cn.jarryleo.android.buddy.ai

import java.util.UUID

/**
 * 单个 AI 提供商配置。
 *
 * 一个 "AI 提供商" 指用户可独立管理、独立切换的一组 AI 服务端点配置
 * (URL + API Key + 协议 + 当前模型)。用户可以维护多个 provider,
 * 每次 AI 调用时使用标记为「当前」的 provider。
 *
 * 字段约束:
 * - [id] 稳定标识,用于编辑/删除/切换时定位条目(基于 name 容易被重命名干扰);
 *   新建时由 service 自动分配(非空 UUID),UI 侧不应自行设置。
 * - [name] 用户给 provider 起的人类可读名字(展示用),允许重复,允许为空(空时回退为 "Provider #N")。
 * - [url] / [apiKey] / [model] 服务调用时的实际参数。
 * - [protocol] 用 [AiProtocol.name] 存储(便于序列化为字符串,反序列化时用 [AiProtocol.fromName] 还原)。
 *
 * 字段全部用 `var`:IntelliJ 的 [com.intellij.openapi.components.PersistentStateComponent]
 * 通过 Bean 序列化(XmlSerializer)读写,需要 setter 入口;data class 的 `val` 属性
 * 没有 setter,会导致反序列化失败或无法在升级时迁移老数据。
 * 这里手写 [equals] / [hashCode] / [toString] / [copy] 替代 data class 能力。
 */
class AiProvider(
    var id: String = "",
    var name: String = "",
    var url: String = "",
    var apiKey: String = "",
    var protocol: String = AiProtocol.OPENAI.name,
    var model: String = "",
) {

    fun copy(
        id: String = this.id,
        name: String = this.name,
        url: String = this.url,
        apiKey: String = this.apiKey,
        protocol: String = this.protocol,
        model: String = this.model,
    ): AiProvider = AiProvider(id, name, url, apiKey, protocol, model)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiProvider) return false
        return id == other.id &&
            name == other.name &&
            url == other.url &&
            apiKey == other.apiKey &&
            protocol == other.protocol &&
            model == other.model
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + apiKey.hashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + model.hashCode()
        return result
    }

    override fun toString(): String =
        "AiProvider(id=$id, name=$name, protocol=$protocol, model=$model)"

    companion object {
        /**
         * 新建一个空白 provider(id 自动分配),供「+ Add」按钮使用。
         */
        fun blank(): AiProvider = AiProvider(
            id = UUID.randomUUID().toString(),
            name = "",
            url = "",
            apiKey = "",
            protocol = AiProtocol.OPENAI.name,
            model = "",
        )
    }
}
