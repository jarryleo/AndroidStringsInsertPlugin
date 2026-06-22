package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.phrases.QuickPhrase
import kotlin.math.roundToInt

/**
 * 快捷短语管理面板。
 *
 * 两种模式:
 * - **列表模式**(editing == null):每行展示一条短语(标题着色 + 文本预览),
 *   右侧 Edit / Delete;顶部「+ Add」按钮进入编辑模式新建;右上「Reset」重置出厂默认。
 * - **行内编辑模式**(editing != null):目标行就地展开为编辑器,
 *   含 Title / Text / Color 三个字段 + Save / Cancel 按钮。
 *   Save 时校验通过才写库;Cancel 直接丢弃草稿。
 *
 * **拖拽排序**(仅列表模式生效):
 * - 在非按钮区域(色块 / 标题 / 文本预览列)**长按**某一行,进入拖拽态;
 * - 拖拽时,被拖行变为半透明"ghost"并跟随手指上下移动;
 * - 释放时按当前指针 Y 落在的 row index 调用 [onMove] 完成排序;
 * - 正在编辑的行不可拖(避免与文本框输入冲突);新短语(editing.id 不在列表中)也不可拖。
 *
 * 配色(Color):支持 `#RRGGBB` / `#RGB` 形式 hex,也支持 8 个常用命名色(red/green/blue/orange/yellow/purple/pink/gray)。
 * 留空表示无染色,沿用 IDE 主题色。
 */
@Composable
fun QuickPhrasesContent(
    phrases: List<QuickPhrase>,
    editing: QuickPhrase?,
    onAdd: () -> Unit,
    onEdit: (QuickPhrase) -> Unit,
    onDelete: (QuickPhrase) -> Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onSaveEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelEdit: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Quick Phrases",
                modifier = Modifier.weight(1f),
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "共 ${phrases.size} 条",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
            CompactButton(
                text = "Reset",
                onClick = onResetDefaults,
                colors = colors,
            )
            CompactButton(
                text = "+ Add",
                onClick = onAdd,
                colors = colors,
                primary = true,
            )
        }

        // 说明
        Text(
            text = "在 AI 聊天面板以按钮形式显示,点击即把文本作为用户消息发送。" +
                "可给文字指定颜色便于快速分辨。长按非按钮区域可拖拽排序。",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        if (phrases.isEmpty() && editing == null) {
            // 空态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.tableBackground, RoundedCornerShape(3.dp))
                    .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有快捷短语,点上方「+ Add」新建一条。",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        } else {
            PhraseReorderableList(
                phrases = phrases,
                editing = editing,
                onEdit = onEdit,
                onDelete = onDelete,
                onMove = onMove,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        }
    }
}

/**
 * 列表 + 行内编辑的可重排容器。
 *
 * 拖拽实现:每行通过 [onGloballyPositioned] 记录自身 y 坐标;在 [Modifier.pointerInput]
 * 中用 [detectDragGesturesAfterLongPress] 启动拖拽;被拖行 ghost 用
 * [Modifier.offset] 跟随手指 Y 偏移;放下时根据指针 Y 落在哪一行调用 [onMove]。
 *
 * 行高统一按 [ROW_HEIGHT_DP] 估算(实测每行 ~38dp,加间距 6dp);
 * 但为了"内容变长 / 编辑态撑高"也能正确命中,使用每行实际记录的高度数组。
 */
