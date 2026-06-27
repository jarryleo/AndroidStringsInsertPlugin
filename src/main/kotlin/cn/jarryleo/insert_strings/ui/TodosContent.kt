package cn.jarryleo.insert_strings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jarryleo.insert_strings.ai.TodoItem
import cn.jarryleo.insert_strings.ai.TodoPriority

/**
 * 主页「Todo」tab 的内容。
 *
 * **设计目标(交互友好)**:
 * 1. **三秒添加** —— 顶部固定「+ Add」按钮,空态时中间居中放大版「+ Add」,
 *    用户点开直接进入「行内展开的编辑表单」,无需额外弹窗 / 模态;
 * 2. **一键完成** —— 列表行最左侧是 22×22 dp 的方形 checkbox,点一下切换完成态
 *    (完成时整行变灰、标题加删除线、checkbox 打勾),service 自动写 completedAt 时间戳;
 * 3. **就地编辑** —— 点行右侧「Edit」按钮(或点标题文本)进入行内编辑,
 *    [Modifier.animateContentSize] 让列表行向下平滑展开 ~10 行高度的编辑表单
 *    (Title + Content + Priority + Save / Cancel),Save 后自动收起;
 * 4. **二次确认删除** —— 点「×」按钮**直接**删除(代办条数一般不多,二次确认反而打断心流),
 *    删错用 AI `todo_add` 一条回来成本极低;
 * 5. **过滤切换** —— 顶部三个 tab chip(All / Active / Completed),
 *    旁边带当前条数,用户切 tab 时有视觉反馈;
 * 6. **优先级视觉** —— 左侧色块 + 文字标识双重提示:
 *    LOW 灰 / NORMAL 蓝 / HIGH 橙 / URGENT 红;色块用 4dp 小圆点贴近标题。
 *
 * **空态** —— 列表为空时整个区域居中显示一个明显的大按钮「+ Add your first todo」,
 * 引导用户添加第一条;若处于「Completed」过滤且 active 不为空,改为「No completed todos」提示。
 *
 * **状态来源** —— 列表 / 编辑中条目 / 当前过滤由父组件持有,本组件只通过回调通知
 * controller。controller 落库后整体重排 [ui.todos],UI 自动重组。
 *
 * **与 AI 协作** —— AI 通过 `todo_*` 工具改动会经 controller 写库 + 同步 ui.todos,
 * 用户切到 Todo tab 时立即看到 AI 的最新结果,无需手动刷新。
 */
@Composable
fun TodosContent(
    todos: List<TodoItem>,
    activeCount: Int,
    completedCount: Int,
    currentFilter: TodoFilter,
    onFilterChange: (TodoFilter) -> Unit,
    editingTodo: TodoItem?,
    onAdd: () -> Unit,
    onEdit: (TodoItem) -> Unit,
    onDelete: (TodoItem) -> Unit,
    onSetCompleted: (TodoItem, Boolean) -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftContentChange: (String) -> Unit,
    onDraftPriorityChange: (TodoPriority) -> Unit,
    onSaveEdit: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶部:过滤 tab + Add 按钮
        TodosToolbar(
            currentFilter = currentFilter,
            onFilterChange = onFilterChange,
            activeCount = activeCount,
            completedCount = completedCount,
            onAdd = onAdd,
            colors = colors,
        )

        // 主体:列表 / 空态
        if (todos.isEmpty() && editingTodo == null) {
            TodosEmptyState(
                filter = currentFilter,
                onAdd = onAdd,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        } else {
            TodosList(
                todos = todos,
                editingTodo = editingTodo,
                onEdit = onEdit,
                onDelete = onDelete,
                onSetCompleted = onSetCompleted,
                onDraftTitleChange = onDraftTitleChange,
                onDraftContentChange = onDraftContentChange,
                onDraftPriorityChange = onDraftPriorityChange,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        }
    }
}

/**
 * 顶部过滤 tab + 计数 + Add 按钮。
 *
 * 三个 tab 风格与 [TabButton] 保持一致(选中 accent / 未选中 buttonBackground);
 * 每个 tab 右侧带 (N) 计数,让用户一眼看到 active / completed 数量。
 * 「+ Add」按钮始终在最右,点击进入新建态(列表底部追加新行 + 展开编辑表单)。
 */
@Composable
private fun TodosToolbar(
    currentFilter: TodoFilter,
    onFilterChange: (TodoFilter) -> Unit,
    activeCount: Int,
    completedCount: Int,
    onAdd: () -> Unit,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabButton(
            text = "All (${activeCount + completedCount})",
            selected = currentFilter == TodoFilter.ALL,
            onClick = { onFilterChange(TodoFilter.ALL) },
            colors = colors,
        )
        TabButton(
            text = "Active ($activeCount)",
            selected = currentFilter == TodoFilter.ACTIVE,
            onClick = { onFilterChange(TodoFilter.ACTIVE) },
            colors = colors,
        )
        TabButton(
            text = "Completed ($completedCount)",
            selected = currentFilter == TodoFilter.COMPLETED,
            onClick = { onFilterChange(TodoFilter.COMPLETED) },
            colors = colors,
        )
        Spacer(modifier = Modifier.weight(1f))
        CompactButton(
            text = "+ Add",
            onClick = onAdd,
            colors = colors,
            primary = true,
        )
    }
}

