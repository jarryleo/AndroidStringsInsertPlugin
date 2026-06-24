package cn.jarryleo.insert_strings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AiAction
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.ui.*
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * 右键 / Edit 菜单中的 "Extract strings.xml" 入口。
 *
 * 行为:
 * 1. 读取当前编辑器选中的文字(纯文本,不能跨越 XML/代码块结构)。
 * 2. 打开与 AskAi 同款的轻量 chat 弹框,自动把「把选中文本插入 strings.xml 并要求
 *    AI 全语种翻译」的提示词作为首条 user 消息发出,AI 会用 [AiAction.InsertStrings]
 *    把翻译写入 currentModule(没有就用行数最多的模块)。
 * 3. AI 完成 insert_strings / 复用 existing key 后:
 *    - 拿到刚写入的 key(由 [InsertStringsChatDriver] 通过
 *      [ChatStateHolder.onInsertStringsInserted] 回调传回)
 *    - 把编辑器选区替换成引用(委托给 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController]):
 *      - XML 布局(res/layout*):  `@string/key`
 *      - Kotlin / Java:          `R.string.key`
 *      - 其它文件:               默认走 `R.string.key`(代码优先)
 * 4. 弹框保持打开(用户可继续与 AI 交互,或手动点 Close 按钮关闭)。
 * 5. AI 后续可显式调用 [AiAction.ReplaceSelection] 工具再次替换 — 实际替换由
 *    [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController.runReplaceSelection]
 *    统一执行,带三层定位策略 + 防双重替换校验,与上一步自动替换走同一路径,
 *    行为完全一致。
 *
 * 「若 key 已存在则提示用户是否覆盖」由 AI 按 system prompt 中的规则通过 ask_user
 * 自然处理(已在 AITranslator.CHAT_SYSTEM_PROMPT 中说明),不在本类里重复实现。
 */
class ExtractStringsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true &&
            !editor.selectionModel.selectedText.isNullOrBlank()
        // 仅当编辑器有非空选区时启用;不是文件级菜单项
        e.presentation.isEnabledAndVisible = hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) {
            Messages.showMessageDialog(
                "Please select some text first.",
                "Extract strings.xml",
                Messages.getInformationIcon()
            )
            return
        }

        val settings = AiSettingsService.getInstance().state
        if (settings.url.isBlank() || settings.apiKey.isBlank()) {
            Messages.showMessageDialog(
                "Please configure AI settings in the InsertStrings tool window.",
                "Extract strings.xml",
                Messages.getInformationIcon()
            )
            return
        }

        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: editor.virtualFile
        val context = ContextManager.getInstance(project)
        context.ensureInitialized()
        context.updateCurrentModule(currentFile)

        // 捕获选区信息(以防 dialog 打开后编辑被改变,这里在 actionPerformed 时立即快照)
        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd

        showExtractDialog(
            editor = editor,
            project = project,
            currentFile = currentFile,
            selectedText = selectedText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
    }

    private fun showExtractDialog(
        editor: Editor,
        project: Project,
        currentFile: VirtualFile?,
        selectedText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val window = SwingUtilities.getWindowAncestor(editor.component)
        val dialog = JDialog(window, "Extract strings.xml", Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isResizable = true
        }

        val state = ExtractStringsChatHolder(project = project)
        // 注入 chat 入口打开时的编辑器选区,使 AI 显式调用 replace_selection 工具时
        // 也能正确替换硬编码文本(与 AskAi 入口行为一致)。
        state.bindEditorSelection(
            editor = editor,
            file = currentFile,
            selectedText = selectedText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )

        val composePanel = ComposePanel()
        val (titleBar, toastLabel) = createTitleBar(dialog, "Extract strings.xml")
        state.bindToastLabel(toastLabel)

        val resizeGrip = createResizeGrip(dialog)
        val gripContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, 16)
            add(resizeGrip, BorderLayout.EAST)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            add(titleBar, BorderLayout.NORTH)
            add(composePanel, BorderLayout.CENTER)
            add(gripContainer, BorderLayout.SOUTH)
            border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color(0xC8C8C8), 1)
        }

        dialog.contentPane = contentPanel
        dialog.preferredSize = Dimension(
            (editor.component.width * 0.45).toInt().coerceIn(420, 720),
            (editor.component.height * 0.6).toInt().coerceIn(360, 560)
        )
        dialog.pack()

        val editorLoc = editor.component.locationOnScreen
        dialog.setLocation(
            editorLoc.x + (editor.component.width - dialog.width) / 2,
            editorLoc.y + (editor.component.height - dialog.height) / 2
        )

        val stringsOps = InsertStringsStringsOpsController(state)
        val sheetsOps = InsertStringsSheetsOpsController(state)
        val fileOps = InsertStringsFileOpsController(state)
        val editorOps = InsertStringsEditorOpsController(state)
        // 把 driver 触发的 onInsertStringsInserted 自动替换路由到 controller 的 runReplaceSelection,
        // 与 AI 显式调用 replace_selection 工具走同一条路径,保证三层定位 + 防双重替换行为一致。
        // 注意:此处不再关闭弹框 —— AI 后续可能继续推进翻译查重(read_string /
        // ask_user / update_string),弹框需保持打开以维持对话上下文。
        state.bindOnAutoReplace { key ->
            editorOps.runReplaceSelection(AiAction.ReplaceSelection(key))
        }
        val contextBuilder = InsertStringsChatContextBuilder(state)
        val driver = InsertStringsChatDriver(
            state = state,
            stringsOps = stringsOps,
            sheetsOps = sheetsOps,
            fileOps = fileOps,
            editorOps = editorOps,
            chatContextBuilder = contextBuilder,
        )

        composePanel.setContent {
            MaterialTheme {
                val colors = rememberIdeColors()
                AiChatContent(
                    chatMessages = state.chatMessages,
                    chatInput = state.chatInput,
                    chatSending = state.chatSending,
                    onClose = { dialog.dispose() },
                    onNewChat = driver::newChat,
                    onChatInputChange = { state.chatInput = it },
                    onSendChat = driver::sendChat,
                    onStopChat = driver::stopChat,
                    onQuickSend = driver::quickSend,
                    onOptionClick = driver::onChatOptionClick,
                    onOpenContext = driver::openContextPopup,
                    onCloseContext = driver::closeContextPopup,
                    showContextPopup = state.showContextPopup,
                    chatContextText = state.chatContextText,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    colors = colors,
                    showHeader = false,
                    showQuickPhrases = false,
                    showEnterHint = false,
                    messageListContentPadding = PaddingValues(8.dp),
                )
            }
        }

        dialog.isVisible = true

        // 预置首条 user 消息 —— 把"插入翻译"的标准提示词按现有 system prompt 的约定发给 AI。
        // 注意:提示词中明确「只操作 strings.xml 不操作 google sheet」「自动生成 key,
        // 长度不超过 40 字符」「向 currentModule 插入,没有就用行数最多模块」,与 system prompt 一致。
        // 关键:首条消息中显式告诉 AI "我来自 Extract strings.xml 入口,选中的就是
        // 布局/代码里的硬编码文本",防止 AI 误判为"用户直接给译文"而跳过
        // replace_selection 步骤(chatEntry=extractStrings 也会在上下文 JSON 中冗余告知)。
        val firstMessage = buildString {
            append("[入口:Extract strings.xml] 我从布局/代码中选中了以下硬编码文本,请把它提取为 strings.xml 翻译条目:")
            append(selectedText)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            driver.sendChatMessage(firstMessage)
        }
    }

    /**
     * 编辑器选区替换/定位/`isLayoutXmlFile` 逻辑已统一迁到
     * [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController](见
     * [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController.runReplaceSelection]
     * 与 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController.performReplace])。
     * 本类不再保留重复实现,所有替换都通过绑定的 auto-replace 回调走 controller。
     */

    // ====== 与 AskAiAction 一致的弹框外观(标题栏 / resize grip) ======
    // 复制而不复用 AskAiAction 的 private 方法,避免两个 action 互相耦合;
    // Extract 弹框独立存在,改动时各自负责。

    private fun createResizeGrip(dialog: JDialog): JPanel {
        val grip = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                g.color = Color(0x909090)
                val w = width
                val h = height
                g.drawLine(w - 4, h - 11, w - 11, h - 4)
                g.drawLine(w - 4, h - 7, w - 7, h - 4)
                g.drawLine(w - 4, h - 3, w - 3, h - 4)
            }
        }
        grip.preferredSize = Dimension(16, 16)
        grip.cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
        grip.isOpaque = true

        var startPoint: Point? = null
        var startSize: Dimension? = null
        grip.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                startPoint = e.point
                startSize = dialog.size
            }
        })
        grip.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val sp = startPoint ?: return
                val ss = startSize ?: return
                val newWidth = (ss.width + e.x - sp.x).coerceAtLeast(320)
                val newHeight = (ss.height + e.y - sp.y).coerceAtLeast(280)
                dialog.setSize(newWidth, newHeight)
            }
        })

        return grip
    }

    private fun createTitleBar(dialog: JDialog, title: String): Pair<JPanel, JBLabel> {
        val titleBar = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(0, 32)
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
            val bgColor = UIManager.getColor("Panel.background") ?: Color(0xF2F2F2)
            background = bgColor
            isOpaque = true
        }

        val label = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
        }
        titleBar.add(label, BorderLayout.WEST)

        val toastLabel = JBLabel("").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = UIManager.getColor("Label.foreground") ?: Color(0x606060)
        }
        titleBar.add(toastLabel, BorderLayout.CENTER)

        val closeBtn = JButton("Close").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            font = font.deriveFont(Font.BOLD, 14f)
            preferredSize = Dimension(60, 24)
            margin = JBUI.insetsTop(8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { dialog.dispose() }
        }
        titleBar.add(closeBtn, BorderLayout.EAST)

        val dragStart = Point()
        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStart.location = e.point
            }

            override fun mouseDragged(e: MouseEvent) {
                val loc = dialog.location
                dialog.setLocation(
                    loc.x + e.x - dragStart.x,
                    loc.y + e.y - dragStart.y
                )
            }
        }
        titleBar.addMouseListener(dragListener)
        titleBar.addMouseMotionListener(dragListener)
        label.addMouseListener(dragListener)
        label.addMouseMotionListener(dragListener)

        titleBar.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)

        return titleBar to toastLabel
    }
}