@Composable
private fun PhraseReorderableList(
    phrases: List<QuickPhrase>,
    editing: QuickPhrase?,
    onEdit: (QuickPhrase) -> Unit,
    onDelete: (QuickPhrase) -> Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onSaveEdit: (title: String, text: String, color: String?) -> Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val scrollState = rememberScrollState()
    // 拖拽状态:哪个 index 正在被拖 + 自按下以来的累计 Y 偏移
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 拖拽起始时,被拖行在 window 坐标系中的 Y 坐标(用于渲染 ghost)
    var dragStartRowY by remember { mutableStateOf(0f) }
    // 每行在 window 坐标系中的 y 坐标 + 高度(用于把指针 Y 映射回 row index)
    val rowTops = remember { mutableStateMapOf<Int, Float>() }
    val rowHeights = remember { mutableStateMapOf<Int, Float>() }
    // 外层 Box(列表容器)在 window 坐标系中的 Y 坐标。
    // 用于把 rowTops[](window 坐标)转换为容器内的相对坐标,让 ghost 渲染位置正确。
    val containerTopRef = remember { mutableStateOf(0f) }

    val draggingPhrase = remember(phrases, draggingIndex) {
        draggingIndex?.let { idx -> phrases.getOrNull(idx) }
    }
    // 是否能拖拽(列表模式才允许,编辑态不允许,新短语不允许)
    val canDrag = editing == null

    // 统一的拖拽结束处理:从 draggingIndex/onMove 拿到 from → 找最近行中心 → onMove
    fun finishDrag() {
        val fromIdx = draggingIndex ?: return
        // dragStartRowY 是容器内相对坐标(在 onStartDrag 里减去了 containerTop),
        // 但 rowTops 是 window 坐标,需要统一到同一参考系。
        // 这里把 rowTops 也转换为容器内相对坐标,然后用统一的参考系找最近行。
        val containerTop = containerTopRef.value
        val ghostCenterY = containerTop + dragStartRowY + dragOffsetY + (rowHeights[fromIdx] ?: 0f) / 2f
        var bestIdx = fromIdx
        var bestDist = Float.MAX_VALUE
        for (i in 0 until phrases.size) {
            val top = rowTops[i] ?: continue
            val h = rowHeights[i] ?: continue
            val center = top + h / 2f
            val d = kotlin.math.abs(center - ghostCenterY)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        if (bestIdx != fromIdx) {
            onMove(fromIdx, bestIdx)
        }
        draggingIndex = null
        dragOffsetY = 0f
    }

    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            // 记录外层容器在 window 中的 Y 坐标,后续渲染 ghost 时用来转换坐标系。
            containerTopRef.value = coords.boundsInWindow().top
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP),
        ) {
            phrases.forEachIndexed { index, phrase ->
                val isEditingThis = editing?.id == phrase.id
                val isDraggingThis = draggingIndex == index && draggingPhrase != null

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            rowTops[index] = coords.boundsInWindow().top
                            rowHeights[index] = coords.size.height.toFloat()
                        }
                        // ghost 视觉:被拖行平移 + 透明
                        .offset {
                            if (isDraggingThis) IntOffset(0, dragOffsetY.roundToInt()) else IntOffset.Zero
                        }
                        .alpha(if (isDraggingThis) 0.5f else 1f)
                ) {
                    if (isEditingThis) {
                        PhraseEditRow(
                            initial = phrase,
                            onSave = onSaveEdit,
                            onCancel = onCancelEdit,
                            colors = colors,
                        )
                    } else {
                        // 关键:canDrag 只由 "editing == null" 决定,不再跟随 draggingIndex 切换。
                        // 如果跟随 draggingIndex 切换,正在被拖的那一行的 DragHandle/LongPressDragArea
                        // 会从组合树中移除,导致其 pointerInput 协程被取消,立即触发 onCancel,
                        // 表现就是 "闪一下就结束"。
                        // 多行同时拖拽的防护放在 onStartDrag 内部(draggingIndex == null 守卫)。
                        PhraseListRow(
                            phrase = phrase,
                            index = index,
                            canDrag = canDrag,
                            onStartDrag = {
                                if (draggingIndex == null) {
                                    draggingIndex = index
                                    dragOffsetY = 0f
                                    // 记录的是 row 在 window 中的 Y,渲染 ghost 时减去容器顶 Y
                                    // 得到容器内相对偏移,这样 ghost 位置才正确。
                                    dragStartRowY = (rowTops[index] ?: 0f) - containerTopRef.value
                                }
                            },
                            onDragDelta = { deltaY ->
                                dragOffsetY += deltaY
                            },
                            onEndDrag = { finishDrag() },
                            onCancelDrag = {
                                draggingIndex = null
                                dragOffsetY = 0f
                            },
                            onEdit = { onEdit(phrase) },
                            onDelete = { onDelete(phrase) },
                            colors = colors,
                        )
                    }
                }
            }
            // 新增场景:editing.id 在 phrases 里不存在,渲染在列表最上方
            if (editing != null && phrases.none { it.id == editing.id }) {
                PhraseEditRow(
                    initial = editing,
                    onSave = onSaveEdit,
                    onCancel = onCancelEdit,
                    colors = colors,
                )
            }
        }

        // 浮在被拖行之上画一个"实色 ghost",让用户清楚看到正在拖的内容。
        // dragStartRowY 已经是容器内相对坐标(在 onStartDrag 里减去了 containerTop),
        // 所以这里直接加上 dragOffsetY 即可。
        val ghostIndex = draggingIndex
        if (ghostIndex != null && draggingPhrase != null && canDrag) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(0, (dragStartRowY + dragOffsetY).roundToInt())
                    }
                    .alpha(0.85f)
            ) {
                PhraseListRow(
                    phrase = draggingPhrase,
                    index = ghostIndex,
                    canDrag = false,
                    onStartDrag = {},
                    onDragDelta = {},
                    onEndDrag = {},
                    onCancelDrag = {},
                    onEdit = {},
                    onDelete = { false },
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 列表模式下的单行:左侧拖拽手柄(≡) + 色块 + 标题(着色) + 文本预览,右侧 Edit / Delete。
 *
 * **拖拽触发方式**(两种,任选其一):
 * 1. 按住左侧"≡"手柄:鼠标按下立即进入拖拽态,无需长按 — 最可靠的入口。
 * 2. 在手柄之外的行区域(色块 / 标题 / 文本预览列)**长按** ~500ms:进入拖拽态。
 *
 * 布局关键:标题/预览区域需要 `weight(1f)` 来占满剩余空间,所以拖拽组件
 * (`DragHandle` / `LongPressDragArea`)必须**作为 Row 的直接子节点**,
 * 而不是包在另一个 `Box` 里——`Modifier.weight` 是 `RowScope` 的扩展,
 * 放在 `Box` 里就变成 no-op,会导致标题把按钮挤出可视区。
 *
 * 不可删条目(isDeletable=false)的 Delete 按钮禁用(NEGATIVE tone + enabled=false),
 * 鼠标光标不变,点击无效。Delete 返回 false 时由 controller 兜底(为了 isDeletable 的
 * 防御性二次保护,即便按钮被误点也不会真删)。
 */
@Composable
private fun PhraseListRow(
    phrase: QuickPhrase,
    index: Int,
    canDrag: Boolean,
    onStartDrag: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onEndDrag: () -> Unit,
    onCancelDrag: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Boolean,
    colors: IdeColors,
) {
    val titleColor = parseColorOrNull(phrase.color) ?: colors.text
    val textPreview = phrase.text.lineSequence().first().let {
        if (it.length > 60) it.take(60) + "…" else it
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT_DP)
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧拖拽手柄(≡):鼠标按下立即进入拖拽,不需要长按。
        if (canDrag) {
            DragHandle(
                onStart = onStartDrag,
                onDrag = onDragDelta,
                onEnd = onEndDrag,
                onCancel = onCancelDrag,
                colors = colors,
            )
        } else {
            // 不可拖时(ghost 行 / 控件禁用)留同样宽度,让对齐统一
            Spacer(Modifier.width(DRAG_HANDLE_WIDTH))
        }

        // 色块(无配色时画一个细描边的空圆,让对齐统一)
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(parseColorOrNull(phrase.color) ?: Color.Transparent, CircleShape)
                .border(
                    BorderStroke(
                        1.dp,
                        if (parseColorOrNull(phrase.color) != null) parseColorOrNull(phrase.color)!!
                        else colors.fieldBorder
                    ),
                    CircleShape,
                ),
        )

        // 标题 + 文本预览:作为 Row 的直接子节点,这样 Modifier.weight(1f) 才能生效,
        // 保证标题区域占满剩余空间而不会被按钮挤压。拖拽组件直接以 Row 子节点形式
        // 接入,内部用 pointerInput 包裹,不会破坏 weight 的作用域。
        if (canDrag) {
            LongPressDragArea(
                modifier = Modifier.weight(1f),
                onStart = onStartDrag,
                onDrag = onDragDelta,
                onEnd = onEndDrag,
                onCancel = onCancelDrag,
            ) {
                Column {
                    Text(
                        text = phrase.title,
                        color = titleColor,
                        style = compactTextStyle(titleColor),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = textPreview,
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                        maxLines = 1,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phrase.title,
                    color = titleColor,
                    style = compactTextStyle(titleColor),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = textPreview,
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                    maxLines = 1,
                )
            }
        }
        CompactButton(
            text = "Edit",
            onClick = onEdit,
            colors = colors,
        )
        CompactButton(
            text = "Delete",
            onClick = { onDelete() },
            colors = colors,
            tone = ButtonTone.NEGATIVE,
            enabled = phrase.isDeletable,
        )
    }
}

/**
 * 拖拽手柄:鼠标按下立即进入拖拽,松开结束。
 * 比长按更直接、更可靠(不依赖长按计时器)。
 *
 * 关键设计:
 * 1. 用 [rememberUpdatedState] 包装回调,确保 `pointerInput` 协程内调用的是
 *    最新版本的回调——否则父组件重组后传入的新 lambda 永远不会被用到。
 * 2. 手柄的 `pointerInput` **始终挂载**(只要被应用到 Row),不在拖拽中途被移除。
 * 3. 用 `try/finally` 保证 onEnd/onCancel 在协程被取消时也能触发,父组件状态不残留。
 */
@Composable
private fun DragHandle(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 4.dp.toPx() }
    // 用 rememberUpdatedState 让 pointerInput 协程始终拿到最新的回调。
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnCancel by rememberUpdatedState(onCancel)
    Box(
        modifier = Modifier
            .size(width = DRAG_HANDLE_WIDTH, height = ROW_HEIGHT_DP - 12.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        // 立即开始拖拽(手柄不需要长按)
                        currentOnStart()
                        down.consume()
                        try {
                            // 跟踪拖拽直到松开或取消
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                // 只跟踪我们起始时的那个指针
                                val change: PointerInputChange =
                                    event.changes.firstOrNull { it.id == pointerId } ?: continue
                                if (change.changedToUp()) {
                                    change.consume()
                                    currentOnEnd()
                                    break
                                } else if (change.positionChanged()) {
                                    // 过滤掉微抖动
                                    if (kotlin.math.abs(change.position.y - change.previousPosition.y) > touchSlopPx) {
                                        change.consume()
                                        currentOnDrag(change.position.y - change.previousPosition.y)
                                    }
                                } else if (!change.pressed) {
                                    currentOnCancel()
                                    break
                                }
                            }
                        } catch (e: CancellationException) {
                            // 协程被取消时确保调用 onCancel,父组件状态不残留
                            currentOnCancel()
                            throw e
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 三横线 ≡
        Text(
            text = "≡",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )
    }
}

/**
 * 长按拖拽区域:鼠标按下后等长按超时(默认 500ms),期间移动距离不超过 touchSlop 则进入拖拽态。
 * 用 [awaitPointerEventScope] 手写,不依赖 [detectDragGesturesAfterLongPress]
 * (后者在 Compose Desktop 上长按计时器有时不触发,实测不可靠)。
 *
 * [modifier] 由调用方传入,通常带 `weight(1f)` 让标题区占满 Row 剩余空间。
 * 组件本身只做 gesture detection,不做布局约束。
 *
 * 关键设计同 [DragHandle]:用 [rememberUpdatedState] 包装回调,try/finally 保证清理。
 */
@Composable
private fun LongPressDragArea(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 4.dp.toPx() }
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnCancel by rememberUpdatedState(onCancel)
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val downPos = down.position
                        val pointerId = down.id
                        var isDragging = false
                        try {
                            // 等待:长按超时 / 移动超过 touchSlop / 松开
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                // 过滤:只处理我们正在跟踪的那个指针,避免被其它指针事件干扰。
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null) {
                                    // 我们的指针还没出现,继续等
                                    continue
                                }
                                if (!change.pressed) {
                                    // 松开或被取消
                                    if (isDragging) {
                                        currentOnEnd()
                                    }
                                    break
                                } else {
                                    val delta = change.position - downPos
                                    val dist = kotlin.math.sqrt(delta.x * delta.x + delta.y * delta.y)
                                    if (!isDragging) {
                                        val elapsed = System.currentTimeMillis() - downTime
                                        if (elapsed >= viewConfiguration.longPressTimeoutMillis) {
                                            // 长按计时器到点,进入拖拽态
                                            isDragging = true
                                            currentOnStart()
                                            change.consume()
                                        } else if (dist > touchSlopPx) {
                                            // 在长按计时到点前移动超过 slop,放弃长按
                                            break
                                        }
                                    } else {
                                        // 已进入拖拽:跟踪 Y 移动
                                        if (change.positionChanged()) {
                                            val dy = change.position.y - change.previousPosition.y
                                            if (kotlin.math.abs(dy) > 0.5f) {
                                                change.consume()
                                                currentOnDrag(dy)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            if (isDragging) {
                                currentOnCancel()
                            }
                            throw e
                        }
                    }
                }
            }
    ) {
        content()
    }
}

