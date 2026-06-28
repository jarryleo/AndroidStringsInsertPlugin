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
 * (保存于 [ChatStateHolder.editorSelection])选区里**所有**匹配 [AiAction.ReplaceSelection.oldText]
 * 的子串替换为 [AiAction.ReplaceSelection.newText](整段选区内全部出现都换,精确字面匹配)。
 *
 * 2026.x 设计:工具只接受 (oldText, newText) 两个参数,不再接收 key、也不做任何智能判断 / 格式转换。
 * - 整段选区就是要替换的硬编码(翻译查重 / Extract 标准流程):oldText = 选区里这段硬编码
 *   字面内容,newText = `"@string/<key>"`(XML)或 `"R.string.<key>"`(其它);选区里通常只出现
 *   1 次,替换后整段变成对 key 的引用。
 * - 精准子串替换(典型需求):用户选了 `android:text="反馈内容: <font>请填写</font>"` 整段、
 *   只想把"反馈内容"换成对 key 的引用 → oldText = "反馈内容",newText = "@string/feedback_title";
 *   替换后整段变成 `android:text="@string/feedback_title: <font>请填写</font>"`,标签和冒号原样保留。
 * - 同一子串在选区里多次出现:全部替换(跟 String.replace 语义一致)。
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
 *  - 自动替换:driver 走完 insert_strings 后回调 [ChatStateHolder.onInsertStringsInserted],
 *    由 action 入口(AskAi / Extract)用原选区文本作 oldText、[buildReferenceText] 拼出的引用
 *    作 newText,触发本工具完成替换。
 *  - 通用:AI 在任何需要把选区里某段子串替换为目标文本时显式调用本工具。
 */
