package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.Processor
import javax.swing.SwingUtilities

/**
 * 编辑器级诊断收集器 — 把所有「当前打开在编辑器中」的文件走
 * [DaemonCodeAnalyzerEx] 的 markup model 抓出 ERROR / WARNING /
 * WEAK_WARNING 级 highlight,转成可发给 AI 的 [FileDiagnostic] 列表。
 *
 * 工具名:`read_diagnostics` — 在 [cn.jarryleo.insert_strings.ai.ToolDefinitions]
 * 注册、在 [cn.jarryleo.insert_strings.ui.InsertStringsChatDriver] 中分发。
 *
 * ### 已知限制(给 AI 看的工具 doc 里也写了相同要点)
 * 1. **只覆盖打开的文件**:未在编辑器打开的文件 daemon 不会跑,结果必为 0。
 *    想知道未打开文件有没有错 → 用 `run_shell gradlew compileDebugKotlin`。
 * 2. **读到的是 daemon 缓存,不是实时**:文件被 `edit_file` 写过之后
 *    daemon 异步重跑(通常 100-500ms,大文件或 dirty 队列长时可达 1-2s)。
 *    写完立刻调本工具可能拿到旧结果。建议先调一个轻量工具让一拍过去。
 * 3. **不包含 build-time 错误**:kapt 报错、Android 资源 ID 找不到、lint、
 *    K2 FIR 编译错都不在范围 — 这些用 `run_shell` 跑构建。
 * 4. **Java + Kotlin 一起**:`processHighlights` 走 `TextEditorHighlightingPass`
 *    全集,Kotlin 的 `KotlinHighlightingPass` 也是同一机制,**不要**去 load
 *    `org.jetbrains.kotlin.*` 的 class(破坏沙箱兼容性)。
 * 5. **dumb mode 处理**:项目刚 sync 完 / indexing 中,daemon 暂停,
 *    tool_result 末尾 `note` 字段会标识,让 AI 不要据此断言无错。
 * 6. **HighlightInfo 是短命对象**:绑在 `RangeHighlighter` 上,daemon 重建
 *    markup 时旧 highlighter 被 `dispose()`。**不要**跨 daemon 周期持有
 *    `HighlightInfo` 引用 — 在 [collectForFile] 末尾立刻转 [FileDiagnostic]。
 *
 * ### 线程模型
 * - 枚举打开的文件 → EDT 上 `FileEditorManager.getOpenFiles()`
 * - 读每个文件的 highlights → pooled thread + `ReadAction`
 *   (`processHighlights` 断言 `isReadAccessAllowed`)
 * - 不要把 PSI / Document 引用跨线程传递,每段重新解析。
 */
