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
import cn.jarryleo.insert_strings.ai.ChatAttachment
import cn.jarryleo.insert_strings.ai.ChatAttachmentLoadResult
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.sheets.SheetsManager
import cn.jarryleo.insert_strings.ui.*
import cn.jarryleo.insert_strings.xml.ContextManager
import cn.jarryleo.insert_strings.xml.KeyedStringsInfo
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
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
            // 2026.x 圆角边框:把 dialog 自身背景设透明,setShape 圆角外的区域不被
            // 系统默认白底填满,真正呈现「圆角矩形」效果。
            background = Color(0, 0, 0, 0)
        }

        // 弹框持有的轻量 chat state。showToast 直接改标题栏右侧的状态标签,
        // 其它表格相关回调全部空实现 —— 弹框不展示 strings.xml 表格。
        val state = AskAiChatHolder(project)

        // 捕获编辑器选区(给 AI 的 replace_selection 工具 / driver 触发的
        // onInsertStringsInserted 使用,例如用户点选 ask_user 的「使用现有 key:<existing_key>」后,
        // AI 用 replace_selection 把硬编码文本替换为对 key 的引用)。
        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd
        state.bindEditorSelection(
            editor = editor,
            file = editor.virtualFile,
            selectedText = selectedText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
        // 把选中文本作为「引用内容」交给 chat 顶部以独立气泡展示,
        // 不再像旧版那样自动以首条 user 消息发出"解释选中代码" —— 旧行为隐式且无法关闭。
        // 用户在输入框自行决定如何向 AI 提问;如确需"解释选中",可在输入框中直接敲 "解释"。
        if (selectedText.isNotBlank()) {
            state.quoteContent = selectedText
        }

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
            // 2026.x:不再用 LineBorder —— 矩形 1px 边框线在 setShape 圆角裁剪下
            // 四个角处会被切掉,产生「边框在圆角处消失」的破洞。
            // 改用 dialog.glassPane 画 1px 圆角描边(见下方),覆盖在所有子组件之上。
        }

        dialog.contentPane = contentPanel
        // 2026.x 弹框尺寸:默认竖屏布局(聊天是纵向滚动的内容),保证 width <= height。
        // 编辑器很宽(4K / 多列)时强制把 width 拉到不超 height,避免弹出「扁宽条」。
        val rawWidth = (editor.component.width * 0.3).toInt().coerceIn(320, 600)
        val rawHeight = (editor.component.height * 0.4).toInt().coerceIn(400, 760)
        val width = minOf(rawWidth, rawHeight)
        val height = maxOf(rawHeight, width + 40)
        dialog.preferredSize = Dimension(width, height)
        dialog.pack()

        val editorLoc = editor.component.locationOnScreen
        dialog.setLocation(
            editorLoc.x + (editor.component.width - dialog.width) / 2,
            editorLoc.y + (editor.component.height - dialog.height) / 2
        )

        // 2026.x 圆角边框:用 Window.setShape 把 dialog 裁成圆角矩形,resize 时重新应用。
        val cornerRadius = 12.0
        fun applyRoundedShape() {
            dialog.shape = RoundRectangle2D.Double(
                0.0, 0.0,
                dialog.width.toDouble(), dialog.height.toDouble(),
                cornerRadius, cornerRadius
            )
        }
        applyRoundedShape()
        dialog.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyRoundedShape()
        })

        // 2026.x 圆角描边:用 dialog.glassPane 在所有子组件之上画 1px 圆角矩形描边。
        // 解决「矩形 LineBorder 在 setShape 圆角裁剪下,四个角处出现破洞」的问题——
        // glassPane 在 children 之后绘制,描边线完整覆盖圆角路径。
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color(0xC8C8C8)
        dialog.glassPane = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                if (!isVisible) return
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderColor
                // stroke 1px,画在 [0, width-1] 范围内(Graphics2D 描边以中心线计)。
                g2.draw(RoundRectangle2D.Double(
                    0.5, 0.5,
                    (width - 1).toDouble(), (height - 1).toDouble(),
                    cornerRadius, cornerRadius
                ))
                g2.dispose()
            }
        }.apply { isVisible = true }

        // 装配 driver + 五个 controller(共享同一 ChatStateHolder)
        val stringsOps = InsertStringsStringsOpsController(state)
        val sheetsOps = InsertStringsSheetsOpsController(state)
        val fileOps = InsertStringsFileOpsController(state)
        val editorOps = InsertStringsEditorOpsController(state)
        // 把 driver 触发的 onInsertStringsInserted 自动替换路由到 controller 的 runReplaceSelection,
        // 与 AI 显式调用 replace_selection 工具走同一条路径,保证三层定位 + 防双重替换行为一致。
        // 2026.x:replace_selection 工具参数为 (oldText, newText) —— 自动替换场景下
        // oldText = 入口捕获的整段硬编码(选区文本),newText = 拼出的对 key 的引用;
        // 选区文本等于 oldText,选区内只匹配 1 次,行为等价于「整段选区替换」。
        val currentFile = editor.virtualFile
        val capturedSelectionText = selectedText
        state.bindOnAutoReplace { key ->
            val newText = InsertStringsEditorOpsController.buildReferenceText(currentFile, key)
            editorOps.runReplaceSelection(AiAction.ReplaceSelection(capturedSelectionText, newText))
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
                    // 弹框 UI 最小化:只保留消息列表 + 输入区
                    showHeader = false,
                    showQuickPhrases = false,
                    showEnterHint = false,
                    // 消息列表用更紧凑的 padding,让气泡更靠近弹框边缘
                    messageListContentPadding = PaddingValues(8.dp),
                    // 入口打开时携带的引用内容(编辑器选区)。非空时会在消息列表顶部
                    // 居中显示一个可折叠的气泡,随着聊天列表一起滚动。
                    quoteContent = state.quoteContent,
                    onQuoteDismiss = {
                        state.quoteContent = null
                    },
                    // 引用面板的「复制」按钮:把引用文本写入系统剪贴板 + 走 state.showToast
                    // 在标题栏中央弹 1.8s 提示。ClipboardManager 是项目自带的跨平台
                    // AWT 剪贴板封装(基于 java.awt.Toolkit),与现有 copy 行为一致。
                    onCopyQuote = { text ->
                        ClipboardManager.setSysClipboardText(text)
                        state.showToast("已复制到剪贴板")
                    },
                    // ===== 多模态图片(2026.x 新增)=====
                    pendingImages = state.pendingImages,
                    onPickImage = { onPickImageClicked(state) },
                    onRemoveImage = { id -> state.pendingImages.removeAll { it.id == id } },
                    onImageDropped = { t -> onImageDroppedOrPasted(state, t) },
                    onPasteFromClipboard = { onPasteFromClipboard(state) },
                )
            }
        }

        dialog.isVisible = true
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
     * 2026.x 微调:
     * 1. 标题栏高度 36px(24px 内容 + 6px 上 + 6px 下),四面等距 padding(EmptyBorder(6,6,6,6)),
     *    让「标题左侧 = 关闭右侧 = 上下间距」都是 6px,视觉上是一个均匀的方框。
     * 2. 标题 label 强制 24px 高 + TOP_ALIGNMENT,与关闭按钮顶部对齐,二者上下边界重合。
     * 3. 关闭按钮改成「实按钮」(带边框 + 背景),不再用透明文字,更直观;
     *    大小 24×24,内部用 ✕ 字符作为关闭图标(占位小、跨平台)。
     *
     * @return (titleBar, toastLabel) toastLabel 由 caller 注入到 ChatHolder 以便 showToast 用。
     */
    private fun createTitleBar(dialog: JDialog, title: String): Pair<JPanel, JBLabel> {
        // 标题栏内边距 6px(上下左右),与关闭按钮 24×24 一起决定整条高度 = 6+24+6 = 36。
        val titleBarHeight = 36
        val innerPad = 6
        val controlSize = 24  // 标题 label 与关闭按钮统一 24px 高,保证两者上下边对齐

        val titleBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            // 四面等距 padding —— 让"标题左侧 / 关闭右侧"等于"上 / 下"。
            border = BorderFactory.createEmptyBorder(innerPad, innerPad, innerPad, innerPad)
            val bgColor = UIManager.getColor("Panel.background") ?: Color(0xF2F2F2)
            background = bgColor
            isOpaque = true
            preferredSize = Dimension(0, titleBarHeight)
            minimumSize = Dimension(0, titleBarHeight)
        }

        val label = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            // 强制 24px 高,与关闭按钮上下边对齐(TOP_ALIGNMENT 让两者都从 y=innerPad 开始)。
            val orig = preferredSize
            preferredSize = Dimension(orig.width, controlSize)
            maximumSize = Dimension(Int.MAX_VALUE, controlSize)
            alignmentY = Component.TOP_ALIGNMENT
        }
        titleBar.add(label)

        // 弹性间距:把 toast 推到中间、Close 推到最右
        titleBar.add(Box.createHorizontalGlue())

        val toastLabel = JBLabel("").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = UIManager.getColor("Label.foreground") ?: Color(0x606060)
            val orig = preferredSize
            preferredSize = Dimension(orig.width, controlSize)
            maximumSize = Dimension(Int.MAX_VALUE, controlSize)
            alignmentY = Component.TOP_ALIGNMENT
        }
        titleBar.add(toastLabel)
        titleBar.add(Box.createHorizontalGlue())

        // 实心 24×24 关闭按钮:用 ✕ Unicode 字符作图标,带平台默认按钮背景 + 1px 灰色描边,
        // 不再用之前「透明文字看着像 label」的实现。
        val closeBtn = JButton("✕").apply {
            isFocusPainted = false
            preferredSize = Dimension(controlSize, controlSize)
            minimumSize = Dimension(controlSize, controlSize)
            maximumSize = Dimension(controlSize, controlSize)
            margin = JBUI.emptyInsets()
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = UIManager.getColor("Button.foreground") ?: Color(0x333333)
            // 平台默认按钮背景 + 1px 描边,看起来像个真正的按钮
            isContentAreaFilled = true
            background = UIManager.getColor("Button.background") ?: Color(0xE6E6E6)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    UIManager.getColor("Button.borderColor") ?: Color(0xBFBFBF), 1
                ),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentY = Component.TOP_ALIGNMENT
            // toolTip 让用户 hover 时知道这是关闭按钮(✕ 字符跨平台)
            toolTipText = "Close"
            addActionListener { dialog.dispose() }
        }
        titleBar.add(closeBtn)

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

