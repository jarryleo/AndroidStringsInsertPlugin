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
import androidx.compose.runtime.remember
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
    onQuickSend: (String) -> Unit,
    onOptionClick: (Int, String) -> Unit,
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
                    itemsIndexed(chatMessages) { index, msg ->
                        ChatBubble(
                            message = msg,
                            messageIndex = index,
                            onOptionClick = onOptionClick,
                            chatSending = chatSending,
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

        val quickPhrases = remember {
            listOf(
                "帮我检查表格全部的翻译",
                "帮我修正完善表格全部翻译",
                "帮我检查选择的翻译是否有误",
                "帮我补全和修正选中的翻译",
                "帮我把选中的翻译插入表格",
                "帮我从表格读取选中的翻译并插入文件",
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            quickPhrases.forEach { phrase ->
                CompactButton(
                    text = phrase,
                    onClick = { onQuickSend(phrase) },
                    enabled = !chatSending,
                    colors = colors,
                )
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
    messageIndex: Int,
    onOptionClick: (Int, String) -> Unit,
    chatSending: Boolean,
    colors: IdeColors,
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
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
        if (isTool) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(colors.fieldBackground, RoundedCornerShape(6.dp))
                    .border(width = 1.dp, color = colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                    )
                }
            }
        } else {
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
        if (!isUser && !isTool && message.options.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 4.dp, end = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                message.options.forEach { option ->
                    CompactButton(
                        text = option,
                        onClick = { onOptionClick(messageIndex, option) },
                        enabled = !chatSending,
                        colors = colors,
                        tone = classifyOptionTone(option),
                    )
                }
            }
        }
    }
}

private fun classifyOptionTone(text: String): ButtonTone {
    val lower = text.trim().lowercase()
    if (lower.isEmpty()) return ButtonTone.NEUTRAL

    val negativeKeywords = listOf(
        "取消", "否", "拒绝", "停止", "否认", "放弃", "关闭",
        "cancel", "no", "deny", "stop", "abort", "discard", "close", "quit", "reject"
    )
    val warningKeywords = listOf(
        "覆盖", "删除", "清空", "移除", "重置", "替换", "格式化",
        "overwrite", "delete", "remove", "clear", "reset", "replace", "drop", "wipe", "format"
    )
    val positiveKeywords = listOf(
        "追加", "插入", "同意", "确认", "允许", "继续", "确定", "执行", "保存", "应用", "是", "好", "可以",
        "在列表末尾", "新建", "添加", "创建",
        "append", "insert", "yes", "ok", "confirm", "allow", "continue", "apply", "save",
        "add", "create", "new", "proceed", "accept", "agree"
    )
    val infoKeywords = listOf(
        "查看", "读取", "检查", "选择", "切换", "浏览", "详情", "帮助",
        "view", "read", "check", "select", "switch", "browse", "detail", "help", "show", "inspect"
    )

    return when {
        negativeKeywords.any { lower.contains(it) } -> ButtonTone.NEGATIVE
        warningKeywords.any { lower.contains(it) } -> ButtonTone.WARNING
        positiveKeywords.any { lower.contains(it) } -> ButtonTone.POSITIVE
        infoKeywords.any { lower.contains(it) } -> ButtonTone.INFO
        else -> ButtonTone.NEUTRAL
    }
}
