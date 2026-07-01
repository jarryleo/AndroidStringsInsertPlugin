package cn.jarryleo.android.buddy.ui

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
import cn.jarryleo.android.buddy.ai.TodoItem
import cn.jarryleo.android.buddy.ai.TodoPriority
import cn.jarryleo.android.buddy.ai.TodoRecurrence
import cn.jarryleo.android.buddy.ai.TodoReminder
import cn.jarryleo.android.buddy.ai.TodoTimeOfDay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
 *    (Title + Content + Priority + **Reminder** + Save / Cancel),Save 后自动收起;
 * 4. **二次确认删除** —— 点「×」按钮**直接**删除(代办条数一般不多,二次确认反而打断心流),
 *    删错用 AI `todo_add` 一条回来成本极低;
 * 5. **过滤切换** —— 顶部三个 tab chip(All / Active / Completed),
 *    旁边带当前条数,用户切 tab 时有视觉反馈;
 * 6. **优先级视觉** —— 左侧色块 + 文字标识双重提示:
 *    LOW 灰 / NORMAL 蓝 / HIGH 橙 / URGENT 红;色块用 4dp 小圆点贴近标题;
 * 7. **提醒标识** —— 有提醒的 todo 在 checkbox 旁显示 ⏰ 闹钟图标,
 *    鼠标移上去(hover)显示完整「下次触发时间 + 循环类型 + 时间点」tooltip,
 *    让用户一眼看出哪些 todo 设了提醒 / 什么时候会触发。
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
    /**
     * 「Save Reminder」按钮回调:把当前编辑草稿里的 reminder 字段单独写库(不动 title/content/priority)。
     * 返回 true 表示成功,false 表示校验失败(UI toast 提示)。
     */
    onSaveReminder: (TodoReminder?) -> Boolean,
    /**
     * 提醒相关 toast(由 [InsertStringsUI.showToast] 透传);
     * 通知用户"已保存提醒 / 已清除提醒 / 已关闭提醒"。
     */
    onShowToast: (String) -> Unit = {},
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
                onSaveReminder = onSaveReminder,
                onShowToast = onShowToast,
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
    onSaveReminder: (TodoReminder?) -> Boolean,
    onShowToast: (String) -> Unit,
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
                    onSaveReminder = onSaveReminder,
                    onShowToast = onShowToast,
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
                    onSaveReminder = onSaveReminder,
                    onShowToast = onShowToast,
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 单条代办行(列表态 + 编辑态合一)。
 *
 * 列表态:checkbox(左) + **闹钟图标**(若有提醒) + 优先级色块 + 标题(粗体) + content 预览(若有)
 *        + **下次提醒时间**(若有提醒,子标题展示) + Edit / × 按钮(右);
 * 编辑态:在原行下方就地展开 [TodoEditForm],高度随 content 文本框自适应;
 *        checkbox / Edit / × 全部禁用(防止编辑中误操作)。
 *
 * 视觉:
 * - 未完成:正常文本色,无删除线,checkbox 未勾选;
 * - 已完成:次要文本色 + 删除线 + 整行 headerBackground 浅色背景,checkbox 勾选;
 * - 编辑中:accent 描边;
 * - 有提醒:checkbox 旁 ⏰ 图标(accent 蓝色),子标题展示「⏰ 下次:yyyy-MM-dd HH:mm」+ 循环类型标签。
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
    onSaveReminder: (TodoReminder?) -> Boolean,
    onShowToast: (String) -> Unit,
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
            // 闹钟图标:有 reminder 时显示;列表态(非编辑)用 ⏰ 字符 + 副标题展示时间
            ReminderIndicator(
                reminder = item.reminder,
                isEditing = isEditing,
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
                // 有提醒时,副标题展示下次时间(列表态)
                val reminder = item.reminder
                if (!isEditing && reminder != null) {
                    val next = reminder.nextTriggerAt
                    val nextText = if (next != null) formatReminderTime(next) else "(未设置)"
                    val recurrenceText = formatRecurrence(reminder)
                    val isExpired = isReminderExpired(reminder)
                    val expiredLabel = if (isExpired) "  ·  ⚠ 已过期" else ""
                    val displayColor = when {
                        isCompleted -> colors.secondaryText
                        isExpired -> EXPIRED_REMINDER_COLOR
                        else -> colors.accent
                    }
                    Text(
                        text = "⏰ 下次:$nextText  ·  $recurrenceText$expiredLabel",
                        color = displayColor,
                        style = compactTextStyle(displayColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                initialReminder = draft.reminder,
                onTitleChange = onDraftTitleChange,
                onContentChange = onDraftContentChange,
                onPriorityChange = onDraftPriorityChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                onSaveReminder = onSaveReminder,
                onShowToast = onShowToast,
                targetItem = item,
                colors = colors,
            )
        }
    }
}

