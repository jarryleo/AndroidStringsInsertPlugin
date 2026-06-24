package cn.jarryleo.insert_strings.file

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.PatternSyntaxException
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * 文件操作底层服务(纯函数 + IntelliJ Platform API),与 UI / AI 完全解耦。
 *
 * 设计目标:
 * - 提供 6 个高内聚低耦合的方法,每个方法一次只做一件事(读 / 写 / 搜索 / 列举 / 引用查找 / 编辑区查询)
 * - 所有方法都是同步阻塞(由调用方决定是否切到后台线程),内部已经处理
 *   "PSafe 读 / 写锁"边界(读路径用 [ReadAction],写路径用 [ApplicationManager.runWriteAction] 包装)
 * - 路径解析统一规则:相对路径相对项目根解析;绝对路径必须落在项目根目录内(防止误改系统文件)
 *
 * 该类不依赖任何 Compose / Compose state,只依赖 [Project] + IntelliJ Platform API。
 * controllers 把它包成 AiAction → 工具结果文本,UI 层完全不知道它的存在。
 */
class FileOpsService(private val project: Project) {

    /**
     * 当前编辑区(光标所在编辑器)的相关信息。
     * @param filePath  编辑器打开文件的绝对路径(在项目内),可能为 null(无打开文件)
     * @param fileName  文件名带后缀(用于 AI 判断文件类型),如 MainActivity.kt / activity_main.xml
     * @param fileType  小写后缀(无 "."),如 kt / java / xml / gradle,无文件时为空串
     * @param language  高层类型分类: kotlin / java / xml / gradle / json / other —— AI 用以决定读法
     * @param selectedText 当前编辑器选中的文字;无选区时为空串
     * @param selectionStartLine 选区起始行(0-based);无选区时为 -1
     * @param selectionEndLine 选区结束行(0-based,含);无选区时为 -1
     * @param lineCount 文件总行数;无文件时为 0
     */
    data class EditorFileInfo(
        val filePath: String?,
        val fileName: String?,
        val fileType: String,
        val language: String,
        val selectedText: String,
        val selectionStartLine: Int,
        val selectionEndLine: Int,
        val lineCount: Int,
    ) {
        fun isEmpty(): Boolean = filePath.isNullOrBlank()
    }

    /**
     * 读取文件返回内容 + 元数据。
     * @param content 全文(限定范围时为切片)
     * @param totalLines 文件总行数
     * @param startLine 实际返回内容的起始行(0-based,包含)
     * @param endLine 实际返回内容的结束行(0-based,包含)
     * @param truncated 是否因为行数超限被截断
     * @param fileSize 文件字节数
     */
    data class ReadResult(
        val content: String,
        val totalLines: Int,
        val startLine: Int,
        val endLine: Int,
        val truncated: Boolean,
        val fileSize: Long,
    )

    /**
     * 一条搜索命中(单文件内,行号 + 匹配片段)。
     */
    data class SearchHit(
        val filePath: String,
        val line: Int,           // 1-based,符合 IDE 习惯
        val column: Int,         // 1-based
        val matchedText: String, // 整行内容(截断到 200 字符)
        val matchStart: Int,     // 0-based 偏移(行内),便于 AI 进一步编辑
        val matchEnd: Int,
    )

    /**
     * 一条引用命中(find_references 用)。
     */
    data class ReferenceHit(
        val filePath: String,
        val line: Int,
        val column: Int,
        val matchedText: String,
    )

    /**
     * 一次编辑的结果。
     * @param occurrences 命中次数(1 表示成功替换;0 表示 oldText/regex 未找到;>1 表示多匹配且未指定 replace_all,拒绝替换)
     * @param replaced 实际替换次数
     * @param newContent 替换后文件全文(便于 AI 复查;文件过大时为 null)
     */
    data class EditResult(
        val occurrences: Int,
        val replaced: Int,
        val newContent: String?,
    )

