package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class StringsWriter(
    private val project: Project,
    private val nodeName: String,
    private val anchorName: String,
    private val stringName: String,
    private val stringsInfoList: Map<String, String>,
    private val languagesInfoList: List<StringsInfo>
) {

    fun write() {
        val defaultText = stringsInfoList.getOrDefault("values", "")
        languagesInfoList.forEach { stringsInfo ->
            val xmlFile = stringsInfo.stringsFile ?: return
            var text = languagesInfoList.find { it.language == stringsInfo.language }?.let {
                stringsInfoList.getOrDefault(it.language, defaultText)
            } ?: run {
                defaultText
            }
            if (text.isEmpty()) {
                text = defaultText
            }
            val escapedText = AndroidStringEscaper.escape(text)
            val node = "<string name=\"$stringName\">$escapedText</string>"
            writeToXml(xmlFile, stringName, node)
        }
    }

    private fun writeToXml(xmlFile: VirtualFile, name: String, node: String) {
        val document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val xml = document.text
            val start = xml.indexOf("<string name=\"$name\">")
            val end = xml.indexOf("</string>", start) + "</string>".length
            if (start != -1 && end != -1) {
                document.replaceString(start, end, node)
            } else {
                if (nodeName.isNotEmpty()) {
                    val nodeStart = xml.indexOf("<string name=\"$nodeName\">")
                    if (nodeStart != -1) {
                        val nodeEnd = xml.indexOf("</string>", nodeStart) + "</string>".length
                        if (nodeEnd != -1) {
                            document.insertString(nodeEnd, "\n\t$node")
                            return@runWriteCommandAction
                        }
                    }
                }

                val anchorStart = xml.indexOf("<string name=\"$anchorName\">")
                if (anchorStart != -1) {
                    document.insertString(anchorStart, "$node\n\t")
                } else {
                    val insertIndex = xml.indexOf("</resources>")
                    if (insertIndex != -1) {
                        document.insertString(insertIndex, "\t$node\n")
                    }
                }
            }
        }
    }
}
