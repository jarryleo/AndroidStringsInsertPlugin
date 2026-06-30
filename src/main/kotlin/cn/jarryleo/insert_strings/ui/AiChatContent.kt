package cn.jarryleo.insert_strings.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.unit.sp
import cn.jarryleo.insert_strings.ai.ChatMessage
import cn.jarryleo.insert_strings.ai.ToolCall
import cn.jarryleo.insert_strings.phrases.QuickPhrase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

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
    onOptionClick: (Int, Int, String) -> Unit,
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
    /**
     * 入口打开时携带的「引用内容」(例如 AskAi 入口捕获的编辑器选区)。
     * - 非空时在消息列表顶部居中渲染一个独立气泡,默认折叠成 3 行,
     *   点击标题 / 「展开」可显示全文,再次点击折叠。
     * - 该气泡作为 LazyColumn 的第一项渲染,会随着聊天列表一起滚动,
     *   而非固定在消息区顶部 —— 与用户预期的"引用"语义一致。
     * - 通过 [onQuoteDismiss] 可移除(把状态置 null),引用消失且不再随列表滚动出现。
     */
    quoteContent: String? = null,
    /**
     * 引用气泡的关闭回调。引用面板右上的「×」被点击时触发。
     * 不传则不渲染关闭按钮(主面板 / 永久引用场景)。
     */
    onQuoteDismiss: (() -> Unit)? = null,
    /**
     * 引用气泡的「复制」按钮回调:把引用文本写入系统剪贴板 + 弹 toast 反馈。
     * 不传则不渲染复制按钮(无 toast 反馈的场景)。
     * 翻译/解释/总结三个按钮走 [onQuickSend],不再额外加回调。
     */
    onCopyQuote: ((String) -> Unit)? = null,
    /**
     * 「Clear」按钮回调:清除主面板当前选中的 keyEntries 与 rows,
     * 同步清掉聊天顶部「已选择翻译(N)」面板与主面板表格。
     * 仅主面板聊天传这个回调;不传则不渲染按钮(弹框场景不需要)。
     *
     * 第二个参数 [canClear] 表示当前是否有可清除的内容(用于控制按钮的 enabled 状态):
     * - true:有选中 key 或表格有数据,按钮可点;
     * - false:无可清除内容,按钮置灰。
     * 不传则按钮始终可点(由回调内部自行 no-op)。
     */
    onClearSelected: (() -> Unit)? = null,
    canClear: Boolean = true,
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
            quoteContent = quoteContent,
            onQuoteDismiss = onQuoteDismiss,
            onCopyQuote = onCopyQuote,
            onClearSelected = onClearSelected,
            canClear = canClear,
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
    onOptionClick: (Int, Int, String) -> Unit,
    colors: IdeColors,
    showHeader: Boolean = true,
    showQuickPhrases: Boolean = true,
    quickPhrases: List<QuickPhrase> = emptyList(),
    showEnterHint: Boolean = true,
    messageListContentPadding: PaddingValues = PaddingValues(8.dp),
    selectedKeys: List<String> = emptyList(),
    quoteContent: String? = null,
    onQuoteDismiss: (() -> Unit)? = null,
    onCopyQuote: ((String) -> Unit)? = null,
    onClearSelected: (() -> Unit)? = null,
    canClear: Boolean = true,
) {
    val quotedContentState = remember { mutableStateOf(quoteContent) }
    val listState = rememberLazyListState()
    val renderItems = buildChatRenderItems(chatMessages)

    // 自动滚动跟随逻辑:
    //
    // 核心问题:流式输出时内容持续增长,canScrollForward 在内容增长后短暂变 true,
    // 导致基于 canScrollForward 的 isAtBottom 误判为"用户不在底部",自动滚动失效。
    // 同时,animateScrollToItem 的动画被下一个 chunk 取消,滚动永远到不了底,加剧问题。
    //
    // 解决方案:
    // 1. 用 userScrolledUp 替代 isAtBottom:追踪 firstVisibleItemIndex 和 scrollOffset,
    //    只有当这些值实际变化时(用户真的滚了)才标记为上滑。内容增长不会改变这些值,
    //    因此不会误判。
    // 2. 流式滚动用即时 scrollToItemBottomAligned(一帧内完成),避免动画被取消。
    // 3. scrollToItemBottomAligned 优化:已可见的 item 只做微调,不做"跳到顶再调到底"
    //    的两步操作,消除高频 chunk 下的视觉抖动。

    data class ScrollPos(val index: Int, val offset: Int)

    // userScrolledUp 检测:
    // 关键问题:仅靠 firstVisibleItemIndex/Offset 的方向判断无法区分
    // 「用户主动上滑」与「内容增长/插入导致的布局重算」。
    // 快速连续加入多个气泡时,firstVisibleItemScrollOffset 会因布局重算而
    // 波动,被误判为用户上滑,userScrolledUp 误翻 true 导致自动滚动失效。
    //
    // 修复:结合 isScrollInProgress 判断——只有真正在滚动(isScrollInProgress=true)
    // 才进行方向判断;内容增长/插入不触发 isScrollInProgress,不会被误判。
    // 滚动停止后,根据 canScrollForward 决定是否重置状态。
    val userScrolledUp = remember { mutableStateOf(false) }
    var lastScrollPos by remember { mutableStateOf(ScrollPos(0, 0)) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .distinctUntilChanged()
            .collect { (scrolling, index, offset) ->
                val pos = ScrollPos(index, offset)
                val prev = lastScrollPos
                if (scrolling) {
                    // 真正在滚动:用方向判断
                    if (pos.index < prev.index ||
                        (pos.index == prev.index && pos.offset < prev.offset)
                    ) {
                        userScrolledUp.value = true
                    } else if (pos.index >= prev.index && pos.offset >= prev.offset) {
                        if (!listState.canScrollForward) {
                            userScrolledUp.value = false
                        }
                    }
                } else {
                    // 非滚动状态(内容增长/插入):只根据最终位置重置
                    // 如果已在最底部(canScrollForward=false),重置为未上滑
                    if (!listState.canScrollForward) {
                        userScrolledUp.value = false
                    }
                }
                lastScrollPos = pos
            }
    }

    // 引用气泡作为 LazyColumn 的第 0 项,导致所有 renderItems 在 LazyColumn 中的索引
    // 整体偏移 1。scrollToItem 系列函数期望的是 LazyColumn 索引,必须加上这个偏移。
    val quoteOffset = if (quotedContentState.value.isNullOrBlank()) 0 else 1

    // 新消息加入时的滚动策略:
    //  - **用户消息**:强制滚到底(用户发消息了就表示阅读完了,必须把视线拉到最底)
    //    —— 不受 userScrolledUp 影响。用 animate 做平滑过渡。
    //  - **AI 消息**:仅当用户未上滑时才滚到底;用户在阅读历史时不打扰。
    //    用即时滚动,避免动画被紧随的流式 effect 取消。
    LaunchedEffect(chatMessages.size) {
        val newMsg = chatMessages.lastOrNull() ?: return@LaunchedEffect
        if (newMsg.role != "user" && userScrolledUp.value) return@LaunchedEffect
        if (renderItems.isEmpty()) return@LaunchedEffect
        val targetIndex = renderItems.size - 1 + quoteOffset
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it > targetIndex }
            .first()
        if (newMsg.role == "user") {
            userScrolledUp.value = false
            listState.animateScrollToItemBottomAligned(targetIndex)
        } else {
            listState.scrollToItemBottomAligned(targetIndex)
        }
    }
    // 流式生成中:每个 chunk 触发 effect,仅在用户未上滑时跟随。
    // 用户上滑后停止跟随;用户滑回底部时,userScrolledUp 变 false,
    // effect 重新 fire,立即恢复跟随。
    //
    // 使用即时 scrollToItemBottomAligned:一帧内完成,不被后续 chunk 取消。
    // 已可见的 item 只做微调(不做"跳到顶再调到底"),消除抖动。
    val lastStreamingMessage = chatMessages.lastOrNull { it.streaming }
    LaunchedEffect(lastStreamingMessage?.thinking, lastStreamingMessage?.content, userScrolledUp.value) {
        if (lastStreamingMessage == null) return@LaunchedEffect
        if (userScrolledUp.value) return@LaunchedEffect
        if (renderItems.isEmpty()) return@LaunchedEffect
        val targetIndex = renderItems.size - 1 + quoteOffset
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it > targetIndex }
            .first()
        listState.scrollToItemBottomAligned(targetIndex)
    }

    // AI 提问按钮出现时:options 通过 copy() 写入最后一条消息,chatMessages.size 不变,
    // 流式也已停止,前两个 effect 都不会触发。单独监听 options 变化,
    // 等一帧让按钮完成布局后滚到底部,确保用户能看到按钮。
    val lastMsgOptions = chatMessages.lastOrNull()?.options
    val lastToolCallId = chatMessages.lastOrNull()?.toolCallId
    val lastToolCalls = chatMessages.lastOrNull()?.toolCalls
    val lastProtocolSummary = chatMessages.lastOrNull()?.protocolSummary
    val lastProtocolVisible = chatMessages.lastOrNull()?.protocolVisible
    LaunchedEffect(lastMsgOptions, lastToolCallId, lastToolCalls, lastProtocolSummary, lastProtocolVisible) {
        if (lastMsgOptions.isNullOrEmpty()) return@LaunchedEffect
        if (renderItems.isEmpty()) return@LaunchedEffect
        if (userScrolledUp.value) return@LaunchedEffect
        withFrameNanos { /* one frame */ }
        val targetIndex = renderItems.size - 1 + quoteOffset
        listState.scrollToItemBottomAligned(targetIndex)
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
                // 「Clear」按钮:仅主面板聊天传入 [onClearSelected] 时渲染(弹框场景不显示)。
                // 位于 Context 按钮左侧,点击后清除主面板 keyEntries + rows,
                // 同步清掉聊天顶部「已选择翻译(N)」面板与主面板表格。
                // 没有可清除内容时(canClear = false)置灰,避免误点。
                if (onClearSelected != null) {
                    CompactButton(
                        text = "Clear",
                        onClick = onClearSelected,
                        modifier = Modifier.width(60.dp),
                        colors = colors,
                        enabled = canClear,
                    )
                }
                CompactButton(
                    text = "Context",
                    onClick = onOpenContext,
                    modifier = Modifier.width(68.dp),
                    colors = colors,
                )
                /*CompactButton(
                    text = "Back",
                    onClick = onClose,
                    modifier = Modifier.width(56.dp),
                    colors = colors,
                )*/
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
            // 注意:即便 chatMessages 为空,只要 quoteContent 非空,也要把引用气泡渲染出来,
            // 这样 AskAi 弹框打开后用户能立刻看到「我选中的内容」作为视觉锚。
            // 引用作为 LazyColumn 的第一项,可以随着后续消息插入一起被滚走,
            // 也符合"气泡"的语义(不应该固定在顶部像工具栏一样)。
            if (chatMessages.isEmpty() && quotedContentState.value.isNullOrBlank()) {
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
                    // 引用气泡:作为列表的第一项,默认折叠成 3 行,可展开/收起,
                    // 并随消息列表一起滚动。外观类似 user 消息气泡但居中、且字号略小,
                    // 避免和真正的 user 消息混淆。
                    if (!quotedContentState.value.isNullOrBlank()) {
                        item(key = "quote") {
                            QuoteBubble(
                                text = quotedContentState.value ?: "",
                                onDismiss = {
                                    quotedContentState.value = null
                                    onQuoteDismiss?.invoke()
                                },
                                onCopy = onCopyQuote,
                                onQuickSend = onQuickSend,
                                chatSending = chatSending,
                                colors = colors,
                            )
                        }
                    }
                    itemsIndexed(renderItems) { _, item ->
                        when (item) {
                            is ChatRenderItem.Message -> {
                                ChatBubble(
                                    message = item.message,
                                    messageIndex = item.sourceIndex,
                                    onOptionClick = onOptionClick,
                                    chatSending = chatSending,
                                    colors = colors,
                                )
                            }

                            is ChatRenderItem.ToolGroup -> {
                                ToolGroupBubble(
                                    messages = item.messages,
                                    toolCallsById = item.toolCallsById,
                                    taskSummary = item.taskSummary,
                                    colors = colors,
                                )
                            }
                        }
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

private sealed class ChatRenderItem {
    data class Message(
        val sourceIndex: Int,
        val message: ChatMessage
    ) : ChatRenderItem()

    data class ToolGroup(
        val sourceIndex: Int,
        val messages: List<ChatMessage>,
        val toolCallsById: Map<String, ToolCall>,
        val taskSummary: String?
    ) : ChatRenderItem()
}

/**
 * UI-only grouping: adjacent tool_result messages are stacked into one visual bubble.
 * The underlying [chatMessages] list stays unchanged so function-calling protocol
 * pairing remains intact.
 *
 * **隐藏过滤**(2026.x 新增):[ChatMessage.hidden] = true 的消息直接从渲染列表中剔除。
 * 典型场景是「自动触发 - 代办提醒」流程——scheduler 发起的 system message + AI 的回复
 * + 中间 tool 消息都标 hidden,UI 完全不显示,但 AI 协议历史里仍然存在(供后续对话参考)。
 */
private fun buildChatRenderItems(chatMessages: List<ChatMessage>): List<ChatRenderItem> {
    val items = mutableListOf<ChatRenderItem>()
    // 原始下标 → 渲染下标的映射,用于 ChatRenderItem 内部引用源消息数组的位置
    var i = 0
    while (i < chatMessages.size) {
        val msg = chatMessages[i]
        // 隐藏消息整体跳过(包括后续紧跟的 tool 消息,直到下一个非隐藏的 assistant)
        if (msg.hidden) {
            i++
            continue
        }
        if (msg.role != "tool") {
            items += ChatRenderItem.Message(i, msg)
            i++
            continue
        }

        val start = i
        val group = mutableListOf<ChatMessage>()
        while (i < chatMessages.size && chatMessages[i].role == "tool" && !chatMessages[i].hidden) {
            group += chatMessages[i]
            i++
        }
        // 如果这个 tool 组前面是一个 hidden 消息,整个 tool 组都不该展示(它属于 hidden 上下文)
        if (group.isEmpty()) {
            i++
            continue
        }
        val prev = chatMessages.getOrNull(start - 1)
        val toolCallsById = prev
            ?.takeIf { it.role == "assistant" && !it.hidden }
            ?.toolCalls
            ?.associateBy { it.id }
            .orEmpty()
        val taskSummary = prev
            ?.takeIf { it.role == "assistant" && it.content.isNotBlank() }
            ?.content
        items += ChatRenderItem.ToolGroup(start, group, toolCallsById, taskSummary)
    }
    return items
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

/**
 * 引用气泡:AskAi / ExtractStrings 入口打开 chat 时携带的"上下文内容"
 * (例如编辑器选中的文本)以一个独立气泡形式展示在消息列表顶部。
 *
 * 视觉与交互:
 *  - 居中气泡(不像 user 消息靠右,也不像 assistant 靠左),宽度约 70% 列表宽,
 *    最大不超过 480dp,呼应"引用"的视觉语义。
 *  - 颜色用 `colors.fieldBackground` + `colors.border` 描边 —— 与助手气泡底色一致,
 *    但顶部增加一个引用图标 + 「引用内容」小标签,告诉用户这是入口带进来的,
 *    不是来自 AI 也不是用户输入的对话。
 *  - 折叠态:固定 3 行(可纵向滚动查看更多),右侧有「展开 ▾」按钮;
 *    展开态:无高度限制,内容按 markdown 渲染;再次点击「折叠 ▴」收回到 3 行。
 *  - 列表右侧的「×」是可选的关闭按钮(由 [onQuoteDismiss] 启用),不传则不渲染。
 *  - 内容走 [MarkdownContent] 渲染,意味着选中代码块、行内 code 等都能正确着色,
 *    并且用 `bubbleColor` 让 inline code / fenced code 与气泡底色协调(不再白底白字)。
 *  - 引用面板底部 4 个预置按钮(翻译/解释/总结/复制):
 *      * 翻译 / 解释 / 总结 走 [onQuickSend],由 [QuoteActions.buildPrompt] 把选区
 *        包成 user 消息发给 AI(显式禁止 AI 调任何工具,避免误触发 insert_strings);
 *      * 复制 走 [onCopy],由调用方处理剪贴板 + toast 反馈;
 *      * AI 正在生成(chatSending=true)时所有按钮禁用,避免触发竞态。
 *      * 复制按钮不传 [onCopy] 时不渲染(例如主面板没接剪贴板回调的场景)。
 */
@Composable
private fun QuoteBubble(
    text: String,
    onDismiss: (() -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onQuickSend: (String) -> Unit,
    chatSending: Boolean,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    val bubbleColor = colors.fieldBackground
    val bubbleTextColor = colors.text
    val bubbleColors = colors.copy(text = bubbleTextColor, secondaryText = colors.secondaryText)
    // 折叠 3 行时,单行高度 ~ 16sp(lineHeight) = 16dp,加 padding 12dp ≈ 60dp;
    // 留点 padding 余量取 64dp。再长则内部 LazyColumn 滚动。
    val collapsedHeight = 64.dp
    // 不再 wrap 在 CenterHorizontally 里 —— 让气泡左对齐 (Alignment.Start) 贴向消息列表
    // 的左侧,与助手气泡 (ChatBubble) 的贴边位置一致;消息列表自身的 contentPadding
    // (8.dp) 即为「贴边间距」。这样引用与左右两侧的真气泡视觉一致,不再悬空居中。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 头部:左侧 ❝ + 引用内容 标题 + ▾ 折叠指示(整段点击切换展开) + 右侧 × 关闭按钮。
                //
                // 关键 —— 改用 Box 叠加而不是 Row 拼接:
                // 旧版用 Row(weight 标题 + Box ×)实现"标题占满、× 贴右",但 Compose Desktop
                // 实际表现是标题的 clickable 会**吞掉** × 区域的点击事件,导致关闭按钮无效。
                // 这里改用 Box + Alignment 叠加:
                //  - 标题段(❝ + 引用内容 + ▾)以 CenterStart 摆左,自带 clickable 切换展开;
                //  - × 按钮以 CenterEnd 摆右,独立的 clickable 调用 onDismiss();
                //  - 两个 clickable 分别绑在 Box 的两个子节点上,父级 Box 不参与命中,
                //    从根本上消除"父级 clickable 拦截子级"的隐患。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp),
                ) {
                    // 标题段:左对齐 + 自带 padding 让命中区域略大于文字,手感更稳。
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = !expanded }
                            .padding(vertical = 2.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // 引用图标
                            text = "❝",
                            color = colors.accent,
                            style = compactTextStyle(colors.accent).copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "引用内容",
                            color = colors.secondaryText,
                            style = compactTextStyle(colors.secondaryText),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (expanded) "▴" else "▾",
                            color = colors.secondaryText,
                            style = compactTextStyle(colors.secondaryText),
                        )
                    }
                    if (onDismiss != null) {
                        // × 按钮:右对齐,独立 Box,独立 clickable。
                        // 关键:这里不能再嵌套到任何带 clickable 的父节点里 —— Box 的
                        // clickable 是命中这个 20dp 方块的唯一入口,父级 Box 不参与
                        // 命中,自然不会被标题的 clickable 拦截。
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "×",
                                color = colors.secondaryText,
                                style = compactTextStyle(colors.secondaryText).copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
                // 内容:折叠时套一个高度受限的 verticalScroll,展开时直接渲染。
                // 折叠态使用 scrollable Column 而不是 LazyColumn 是为了避免和父级 LazyColumn
                // 嵌套(虽然 Compose 支持,但 LazyColumn 嵌套会强制父级 "fill viewport" 行为,
                // 引发整列被滚动锁定的体验问题)。
                if (expanded) {
                    SelectionContainer {
                        MarkdownContent(
                            markdown = text,
                            colors = bubbleColors,
                            bubbleColor = bubbleColor,
                        )
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = collapsedHeight)
                            .verticalScroll(scrollState),
                    ) {
                        SelectionContainer {
                            MarkdownContent(
                                markdown = text,
                                colors = bubbleColors,
                                bubbleColor = bubbleColor,
                            )
                        }
                    }
                }
                // 底部:4 个预置功能按钮(翻译/解释/总结/复制)。
                // - 用 FlowRow 是为了在窄宽度场景下按钮换行,而不是被裁切。
                // - 翻译/解释/总结 走 onQuickSend -> sendChatMessage,
                //   会以"user"角色把 QuoteActions.buildPrompt 包装后的内容塞进对话。
                // - 复制 走 onCopy,由调用方决定写剪贴板 + toast 的具体实现。
                QuoteActionBar(
                    quotedText = text,
                    onQuickSend = onQuickSend,
                    onCopy = onCopy,
                    enabled = !chatSending,
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 引用面板底部的预置功能按钮行(翻译 / 解释 / 总结 / 复制)。
 *
 *  - 4 个按钮共用 [CompactButton] 风格,**使用 NEUTRAL tone**(沿用 IDE 主题的常规按钮
 *    配色),不染色 —— 保持引用面板视觉简洁,与气泡内的"操作型"按钮风格统一。
 *  - 按钮宽度按 label 自适应(`wrapContentWidth`),4 个按钮总宽约 200~240dp,
 *    在贴边布局的引用气泡中一行排得下,窄屏(弹框)FlowRow 自动换行,不影响。
 *  - [enabled]=false 时由 CompactButton 自动用次要色渲染,给出"不可点"的视觉反馈。
 *  - 翻译/解释/总结按钮的 onClick 会用 [QuoteActions.buildPrompt] 把 [quotedText]
 *    包成 user 消息,经 [onQuickSend] 走 sendChatMessage 把消息塞进 chatMessages
 *    并触发一轮 AI 调用。
 *  - 复制按钮的 onClick 直接把 [quotedText] 透传给 [onCopy],由调用方实现
 *    写剪贴板 + toast 反馈等具体逻辑。
 */
@Composable
private fun QuoteActionBar(
    quotedText: String,
    onQuickSend: (String) -> Unit,
    onCopy: ((String) -> Unit)?,
    enabled: Boolean,
    colors: IdeColors,
) {
    val buttons = remember(onCopy) {
        // 复制按钮只在提供了 onCopy 回调时才显示,避免出现"点了没反应"的空气泡。
        if (onCopy == null) {
            QuoteActions.all.filter { it != QuoteActions.Kind.COPY }
        } else {
            QuoteActions.all
        }
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        buttons.forEach { kind ->
            CompactButton(
                text = kind.label,
                onClick = {
                    when (kind) {
                        QuoteActions.Kind.COPY -> onCopy?.invoke(quotedText)
                        else -> onQuickSend(QuoteActions.buildPrompt(kind, quotedText))
                    }
                },
                modifier = Modifier.wrapContentWidth().padding(horizontal = 5.dp),
                colors = colors,
                enabled = enabled,
                tone = ButtonTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    messageIndex: Int,
    onOptionClick: (Int, Int, String) -> Unit,
    chatSending: Boolean,
    colors: IdeColors,
    taskSummary: String? = null,
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val bubbleColor = if (isUser) colors.accent else colors.fieldBackground
    val border = if (isUser) {
        Modifier
    } else {
        Modifier.border(width = 1.dp, color = colors.border, RoundedCornerShape(12.dp))
    }
    // 用户气泡底色是品牌色(蓝),默认 `colors.text`(深灰)在上面读不清;
    // 助手气泡底色是 fieldBackground(白/近白),保持深色文字。
    val bubbleTextColor = if (isUser) colors.accentText else colors.text
    val bubbleColors = colors.copy(text = bubbleTextColor, secondaryText = bubbleTextColor)
    // 预计算这些布尔,既用于控制气泡是否绘制,也用于控制时间戳是否绘制
    val showThinkingHeader = !isUser && (message.streaming || message.thinking.isNotBlank())
    val showThinkingBox = !isUser && message.thinking.isNotBlank()
    val showReply = !isUser && message.content.isNotBlank()
    val showAskQuestion = !isUser && !message.askQuestion.isNullOrBlank()
    val showUserContent = isUser && message.content.isNotBlank()
    val hasAnyContent = showUserContent || showThinkingHeader || showReply || showAskQuestion
    val showTimestamp = isTool || hasAnyContent
    val timestampText = formatMessageTimestamp(message.timestamp)
    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isTool) {
            // 「解析失败」的工具结果(参数无法解析 / 工具名未注册)对用户没有可操作意义,
            // 但又必须以 tool_result 形式返回给 AI 以满足 Anthropic 协议。
            // 这里把它折叠成助手一侧的简短错误行,不再用 ToolBubble 渲染,
            // 既避免空气泡,又让用户能看到具体是哪个工具调用出了问题。
            // 工具消息实际上是被 ToolGroupBubble 渲染的(见 buildChatRenderItems),
            // 这里的 isTool 分支是死代码,保留只为防御性兜底。
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
        } else if (hasAnyContent) {
            // === 助手气泡(assistant / user) ===
            // 助手侧四段式结构:Thinking 区(可选) + content 正文区(可选) +
            //                  askQuestion 询问文字区(可选,仅 ask_user) + options 区(下方)。
            // 关键拆分 — 「思考 / 正文 / 询问文字」三段独立:
            //  - Thinking 区:模型在调用工具/给出最终答案之前的中间发言,
            //    UI 上折叠成可点击展开的「Thought · N 行」区;非推理模型或无文本时整个区消失。
            //  - content 正文区:AI 同时返回的「前言/解释」等正文文本(finishWithReply 阶段
            //    从 reply.reply 保留,ask_user 场景下不会被「执行操作: ask_user」占位覆盖)。
            //  - askQuestion 询问文字区:仅 ask_user 工具调用携带的 question,由
            //    [processAiReply] 的 AskUser 分支写入 ChatMessage.askQuestion 字段;
            //    UI 上以「❓ Question」标签 + 问题正文渲染,与 content 正文视觉上完全分离,
            //    避免旧实现把 question 覆盖进 content 时造成的"思考与正文被折叠在 Thought"误解。
            //  - options 区:在气泡外部的 FlowRow 里渲染按钮,与 askQuestion 配套。
            //
            // **时间戳布局(2026.x 修复)**:用 **Row** 把时间戳和气泡**并排**放,
            // `verticalAlignment = Alignment.Bottom` 让两者**底边对齐**;
            // `Arrangement.End/Start` 让 Row 把内容推到正确的一侧(用户右 / AI 左);
            // 32dp 边距从气泡移到 Row 上,这样时间戳能**紧贴气泡**(4dp 间距),
            // 不会像之前那样被 32dp 边距隔开。
            // 用户消息 → [timestamp][bubble];AI → [bubble][timestamp]。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (isUser && showTimestamp) {
                    // 用户消息:时间戳在气泡**左边**,贴气泡底边
                    Text(
                        text = timestampText,
                        modifier = Modifier.padding(end = 4.dp),
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText).copy(fontSize = 10.sp),
                    )
                }
                // 气泡本身(不再有 32dp 边距,边距在 Row 上)
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
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
                        // 思考区与正文/询问区之间的细分隔线:只要思考区存在 + 下方任一内容存在都画
                        if (showThinkingHeader && (showReply || showAskQuestion)) {
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
                                    colors = bubbleColors,
                                    bubbleColor = bubbleColor,
                                )
                            }
                        } else if (showUserContent) {
                            SelectionContainer {
                                MarkdownContent(
                                    markdown = message.content,
                                    colors = bubbleColors,
                                    bubbleColor = bubbleColor,
                                )
                            }
                        }
                        // 询问文字区:仅 ask_user 消息渲染,顶部带「❓ Question」标签
                        // 与下方 content 正文清晰分开,避免被吞进 Thought 折叠区。
                        // 与正文区之间再加一条分隔线,保持三段式视觉一致。
                        if (showAskQuestion) {
                            if (showReply) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.grid)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "❓",
                                        color = colors.accent,
                                        style = compactTextStyle(colors.accent),
                                    )
                                    Text(
                                        text = "Question",
                                        color = colors.accent,
                                        style = compactTextStyle(colors.accent)
                                            .copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                                SelectionContainer {
                                    MarkdownContent(
                                        markdown = message.askQuestion ?: "",
                                        colors = bubbleColors,
                                        bubbleColor = bubbleColor,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!isUser && showTimestamp) {
                    // AI / 工具消息:时间戳在气泡**右边**,贴气泡底边
                    Text(
                        text = timestampText,
                        modifier = Modifier.padding(start = 4.dp),
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText).copy(fontSize = 10.sp),
                    )
                }
            }
        }
        if (!isUser && !isTool && message.options.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 4.dp, end = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                message.options.forEachIndexed { optionIndex, option ->
                    CompactButton(
                        text = option,
                        onClick = { onOptionClick(messageIndex, optionIndex, option) },
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
private fun ToolGroupBubble(
    messages: List<ChatMessage>,
    toolCallsById: Map<String, ToolCall>,
    colors: IdeColors,
    taskSummary: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val summaries = messages.map { msg ->
        buildToolDisplaySummary(
            content = msg.content,
            toolCall = msg.toolCallId?.let { toolCallsById[it] },
        )
    }
    val failedCount = summaries.count { it.success == false }
    val skippedCount = summaries.count { it.success == null }
    // run_shell 在进程运行期间会用 streaming=true 的 tool 消息持续追加输出,
    // 此时在卡片头部加一个呼吸圆点 + "输出中" 文案,告诉用户工具还没跑完。
    // 视觉与 assistant 消息的 ThinkingPulseDot 一致(单色 8dp 圆点 + 1.2s 呼吸 alpha)。
    val anyStreaming = messages.any { it.streaming }
    val headerStatus = when {
        anyStreaming -> "输出中…"
        failedCount > 0 -> "$failedCount 失败"
        skippedCount > 0 -> "$skippedCount 跳过"
        else -> "完成"
    }
    val displayTaskSummary = taskSummary?.takeIf { it.isNotBlank() }
    // 时间戳(2026.x 新增):取组里最后一条 tool 消息的时间(代表整组工具调用完成的时间);
    // 与用户/AI 气泡一致:用 Row 把它和工具气泡并排,底边对齐,4dp 间距。
    val timestamp = messages.lastOrNull()?.timestamp ?: 0L
    val timestampText = if (timestamp > 0) formatMessageTimestamp(timestamp) else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .padding(end = 32.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
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
                    text = "工具调用 · ${messages.size} 条",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (anyStreaming) {
                    ThinkingPulseDot(colors)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = headerStatus,
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }

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

            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                summaries.forEachIndexed { idx, summary ->
                    ToolSummaryRow(
                        index = idx + 1,
                        summary = summary,
                        colors = colors,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    messages.forEachIndexed { idx, msg ->
                        val toolCall = msg.toolCallId?.let { toolCallsById[it] }
                        val summary = summaries.getOrNull(idx)
                        ToolDetailBlock(
                            index = idx + 1,
                            summary = summary,
                            content = msg.content,
                            toolCall = toolCall,
                            colors = colors,
                        )
                    }
                }
            }
        }
        if (timestampText.isNotEmpty()) {
            Text(
                text = timestampText,
                modifier = Modifier.padding(start = 4.dp),
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText).copy(fontSize = 10.sp),
            )
        }
    }
}

@Composable
private fun ToolSummaryRow(
    index: Int,
    summary: ToolDisplaySummary,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(5.dp))
            .border(BorderStroke(1.dp, colors.grid), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "$index.",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )
        Text(
            text = summary.statusLabel,
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )
        Text(
            text = summary.name,
            color = colors.text,
            style = compactTextStyle(colors.text),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 72.dp, max = 132.dp),
        )
        Text(
            text = summary.target,
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = summary.result,
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun ToolDetailBlock(
    index: Int,
    summary: ToolDisplaySummary?,
    content: String,
    toolCall: ToolCall?,
    colors: IdeColors,
) {
    val detailScroll = rememberScrollState()
    val args = toolCall?.arguments?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, colors.grid), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "$index. ${summary?.name ?: "tool"} · ${summary?.target ?: "对象:-"} · ${summary?.result ?: "结果:-"}",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            fontWeight = FontWeight.SemiBold,
        )
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(detailScroll),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (args != null) {
                    Text(
                        text = "参数:\n${formatJsonForDisplay(args)}",
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText).copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Text(
                    text = "结果:\n$content",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText).copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

private data class ToolDisplaySummary(
    val name: String,
    val target: String,
    val result: String,
    val statusLabel: String,
    val success: Boolean?,
)

private fun buildToolDisplaySummary(
    content: String,
    toolCall: ToolCall?,
): ToolDisplaySummary {
    val rawName = toolCall?.name
        ?: extractField(content, "类型")
        ?: inferToolNameFromContent(content)
        ?: "tool"
    val args = toolCall?.arguments?.parseJsonObjectOrNull()
    val operation = args?.getString("operation")
        ?: extractField(content, "操作")
    val name = compactToolName(rawName, operation)
    val target = targetFromArgs(rawName, args)
        ?: targetFromContent(content)
        ?: "对象:-"
    val success = when {
        content.contains("状态:成功") -> true
        content.contains("状态:失败") ||
                content.contains("状态:解析失败") ||
                content.contains("[工具执行异常]") ||
                content.contains("[工具文档加载失败]") -> false

        content.contains("状态:已跳过") ||
                content.contains("[用户取消]") ||
                content.contains("[已取消]") -> null

        else -> null
    }
    val statusLabel = when (success) {
        true -> "OK"
        false -> "ERR"
        null -> "SKIP"
    }
    val result = buildToolResultText(content, success)
    return ToolDisplaySummary(name, target, result, statusLabel, success)
}

private fun compactToolName(name: String, operation: String?): String {
    val base = when (name.removePrefix("unknown(").removeSuffix(")")) {
        "insert_strings" -> "strings.insert"
        "update_string" -> "strings.update"
        "delete_string" -> "strings.delete"
        "query_keys" -> "strings.query"
        "read_string" -> "strings.read"
        "find_keys_by_text" -> "strings.find"
        "sheets_operation" -> "sheets"
        "find_rows_by_text" -> "sheets.find"
        "get_editor_file" -> "editor.file"
        "read_file" -> "file.read"
        "edit_file" -> "file.edit"
        "create_file" -> "file.create"
        "search_in_files" -> "file.search"
        "find_references" -> "file.refs"
        "list_files" -> "file.list"
        "load_tool_doc" -> "tool.doc"
        "ask_user" -> "ask_user"
        "task_complete" -> "done"
        else -> name
    }
    return if (base == "sheets" && !operation.isNullOrBlank()) {
        "sheets.${operation.lowercase()}"
    } else {
        base
    }
}

private fun targetFromArgs(toolName: String, args: JsonObject?): String? {
    if (args == null) return null
    fun moduleSuffix(): String = args.getString("module")?.let { " @$it" }.orEmpty()
    return when (toolName) {
        "insert_strings", "update_string", "delete_string", "read_string" ->
            args.getString("name")?.let { "key:$it${moduleSuffix()}" }

        "query_keys" ->
            args.getString("pattern")?.let { "pattern:$it${moduleSuffix()}" }
                ?: args.getString("module")?.let { "module:$it" }

        "find_keys_by_text" ->
            args.getString("text")?.let { "text:${truncateText(it, 36)}" }

        "sheets_operation" -> {
            val sheet = args.getString("sheetName")?.let { "sheet:$it" }
            val range = args.getString("range")?.let { "range:$it" }
            val key = args.getString("key")?.let { "key:$it" }
            val row = args.getString("rowNumber")?.let { "row:$it" }
            val col = args.getString("columnIndex")?.let { "col:$it" }
            listOfNotNull(sheet, range, key, row, col).joinToString(" ").takeIf { it.isNotBlank() }
        }

        "find_rows_by_text" ->
            args.getString("text")?.let { "text:${truncateText(it, 36)}" }

        "read_file", "edit_file", "create_file" ->
            args.getString("path")?.let { "path:${truncateText(it, 48)}" }

        "search_in_files" ->
            args.getString("pattern")?.let { "pattern:${truncateText(it, 36)}" }

        "find_references" ->
            args.getString("symbol")?.let { "symbol:$it" }

        "list_files" ->
            args.getString("relativeDir")?.let { "dir:$it" }

        "load_tool_doc" ->
            args.getString("tool")?.let { "doc:$it" }

        "ask_user" ->
            args.getString("question")?.let { "question:${truncateText(it, 36)}" }

        "run_shell" -> {
            val cmd = args.getString("command") ?: "?"
            val firstArg = args.getAsJsonArray("args")?.takeIf { it.size() > 0 }
                ?.get(0)?.takeIf { !it.isJsonNull }
                ?.let { runCatching { it.asString }.getOrNull() }
            val cwd = args.getString("cwd")?.let { " @ $it" }.orEmpty()
            "shell: $cmd $firstArg$cwd".trim()
        }

        "read_diagnostics" -> {
            val sev = args.getString("minSeverity")
            if (sev.isNullOrBlank()) "LSP: all severities" else "LSP: $sev+"
        }

        "fetch_url" -> {
            val url = args.getString("url") ?: "?"
            // 显示 fetch: <scheme>://<host>[:<port>](不含 path)— 路径太长容易把卡片撑爆,
            // 用户/AI 点开 ToolGroupBubble 详情后能看到完整 URL。
            // java.net.URI 没有 getDefaultPort() 方法(它在 java.net.URL 才有),所以这里
            // 简化:port > 0 就显式展示。80/443 等常见端口会被显示出来 — 可读性 > 简洁性。
            val parsed = runCatching { java.net.URI(url) }.getOrNull()
            if (parsed == null) {
                "fetch: $url"
            } else {
                val host = parsed.host
                val port = parsed.port
                val scheme = parsed.scheme?.lowercase()
                when {
                    host.isNullOrBlank() -> "fetch: $url"
                    port > 0 && scheme != null -> "fetch: $scheme://$host:$port"
                    scheme != null -> "fetch: $scheme://$host"
                    else -> "fetch: $url"
                }
            }
        }

        "web_search" -> {
            // 显示 search: <query 截短> — 长 query 容易把卡片撑爆,截前 36 字符 + …
            // 用户/AI 点开 ToolGroupBubble 详情后能看到完整 query 和结果列表。
            val q = args.getString("query") ?: "?"
            "search: ${truncateText(q, 36)}"
        }

        else -> null
    }
}


private fun targetFromContent(content: String): String? {
    val module = extractField(content, "模块") ?: extractField(content, "module")
    val key = extractField(content, "key") ?: extractField(content, "name")
    val path = extractField(content, "path")
    val range = extractField(content, "range")
    val sheet = extractField(content, "工作表") ?: extractField(content, "sheetName")
    return when {
        key != null -> "key:$key${module?.let { " @$it" }.orEmpty()}"
        path != null -> "path:${truncateText(path, 48)}"
        range != null -> "range:$range"
        sheet != null -> "sheet:$sheet"
        module != null -> "module:$module"
        else -> null
    }
}

private fun buildToolResultText(content: String, success: Boolean?): String {
    val info = extractTextAfter(content, "信息:")
        ?: extractTextAfter(content, "失败:")
        ?: content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val prefix = when (success) {
        true -> "成功"
        false -> "失败"
        null -> if (content.contains("已跳过")) "跳过" else "结果"
    }
    return "$prefix: ${truncateSummary(info, 58)}"
}

private fun inferToolNameFromContent(content: String): String? {
    val trimmed = content.trim()
    if (trimmed.startsWith("[工具执行结果]")) {
        val rest = trimmed.removePrefix("[工具执行结果]").trim()
        return rest.substringBefore(' ').takeIf {
            it.isNotBlank() && !it.contains(':') && !it.contains('=')
        }
    }
    if (trimmed.startsWith("[用户取消]")) {
        return trimmed.removePrefix("[用户取消]").trim().substringBefore(' ').takeIf { it.isNotBlank() }
    }
    if (trimmed.startsWith("[工具执行异常]")) {
        return trimmed.removePrefix("[工具执行异常]").trim().substringBefore(' ').takeIf { it.isNotBlank() }
    }
    if (trimmed.contains("工具文档")) return "load_tool_doc"
    return null
}

private fun String.parseJsonObjectOrNull(): JsonObject? =
    runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()

private fun JsonObject.getString(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.let { element ->
        runCatching {
            if (element.isJsonPrimitive) element.asString else element.toString()
        }.getOrNull()
    }?.trim()?.takeIf { it.isNotEmpty() }

private fun formatJsonForDisplay(text: String): String {
    return runCatching {
        val element = JsonParser.parseString(text)
        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(element)
    }.getOrElse { text }
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
    val equalsMarker = "$key="
    val equalsStart = text.indexOf(equalsMarker)
    if (start < 0 && equalsStart < 0) return null
    val actualStart: Int
    val markerLength: Int
    if (start >= 0 && (equalsStart < 0 || start < equalsStart)) {
        actualStart = start
        markerLength = marker.length
    } else {
        actualStart = equalsStart
        markerLength = equalsMarker.length
    }
    val valueStart = actualStart + markerLength
    // 取到下一个空格分隔字段之前
    val rest = text.substring(valueStart)
    val endIdx = rest.indexOf(' ').let { if (it < 0) rest.length else it }
    val raw = rest.substring(0, endIdx).trim().trim('\'', '"', ',', ';')
    return raw.takeIf { it.isNotEmpty() }
}

private fun extractTextAfter(text: String, marker: String): String? {
    val start = text.indexOf(marker)
    if (start < 0) return null
    val rest = text.substring(start + marker.length).trim()
    return rest.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
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

/**
 * 把消息时间戳格式化为"人类可读"短字符串(2026.x 新增)。
 *
 * 规则:
 *  - 当天: `HH:mm`(例如 "14:30"),紧凑,适合气泡角落;
 *  - 跨天(昨天 / 更早): `MM-dd HH:mm`(例如 "06-27 14:30"),让用户能区分
 *    "今天的对话"和"昨天的对话",避免视觉混淆。
 *
 * 不显示年份(2026.x 时几乎不会有跨年场景);若以后需要可加 `sameYear` 判断。
 */
private fun formatMessageTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

/**
 * 滚动到指定 index 并让 item **底部**对齐 viewport **底部**。
 *
 * 优化:若 item 已可见,直接计算底部偏差做单次 scrollBy,避免"先跳到顶再调到底"的
 * 两步操作。流式输出时 item 始终可见,每个 chunk 只做一次微调,消除视觉抖动。
 *
 * 若 item 不可见(首次出现或跳转),回退到 scrollToItem(顶对齐) + 等一帧 + 底部对齐。
 */
private suspend fun LazyListState.scrollToItemBottomAligned(index: Int) {
    val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleItem != null) {
        val overflow = (visibleItem.offset + visibleItem.size) - layoutInfo.viewportEndOffset
        if (overflow > 0) scrollBy(overflow.toFloat())
        return
    }
    scrollToItem(index, 0)
    withFrameNanos { /* one frame */ }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val overflow = (item.offset + item.size) - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

/**
 * 同 [scrollToItemBottomAligned],但 scroll 阶段用动画而非即时落位。
 * 用于用户消息等非高频场景,提供平滑的视觉过渡。
 * 已可见的 item 同样做单次 animateScrollBy 优化。
 */
private suspend fun LazyListState.animateScrollToItemBottomAligned(index: Int) {
    val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleItem != null) {
        val overflow = (visibleItem.offset + visibleItem.size) - layoutInfo.viewportEndOffset
        if (overflow > 0) animateScrollBy(overflow.toFloat())
        return
    }
    animateScrollToItem(index, 0)
    withFrameNanos { /* one frame */ }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val overflow = (item.offset + item.size) - layoutInfo.viewportEndOffset
    if (overflow > 0) animateScrollBy(overflow.toFloat())
}
