package cn.jarryleo.demo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class TestDialogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        showDialog()
    }

    private fun showDialog() {
        TestDialog.showDialog()
    }

    private fun showTips() {
        Messages.showMessageDialog(
            "Hello World!",
            "Information",
            Messages.getInformationIcon()
        )
    }
}
