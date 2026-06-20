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

    constructor(file: VirtualFile?, keys: List<String>) : this(null, file, null) {
        selectedKeys.clear()
        keys.map { it.trim() }.filter { it.isNotEmpty() }.forEach { selectedKeys.add(it) }
        multiKeyInfoList.clear()
        scanResDir()
    }

    private var currentFile: VirtualFile? = null
    private var isXmlFile = false
    private var selectText = ""
    private var currentLineText = ""
    private var nextNodeLineText = ""
    private var selectionEndLine: Int = -1

    private val selectedKeys = mutableListOf<String>()

    val nodeName by lazy {
        selectedKeys.firstOrNull() ?: ""
    }
    val anchorNodeName by lazy {
        computeAnchorKey()
    }

    private val stringsInfoList = ArrayList<StringsInfo>()
    private val multiKeyInfoList = mutableListOf<KeyedStringsInfo>()

    init {
        actionEvent?.let {
            getStringsFromEvent()
        }
        file?.let {
            currentFile = it
            selectText = nodeText ?: ""
            isXmlFile = currentFile?.extension == "xml"
        }
        parseSelectedKeys()
        scanResDir()
    }

    private fun getStringsFromEvent() {
        actionEvent?.getData(CommonDataKeys.EDITOR)?.let { editor ->
            currentFile = editor.virtualFile
            isXmlFile = currentFile?.extension == "xml"
            selectText = editor.selectionModel.selectedText ?: ""
            selectionEndLine = editor.document.getLineNumber(editor.selectionModel.selectionEnd)
            val lineStartPosition = editor.caretModel.visualLineStart
            val lineEndPosition = editor.caretModel.visualLineEnd
            currentLineText = editor.document.getText(TextRange(lineStartPosition, lineEndPosition))
        } ?: run {
            actionEvent?.getData(CommonDataKeys.PSI_FILE)?.let { psiFile ->
                currentFile = psiFile.virtualFile
                isXmlFile = currentFile?.extension == "xml"
            }
        }
    }

    /**
     * 从选中文本或当前行解析出所有选中的 string key。
     * 选中文本包含多个 <string name="..."> 时，提取全部 key；
     * 选中文本只是纯 key 名（不含 < 标签）时，按单 key 处理（向后兼容）；
     * 无选中文本时，取当前行所在 <string> 的 key。
     */
    private fun parseSelectedKeys() {
        selectedKeys.clear()
        if (selectText.isNotEmpty()) {
            val regex = """<string\b[^>]*\bname\s*=\s*(['"])(.*?)\1""".toRegex()
            regex.findAll(selectText).forEach { m ->
                val k = m.groupValues[2]
                if (k.isNotEmpty() && selectedKeys.none { it == k }) {
                    selectedKeys.add(k)
                }
            }
            if (selectedKeys.isEmpty()) {
                val trimmed = selectText.trim()
                if (trimmed.isNotEmpty() && !trimmed.contains('<')) {
                    selectedKeys.add(trimmed)
                }
            }
        }
        if (selectedKeys.isEmpty()) {
            val k = getNodeName(currentLineText)
            if (k.isNotEmpty()) selectedKeys.add(k)
        }
    }

    /**
     * 计算锚点 key：选区最后一行之后第一个 <string>，且不属于本次选中的 key。
     */
    private fun computeAnchorKey(): String {
        val editor = actionEvent?.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            return getNodeName(nextNodeLineText)
        }
        val doc = editor.document
        val startLine = if (selectionEndLine >= 0) selectionEndLine else editor.caretModel.logicalPosition.line
        var nextLine = startLine
        val lineCount = doc.lineCount
        while (nextLine < lineCount) {
            val ls = doc.getLineStartOffset(nextLine)
            val le = doc.getLineEndOffset(nextLine)
            val lineText = doc.getText(TextRange(ls, le))
            val k = getNodeName(lineText)
            if (k.isNotEmpty() && k !in selectedKeys) {
                return k
            }
            nextLine++
        }
        return ""
    }

    private fun scanResDir() {
        val resDir = currentFile?.parent?.parent ?: return
        if (resDir.name != "res") return
        if (selectedKeys.isEmpty()) return

        selectedKeys.forEach { key ->
            multiKeyInfoList.add(KeyedStringsInfo(key, anchorNodeName, ArrayList()))
        }

        resDir.children
            .filter { it.isDirectory && it.name.startsWith("values") }
            .forEach { valuesDir ->
                val stringsFile = valuesDir.children
                    .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }

                val targetFile = if (stringsFile != null && containsAnyKey(stringsFile)) {
                    stringsFile
                } else {
                    val virtualFileList = valuesDir.children
                        .filter { it.extension == "xml" }
                        .filter { checkContainsStrings(it) }
                    virtualFileList.firstOrNull { containsAnyKey(it) }
                        ?: stringsFile
                        ?: virtualFileList.firstOrNull()
                }

                if (targetFile != null) {
                    val document = FileDocumentManager.getInstance().getDocument(targetFile)
                    val xml = document?.text
                    if (xml != null) {
                        multiKeyInfoList.forEach { entry ->
                            val text = getNodeText(xml, entry.key)
                            (entry.stringsInfoList as ArrayList).add(
                                StringsInfo(targetFile, valuesDir.name, entry.key, text)
                            )
                        }
                    }
                }
            }

        stringsInfoList.clear()
        multiKeyInfoList.firstOrNull()?.stringsInfoList?.let { stringsInfoList.addAll(it) }
    }

    private fun containsAnyKey(xmlFile: VirtualFile): Boolean {
        return selectedKeys.any { checkXmlFileContainsKey(xmlFile, it) }
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

    fun getMultiKeyStringsInfoList(): List<KeyedStringsInfo> {
        return multiKeyInfoList
    }

    fun getSelectedKeys(): List<String> {
        return selectedKeys.toList()
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