/**
 * 行内编辑态:在同一行下面展开 Title / Text / Color 三个字段 + Save / Cancel。
 * 校验:title 和 text 都不能为空;color 留空或合法格式都接受。
 */
@Composable
private fun PhraseEditRow(
    initial: QuickPhrase,
    onSave: (title: String, text: String, color: String?) -> Boolean,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var text by remember(initial.id) { mutableStateOf(initial.text) }
    var color by remember(initial.id) { mutableStateOf(initial.color.orEmpty()) }
    var error by remember(initial.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(3.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsLabel("Title", colors)
        CompactTextField(
            value = title,
            onValueChange = {
                title = it
                error = ""
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("Text", colors)
        CompactTextField(
            value = text,
            onValueChange = {
                text = it
                error = ""
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 5,
            colors = colors,
        )

        SettingsLabel("Color (optional)", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactTextField(
                value = color,
                onValueChange = {
                    color = it
                    error = ""
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            // 色块预览
            val parsed = parseColorOrNull(color)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(parsed ?: Color.Transparent, RoundedCornerShape(3.dp))
                    .border(
                        BorderStroke(1.dp, if (parsed != null) parsed else colors.fieldBorder),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
        Text(
            text = "支持 #RRGGBB / #RGB,或命名色 red/green/blue/orange/yellow/purple/pink/gray。留空表示无染色。",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        if (error.isNotEmpty()) {
            Text(
                text = error,
                color = Color(0xFFB91C1C),
                style = compactTextStyle(Color(0xFFB91C1C)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.weight(1f))
            CompactButton(
                text = "Cancel",
                onClick = onCancel,
                colors = colors,
            )
            CompactButton(
                text = "Save",
                onClick = {
                    val colorToSave = color.trim().takeIf { it.isNotEmpty() }
                    if (colorToSave != null && parseColorOrNull(colorToSave) == null) {
                        error = "颜色格式不合法,请使用 #RRGGBB 或命名色。"
                        return@CompactButton
                    }
                    val ok = onSave(title, text, colorToSave)
                    if (!ok) {
                        error = "标题和文本都不能为空。"
                    }
                },
                colors = colors,
                primary = true,
            )
        }
    }
}

/**
 * 解析 hex / 命名色,失败返回 null。
 * 与 SheetsManager 的解析规则保持一致(简化版)。
 */
private fun parseColorOrNull(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return runCatching {
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            if (hex.length != 3 && hex.length != 6) return@runCatching null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return@runCatching null
            val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            Color(r, g, b)
        } else {
            NAMED_COLORS[s.lowercase()]?.let { Color((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        }
    }.getOrNull()
}

private val NAMED_COLORS: Map<String, Int> = mapOf(
    "red" to 0xFF0000,
    "green" to 0x00FF00,
    "blue" to 0x0000FF,
    "yellow" to 0xFFFF00,
    "orange" to 0xFFA500.toInt(),
    "purple" to 0x800080,
    "pink" to 0xFFC0CB.toInt(),
    "gray" to 0x808080,
    "grey" to 0x808080,
)

// 列表行最小高度(用于 hit test 时的辅助估算;实际命中以 onGloballyPositioned 记录的高度为准)
private val ROW_HEIGHT_DP = 38.dp
private val ROW_SPACING_DP = 6.dp
private val DRAG_HANDLE_WIDTH = 18.dp
