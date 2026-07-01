package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.phrases.DefaultPhrases
import cn.jarryleo.android.buddy.phrases.QuickPhrase
import cn.jarryleo.android.buddy.phrases.QuickPhrasesService
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
     *
     * 首次安装(列表为空)时自动写入默认短语,保证用户首次打开设置面板时
     * 看到一份带颜色、可直接使用的快捷短语。
     * 之后再被用户清空时不会复活(用户清空是主动行为,应当尊重)。
     */
    fun loadPhrases() {
        val saved = QuickPhrasesService.getInstance().list()
        if (saved.isEmpty()) {
            // 首次安装:写入默认短语
            val defaults = DefaultPhrases.build()
            QuickPhrasesService.getInstance().replaceAll(defaults)
        }
        val finalList = QuickPhrasesService.getInstance().list()
        ui.quickPhrases.clear()
        ui.quickPhrases.addAll(finalList)
    }

    /**
     * 新增一条短语:进入编辑态,分配新 id,标题/文本都为空,
     * 颜色默认 null,isDeletable = true。点 Save 时才真正落库。
     */
    fun beginAdd() {
        ui.editingPhrase = QuickPhrase(
            id = UUID.randomUUID().toString(),
            title = "",
            text = "",
            color = null,
            isDeletable = true,
        )
    }

    /**
     * 进入编辑态:把要编辑的条目复制一份到 [ui.editingPhrase],
     * 保留 id 和 isDeletable,改完 Save 时按 id upsert。
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
        // 保留 isDeletable(用户编辑已有内置短语时不应改变其"不可删"属性)
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
     * 默认短语(isDeletable=false)不允许删除,直接返回 false。
     *
     * @return true 表示已删除;false 表示条目不可删或不存在。
     */
    fun delete(phrase: QuickPhrase): Boolean {
        if (!phrase.isDeletable) return false
        QuickPhrasesService.getInstance().delete(phrase.id)
        ui.quickPhrases.removeAll { it.id == phrase.id }
        if (ui.editingPhrase?.id == phrase.id) {
            ui.editingPhrase = null
        }
        return true
    }

    /**
     * 拖拽重排:把 [fromIndex] 处的元素移到 [toIndex]。
     * 越界或 from == to 时 no-op。
     */
    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val size = ui.quickPhrases.size
        if (fromIndex !in 0 until size || toIndex !in 0 until size) return
        QuickPhrasesService.getInstance().move(fromIndex, toIndex)
        val item = ui.quickPhrases.removeAt(fromIndex)
        ui.quickPhrases.add(toIndex, item)
    }

    /**
     * 重置出厂默认:
     * - 删掉所有 isDeletable = false 的内置短语;
     * - 重新插入 [DefaultPhrases.build] 产生的最新默认(可能 id 变化,但内容一致);
     * - 用户自建(isDeletable = true)的条目保留,追加在默认之后。
     *
     * 这样保证用户改过的默认文字 / 颜色会回到出厂值,但自建的不丢。
     */
    fun resetDefaults() {
        val service = QuickPhrasesService.getInstance()
        val userPhrases = ui.quickPhrases.filter { it.isDeletable }
        // 删除所有内置条目(按 id)
        val builtinIds = ui.quickPhrases.filter { !it.isDeletable }.map { it.id }
        builtinIds.forEach { service.delete(it) }
        // 重新插入默认 + 保留用户自建
        val rebuilt = DefaultPhrases.build() + userPhrases
        service.replaceAll(rebuilt)
        // 同步 UI
        ui.quickPhrases.clear()
        ui.quickPhrases.addAll(rebuilt)
        // 退出编辑态(避免编辑态里的 id 引用了已删除的内置条目)
        ui.editingPhrase = null
    }
}
