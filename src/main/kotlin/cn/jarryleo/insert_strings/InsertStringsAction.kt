package cn.jarryleo.insert_strings

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class InsertStringsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val stringsScanner = StringsScanner(e)
        val xml = stringsScanner.isXml()
        if (xml) {
            val nodeName = stringsScanner.nodeName
            val anchorNodeName = stringsScanner.anchorNodeName
            InsertStringsDialog.showDialog(
                nodeName,
                stringsScanner.getLanguageList().toTypedArray(),
                object : OnStringsInsertListener {
                    override fun onInsert(stringName: String, stringsInfoList: Map<String, String>) {
                        val project = e.project ?: return
                        StringsWriter(
                            project,
                            anchorNodeName,
                            stringName,
                            stringsInfoList,
                            stringsScanner.getStringsInfoList()
                        ).write()
                    }
                }
            )
        } else {
            showTips("Please select a string in the xml file.")
        }
    }

    private fun showTips(tips: String, title: String = "Information") {
        Messages.showMessageDialog(
            tips,
            title,
            Messages.getInformationIcon()
        )
    }
}
