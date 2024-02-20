package cn.jarryleo.demo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class InsertStringsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        showDialog()
    }

    private fun showDialog() {
        InsertStringsDialog.showDialog()
    }

    private fun showTips() {
        Messages.showMessageDialog(
            "Hello World!",
            "Information",
            Messages.getInformationIcon()
        )
    }
}