    companion object {
        /** 单次读取最大返回行数(超过会被截断,需要 startLine/endLine 分页)。 */
        const val MAX_READ_LINES = 600
        /** 单次读取最大字节数(超过直接拒绝读取,避免 OOM / token 爆炸)。 */
        const val MAX_READ_BYTES = 1_500_000L  // 1.5MB
        /** 单次搜索返回的命中条数上限。 */
        const val MAX_SEARCH_HITS = 200
        /** 单次 find_references 返回的命中条数上限。 */
        const val MAX_REFERENCE_HITS = 200
        /** 单行显示最大字符数(防止长行刷屏)。 */
        const val MAX_LINE_DISPLAY = 200
        /** 单次编辑允许的最大文件字节数(防止 OOM;超出需分多次操作)。 */
        const val MAX_EDIT_BYTES = 3_000_000L  // 3MB
        /** find_references 默认搜索的文件后缀。 */
        val REFERENCE_FILE_EXTENSIONS = setOf("java", "kt", "xml")
    }

    // ============================================================
    // 1) 编辑区查询:getEditorFile
    // ============================================================

    /**
     * 获取当前 IDE 编辑器中打开的文件信息(路径 / 类型 / 选区)。
     *
     * 关键点:
     * - 用 [FileEditorManager] 拿当前编辑器;不依赖 [AnActionEvent],可在任意线程调用
     * - 必须包在 [ReadAction] 内,否则 PsiDocumentManager 可能在读 PsiFile 时抛 "Read access not allowed"
     */
    fun getEditorFile(): EditorFileInfo {
        return ReadAction.compute<EditorFileInfo, RuntimeException> {
            val editorManager = FileEditorManager.getInstance(project)
            val editor: Editor? = editorManager.selectedTextEditor
            val virtualFile: VirtualFile? = editorManager.selectedFiles.firstOrNull()
            if (editor == null || virtualFile == null) {
                return@compute emptyInfo()
            }
            val fileName = virtualFile.name
            val fileType = fileName.substringAfterLast('.', "").lowercase()
            val language = mapExtensionToLanguage(fileType)
            val document = editor.document
            val totalLines = document.lineCount
            val selectionModel = editor.selectionModel
            val selectedText = if (selectionModel.hasSelection()) {
                selectionModel.selectedText ?: ""
            } else {
                ""
            }
            val startLine: Int
            val endLine: Int
            if (selectionModel.hasSelection()) {
                val startOffset = selectionModel.selectionStart
                val endOffset = selectionModel.selectionEnd
                startLine = document.getLineNumber(startOffset.coerceAtMost(endOffset))
                endLine = document.getLineNumber((endOffset - 1).coerceAtLeast(0))
            } else {
                startLine = -1
                endLine = -1
            }
            EditorFileInfo(
                filePath = virtualFile.path,
                fileName = fileName,
                fileType = fileType,
                language = language,
                selectedText = selectedText,
                selectionStartLine = startLine,
                selectionEndLine = endLine,
                lineCount = totalLines,
            )
        }
    }

    private fun emptyInfo() = EditorFileInfo(
        filePath = null,
        fileName = null,
        fileType = "",
        language = "other",
        selectedText = "",
        selectionStartLine = -1,
        selectionEndLine = -1,
        lineCount = 0,
    )

