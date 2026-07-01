package cn.jarryleo.android.buddy.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * 持久化层 bean。IntelliJ 的 [PersistentStateComponent] 通过反射读写 bean 属性,
 * 所以这里每个字段必须是 public `var`,`MutableList<AiRole>` 会被自动 XML 序列化。
 */
class AiRolesState {
    var roles: MutableList<AiRole> = mutableListOf()
    /**
     * 是否已经完成首次默认角色安装(由 [cn.jarryleo.android.buddy.ui.InsertStringsRolesController.loadRoles]
     * 在第一次发现 [roles] 为空时写入默认占位角色后置为 true)。
     *
     * 用途:与 [cn.jarryleo.android.buddy.phrases.QuickPhrasesService] 不同,
     * 角色列表允许用户主动「全部删除」,此时**不应该**再复活默认角色(尊重用户选择)。
     * 没有这个 flag 的话,只要 [roles] 为空就重装,会反复把用户删掉的内置角色塞回来。
     *
     * 老数据迁移:对没有该字段的历史 state,反序列化后默认为 false,
     * 首次 loadRoles 仍会安装默认角色,之后置 true;如果用户已经手动清空过列表,
     * 老 state 的 `roles` 也是空,会触发一次「重装默认」,这是可接受的迁移行为
     * (相当于给未配置的用户补上入口)。
     */
    var defaultsInstalled: Boolean = false
}

/**
 * 全局(应用级)AI 角色预设的持久化服务。
 *
 * 用 `applicationService` 而非 `projectService`,理由同
 * [cn.jarryleo.android.buddy.ai.AiSettingsService] / [cn.jarryleo.android.buddy.phrases.QuickPhrasesService]:
 * 角色预设是用户的个人偏好,不与具体项目绑定;换一个项目也能直接用同一组角色,无需重复配置。
 *
 * 存储位置:`<config>/options/insertStringsAiRoles.xml`(应用级配置目录)。
 *
 * **单选启用语义**:任意时刻最多一条角色的 [AiRole.isEnabled] = true。
 * 通过 [setEnabled] / [upsert] 等写入方法在内部强制执行(启用某条会自动把其它已启用的改为 false),
 * UI 只需要把"启用"作为一种状态切换,无需关心如何取消其它角色的启用。
 */
@State(
    name = "InsertStringsAiRoles",
    storages = [Storage("insertStringsAiRoles.xml")]
)
class AiRolesService : PersistentStateComponent<AiRolesState> {
    private var state = AiRolesState()

    override fun getState(): AiRolesState = state

    override fun loadState(state: AiRolesState) {
        this.state = state
        // 防御性归一化:加载时若意外出现多条 enabled,只保留第一条为启用,保证单选语义。
        val firstEnabledIdx = state.roles.indexOfFirst { it.isEnabled }
        if (firstEnabledIdx >= 0) {
            state.roles.forEachIndexed { idx, role ->
                if (idx != firstEnabledIdx && role.isEnabled) role.isEnabled = false
            }
        }
    }

    /**
     * 当前已保存的全部角色(返回不可变副本,避免外部直接修改内部 state)。
     */
    fun list(): List<AiRole> = state.roles.toList()

    /**
     * 是否已经完成过首次默认角色安装。
     * 控制器在 loadRoles 时用此判断是否要写入占位默认角色,避免用户主动清空后被反复复活。
     */
    fun isDefaultsInstalled(): Boolean = state.defaultsInstalled

    /**
     * 当前已启用的角色(没有则 null)。AI 聊天时由 [cn.jarryleo.android.buddy.ai.AITranslator] 读取,
     * 把 [AiRole.prompt] 注入到 system 消息。
     */
    fun activeRole(): AiRole? = state.roles.firstOrNull { it.isEnabled }

    /**
     * 新增或更新一条角色(按 [AiRole.id] 匹配)。
     * 编辑时复用 [AiRole.id],新增时由 controller 分配。
     *
     * @param role 入参的 [AiRole.isEnabled] 会被尊重:若为 true,会自动把其它已启用的角色
     *             改为 false,保证单选启用语义。
     */
    fun upsert(role: AiRole) {
        val list = state.roles
        val idx = list.indexOfFirst { it.id == role.id }
        if (idx >= 0) {
            list[idx] = role
        } else {
            list.add(role)
        }
        if (role.isEnabled) {
            disableOthers(role.id)
        }
    }

    /**
     * 按 id 删除一条角色。id 不存在时为 no-op。
     */
    fun delete(id: String) {
        state.roles.removeAll { it.id == id }
    }

    /**
     * 首次安装默认角色占位(猫娘 / 小秘)。
     * - 仅在 [AiRolesState.defaultsInstalled] = false 时写入,并把该 flag 置 true;
     * - 已安装过时为 no-op,避免用户清空列表后被反复复活;
     * - 写入的默认 role 全部 isEnabled = false(等用户填写 prompt 后再手动启用);
     * - 写入后立即落盘,下次 loadRoles 不会再触发安装。
     *
     * 由 [cn.jarryleo.android.buddy.ui.InsertStringsRolesController.loadRoles] 在首次
     * (或老 state 升级)时调用。
     */
    fun installDefaultsIfNeeded(defaults: List<AiRole>) {
        if (state.defaultsInstalled) return
        defaults.forEach { role ->
            // 防御性:每个默认 role 重新分配 id,避免多次 installDefaultsIfNeeded 之间
            // 出现 id 冲突(理论上不会,但 UUID 重生成成本低,稳一点)。
            if (role.id.isBlank()) role.id = UUID.randomUUID().toString()
            state.roles.add(role)
        }
        state.defaultsInstalled = true
    }

    /**
     * 把 [id] 对应角色的 [AiRole.isEnabled] 设为 [enabled]。
     * 启用时自动把其它已启用的角色改为 false,保证单选语义。
     * 关闭时仅修改自身;若 [enabled] = false 但没有 id 匹配的角色,no-op。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        val list = state.roles
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx].isEnabled = enabled
        if (enabled) {
            disableOthers(id)
        }
    }

    /**
     * 取消 [exceptId] 之外的所有角色的启用状态。内部使用,供 [upsert] / [setEnabled] 强制单选语义。
     */
    private fun disableOthers(exceptId: String) {
        state.roles.forEach { role ->
            if (role.id != exceptId && role.isEnabled) role.isEnabled = false
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): AiRolesService {
            return ApplicationManager.getApplication().getService(AiRolesService::class.java)
        }
    }
}
