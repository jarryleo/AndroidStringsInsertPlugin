package cn.jarryleo.insert_strings.xml

/**
 * 项目国际化上下文信息，用于以 JSON 格式传递给 AI
 */
data class ContextInfo(
    val projectName: String,
    val currentModule: ModuleInfo?,
    val modules: List<ModuleInfo>,
) {
    val moduleWithMostLines: ModuleInfo? = modules.maxByOrNull { it.totalLines }

    /**
     * 推荐默认插入模块。
     *
     * 决策思路(对应产品需求 1-3):
     * 1) 优先使用 [currentModule] — 用户当前操作文件所在的模块就是他的工作上下文,绝大多数情况下就是他想插入的模块。
     * 2) currentModule 缺失(未打开 Android 文件/在非 strings.xml 文件)或「明显不够用」时,
     *    才退回到「语种数量最多 + 行数最多」的模块。
     * 「明显不够用」= 语种数或行数显著低于项目中最强模块,说明这是一个测试/小工具/边缘模块,
     * 不适合作为默认插入目标。
     */
    val recommendedDefaultModule: ModuleInfo? = computeRecommendedDefault()

    private fun computeRecommendedDefault(): ModuleInfo? {
        val strongest = modules
            .maxByOrNull { it.xmlFiles.size * 1_000_000 + it.totalLines }
            ?: return null
        val cur = currentModule
        if (cur == null) return strongest
        if (cur.moduleName == strongest.moduleName) return cur
        val strongestLangCount = strongest.xmlFiles.size
        val curLangCount = cur.xmlFiles.size
        // currentModule 语种数太弱( < 最强的 60% )→ 退回 strongest
        if (strongestLangCount > 0 && curLangCount < strongestLangCount * 0.6) {
            return strongest
        }
        // currentModule 行数太弱( < 最强的 50% )→ 退回 strongest
        if (strongest.totalLines > 0 && cur.totalLines < strongest.totalLines * 0.5) {
            return strongest
        }
        return cur
    }

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
