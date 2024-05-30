package cn.jarryleo.insert_strings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class InsertStringsManager {

    companion object {
        private val instance = mutableMapOf<Project, InsertStringsManager>()

        @JvmStatic
        fun getInstance(project: Project): InsertStringsManager {
            return instance.getOrPut(project) {
                InsertStringsManager()
            }
        }

        @JvmStatic
        fun updateUI(
            project: Project,
            nodeName: String,
            anchorNodeName: String,
            stringsList: List<StringsInfo>?
        ) {
            getInstance(project).updateUI(nodeName, anchorNodeName, stringsList)
        }
    }

    private var nodeName = ""
    private var anchorNodeName = ""
    private var stringsList: List<StringsInfo>? = emptyList()
    val languages get() = stringsList?.map { it.language }
    private var uiCallBack: UiCallback? = null

    fun setUiCallBack(uiCallBack: UiCallback) {
        this.uiCallBack = uiCallBack
    }

    fun updateUI(nodeName: String, anchorNodeName: String, stringsList: List<StringsInfo>?) {
        this.nodeName = nodeName
        this.anchorNodeName = anchorNodeName
        this.stringsList = stringsList
        uiCallBack?.updateUI(nodeName, stringsList)
    }

    fun insert(project: Project, stringName: String, stringsInfoList: Map<String, String>) {
        StringsWriter(
            project,
            nodeName,
            anchorNodeName,
            stringName,
            stringsInfoList,
            stringsList ?: emptyList()
        ).write()
    }

    fun copy() {
        val clipInfo = ClipInfo(
            nodeName,
            anchorNodeName,
            stringsList?.associate { it.language to it.text } ?: emptyMap()
        )
        //CopyPasteManager.copyTextToClipboard(clipInfo.toXml())
        ClipboardManager.setSysClipboardText(clipInfo.toXml())
    }

    fun paste(file: VirtualFile) {
        val text = ClipboardManager.getSysClipboardText()
        val clipInfo = ClipInfo.fromXml(text) ?: return
        val nodeName = clipInfo.node
        val anchor = clipInfo.anchor
        val languages = clipInfo.value.keys.toList()
        val scanner = StringsScanner(file, nodeName)
        scanner.getStringsInfoList().filter {
            it.language in languages
        }.forEach {
            it.text = clipInfo.value[it.language] ?: ""
        }
        updateUI(
            nodeName,
            anchor,
            scanner.getStringsInfoList()
        )
    }
}