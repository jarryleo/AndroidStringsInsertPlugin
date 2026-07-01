package cn.jarryleo.android.buddy.file

import cn.jarryleo.android.buddy.file.FileOpsService.Companion.MAX_EDIT_BYTES
import cn.jarryleo.android.buddy.file.FileOpsService.Companion.MAX_LINE_DISPLAY
import cn.jarryleo.android.buddy.file.FileOpsService.Companion.MAX_READ_LINES
import cn.jarryleo.android.buddy.file.FileOpsService.Companion.MAX_SEARCH_HITS
import cn.jarryleo.android.buddy.file.FileOpsService.Companion.REFERENCE_FILE_EXTENSIONS
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.PatternSyntaxException
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

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
     * @param content     全文(限定范围时为切片)。**2026.x 新增:每行带 `N: ` 前缀**,
     *                    1-based 行号,数字宽度按 `endLine` 自动对齐(如 `   1:` / `  17:`),
     *                    方便 AI 把行号直接喂给 edit_file。controller 拿到后还会再嵌一层
     *                    框架 `--- begin content ---`,前缀 `N: ` 与嵌套框架并存,行号仍是
     *                    紧贴每行首字符的最左 token,不会破坏现有解析。
     * @param totalLines  文件总行数
     * @param startLine   实际返回内容的起始行(**1-based,包含**;与 IDE 行号一致,2026.x
     *                    起从 0-based 改为 1-based,避免 AI 在 read/edit 之间反复 +1/-1)
     * @param endLine     实际返回内容的结束行(1-based,包含)
     * @param truncated   是否因为行数超限被截断
     * @param fileSize    文件字节数
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
     * @param applied    是否真的写入了磁盘(false 表示定位失败/越界/参数非法,文件未变)
     * @param mode       实际执行的模式(insert_before_line / insert_after_line / replace_line)
     * @param path       文件路径
     * @param line       命中/操作的行号(1-based;replace_line 模式下为起止行的 1-based 闭区间起点)
     * @param endLine    replace_line 模式下的 1-based 结束行(包含);其它模式 -1
     * @param oldText    replace_line 模式下被替换掉的原文(便于 AI 复查);插入模式为空
     * @param newText    实际写入的文本(可能含自动换行归一化,等价于 AI 想插入的内容 + 一个 `\n`)
     * @param newContent 替换后文件全文(便于 AI 复查;文件过大时为 null)
     */
    data class EditResult(
        val applied: Boolean,
        val mode: String,
        val path: String,
        val line: Int,
        val endLine: Int,
        val oldText: String,
        val newText: String,
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

        /** 批量 read_files 单次允许的最大文件数(防 token 爆炸)。 */
        const val MAX_BATCH_READ_FILES = 10

        /** 批量 read_files 单文件最大返回行数(比单文件 read_file 默认 600 略低,
         *  防止 N 个文件合并超长)。 */
        const val MAX_BATCH_READ_LINES = 300
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
     * 2026.x 行为变更:行号口径从 0-based 改为 **1-based**,与 IDE / 编辑器一致;
     * 返回的 `content` 每行带 `N: ` 前缀(数字宽度按 `endLine` 自动对齐),让 AI 看到的
     * 数字就是它要喂给 [editFile] 的 `line` 参数,**零心智转换**。
     *
     * @param path          路径(相对项目根或绝对路径)
     * @param startLine     起始行 **1-based(包含)**,负数 / 0 视为 1,超界自动夹到 `totalLines`
     * @param endLine       结束行 **1-based(包含)**,-1 / 0 表示到文件末尾
     * @param maxLines      单次最大返回行数,默认 [MAX_READ_LINES]
     * @return 失败时返回 [ReadResult] 的 content 为错误信息 + truncated=false;成功时
     *         content 每行格式为 `   17: actual content`(宽度对齐,前缀是行号,冒号+空格
     *         分隔,后跟原始行内容,空行也带前缀)。
     */
    fun readFile(
        path: String,
        startLine: Int = 1,
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
        // 1-based → 0-based 切片下标;clamp 边界
        val s1 = if (startLine < 1) 1 else startLine.coerceAtMost(total)
        val e1 = if (endLine < 1) total else endLine.coerceAtMost(total)
        if (s1 > e1) {
            return ReadResult(
                content = "[失败] 起始行($s1)大于结束行($e1),文件总行数 $total。",
                totalLines = total,
                startLine = 0,
                endLine = 0,
                truncated = false,
                fileSize = size,
            )
        }
        val slice0 = lines.subList(s1 - 1, e1)  // subList 左闭右开,end = e1(1-based 含)
        val truncatedFlag = slice0.size > maxLines
        val realSlice = if (truncatedFlag) slice0.subList(0, maxLines) else slice0
        val lastLineNumber = s1 + realSlice.size - 1
        // 行号前缀宽度 = lastLineNumber 的位数(1..9 用 1 位,10..99 用 2 位,...),
        // 保证每行前缀宽度一致,AI 解析时只看冒号前的数字,不需要看对齐。
        val width = lastLineNumber.toString().length
        val content = realSlice.mapIndexed { idx, lineText ->
            val n = s1 + idx
            val pad = " ".repeat(width - n.toString().length)
            "$pad$n: $lineText"
        }.joinToString("\n")
        return ReadResult(
            content = content,
            totalLines = total,
            startLine = s1,
            endLine = lastLineNumber,
            truncated = truncatedFlag,
            fileSize = size,
        )
    }

    // ============================================================
    // 3) 文件编辑:editFile(整行模式,2026.x 取消列精度)
    // ============================================================

    /**
     * 整行粒度的文件编辑(2026.x 重写,**取消列参数**)。
     *
     * 取消列的动机:AI 给出列号时常常因 IDE 渲染 / tab / 全角空格 / 缩进隐藏字符等偏差 1~N
     * 列,导致在错位的位置写入,产生乱码。整行粒度天然对列不敏感,只要行号准就不会错位。
     *
     * 位置语义:
     * - `line`(必填,1-based):目标行号,与 [readFile] 返回的 `N: ` 前缀的 N 一一对应,
     *   AI 可以直接把那个数字传进来。1-based → 0-based 在函数内做转换。
     *
     * 模式(互斥,必填其一):
     * - **insert_before_line**:`text` 插入到 `line` 这一行**之前**(等价于在 `line-1`
     *   之后插入)。`line=1` 表示在文件最开头插入。光标不替换任何原文。
     * - **insert_after_line**:`text` 插入到 `line` 这一行**之后**(等价于在 `line+1`
     *   之前插入)。`line=totalLines` 表示在文件最末尾插入。光标不替换任何原文。
     * - **replace_line**:`[line, endLine]` 闭区间内的整段文本(1-based 包含)被 `text` 替换;
     *   `endLine=-1` / `endLine=line` 表示单行替换,`endLine > line` 表示多行替换。
     *   替换后 `text` 内部用 `\n` 换行,`text` 不以 `\n` 结尾时,函数会**自动补一个 `\n`**
     *   让文本保持「行」粒度(否则会跟下一行粘连成一行)。
     *
     * 边界 / 失败(抛 [IllegalArgumentException],controller 转成失败 tool_result):
     * - 路径越界 / 文件不存在 / 不是 regular file
     * - 文件 > [MAX_EDIT_BYTES]
     * - `line < 1` 或 `line > totalLines + 1`(insert_after_line 允许 line = totalLines + 1,
     *   其它模式不允许越界)
     * - `mode` 非法(不在三种之内)
     * - replace_line 模式下 `endLine < line` 或 `endLine > totalLines`
     *
     * @param path     路径(相对项目根或项目内绝对)
     * @param line     1-based 行号
     * @param mode     insert_before_line / insert_after_line / replace_line
     * @param text     要插入或替换的文本(支持多行,行间用 `\n`)
     * @param endLine  replace_line 专用,1-based 结束行(包含);-1 等价于 `line`;其它模式忽略
     */
    fun editFile(
        path: String,
        line: Int,
        mode: String,
        text: String,
        endLine: Int = -1,
    ): EditResult {
        val resolved = resolveProjectPath(path)
            ?: error("[拒绝] 路径解析失败:'$path' 不在项目根目录 ${project.basePath} 内。")
        if (!resolved.exists() || !resolved.isRegularFile()) {
            error("[失败] 路径不存在或不是文件:'$path'")
        }
        val size = Files.size(resolved)
        if (size > MAX_EDIT_BYTES) {
            error("[失败] 文件过大(${size} 字节,>${MAX_EDIT_BYTES} 字节),请拆分为多次小范围编辑。")
        }

        val original = Files.readString(resolved, StandardCharsets.UTF_8)
        val lines = original.split('\n')
        val totalLines = lines.size
        val normalizedMode = mode.lowercase()

        if (line < 1) {
            error("[失败] line=$line 非法,必须 >= 1。")
        }
        when (normalizedMode) {
            "insert_before_line", "replace_line" -> {
                if (line > totalLines) {
                    error("[失败] $normalizedMode 模式下 line=$line 越界(1..$totalLines)。")
                }
            }

            "insert_after_line" -> {
                // 允许 line == totalLines + 1(末尾插入)
                if (line > totalLines + 1) {
                    error("[失败] $normalizedMode 模式下 line=$line 越界(1..${totalLines + 1})。")
                }
            }

            else -> error("[失败] mode='$mode' 非法,只支持 insert_before_line / insert_after_line / replace_line。")
        }

        // 行号 → 字符 offset
        fun lineStartOffset(line1Based: Int): Int {
            // 1-based line 之前所有行的长度 + 行间换行符
            // lines[0..line1Based-2] 共 line1Based-1 行,每行 length + '\n'
            return lines.subList(0, line1Based - 1).sumOf { it.length + 1 }
        }

        val finalText: String
        val targetStart: Int
        val targetEnd: Int
        var oldTextSnapshot = ""
        var actualEndLine = -1

        when (normalizedMode) {
            "insert_before_line" -> {
                finalText = ensureTrailingNewline(text)
                targetStart = lineStartOffset(line)
                targetEnd = targetStart
            }

            "insert_after_line" -> {
                finalText = ensureTrailingNewline(text)
                // 插入在 line 这一行的「之后」,等价于在 line+1 这一行「之前」。
                // 当 line == totalLines 时,lineStartOffset(totalLines + 1) 是文件末尾。
                if (line == totalLines) {
                    // 文件末尾:直接 append,offset = original.length(注意 original 以 \n 结尾)
                    targetStart = original.length
                    targetEnd = targetStart
                } else {
                    targetStart = lineStartOffset(line + 1)
                    targetEnd = targetStart
                }
            }

            "replace_line" -> {
                val eLine1 = if (endLine < 1) line else endLine.coerceAtLeast(line)
                if (eLine1 > totalLines) {
                    error("[失败] replace_line 模式下 endLine=$eLine1 越界(1..$totalLines)。")
                }
                actualEndLine = eLine1
                targetStart = lineStartOffset(line)
                val endOffset = lineStartOffset(eLine1) + lines[eLine1 - 1].length
                targetEnd = endOffset
                oldTextSnapshot = original.substring(targetStart, targetEnd)
                finalText = ensureTrailingNewline(text)
            }

            else -> error("[失败] mode='$mode' 非法,只支持 insert_before_line / insert_after_line / replace_line。")
        }

        val updated = original.replaceRange(targetStart, targetEnd, finalText)
        writeAtomic(resolved, updated)

        return EditResult(
            applied = true,
            mode = normalizedMode,
            path = path,
            line = line,
            endLine = actualEndLine,
            oldText = oldTextSnapshot,
            newText = finalText,
            newContent = updated,
        )
    }

    /**
     * 保证插入/替换文本以 `\n` 结尾,保持行粒度:
     * - text 为空 → 返回空字符串(由调用方自行判断是否合法,默认 replace_line 不接受空)
     * - text 以 `\n` 结尾 → 原样返回
     * - 否则 → 追加一个 `\n`
     */
    private fun ensureTrailingNewline(text: String): String {
        if (text.isEmpty()) return text
        return if (text.endsWith("\n")) text else text + "\n"
    }

    /**
     * 原子写文件:先写到临时文件,再 rename 覆盖,避免半写状态污染磁盘。
     *
     * 关键点(rename 完成后):
     *  1. 拿到 target 对应的 [VirtualFile](优先用 VFS 缓存),如果文件刚被创建,可能要先 `refresh`。
     *  2. `vFile.refresh(false, false)` —— 让 VFS 重新读文件(同步返回)。
     *  3. 如果该文件正被编辑器打开,`FileDocumentManager.reloadFromDisk(document)` 强制重读 —
     *    这是修复"磁盘改了但 IDE 还显示老内容"的关键步骤。
     *  4. `FileEditorManager.updateFilePresentation(vFile)` 让打开的编辑器重画 tab 标题/图标。
     *  5. `DaemonCodeAnalyzer.restart(vFile)` 重跑语法高亮、错误检查、imports 检查等
     *     后台分析任务,用户改完代码能立即看到红色下划线/补全建议。
     *
     * 这 4 步缺一不可:
     *  - 只 refresh VFS → 编辑器不重读,显示的还是缓存的旧内容(用户报告的核心 bug)
     *  - 只 reloadFromDisk → PSI 缓存未刷新,跳转/补全可能跳到旧定义
     *  - 只 updateFilePresentation → 标题更新了但内容还是旧的
     *  - 不 restart DaemonCodeAnalyzer → 代码分析基于旧 PSI,红色波浪线延迟出现
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
                // 写盘后立即通知 IDE 各层缓存重读(在 write action 内同步触发,确保 reload
                // 看到的就是这次写盘的最新内容;否则 background refresh 可能与下一次操作竞争)
                refreshIdeCachesAfterWrite(target)
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                throw e
            }
        }
    }

    /**
     * 写盘后通知 IntelliJ 各层缓存重读,让编辑器 / PSI / Daemon Code Analyzer
     * 立即看到新内容。详见 [writeAtomic] 的注释。
     */
    private fun refreshIdeCachesAfterWrite(target: Path) {
        val vFile = resolveVirtualFile(target) ?: return
        // 1. VFS 层:重新读文件系统元数据
        vFile.refresh(false, false)
        // 2. Document 层:如果该文件在编辑器中打开,强制重读磁盘内容
        val docManager = FileDocumentManager.getInstance()
        val document = docManager.getDocument(vFile)
        if (document != null) {
            // isSaveNeeded 检测到磁盘比 document 新时,reloadFromDisk 会重新加载
            runCatching { docManager.reloadFromDisk(document) }
        }
        // 3. 编辑器层:通知 tab 标题/图标刷新(例如未保存标记清除)
        runCatching { FileEditorManager.getInstance(project).updateFilePresentation(vFile) }
        // 4. PSI 层:丢掉 PsiFile 缓存,让下次访问时重新解析
        //    PsiManager.findFile 会用 stale 缓存(尤其是结构修改场景),需主动 invalidate
        runCatching {
            PsiManager.getInstance(project).findFile(vFile)?.let { psi ->
                // 触发 PSI 重新解析(等价于让 IDE 看到新的文件内容/结构)
                psi.subtreeChanged()
            }
        }
        // 5. 后台分析:重跑高亮、错误检查、补全等。
        //    IntelliJ 2025.1.3 的 DaemonCodeAnalyzer.restart() 只接受 PsiFile,需要转一次。
        runCatching {
            PsiManager.getInstance(project).findFile(vFile)?.let { psiFile ->
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            }
        }
    }

    /**
     * 把 [Path] 解析为 [VirtualFile](优先命中 VFS 缓存)。
     * 文件可能是新建的(刚被 createFile 落盘)或已有的。
     */
    private fun resolveVirtualFile(path: Path): VirtualFile? {
        return runCatching {
            val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            lfs.findFileByIoFile(path.toFile()) ?: run {
                // 极端情况:VFS 还没建立索引(刚创建的文件),用 URL 兜底
                val url = "file://${path.toAbsolutePath().toString().replace('\\', '/')}"
                VirtualFileManager.getInstance().findFileByUrl(url)
            }
        }.getOrNull()
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

    // ============================================================
    // 8) 文件元信息:file_info
    // ============================================================

    /**
     * 读取文件元信息(2026.x 新增),不读全文。
     *
     * 返回:[FileMeta],含 size / lineCount / lastModified / exists / isDirectory / isRegularFile。
     * 路径越界/不存在时 exists=false,其它字段为 0 / ""。
     */
    data class FileMeta(
        val path: String,
        val exists: Boolean,
        val sizeBytes: Long,
        val lineCount: Int,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean,
        val isRegularFile: Boolean,
        val fileName: String,
    )

    fun fileInfo(path: String): FileMeta {
        val resolved = resolveProjectPath(path)
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        if (resolved == null) {
            return FileMeta(path, false, 0L, 0, 0L, false, false, fileName)
        }
        if (!resolved.exists()) {
            return FileMeta(path, false, 0L, 0, 0L, false, false, fileName)
        }
        val isDir = Files.isDirectory(resolved)
        val isReg = Files.isRegularFile(resolved)
        val size = if (isReg) Files.size(resolved) else 0L
        val mtime = runCatching { Files.getLastModifiedTime(resolved).toMillis() }.getOrDefault(0L)
        val lineCount = if (isReg) {
            // 用 NIO 读 64KB 块估算行数,避免 OOM(超大文件也安全)
            runCatching {
                Files.newBufferedReader(resolved, StandardCharsets.UTF_8).use { reader ->
                    var count = 0
                    var lastCharWasNewline = true
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        for (i in 0 until n) {
                            if (buf[i] == '\n') count++
                        }
                        lastCharWasNewline = buf[n - 1] == '\n'
                    }
                    if (!lastCharWasNewline) count++
                    count
                }
            }.getOrDefault(0)
        } else 0
        return FileMeta(path, true, size, lineCount, mtime, isDir, isReg, fileName)
    }

    // ============================================================
    // 9) 批量读取:read_files
    // ============================================================

    /**
     * 批量读取多个文件(2026.x 新增),每个文件独立应用 [readFile] 同样的约束。
     *
     * @return [List]<[ReadResult]> 与 [paths] 一一对应;失败的文件其 [ReadResult.content] 是错误信息,
     *         [ReadResult.fileSize] = 0(便于 controller 区分"成功"和"失败")。
     */
    fun readFiles(paths: List<String>, maxLines: Int = MAX_BATCH_READ_LINES): List<ReadResult> {
        return paths.map { p ->
            try {
                readFile(p, startLine = 0, endLine = -1, maxLines = maxLines)
            } catch (e: Exception) {
                ReadResult(
                    content = "[失败] ${e.message ?: "unknown"}",
                    totalLines = 0,
                    startLine = 0,
                    endLine = 0,
                    truncated = false,
                    fileSize = 0L,
                )
            }
        }
    }

    // ============================================================
    // 10) 删除文件:delete_file
    // ============================================================

    /**
     * 删除文件或空目录(2026.x 新增)。
     *
     * 行为:
     * - 是文件 → 直接删除
     * - 是空目录 → 直接删除
     * - 是非空目录 → 抛 [IllegalArgumentException] 拒绝(必须 AI 先递归清空)
     *
     * 越界路径 / 不存在路径会被拒绝。
     * 成功后 IDE 自动关闭已打开的 tab(如果该文件在编辑器中打开),PSI 缓存由 VFS 失效。
     */
    fun deleteFile(path: String) {
        val resolved = resolveProjectPath(path)
            ?: error("[拒绝] 路径解析失败:'$path' 不在项目根目录 ${project.basePath} 内。")
        if (!resolved.exists()) {
            error("[失败] 路径不存在:'$path'")
        }
        if (Files.isDirectory(resolved)) {
            // 用 Files.list 流式检查,避免 Files.newDirectoryStream 资源泄漏
            val hasContent = Files.list(resolved).use { stream ->
                stream.findAny().isPresent
            }
            if (hasContent) {
                error("[失败] 目录非空,拒绝删除:'$path'。请先 list_files 看子项并逐个删除。")
            }
            // 删除空目录 + 通知 IDE
            ApplicationManager.getApplication().runWriteAction {
                val vFile = resolveVirtualFile(resolved)
                Files.delete(resolved)
                vFile?.refresh(false, false)
            }
        } else if (Files.isRegularFile(resolved)) {
            val vFile = resolveVirtualFile(resolved)
            // 如果该文件正被编辑器打开,先关闭对应 tab(否则删除会失败或保留 stale tab)
            val editorManager = FileEditorManager.getInstance(project)
            if (vFile != null && editorManager.isFileOpen(vFile)) {
                runCatching { editorManager.closeFile(vFile) }
            }
            ApplicationManager.getApplication().runWriteAction {
                Files.delete(resolved)
                vFile?.refresh(false, false)
                // 通知 PSI 缓存失效
                runCatching {
                    PsiManager.getInstance(project).findFile(vFile ?: return@runCatching)?.subtreeChanged()
                }
            }
        } else {
            error("[失败] 路径不是文件也不是目录:'$path'")
        }
    }

    // ============================================================
    // 11) 移动/重命名:move_file
    // ============================================================

    /**
     * 移动或重命名文件/目录(2026.x 新增)。
     *
     * 行为:
     * - 等价于 `mv <src> <dst>`(用 Files.move + REPLACE_EXISTING_ATOMIC_MOVE,
     *   在大多数文件系统上是原子的,不会留半写状态)。
     * - 目标已存在 → 抛 [IllegalArgumentException] 拒绝(防误覆盖)。
     * - 自动 mkdirs 创建目标父目录。
     * - 跨目录移动也支持(目标父目录会自动创建)。
     */
    fun moveFile(src: String, dst: String) {
        val srcPath = resolveProjectPath(src)
            ?: error("[拒绝] src 路径解析失败:'$src' 不在项目根目录 ${project.basePath} 内。")
        val dstPath = resolveProjectPath(dst)
            ?: error("[拒绝] dst 路径解析失败:'$dst' 不在项目根目录 ${project.basePath} 内。")
        if (!srcPath.exists()) {
            error("[失败] src 不存在:'$src'")
        }
        if (dstPath.exists()) {
            error("[失败] dst 已存在,拒绝覆盖:'$dst'。如需覆盖请先 delete 后 move。")
        }
        // 自动创建目标父目录
        dstPath.parent?.let { parent ->
            if (!parent.exists()) {
                Files.createDirectories(parent)
            }
        }
        // 如果 src 在编辑器中打开,先关闭(否则移动后 IDE 仍持有旧路径的 Document,产生 stale state)
        val vFile = resolveVirtualFile(srcPath)
        if (vFile != null) {
            val editorManager = FileEditorManager.getInstance(project)
            if (editorManager.isFileOpen(vFile)) {
                runCatching { editorManager.closeFile(vFile) }
            }
        }
        ApplicationManager.getApplication().runWriteAction {
            Files.move(
                srcPath, dstPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            // 通知 IDE 两端:src 失效、dst 出现
            resolveVirtualFile(srcPath)?.let { it.refresh(false, false) }
            resolveVirtualFile(dstPath)?.let { vDst ->
                vDst.refresh(false, false)
                // 重跑代码分析(若目标是代码文件)。
                // IntelliJ 2025.1.3 的 DaemonCodeAnalyzer.restart() 只接受 PsiFile,
                // 需要 PsiManager.getInstance(project).findFile(vDst) 转一次。
                runCatching {
                    PsiManager.getInstance(project).findFile(vDst)?.let { psiFile ->
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                    }
                }
            }
        }
    }
}
