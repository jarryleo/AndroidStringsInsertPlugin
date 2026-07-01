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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.jarryleo.android.buddy.ai.AiRole

/**
 * AI 角色预设管理面板。
 *
 * 整体布局:
 * 1. **顶部** —— 「角色」标题 + 当前条数 + 「+ Add」按钮(列表非空时显示);
 * 2. **空态** —— 列表为空时,中间居中显示一个大的「+ Add Role」入口,提示用户添加第一个角色;
 * 3. **列表** —— 每行展示:角色标题(启用时整行高亮)+ 三个动作按钮(`Edit` / `Delete` / `Enable` / `Disable`);
 *    启用中角色的行使用 accent 描边 + headerBackground 背景高亮;启用按钮在已启用的角色上显示为 `Disable`。
 * 4. **行内编辑**(点击 Edit 时展开):
 *    - 列表行**就地向下动画展开**([Modifier.animateContentSize]),高度按 prompt 文本框(~10 行)自适应;
 *    - 展开后包含 Title / Prompt 两个输入框 + `Save` / `Cancel` 按钮;
 *    - Save 校验 title 非空后写库;Cancel 直接丢弃草稿,行收起。
 * 5. **新增场景** —— 点 `+ Add` 时在列表底部插入一个临时的"未保存"编辑行,等同上面 4 的形态,Save 后落入列表。
 *
 * 行为约束:
 * - 启用是单选(由 [cn.jarryleo.android.buddy.ai.AiRolesService] 强制,UI 只需把启用 / 取消启用按用户意图转发即可)。
 * - 编辑中的行不允许再点 Edit / Enable / Disable(防止草稿和已持久化状态混在一起)。
 * - 启用状态变化会立即影响 AI 聊天时的 system 消息(由
 *   [cn.jarryleo.android.buddy.ai.AITranslator] 实时读取 active role)。
 *
 * 持久化:
 * - 全部角色经 [cn.jarryleo.android.buddy.ai.AiRolesService] 持久化,跨项目可用;
 * - 加载时机:[InsertStringsUI.createToolWindowContent] 启动时调一次
 *   [InsertStringsRolesController.loadRoles];之后任何修改都立即落库。
 */
@Composable
fun AiRolesContent(
    roles: List<AiRole>,
    editingRole: AiRole?,
    onAdd: () -> Unit,
    onEdit: (AiRole) -> Unit,
    onDelete: (AiRole) -> Unit,
    onSetEnabled: (AiRole, Boolean) -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftPromptChange: (String) -> Unit,
    onSaveEdit: (title: String, prompt: String) -> Boolean,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶部标题行:仅在列表非空时显示,避免和空态的「+ Add Role」按钮重复
        if (roles.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Role Presets",
                    modifier = Modifier.weight(1f),
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "共 ${roles.size} 条",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
                CompactButton(
                    text = "+ Add",
                    onClick = onAdd,
                    colors = colors,
                    primary = true,
                )
            }
            Text(
                text = "启用后,该角色的提示词会随每次 AI 聊天自动注入到 system 消息。" +
                    "同一时间最多启用一个角色,启用新角色会自动取消之前的启用。",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }

        // 列表 / 空态
        if (roles.isEmpty() && editingRole == null) {
            EmptyRoleState(
                onAdd = onAdd,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        } else {
            RoleList(
                roles = roles,
                editingRole = editingRole,
                onEdit = onEdit,
                onDelete = onDelete,
                onSetEnabled = onSetEnabled,
                onDraftTitleChange = onDraftTitleChange,
                onDraftPromptChange = onDraftPromptChange,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                modifier = Modifier.fillMaxSize(),
                colors = colors,
            )
        }
    }
}

/**
 * 空态视图:中间居中一个明显的大按钮,引导用户添加第一个角色。
 * 用 Box + 垂直 Column,让 + 按钮 / 文字垂直居中。
 */
@Composable
private fun EmptyRoleState(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .heightIn(min = 100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
                    text = "+ Add Role",
                    color = colors.accentText,
                    style = compactTextStyle(colors.accentText),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "添加一个 AI 角色预设,设置 prompt 后启用即可让 AI 以该角色身份回复",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }
    }
}

/**
 * 角色列表 + 行内编辑的可滚动容器。
 *
 * 渲染策略:
 * - 对每个已有 role,渲染一个列表行;
 *   当 `editingRole.id == role.id` 时,该行展开为编辑态(含 title / prompt 字段 + Save / Cancel);
 * - 末尾追加一个"新增未保存"的编辑行(仅当 editingRole.id 不在 roles 列表中时)。
 *
 * 动画:每个列表行外层用 [Modifier.animateContentSize] 包裹,Edit / Save / Cancel 切换时
 * 行高度平滑过渡,符合"条目向下动画展开"的诉求。
 */
