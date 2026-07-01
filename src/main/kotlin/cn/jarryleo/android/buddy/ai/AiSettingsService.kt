package cn.jarryleo.android.buddy.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * AI 设置的持久化 bean。
 *
 * 字段分为两类:
 *
 * 1. **镜像字段**(url / apiKey / protocol / model):
 *    用于在无 provider 列表时承载「老的单配置」持久化格式,也是 [com.intellij.openapi.components.PersistentStateComponent]
 *    的 Bean XML 序列化天然会写入 XML 的字段(它们是 `var`,有 setter)。读取后由 [AiSettingsService.loadState] 做
 *    老格式迁移。
 *
 *    这些字段同样被 service 的 [getState] 在写回前**同步为当前 provider 的值**,
 *    因此外部代码(例如 [AITranslator])通过 `state.url` / `state.model` 等直接读取
 *    也能拿到当前 provider 的配置,**不需要修改任何调用点**。
 *
 * 2. **新格式字段**(providers / currentProviderId):
 *    从 3.10.0 版本起,AI 设置改为「多 provider」模型:用户可以维护多个 AI 提供商,
 *    通过切换「当前」provider 来快速在不同 AI 服务间来回切。
 *    - [providers] 是全部 provider 列表;
 *    - [currentProviderId] 是当前激活的 provider id,AI 调用时使用。
 *
 * 老格式 → 新格式的迁移:[AiSettingsService.loadState] 中,如果
 * `providers` 为空但镜像字段有值,会基于镜像字段创建一个默认 provider 并置为当前。
 */
class AiSettingsState {
    // ===== 镜像字段(读 = 当前 provider 的值,由 service 在 getState/loadState 时同步) =====
    var url: String = ""
    var apiKey: String = ""
    var protocol: String = AiProtocol.OPENAI.name
    var model: String = "qwen-plus"

    // ===== 新格式字段 =====
    /**
     * 全部 provider 列表。id 是稳定标识,name 用于展示,url/apiKey/protocol/model 是实际配置。
     */
    var providers: MutableList<AiProvider> = mutableListOf()
    /**
     * 当前激活的 provider id。null 表示还没有任何 provider(刚装的初始状态)。
     */
    var currentProviderId: String? = null
}

/**
 * AI 设置的全局(应用级)持久化服务。
 *
 * 用 `applicationService` 而非 `projectService`:
 * - 「AI 提供商」是用户的个人偏好,不与具体项目绑定;
 * - 用户希望换一个项目也能直接用同一组配置,无需重复配置。
 *
 * 存储位置:`<config>/options/insertStringsAiSettings.xml`(应用级配置目录)。
 *
 * **设计要点**:
 * 1. 多 provider 模型:用户可添加/编辑/删除/切换 provider;切换时同步更新镜像字段。
 * 2. 向后兼容:从 3.10.0 之前的单配置格式升级时,首次加载会基于镜像字段自动生成一个
 *    "Default" provider 并置为当前,避免用户已经填好的配置丢失。
 * 3. 镜像字段的双向同步:每次修改 providers 列表后,镜像字段会被同步为当前 provider 的
 *    值,保证旧的 `state.url` 读取语义不变。
 */
@State(
    name = "InsertStringsAiSettings",
    storages = [Storage("insertStringsAiSettings.xml")]
)
class AiSettingsService : PersistentStateComponent<AiSettingsState> {
    private var state = AiSettingsState()

    override fun getState(): AiSettingsState {
        // 写回前把镜像字段同步为当前 provider 的值,
        // 这样老格式数据(读 url/apiKey/protocol/model 字段)始终拿到的是当前 provider 的值。
        val current = currentProvider()
        if (current != null) {
            state.url = current.url
            state.apiKey = current.apiKey
            state.protocol = current.protocol
            state.model = current.model
        }
        return state
    }

    override fun loadState(state: AiSettingsState) {
        this.state = state
        // 老格式迁移:providers 为空但镜像字段有值 → 创建默认 provider
        if (state.providers.isEmpty() &&
            (state.url.isNotBlank() || state.apiKey.isNotBlank() || state.model.isNotBlank())
        ) {
            val legacy = AiProvider(
                id = UUID.randomUUID().toString(),
                name = "Default",
                url = state.url,
                apiKey = state.apiKey,
                protocol = state.protocol,
                model = state.model,
            )
            state.providers.add(legacy)
            state.currentProviderId = legacy.id
        }
        // 没有 current 时回退到列表第一个
        if (state.currentProviderId == null && state.providers.isNotEmpty()) {
            state.currentProviderId = state.providers.first().id
        }
        syncMirrorsToCurrent()
    }

    /**
     * 把镜像字段同步为当前 provider 的值。
     * 在所有「providers 列表或 currentProviderId 变化」之后调用,
     * 保证 `state.url/apiKey/protocol/model` 总是反映当前 provider。
     */
    private fun syncMirrorsToCurrent() {
        val current = currentProvider() ?: return
        state.url = current.url
        state.apiKey = current.apiKey
        state.protocol = current.protocol
        state.model = current.model
    }

    // ============== 读取 ==============

    /**
     * 当前激活的 provider(没有则 null)。
     * AI 调用的实际配置从这里取。
     */
    fun currentProvider(): AiProvider? {
        val id = state.currentProviderId ?: return null
        return state.providers.firstOrNull { it.id == id }
    }

    /**
     * 全部 provider 列表(返回不可变副本,防止外部直接改内部 state)。
     */
    fun listProviders(): List<AiProvider> = state.providers.toList()

    // ============== 写操作(自动同步镜像) ==============

    /**
     * 新增一个 provider。
     * 若当前没有激活 provider,自动把新 provider 置为当前。
     * 若 [provider] 的 id 为空,会自动分配 UUID。
     *
     * @return 入库后的 provider(可能与入参不是同一实例,但字段一致)
     */
    fun addProvider(provider: AiProvider): AiProvider {
        if (provider.id.isBlank()) provider.id = UUID.randomUUID().toString()
        state.providers.add(provider)
        if (state.currentProviderId == null) {
            state.currentProviderId = provider.id
        }
        syncMirrorsToCurrent()
        return provider
    }

    /**
     * 更新一个已存在的 provider(按 id 匹配)。
     * id 不存在时 no-op。
     */
    fun updateProvider(provider: AiProvider) {
        val idx = state.providers.indexOfFirst { it.id == provider.id }
        if (idx < 0) return
        state.providers[idx] = provider
        syncMirrorsToCurrent()
    }

    /**
     * 删除一个 provider。
     * 若删的是当前 provider,会自动切到列表里剩余的第一个(若列表为空则清空当前)。
     * id 不存在时 no-op。
     */
    fun deleteProvider(id: String) {
        state.providers.removeAll { it.id == id }
        if (state.currentProviderId == id) {
            state.currentProviderId = state.providers.firstOrNull()?.id
        }
        syncMirrorsToCurrent()
    }

    /**
     * 把 [id] 对应的 provider 设为当前。
     * id 不存在时 no-op。
     */
    fun setCurrentProvider(id: String) {
        if (state.providers.none { it.id == id }) return
        state.currentProviderId = id
        syncMirrorsToCurrent()
    }

    companion object {
        @JvmStatic
        fun getInstance(): AiSettingsService {
            return ApplicationManager.getApplication().getService(AiSettingsService::class.java)
        }
    }
}