/**
 * 代办列表。
 *
 * 渲染策略:
 * - 对每个 [TodoItem] 渲染一行;
 *   当 [editingTodo]?.id == item.id 时,该行就地展开编辑表单(含 Title / Content / Priority + Save / Cancel);
 * - 末尾追加一个"新增未保存"的编辑行(仅当 editingTodo.id 不在 todos 中时)。
 *
 * 动画:每行外层用 [Modifier.animateContentSize] 包裹,Edit / Save / Cancel 切换时
 * 行高度平滑过渡,符合"就地展开"诉求。
 */
@Composable
private fun TodosList(
    todos: List<TodoItem>,
    editingTodo: TodoItem?,
    onEdit: (TodoItem) -> Unit,
    onDelete: (TodoItem) -> Unit,
    onSetCompleted: (TodoItem, Boolean) -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftContentChange: (String) -> Unit,
    onDraftPriorityChange: (TodoPriority) -> Unit,
    onSaveEdit: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        todos.forEach { item ->
            // 关键:用 [key] 给每个 TodoRow 稳定标识(基于 item.id),让 Compose 正确追踪
            // 单条代办在列表中的位置变化(比如从 active 搬到 completed)。
            // 缺这个 key 时,完成态切换后 UI 可能不更新(Compose 按位置匹配,把"刚完成的"
            // 误当成"原来位置上的另一个 item"而跳过重组)。
            key(item.id) {
                val isEditingThis = editingTodo?.id == item.id
                TodoRow(
                    item = item,
                    draft = if (isEditingThis) editingTodo else null,
                    isEditing = isEditingThis,
                    onStartEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onToggleCompleted = { onSetCompleted(item, !item.isCompleted) },
                    onDraftTitleChange = onDraftTitleChange,
                    onDraftContentChange = onDraftContentChange,
                    onDraftPriorityChange = onDraftPriorityChange,
                    onSaveEdit = onSaveEdit,
                    onCancelEdit = onCancelEdit,
                    colors = colors,
                )
            }
        }
        // 新增场景:editingTodo.id 不在 todos 中 → 在列表底部追加一个编辑行
        if (editingTodo != null && todos.none { it.id == editingTodo.id }) {
            // 新建中的代办用临时 UUID 作 key,与已有代办天然不冲突;
            // 编辑已有代办时,editingTodo.id == 已有代办 id,不会进这个分支。
            key(editingTodo.id) {
                TodoRow(
                    item = editingTodo,
                    draft = editingTodo,
                    isEditing = true,
                    onStartEdit = {},
                    onDelete = {},
                    onToggleCompleted = {},
                    onDraftTitleChange = onDraftTitleChange,
                    onDraftContentChange = onDraftContentChange,
                    onDraftPriorityChange = onDraftPriorityChange,
                    onSaveEdit = onSaveEdit,
                    onCancelEdit = onCancelEdit,
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 单条代办行(列表态 + 编辑态合一)。
 *
 * 列表态:checkbox(左) + 优先级色块 + 标题(粗体) + content 预览(若有) + Edit / × 按钮(右);
 * 编辑态:在原行下方就地展开 [TodoEditForm],高度随 content 文本框自适应;
 *        checkbox / Edit / × 全部禁用(防止编辑中误操作)。
 *
 * 视觉:
 * - 未完成:正常文本色,无删除线,checkbox 未勾选;
 * - 已完成:次要文本色 + 删除线 + 整行 headerBackground 浅色背景,checkbox 勾选;
 * - 编辑中:accent 描边。
 */
@Composable
private fun TodoRow(
    item: TodoItem,
    draft: TodoItem?,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: () -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftContentChange: (String) -> Unit,
    onDraftPriorityChange: (TodoPriority) -> Unit,
    onSaveEdit: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancelEdit: () -> Unit,
    colors: IdeColors,
) {
    val isCompleted = item.completeState.value
    val borderColor = when {
        isEditing -> colors.accent
        isCompleted -> colors.grid
        else -> colors.fieldBorder
    }
    val background = when {
        isCompleted -> colors.headerBackground
        else -> colors.tableBackground
    }
    val titleColor = if (isCompleted) colors.secondaryText else colors.text
    val displayTitle = item.title.ifBlank { "(untitled)" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(3.dp))
            .animateContentSize(),
    ) {
        // 标题栏(列表态 / 编辑态都显示)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // checkbox
            TodoCheckbox(
                checked = isCompleted,
                enabled = !isEditing,
                onToggle = onToggleCompleted,
                colors = colors,
            )
            // 优先级色块(4dp 小圆点)
            PriorityDot(priority = item.priority)
            // 标题 + 描述(占 weight=1f,撑满剩余宽度)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    color = titleColor,
                    style = compactTextStyle(titleColor),
                    fontWeight = if (isCompleted) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                )
                if (item.content.isNotBlank()) {
                    Text(
                        text = item.content,
                        color = if (isCompleted) colors.secondaryText else colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                    )
                }
            }
            // 优先级文字标识(URGENT/HIGH 强调,LOW/NORMAL 灰色)
            PriorityLabel(priority = item.priority, isCompleted = isCompleted, colors = colors)
            // Edit 按钮
            CompactButton(
                text = "Edit",
                onClick = onStartEdit,
                colors = colors,
                enabled = !isEditing,
            )
            // × 删除按钮
            CompactButton(
                text = "×",
                onClick = onDelete,
                colors = colors,
                tone = ButtonTone.NEGATIVE,
                enabled = !isEditing,
                modifier = Modifier.width(32.dp),
            )
        }
        // 编辑态:就地展开编辑表单
        if (isEditing && draft != null) {
            TodoEditForm(
                initialTitle = draft.title,
                initialContent = draft.content,
                initialPriority = draft.priority,
                onTitleChange = onDraftTitleChange,
                onContentChange = onDraftContentChange,
                onPriorityChange = onDraftPriorityChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                colors = colors,
            )
        }
    }
}

