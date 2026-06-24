package cn.jarryleo.insert_strings.ui

import cn.jarryleo.insert_strings.ai.AiAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.SwingUtilities

/**
 * AI 驱动的「编辑器选区替换」操作 controller。
 *
 * 唯一动作:[runReplaceSelection] — 把 chat 入口打开时捕获的 [EditorSelectionContext]
 * (保存于 [ChatStateHolder.editorSelection])中的硬编码文本替换为对 key 的引用:
 *  - XML 布局(res/layout* 等)→ `@string/<key>`
 *  - 其它文件(Kotlin/Java/...)→ `R.string/<key>`
 *
 * 与 [InsertStringsFileOpsController] 的区别:本 controller 操作的是 chat 入口捕获的
 * 选区(已通过 state.editorSelection 注入),不是任意项目内文件。
 *
 * **重要:本 controller 在替换完成后不关闭聊天视图**。原因:
 *  - 翻译查重的典型流程是用户点选「使用现有 key:<existing_key>」→ AI 调用本工具替换硬编码
 *    文本 → 继续调用 read_string 校验现有翻译 → 用 ask_user 询问用户是否需要修正 →
 *    可能再调用 update_string。整个流程需要聊天视图保持打开,AI 才有上下文继续推进。
 *  - 若关闭聊天视图,AI 无法继续 read_string/update_string 等后续工具调用。
 * 主面板场景因 editorSelection == null 直接返回失败。
 *
 * 触发场景:
 *  - 翻译查重:用户点选 ask_user 的「使用现有 key:<existing_key>」后,AI 用本工具触发替换。
 *  - 通用:AI 在任何需要把硬编码文本替换为 key 引用时显式调用本工具。
 */
