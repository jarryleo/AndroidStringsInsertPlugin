package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

@Suppress("unused")
class StringsScanner(
    private val actionEvent: AnActionEvent? = null,
    file: VirtualFile? = null,
    private val nodeText: String? = null,
) {

    constructor(actionEvent: AnActionEvent?) : this(actionEvent, null)

    constructor(file: VirtualFile?, nodeText: String?) : this(null, file, nodeText)

    private var currentFile: VirtualFile? = null
    private var isXmlFile = false
    private var selectText = ""
    private var currentLineText = ""
    private var nextNodeLineText = ""
    val nodeName by lazy {
        getStringName()
    }
    val anchorNodeName by lazy {
        getNodeName(nextNodeLineText)
    }

    private val stringsInfoList = ArrayList<StringsInfo>()

    init {
        actionEvent?.let {
            getStringsFromEvent()
        }
        file?.let {
            currentFile = it
            selectText = nodeText ?: ""
            isXmlFile = currentFile?.extension == "xml"
        }
        scanResDir()
    }

    private fun getStringsFromEvent() {
        actionEvent?.getData(CommonDataKeys.EDITOR)?.let { editor ->
            currentFile = editor.virtualFile
            isXmlFile = currentFile?.extension == "xml"
            selectText = editor.selectionModel.selectedText ?: ""
            val lineStartPosition = editor.caretModel.visualLineStart
            val lineEndPosition = editor.caretModel.visualLineEnd
            currentLineText = editor.document.getText(TextRange(lineStartPosition, lineEndPosition))
            val line = editor.caretModel.logicalPosition.line
            var nextLine = line + 1
            val lineCount = editor.document.lineCount
            while (nextLine < lineCount) {
                val lineStartOffset = editor.document.getLineStartOffset(nextLine)
                val lineEndOffset = editor.document.getLineEndOffset(nextLine)
                nextNodeLineText = editor.document.getText(TextRange(lineStartOffset, lineEndOffset))
                if (getNodeName(nextNodeLineText).isNotEmpty()) {
                    break
                }
                nextLine++
            }
        } ?: run {
            actionEvent?.getData(CommonDataKeys.PSI_FILE)?.let { psiFile ->
                currentFile = psiFile.virtualFile
                isXmlFile = currentFile?.extension == "xml"
            }
        }
    }

    private fun scanResDir() {
        val resDir = currentFile?.parent?.parent ?: return
        if (resDir.name != "res") {
            return
        }
        resDir.children
            .filter { it.isDirectory && it.name.startsWith("values") }
            .forEach { valuesDir ->
                val stringsFile = valuesDir.children
                    .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }
                if (stringsFile != null && checkXmlFileContainsKey(stringsFile, nodeName)) {
                    stringsInfoList.add(
                        StringsInfo(
                            stringsFile,
                            valuesDir.name,
                            getStringName(),
                            getTextFromStringsXml(stringsFile)
                        )
                    )
                } else {
                    val virtualFileList = valuesDir.children
                        .filter { it.extension == "xml" }
                        .filter { checkContainsStrings(it) }
                    val xmlFile = virtualFileList
                        .firstOrNull {
                            checkXmlFileContainsKey(it, nodeName)
                        }
                    if (xmlFile != null) {
                        stringsInfoList.add(
                            StringsInfo(
                                xmlFile,
                                valuesDir.name,
                                getStringName(),
                                getTextFromStringsXml(xmlFile)
                            )
                        )
                    } else {
                        if (stringsFile != null) {
                            stringsInfoList.add(
                                StringsInfo(
                                    stringsFile,
                                    valuesDir.name,
                                    getStringName(),
                                    getTextFromStringsXml(stringsFile)
                                )
                            )
                        } else {
                            val firstXmlFile = virtualFileList.firstOrNull()
                            if (firstXmlFile != null) {
                                stringsInfoList.add(
                                    StringsInfo(
                                        firstXmlFile,
                                        valuesDir.name,
                                        getStringName(),
                                        getTextFromStringsXml(firstXmlFile)
                                    )
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun checkContainsStrings(virtualFile: VirtualFile): Boolean {
        virtualFile.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.contains("<string")) {
                    return true
                }
            }
        }
        return false
    }

    private fun getTextFromStringsXml(xmlFile: VirtualFile): String {
        val key = getStringName()
        val document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: return ""
        val xml = document.text
        return getNodeText(xml, key)
    }

    private fun checkXmlFileContainsKey(xmlFile: VirtualFile, key: String): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: return false
        val xml = document.text
        return findStringNode(xml, key) != null
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

    private fun getStringName(): String {
        if (selectText.isNotEmpty()) {
            return selectText
        }
        return getNodeName(currentLineText)
    }

    private fun getNodeName(text: String): String {
        val regex = """<string\b[^>]*\bname\s*=\s*(['"])(.*?)\1""".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.groupValues?.get(2) ?: ""
    }

    private fun getNodeText(text: String, key: String): String {
        return findStringNode(text, key)?.groupValues?.get(2) ?: ""
    }

    private fun findStringNode(text: String, key: String): MatchResult? {
        if (key.isEmpty()) return null
        val escapedKey = Regex.escape(key)
        val regex = """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>([\s\S]*?)</string>""".toRegex()
        return regex.find(text)
    }

    fun getLanguageList(): List<String> {
        return stringsInfoList.map { it.language }
    }
}
