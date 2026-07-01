package cn.jarryleo.android.buddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.jarryleo.android.buddy.ai.AiEndpoint
import cn.jarryleo.android.buddy.ai.AiProvider
import cn.jarryleo.android.buddy.ai.AiProtocol

/**
 * AI 提供商设置面板(替换旧的「单 URL/Key/Protocol/Model」配置 UI)。
 *
 * **布局**:
 * 1. **顶部** —— 「当前使用」概览卡(显示当前 provider 名称 + 当前 model 名称),
 *    右侧「+ Add」按钮进入新建态。
 * 2. **中部** —— 全部 provider 列表。每一行展示:Provider 名称 + 模型名,
 *    右侧 3 个动作按钮 `Delete` / `Edit` / `Use`。
 *    - 当前 provider 整行高亮,`Use` 置灰;
 *    - 列表为空时显示空态提示。
 * 3. **底部** —— 编辑/新建表单([editingAiProvider] 非 null 时展开),含 Name / URL /
 *    API Key / Protocol / Model 字段 + `Get Models` 拉取按钮 + `Save` / `Cancel`。
 *    - Save 时 controller 校验,失败用 toast 提示,成功落库并同步 UI 列表;
 *    - Cancel 直接丢弃草稿。
 *
 * **状态来源**:
 * - [aiProviders] / [currentAiProviderId] / [editingAiProvider] / [editingIsNew] 全部由
 *   [InsertStringsUI] 持有,持久化由 [cn.jarryleo.android.buddy.ai.AiSettingsService] 负责。
 *   本 Composable 不直接写 service,只通过回调通知 controller。
 */
@Composable
fun AiProvidersContent(
    aiProviders: List<AiProvider>,
    currentAiProviderId: String?,
    editingAiProvider: AiProvider?,
    editingIsNew: Boolean,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onAddProvider: () -> Unit,
    onEditProvider: (AiProvider) -> Unit,
    onDeleteProvider: (AiProvider) -> Unit,
    onUseProvider: (AiProvider) -> Unit,
    onProviderNameChange: (String) -> Unit,
    onProviderUrlChange: (String) -> Unit,
    onProviderApiKeyChange: (String) -> Unit,
    onProviderProtocolChange: (AiProtocol) -> Unit,
    onProviderModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val current = aiProviders.firstOrNull { it.id == currentAiProviderId }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ============ 顶部:当前使用 + Add ============
        CurrentProviderHeader(
            current = current,
            onAdd = onAddProvider,
            colors = colors,
        )

        // ============ 中部:provider 列表 ============
        ProvidersList(
            providers = aiProviders,
            currentId = currentAiProviderId,
            onEdit = onEditProvider,
            onDelete = onDeleteProvider,
            onUse = onUseProvider,
            colors = colors,
        )

        // ============ 底部:编辑 / 新建表单 ============
        if (editingAiProvider != null) {
            ProviderEditForm(
                draft = editingAiProvider,
                isNew = editingIsNew,
                modelOptions = modelOptions,
                modelFetchStatus = modelFetchStatus,
                onNameChange = onProviderNameChange,
                onUrlChange = onProviderUrlChange,
                onApiKeyChange = onProviderApiKeyChange,
                onProtocolChange = onProviderProtocolChange,
                onModelChange = onProviderModelChange,
                onFetchModels = onFetchModels,
                onSave = onSaveEditing,
                onCancel = onCancelEditing,
                colors = colors,
            )
        }
    }
}

/**
 * 顶部「当前使用」概览卡。
 *
 * 显示当前 provider 名称(粗体)+ 当前模型名;无当前 provider 时显示「未配置」提示。
 * 右侧「+ Add」按钮固定显示,点击进入新建态。
 */
