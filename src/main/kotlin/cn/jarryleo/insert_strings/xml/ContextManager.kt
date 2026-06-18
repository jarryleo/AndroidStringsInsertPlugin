package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.ArrayDeque

object ContextManager {

    var contextInfo: ContextInfo? = null
        private set

    /**
     * 初始化项目所有模块的 strings.xml 上下文信息
     */
    @JvmStatic
    fun initContextInfo(project: Project) {
        val moduleManager = ModuleManager.getInstance(project)
        val moduleInfos = moduleManager.modules.mapNotNull { module ->
            val xmlFiles = collectStringsXmlFiles(module)
            if (xmlFiles.isEmpty()) return@mapNotNull null
            ModuleInfo(
                moduleName = module.name,
                modulePath = getModuleRootPath(module),
                xmlFiles = xmlFiles,
                totalLines = xmlFiles.sumOf { it.fileLines }
            )
        }
        contextInfo = ContextInfo(
            projectName = project.name,
            currentModule = null,
            modules = moduleInfos
        )
    }

    /**
     * 确保上下文已经初始化
     */
    @JvmStatic
    fun ensureInitialized(project: Project) {
        if (contextInfo == null) {
            initContextInfo(project)
        }
    }

    /**
     * 根据当前文件更新 currentModule
     */
    @JvmStatic
    fun updateCurrentModule(project: Project, currentFile: VirtualFile?) {
        ensureInitialized(project)
        val current = currentFile?.let { buildCurrentModuleInfo(project, it) }
        contextInfo = contextInfo?.copy(currentModule = current)
    }

    /**
     * 获取指定模块的所有 strings.xml 文件信息（key/text 为空，仅用于定位文件）
     */
    @JvmStatic
    fun getModuleStringsInfo(project: Project, moduleName: String): List<StringsInfo> {
        val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName } ?: return emptyList()
        return collectStringsXml(module).map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, "", "")
        }
    }

    /**
     * 扫描指定模块中指定 key 的翻译内容
     */
    @JvmStatic
    fun scanModuleForKey(project: Project, moduleName: String, key: String): List<StringsInfo> {
        val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName } ?: return emptyList()
        return collectStringsXml(module).map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, key, getStringText(stringsFile, key))
        }
    }

    private fun buildCurrentModuleInfo(project: Project, file: VirtualFile): ModuleInfo? {
        val module = findModuleForFile(project, file) ?: return null
        val xmlFiles = collectStringsXmlFiles(module)
        if (xmlFiles.isEmpty()) return null
        return ModuleInfo(
            moduleName = module.name,
            modulePath = getModuleRootPath(module),
            xmlFiles = xmlFiles
        )
    }

    private fun findModuleForFile(project: Project, file: VirtualFile): Module? {
        return ModuleManager.getInstance(project).modules.find { belongsToModule(it, file) }
    }

    private fun belongsToModule(module: Module, file: VirtualFile): Boolean {
        val rootPath = getModuleRootPath(module)
        return rootPath.isNotBlank() && file.path.startsWith(rootPath)
    }

    private fun collectStringsXmlFiles(module: Module): List<XmlFileInfo> {
        return collectStringsXml(module).map { (valuesDir, stringsFile) ->
            XmlFileInfo(
                filePath = stringsFile.path,
                language = valuesDir.name,
                fileLines = getFileLines(stringsFile)
            )
        }
    }

    private fun collectStringsXml(module: Module): List<Pair<VirtualFile, VirtualFile>> {
        val result = mutableListOf<Pair<VirtualFile, VirtualFile>>()
        val seenLanguages = mutableSetOf<String>()
        findResDirectories(module).forEach { resDir ->
            resDir.children
                .filter { it.isDirectory && it.name.startsWith("values") }
                .forEach { valuesDir ->
                    val stringsFile = valuesDir.children
                        .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }
                    if (stringsFile != null && seenLanguages.add(valuesDir.name)) {
                        result.add(valuesDir to stringsFile)
                    }
                }
        }
        return result
    }

    private fun findResDirectories(module: Module): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<VirtualFile, Int>>()
        ModuleRootManager.getInstance(module).contentRoots.forEach { queue.add(it to 0) }
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (!dir.isDirectory || depth > 6) continue
            val path = dir.path
            if (!visited.add(path)) continue
            if (dir.name == "res") {
                result.add(dir)
                continue
            }
            if (shouldSkipDir(dir.name)) continue
            dir.children?.filter { it.isDirectory }?.forEach { queue.add(it to depth + 1) }
        }
        return result
    }

    private fun shouldSkipDir(name: String): Boolean {
        return name in SKIP_DIRS
    }

    private val SKIP_DIRS = setOf(
        ".git", ".idea", ".gradle", "build", "out", "captures", ".cxx",
        "node_modules", "gradle"
    )

    private fun getModuleRootPath(module: Module): String {
        return ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.path
            ?: module.moduleFilePath?.substringBeforeLast(File.separatorChar) ?: ""
    }

    private fun getFileLines(file: VirtualFile): Int {
        return FileDocumentManager.getInstance().getDocument(file)?.lineCount
            ?: file.inputStream.bufferedReader().useLines { it.count() }
    }

    private fun getStringText(file: VirtualFile, key: String): String {
        if (key.isEmpty()) return ""
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return ""
        val xml = document.text
        val escapedKey = Regex.escape(key)
        val regex = """<string\b(?=[^>]*\bname\s*=\s*(['"])$escapedKey\1)[^>]*>([\s\S]*?)</string>""".toRegex()
        return regex.find(xml)?.groupValues?.get(2) ?: ""
    }
}
