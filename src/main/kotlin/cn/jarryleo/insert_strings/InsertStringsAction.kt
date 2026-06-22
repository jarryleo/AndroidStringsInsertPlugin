package cn.jarryleo.insert_strings

import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.StringsScanner
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class InsertStringsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val stringsScanner = StringsScanner(e)
        val xml = stringsScanner.isXml()
        e.presentation.isEnabledAndVisible = xml
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stringsScanner = StringsScanner(e)
        val xml = stringsScanner.isXml()
        if (xml) {
            val entries = stringsScanner.getMultiKeyStringsInfoList()
            val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.getData(CommonDataKeys.EDITOR)?.virtualFile
            val context = ContextManager.getInstance(project)
            context.ensureInitialized()
            context.updateCurrentModule(currentFile)
            InsertStringsManager.updateUI(project, entries)
            ToolWindowManager.getInstance(project)
                .getToolWindow("InsertStrings")
                ?.show()
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