@Composable
private fun CurrentProviderHeader(
    current: AiProvider?,
    onAdd: () -> Unit,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Current Provider",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
            if (current == null) {
                Text(
                    text = "(none configured)",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    text = current.name.ifBlank { "(unnamed)" },
                    color = colors.text,
                    style = compactTextStyle(colors.text),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Model: ${current.model.ifBlank { "(not set)" }}",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        }
        CompactButton(
            text = "+ Add",
            onClick = onAdd,
            colors = colors,
            primary = true,
        )
    }
}

/**
 * 中部 provider 列表。
 *
 * 每行:名称 + 模型 + 3 个动作按钮(Delete / Edit / Use)。
 * 当前 provider 整行使用 accent 描边 + 浅色背景高亮,`Use` 置灰(已是当前)。
 * 列表为空时显示空态提示。
 */
@Composable
private fun ProvidersList(
    providers: List<AiProvider>,
    currentId: String?,
    onEdit: (AiProvider) -> Unit,
    onDelete: (AiProvider) -> Unit,
    onUse: (AiProvider) -> Unit,
    colors: IdeColors,
) {
    if (providers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(colors.tableBackground, RoundedCornerShape(3.dp))
                .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No AI providers yet. Click 「+ Add」 to create one.",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            providers.forEach { provider ->
                ProviderListRow(
                    provider = provider,
                    isCurrent = provider.id == currentId,
                    onEdit = { onEdit(provider) },
                    onDelete = { onDelete(provider) },
                    onUse = { onUse(provider) },
                    colors = colors,
                )
            }
        }
    }
}

/**
 * 列表中的单行 provider 展示。
 *
 * 当前 provider 整行用 accent 描边 + headerBackground 背景高亮;非当前用 fieldBorder + 浅色背景。
 * "Use" 按钮在当前 provider 上置灰(已为当前)。
 * 名称为空时回退为 "(unnamed)",模型为空时回退为 "(not set)",避免空文本。
 */
@Composable
private fun ProviderListRow(
    provider: AiProvider,
    isCurrent: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
    colors: IdeColors,
) {
    val borderColor = if (isCurrent) colors.accent else colors.fieldBorder
    val background = if (isCurrent) colors.headerBackground else colors.tableBackground
    val displayName = provider.name.ifBlank { "(unnamed)" }
    val displayModel = provider.model.ifBlank { "(not set)" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(3.dp))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = colors.text,
                style = compactTextStyle(colors.text),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "Model: $displayModel",
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
                maxLines = 1,
            )
        }
        CompactButton(
            text = "Delete",
            onClick = onDelete,
            colors = colors,
            tone = ButtonTone.NEGATIVE,
        )
        CompactButton(
            text = "Edit",
            onClick = onEdit,
            colors = colors,
        )
        CompactButton(
            text = "Use",
            onClick = onUse,
            colors = colors,
            enabled = !isCurrent,
        )
    }
}

/**
 * 底部编辑/新建表单。
 *
 * 同时用于「+ Add」新建和「Edit」编辑场景:用 [isNew] 区分标题和保存语义。
 * Save 按钮的回调由 controller 负责校验(URL/Key 必填、name 可空)和实际写库。
 * Cancel 按钮直接清空草稿,无副作用。
 *
 * Get Models 按钮沿用旧的 [ModelField] 组件,从当前 draft 的 url/apiKey/protocol 拉取模型列表。
 */
@Composable
private fun ProviderEditForm(
    draft: AiProvider,
    isNew: Boolean,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProtocolChange: (AiProtocol) -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    colors: IdeColors,
) {
    val protocol = AiProtocol.fromName(draft.protocol)
    val endpointPreview = AiEndpoint.completeChatEndpoint(draft.url, protocol)
    val titleText = if (isNew) "Add Provider" else "Edit Provider"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.accent), RoundedCornerShape(3.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = titleText,
            color = colors.text,
            style = compactTextStyle(colors.text),
            fontWeight = FontWeight.Bold,
        )

        SettingsLabel("Name", colors)
        CompactTextField(
            value = draft.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
        )

        SettingsLabel("URL", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactTextField(
                value = draft.url,
                onValueChange = onUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            ProtocolDropdown(
                protocol = protocol,
                onProtocolChange = onProtocolChange,
                modifier = Modifier.width(112.dp),
                colors = colors,
            )
        }
        Text(
            text = "Preview: $endpointPreview",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
        )

        SettingsLabel("API Key", colors)
        CompactTextField(
            value = draft.apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
            visualTransformation = PasswordVisualTransformation(),
        )

        SettingsLabel("Model", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModelField(
                value = draft.model,
                options = modelOptions,
                onValueChange = onModelChange,
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            CompactButton(
                text = "Get Models",
                onClick = onFetchModels,
                modifier = Modifier.width(88.dp),
                colors = colors,
            )
        }
        if (modelFetchStatus.isNotEmpty()) {
            Text(
                text = modelFetchStatus,
                color = colors.secondaryText,
                style = compactTextStyle(colors.secondaryText),
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
                onClick = onSave,
                colors = colors,
                primary = true,
            )
        }
    }
}