internal class InsertStringsEditorOpsController(
    private val state: ChatStateHolder,
) {

    /**
     * 把 chat 入口捕获的编辑器选区替换为对 [AiAction.ReplaceSelection.key] 的引用。
     *
     * 返回标准化工具结果文本(成功 / 失败 + 原因),driver 直接回传给 AI。
     *
     * 防双重替换:检查 [ChatStateHolder.editorReplacementTriggered];若已被前序
     * onInsertStringsInserted 自动替换(或者本入口多次调用工具)处理,直接返回
     * 「已跳过」而不做任何写入。同步块保证 check-and-set 的原子性(后台 tool loop
     * 路径与 EDT 上的 onAutoReplace 回调路径并发时不会双重执行)。
     */
    fun runReplaceSelection(action: AiAction.ReplaceSelection): String {
        val key = action.key.trim()
        if (key.isEmpty()) {
            return "[工具执行结果] 类型:replace_selection 状态:失败 信息:key 不能为空"
        }
        val ctx = state.editorSelection ?: return "[工具执行结果] 类型:replace_selection 状态:失败 " +
            "信息:当前聊天入口没有可替换的编辑器选区(主面板聊天视图无编辑器上下文);" +
            "本工具仅在 ExtractStrings / AskAi 弹框入口有效"

        return synchronized(replaceLock) {
            if (state.editorReplacementTriggered) {
                return@synchronized "[工具执行结果] 类型:replace_selection 状态:已跳过 " +
                    "信息:编辑器选区已被前序自动替换处理(insert_strings 触发的 onInsertStringsInserted " +
                    "或本入口之前的 replace_selection 调用),本次工具调用被忽略以防止双重替换"
            }

            // performReplace 需要在 EDT 上跑(WriteCommandAction 必须在 EDT)。
            // 工具路径通常在后台 tool loop 上调用,用 invokeAndWait 同步等待;
            // 自动替换路径已在 EDT 上 invokeLater 调用,直接执行避免嵌套 invokeAndWait 异常。
            val resultHolder = arrayOfNulls<Pair<Boolean, String>>(1)
            val runnable = { resultHolder[0] = performReplace(ctx, key) }
            val message = try {
                if (SwingUtilities.isEventDispatchThread()) {
                    runnable()
                } else {
                    SwingUtilities.invokeAndWait(runnable)
                }
                val (success, msg) = resultHolder[0]
                    ?: (false to "[工具执行结果] 类型:replace_selection 状态:失败 信息:EDT 未返回结果")
                // 仅在替换成功后才置位标志 —— 失败时允许后续重试
                if (success) state.editorReplacementTriggered = true
                msg
            } catch (e: Exception) {
                "[工具执行结果] 类型:replace_selection 状态:失败 信息:执行异常:${e.message ?: "unknown"}"
            }
            message
        }
    }

    /**
     * 保证 runReplaceSelection 多次并发调用(check-and-set 标志)与 performReplace
     * 写入之间的原子性,避免后台 tool loop 路径与 EDT 上的 onAutoReplace 回调路径
     * 同时进入 performReplace 造成双重写入。
     */
    private val replaceLock = Any()

    /**
     * 在 EDT 上执行实际的编辑器替换:用 [EditorSelectionContext] 里的 editor / file /
     * selectedText / 选区范围,定位并替换。
     *
     * 替换策略(与 [cn.jarryleo.insert_strings.ExtractStringsAction] 原
     * `findReplacementRange` 一致的三层定位):
     *  1. 当前仍选中同一段文本 → 用当前选区;
     *  2. 入口打开时捕获的 offset 仍对应原文 → 用旧 offset;
     *  3. 文档中原选中文本只出现一次 → 用该唯一位置;
     *  0 / >1 处匹配 → 返回失败,让 AI 重新决策。
     *
     * 附加安全检查:
     *  - 入口打开时的 [EditorSelectionContext.file] 与当前 editor.virtualFile 一致:
     *    用户若在 AI 回复期间切换到别的文件,直接拒绝(避免把替换写到错误的文件)。
     *  - 原始文本长度/位置变化时(用户在 AI 思考过程中编辑文档),三层定位策略会兜底
     *    校验,确保不会替换错位置。
     *
     * 返回 (success, message):success=false 时调用方不置位 [ChatStateHolder.editorReplacementTriggered],
     * 允许后续重试。
     */
    private fun performReplace(ctx: EditorSelectionContext, key: String): Pair<Boolean, String> {
        val project = state.project
        val editor = ctx.editor
        if (editor.isDisposed) {
            return false to "[工具执行结果] 类型:replace_selection 状态:失败 信息:编辑器已关闭"
        }
        // 安全检查 1:用户切换了文件 —— 拒绝,避免把替换写到错误的文件
        val currentFile = editor.virtualFile
        if (ctx.file != null && currentFile != null && ctx.file != currentFile) {
            return false to "[工具执行结果] 类型:replace_selection 状态:失败 " +
                "信息:用户已切换到其它文件(入口打开时文件=${ctx.file.path},当前=${currentFile.path})," +
                "为防止误替换已拒绝"
        }
        val document = editor.document
        val textLength = document.textLength
        val currentSelection = editor.selectionModel
        val originalText = ctx.selectedText.takeIf { it.isNotBlank() }
            ?: return false to "[工具执行结果] 类型:replace_selection 状态:失败 信息:原始选区文本为空,无法定位"

        val range = locateRange(document, originalText, currentSelection, ctx, textLength)
            ?: return false to "[工具执行结果] 类型:replace_selection 状态:失败 " +
                "信息:无法定位原始选中文本(可能已变更,或文档中存在多处匹配),为防止误替换已拒绝"

        val replacement = if (isLayoutXmlFile(ctx.file)) "@string/$key" else "R.string.$key"

        var replaced = false
        var errorMsg: String? = null
        ApplicationManager.getApplication().runWriteAction {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(range.first, range.second, replacement)
                    editor.selectionModel.removeSelection()
                    val caret = range.first + replacement.length
                    editor.caretModel.moveToOffset(caret)
                    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                    replaced = true
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "unknown"
            }
        }
        if (!replaced) {
            return false to "[工具执行结果] 类型:replace_selection 状态:失败 信息:写入失败:${errorMsg ?: "unknown"}"
        }
        // 触发文件重读(让 IDE 反映最新内容)
        ctx.file?.let { f ->
            runCatching { FileEditorManager.getInstance(project).updateFilePresentation(f) }
        }
        // **不关闭聊天视图**:翻译查重的完整流程是 replace_selection → read_string → (可选)
        // ask_user 询问是否修正 → update_string。若此处关闭聊天视图,AI 无法继续推进
        // 后续工作(主面板会切回表格,AskAi/ExtractStrings 弹框保持打开,都依赖对话上下文)。
        return true to ("[工具执行结果] 类型:replace_selection 状态:成功 " +
            "信息:已将选区替换为 $replacement(原长度 ${range.second - range.first} 字符),聊天视图保持打开")
    }

    private fun locateRange(
        document: com.intellij.openapi.editor.Document,
        originalText: String,
        currentSelection: SelectionModel,
        ctx: EditorSelectionContext,
        textLength: Int,
    ): Pair<Int, Int>? {
        if (currentSelection.hasSelection()) {
            val start = currentSelection.selectionStart
            val end = currentSelection.selectionEnd
            if (start >= 0 && end <= textLength && start < end) {
                val currentText = document.charsSequence.subSequence(start, end).toString()
                if (currentText == originalText) return start to end
            }
        }
        if (ctx.selectionStart >= 0 && ctx.selectionEnd <= textLength && ctx.selectionStart < ctx.selectionEnd) {
            val capturedText = document.charsSequence.subSequence(ctx.selectionStart, ctx.selectionEnd).toString()
            if (capturedText == originalText) return ctx.selectionStart to ctx.selectionEnd
        }
        val fullText = document.text
        val first = fullText.indexOf(originalText)
        if (first < 0) return null
        val second = fullText.indexOf(originalText, first + originalText.length)
        return if (second < 0) first to (first + originalText.length) else null
    }

    /**
     * 判断文件是否位于 Android 模块的 res/layout* 目录(布局 XML)。
     * 与 [cn.jarryleo.insert_strings.ExtractStringsAction.isLayoutXmlFile] 保持一致。
     */
    private fun isLayoutXmlFile(file: VirtualFile?): Boolean {
        if (file == null) return false
        if (file.extension?.lowercase() != "xml") return false
        val path = file.path
        return path.contains("/src/main/res/layout") ||
            path.contains("/src/test/res/layout") ||
            path.contains("/src/androidTest/res/layout") ||
            path.contains("/src/debug/res/layout") ||
            path.contains("/src/release/res/layout")
    }
}
