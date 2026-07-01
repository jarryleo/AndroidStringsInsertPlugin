package cn.jarryleo.android.buddy.xml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 将多个 key 的翻译写回各语言 strings.xml。
 *
 * 每个语言文件中，按 [keys] 顺序依次处理：
 * - 若 key 已存在，原地替换内容；
 * - 若 key 不存在，插在上一个已处理 key 之后，或锚点 key 之前，或 </resources> 之前。
 *
 * 兼容旧的单 key 调用：[nodeName] 仅用于旧签名，新逻辑以锚点和列表内前一个 key 定位。
 */
class StringsWriter(
    private val project: Project,
    private val keys: List<String>,
    private val translationsPerKey: Map<String, Map<String, String>>,
    private val languagesInfoList: List<StringsInfo>,
    private val anchorNodeName: String = "",
) {

    constructor(
        project: Project,
        @Suppress("UNUSED_PARAMETER") nodeName: String,
        anchorName: String,
        stringName: String,
        stringsInfoList: Map<String, String>,
        languagesInfoList: List<StringsInfo>
    ) : this(
        project,
        listOf(stringName),
        mapOf(stringName to stringsInfoList),
        languagesInfoList,
        anchorName
    )

    fun write() {
        val defaultTranslations = translationsPerKey.mapValues { (_, map) ->
            map.getOrDefault("values", "")
        }
        languagesInfoList.groupBy { it.language }.forEach { (_, infoList) ->
            val stringsInfo = infoList.firstOrNull() ?: return@forEach
            val xmlFile = stringsInfo.stringsFile ?: return@forEach
            writeToXml(xmlFile, stringsInfo.language, defaultTranslations)
        }
    }

    private fun writeToXml(
        xmlFile: VirtualFile,
        language: String,
        defaultTranslations: Map<String, String>
    ) {
        val document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            // 缩进在循环外算一次:插入新节点不会改变其它节点的缩进
            val indent = StringsXmlFormatter.detectIndent(document.text)
            keys.forEachIndexed { index, key ->
                val translations = translationsPerKey[key] ?: emptyMap()
                var text = translations[language]
                    ?: translations.firstNotNullOfOrNull { (lang, v) ->
                        if (lang.equals("values", ignoreCase = true)) v else null
                    }
                    ?: defaultTranslations[key]
                    ?: ""
                if (text.isEmpty()) {
                    text = defaultTranslations[key] ?: ""
                }
                val escapedText = AndroidStringEscaper.escape(text)
                val node = "<string name=\"$key\">$escapedText</string>"

                val xml = document.text
                val start = xml.indexOf("<string name=\"$key\">")
                if (start != -1) {
                    val end = xml.indexOf("</string>", start) + "</string>".length
                    if (end != -1) {
                        document.replaceString(start, end, node)
                        return@forEachIndexed
                    }
                }

                val insertPos = findInsertPosition(document.text, index)
                document.insertString(
                    insertPos,
                    StringsXmlFormatter.buildInsertLine(indent, node)
                )
            }
        }
    }

    private fun findInsertPosition(xml: String, currentIndex: Int): Int {
        if (currentIndex > 0) {
            val prevKey = keys[currentIndex - 1]
            val prevStart = xml.indexOf("<string name=\"$prevKey\">")
            if (prevStart != -1) {
                val prevEnd = xml.indexOf("</string>", prevStart) + "</string>".length
                if (prevEnd != -1) {
                    // 跳过 prev 行尾的 \n,返回下一行行首(包含缩进);
                    // 这样插入 "${indent}${node}\n" 后新行紧跟在前一行之后,无空行也无粘连。
                    return if (prevEnd < xml.length && xml[prevEnd] == '\n') prevEnd + 1 else prevEnd
                }
            }
        }
        if (anchorNodeName.isNotEmpty()) {
            val anchorStart = xml.indexOf("<string name=\"$anchorNodeName\">")
            if (anchorStart != -1) {
                // 返回 anchor 所在行的行首(包含缩进),插入新行紧邻 anchor 之前
                return xml.lastIndexOf('\n', anchorStart - 1).let { if (it < 0) 0 else it + 1 }
            }
        }
        val insertIndex = xml.indexOf("</resources>")
        return if (insertIndex != -1) insertIndex else xml.length
    }
}
