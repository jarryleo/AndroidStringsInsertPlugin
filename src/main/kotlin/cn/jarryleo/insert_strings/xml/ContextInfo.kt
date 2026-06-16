package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * strings相关上下文
 */
data class ContextInfo(
    val moduleList: List<ModuleInfo>, //模块列表
) {
    fun getJson(): String {
        return "" //TODO 完善对象转json
    }
}

data class ModuleInfo(
    val moduleName: String,  //模块名称
    val xmlFileList: List<XmlFileInfo> //模块内所有 strings.xml 文件信息
)

data class XmlFileInfo(
    val stringsFile: VirtualFile?, //对应的 strings.xml文件
    val language: String, //对应的国际化语言缩写
    var fileLines: Int = 0, //strings.xml 的文件行数
    val selectedStrings: List<StringsInfo> = emptyList(), //选择的字符串
) {

    init {
        stringsFile?.let {
            val document = FileDocumentManager.getInstance().getDocument(it)
            fileLines = document?.lineCount ?: 0
        }
    }
}
