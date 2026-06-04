package cn.jarryleo.insert_strings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.jarryleo.insert_strings.ai.AiEndpoint
import cn.jarryleo.insert_strings.ai.AiProtocol

@Composable
fun AiSettingsContent(
    aiUrl: String,
    aiApiKey: String,
    aiProtocol: AiProtocol,
    aiModel: String,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onClose: () -> Unit,
    onAiUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiProtocolChange: (AiProtocol) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    val endpointPreview = AiEndpoint.completeChatEndpoint(aiUrl, aiProtocol)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "AI Settings",
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

        SettingsLabel("URL", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactTextField(
                value = aiUrl,
                onValueChange = onAiUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = colors,
            )
            ProtocolDropdown(
                protocol = aiProtocol,
                onProtocolChange = onAiProtocolChange,
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
            value = aiApiKey,
            onValueChange = onAiApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = colors,
            visualTransformation = PasswordVisualTransformation(),
        )

        SettingsLabel("Model", colors)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModelField(
                value = aiModel,
                options = modelOptions,
                onValueChange = onAiModelChange,
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

        Spacer(Modifier.weight(1f))

        CompactButton(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            primary = true,
        )
    }
}
