package cn.jarryleo.android.buddy.ui

import cn.jarryleo.android.buddy.InsertStringsManager
import cn.jarryleo.android.buddy.ai.AITranslator
import cn.jarryleo.android.buddy.xml.KeyedStringsInfo
import cn.jarryleo.android.buddy.xml.StringsInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 主面板的核心动作:Copy / Paste / Insert / 切换 key / 保存编辑 / AI 单行翻译 / Toast。
 *
 * 拆分理由:这块是「主表编辑器」的核心交互,频繁读写 keyEntries / rows,独立成类便于复用和测试。
 */
internal class InsertStringsActionsController(
    private val ui: InsertStringsUI,
) {

    private val project: Project get() = ui.project
    private val manager: InsertStringsManager get() = ui.insertStringsManager

    fun copy() {
        saveCurrentEdits()
        manager.copy()
        showToast("Copied ${ui.keyEntries.size} key(s)")
    }

    fun paste() {
        val selectedEditor = FileEditorManager.getInstance(project).selectedEditor
        if (selectedEditor == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        if (manager.paste(selectedEditor.file)) {
            showToast("Pasted")
        } else {
            showToast("Nothing to paste")
        }
    }

    fun insert() {
        val languages = manager.languages
        if (languages == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        saveCurrentEdits()
        if (ui.keyEntries.isEmpty()) {
            Messages.showMessageDialog(
                "No key to insert!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        val translationsPerKey = ui.keyEntries.associate { entry ->
            entry.key to entry.stringsInfoList.associate { it.language to it.text }
        }
        manager.insert(
            project = project,
            translationsPerKey = translationsPerKey
        )
        showToast("Inserted ${ui.keyEntries.size} key(s)")
    }

    fun selectKey(index: Int) {
        if (index == ui.selectedKeyIndex) return
        if (index !in ui.keyEntries.indices) return
        saveCurrentEdits()
        ui.selectedKeyIndex = index
        updateRowsForSelectedKey()
    }

    fun saveCurrentEdits() {
        if (ui.selectedKeyIndex !in ui.keyEntries.indices) return
        val entry = ui.keyEntries[ui.selectedKeyIndex]
        val updatedTexts = ui.rows.associate { it.language to it.text }
        val updatedInfoList = entry.stringsInfoList.map { info ->
            if (updatedTexts.containsKey(info.language)) {
                StringsInfo(info.stringsFile, info.language, info.key, updatedTexts[info.language] ?: "")
            } else {
                info
            }
        }
        ui.keyEntries[ui.selectedKeyIndex] = KeyedStringsInfo(
            key = ui.stringName,
            anchorNodeName = entry.anchorNodeName,
            stringsInfoList = updatedInfoList
        )
    }

    fun updateRowsForSelectedKey() {
        val entry = ui.keyEntries.getOrNull(ui.selectedKeyIndex) ?: return
        ui.stringName = entry.key
        val seen = mutableSetOf<String>()
        val newRows = entry.stringsInfoList
            .filter { it.language.isNotEmpty() && seen.add(it.language) }
            .map { StringRow(language = it.language, text = it.text) }
        ui.rows.clear()
        ui.rows.addAll(newRows)
    }

    fun updateRowText(rowIndex: Int, text: String) {
        if (rowIndex !in ui.rows.indices) return
        ui.rows[rowIndex] = ui.rows[rowIndex].copy(text = text)
    }

    /**
     * 清除主面板当前选中的所有 key 和对应的翻译表数据。
     *
     * 用途:聊天面板右上角「Clear」按钮触发(主面板专属,弹框场景不调用)。
     * 行为:
     * - 把 [InsertStringsUI.keyEntries] 清空(同时清掉聊天面板顶部的「已选择翻译(N)」面板);
     * - 把 [InsertStringsUI.rows] 清空(主面板回到未选 key 的空白表格);
     * - 重置 [InsertStringsUI.stringName] 与 [InsertStringsUI.selectedKeyIndex];
     * - 没有可清除内容时直接 no-op + 提示,避免误点产生"无变化"反馈。
     */
    fun clearSelected() {
        if (ui.keyEntries.isEmpty() && ui.rows.isEmpty()) {
            showToast("没有可清除的选中内容")
            return
        }
        val clearedKeys = ui.keyEntries.size
        ui.keyEntries.clear()
        ui.rows.clear()
        ui.stringName = ""
        ui.selectedKeyIndex = 0
        showToast("已清除 $clearedKeys 个 key")
    }

    fun translateRow(rowIndex: Int) {
        if (rowIndex !in ui.rows.indices) return
        saveCurrentEdits()
        val targetLangRaw = ui.rows[rowIndex].language
        val targetLanguage = targetLangRaw.let {
            if (it.equals("values", ignoreCase = true)) "values-en" else it
        }
        val currentKey = ui.keyEntries.getOrNull(ui.selectedKeyIndex)?.key ?: return
        val items = ui.keyEntries.mapNotNull { entry ->
            val sourceText = entry.stringsInfoList.firstOrNull { it.text.isNotEmpty() }?.text
                ?: return@mapNotNull null
            entry.key to sourceText
        }
        if (items.isEmpty()) return

        updateRowText(rowIndex, "Translating...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.translateBatch(targetLanguage, items)
            SwingUtilities.invokeLater {
                val nowKey = ui.keyEntries.getOrNull(ui.selectedKeyIndex)?.key
                if (nowKey == currentKey && rowIndex in ui.rows.indices) {
                    updateRowText(rowIndex, result[currentKey] ?: "")
                }
                ui.keyEntries.forEachIndexed { index, entry ->
                    val translated = result[entry.key] ?: return@forEachIndexed
                    val newInfoList = entry.stringsInfoList.map { info ->
                        if (info.language == targetLangRaw) {
                            StringsInfo(info.stringsFile, info.language, info.key, translated)
                        } else {
                            info
                        }
                    }
                    ui.keyEntries[index] = KeyedStringsInfo(
                        entry.key, entry.anchorNodeName, newInfoList
                    )
                }
            }
        }
    }

    fun showToast(message: String) {
        ui.toastTimer?.stop()
        ui.toastMessage = message
        ui.toastTimer = Timer(1800) {
            ui.toastMessage = ""
        }.apply {
            isRepeats = false
            start()
        }
    }
}