internal class InsertStringsDiagnosticsController(
    private val state: ChatStateHolder,
) {

    // region ============== 公开 API ==============

    /**
     * 抓取当前所有打开文件的诊断,按 [FileDiagnostic] 列表返回(已按文件聚合)。
     * 入口是 `edit_file` 写完后 AI 想要"再读一次错误"的常见用法,也可被
     * `read_file` 在 fileKind=Kotlin/Java 时按需触发。
     *
     * **注意**:读到的是 daemon 缓存;如果文件刚被改过,立刻调用可能拿到
     * 旧结果。如果 caller 需要最新结果,应先调 [forceReanalyzeAllOpenFiles]
     * 再等几百毫秒(daemon 异步)。
     */
    fun collectOpenFileDiagnostics(
        minSeverity: HighlightSeverity = HighlightSeverity.WEAK_WARNING,
    ): DiagnosticsResult {
        val project = state.project
        val openFiles: Array<VirtualFile> = edtOrInvokeAndWait {
            FileEditorManager.getInstance(project).openFiles
        }

        val perFile = LinkedHashMap<String, MutableList<FileDiagnostic>>(openFiles.size)
        for (vf in openFiles) {
            val fileDiags = ReadAction.compute<List<FileDiagnostic>, Nothing> {
                collectForFile(project, vf, minSeverity)
            }
            // 同一 path 多 tab(罕见)合并到一份
            val key = vf.path
            perFile.getOrPut(key) { mutableListOf() }.addAll(fileDiags)
        }
        val flat = perFile.values.flatten()
        return DiagnosticsResult(
            openFileCount = openFiles.size,
            diagnostics = flat,
        )
    }

    /**
     * 把 daemon 对该文件的 dirty 标记置上,让定时器尽快重新跑一次分析。
     * 调完后不要立刻读 — daemon 是异步的,500ms 内会刷新。
     * 想要同步就用 `DaemonCodeAnalyzerEx.runMainPasses`(慢,且断言非 EDT)。
     *
     * 用法:写文件工具的 `onAfterCommit` 钩子里调一下,等下次 AI 主动
     * `read_diagnostics` 就能拿到新结果。本期不在 edit_file 路径里自动调,
     * 暴露为公共方法供 driver / 后续 hook 使用。
     */
    fun forceReanalyze(psiFile: PsiFile) {
        DaemonCodeAnalyzer.getInstance(state.project).restart(psiFile)
    }

    /**
     * 等价于对所有打开文件调一次 [forceReanalyze]。
     * 比 daemon 自己的 `restart()` (对所有已打开文件) 更窄 — 只重启用户
     * 当前真正打开的,不会拖累后台 dirty 队列里其他文件。
     */
    fun forceReanalyzeAllOpenFiles() {
        val project = state.project
        val psiManager = PsiManager.getInstance(project)
        val openFiles: Array<VirtualFile> = edtOrInvokeAndWait {
            FileEditorManager.getInstance(project).openFiles
        }
        for (vf in openFiles) {
            ReadAction.run<Nothing> {
                val psi = psiManager.findFile(vf) ?: return@run
                forceReanalyze(psi)
            }
        }
    }

    /**
     * 把 [DiagnosticsResult] 序列化成 tool_result 字符串 —
     * 头部带 `[工具执行结果] 类型:read_diagnostics ...` 标记(沿用项目其它
     * controller 的结果格式约定),主体是一个 JSON `JsonObject`(由 AI 直接解析)。
     *
     * @param projectBase 当前 IDE 打开的项目根,null 时 path 用绝对路径
     * @param includeDumbModeNote `true` 时若检测到 daemon 未完成,追加 `note` 字段
     *        提示 AI 不要据此断言无错。
     */
    fun formatToolResult(
        result: DiagnosticsResult,
        projectBase: String?,
        includeDumbModeNote: Boolean = true,
    ): String {
        val base = state.project.basePath ?: projectBase
        val root = JsonObject().apply {
            addProperty("diagnosticCount", result.diagnostics.size)
            addProperty("openFileCount", result.openFileCount)
            add("summary", JsonObject().apply {
                addProperty("ERROR", result.countBySeverity(Severity.ERROR))
                addProperty("WARNING", result.countBySeverity(Severity.WARNING))
                addProperty("WEAK_WARNING", result.countBySeverity(Severity.WEAK_WARNING))
                addProperty("INFO", result.countBySeverity(Severity.INFO))
            })
            add("files", JsonArray().apply {
                result.groupByFile(base).forEach { (relPath, errors) ->
                    add(JsonObject().apply {
                        addProperty("path", relPath)
                        add("errors", JsonArray().apply {
                            errors.forEach { d ->
                                add(JsonObject().apply {
                                    addProperty("line", d.line)
                                    addProperty("column", d.column)
                                    addProperty("severity", d.severity.name)
                                    addProperty("message", d.message)
                                    if (d.symbolName != null) addProperty("symbol", d.symbolName)
                                })
                            }
                        })
                    })
                }
            })
            if (includeDumbModeNote && result.diagnostics.isEmpty() && result.openFileCount > 0) {
                addProperty(
                    "note",
                    "dumb mode 或 daemon 未完成(项目刚 sync / indexing 中),结果可能滞后;可调 run_shell gradlew compileDebugKotlin 兜底验证。"
                )
            }
        }
        val body = root.toString()
        return buildString {
            append("[工具执行结果] 类型:read_diagnostics 状态:成功")
            if (base != null) append(" 项目根:").append(base)
            append('\n').append(body)
        }
    }

    /**
     * 把 [AiAction.ReadDiagnostics.minSeverity] 字符串映射到 [HighlightSeverity]。
     * 接受 `"ERROR"` / `"WARNING"` / `"WEAK_WARNING"`,其它(null/空/无效)走默认 WEAK_WARNING。
     */
    fun parseSeverity(value: String?): HighlightSeverity = when (value) {
        "ERROR" -> HighlightSeverity.ERROR
        "WARNING" -> HighlightSeverity.WARNING
        "WEAK_WARNING" -> HighlightSeverity.WEAK_WARNING
        else -> HighlightSeverity.WEAK_WARNING
    }

    // endregion

    // region ============== 内部实现 ==============

    private fun collectForFile(
        project: Project,
        vf: VirtualFile,
        minSeverity: HighlightSeverity,
    ): List<FileDiagnostic> {
        val doc: Document = FileDocumentManager.getInstance().getDocument(vf) ?: return emptyList()
        // commitDocument 是幂等的;若 caller 还在 write action 里残留的 PSI 变更会被刷进来,
        // 这样下面的 findFile 拿到最新 PSI。
        val psiMgr = PsiManager.getInstance(project)
        val psi: PsiFile = psiMgr.findFile(vf) ?: return emptyList()
        PsiDocumentManager.getInstance(project).commitDocument(doc)

        val out = mutableListOf<HighlightInfo>()
        // 静态方法,直接走 markup model(没有 service 查找开销)
        DaemonCodeAnalyzerEx.processHighlights(
            doc, project, minSeverity, 0, doc.textLength,
            Processor { info ->
                // file-level annotation 故意过滤:它没 line/column,
                // 且 info.text 在 highlighter 还没建好时抛 RuntimeException
                if (!info.isFileLevelAnnotation) out.add(info)
                true
            },
        )
        return out.map { it.toDiagnostic(vf, psi, doc) }
    }

    /**
     * 把 daemon 的 [HighlightInfo] 转成 AI 友好的 [FileDiagnostic]。
     *
     * 关于 symbolName:daemon 的 HighlightInfo **不存**"出错符号的限定名";
     * 采用最稳的做法 — 拿 reference 的 canonical text(unresolved-ref 错误时
     * 这就是编译期看到的 FQN 字符串),其它错误(类型不匹配 / 未使用符号等)
     * 退化为用 highlighter 范围内的 text 作为回退。
     */
    private fun HighlightInfo.toDiagnostic(
        virtualFile: VirtualFile,
        psiFile: PsiFile,
        doc: Document,
    ): FileDiagnostic {
        val offset = startOffset
        val line = doc.getLineNumber(offset)
        val column = offset - doc.getLineStartOffset(line)

        val symbolName: String? = run {
            val ref = psiFile.findReferenceAt(offset)
            ref?.canonicalText
        } ?: run {
            // 非引用类错误:取 highlighter 范围内的源码片段作为「出错符号」的近似
            runCatching { text.take(120) }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        val relPath = state.project.basePath?.let { base ->
            val baseVf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(base)
            baseVf?.let { VfsUtilCore.getRelativePath(virtualFile, it) }
        }

        return FileDiagnostic(
            virtualFile = virtualFile,
            relativePath = relPath,
            line = line,
            column = column,
            severity = severity.toSeverity(),
            message = description ?: type.toString(),
            symbolName = symbolName,
        )
    }

    private fun HighlightSeverity.toSeverity(): Severity = when (this) {
        HighlightSeverity.ERROR -> Severity.ERROR
        HighlightSeverity.WARNING -> Severity.WARNING
        HighlightSeverity.WEAK_WARNING, HighlightSeverity.INFO -> Severity.WEAK_WARNING
        else -> Severity.INFO
    }

    /**
     * 在 EDT 上读 UI 状态(打开的文件列表)。如果调用方已经在 EDT,直接执行;
     * 否则用 `invokeAndWait` 同步等。`invokeAndWait` 在 pooled thread 调是合法的
     * (不能从 EDT 调),所以 driver 端从 `executeOnPooledThread` 里走这条路径没问题。
     */
    private inline fun <T> edtOrInvokeAndWait(crossinline block: () -> T): T {
        return if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            val holder = arrayOfNulls<Any>(1)
            SwingUtilities.invokeAndWait { holder[0] = block() }
            @Suppress("UNCHECKED_CAST")
            holder[0] as T
        }
    }

    // endregion
}

