package cn.jarryleo.insert_strings

import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import cn.jarryleo.insert_strings.xml.StringsInfo
import cn.jarryleo.insert_strings.xml.StringsScanner
import cn.jarryleo.insert_strings.xml.StringsWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class InsertStringsManager(private val project: Project) {

    companion object {
        private val instance = mutableMapOf<Project, InsertStringsManager>()

        @JvmStatic
        fun getInstance(project: Project): InsertStringsManager {
            return instance.getOrPut(project) {
                InsertStringsManager(project)
            }
        }

        @JvmStatic
        fun updateUI(
            project: Project,
            entries: List<KeyedStringsInfo>
        ) {
            getInstance(project).updateUI(entries)
        }
    }

    private var entries: List<KeyedStringsInfo> = emptyList()
    val keys get() = entries.map { it.key }
    val languages get() = entries.firstOrNull()?.stringsInfoList?.map { it.language }
    private var uiCallBack: UiCallback? = null

    init {
        ContextManager.initContextInfo(project)
    }

    fun setUiCallBack(uiCallBack: UiCallback) {
        this.uiCallBack = uiCallBack
        if (entries.isNotEmpty()) {
            uiCallBack.updateUI(entries)
        }
    }

    fun updateUI(entries: List<KeyedStringsInfo>) {
        this.entries = entries
        uiCallBack?.updateUI(entries)
    }

    fun insert(project: Project, translationsPerKey: Map<String, Map<String, String>>) {
        val languagesInfoList = entries.firstOrNull()?.stringsInfoList ?: emptyList()
        val anchor = entries.firstOrNull()?.anchorNodeName ?: ""
        StringsWriter(
            project,
            translationsPerKey.keys.toList(),
            translationsPerKey,
            languagesInfoList,
            anchor
        ).write()
    }

    fun insertIntoModule(
        project: Project,
        moduleName: String,
        translationsPerKey: Map<String, Map<String, String>>
    ) {
        val moduleStringsInfo = ContextManager.getModuleStringsInfo(project, moduleName)
        StringsWriter(
            project,
            translationsPerKey.keys.toList(),
            translationsPerKey,
            moduleStringsInfo,
            ""
        ).write()
    }

    fun copy() {
        val clipEntries = entries.map { entry ->
            ClipEntry(
                entry.key,
                entry.stringsInfoList.associate { it.language to it.text }
            )
        }
        val anchor = entries.firstOrNull()?.anchorNodeName ?: ""
        ClipboardManager.setSysClipboardText(ClipInfo(clipEntries, anchor).toJson())
    }

    fun paste(file: VirtualFile): Boolean {
        val text = ClipboardManager.getSysClipboardText()
        val clipInfo = ClipInfo.fromJson(text) ?: return false
        val keys = clipInfo.entries.map { it.key }
        if (keys.isEmpty()) return false
        val scanner = StringsScanner(file, keys)
        val newEntries = scanner.getMultiKeyStringsInfoList().map { entry ->
            val clipEntry = clipInfo.entries.find { it.key == entry.key }
            if (clipEntry != null) {
                entry.stringsInfoList.forEach { info ->
                    info.text = clipEntry.translations[info.language] ?: info.text
                }
            }
            KeyedStringsInfo(
                entry.key,
                clipInfo.anchor,
                entry.stringsInfoList
            )
        }
        updateUI(newEntries)
        return true
    }

    fun getEntries(): List<KeyedStringsInfo> = entries
}