// ============== 多模态图片相关工具(2026.x 新增)==============
// 三个聊天入口(主面板 / AskAi / ExtractStrings)复用同一套实现,放在顶层函数里共享。
// 入参是 [ChatStateHolder] 即可。

/**
 * 「📎」按钮回调:弹文件选择器,选中的图片加入 [ChatStateHolder.pendingImages]。
 */
internal fun onPickImageClicked(state: ChatStateHolder) {
    try {
        val picked = ChatImagePicker.pickImageFiles(state.project)
        picked.forEach { att ->
            if (state.pendingImages.none { it.id == att.id }) {
                state.pendingImages.add(att)
            }
        }
    } catch (t: Throwable) {
        state.showToast("选图失败: ${t.message}")
    }
}

/**
 * 拖拽图片到聊天输入框时的回调:从 Transferable 读图并加入 [ChatStateHolder.pendingImages]。
 */
internal fun onImageDroppedOrPasted(state: ChatStateHolder, transferable: Transferable) {
    when (val r = ChatImagePicker.addFromTransferable(transferable)) {
        is ChatAttachmentLoadResult.Ok -> {
            if (state.pendingImages.none { it.id == r.attachment.id }) {
                state.pendingImages.add(r.attachment)
            }
        }
        is ChatAttachmentLoadResult.Error -> state.showToast(r.message)
        ChatAttachmentLoadResult.Unavailable -> { /* 静默忽略 */ }
    }
}

