package cn.jarryleo.insert_strings

import com.intellij.openapi.project.Project

object InsertStringsManager : OnStringsInsertListener {
    var nodeName = ""
    var anchorNodeName = ""
    var languages: List<String>? = emptyList()
    var stringsList: List<StringsInfo>? = emptyList()
    var uiCallBack: UiCallback? = null

    @JvmStatic
    fun updateUI(nodeName: String, anchorNodeName: String, languages: List<String>?, stringsList: List<StringsInfo>?) {
        this.nodeName = nodeName
        this.anchorNodeName = anchorNodeName
        this.languages = languages
        this.stringsList = stringsList
        uiCallBack?.updateUI(nodeName, languages, stringsList)
    }

    override fun onInsert(project: Project, stringName: String, stringsInfoList: Map<String, String>) {
        StringsWriter(
            project,
            nodeName,
            anchorNodeName,
            stringName,
            stringsInfoList,
            stringsList ?: emptyList()
        ).write()
    }
}