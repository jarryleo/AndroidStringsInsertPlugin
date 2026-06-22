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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * 在编辑器右键 / Edit 菜单中触发,弹出一个与 InsertStrings 主面板聊天页
 * "等价 AI 操作能力"的轻量 dialog。
 *
 * 复用 [InsertStringsChatDriver] + 三个 controller + [InsertStringsChatContextBuilder],
 * 状态由 [AskAiChatHolder] 持有(实现 [ChatStateHolder],与主面板的 InsertStringsUI 同型)。
 * 弹框外壳(`JDialog` + 自绘标题栏 + Close 按钮)保持不变;内容区用主面板同款
 * [AiChatContent] Composable,从而获得工具调用 / ask_user / Context / Stop 等全套能力。
 */
class AskAiAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selectedText = editor.selectionModel.selectedText ?: ""

        val settings = AiSettingsService.getInstance().state
        if (settings.url.isBlank() || settings.apiKey.isBlank()) {
            Messages.showMessageDialog(
                "Please configure AI settings in the InsertStrings tool window.",
                "Ask AI",
                Messages.getInformationIcon()
            )
            return
        }

        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: editor.virtualFile
        val context = ContextManager.getInstance(project)
        context.ensureInitialized()
        context.updateCurrentModule(currentFile)

        showChatDialog(editor, project, selectedText)
    }

    private fun showChatDialog(
        editor: com.intellij.openapi.editor.Editor,
        project: Project,
        selectedText: String
    ) {
        val window = SwingUtilities.getWindowAncestor(editor.component)
        val dialog = JDialog(window, "Ask AI", Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isResizable = true
        }

        // 弹框持有的轻量 chat state。showToast 直接改标题栏右侧的状态标签,
        // 其它表格相关回调全部空实现 —— 弹框不展示 strings.xml 表格。
        val state = AskAiChatHolder(project)

        val composePanel = ComposePanel()
        val (titleBar, toastLabel) = createTitleBar(dialog, "Ask AI")
        state.bindToastLabel(toastLabel)

        val resizeGrip = createResizeGrip(dialog)
        // 用一个 BorderLayout 子容器把 grip 推到整条 SOUTH 的最右侧,
        // 直接放 FlowLayout 会被 BorderLayout 把容器本身拉满到整宽,grip 反而停在容器内 16px 处。
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

        // 装配 driver + 三个 controller(共享同一 ChatStateHolder)
        val stringsOps = InsertStringsStringsOpsController(state)
        val sheetsOps = InsertStringsSheetsOpsController(state)
        val contextBuilder = InsertStringsChatContextBuilder(state)
        val driver = InsertStringsChatDriver(
            state = state,
            stringsOps = stringsOps,
            sheetsOps = sheetsOps,
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
                    // 弹框 UI 最小化:只保留消息列表 + 输入区
                    showHeader = false,
                    showQuickPhrases = false,
                    showEnterHint = false,
                    // 消息列表用更紧凑的 padding,让气泡更靠近弹框边缘
                    messageListContentPadding = PaddingValues(8.dp),
                )
            }
        }

        dialog.isVisible = true

        // 复刻原行为:有选中文字时,把"解释选中代码"作为首条 user 消息自动发出
        if (selectedText.isNotBlank()) {
            val firstMessage =
                "请分析并解释以下从编辑器中选中的代码或文本内容。请用简洁清晰的中文回答，重点说明其含义、用途和关键逻辑。\n\n```\n$selectedText\n```"
            ApplicationManager.getApplication().executeOnPooledThread {
                driver.sendChatMessage(firstMessage)
            }
        }
    }

    /**
     * 右下角 resize grip:小三角组件,拖动它即可调整 dialog 尺寸。
     * 解决 undecorated JDialog 默认无可见缩放手柄的问题。
     */
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

        // 拖动调整 dialog 尺寸
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

    /**
     * 自绘标题栏:左侧标题、中部 toast(平时空)、右侧 Close 按钮。
     * 整条支持拖动。
     *
     * @return (titleBar, toastLabel) toastLabel 由 caller 注入到 ChatHolder 以便 showToast 用。
     */
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
 * AskAi 弹框使用的 [ChatStateHolder] 轻量实现。
 *
 * 持有 dialog 生命周期内的 chat state + driver 引用。
 * 表格相关字段为空容器(不与主面板共享,controllers 写入会被丢弃,符合"弹框不展示表格"语义)。
 * showToast 直接把文字写到标题栏中央的 toast 标签上(1.8s 后自动清空)。
 */
private class AskAiChatHolder(
    override val project: Project
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

    // 弹框无表格 —— 提供空容器,controllers 写入即丢弃
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
}