/**
 * 自定义 22x22dp checkbox。
 *
 * 不用系统 Checkbox 是因为 IDE 主题差异下原生 Checkbox 经常看不清勾选态;
 * 这里手画:外框 + 内部填充(勾选时填 accent),无任何第三方依赖。
 *
 * 关键:[Modifier.clickable] 绑在 Box 上,带 enabled 守卫(编辑中禁用);
 * interactionSource 抑制默认波纹,符合整个 UI 的"无波纹"风格。
 */
@Composable
private fun TodoCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    colors: IdeColors,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                if (checked) colors.accent else Color.Transparent,
                RoundedCornerShape(3.dp),
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (checked) colors.accent else colors.fieldBorder,
                ),
                RoundedCornerShape(3.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            // 勾号:用对勾字符,Compose Desktop 字体下显示正常
            Text(
                text = "✓",
                color = colors.accentText,
                style = compactTextStyle(colors.accentText)
                    .copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * 优先级色块:4dp 直径的小圆点,贴近 checkbox,扫一眼就能识别轻重缓急。
 * 颜色定义见 [priorityColor] —— LOW 灰 / NORMAL 蓝 / HIGH 橙 / URGENT 红。
 */
@Composable
private fun PriorityDot(priority: TodoPriority) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(priorityColor(priority), CircleShape)
    )
}