@Composable
private fun RoleList(
    roles: List<AiRole>,
    editingRole: AiRole?,
    onEdit: (AiRole) -> Unit,
    onDelete: (AiRole) -> Unit,
    onSetEnabled: (AiRole, Boolean) -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftPromptChange: (String) -> Unit,
    onSaveEdit: (title: String, prompt: String) -> Boolean,
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
        roles.forEach { role ->
            val isEditingThis = editingRole?.id == role.id
            RoleListRow(
                role = role,
                draft = if (isEditingThis) editingRole else null,
                isEditing = isEditingThis,
                onStartEdit = { onEdit(role) },
                onDelete = { onDelete(role) },
                onToggleEnable = { onSetEnabled(role, !role.isEnabled) },
                onDraftTitleChange = onDraftTitleChange,
                onDraftPromptChange = onDraftPromptChange,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                colors = colors,
            )
        }
        // 新增场景:editingRole.id 不在 roles 中 → 在列表底部追加一个编辑行
        if (editingRole != null && roles.none { it.id == editingRole.id }) {
            RoleListRow(
                role = editingRole,
                draft = editingRole,
                isEditing = true,
                onStartEdit = {},
                onDelete = {},
                onToggleEnable = {},
                onDraftTitleChange = onDraftTitleChange,
                onDraftPromptChange = onDraftPromptChange,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                colors = colors,
            )
        }
    }
}

/**
 * 单条角色行(列表态 + 编辑态合一)。
 *
 * 列表态:Title(粗体) + Edit / Delete / Enable(Disable) 三个动作按钮;
 * 编辑态:在原行下方就地展开一个 [RoleEditForm],高度随 prompt 文本框(~10 行)自适应;
 *        三个动作按钮禁用(防止编辑中误操作)。
 *
 * 视觉:
 * - 启用中(enabled = true):accent 描边 + headerBackground 背景高亮,Title 粗体;
 * - 其它:fieldBorder 描边 + tableBackground 背景。
 *
 * 关键实现细节:
 * - 用 [Modifier.animateContentSize] 让列表态 ↔ 编辑态的切换平滑过渡;
 * - 标题用 role 的 title(已保存值);编辑态下,Title 文本框绑 draft.title(prompt 同理),
 *   这样编辑过程中可以实时看到用户的输入,Save 时由 controller 落库。
 */
@Composable
private fun RoleListRow(
    role: AiRole,
    draft: AiRole?,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnable: () -> Unit,
    onDraftTitleChange: (String) -> Unit,
    onDraftPromptChange: (String) -> Unit,
    onSaveEdit: (title: String, prompt: String) -> Boolean,
    onCancelEdit: () -> Unit,
    colors: IdeColors,
) {
    val borderColor = if (role.isEnabled) colors.accent else colors.fieldBorder
    val background = if (role.isEnabled) colors.headerBackground else colors.tableBackground
    val displayTitle = role.title.ifBlank { "(untitled)" }
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
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = displayTitle,
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = compactTextStyle(colors.text),
                fontWeight = if (role.isEnabled) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
            CompactButton(
                text = "Edit",
                onClick = onStartEdit,
                colors = colors,
                enabled = !isEditing,
            )
            CompactButton(
                text = "Delete",
                onClick = onDelete,
                colors = colors,
                tone = ButtonTone.NEGATIVE,
                enabled = !isEditing,
            )
            CompactButton(
                text = if (role.isEnabled) "Disable" else "Enable",
                onClick = onToggleEnable,
                colors = colors,
                enabled = !isEditing,
            )
        }
        // 编辑态:就地展开编辑表单
        if (isEditing && draft != null) {
            RoleEditForm(
                initialTitle = draft.title,
                initialPrompt = draft.prompt,
                onTitleChange = onDraftTitleChange,
                onPromptChange = onDraftPromptChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                colors = colors,
            )
        }
    }
}

/**
 * 行内编辑表单:Title / Prompt 两个输入框 + Save / Cancel 按钮。
 *
 * Prompt 文本框高度策略:
 * - 最小高度 = 10 行(lineHeight 16sp × 10 + 上下 padding 8dp ≈ 180dp);
 * - 超出 10 行时按需继续增长(由 [CompactTextField] 的 `maxLines` 控制上限,这里给到
 *   30 行 ≈ 480dp,够放下一般长度的角色 prompt);
 * - 这样空 prompt 时不会缩成 1 行,长度增长时也能继续展开,符合"至少 10 行"的预期。
 *
 * Save 按钮把当前输入交给 controller 校验;Cancel 直接丢弃。
 */
@Composable
private fun RoleEditForm(
    initialTitle: String,
    initialPrompt: String,
    onTitleChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onSave: (title: String, prompt: String) -> Boolean,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var error by remember(initialTitle, initialPrompt) { mutableStateOf("") }

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

        SettingsLabel("Prompt", colors)
        CompactTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                error = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                // 至少 10 行高:lineHeight 16sp × 10 + 上下 padding 8dp ≈ 168dp。
                // 空 prompt 时也保持这个高度,避免退化成 1 行的小输入框。
                .heightIn(min = PROMPT_MIN_HEIGHT),
            singleLine = false,
            // 上限 30 行 ≈ 480dp,够放下常规长度的角色 prompt;超出后字段内自滚。
            maxLines = 30,
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
                    val ok = onSave(title, prompt)
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
 * Prompt 输入框的最小高度(空态时也保持这个高度,符合"至少 10 行"的诉求)。
 * 计算方式:lineHeight 16.sp × 10 行 + 上下 padding 8dp ≈ 168.dp。
 * [Modifier.heightIn] 的 `min` 用此值保证空 prompt 也至少 10 行高,不会退化成 1 行的小输入框。
 */
private val PROMPT_MIN_HEIGHT: Dp = (10 * 16).dp + 8.dp