/**
 * Extract strings.xml 弹框使用的 [ChatStateHolder] 轻量实现。
 *
 * 与 [AskAiChatHolder] 几乎一致:
 * - 持有一个 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController.runReplaceSelection]
 *   回调 [onAutoReplace],由 [ExtractStringsAction] 在构造完 controller 后注入。
 * - [onInsertStringsInserted] 把 driver 拿到的 key 转发到 [onAutoReplace],
 *   由 controller 走与 replace_selection 工具完全相同的替换/防双重替换路径。
 * - [editorSelection] 通过 [bindEditorSelection] 注入,使 AI 显式调用 replace_selection
 *   工具时也能正确替换硬编码文本。
 */
private class ExtractStringsChatHolder(
    override val project: Project
) : ChatStateHolder {
    override val insertStringsManager: InsertStringsManager =
        InsertStringsManager.getInstance(project)

    override val chatMessages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    override var chatInput: String by mutableStateOf("")
    override var chatSending: Boolean by mutableStateOf(false)
    // 当前 chat 入口标识 —— Extract strings.xml 弹框固定 "extractStrings",
    // 由 ChatStateHolder.chatEntry 写入上下文让 AI 知道:在用户选「使用现有 key:」时
    // **必须**先调 replace_selection 把 editorSelection 选中的硬编码文本替换掉。
    override val chatEntry: String = "extractStrings"

    @Volatile
    override var stopRequested: Boolean = false
    override var pendingAskUserToolCallId: String? = null
    override var askUserCallCount: Int = 0
    override var toolDocLoadCount: Int = 0
    override var pendingSheetsInsert: PendingSheetsInsert? = null
    override var showContextPopup: Boolean by mutableStateOf(false)
    override var chatContextText: String by mutableStateOf("")
    override var editorSelection: EditorSelectionContext? = null
    @Volatile
    override var editorReplacementTriggered: Boolean = false

    override val keyEntries: MutableList<KeyedStringsInfo> = mutableListOf()
    override var selectedKeyIndex: Int = 0
    override val sheetsAvailableSheets: MutableList<SheetsManager.SheetInfo> = mutableListOf()
    override val rows: List<StringRow> = emptyList()

    private var toastLabel: JBLabel? = null
    private var toastTimer: Timer? = null
    private var onAutoReplace: ((String) -> Unit)? = null

    fun bindToastLabel(label: JBLabel) {
        toastLabel = label
    }

    /**
     * 注入 chat 入口打开时的编辑器选区(由 [ExtractStringsAction.showExtractDialog]
     * 在构造后立即调用)。用于 AI 通过 [AiAction.ReplaceSelection] 工具或 driver
     * 触发 [onInsertStringsInserted] 时,把硬编码选区替换为对 key 的引用。
     */
    fun bindEditorSelection(
        editor: com.intellij.openapi.editor.Editor,
        file: com.intellij.openapi.vfs.VirtualFile?,
        selectedText: String,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        editorSelection = EditorSelectionContext(
            editor = editor,
            file = file,
            selectedText = selectedText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
        editorReplacementTriggered = false
    }

    /**
     * 注入「driver 完成 insert_strings 后自动触发的替换回调」。
     * 由 [ExtractStringsAction.showExtractDialog] 在构造完
     * [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController] 后调用,
     * 内部走 controller 的 runReplaceSelection —— 与 replace_selection 工具
     * 完全相同的替换/防双重替换路径。
     */
    fun bindOnAutoReplace(callback: (String) -> Unit) {
        this.onAutoReplace = callback
    }

    override fun showToast(message: String) {
        SwingUtilities.invokeLater {
            toastLabel?.text = message
            toastTimer?.stop()
            toastTimer = Timer(1800) {
                toastLabel?.text = ""
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * Extract 入口的 [onInsertStringsInserted] 实现:把 [editorSelection] 选中的硬编码文本
     * 替换为对 key 的引用。
     *
     * 替换 / 防双重替换 / 弹框保持打开等逻辑全部委托给 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController]
     * (由 [bindOnAutoReplace] 注入),与 AI 显式调用 [AiAction.ReplaceSelection] 工具
     * 走同一条路径 —— 保证两条路径行为一致、不会双重替换。
     */
    override fun onInsertStringsInserted(key: String, module: String?) {
        val cb = onAutoReplace ?: return
        SwingUtilities.invokeLater {
            cb(key)
        }
    }
}