internal class InsertStringsEditorOpsController(
    private val state: ChatStateHolder,
) {

    /**
     * 把 chat 入口捕获的编辑器选区里**所有**匹配 [AiAction.ReplaceSelection.oldText] 的子串
     * 替换为 [AiAction.ReplaceSelection.newText]。
     *
     * 返回标准化工具结果文本(成功 / 失败 + 原因),driver 直接回传给 AI。
     *
     * 防双重替换:检查 [ChatStateHolder.editorReplacementTriggered];若已被前序
     * onInsertStringsInserted 自动替换(或者本入口多次调用工具)处理,直接返回
     * 「已跳过」而不做任何写入。同步块保证 check-and-set 的原子性(后台 tool loop
     * 路径与 EDT 上的 onAutoReplace 回调路径并发时不会双重执行)。
     */
    fun runReplaceSelection(action: AiAction.ReplaceSelection): String {
        val oldText = action.oldText
        if (oldText.isEmpty()) {
            return "[工具执行结果] 类型:replace_selection 状态:失败 信息:oldText 不能为空(防止无限循环)"
        }
        val newText = action.newText
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
            val runnable = { resultHolder[0] = performReplace(ctx, oldText, newText) }
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
     * selectedText / 选区范围,定位选区,再在选区里**全文替换**所有匹配 [oldText] 的子串。
     *
     * 替换策略(与 [cn.jarryleo.insert_strings.ExtractStringsAction] 原
     * `findReplacementRange` 一致的三层定位,用于定位选区范围):
     *  1. 当前仍选中同一段文本 → 用当前选区;
     *  2. 入口打开时捕获的 offset 仍对应原文 → 用旧 offset;
     *  3. 文档中原选中文本只出现一次 → 用该唯一位置;
     *  0 / >1 处匹配 → 返回失败,让 AI 重新决策。
     *
     * 定位到选区后,取选区文本,统计 oldText 出现次数:
     *  - 0 次 → 返回失败(子串不在选区里,AI 大概率搞错了),附选区前 60 字符预览;
     *  - ≥1 次 → 用 `String.replace(oldText, newText)` 在选区文本上**全文替换**
     *    (语义等同于 Kotlin String.replace:非重叠、全部出现都换),得到新选区内容;
     *    一次 `document.replaceString` 覆盖整个选区 range(原子写入,无 offset 漂移)。
     *
     * 附加安全检查:
     *  - 入口打开时的 [EditorSelectionContext.file] 与当前 editor.virtualFile 一致:
     *    用户若在 AI 回复期间切换到别的文件,直接拒绝(避免把替换写到错误的文件)。
     *
     * 返回 (success, message):success=false 时调用方不置位 [ChatStateHolder.editorReplacementTriggered],
     * 允许后续重试。
     */
    private fun performReplace(
        ctx: EditorSelectionContext,
        oldText: String,
        newText: String,
    ): Pair<Boolean, String> {
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

        val selectionRange = locateRange(document, originalText, currentSelection, ctx, textLength)
            ?: return false to "[工具执行结果] 类型:replace_selection 状态:失败 " +
                "信息:无法定位原始选中文本(可能已变更,或文档中存在多处匹配),为防止误替换已拒绝"

        val selectionContent = document.charsSequence.subSequence(selectionRange.first, selectionRange.second).toString()
        val occurrences = countOccurrences(selectionContent, oldText)
        if (occurrences == 0) {
            val preview = if (selectionContent.length > 60) selectionContent.take(60) + "…" else selectionContent
            return false to ("[工具执行结果] 类型:replace_selection 状态:失败 " +
                "信息:oldText 在选区中未出现(可能 AI 选错了子串,或选区在 AI 思考过程中被修改)。" +
                "oldText=\"$oldText\",选区前 60 字符:\"$preview\"。" +
                "请重新确认 oldText 必须是选区文本里的一段连续子串")
        }
        val newSelectionContent = selectionContent.replace(oldText, newText)

        var replaced = false
        var errorMsg: String? = null
        ApplicationManager.getApplication().runWriteAction {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(selectionRange.first, selectionRange.second, newSelectionContent)
                    editor.selectionModel.removeSelection()
                    val caret = selectionRange.first + newSelectionContent.length
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
        val detail = if (occurrences == 1) {
            "已将选区内 1 处匹配的 oldText 替换为 newText(选区原长度 ${originalText.length} 字符,替换后长度 ${newSelectionContent.length} 字符)"
        } else {
            "已将选区内 $occurrences 处匹配的 oldText 全部替换为 newText(选区原长度 ${originalText.length} 字符,替换后长度 ${newSelectionContent.length} 字符)"
        }
        return true to ("[工具执行结果] 类型:replace_selection 状态:成功 " +
            "信息:$detail,聊天视图保持打开")
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
     * 统计 [haystack] 中 [needle] 的非重叠出现次数。
     * 等价于 `haystack.split(needle).size - 1`,但省一次中间数组分配。
     */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val hit = haystack.indexOf(needle, idx)
            if (hit < 0) break
            count++
            idx = hit + needle.length
        }
        return count
    }

    companion object {
        /**
         * 根据文件类型拼出对 key 的字面引用文本(供 [AiAction.ReplaceSelection.newText] 传入)。
         *
         * 翻译查重 +「使用现有 key」流程:driver 走完 insert_strings 后回调
         * [ChatStateHolder.onInsertStringsInserted],由 action 入口(AskAi / Extract)
         * 调本方法把 `(file, key) → "@string/<key>" / "R.string.<key>"` 拼好,
         * 再走 [runReplaceSelection] 完成替换。
         */
        fun buildReferenceText(file: VirtualFile?, key: String): String =
            if (isLayoutXmlFile(file)) "@string/$key" else "R.string.$key"

        /**
         * 判断文件是否位于 Android 模块的 res/layout* 目录(布局 XML)。
         * 与 [cn.jarryleo.insert_strings.ExtractStringsAction.isLayoutXmlFile] 保持一致。
         */
        fun isLayoutXmlFile(file: VirtualFile?): Boolean {
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
}
