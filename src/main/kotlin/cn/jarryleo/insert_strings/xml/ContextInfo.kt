package cn.jarryleo.insert_strings.xml

import com.alibaba.fastjson2.JSON

/**
 * 项目国际化上下文信息，用于以 JSON 格式传递给 AI
 */
data class ContextInfo(
    val projectName: String,
    val currentModule: ModuleInfo?,
    val modules: List<ModuleInfo>,
) {
    val moduleWithMostLines: ModuleInfo? = modules.maxByOrNull { it.totalLines }

    fun getJson(): String = JSON.toJSONString(this)

    fun findModule(name: String): ModuleInfo? = modules.find {
        it.moduleName == name || it.originalModuleName == name
    }
}

/**
 * 模块信息
 */
data class ModuleInfo(
    val moduleName: String,
    val originalModuleName: String,
    val modulePath: String,
    val xmlFiles: List<XmlFileInfo>,
    val totalLines: Int = xmlFiles.sumOf { it.fileLines },
)

/**
 * strings.xml 文件信息
 */
data class XmlFileInfo(
    val filePath: String,
    val language: String,
    val fileLines: Int = 0,
)