/**
 * Ctrl+V / Cmd+V 拦截(2026.x 多模态):
 *  - 剪贴板是图片 → 加入 [ChatStateHolder.pendingImages];
 *  - 剪贴板是文字 → 追加到 [ChatStateHolder.chatInput](等同原生粘贴);
 *  - 空 / 其它 → toast 提示。
 */
internal fun onPasteFromClipboard(state: ChatStateHolder) {
    when (val r = ChatImagePicker.addFromClipboard()) {
        is ChatAttachmentLoadResult.Ok -> {
            if (state.pendingImages.none { it.id == r.attachment.id }) {
                state.pendingImages.add(r.attachment)
            }
            return
        }
        is ChatAttachmentLoadResult.Error -> {
            state.showToast(r.message)
            return
        }
        ChatAttachmentLoadResult.Unavailable -> { /* 走文字兜底 */ }
    }
    runCatching {
        val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .getContents(null)
            ?.takeIf { it.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) }
            ?.let { it.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String }
        if (!text.isNullOrEmpty()) {
            state.chatInput = state.chatInput + text
        } else {
            state.showToast("剪贴板为空")
        }
    }.onFailure { state.showToast("粘贴失败: ${it.message}") }
}

/**
 * AskAi 弹框使用的 [ChatStateHolder] 轻量实现。
 *
 * 持有 dialog 生命周期内的 chat state + driver 引用。
 * 表格相关字段为空容器(不与主面板共享,controllers 写入会被丢弃,符合"弹框不展示表格"语义)。
 * showToast 直接把文字写到标题栏中央的 toast 标签上(1.8s 后自动清空)。
 *
 * 编辑器选区:在 [AskAiAction] 调用 [bindEditorSelection] 时注入,使 [onInsertStringsInserted]
 * 与 [AiAction.ReplaceSelection] 工具都能把硬编码选区替换为对 key 的引用。
 */
