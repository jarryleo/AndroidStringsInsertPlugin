package cn.jarryleo.insert_strings.xml

import cn.jarryleo.insert_strings.ui.DebugLog
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.*

/**
 * 项目级 strings.xml 上下文管理器(原为单例 object,改为 per-Project 实例)。
 *
 * 改动原因:原实现把所有项目的模块列表、当前模块、文件缓存都存在 Kotlin `object` 的全局字段里,
 * 多个 Android Studio 项目同时打开时,后初始化的项目会**覆盖**之前项目的缓存,
 * 导致 AI 工具读到错的模块、修改到错的项目文件 — 见 issue「多项目上下文串扰」。
 *
 * 设计:实例本身按 Project 维度存储,缓存 [contextInfo] / [moduleFilesMap] / [modulePathMap] 全部进实例字段;
 * 静态 [getInstance] 用 [WeakHashMap] 弱持有 Project 引用,关闭/GC 后自动释放,
 * 不需要手动反注册,也不需要订阅 ProjectManager 监听(避免引入新依赖)。
 *
 * 公共 API 形态保持不变:每个方法第一个参数仍是 [project],内部用 `getInstance(project)` 取对应实例。
 * 调用点不需要再关心「这是不是单例」,照常传 project 即可。
 */
class ContextManager private constructor(private val project: Project) {

    var contextInfo: ContextInfo? = null
        private set

    // moduleName -> list of (valuesDir, stringsFile)
    private var moduleFilesMap: Map<String, List<Pair<VirtualFile, VirtualFile>>> = emptyMap()

    // moduleName -> module root path
    private var modulePathMap: Map<String, String> = emptyMap()

    /**
     * 获取模块的 values 目录与 strings.xml 文件对。
     * 对 AI 工具调用开放,允许扫描任意模块的所有语言文件。
     * @return values 目录 -> strings.xml 映射列表,模块不存在时返回空列表
     */
    fun getModuleFiles(moduleName: String): List<Pair<VirtualFile, VirtualFile>> {
        ensureInitialized()
        val displayName = resolveDisplayModuleName(moduleName) ?: return emptyList()
        return moduleFilesMap[displayName] ?: emptyList()
    }

    /**
     * 获取项目中所有模块的所有 strings.xml 文件(三元组: moduleName, valuesDir, stringsFile)。
     * 用于 AI 工具的跨模块反查。
     */
    fun getAllModuleFiles(): List<Triple<String, VirtualFile, VirtualFile>> {
        ensureInitialized()
        return moduleFilesMap.flatMap { (moduleName, files) ->
            files.map { (valuesDir, stringsFile) ->
                Triple(moduleName, valuesDir, stringsFile)
            }
        }
    }

    /**
     * 初始化项目所有模块的 strings.xml 上下文信息。
     * 通过扫描项目目录树查找包含 res 目录的模块,不依赖 IntelliJ 的 ModuleManager,
     * 避免 Android 项目未编译时模块信息不准确的问题。
     */
    fun initContextInfo() {
        val basePath = project.basePath ?: return
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(basePath) ?: return

        val allResDirs = findResDirectories(baseDir)
        val rawModules = mutableMapOf<String, MutableList<Pair<VirtualFile, VirtualFile>>>()
        val rawPaths = mutableMapOf<String, String>()

        for (resDir in allResDirs) {
            val moduleRoot = determineModuleRoot(baseDir, resDir) ?: continue
            val moduleName = if (moduleRoot.path == baseDir.path) {
                project.name
            } else {
                moduleRoot.name
            }
            rawPaths.putIfAbsent(moduleName, moduleRoot.path)
            DebugLog.log("ContextManager[${project.name}]", "found module=$moduleName, resDir=${resDir.path}")

            resDir.children
                .filter { it.isDirectory && it.name.startsWith("values") }
                .forEach { valuesDir ->
                    val stringsFile = valuesDir.children
                        .find { it.name.contains("strings", ignoreCase = true) && it.extension == "xml" }
                    if (stringsFile != null) {
                        rawModules.getOrPut(moduleName) { mutableListOf() }.add(valuesDir to stringsFile)
                    }
                }
        }

        val tempMap = mutableMapOf<String, List<Pair<VirtualFile, VirtualFile>>>()
        val moduleInfos = rawModules.mapNotNull { (name, files) ->
            if (files.isEmpty()) return@mapNotNull null
            // 同一模块下同语言只保留一份（多个 sourceSet 时会出现重复）
            val seenLanguages = mutableSetOf<String>()
            val uniqueFiles = files.filter { seenLanguages.add(it.first.name) }
            if (uniqueFiles.isEmpty()) return@mapNotNull null

            tempMap[name] = uniqueFiles

            ModuleInfo(
                moduleName = name,
                originalModuleName = name,
                modulePath = rawPaths[name] ?: "",
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
        modulePathMap = rawPaths
        contextInfo = ContextInfo(
            projectName = project.name,
            currentModule = null,
            modules = moduleInfos
        )
    }

    /**
     * 确保上下文已经初始化
     */
    fun ensureInitialized() {
        if (contextInfo == null) {
            initContextInfo()
        }
    }

    /**
     * 根据当前文件更新 currentModule
     */
    fun updateCurrentModule(currentFile: VirtualFile?) {
        ensureInitialized()
        val current = currentFile?.let { buildCurrentModuleInfo(it) }
        contextInfo = contextInfo?.copy(currentModule = current)
    }

    /**
     * 获取指定显示名称模块的所有 strings.xml 文件信息（key/text 为空，仅用于定位文件）
     */
    fun getModuleStringsInfo(moduleName: String): List<StringsInfo> {
        ensureInitialized()
        val displayName = resolveDisplayModuleName(moduleName) ?: return emptyList()
        val files = moduleFilesMap[displayName] ?: return emptyList()
        return files.map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, "", "")
        }
    }

