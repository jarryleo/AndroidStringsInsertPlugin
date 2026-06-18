package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.ChatMessage

@Composable
fun AiChatContent(
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                text = "New Topic",
                onClick = onNewChat,
                modifier = Modifier.width(82.dp),
                colors = colors,
            )
            Text(
                text = "AI Chat",
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = compactTextStyle(colors.text),
                fontWeight = FontWeight.Bold,
            )
            CompactButton(
                text = "Back",
                onClick = onClose,
                modifier = Modifier.width(56.dp),
                colors = colors,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
                .background(colors.tableBackground, RoundedCornerShape(4.dp))
        ) {
            if (chatMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Start a conversation...",
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(chatMessages) { _, msg ->
                        ChatBubble(
                            message = msg,
                            colors = colors,
                        )
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
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactTextField(
                value = chatInput,
                onValueChange = onChatInputChange,
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 10,
                colors = colors,
            )
            CompactButton(
                text = if (chatSending) "..." else "Send",
                onClick = onSendChat,
                modifier = Modifier.width(56.dp),
                colors = colors,
                primary = true,
            )
        }
    }
}

@Composable
private fun ChatBubble(
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
