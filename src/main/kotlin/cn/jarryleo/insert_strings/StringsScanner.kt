package cn.jarryleo.insert_strings

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

/**
 * 通过用户点击的位置，扫描strings文件，并获取相关信息
 */
class StringsScanner(private val actionEvent: AnActionEvent) {

    private var currentFile: VirtualFile? = null
    private var isXmlFile = false
    private var selectText = ""
    private var currentLineText = ""
    private val stringsInfoList = ArrayList<StringsInfo>()

    init {
        actionEvent.getData(CommonDataKeys.EDITOR)?.let { editor ->
            currentFile = editor.virtualFile
            isXmlFile = currentFile?.extension == "xml"
            selectText = editor.selectionModel.selectedText ?: ""
            val lineStartPosition = editor.caretModel.visualLineStart
            val lineEndPosition = editor.caretModel.visualLineEnd
            currentLineText = editor.document.getText(TextRange(lineStartPosition, lineEndPosition))
            //如果currentLineText 为空，则获取上一行文本
            var line = editor.caretModel.logicalPosition.line
            while (currentLineText.trim().isEmpty() && line > 0) {
                line--
                val lineStartOffset = editor.document.getLineStartOffset(line)
                val lineEndOffset = editor.document.getLineEndOffset(line)
                currentLineText = editor.document.getText(TextRange(lineStartOffset, lineEndOffset))
            }
        } ?: run {
            actionEvent.getData(CommonDataKeys.PSI_FILE)?.let { psiFile ->
                currentFile = psiFile.virtualFile
                isXmlFile = currentFile?.extension == "xml"
            }
        }
        scanResDir()
    }

    private fun scanResDir() {
        val resDir = currentFile?.parent?.parent ?: return
        if (resDir.name != "res") {
            return
        }
        resDir.children
            .filter { it.isDirectory && it.name.startsWith("values") }
            .forEach { valuesDir ->
                //查找名为 strings 的文件，
                val stringsFile = valuesDir.children
                    .find { it.name.contains("strings", ignoreCase = true) } ?: scanFile(valuesDir)
                if (stringsFile != null) {
                    stringsInfoList.add(
                        StringsInfo(
                            stringsFile,
                            valuesDir.name.removePrefix("values-")
                        )
                    )
                }
            }
    }

    /**
     * 扫描valuesDir下所有文件的内容，看看节点有没有 <string> 标签，有的话返回此文件
     */
    private fun scanFile(valuesDir: VirtualFile): VirtualFile? {
        valuesDir.children
            .forEach { stringsFile ->
                // 找不到的话就要扫描文件内容，看看节点有没有 <string> 标签
                stringsFile.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains("<string")) {
                            return stringsFile
                        }
                    }
                }
            }
        return null
    }

    fun isXml(): Boolean {
        return isXmlFile
    }

    fun getSelectText(): String {
        return selectText
    }

    fun getCurrentLineText(): String {
        return currentLineText
    }

    fun getCurrentFilePath(): String {
        return currentFile?.path ?: ""
    }

    fun getStringsInfoList(): List<StringsInfo> {
        return stringsInfoList
    }

    /**
     * 通过正则获取文本 <string name="app_name">text</string> 中的 app_name
     */
    fun getStringName(): String {
        if (selectText.isNotEmpty()) {
            return selectText
        }
        val regex = "<string name=\"(.*?)\">".toRegex()
        val matchResult = regex.find(currentLineText)
        return matchResult?.groupValues?.get(1) ?: ""
    }

    fun getLanguageList(): List<String> {
        return stringsInfoList.map { it.language }
    }
}