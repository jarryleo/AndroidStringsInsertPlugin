package cn.jarryleo.insert_strings.xml

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.ArrayDeque

object ContextManager {

    var contextInfo: ContextInfo? = null
        private set

    // displayModuleName -> (originalModuleName, list of valuesDir to stringsFile)
    private var moduleFilesMap: Map<String, List<Pair<VirtualFile, VirtualFile>>> = emptyMap()

    /**
     * 获取模块的 values 目录与 strings.xml 文件对。
     * 对 AI 工具调用开放,允许扫描任意模块的所有语言文件。
     * @return values 目录 -> strings.xml 映射列表,模块不存在时返回空列表
     */
    @JvmStatic
    fun getModuleFiles(project: Project, moduleName: String): List<Pair<VirtualFile, VirtualFile>> {
        ensureInitialized(project)
        return moduleFilesMap[moduleName] ?: run {
            val target = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                ?: return emptyList()
            val displayName = getDisplayModuleName(target, project)
            moduleFilesMap[displayName] ?: emptyList()
        }
    }

    /**
     * 获取项目中所有模块的所有 strings.xml 文件(三元组: moduleName, valuesDir, stringsFile)。
     * 用于 AI 工具的跨模块反查。
     */
    @JvmStatic
    fun getAllModuleFiles(project: Project): List<Triple<String, VirtualFile, VirtualFile>> {
        ensureInitialized(project)
        return moduleFilesMap.flatMap { (moduleName, files) ->
            files.map { (valuesDir, stringsFile) ->
                Triple(moduleName, valuesDir, stringsFile)
            }
        }
    }

    /**
     * 初始化项目所有模块的 strings.xml 上下文信息
     */
    @JvmStatic
    fun initContextInfo(project: Project) {
        val moduleManager = ModuleManager.getInstance(project)
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val rawModuleFiles = mutableMapOf<Module, MutableList<Pair<VirtualFile, VirtualFile>>>()

        moduleManager.modules.forEach { module ->
            ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
                findResDirectories(root).forEach { resDir ->
                    // 通过文件索引把 res 目录归属到真正的模块，避免根模块吞掉子模块
                    val ownerModule = fileIndex.getModuleForFile(resDir) ?: module
                    resDir.children
                        .filter { it.isDirectory && it.name.startsWith("values") }
                        .forEach { valuesDir ->
                            val stringsFile = valuesDir.children
                                .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }
                            if (stringsFile != null) {
                                rawModuleFiles.getOrPut(ownerModule) { mutableListOf() }.add(valuesDir to stringsFile)
                            }
                        }
                }
            }
        }

        val tempMap = mutableMapOf<String, List<Pair<VirtualFile, VirtualFile>>>()
        val moduleInfos = rawModuleFiles.mapNotNull { (module, files) ->
            if (files.isEmpty()) return@mapNotNull null
            // 同一模块下同语言只保留一份（多个 sourceSet 时会出现重复）
            val seenLanguages = mutableSetOf<String>()
            val uniqueFiles = files.filter { seenLanguages.add(it.first.name) }
            if (uniqueFiles.isEmpty()) return@mapNotNull null

            val displayName = getDisplayModuleName(module, project)
            tempMap[displayName] = uniqueFiles

            ModuleInfo(
                moduleName = displayName,
                originalModuleName = module.name,
                modulePath = getModuleRootPath(module),
                xmlFiles = uniqueFiles.map { (valuesDir, stringsFile) ->
                    XmlFileInfo(
                        filePath = stringsFile.path,
                        language = valuesDir.name,
                        fileLines = getFileLines(stringsFile)
                    )
                }
            )
        }

        moduleFilesMap = tempMap
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
     * 获取指定显示名称模块的所有 strings.xml 文件信息（key/text 为空，仅用于定位文件）
     */
    @JvmStatic
    fun getModuleStringsInfo(project: Project, moduleName: String): List<StringsInfo> {
        ensureInitialized(project)
        val files = moduleFilesMap[moduleName]
            ?: moduleFilesMap.values.flatten().let { all ->
                // 尝试用原始模块名查找
                val target = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                    ?: return emptyList()
                val displayName = getDisplayModuleName(target, project)
                moduleFilesMap[displayName]
            }
            ?: return emptyList()
        return files.map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, "", "")
        }
    }

    /**
     * 扫描指定模块中指定 key 的翻译内容
     */
    @JvmStatic
    fun scanModuleForKey(project: Project, moduleName: String, key: String): List<StringsInfo> {
        ensureInitialized(project)
        val files = moduleFilesMap[moduleName]
            ?: moduleFilesMap.values.flatten().let {
                val target = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                    ?: return emptyList()
                val displayName = getDisplayModuleName(target, project)
                moduleFilesMap[displayName]
            }
            ?: return emptyList()
        return files.map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, key, getStringText(stringsFile, key))
        }
    }

    private fun buildCurrentModuleInfo(project: Project, file: VirtualFile): ModuleInfo? {
        val displayName = findModuleDisplayNameForFile(project, file) ?: return null
        val files = moduleFilesMap[displayName] ?: return null
        return ModuleInfo(
            moduleName = displayName,
            originalModuleName = findOriginalModuleName(project, displayName) ?: "",
            modulePath = getModuleRootPathByName(project, displayName) ?: "",
            xmlFiles = files.map { (valuesDir, stringsFile) ->
                XmlFileInfo(
                    filePath = stringsFile.path,
                    language = valuesDir.name,
                    fileLines = getFileLines(stringsFile)
                )
            }
        )
    }

    private fun findModuleDisplayNameForFile(project: Project, file: VirtualFile): String? {
        val module = ProjectRootManager.getInstance(project).fileIndex.getModuleForFile(file) ?: return null
        return getDisplayModuleName(module, project)
    }

    private fun findOriginalModuleName(project: Project, displayName: String): String? {
        return ModuleManager.getInstance(project).modules
            .find { getDisplayModuleName(it, project) == displayName }
            ?.name
    }

    private fun getModuleRootPathByName(project: Project, displayName: String): String? {
        return ModuleManager.getInstance(project).modules
            .find { getDisplayModuleName(it, project) == displayName }
            ?.let { getModuleRootPath(it) }
    }

    private fun getDisplayModuleName(module: Module, project: Project): String {
        val prefix = "${project.name}."
        val name = module.name
        return if (name.startsWith(prefix)) name.removePrefix(prefix) else name
    }

    private fun findResDirectories(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<VirtualFile, Int>>()
        queue.add(root to 0)
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
