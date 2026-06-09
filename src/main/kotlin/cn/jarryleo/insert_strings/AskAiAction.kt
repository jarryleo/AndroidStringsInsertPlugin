package cn.jarryleo.insert_strings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AITranslator
import cn.jarryleo.insert_strings.ai.AiSettingsService
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ui.*
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class AskAiAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText
        e.presentation.isEnabledAndVisible = editor != null && !selectedText.isNullOrBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
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

        showChatDialog(editor, selectedText)
    }

    private fun showChatDialog(
        editor: com.intellij.openapi.editor.Editor,
        selectedText: String
    ) {
        val window = SwingUtilities.getWindowAncestor(editor.component)
        val dialog = JDialog(window, "Ask AI", Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isResizable = true
        }

        val composePanel = ComposePanel()

        val titleBar = createTitleBar(dialog, "Ask AI")

        val contentPanel = JPanel(BorderLayout()).apply {
            add(titleBar, BorderLayout.NORTH)
            add(composePanel, BorderLayout.CENTER)
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

        composePanel.setContent {
            MaterialTheme {
                AskAiChatContent(
                    selectedText = selectedText,
                    onClose = { dialog.dispose() }
                )
            }
        }

        dialog.isVisible = true
    }

    private fun createTitleBar(dialog: JDialog, title: String): JPanel {
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

        val closeBtn = JButton("Close").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            font = font.deriveFont(Font.BOLD, 14f)
            preferredSize = Dimension(60, 24)
            margin = Insets(8, 0, 0, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { dialog.dispose() }
        }
        titleBar.add(closeBtn, BorderLayout.EAST)

        val dragStart = Point()
        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStart.setLocation(e.point)
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

        return titleBar
    }
}

@Composable
private fun AskAiChatContent(
    selectedText: String,
    onClose: () -> Unit
) {
    val colors = rememberIdeColors()
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    var chatInput by remember { mutableStateOf("") }
    var chatSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    if (selectedText.isNotBlank()) {
        LaunchedEffect(Unit) {
            val firstMessage = ChatMessage(
                "user",
                "请分析并解释以下从编辑器中选中的代码或文本内容。请用简洁清晰的中文回答，重点说明其含义、用途和关键逻辑。\n\n```\n$selectedText\n```"
            )
            chatMessages.add(firstMessage)
            chatSending = true
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = AITranslator.chat(chatMessages.toList(), "")
                SwingUtilities.invokeLater {
                    chatMessages.add(ChatMessage("assistant", result))
                    chatSending = false
                }
            }
        }
    }

    LaunchedEffect(chatMessages.size, chatSending) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    fun sendMessage() {
        val input = chatInput.trim()
        if (input.isEmpty() || chatSending) return
        chatMessages.add(ChatMessage("user", input))
        chatInput = ""
        chatSending = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.chat(chatMessages.toList(), "")
            SwingUtilities.invokeLater {
                chatMessages.add(ChatMessage("assistant", result))
                chatSending = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.panel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
                .background(colors.tableBackground, RoundedCornerShape(4.dp))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(chatMessages) { _, msg ->
                    AskAiChatBubble(message = msg, colors = colors)
                }
                if (chatSending) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(colors.fieldBackground, RoundedCornerShape(8.dp))
                                    .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "Thinking...",
                                    color = colors.secondaryText,
                                    style = compactTextStyle(colors.secondaryText),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 10,
                colors = colors,
            )
            CompactButton(
                text = if (chatSending) "..." else "Send",
                onClick = { sendMessage() },
                modifier = Modifier.width(56.dp),
                colors = colors,
                primary = true,
            )
        }
    }
}

@Composable
private fun AskAiChatBubble(
    message: ChatMessage,
    colors: IdeColors,
) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) colors.accent else colors.fieldBackground
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val border = if (isUser) {
        Modifier
    } else {
        Modifier.border(width = 1.dp, color = colors.border, RoundedCornerShape(12.dp))
    }
    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = alignment,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp)
                    .background(bubbleColor, RoundedCornerShape(12.dp))
                    .then(border)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                SelectionContainer {
                    MarkdownContent(
                        markdown = message.content,
                        colors = colors,
                    )
                }
            }
        }
    }
}
