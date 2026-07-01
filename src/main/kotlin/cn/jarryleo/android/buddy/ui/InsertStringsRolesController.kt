package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.ai.AiRole
import cn.jarryleo.android.buddy.ai.AiRolesService
import cn.jarryleo.android.buddy.ai.DefaultRoles

/**
 * AI 角色预设的 CRUD 协调器。
 *
 * 拆分理由:与 [InsertStringsPhrasesController] 类似,角色管理只读 / 写少量 UI 状态,
 * 与 AI 设置 / Sheets 设置无强耦合,独立成类方便阅读。
 *
 * 状态(列表 / 编辑中条目)由 [InsertStringsUI] 持有,本类只负责:
 * - 从 [AiRolesService] 加载到 UI state;
 * - 把 UI 上的编辑 / 删除 / 启用操作落库,并同步 UI state;
 * - 维护稳定 id(新建时分配,编辑已有时复用)。
 *
 * 启用语义由 service 强制单选(启用某条会自动把其它已启用的改为 false),
 * 本类不重复实现该规则,只把"启用 / 取消启用"按用户意图转发给 service。
 */
internal class InsertStringsRolesController(
    private val ui: InsertStringsUI,
) {

    /**
     * 从服务加载全部角色到 UI。
     * 在 tool window 打开时调用一次,只读,不做迁移。
     *
     * 首次安装(老 state 升级也算)且从未安装过默认角色时,会写入 2 个占位角色
     * (猫娘 / 小秘,prompt 为空,isEnabled = false),由 [AiRolesService] 内部用
     * `defaultsInstalled` flag 保证只写一次 ——
     * 这样用户在设置面板手动「全部删除」后,再次打开 tool window 不会被反复复活。
     */
    fun loadRoles() {
        val service = AiRolesService.getInstance()
        // 首次(或老 state 升级)安装默认占位角色。
        // 内部用 defaultsInstalled flag 去重,no-op 安全。
        service.installDefaultsIfNeeded(DefaultRoles.build())
        val list = service.list()
        ui.aiRoles.clear()
        ui.aiRoles.addAll(list)
    }

    /**
     * 进入「新增角色」态:分配新 id 的空白 role 作为草稿,设置到 [InsertStringsUI.editingRole]。
     * Save 时由 [saveEdit] 校验通过后落库(此时 role 才会被加入 [ui.aiRoles] 列表)。
     * 用户中途取消则 [cancelEdit] 直接丢弃草稿,不会有任何持久化副作用。
     */
    fun beginAdd() {
        ui.editingRole = AiRole.blank()
    }

    /**
     * 进入「编辑已有角色」态:把目标 role 复制一份到 [ui.editingRole],保留 id 与 isEnabled。
     * 不写库,Save 时按 id upsert。
     */
    fun beginEdit(role: AiRole) {
        ui.editingRole = role.copy()
    }

    /**
     * 取消编辑,不写库。
     */
    fun cancelEdit() {
        ui.editingRole = null
    }

    /**
     * 保存当前编辑中的角色(新增或更新)。校验:
     * - title 不能为空(否则 toast 提示);
     * - prompt 允许为空(空 prompt = 不实际生效,等同该角色无内容,但仍可保存作为占位)。
     *
     * 保存后:
     * - 新增:role 进入 service 与 UI 列表(若 isEnabled,自动取消其它已启用的角色);
     * - 更新:按 id 替换原条目;
     * - 退出编辑态。
     *
     * @return true 表示已落库;false 表示校验失败(UI 用 toast 提示)。
     */
    fun saveEdit(
        newTitle: String,
        newPrompt: String,
    ): Boolean {
        val editing = ui.editingRole ?: return false
        val title = newTitle.trim()
        if (title.isEmpty()) return false
        val prompt = newPrompt
        val updated = editing.copy(title = title, prompt = prompt)
        AiRolesService.getInstance().upsert(updated)
        val idx = ui.aiRoles.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            ui.aiRoles[idx] = updated
        } else {
            ui.aiRoles.add(updated)
        }
        ui.editingRole = null
        return true
    }

    /**
     * 删除一条角色。立即写库 + 同步 UI 列表。
     * 若删的是当前正在编辑的角色,自动退出编辑态。
     */
    fun delete(role: AiRole) {
        AiRolesService.getInstance().delete(role.id)
        ui.aiRoles.removeAll { it.id == role.id }
        if (ui.editingRole?.id == role.id) {
            ui.editingRole = null
        }
    }

    /**
     * 切换角色的启用状态(启用 / 取消启用)。由 service 强制单选:
     * 启用某条会自动把其它已启用的改为 false,UI 列表会反映这个变化。
     */
    fun setEnabled(role: AiRole, enabled: Boolean) {
        AiRolesService.getInstance().setEnabled(role.id, enabled)
        // 同步 UI 列表:启用 / 取消当前条目,并把其它条目的 isEnabled 置为 false(若有任一变化)。
        val newList = ui.aiRoles.map { r ->
            when {
                r.id == role.id -> r.copy(isEnabled = enabled)
                enabled -> r.copy(isEnabled = false)
                else -> r
            }
        }
        ui.aiRoles.clear()
        ui.aiRoles.addAll(newList)
    }
}
