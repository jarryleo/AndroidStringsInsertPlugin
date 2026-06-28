package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.file.FileOpsService
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * AI 驱动的「文件 / 内容 / 引用」操作 controller。
 *
 * 设计原则与现有 [InsertStringsStringsOpsController] / [InsertStringsSheetsOpsController] 完全一致:
 *  - 每个 `runXxx(action)` 方法独立返回工具结果文本,driver 把它直接塞到 tool message
 *  - 写操作(edit / create)完成后,刷新 IDE 中已打开的对应文件,让用户立即看到变化
 *  - 路径解析 / 安全约束全部下沉到 [FileOpsService],controller 只做"action → 调用 service → 拼文本"
 *
 * 与 [InsertStringsStringsOpsController] 不同之处:
 *  - 路径无"模块"概念,直接走项目根(用户问「修改 MainActivity.kt」,给相对路径即可)
 *  - 写操作走 IDE 的 runWriteAction(原子性),完成后让 IDE 重读文件即可
 */
internal class InsertStringsFileOpsController(
    private val state: ChatStateHolder,
) {

    private val project: Project get() = state.project
    private val service: FileOpsService by lazy { FileOpsService(project) }

    fun runGetEditorFile(@Suppress("UNUSED_PARAMETER") action: AiAction.GetEditorFile): String {
        val info = try {
            service.getEditorFile()
        } catch (e: Exception) {
            return "[工具执行结果] 类型:get_editor_file 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (info.isEmpty()) {
            return buildString {
                appendLine("[工具执行结果] 类型:get_editor_file 状态:成功 信息:当前 IDE 未打开任何编辑器。")
                appendLine("  提示:打开任意文件后再次调用本工具即可获取路径与选中文字。")
            }
        }
        val selectInfo = if (info.selectedText.isNotBlank()) {
            val preview = info.selectedText.take(120) +
                if (info.selectedText.length > 120) "…(共 ${info.selectedText.length} 字符)" else ""
            "第 ${info.selectionStartLine + 1}-${info.selectionEndLine + 1} 行,内容预览:\n" +
                "    \"${preview.replace("\n", "\\n")}\""
        } else {
            "无选区"
        }
        return buildString {
            appendLine("[工具执行结果] 类型:get_editor_file 状态:成功")
            appendLine("  filePath: ${info.filePath}")
            appendLine("  fileName: ${info.fileName}")
            appendLine("  fileType: .${info.fileType} (language=${info.language})")
            appendLine("  lineCount: ${info.lineCount}")
            appendLine("  selectedText: $selectInfo")
        }
    }

    fun runReadFile(action: AiAction.ReadFile): String {
        val result = try {
            service.readFile(action.path, action.startLine, action.endLine, action.maxLines)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:read_file 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (result.fileSize == 0L) {
            // 失败路径:把 content 当错误信息回传
            return "[工具执行结果] 类型:read_file 状态:失败 path:${action.path} 信息:${result.content}"
        }
        val tail = buildString {
            if (result.truncated) {
                append("\n… 内容已截断(单次最多 ${FileOpsService.MAX_READ_LINES} 行)。")
                append(" 请用 startLine/endLine 分页继续读取后续内容。")
            }
        }
        val header = "[工具执行结果] 类型:read_file 状态:成功 path:${action.path} " +
            "显示行:${result.startLine + 1}-${result.endLine + 1}/${result.totalLines} " +
            "字节:${result.fileSize}"
        return buildString {
            append(header)
            append(tail)
            appendLine()
            append("--- begin content ---\n")
            append(result.content)
            if (!result.content.endsWith("\n")) append("\n")
            append("--- end content ---")
        }
    }

    fun runEditFile(action: AiAction.EditFile): String {
        val result = try {
            service.editFile(
                path = action.path,
                oldText = action.oldText,
                newText = action.newText,
                useRegex = action.useRegex,
                replaceAll = action.replaceAll,
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:edit_file 状态:失败 path:${action.path} 信息:${e.message ?: "unknown"}"
        }
        // 兜底通知 IDE(主流程已在 FileOpsService.writeAtomic 内部完成 VFS + Document +
        // FileEditor + PSI + Daemon 五层重读,这里 idempotent 再补一次)
        SwingUtilities.invokeLater { refreshOpenFile(action.path) }
        val modeDesc = if (action.useRegex) {
            if (action.replaceAll) "regex 全文" else "regex(唯一)"
        } else {
            if (action.replaceAll) "文本全文" else "文本(唯一)"
        }
        val preview = result.newContent?.let { newContent ->
            if (newContent.length > 800) {
                newContent.take(800) + "…(共 ${newContent.length} 字符)"
            } else {
                newContent
            }
        } ?: "(过大未返回)"
        return buildString {
            append("[工具执行结果] 类型:edit_file 状态:成功 模式:$modeDesc ")
            append("path:${action.path} 命中:${result.occurrences} 实际替换:${result.replaced}")
            appendLine()
            if (result.replaced > 0) {
                appendLine("--- 替换后内容(预览) ---")
                append(preview)
                if (!preview.endsWith("\n")) append("\n")
                append("--- end ---")
            } else {
                appendLine("未发生任何替换(occurrences=0)。")
            }
            // 2026.x:告诉 AI 缓存已重读,避免它再调 read_file "验证"(浪费 round-trip)
            appendLine()
            append("提示:IDE 缓存已自动重读(编辑器/PSI/Daemon Code Analyzer)," +
                "无需再调 read_file 验证,直接进入下一个动作。")
        }
    }

    fun runCreateFile(action: AiAction.CreateFile): String {
        val created = try {
            service.createFile(action.path, action.content, action.overwrite)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:create_file 状态:失败 path:${action.path} 信息:${e.message ?: "unknown"}"
        }
        if (!created) {
            return "[工具执行结果] 类型:create_file 状态:成功 但未写入 原因:文件已存在,overwrite=false"
        }
        // 兜底通知 IDE
        SwingUtilities.invokeLater { refreshOpenFile(action.path) }
        return buildString {
            append("[工具执行结果] 类型:create_file 状态:成功 path:${action.path} " +
                "字节:${action.content.toByteArray(Charsets.UTF_8).size}")
            appendLine()
            append("提示:IDE 缓存已自动重读(编辑器/PSI/Daemon Code Analyzer)," +
                "无需再调 read_file 验证,直接进入下一个动作。")
        }
    }

    fun runSearchInFiles(action: AiAction.SearchInFiles): String {
        val hits = try {
            service.searchInFiles(
                pattern = action.pattern,
                useRegex = action.useRegex,
                caseSensitive = action.caseSensitive,
                filePattern = action.filePattern,
                relativeDir = action.relativeDir,
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:search_in_files 状态:失败 信息:${e.message ?: "unknown"}"
        }
        val limited = hits.take(action.limit)
        if (limited.isEmpty()) {
            val scope = buildString {
                append("pattern:\"").append(action.pattern).append("\"")
                if (action.useRegex) append(" (regex)")
                if (!action.caseSensitive) append(" (ignoreCase)")
                if (!action.filePattern.isNullOrBlank()) append(" filePattern:").append(action.filePattern)
                if (!action.relativeDir.isNullOrBlank()) append(" dir:").append(action.relativeDir)
            }
            return "[工具执行结果] 类型:search_in_files 状态:成功 命中:0 范围:$scope 未找到匹配"
        }
        return buildString {
            append("[工具执行结果] 类型:search_in_files 状态:成功 命中:").append(limited.size)
            if (hits.size >= action.limit) append(" (已达返回上限 ${action.limit})")
            appendLine()
            limited.forEachIndexed { idx, hit ->
                append(idx + 1).append(". ")
                append(hit.filePath).append(":").append(hit.line).append(":").append(hit.column)
                append("  | ").append(hit.matchedText)
                appendLine()
            }
        }
    }

    fun runFindReferences(action: AiAction.FindReferences): String {
        val hits = try {
            service.findReferences(
                symbol = action.symbol,
                kind = action.kind,
                caseSensitive = action.caseSensitive,
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:find_references 状态:失败 信息:${e.message ?: "unknown"}"
        }
        val limited = hits.take(action.limit)
        if (limited.isEmpty()) {
            return "[工具执行结果] 类型:find_references 状态:成功 命中:0 " +
                "symbol:\"${action.symbol}\" kind:${action.kind} 未找到引用"
        }
        return buildString {
            append("[工具执行结果] 类型:find_references 状态:成功 命中:").append(limited.size)
            append(" symbol:\"").append(action.symbol).append("\" kind:").append(action.kind)
            if (hits.size >= action.limit) append(" (已达返回上限 ${action.limit})")
            appendLine()
            limited.forEachIndexed { idx, hit ->
                append(idx + 1).append(". ")
                append(hit.filePath).append(":").append(hit.line).append(":").append(hit.column)
                append("  | ").append(hit.matchedText)
                appendLine()
            }
        }
    }

    fun runListFiles(action: AiAction.ListFiles): String {
        val entries = try {
            service.listFiles(
                relativeDir = action.relativeDir,
                pattern = action.pattern,
                recursive = action.recursive,
                includeDirs = action.includeDirs,
                maxEntries = action.maxEntries,
            )
        } catch (e: Exception) {
            return "[工具执行结果] 类型:list_files 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (entries.isEmpty()) {
            return "[工具执行结果] 类型:list_files 状态:成功 命中:0 dir:${action.relativeDir} " +
                "pattern:\"${action.pattern}\" recursive:${action.recursive} 未找到匹配"
        }
        return buildString {
            append("[工具执行结果] 类型:list_files 状态:成功 命中:").append(entries.size)
            append(" dir:").append(action.relativeDir)
            append(" pattern:\"").append(action.pattern).append("\"")
            append(" recursive:").append(action.recursive)
            append(" includeDirs:").append(action.includeDirs)
            appendLine()
            entries.forEachIndexed { idx, e ->
                append(idx + 1).append(". ").append(e).appendLine()
            }
            if (entries.size >= action.maxEntries) {
                appendLine("… 已达 maxEntries 上限,可提高 maxEntries 继续列举。")
            }
        }
    }

    // region 2026.x 新增 4 个写代码工具

    fun runFileInfo(action: AiAction.FileInfo): String {
        val meta = try {
            service.fileInfo(action.path)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:file_info 状态:失败 信息:${e.message ?: "unknown"}"
        }
        if (!meta.exists) {
            return "[工具执行结果] 类型:file_info 状态:成功 path:${action.path} " +
                "exists:false(路径不存在或不在项目根内)"
        }
        val mtime = if (meta.lastModifiedMillis > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            sdf.format(java.util.Date(meta.lastModifiedMillis))
        } else "unknown"
        return buildString {
            append("[工具执行结果] 类型:file_info 状态:成功 path:${action.path}")
            appendLine()
            append("  fileName: ").append(meta.fileName)
            append("  sizeBytes: ").append(meta.sizeBytes)
            append("  lineCount: ").append(meta.lineCount)
            append("  isDirectory: ").append(meta.isDirectory)
            append("  isRegularFile: ").append(meta.isRegularFile)
            append("  lastModified: ").append(mtime)
        }
    }

    fun runReadFiles(action: AiAction.ReadFiles): String {
        if (action.paths.isEmpty()) {
            return "[工具执行结果] 类型:read_files 状态:失败 信息:paths 不能为空"
        }
        if (action.paths.size > FileOpsService.MAX_BATCH_READ_FILES) {
            return "[工具执行结果] 类型:read_files 状态:失败 信息:paths 数量 ${action.paths.size} 超过上限 " +
                "${FileOpsService.MAX_BATCH_READ_FILES},请拆批"
        }
        val maxLines = if (action.maxLines <= 0) FileOpsService.MAX_BATCH_READ_LINES
        else action.maxLines.coerceAtMost(FileOpsService.MAX_BATCH_READ_LINES)
        val results = try {
            service.readFiles(action.paths, maxLines = maxLines)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:read_files 状态:失败 信息:${e.message ?: "unknown"}"
        }
        return buildString {
            append("[工具执行结果] 类型:read_files 状态:成功 文件数:").append(action.paths.size)
            append(" 每文件 maxLines:").append(maxLines).appendLine()
            results.forEachIndexed { idx, r ->
                val path = action.paths[idx]
                if (r.fileSize == 0L && r.content.startsWith("[失败]")) {
                    appendLine()
                    append("[文件 ${idx + 1}] $path 状态:失败")
                    appendLine()
                    append("  原因:").append(r.content.removePrefix("[失败]").trim())
                    return@forEachIndexed
                }
                appendLine()
                append("[文件 ${idx + 1}] $path 行 ${r.startLine + 1}-${r.endLine + 1}/" +
                    "${r.totalLines} 字节:${r.fileSize}")
                appendLine("--- begin content ---")
                append(r.content)
                if (!r.content.endsWith("\n")) append("\n")
                append("--- end content ---")
            }
        }
    }

    fun runDeleteFile(action: AiAction.DeleteFile): String {
        try {
            service.deleteFile(action.path)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:delete_file 状态:失败 path:${action.path} " +
                "信息:${e.message ?: "unknown"}"
        }
        // 兜底通知 IDE(防止 IDE 缓存的虚拟文件未失效)
        SwingUtilities.invokeLater { refreshOpenFile(action.path) }
        return "[工具执行结果] 类型:delete_file 状态:成功 path:${action.path} " +
            "提示:IDE 缓存已刷新,若该文件在编辑器中打开已自动关闭"
    }

    fun runMoveFile(action: AiAction.MoveFile): String {
        try {
            service.moveFile(action.src, action.dst)
        } catch (e: Exception) {
            return "[工具执行结果] 类型:move_file 状态:失败 src:${action.src} dst:${action.dst} " +
                "信息:${e.message ?: "unknown"}"
        }
        // 兜底通知 IDE 两端
        SwingUtilities.invokeLater {
            refreshOpenFile(action.src)
            refreshOpenFile(action.dst)
        }
        return "[工具执行结果] 类型:move_file 状态:成功 src:${action.src} → dst:${action.dst} " +
            "提示:IDE 缓存已刷新,源文件 tab 已关闭,目标文件可在 Project 面板中重新打开"
    }

    // endregion

    /**
     * 写文件完成后,通知 IDE 重读(若该文件正在 IDE 中打开,刷新显示)。
     *
     * 修复历史(2026.x 强化):
     * - 原版只调用 `vFile.refresh(false, false)`,VFS 知道文件改了,但 **编辑器里展示的
     *   Document / PSI 缓存 / Daemon Code Analyzer 都还是旧内容** —— 这是用户报告的
     *   "修改后 IDE 还是显示老内容" 的根本原因。
     * - 现版在 [FileOpsService.writeAtomic] 内部已经一次性完成 VFS + Document +
     *   FileEditor + PSI + DaemonCodeAnalyzer 五层重读(详见该方法注释),所以这里
     *   只做兜底:对一些 FileOpsService 没覆盖到的写入路径(如 controller 直接用
     *   Path 写盘的旧代码)再补一次 VFS + Document 强制重读。
     *
     * 失败时静默忽略(文件可能没在 IDE 中打开,或已被删除)。
     */
    private fun refreshOpenFile(path: String) {
        runCatching {
            val basePath = project.basePath ?: return@runCatching
            val resolved = if (java.nio.file.Paths.get(path).isAbsolute) path
            else "$basePath/${path.trimStart('/', '\\')}"
            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByIoFile(java.io.File(resolved)) ?: return@runCatching
            vFile.refresh(false, false)
            // 兜底:强制重读编辑器 Document(若文件已打开)。
            // 正常路径 [FileOpsService.writeAtomic] 已经处理过,这里只是 idempotent 兜底,
            // 对绕过 writeAtomic 的旧写入路径仍然有效。
            val docManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            docManager.getDocument(vFile)?.let { doc ->
                runCatching { docManager.reloadFromDisk(doc) }
            }
            // 兜底:通知编辑器 tab 刷新图标/标题(清除 dirty 标记等)。
            runCatching {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .updateFilePresentation(vFile)
            }
        }
    }
}
