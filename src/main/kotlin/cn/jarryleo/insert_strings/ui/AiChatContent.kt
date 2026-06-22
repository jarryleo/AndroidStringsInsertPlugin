package cn.jarryleo.insert_strings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.phrases.QuickPhrase

@Composable
fun AiChatContent(
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onStopChat: () -> Unit,
    onQuickSend: (String) -> Unit,
    onOptionClick: (Int, String) -> Unit,
    onOpenContext: () -> Unit,
    onCloseContext: () -> Unit,
    showContextPopup: Boolean,
    chatContextText: String,
    modifier: Modifier = Modifier,
    colors: IdeColors,
    showHeader: Boolean = true,
    showQuickPhrases: Boolean = true,
    /**
     * 快捷短语列表。
     * - 与 [showQuickPhrases] 配合:showQuickPhrases = true 时才渲染;
     * - 列表为空时不显示该区域,避免空 UI 占用空间。
     * - 元素的 [QuickPhrase.color] 控制按钮的文字着色(便于分辨不同类别的快捷短语)。
     */
    quickPhrases: List<QuickPhrase> = emptyList(),
    showEnterHint: Boolean = true,
    /**
     * 消息列表内部的 contentPadding。弹框等紧凑场景可传较小值(如 4.dp),
     * 主面板等标准场景保持默认 8.dp。
     */
    messageListContentPadding: PaddingValues = PaddingValues(8.dp),
    /**
     * 当前用户在 strings.xml 中已选择的 key 列表。
     * - 在消息列表上方展示,左侧标题「已选择翻译(N)」,右侧是高度约 3 行的滚动列表。
     * - 列表为空(未选 key)时不显示整个区域,不占用聊天空间。
     * - 该列表会随着用户重新选 key 同步更新(由外部传入最新值即可)。
     */
    selectedKeys: List<String> = emptyList(),
) {
    Box(modifier = modifier) {
        AiChatBody(
            chatMessages = chatMessages,
            chatInput = chatInput,
            chatSending = chatSending,
            onNewChat = onNewChat,
            onOpenContext = onOpenContext,
            onClose = onClose,
            onChatInputChange = onChatInputChange,
            onSendChat = onSendChat,
            onStopChat = onStopChat,
            onQuickSend = onQuickSend,
            onOptionClick = onOptionClick,
            colors = colors,
            showHeader = showHeader,
            showQuickPhrases = showQuickPhrases,
            quickPhrases = quickPhrases,
            showEnterHint = showEnterHint,
            messageListContentPadding = messageListContentPadding,
            selectedKeys = selectedKeys,
        )
        if (showContextPopup) {
            ContextPopupOverlay(
                contextText = chatContextText,
                onClose = onCloseContext,
                colors = colors,
            )
        }
    }
}