    private fun mapExtensionToLanguage(ext: String): String = when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "gradle" -> "gradle"
        "json" -> "json"
        "properties" -> "properties"
        "md" -> "markdown"
        "txt" -> "text"
        else -> "other"
    }

    // ============================================================
    // 2) 文件读取:readFile
    // ============================================================

    /**
     * 读取项目内文件(相对项目根 / 绝对路径均可,绝对路径必须落在项目根内)。
     *
     * @param path          路径(相对项目根或绝对路径)
     * @param startLine     起始行 0-based,负数视为 0
     * @param endLine       结束行 0-based(包含),负数或超界视为最后一行的 0-based 索引
     * @param maxLines      单次最大返回行数,默认 [MAX_READ_LINES]
     * @return 失败时返回 [ReadResult] 的 content 为空字符串 + truncated=true,或者抛 [IllegalArgumentException]
     */
    fun readFile(
        path: String,
        startLine: Int = 0,
        endLine: Int = -1,
        maxLines: Int = MAX_READ_LINES,
    ): ReadResult {
        val resolved = resolveProjectPath(path)
            ?: return ReadResult(
                content = "[拒绝] 路径解析失败:'$path' 不在项目根目录 ${project.basePath} 内。",
                totalLines = 0,
                startLine = 0,
                endLine = 0,
                truncated = false,
                fileSize = 0L,
            )

        val file = resolved.toFile()
        if (!file.exists() || !file.isFile) {
            return ReadResult(
                content = "[失败] 路径不存在或不是文件:'$path'",
                totalLines = 0,
                startLine = 0,
                endLine = 0,
                truncated = false,
                fileSize = 0L,
            )
        }
        val size = file.length()
        if (size > MAX_READ_BYTES) {
            return ReadResult(
                content = "[失败] 文件过大(${size} 字节,>${MAX_READ_BYTES} 字节),请缩小读取范围或使用 search_in_files 检索指定内容。",
                totalLines = 0,
                startLine = 0,
                endLine = 0,
                truncated = false,
                fileSize = size,
            )
        }
        val text = file.readText(StandardCharsets.UTF_8)
        val lines = text.split('\n')
        val total = lines.size
        val s = startLine.coerceAtLeast(0)
        val e = if (endLine < 0) total - 1 else endLine.coerceAtMost(total - 1)
        if (s > e) {
            return ReadResult(
                content = "[失败] 起始行($s)大于结束行($e),文件总行数 $total。",
                totalLines = total,
                startLine = 0,
                endLine = 0,
                truncated = false,
                fileSize = size,
            )
        }
        val slice = lines.subList(s, e + 1)
        val truncatedFlag = slice.size > maxLines
        val realSlice = if (truncatedFlag) slice.subList(0, maxLines) else slice
        return ReadResult(
            content = realSlice.joinToString("\n"),
            totalLines = total,
            startLine = s,
            endLine = s + realSlice.size - 1,
            truncated = truncatedFlag,
            fileSize = size,
        )
    }

    // ============================================================
    // 3) 文件编辑:editFile(支持 unique 模式 + regex 模式)
    // ============================================================

    /**
     * 精准编辑文件(可正则 / 可全量替换)。
     *
     * @param path        路径(相对项目根或绝对)
     * @param oldText     唯一匹配文本(useRegex=false 时使用)
     * @param newText     替换为的新文本
     * @param useRegex    true 时把 oldText 视为正则
     * @param replaceAll  true 时替换所有匹配;false 时要求 oldText(正则)只匹配 1 处,匹配 0/>1 处则失败
     * @return 失败信息写在 [EditResult.newContent] 为 null 时返回的字符串中(异常路径)
     */
    fun editFile(
        path: String,
        oldText: String,
        newText: String,
        useRegex: Boolean,
        replaceAll: Boolean,
    ): EditResult {
        val resolved = resolveProjectPath(path)
            ?: return EditResult(0, 0, null).also {
                error("[拒绝] 路径解析失败:'$path' 不在项目根目录 ${project.basePath} 内。")
            }
        if (!resolved.exists() || !resolved.isRegularFile()) {
            error("[失败] 路径不存在或不是文件:'$path'")
        }
        val size = Files.size(resolved)
        if (size > MAX_EDIT_BYTES) {
            error("[失败] 文件过大(${size} 字节,>${MAX_EDIT_BYTES} 字节),请拆分为多次小范围编辑。")
        }
        val original = Files.readString(resolved, StandardCharsets.UTF_8)

        // 校验 oldText / regex
        if (oldText.isEmpty()) {
            error("[失败] oldText / pattern 不能为空字符串。")
        }

        if (!useRegex) {
            val occurrences = countOccurrences(original, oldText)
            if (occurrences == 0) {
                return EditResult(0, 0, null).also {
                    error("[失败] 未在文件中找到 oldText。请用 read_file 重新确认内容(注意空格/换行/转义)。")
                }
            }
            if (occurrences > 1 && !replaceAll) {
                return EditResult(occurrences, 0, null).also {
                    error("[失败] oldText 在文件中出现 $occurrences 次(非唯一),请用 read_file 确认上下文后把 oldText 扩展到唯一,或把 replaceAll 设为 true。")
                }
            }
            val replaced: Int
            val updated: String = if (replaceAll) {
                replaced = occurrences
                original.replace(oldText, newText)
            } else {
                replaced = 1
                original.replaceFirst(oldText, newText)
            }
            writeAtomic(resolved, updated)
            return EditResult(occurrences, replaced, updated)
        }

        // regex 模式
        val regex = try {
            Regex(oldText, if (replaceAll) RegexOption.MULTILINE else RegexOption.MULTILINE)
        } catch (e: PatternSyntaxException) {
            error("[失败] 正则语法错误:${e.description ?: e.message}")
        }
        val matches = regex.findAll(original).toList()
        if (matches.isEmpty()) {
            return EditResult(0, 0, null).also {
                error("[失败] 正则未在文件中匹配到任何位置。")
            }
        }
        if (matches.size > 1 && !replaceAll) {
            return EditResult(matches.size, 0, null).also {
                error("[失败] 正则匹配 ${matches.size} 处(非唯一),请收紧 pattern 或把 replaceAll 设为 true。")
            }
        }
        val updated = if (replaceAll) {
            regex.replace(original, newText)
        } else {
            original.replaceRange(matches[0].range, newText)
        }
        writeAtomic(resolved, updated)
        return EditResult(matches.size, matches.size, updated)
    }

    /**
     * 原子写文件:先写到临时文件,再 rename 覆盖,避免半写状态污染磁盘。
     */
    private fun writeAtomic(target: Path, content: String) {
        ApplicationManager.getApplication().runWriteAction {
            val tmp = Files.createTempFile(
                target.parent,
                target.fileName.toString() + ".tmp-",
                ".edit"
            )
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8)
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                throw e
            }
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var idx = 0
        var count = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    // ============================================================
    // 4) 创建文件:createFile
    // ============================================================

    /**
     * 创建文件(支持嵌套目录,自动 mkdirs)。如果文件已存在,根据 [overwrite] 决定是否覆盖。
     *
     * @return true 表示已创建(或已覆盖),false 表示已存在未覆盖
     */
    fun createFile(path: String, content: String, overwrite: Boolean): Boolean {
        val resolved = resolveProjectPath(path)
            ?: error("[拒绝] 路径解析失败:'$path' 不在项目根目录 ${project.basePath} 内。")
        if (resolved.exists() && !overwrite) {
            return false
        }
        resolved.parent?.let { parent ->
            if (!parent.exists()) {
                Files.createDirectories(parent)
            }
        }
        writeAtomic(resolved, content)
        return true
    }

    // ============================================================
    // 5) 文件内容搜索:searchInFiles
    // ============================================================

    /**
     * 在项目内文件中搜索文本 / 正则,返回命中行列表。
     *
     * 实现说明:
     * - 走 java.nio.file.Files.walk 遍历项目根(脱离 IDE 索引,响应快,无索引噪声)
     * - 文件后缀白名单:[REFERENCE_FILE_EXTENSIONS] ∪ {gradle, json, properties, txt, md}
     * - 行级匹配,匹配整行截断到 [MAX_LINE_DISPLAY] 字符,匹配起止偏移按 0-based 行内列
     * - 命中数到达 [MAX_SEARCH_HITS] 时停止扫描,避免 OOM / 慢响应
     *
     * @param pattern          搜索文本/正则
     * @param useRegex         true 时按正则
     * @param caseSensitive    是否区分大小写(默认 false)
     * @param filePattern      可选 glob(只匹配文件名,例 "*.kt");null 时不限制
     * @param relativeDir      限定子目录(相对项目根);null 时搜索整个项目
     */
    fun searchInFiles(
        pattern: String,
        useRegex: Boolean,
        caseSensitive: Boolean = false,
        filePattern: String? = null,
        relativeDir: String? = null,
    ): List<SearchHit> {
        if (pattern.isEmpty()) return emptyList()
        val basePath = project.basePath?.let { Paths.get(it) } ?: return emptyList()
        val searchRoot: Path = relativeDir?.let { rel ->
            resolveProjectPath(rel) ?: return emptyList()
        } ?: basePath
        if (!searchRoot.exists()) return emptyList()

        val regexOptions = if (caseSensitive) {
            setOf(RegexOption.MULTILINE)
        } else {
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
        }
        val regex = if (useRegex) {
            try {
                Regex(pattern, regexOptions)
            } catch (e: PatternSyntaxException) {
                return emptyList()
            }
        } else {
            val escaped = Regex.escape(pattern)
            Regex(escaped, regexOptions)
        }

        val fileExtRegex = if (!filePattern.isNullOrBlank()) {
            globToRegex(filePattern)
        } else null

        val allowedExts = REFERENCE_FILE_EXTENSIONS + setOf("gradle", "kts", "json", "properties", "txt", "md")
        val hits = mutableListOf<SearchHit>()

        Files.walk(searchRoot).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { path ->
                    val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
                    ext in allowedExts
                }
                .filter { path ->
                    if (fileExtRegex == null) true
                    else fileExtRegex.matches(path.fileName.toString())
                }
                .forEach { path ->
                    if (hits.size >= MAX_SEARCH_HITS) return@forEach
                    val rel = basePath.relativize(path).toString().replace('\\', '/')
                    runCatching {
                        val text = Files.readString(path, StandardCharsets.UTF_8)
                        text.split('\n').forEachIndexed { idx, line ->
                            val match = regex.find(line) ?: return@forEachIndexed
                            hits.add(
                                SearchHit(
                                    filePath = rel,
                                    line = idx + 1,
                                    column = match.range.first + 1,
                                    matchedText = line.take(MAX_LINE_DISPLAY) +
                                        if (line.length > MAX_LINE_DISPLAY) "…" else "",
                                    matchStart = match.range.first,
                                    matchEnd = match.range.last + 1,
                                )
                            )
                            if (hits.size >= MAX_SEARCH_HITS) return@forEachIndexed
                        }
                    } // 文件读失败(权限/二进制)忽略
                }
        }
        return hits
    }

    /**
     * 极简 glob → 正则(只支持 * 和 ?):
     *  - `*` → `[^/]*`(单层目录任意字符)
     *  - `?` → `[^/]`
     * 仅用于 filePattern(单文件名匹配),不处理 `**` 多级目录。
     */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '.', '(', ')', '[', ']', '{', '}', '+', '|', '^', '$', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    // ============================================================
    // 6) 引用查找:findReferences
    // ============================================================

    /**
     * 查找符号在项目中的引用(Java/Kotlin/XML 文件)。
     *
     * 与 [searchInFiles] 的差异:
     * - 这是「符号引用」语义,而非纯文本搜索:对常见模式(R.id.x / R.layout.x / R.string.x /
     *   R.drawable.x / @+id/foo / @string/foo)做包装,让 AI 用「资源名 / 视图 id / key」就能找到引用点
     * - 对 Java/Kotlin 里的全限定类名(Foo / com.x.Foo)也按标识符边界匹配
     * - 实现基于 [searchInFiles] 的多模式扫描,避免依赖 IDE 索引(响应快)
     *
     * @param symbol   资源名 / key / 类名 / 标识符
     * @param kind     引用类型:id(查找 R.id.x / @+id/x / @id/x)/string/layout/drawable/class/general
     * @param caseSensitive 是否区分大小写
     */
    fun findReferences(
        symbol: String,
        kind: String,
        caseSensitive: Boolean = false,
    ): List<ReferenceHit> {
        if (symbol.isBlank()) return emptyList()
        val safeSymbol = Regex.escape(symbol)
        // 不同 kind 对应不同的搜索 pattern 集合
        val patterns: List<Regex> = when (kind.lowercase()) {
            "id" -> listOf(
                Regex("R\\.id\\.$safeSymbol\\b", regexOptions(caseSensitive)),
                Regex("@\\+?id/$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            "string" -> listOf(
                Regex("R\\.string\\.$safeSymbol\\b", regexOptions(caseSensitive)),
                Regex("@string/$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            "layout" -> listOf(
                Regex("R\\.layout\\.$safeSymbol\\b", regexOptions(caseSensitive)),
                Regex("@layout/$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            "drawable" -> listOf(
                Regex("R\\.drawable\\.$safeSymbol\\b", regexOptions(caseSensitive)),
                Regex("@drawable/$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            "color" -> listOf(
                Regex("R\\.color\\.$safeSymbol\\b", regexOptions(caseSensitive)),
                Regex("@color/$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            "class" -> listOf(
                // 全限定或简单类名
                Regex("\\b$safeSymbol\\b", regexOptions(caseSensitive)),
            )
            else -> listOf(Regex("\\b$safeSymbol\\b", regexOptions(caseSensitive)))
        }
        val hits = mutableListOf<ReferenceHit>()
        val basePath = project.basePath?.let { Paths.get(it) } ?: return emptyList()
        val allowedExts = REFERENCE_FILE_EXTENSIONS
        Files.walk(basePath).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { path ->
                    val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
                    ext in allowedExts
                }
                .forEach { path ->
                    if (hits.size >= MAX_REFERENCE_HITS) return@forEach
                    val rel = basePath.relativize(path).toString().replace('\\', '/')
                    runCatching {
                        val text = Files.readString(path, StandardCharsets.UTF_8)
                        text.split('\n').forEachIndexed { idx, line ->
                            patterns.forEach { rgx ->
                                rgx.findAll(line).forEach { m ->
                                    hits.add(
                                        ReferenceHit(
                                            filePath = rel,
                                            line = idx + 1,
                                            column = m.range.first + 1,
                                            matchedText = line.take(MAX_LINE_DISPLAY) +
                                                if (line.length > MAX_LINE_DISPLAY) "…" else "",
                                        )
                                    )
                                    if (hits.size >= MAX_REFERENCE_HITS) return@forEachIndexed
                                }
                            }
                        }
                    }
                }
        }
        return hits
    }

    private fun regexOptions(caseSensitive: Boolean): Set<RegexOption> {
        return if (caseSensitive) setOf(RegexOption.MULTILINE)
        else setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    }

    // ============================================================
    // 7) 列举文件:listFiles
    // ============================================================

    /**
     * 列举项目内某目录下的文件/子目录,支持 glob 与递归控制。
     *
     * @param relativeDir 相对项目根的子目录(默认项目根 ".")
     * @param pattern     glob 模式(默认 "*")
     * @param recursive   是否递归子目录
     * @param includeDirs 是否在结果中包含目录
     * @param maxEntries  最大返回条数(默认 500,避免爆栈)
     */
    fun listFiles(
        relativeDir: String,
        pattern: String = "*",
        recursive: Boolean = false,
        includeDirs: Boolean = false,
        maxEntries: Int = 500,
    ): List<String> {
        val basePath = project.basePath?.let { Paths.get(it) } ?: return emptyList()
        val target: Path = if (relativeDir.isBlank() || relativeDir == ".") {
            basePath
        } else {
            resolveProjectPath(relativeDir) ?: return emptyList()
        }
        if (!target.exists() || !Files.isDirectory(target)) return emptyList()
        val rgx = globToRegex(pattern)
        val out = mutableListOf<String>()
        val walker = if (recursive) {
            Files.walk(target, 10)
        } else {
            Files.walk(target, 1)
        }
        walker.use { stream ->
            stream
                .filter { it != target } // 排除自身
                .filter { p ->
                    val isDir = Files.isDirectory(p)
                    if (isDir && !includeDirs) return@filter false
                    rgx.matches(p.fileName.toString())
                }
                .forEach { p ->
                    if (out.size >= maxEntries) return@forEach
                    val rel = basePath.relativize(p).toString().replace('\\', '/')
                    out.add(if (Files.isDirectory(p)) "$rel/" else rel)
                }
        }
        return out.sorted()
    }

    // ============================================================
    // 路径解析
    // ============================================================

    /**
     * 解析路径:
     * - 绝对路径:必须以项目根路径为前缀(防止越界访问)
     * - 相对路径:相对项目根解析
     * - 失败返回 null,由调用方在工具结果里回报
     */
    fun resolveProjectPath(path: String): Path? {
        val basePath = project.basePath?.let { Paths.get(it) } ?: return null
        if (path.isBlank()) return null
        val candidate = if (Paths.get(path).isAbsolute) {
            Paths.get(path).normalize()
        } else {
            basePath.resolve(path).normalize()
        }
        val normalizedBase = basePath.normalize()
        if (!candidate.startsWith(normalizedBase)) return null
        return candidate
    }

    /**
     * 工具方法:判断文件是否落在 Android 模块的 res/ 目录(用于 AI 自动判断 strings.xml / layout.xml 等)。
     * 当前 controller 不直接使用,留作将来扩展。
     */
    @Suppress("unused")
    fun isAndroidResFile(virtualFile: VirtualFile): Boolean {
        val path = virtualFile.path
        return path.contains("/src/main/res/") || path.contains("/src/test/res/")
    }

    /**
     * 工具方法:用 IntelliJ PSI 索引按文件名查找项目内的所有同名文件(可能多个模块都有同名 strings.xml)。
     * 供 find_references / 调试用。
     */
    @Suppress("unused")
    fun findFilesByName(fileName: String): List<VirtualFile> {
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            FilenameIndex.getVirtualFilesByName(
                fileName,
                GlobalSearchScope.projectScope(project)
            ).toList()
        }
    }
}