/**
 * 闹钟图标:有 reminder 时显示 ⏰ 字符(accent 色),无 reminder 时显示空白占位保持布局稳定。
 * 编辑态时不显示图标(让编辑表单单独展示 reminder section,避免视觉拥挤)。
 */
@Composable
private fun ReminderIndicator(reminder: TodoReminder?, isEditing: Boolean) {
    val showIcon = !isEditing && reminder != null
    if (showIcon) {
        Text(
            text = "⏰",
            color = reminder?.let { colors ->
                // 借用 colors 的方式稍嫌繁,这里直接用主题色
                androidx.compose.ui.graphics.Color(0xFFEA580C)
            } ?: androidx.compose.ui.graphics.Color.Unspecified,
            style = compactTextStyle(androidx.compose.ui.graphics.Color(0xFFEA580C))
                .copy(fontSize = 14.sp),
        )
    } else {
        // 占位:维持 14sp 宽度的空白,避免 checkbox 与优先级色块挤压
        Spacer(modifier = Modifier.size(0.dp))
    }
}

/**
 * 已过期提醒的标注色(深红,与普通 accent 蓝形成对比,显眼但不至于扎眼)。
 * 与 [TodoReminderSection] 里校验失败的红色保持一致以视觉统一。
 */
private val EXPIRED_REMINDER_COLOR = Color(0xFFB91C1C)

/**
 * 判断 [r] 是否处于「已过期」状态:仅当下一次触发时间已过去才算。
 * - 关闭的(enabled=false)不算过期(用户已主动停用);
 * - nextTriggerAt 为 null(草稿态)也不算。
 * - 用于列表副标题、编辑面板 Save 时的提示、AI 工具结果。
 */
private fun isReminderExpired(r: TodoReminder, now: Long = System.currentTimeMillis()): Boolean {
    val at = r.nextTriggerAt ?: return false
    return r.enabled && at < now
}

/**
 * 格式化为 `MM-dd HH:mm` 短字符串(列表态副标题用,空间有限)。
 */