    fun resolveDisplayModuleName(moduleName: String?): String? {
        ensureInitialized()
        val candidate = moduleName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate in moduleFilesMap) return candidate
        contextInfo?.findModule(candidate)?.let { return it.moduleName }
        return null
    }

    /**
     * 根据 strings.xml 文件查找其所属模块的显示名(displayModuleName)。
     * 用在 AI 工具执行链路里,判断「当前 keyEntries 是否与目标模块一致」,避免跨模块串写。
     * 找不到(未初始化/外部文件)返回 null。
     */
    fun findModuleNameForFile(file: VirtualFile?): String? {
        if (file == null) return null
        ensureInitialized()
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
    fun ensureLanguageFile(
        moduleName: String,
        language: String
    ): VirtualFile? {
        if (language.isBlank()) return null
        ensureInitialized()
        val displayName = resolveDisplayModuleName(moduleName) ?: return null
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
    fun scanModuleForKey(moduleName: String, key: String): List<StringsInfo> {
        ensureInitialized()
        val displayName = resolveDisplayModuleName(moduleName) ?: return emptyList()
        val files = moduleFilesMap[displayName] ?: return emptyList()
        return files.map { (valuesDir, stringsFile) ->
            StringsInfo(stringsFile, valuesDir.name, key, getStringText(stringsFile, key))
        }
    }

    private fun buildCurrentModuleInfo(file: VirtualFile): ModuleInfo? {
        val displayName = findModuleDisplayNameForFile(file) ?: return null
        val files = moduleFilesMap[displayName] ?: return null
        return ModuleInfo(
            moduleName = displayName,
            originalModuleName = displayName,
            modulePath = modulePathMap[displayName] ?: "",
            xmlFiles = files.map { (valuesDir, stringsFile) ->
                XmlFileInfo(
                    filePath = stringsFile.path,
                    language = valuesDir.name,
                    fileLines = getFileLines(stringsFile)
                )
            }
        )
    }

    /**
     * 根据文件路径查找其所属模块。
     * 通过路径前缀匹配,取最深的模块路径作为归属。
     */
    private fun findModuleDisplayNameForFile(file: VirtualFile): String? {
        val filePath = file.path
        return modulePathMap.entries
            .filter { (_, modulePath) ->
                filePath == modulePath || filePath.startsWith(modulePath)
            }
            .maxByOrNull { (_, modulePath) -> modulePath.length }
            ?.key
    }

    /**
     * BFS 扫描目录下所有 res 子目录
     */
    private fun findResDirectories(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<VirtualFile, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (!dir.isDirectory || depth > 15) continue
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

    /**
     * 从 res 目录向上推导模块根目录。
     * 跳过 Android source set 中间目录(src/main/debug/release 等),
     * 但不跳过项目根目录。
     */
    private fun determineModuleRoot(projectRoot: VirtualFile, resDir: VirtualFile): VirtualFile? {
        var current: VirtualFile = resDir.parent ?: return null
        while (current.path != projectRoot.path) {
            val parent = current.parent ?: return current
            if (parent.path == projectRoot.path) return current
            if (current.name in SOURCE_SET_DIRS) {
                current = parent
            } else {
                return current
            }
        }
        return current // res 在项目根目录下
    }

    private fun shouldSkipDir(name: String): Boolean {
        return name in SKIP_DIRS
    }

    private val SOURCE_SET_DIRS = setOf("src", "main", "debug", "release", "test", "androidTest")

    private val SKIP_DIRS = setOf(
        ".git", ".idea", ".gradle", "build", "out", "captures", ".cxx",
        "node_modules", "gradle"
    )

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

    companion object {
        // 用 WeakHashMap 弱持有 Project,避免关闭后仍持有引用导致内存泄漏;
        // 同时不订阅 ProjectManager 主题,降低与平台其他监听的耦合。
        private val instances = WeakHashMap<Project, ContextManager>()

        @JvmStatic
        fun getInstance(project: Project): ContextManager {
            // WeakHashMap 本身不是线程安全的,这里加锁避免多线程下出现重复实例。
            synchronized(instances) {
                return instances.getOrPut(project) { ContextManager(project) }
            }
        }
    }
}
