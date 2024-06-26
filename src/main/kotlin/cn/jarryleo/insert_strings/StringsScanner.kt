package cn.jarryleo.insert_strings

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

/**
 * 通过用户点击的位置，扫描strings文件，并获取相关信息
 */
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
    private var selectText = "" //选中文本
    private var currentLineText = "" //当前行文本
    private var nextNodeLineText = "" //下一个节点的一行文本
    val nodeName by lazy {  //当前节点名称
        getStringName()
    }
    val anchorNodeName by lazy {  //当前锚点节点名称，当前鼠标的下一个节点
        getNodeName(nextNodeLineText)
    }

    //多语言stings.xml文件信息
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
                //查找名为 strings 的文件，
                val stringsFile = valuesDir.children
                    .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }
                //找到语言目录下的strings.xml文件，且含有指定的key
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
                    //查找values目录下的所有xml文件，看看有没有 <string> 标签且含有指定key
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
                        //如果没有找到含有指定key的xml文件，则取strings.xml文件
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
                            //如果没有找到含有指定key的xml文件，则取values目录下的第一个含有<string>标签的xml文件
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

    /**
     * 扫描文件，如果是xml文件且含有 <string> 标签，则返回
     */
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
        return xml.contains("<string name=\"$key\">")
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
    private fun getStringName(): String {
        if (selectText.isNotEmpty()) {
            return selectText
        }
        return getNodeName(currentLineText)
    }

    private fun getNodeName(text: String): String {
        val regex = "<string name=\"(.*?)\">".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.groupValues?.get(1) ?: ""
    }

    private fun getNodeText(text: String, key: String): String {
        val regex = "<string name=\"$key\">(.*?)</string>".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.groupValues?.get(1) ?: ""
    }

    fun getLanguageList(): List<String> {
        return stringsInfoList.map { it.language }
    }
}