private fun formatReminderTime(timestamp: Long): String {
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

/**
 * 循环类型的简短描述,例如「每日 09:30」「周一/三/五 09:30」「一次性」。
 */
@Suppress("DEPRECATION")
private fun formatRecurrence(r: TodoReminder): String {
    val tod = r.timeOfDay?.format() ?: "-"
    return when (r.recurrence) {
        TodoRecurrence.NONE -> "一次性 $tod"
        TodoRecurrence.DAILY -> "每日 $tod"
        TodoRecurrence.CUSTOM -> {
            val days = r.recurrenceDays.sorted().joinToString("") { dowToShort(it) }
            "每周$days $tod"
        }
        // 兼容老数据:setter 会立即迁移,理论上这里看不到;兜底走 CUSTOM 显示。
        TodoRecurrence.WEEKDAYS, TodoRecurrence.WEEKLY -> "每周 ${r.recurrenceDays.sorted().joinToString("") { dowToShort(it) }} $tod"
    }
}

private fun dowToShort(dow: Int): String = when (dow) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> "?"
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
 * 行内编辑表单:Title(必填)/ Content(可选,多行)/ Priority(下拉)/
 * **Reminder**(可选,见 [TodoReminderSection])/ Save / Cancel。
 * 展开高度按 content 文本框自适应;Title 单行。
 * Save 时 controller 校验 title 非空;Cancel 直接丢弃。
 *
 * Reminder 子表单独立有自己的「Save Reminder」与「Clear」按钮 —
 * 用户可以单独保存 reminder 而不动 title/content/priority,
 * 也可一键清掉整条提醒。
 */
@Composable
private fun TodoEditForm(
    initialTitle: String,
    initialContent: String,
    initialPriority: TodoPriority,
    initialReminder: TodoReminder?,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onPriorityChange: (TodoPriority) -> Unit,
    onSave: (title: String, content: String, priority: TodoPriority) -> Boolean,
    onCancel: () -> Unit,
    onSaveReminder: (TodoReminder?) -> Boolean,
    onShowToast: (String) -> Unit,
    targetItem: TodoItem,
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

        // ===== Reminder 子表单 =====
        TodoReminderSection(
            initial = initialReminder,
            onSave = onSaveReminder,
            onShowToast = onShowToast,
            targetItem = targetItem,
            colors = colors,
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
 * Reminder 编辑子表单:循环类型下拉 + 时间点选择 + 自定义星期多选 + 「保存提醒」按钮。
 *
 * 设计:
 * - 时间采用本地时区的"绝对时间"输入(默认 = 当前小时:分钟),配合循环类型即可表达
 *   任何"X 时间点 / X 时 X 分"的需求;UI 不解析"明天 9 点"这种自然语言,
 *   留给用户手动改。
 * - 一键「+5m / Now / 自定义时间」快速按钮覆盖最常见场景;+5m 是**累加**模式(每次点 +5 分钟),
 *   与弹框里"X 分钟后再提醒"的延迟语义**不同**——弹框的 X 分钟是"现在延后 N 分钟触发",
 *   而这里的 +5m 是"把表单里的时间 HH:MM 向前加 5 分钟"。
 * - "Save Reminder" 按钮独立提交,不动 title/content/priority;
 *   "Clear" 按钮把 reminder 整体清掉(null)。
 *
 * **状态模型**(避坑指南,2026.x 重构后):
 * - `hourText` / `minuteText` 是**用户面向的输入状态**;**不要**给它们的 `remember` 加
 *   `timeOfDay` 之类的依赖键 —— 那会让用户每输入一个字符,输入框就被重置,只能输入 1 位数。
 * - `timeOfDay` 只在「Save Reminder」时由 hourText/minuteText 解析得到,不在输入过程中维护。
 * - 「Now / +5m」按钮同时更新 hourText + minuteText(让输入框显示新值)。
 * - 校验:hour ∈ [0,23],minute ∈ [0,59],Save 时把无效输入显示为红色提示并不入库。
 *
 * **指定日期模式**(2026.x 一次性新增):
 * - recurrence=NONE 时,UI 多出「日期」输入行(YYYY / MM / DD 三个数字框 + Today / Tomorrow 快捷按钮);
 * - 日期只对一次性提醒生效(DAILY/CUSTOM 时整行不显示,沿用旧逻辑)。
 * - 老 reminder 里 nextTriggerAt 是绝对 timestamp;UI 启动时把 timestamp 反推成「日期 + 时分」作为默认值。
 */
@Composable
private fun TodoReminderSection(
    initial: TodoReminder?,
    onSave: (TodoReminder?) -> Boolean,
    onShowToast: (String) -> Unit,
    targetItem: TodoItem,
    colors: IdeColors,
) {
    // 草稿态:循环类型 + 时间点(hourText/minuteText 字符串)+ 自定义 days
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var recurrence by remember(initial) { mutableStateOf(initial?.recurrence ?: TodoRecurrence.NONE) }
    // 时间点初始化:优先用 initial,否则用现在的小时/分钟
    val seedHour = initial?.timeOfDay?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val seedMinute = initial?.timeOfDay?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
    // 关键:remember 仅以 initial 为 key(initial 不变则保留用户已输入的字符);
    // 不要把 timeOfDay 作为 key 加进来,否则每输入一位就会重置输入框。
    var hourText by remember(initial) { mutableStateOf("%02d".format(seedHour)) }
    var minuteText by remember(initial) { mutableStateOf("%02d".format(seedMinute)) }
    var customDays by remember(initial) {
        mutableStateOf((initial?.recurrenceDays ?: emptySet()).toMutableSet())
    }
    // 指定日期(NONE 时用):从 initial.nextTriggerAt 反推本地年月日,无值时回退今天。
    // 只在 initial 变化时同步(用户改输入框时不会被覆盖),与 hourText/minuteText 策略一致。
    val seedCal = Calendar.getInstance().apply {
        if (initial?.nextTriggerAt != null) timeInMillis = initial.nextTriggerAt!!
    }
    var yearText by remember(initial) { mutableStateOf("%04d".format(seedCal.get(Calendar.YEAR))) }
    var monthText by remember(initial) { mutableStateOf("%02d".format(seedCal.get(Calendar.MONTH) + 1)) }
    var dayText by remember(initial) { mutableStateOf("%02d".format(seedCal.get(Calendar.DAY_OF_MONTH))) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.headerBackground, RoundedCornerShape(3.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsLabel("Reminder", colors)
            Spacer(Modifier.weight(1f))
            // 启用 / 关闭开关(简单 toggle,不另起按钮组,直接用 CompactButton 表示)
            CompactButton(
                text = if (enabled) "On" else "Off",
                onClick = { enabled = !enabled },
                colors = colors,
                primary = enabled,
                modifier = Modifier.width(48.dp),
            )
        }

        // 循环类型下拉(用 CompactButton 模拟,避免引入 DropdownMenu)
        // 只展示 NONE/DAILY/CUSTOM 三个;WEEKDAYS/WEEKLY 已合并到 CUSTOM,UI 不再提供。
        SettingsLabel("Recurrence", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val visible = listOf(TodoRecurrence.NONE, TodoRecurrence.DAILY, TodoRecurrence.CUSTOM)
            visible.forEach { r ->
                CompactButton(
                    text = r.displayName,
                    onClick = { recurrence = r },
                    colors = colors,
                    primary = recurrence == r,
                )
            }
        }

        // 指定日期(2026.x 一次性新增):仅 NONE 时显示。DAILY / CUSTOM 由循环规则决定日期,无需指定。
        if (recurrence == TodoRecurrence.NONE) {
            SettingsLabel("Date", colors)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactTextField(
                    value = yearText,
                    onValueChange = { input ->
                        yearText = input.filter { it.isDigit() }.take(4)
                    },
                    modifier = Modifier.width(56.dp),
                    singleLine = true,
                    colors = colors,
                )
                Text("-", color = colors.text, style = compactTextStyle(colors.text))
                CompactTextField(
                    value = monthText,
                    onValueChange = { input ->
                        monthText = input.filter { it.isDigit() }.take(2)
                    },
                    modifier = Modifier.width(40.dp),
                    singleLine = true,
                    colors = colors,
                )
                Text("-", color = colors.text, style = compactTextStyle(colors.text))
                CompactTextField(
                    value = dayText,
                    onValueChange = { input ->
                        dayText = input.filter { it.isDigit() }.take(2)
                    },
                    modifier = Modifier.width(40.dp),
                    singleLine = true,
                    colors = colors,
                )
                Spacer(Modifier.width(6.dp))
                CompactButton(
                    text = "Today",
                    onClick = {
                        val cal = Calendar.getInstance()
                        yearText = "%04d".format(cal.get(Calendar.YEAR))
                        monthText = "%02d".format(cal.get(Calendar.MONTH) + 1)
                        dayText = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
                    },
                    colors = colors,
                )
                CompactButton(
                    text = "Tomorrow",
                    onClick = {
                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
                        yearText = "%04d".format(cal.get(Calendar.YEAR))
                        monthText = "%02d".format(cal.get(Calendar.MONTH) + 1)
                        dayText = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
                    },
                    colors = colors,
                )
            }
        }

        // 时间点:小时 + 分钟两个数字输入框 + Now/+5m 快捷按钮
        SettingsLabel("Time of day", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactTextField(
                value = hourText,
                onValueChange = { input ->
                    hourText = input.filter { it.isDigit() }.take(2)
                },
                modifier = Modifier.width(50.dp),
                singleLine = true,
                colors = colors,
            )
            Text(":", color = colors.text, style = compactTextStyle(colors.text))
            CompactTextField(
                value = minuteText,
                onValueChange = { input ->
                    minuteText = input.filter { it.isDigit() }.take(2)
                },
                modifier = Modifier.width(50.dp),
                singleLine = true,
                colors = colors,
            )
            Spacer(Modifier.width(8.dp))
            // 「Now」按钮:把时间设成现在
            CompactButton(
                text = "Now",
                onClick = {
                    val now = Calendar.getInstance()
                    hourText = "%02d".format(now.get(Calendar.HOUR_OF_DAY))
                    minuteText = "%02d".format(now.get(Calendar.MINUTE))
                },
                colors = colors,
            )
            // 「+5m」按钮:把当前 hourText:minuteText 向前加 5 分钟,跨小时 / 跨天时滚动。
            // 累加模式:每次点击都加 5 分钟,与「弹框的 N 分钟后再提醒」语义不同 —
            // 弹框是 now+N 触发,这里是修改表单的 HH:MM 输入框,适合"调到下班前 17:35"这种场景。
            CompactButton(
                text = "+5m",
                onClick = {
                    val h = hourText.toIntOrNull() ?: 0
                    val m = minuteText.toIntOrNull() ?: 0
                    val totalMinutes = h * 60 + m + 5
                    val newH = (totalMinutes / 60) % 24
                    val newM = totalMinutes % 60
                    hourText = "%02d".format(newH)
                    minuteText = "%02d".format(newM)
                },
                colors = colors,
            )
        }
        // 校验提示:输入的 hour/minute 不合法时,实时显示红字(让用户在 Save 之前就能发现)。
        val hourValid = hourText.isNotEmpty() && (hourText.toIntOrNull() ?: -1) in 0..23
        val minuteValid = minuteText.isNotEmpty() && (minuteText.toIntOrNull() ?: -1) in 0..59
        if (enabled && (!hourValid || !minuteValid)) {
            val msg = when {
                !hourValid && !minuteValid -> "小时必须是 0-23,分钟必须是 0-59。"
                !hourValid -> "小时必须是 0-23,当前: ${hourText}"
                else -> "分钟必须是 0-59,当前: ${minuteText}"
            }
            Text(
                text = msg,
                color = Color(0xFFB91C1C),
                style = compactTextStyle(Color(0xFFB91C1C)),
            )
        }

        // 实时过期检测(2026.x):enabled 且用户已设完整时间时,如果草稿 timestamp < now,
        // 提示「该时间已过期」。这样用户在改时分 / 日期时能立刻看到状态变化,不用等点 Save。
        // 计算用 try/catch 兜底:日期不合法时 h/m/d 解析可能抛错,此时不显示过期提示(避免误导)。
        if (enabled && hourValid && minuteValid) {
            val draftTrigger: Long? = try {
                val h = hourText.toInt().coerceIn(0, 23)
                val m = minuteText.toInt().coerceIn(0, 59)
                if (recurrence == TodoRecurrence.NONE) {
                    val y = yearText.toIntOrNull() ?: 0
                    val mo = monthText.toIntOrNull() ?: 0
                    val d = dayText.toIntOrNull() ?: 0
                    if (y < 1970 || mo !in 1..12 || d !in 1..31) null
                    else Calendar.getInstance().apply {
                        clear()
                        set(y, mo - 1, d, h, m, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                } else {
                    Calendar.getInstance().apply {
                        timeInMillis = System.currentTimeMillis()
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
            } catch (e: Exception) {
                null
            }
            if (draftTrigger != null && draftTrigger < System.currentTimeMillis()) {
                Text(
                    text = "⚠ 该时间已过期,保存后会立即触发(< 24h)或被静默清除(≥ 24h)。",
                    color = EXPIRED_REMINDER_COLOR,
                    style = compactTextStyle(EXPIRED_REMINDER_COLOR),
                )
            }
        }

        // 自定义星期多选(仅 CUSTOM 时显示)
        if (recurrence == TodoRecurrence.CUSTOM) {
            SettingsLabel("Days of week", colors)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dayLabels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
                dayLabels.forEach { (dow, label) ->
                    val isSelected = dow in customDays
                    CompactButton(
                        text = label,
                        onClick = {
                            customDays = customDays.toMutableSet().apply {
                                if (isSelected) remove(dow) else add(dow)
                            }
                        },
                        colors = colors,
                        primary = isSelected,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }

        if (error.isNotEmpty()) {
            Text(
                text = error,
                color = Color(0xFFB91C1C),
                style = compactTextStyle(Color(0xFFB91C1C)),
            )
        }

        // 按钮行:保存提醒 / 清除
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactButton(
                text = "Clear",
                onClick = {
                    val ok = onSave(null)
                    if (!ok) {
                        error = "清除提醒失败。"
                    } else {
                        error = ""
                        onShowToast("已清除提醒")
                    }
                },
                colors = colors,
                tone = ButtonTone.NEGATIVE,
            )
            Spacer(Modifier.weight(1f))
            CompactButton(
                text = "Save Reminder",
                onClick = {
                    if (!enabled) {
                        // 关闭时等价于"清掉 reminder"
                        val ok = onSave(null)
                        if (!ok) {
                            error = "保存提醒失败。"
                        } else {
                            error = ""
                            onShowToast("已关闭提醒")
                        }
                        return@CompactButton
                    }
                    // 兜底校验(虽然上面有实时提示,这里再防一手)
                    val h = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val m = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    if ((hourText.toIntOrNull() ?: -1) !in 0..23 || (minuteText.toIntOrNull() ?: -1) !in 0..59) {
                        error = "时间不合法,请检查 HH:MM。"
                        return@CompactButton
                    }
                    val timeOfDay = TodoTimeOfDay(h, m)
                    val now = System.currentTimeMillis()
                    val finalTrigger: Long = if (recurrence == TodoRecurrence.NONE) {
                        // 一次性:按"用户指定的日期 + 时分"组装 timestamp,即使该时间已过也尊重日期
                        // (用户写「3 月 15 日 10 点」就是 3 月 15 日 10 点;过期由 scheduler 兜底处理)。
                        val y = yearText.toIntOrNull() ?: 0
                        val mo = monthText.toIntOrNull() ?: 0
                        val d = dayText.toIntOrNull() ?: 0
                        if (y < 1970 || mo !in 1..12 || d !in 1..31) {
                            error = "日期不合法,请检查 YYYY-MM-DD。"
                            return@CompactButton
                        }
                        val cal = Calendar.getInstance().apply {
                            clear()
                            set(y, mo - 1, d, h, m, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        cal.timeInMillis
                    } else {
                        // 循环:按今天的时分组装,scheduler 会自己算下一次触发。
                        combineNowWithTimeOfDay(now, timeOfDay)
                    }
                    val reminder = TodoReminder(
                        enabled = true,
                        nextTriggerAt = finalTrigger,
                        recurrence = recurrence,
                        timeOfDay = timeOfDay,
                        recurrenceDays = customDays,
                    )
                    val validateErr = reminder.validate()
                    if (validateErr != null) {
                        error = validateErr
                        return@CompactButton
                    }
                    // 过期检测(2026.x):让用户设当前时间之前的提醒时,UI 提示「该时间已过期」;
                    // 不阻止保存(用户可能就是想「立即提醒」,scheduler 会按 24h 内立即触发 / 超 24h 静默清除处理),
                    // 但要让用户清楚后果,避免误以为下次会按这个时间点触发。
                    val expired = isReminderExpired(reminder)
                    val ok = onSave(reminder)
                    if (!ok) {
                        error = "保存提醒失败。"
                    } else {
                        error = ""
                        val next = formatReminderTime(finalTrigger)
                        if (expired) {
                            onShowToast("已保存提醒 ⚠ 该时间已过期,触发会立即/在 24h 静默期内弹出")
                        } else {
                            onShowToast("已保存提醒,下次触发: $next")
                        }
                    }
                },
                colors = colors,
                primary = true,
            )
        }
    }
}

/**
 * 把"今天"日期 + [tod] 小时分钟组合成时间戳;若该时间已过(<= [now]),
 * 调用方自己决定是返回原值还是 +1 天(见 [TodoReminderSection] 的 Save Reminder 按钮)。
 */
private fun combineNowWithTimeOfDay(now: Long, tod: TodoTimeOfDay): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, tod.hour)
        set(Calendar.MINUTE, tod.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
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
