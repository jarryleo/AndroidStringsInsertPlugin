package cn.jarryleo.insert_strings.xml

import cn.jarryleo.insert_strings.ui.DebugLog
import cn.jarryleo.insert_strings.xml.ContextManager.getModuleFiles
import cn.jarryleo.insert_strings.xml.ContextManager.getModuleStringsInfo
import cn.jarryleo.insert_strings.xml.ContextManager.moduleFilesMap
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.*

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
        val displayName = resolveDisplayModuleName(project, moduleName) ?: return emptyList()
        return moduleFilesMap[displayName] ?: emptyList()
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
        val allModules = moduleManager.modules.toList()

        moduleManager.modules.forEach { module ->
            DebugLog.log("ContextManager", "find module name = ${module.name}")
            ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
                DebugLog.log("ContextManager", "  root = ${root.name} , path = ${root.path}")
                findResDirectories(root).forEach { resDir ->
                    DebugLog.log("ContextManager", "    resDir = ${resDir.name} , path = ${resDir.path}")
                    // 通过文件索引把 res 目录归属到真正的模块，避免根模块吞掉子模块
                    val ownerModule = findOwnerModuleForDir(allModules, resDir)
                        ?: fileIndex.getModuleForFile(resDir)
                        ?: module
                    DebugLog.log("ContextManager", "    ownerModule = $ownerModule")
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
        DebugLog.log("ContextManager", "contextInfo = ${contextInfo?.getJson()}")
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
        val displayName = resolveDisplayModuleName(project, moduleName) ?: return emptyList()
        val files = moduleFilesMap[displayName] ?: return emptyList()
        return files.map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, "", "")
        }
    }

    @JvmStatic
    fun resolveDisplayModuleName(project: Project, moduleName: String?): String? {
        ensureInitialized(project)
        val candidate = moduleName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate in moduleFilesMap) return candidate
        contextInfo?.findModule(candidate)?.let { return it.moduleName }
        val target = ModuleManager.getInstance(project).modules.find { it.name == candidate }
            ?: return null
        return getDisplayModuleName(target, project).takeIf { it in moduleFilesMap }
    }

    /**
     * 根据 strings.xml 文件查找其所属模块的显示名(displayModuleName)。
     * 用在 AI 工具执行链路里,判断「当前 keyEntries 是否与目标模块一致」,避免跨模块串写。
     * 找不到(未初始化/外部文件)返回 null。
     */
    @JvmStatic
    fun findModuleNameForFile(project: Project, file: VirtualFile?): String? {
        if (file == null) return null
        ensureInitialized(project)
        return moduleFilesMap.entries.firstOrNull { (_, files) ->
            files.any { it.second == file }
        }?.key
    }

    /**
     * 在指定模块下补齐缺失的语言文件(values/values-zh-rCN 等目录下的 strings.xml),
     * 并把新创建的文件刷新进 [moduleFilesMap] 缓存,确保后续 [getModuleStringsInfo] /
     * [getModuleFiles] 立刻看到。AI 跨语言写值时,目标模块只缺 values 等少数语言时,
     * 不再因为没文件而漏写 — 所有语言落在同一模块。
     *
     * 实现思路:取该模块任意一个已有 values* 目录的父目录(res/),在其下创建
     * `values[/...]/strings.xml`(空 resources 根)。注意:Android 限定符语言目录
     * 只允许特定字符,这里只创建合法名称(language 必须以 values 开头)。
     *
     * @return 新创建(或已存在)的 strings.xml 文件;模块没有 res 目录时返回 null
     */
    @JvmStatic
    fun ensureLanguageFile(
        project: Project,
        moduleName: String,
        language: String
    ): VirtualFile? {
        if (language.isBlank()) return null
        ensureInitialized(project)
        val displayName = resolveDisplayModuleName(project, moduleName) ?: return null
        val existing = moduleFilesMap[displayName]?.firstOrNull { it.first.name == language }
        if (existing != null) return existing.second

        val anyDir = moduleFilesMap[displayName]?.firstOrNull()?.first
        val resDir = anyDir?.parent ?: return null

        val newFile: VirtualFile? = WriteAction.computeAndWait<VirtualFile?, Exception> {
            try {
                val valuesDir = if (resDir.findChild(language) == null) {
                    resDir.createChildDirectory(this, language)
                } else {
                    resDir.findChild(language)!!
                }
                val existingFile = valuesDir.children?.firstOrNull {
                    it.name.contains("strings", ignoreCase = true) && it.extension == "xml"
                }
                if (existingFile != null) {
                    existingFile
                } else {
                    valuesDir.createChildData(this, "strings.xml").apply {
                        setBinaryContent(
                            """<?xml version="1.0" encoding="utf-8"?>
<resources>
</resources>
""".toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (newFile != null) {
            // 刷新缓存,让后续 getModuleFiles 看到
            val dir = newFile.parent
            if (dir != null) {
                val updated = moduleFilesMap[displayName].orEmpty() + (dir to newFile)
                moduleFilesMap = moduleFilesMap + (displayName to updated)
            }
        }
        return newFile
    }

    /**
     * 扫描指定模块中指定 key 的翻译内容
     */
    @JvmStatic
    fun scanModuleForKey(project: Project, moduleName: String, key: String): List<StringsInfo> {
        ensureInitialized(project)
        val displayName = resolveDisplayModuleName(project, moduleName) ?: return emptyList()
        val files = moduleFilesMap[displayName] ?: return emptyList()
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
        val modules = ModuleManager.getInstance(project).modules.toList()
        val module = findOwnerModuleForDir(modules, file)
            ?: ProjectRootManager.getInstance(project).fileIndex.getModuleForFile(file)
            ?: return null
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

    private fun findOwnerModuleForDir(modules: List<Module>, dir: VirtualFile): Module? {
        val dirPath = dir.path
        return modules
            .flatMap { module ->
                ModuleRootManager.getInstance(module).contentRoots.map { root -> module to root }
            }
            .filter { (_, root) ->
                val rootPath = root.path
                dirPath == rootPath || dirPath.startsWith(root.path)
            }
            .maxByOrNull { (_, root) -> root.path.length }
            ?.first
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
