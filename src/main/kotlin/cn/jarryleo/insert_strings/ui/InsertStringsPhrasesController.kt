package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.phrases.QuickPhrase
import cn.jarryleo.insert_strings.phrases.QuickPhrasesService
import java.util.UUID

/**
 * 快捷短语的 CRUD 协调器。
 *
 * 拆分理由:[InsertStringsSettingsController] 已经比较重(AI + Sheets),
 * 把快捷短语的加载/CRUD 单独抽出后,既方便阅读也避免在那个类里堆字段。
 *
 * 状态(列表 / 编辑中条目 / 编辑草稿)全部由 [InsertStringsUI] 持有,
 * 本类只负责「读服务 + 写服务 + 维护 stable id」,保证:
 * - UI 可以随时基于 mutable state 渲染,无需关注持久化时机;
 * - 编辑中条目的 id 由 controller 在「新建」时分配,「编辑已有」时复用;
 * - 任何「保存」动作都走服务,服务写完 IntelliJ 立即落盘到 XML。
 */
internal class InsertStringsPhrasesController(
    private val ui: InsertStringsUI,
) {

    /**
     * 从服务加载全部快捷短语到 UI。
     * 在 tool window 打开时调用一次。
     */
    fun loadPhrases() {
        val saved = QuickPhrasesService.getInstance().list()
        ui.quickPhrases.clear()
        ui.quickPhrases.addAll(saved)
    }

    /**
     * 新增一条短语:进入编辑态,分配新 id,标题/文本都为空,
     * 颜色默认 null。点 Save 时才真正落库。
     */
    fun beginAdd() {
        ui.editingPhrase = QuickPhrase(
            id = UUID.randomUUID().toString(),
            title = "",
            text = "",
            color = null,
        )
    }

    /**
     * 进入编辑态:把要编辑的条目复制一份到 [ui.editingPhrase],
     * 保留 id,改完 Save 时按 id upsert。
     */
    fun beginEdit(phrase: QuickPhrase) {
        ui.editingPhrase = phrase.copy()
    }

    /**
     * 取消编辑,不写库。
     */
    fun cancelEdit() {
        ui.editingPhrase = null
    }

    /**
     * 保存当前编辑中的短语(新增或更新)。要求标题和文本都非空。
     *
     * @return true 表示已写入,false 表示校验失败(UI 会用 toast 提示)。
     */
    fun saveEdit(
        newTitle: String,
        newText: String,
        newColor: String?,
    ): Boolean {
        val editing = ui.editingPhrase ?: return false
        val title = newTitle.trim()
        val text = newText.trim()
        if (title.isEmpty() || text.isEmpty()) return false
        val color = newColor?.trim()?.takeIf { it.isNotEmpty() }
        val updated = editing.copy(title = title, text = text, color = color)
        QuickPhrasesService.getInstance().upsert(updated)
        // 同步到 UI 列表(保持顺序 = 编辑前位置;若是新增则追加到末尾)
        val idx = ui.quickPhrases.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            ui.quickPhrases[idx] = updated
        } else {
            ui.quickPhrases.add(updated)
        }
        ui.editingPhrase = null
        return true
    }

    /**
     * 删除一条短语。立即写库 + 同步 UI。
     */
    fun delete(phrase: QuickPhrase) {
        QuickPhrasesService.getInstance().delete(phrase.id)
        ui.quickPhrases.removeAll { it.id == phrase.id }
        // 若删的正是正在编辑的,退出编辑态
        if (ui.editingPhrase?.id == phrase.id) {
            ui.editingPhrase = null
        }
    }
}