// region ============== 数据类型 ==============

enum class Severity { ERROR, WARNING, WEAK_WARNING, INFO }

/**
 * 给 AI 看的「一行一个错误」的扁平数据。刻意做成 data class + 普通字段,
 * 不要在内部引用 PsiElement / HighlightInfo — 这些对象跨线程 / 跨 daemon
 * 周期都不安全(daemon 重建 markup 时旧 highlighter 会被 dispose)。
 */
data class FileDiagnostic(
    val virtualFile: VirtualFile,
    val relativePath: String?,
    val line: Int,          // 0-based
    val column: Int,        // 0-based
    val severity: Severity, // ERROR / WARNING / WEAK_WARNING / INFO
    val message: String,    // highlight description
    val symbolName: String?,// 失败符号的 FQN(unresolved-ref 错误)或源码片段
)

/**
 * 一次 collect 的整体结果,扁平列表 + 打开文件数(给 summary 字段用)。
 */
data class DiagnosticsResult(
    val openFileCount: Int,
    val diagnostics: List<FileDiagnostic>,
) {
    fun countBySeverity(severity: Severity): Int =
        diagnostics.count { it.severity == severity }

    /**
     * 按文件聚合,返回 Map<relativePath 或 absolute path, errors>。
     * 用于 [InsertStringsDiagnosticsController.formatToolResult] 生成 JSON。
     */
    fun groupByFile(projectBase: String?): Map<String, List<FileDiagnostic>> {
        return diagnostics.groupBy { d ->
            d.relativePath ?: d.virtualFile.path
        }
    }
}

// endregion
