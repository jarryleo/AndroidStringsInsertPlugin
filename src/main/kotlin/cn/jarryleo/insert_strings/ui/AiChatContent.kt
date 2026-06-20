package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                        // 工具气泡携带任务摘要:取前一条 assistant 消息的文本作为 AI 意图
                        val taskSummary = if (msg.role == "tool" && index > 0) {
                            val prev = chatMessages.getOrNull(index - 1)
                            if (prev?.role == "assistant" &&
                                prev.toolCalls.isNotEmpty() &&
                                prev.content.isNotBlank()
                            ) {
                                prev.content
                            } else null
                        } else null
                        ChatBubble(
                            message = msg,
                            taskSummary = taskSummary,
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
    taskSummary: String? = null,
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
            ToolBubble(
                content = message.content,
                taskSummary = taskSummary,
                colors = colors,
            )
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

@Composable
private fun ToolBubble(
    content: String,
    colors: IdeColors,
    taskSummary: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // 主摘要:从 content 抽取操作名 + 状态 + 简略信息
    val summary = remember(content) {
        buildToolSummary(content)
    }
    val lineCount = remember(content) {
        content.count { it == '\n' } + 1
    }
    val displayTaskSummary = taskSummary?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .background(colors.fieldBackground, RoundedCornerShape(6.dp))
            .border(width = 1.dp, color = colors.border, RoundedCornerShape(6.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▼" else "▶",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = summary,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!expanded) {
                Text(
                    text = "($lineCount 行)",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        }
        // 任务摘要:紧贴折叠头下方,折叠/展开状态均显示
        if (displayTaskSummary != null) {
            Text(
                text = "任务: ${truncateSummary(displayTaskSummary, 80)}",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 10.dp, bottom = 5.dp),
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = content,
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * 从工具结果 content 中抽取「操作名 + 状态 + 简略信息」,作为折叠状态的单行摘要。
 * 兼容多种消息格式,失败时回退到首行截断。
 */
private fun buildToolSummary(content: String): String {
    val trimmed = content.trim()
    return when {
        trimmed.startsWith("[工具执行结果]") -> {
            val op = extractField(trimmed, "操作") ?: "操作"
            val success = trimmed.contains("状态:成功")
            val icon = if (success) "✅" else "❌"
            val info = extractField(trimmed, "信息")
            if (info != null) {
                "$icon $op · $info"
            } else {
                "$icon $op"
            }
        }
        trimmed.startsWith("[用户取消]") -> {
            val rest = trimmed.removePrefix("[用户取消]").trim()
            "🚫 已取消 · ${rest.ifEmpty { "操作" }}"
        }
        trimmed.startsWith("[工具执行异常]") -> {
            val rest = trimmed.removePrefix("[工具执行异常]").trim()
            "❌ 异常 · ${truncateText(rest, 50)}"
        }
        trimmed.startsWith("[系统监督]") -> "系统监督提示"
        trimmed.startsWith("[工具文档加载失败]") -> "❌ 文档加载失败"
        trimmed.contains("工具文档") -> "📄 工具文档加载"
        else -> {
            val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            if (firstLine.length > 60) firstLine.take(60) + "…" else firstLine
        }
    }
}

/** 从 `key:xxx` 形式中抽取字段值(简单实现,不支持转义)。 */
private fun extractField(text: String, key: String): String? {
    val marker = "$key:"
    val start = text.indexOf(marker)
    if (start < 0) return null
    val valueStart = start + marker.length
    // 取到下一个空格分隔字段之前
    val rest = text.substring(valueStart)
    val endIdx = rest.indexOf(' ').let { if (it < 0) rest.length else it }
    val raw = rest.substring(0, endIdx).trim()
    return raw.takeIf { it.isNotEmpty() }
}

private fun truncateText(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "…"

private fun truncateSummary(text: String, max: Int): String {
    val cleaned = text.replace('\n', ' ').trim()
    return if (cleaned.length <= max) cleaned else cleaned.take(max) + "…"
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