@Composable
private fun AiChatBody(
    chatMessages: List<ChatMessage>,
    chatInput: String,
    chatSending: Boolean,
    onNewChat: () -> Unit,
    onOpenContext: () -> Unit,
    onClose: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onStopChat: () -> Unit,
    onQuickSend: (String) -> Unit,
    onOptionClick: (Int, String) -> Unit,
    colors: IdeColors,
    showHeader: Boolean = true,
    showQuickPhrases: Boolean = true,
    quickPhrases: List<QuickPhrase> = emptyList(),
    showEnterHint: Boolean = true,
    messageListContentPadding: PaddingValues = PaddingValues(8.dp),
    selectedKeys: List<String> = emptyList(),
) {
    val listState = rememberLazyListState()
    // 新消息加入时滚到末尾(用户期待看到刚发的内容)
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }
    // 流式生成中:最后一条消息的 thinking / content 会持续变化,需要保持滚到末尾,
    // 否则用户看到的就是"卡住"的旧文本。但用户如果已经主动向上滚动阅读历史,
    // 就不应该把视图强制拉回底部,否则会变成"追着他跑"的糟糕体验。
    // 判定:仅当「最后一项已可见」时跟随滚动,否则让用户保留当前阅读位置。
    val lastStreamingMessage = chatMessages.lastOrNull { it.streaming }
    LaunchedEffect(lastStreamingMessage?.thinking, lastStreamingMessage?.content) {
        if (lastStreamingMessage == null || chatMessages.isEmpty()) return@LaunchedEffect
        val layout = listState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isAtBottom = lastVisible >= chatMessages.size - 1
        if (isAtBottom) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(if (showHeader || showQuickPhrases) 6.dp else 4.dp)
    ) {
        if (showHeader) {
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
                    text = "Context",
                    onClick = onOpenContext,
                    modifier = Modifier.width(68.dp),
                    colors = colors,
                )
                CompactButton(
                    text = "Back",
                    onClick = onClose,
                    modifier = Modifier.width(56.dp),
                    colors = colors,
                )
            }
        }

        // 已选 key 列表:仅在非空时展示,高度约 3 行可滚动。
        if (selectedKeys.isNotEmpty()) {
            SelectedKeysPanel(
                keys = selectedKeys,
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
                    contentPadding = messageListContentPadding,
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
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (showQuickPhrases && quickPhrases.isNotEmpty()) {
            // 快捷短语以按钮形式展示,点击即把 phrase.text 作为用户消息发送。
            // 颜色从 phrase.color 解析,失败/null 沿用 IDE 主题色。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                quickPhrases.forEach { phrase ->
                    val textColor = phrase.toColorOrNull() ?: colors.text
                    CompactButton(
                        text = phrase.title,
                        onClick = { onQuickSend(phrase.text) },
                        enabled = !chatSending,
                        colors = colors.copy(text = textColor),
                    )
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
                // 回车发送,Alt+回车 / Shift+回车 换行
                onSend = onSendChat,
            )
            if (chatSending) {
                CompactButton(
                    text = "Stop",
                    onClick = onStopChat,
                    modifier = Modifier.width(56.dp),
                    colors = colors,
                    primary = false,
                )
            } else {
                CompactButton(
                    text = "Send",
                    onClick = onSendChat,
                    modifier = Modifier.width(56.dp),
                    colors = colors,
                    primary = true,
                )
            }
        }
        if (showEnterHint) {
            Text(
                text = "回车发送 · Alt+回车 / Shift+回车 换行",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * 「已选择翻译」面板:显示在聊天列表上方,用来给用户一个「我现在到底在跟 AI 聊哪些 key」的视觉锚。
 *
 * 布局:
 *  - 顶部小标题:`已选择翻译(N)`,N 为 key 总数,会随 keyEntries 变化而自动同步。
 *  - 下方滚动列表:固定高度 ≈ 3 行(超过 3 行可纵向滚动),单行展示一个 key,长 key 截断省略。
 *  - 整体外框、背景与消息区一致,不喧宾夺主。
 *
 * 注意:本组件不直接持有状态,所有数据由父组件 `selectedKeys` 传入,
 * 这样无论用户何时在 strings.xml 中重新选择,父组件把最新 List 传进来,UI 就自动更新。
 */
@Composable
private fun SelectedKeysPanel(
    keys: List<String>,
    colors: IdeColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
            .background(colors.tableBackground, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 小标题:左侧文案 + 右侧数量
        Text(
            text = "已选择翻译(${keys.size})",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            fontWeight = FontWeight.Bold,
        )
        // key 列表:固定高度 ~ 3 行(每行约 24dp,加 padding 约 80~90dp),超长可纵向滚动
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 84.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(keys) { key ->
                Text(
                    text = key,
                    color = colors.text,
                    style = compactTextStyle(colors.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            // 「解析失败」的工具结果(参数无法解析 / 工具名未注册)对用户没有可操作意义,
            // 但又必须以 tool_result 形式返回给 AI 以满足 Anthropic 协议。
            // 这里把它折叠成助手一侧的简短错误行,不再用 ToolBubble 渲染,
            // 既避免空气泡,又让用户能看到具体是哪个工具调用出了问题。
            if (isParseFailedToolResult(message.content)) {
                ParseFailedInline(
                    content = message.content,
                    colors = colors,
                )
            } else {
                ToolBubble(
                    content = message.content,
                    taskSummary = taskSummary,
                    colors = colors,
                )
            }
        } else {
            // === 助手气泡(assistant / user) ===
            // 助手侧支持三段式结构:Thinking 区(可选) + content 回复区(可选) + options 区(可选)
            // Thinking 区的展示规则:
            //  - 始终在流式生成中展示(显示「Thinking」标题 + 动态脉动圆点,
            //    如果还没有文本就只显示标题+圆点,让用户感知「模型正在思考」);
            //  - 流式结束后,若积累了文本,就折叠成可点击展开的「Thought · N 行」区;
            //  - 流式结束后若 thinking 为空(模型没产生思考文本),整个 Thinking 区消失,
            //    只留下 content 回复区(简洁,不强行造占位)。
            val showThinkingHeader = !isUser &&
                (message.streaming || message.thinking.isNotBlank())
            val showThinkingBox = !isUser && message.thinking.isNotBlank()
            val showReply = !isUser && message.content.isNotBlank()
            val showUserContent = isUser && message.content.isNotBlank()
            // 只要有任何内容要展示,气泡外壳就要画出来
            val hasAnyContent = showUserContent || showThinkingHeader || showReply
            if (hasAnyContent) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = alignment,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp)
                            .background(bubbleColor, RoundedCornerShape(12.dp))
                            .then(border)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (showThinkingHeader) {
                                ThinkingSection(
                                    thinking = message.thinking,
                                    streaming = message.streaming,
                                    showTextBox = showThinkingBox,
                                    colors = colors,
                                )
                            }
                            if (showThinkingHeader && showReply) {
                                // 思考区与回复区之间的细分隔线
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.grid)
                                )
                            }
                            if (showReply) {
                                SelectionContainer {
                                    MarkdownContent(
                                        markdown = message.content,
                                        colors = colors,
                                    )
                                }
                            } else if (showUserContent) {
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

/**
 * 助手气泡内的「Thinking」区块。
 *
 * 三种形态:
 * - 流式中 + 有 thinking 文本:头部 "Thinking" + 脉动圆点 + 下方 3 行可滚动文本框,
 *   实时显示思考内容。
 * - 流式中 + 无 thinking 文本(模型没产生思考):头部 "Thinking" + 脉动圆点,
 *   不展示空文本框(避免空气泡),仅用 loading 表达「正在思考」。
 * - 已结束 + 有 thinking 文本:头部变成可点击的 "Thought · N 行" 折叠条,
 *   点击展开后展示同样的 3 行滚动区,再点击折叠。默认折叠,避免长思考撑高气泡。
 *
 * 文字本身不参与协议,仅 UI 渲染。
 */
@Composable
private fun ThinkingSection(
    thinking: String,
    streaming: Boolean,
    showTextBox: Boolean,
    colors: IdeColors,
) {
    // 只有在 (流式中) 或 (有文本且已被展开) 时才展示文本框
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = streaming || (showTextBox && userExpanded)
    val lineCount = remember(thinking) { thinking.count { it == '\n' } + 1 }
    // 头部文案:
    //  - 流式时统一叫 "Thinking"(若已有文本顺便带上行数)
    //  - 已结束时若有文本则是 "Thought · N 行",否则是 "Thinking"(但此分支 ChatBubble 不会进入)
    val summary = if (streaming) {
        if (showTextBox) "Thinking · $lineCount 行" else "Thinking"
    } else {
        "Thought · $lineCount 行"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 头部:左圆点 / 折叠指示 + 文案
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    if (showTextBox && !streaming) {
                        it.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { userExpanded = !userExpanded }
                    } else it
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (streaming) {
                ThinkingPulseDot(colors = colors)
            } else if (showTextBox) {
                Text(
                    text = if (userExpanded) "▼" else "▶",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
            Text(
                text = summary,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                fontWeight = if (streaming) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (showTextBox) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ThinkingTextBox(
                    text = thinking,
                    streaming = streaming,
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 思考文字滚动区:固定高度 3 行,超长可纵向滚动。
 * 流式时(scrollToBottom=true)实时滚到最底;折叠展开后由用户自行滚动。
 */
@Composable
private fun ThinkingTextBox(
    text: String,
    streaming: Boolean,
    colors: IdeColors,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        if (streaming) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 60.dp)
            .background(colors.tableBackground, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, colors.grid), RoundedCornerShape(6.dp))
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )
    }
}

/**
 * 「Thinking」头部左侧的动态脉动圆点:1.2s 一周期的呼吸效果,
 * 通过 alpha 在 0.3~1.0 之间循环往复,提示用户 AI 仍在生成。
 */
@Composable
private fun ThinkingPulseDot(colors: IdeColors) {
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(colors.accent, CircleShape)
    )
}

/**
 * 弹出当前 AI 所知上下文的浮层。
 * - 整页半透明遮罩,点击空白处关闭
 * - 中央卡片承载 pretty-print 后的 JSON 内容,支持滚动
 * - 卡片内右上角"X"按钮关闭浮层
 */
@Composable
private fun ContextPopupOverlay(
    contextText: String,
    onClose: () -> Unit,
    colors: IdeColors,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(min = 320.dp, max = 640.dp)
                .heightIn(min = 240.dp, max = 520.dp)
                .background(colors.panel, RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI 上下文(每轮请求都会带上)",
                    modifier = Modifier.weight(1f),
                    color = colors.text,
                    style = compactTextStyle(colors.text),
                    fontWeight = FontWeight.Bold,
                )
                CompactButton(
                    text = "X",
                    onClick = onClose,
                    modifier = Modifier.width(28.dp),
                    colors = colors,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (contextText.isBlank()) {
                Text(
                    text = "(当前无可用上下文:可能未打开 strings.xml 或未配置 Google Sheets)",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(colors.tableBackground, RoundedCornerShape(4.dp))
                        .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .verticalScroll(scrollState),
                ) {
                    SelectionContainer {
                        Text(
                            text = contextText,
                            color = colors.text,
                            style = compactTextStyle(colors.text).copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "点击空白处或按 X 关闭",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
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
            .padding(vertical = 2.dp)
            .padding(end = 32.dp)
            .background(colors.fieldBackground, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = colors.border, RoundedCornerShape(12.dp)),
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

/**
 * 判断该 tool 消息是否为「解析失败」类型。
 * 失败格式由 InsertStringsChatDriver 合成的:
 *   [工具执行结果] 类型:unknown(xxx) 状态:解析失败 信息:该工具调用的参数无法解析或工具名未注册。请检查调用格式后重试。
 * 对用户没有可操作价值,且会与正常 ToolBubble 视觉上雷同(空气泡),需要单独处理。
 */
private fun isParseFailedToolResult(content: String): Boolean {
    val trimmed = content.trim()
    return trimmed.startsWith("[工具执行结果]") && trimmed.contains("状态:解析失败")
}

/**
 * 解析失败时的内联错误行:居左显示一行简短提示,
 * 让用户知道 AI 调用了未识别的工具,而不是看到空气泡。
 */
@Composable
private fun ParseFailedInline(
    content: String,
    colors: IdeColors,
) {
    val toolName = extractField(content.trim(), "类型")?.removePrefix("unknown(")?.removeSuffix(")")
        ?.ifEmpty { null } ?: "未知工具"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "❌",
            style = compactTextStyle(colors.secondaryText),
        )
        Text(
            text = "AI 调用了未注册的工具: $toolName (参数无法解析,已自动忽略)",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            modifier = Modifier.weight(1f),
        )
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