/**
 * 优先级文字标识:URGENT / HIGH 用各自颜色粗体,LOW / NORMAL 灰色小字。
 * 已完成时代替为次要色 + 删除线,与标题风格一致。
 */
@Composable
private fun PriorityLabel(
    priority: TodoPriority,
    isCompleted: Boolean,
    colors: IdeColors,
) {
    val color = when {
        isCompleted -> colors.secondaryText
        else -> priorityColor(priority)
    }
    Text(
        text = priority.name,
        color = color,
        style = compactTextStyle(color).copy(fontWeight = if (priority == TodoPriority.URGENT) FontWeight.Bold else FontWeight.SemiBold),
        textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
    )
}

/**
 * 优先级到颜色的映射。
 * 颜色取与现有 ButtonTone 一致的色相,但只取主色,避免全 button 配色过于花哨。
 */
private fun priorityColor(priority: TodoPriority): Color = when (priority) {
    TodoPriority.LOW -> Color(0xFF6E6E6E)
    TodoPriority.NORMAL -> Color(0xFF3574F0)
    TodoPriority.HIGH -> Color(0xFFEA580C)
    TodoPriority.URGENT -> Color(0xFFDC2626)
}

/**
 * 行内编辑表单:Title(必填)/ Content(可选,多行)/ Priority(下拉)/ Save / Cancel。
 * 展开高度按 content 文本框自适应;Title 单行。
 * Save 时 controller 校验 title 非空;Cancel 直接丢弃。
 */
@Composable
private fun TodoEditForm(
    initialTitle: String,
    initialContent: String,
    initialPriority: TodoPriority,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onPriorityChange: (TodoPriority) -> Unit,
    onSave: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var priority by remember(initialPriority) { mutableStateOf(initialPriority) }
    var error by remember(initialTitle, initialContent) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
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

        SettingsLabel("Content (optional)", colors)
        CompactTextField(
            value = content,
            onValueChange = {
                content = it
                error = ""
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 6,
            colors = colors,
        )

        SettingsLabel("Priority", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 四个紧凑按钮,选中态用对应 priority 颜色,未选中用普通按钮色
            TodoPriority.entries.forEach { p ->
                CompactButton(
                    text = p.name,
                    onClick = { priority = p },
                    colors = colors,
                    primary = priority == p,
                )
            }
        }

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
                    val ok = onSave(title, content, priority)
                    if (!ok) {
                        error = "标题不能为空。"
                    }
                },
                colors = colors,
                primary = true,
            )
        }
    }
}

/**
 * 空态视图:列表为空时,中间居中显示一个明显的大按钮引导用户添加。
 *
 * - ALL 过滤 + 空 → 「+ Add your first todo」(主引导)
 * - ACTIVE 过滤 + 空 → 「No active todos · 全部完成啦 🎉」+ Add 按钮(允许新增)
 * - COMPLETED 过滤 + 空 → 「No completed todos」(不需要 Add,只是过滤问题)
 */
@Composable
private fun TodosEmptyState(
    filter: TodoFilter,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val (title, subtitle, showAdd) = when (filter) {
        TodoFilter.ALL -> Triple("No todos yet", "把要做的事情记下来,逐项勾掉", true)
        TodoFilter.ACTIVE -> Triple("No active todos", "全部完成,继续保持 🎉", true)
        TodoFilter.COMPLETED -> Triple("No completed todos", "勾选 todo 后会出现在这里", false)
    }
    Box(
        modifier = modifier
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .heightIn(min = 140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "📋",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText).copy(fontSize = 32.sp),
            )
            Text(
                text = title,
                color = colors.text,
                style = compactTextStyle(colors.text).copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
            if (showAdd) {
                Box(
                    modifier = Modifier
                        .background(colors.accent, RoundedCornerShape(3.dp))
                        .border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(3.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onAdd,
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+ Add your first todo",
                        color = colors.accentText,
                        style = compactTextStyle(colors.accentText).copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * 代办列表的过滤模式。
 * - [ALL]      全部(未完成 + 已完成),未完成在上前
 * - [ACTIVE]   仅未完成
 * - [COMPLETED] 仅已完成
 */
enum class TodoFilter {
    ALL,
    ACTIVE,
    COMPLETED,
}