private class AskAiChatHolder(
    override val project: Project
) : ChatStateHolder {
    override val insertStringsManager: InsertStringsManager =
        InsertStringsManager.getInstance(project)

    override val chatMessages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    override var chatInput: String by mutableStateOf("")
    override var chatSending: Boolean by mutableStateOf(false)

    // 当前 chat 入口标识 —— AskAi 弹框固定 "askAi",由 ChatStateHolder.chatEntry 写入上下文
    // 让 AI 知道入口类型,避免「使用现有 key」后跳过 replace_selection。
    override val chatEntry: String = "askAi"

    // 入口打开时携带的「引用内容」:编辑器选中的文本会作为引用气泡展示在 chat 顶部
    // (不再自动以首条 user 消息发出"解释选中代码"这种隐式 prompt)。
    override var quoteContent: String? = null

    // 待发送的图片附件(2026.x 多模态):粘贴/选择/拖拽进来的图,
    // 发送时随本条 user 消息发出,发送后清空。
    override val pendingImages: SnapshotStateList<ChatAttachment> = mutableStateListOf()

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

    // 弹框无表格 —— 提供空容器,controllers 写入即丢弃
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
     * 注入 chat 入口打开时的编辑器选区(由 [AskAiAction.showChatDialog] 在构造后立即调用)。
     * 用于 AI 通过 [AiAction.ReplaceSelection] 工具或 driver 触发
     * [onInsertStringsInserted] 时,把硬编码选区替换为对 key 的引用。
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
     * 注入「driver 完成 insert_strings 后自动触发的替换回调」。
     * 由 [AskAiAction.showChatDialog] 在构造完 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController]
     * 后调用,内部走 controller 的 [cn.jarryleo.insert_strings.ui.InsertStringsEditorOpsController.runReplaceSelection]
     * 与 replace_selection 工具完全相同的替换/防双重替换路径,避免在此处维护重复实现。
     */
    fun bindOnAutoReplace(callback: (String) -> Unit) {
        this.onAutoReplace = callback
    }

    /**
     * AskAi 入口的 [onInsertStringsInserted] 实现:把 [editorSelection] 选中的硬编码文本
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

