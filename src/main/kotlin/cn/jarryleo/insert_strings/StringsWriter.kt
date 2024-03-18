package cn.jarryleo.insert_strings

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 写入strings到xml文件
 */
class StringsWriter(
    private val project: Project,
    private val anchorName: String,
    private val nodeName: String,
    private val stringsInfoList: Map<String, String>,
    private val languagesInfoList: List<StringsInfo>
) {

    fun write() {
        val defaultText = stringsInfoList.getOrDefault("values", "")
        languagesInfoList.forEach { stringsInfo ->
            val xmlFile = stringsInfo.stringsFile
            var text = languagesInfoList.find { it.language == stringsInfo.language }?.let {
                stringsInfoList.getOrDefault(it.language, defaultText)
            } ?: run {
                defaultText
            }
            if (text.isEmpty()) {
                text = defaultText
            }
            val node = "<string name=\"$nodeName\">$text</string>"
            writeToXml(xmlFile, nodeName, node)
        }
    }

    /**
     * 写入strings到xml文件，如果strings节点已存在，则更新，否则插入
     */
    private fun writeToXml(xmlFile: VirtualFile, name: String, node: String) {
        val document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val xml = document.text
            val start = xml.indexOf("<string name=\"$name\">")
            val end = xml.indexOf("</string>", start) + "</string>".length
            if (start != -1 && end != -1) {
                document.replaceString(start, end, node)
            } else {
                //查找插入位置为 anchorName 节点的下面
                val anchorStart = xml.indexOf("<string name=\"$anchorName\">")
                val anchorEnd = xml.indexOf("</string>", anchorStart) + "</string>".length
                if (anchorStart != -1 && anchorEnd != -1) {
                    document.insertString(anchorEnd, "\n\t$node")
                } else {
                    //查找插入位置为 </resources> 节点的上面
                    val insertIndex = xml.indexOf("</resources>")
                    if (insertIndex != -1) {
                        //插入新的节点
                        document.insertString(insertIndex, "\t$node\n")
                    }
                }
            }
        }
    }
}