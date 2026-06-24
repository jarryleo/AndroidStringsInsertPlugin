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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
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
 * 3. AI 完成 insert_strings 后:
 *    - 拿到刚写入的 key(由 [InsertStringsChatDriver] 通过
 *      [ChatStateHolder.onInsertStringsInserted] 回调传回)
 *    - 把编辑器选区替换成引用:
 *      - XML 布局(res/layout*):  `@string/key`
 *      - Kotlin / Java:          `R.string.key`
 *      - 其它文件:               默认走 `R.string.key`(代码优先)
 * 4. 弹框关闭。
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

        val state = ExtractStringsChatHolder(
            project = project,
            onKeyInserted = { key ->
                // AI 完成 insert_strings 后,弹回 EDT 替换选区
                SwingUtilities.invokeLater {
                    replaceSelection(editor, currentFile, selectionStart, selectionEnd, key)
                }
            },
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
        val contextBuilder = InsertStringsChatContextBuilder(state)
        val driver = InsertStringsChatDriver(
            state = state,
            stringsOps = stringsOps,
            sheetsOps = sheetsOps,
            fileOps = fileOps,
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
        val firstMessage = buildString {
            append("请插入翻译:").append(selectedText)
            appendLine()
            appendLine("默认需要你自动生成一个 key,key 长度不要超过 40 个字符。")
            appendLine("然后向 currentModule 模块插入这个模块所有语种的翻译;")
            appendLine("若 currentModule 不存在则插入行数最多的模块内。")
            appendLine("需要保证模块内每个语种都有对应的翻译。")
            appendLine("注意:只操作 strings.xml 文件不操作 google sheet。")
            appendLine("插入前需检查 key 是否存在;若 key 已存在则提示用户是否覆盖。")
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            driver.sendChatMessage(firstMessage)
        }
    }

    /**
     * 把编辑器中 [selectionStart, selectionEnd) 这段选区替换成对应引用。
     * - XML layout 文件(在 res/layout* 下): `@string/key`
     * - Kotlin / Java / 其它:              `R.string.key`
     *
     * 在 WriteCommandAction 中执行,确保 IDE 撤销/重做栈能正确记录本次修改。
     */
    private fun replaceSelection(
        editor: Editor,
        file: VirtualFile?,
        selectionStart: Int,
        selectionEnd: Int,
        key: String,
    ) {
        if (key.isBlank()) return
        if (selectionStart < 0 || selectionEnd <= selectionStart) return
        val document = editor.document
        if (selectionEnd > document.textLength) return

        val replacement = if (isLayoutXmlFile(file)) "@string/$key" else "R.string.$key"

        WriteCommandAction.runWriteCommandAction(editor.project) {
            try {
                document.replaceString(selectionStart, selectionEnd, replacement)
                editor.selectionModel.removeSelection()
                val caret = selectionStart + replacement.length
                editor.caretModel.moveToOffset(caret)
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            } catch (e: Exception) {
                Messages.showMessageDialog(
                    "Failed to replace selection: ${e.message ?: "unknown"}",
                    "Extract strings.xml",
                    Messages.getErrorIcon()
                )
            }
        }
    }

    /**
     * 判断文件是否位于 Android 模块的 res/layout* 目录(布局 XML)。
     * res/values* 下的 strings.xml / colors.xml 等也算 XML,但语义上是"被插入",
     * 不是"被引用",所以走 R.string.key 路径反而更安全 —— 这里严格按 layout 判断。
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
 * 与 [AskAiChatHolder] 几乎一致,只在 [onInsertStringsInserted] 上覆写:
 * 把 driver 拿到的 key 转发到构造时传入的回调,由 [ExtractStringsAction] 在 EDT
 * 上完成选区替换。
 */
private class ExtractStringsChatHolder(
    override val project: Project,
    private val onKeyInserted: (String) -> Unit,
) : ChatStateHolder {
    override val insertStringsManager: InsertStringsManager =
        InsertStringsManager.getInstance(project)

    override val chatMessages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    override var chatInput: String by mutableStateOf("")
    override var chatSending: Boolean by mutableStateOf(false)

    @Volatile
    override var stopRequested: Boolean = false
    override var pendingAskUserToolCallId: String? = null
    override var askUserCallCount: Int = 0
    override var toolDocLoadCount: Int = 0
    override var pendingSheetsInsert: PendingSheetsInsert? = null
    override var showContextPopup: Boolean by mutableStateOf(false)
    override var chatContextText: String by mutableStateOf("")

    override val keyEntries: MutableList<KeyedStringsInfo> = mutableListOf()
    override var selectedKeyIndex: Int = 0
    override val sheetsAvailableSheets: MutableList<SheetsManager.SheetInfo> = mutableListOf()
    override val rows: List<StringRow> = emptyList()

    private var toastLabel: JBLabel? = null
    private var toastTimer: Timer? = null

    fun bindToastLabel(label: JBLabel) {
        toastLabel = label
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

    override fun onInsertStringsInserted(key: String, module: String?) {
        onKeyInserted(key)
    }
}
