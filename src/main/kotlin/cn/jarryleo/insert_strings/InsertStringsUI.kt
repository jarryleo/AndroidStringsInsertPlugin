package cn.jarryleo.insert_strings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.awt.Color as AwtColor
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback {
    private lateinit var project: Project
    private lateinit var insertStringsManager: InsertStringsManager

    private var stringName by mutableStateOf("")
    private val rows = mutableStateListOf<StringRow>()
    private var showSettings by mutableStateOf(false)
    private var aiUrl by mutableStateOf("")
    private var aiApiKey by mutableStateOf("")
    private var aiProtocol by mutableStateOf(AiProtocol.OPENAI)
    private var aiModel by mutableStateOf("qwen-plus")
    private val modelOptions = mutableStateListOf<String>()
    private var modelFetchStatus by mutableStateOf("")
    private var toastMessage by mutableStateOf("")
    private var toastTimer: Timer? = null

    private val rootPanel = ComposePanel().apply {
        setContent {
            MaterialTheme {
                InsertStringsContent(
                    stringName = stringName,
                    rows = rows,
                    onStringNameChange = { stringName = it },
                    onTextChange = ::updateRowText,
                    onClear = { row -> updateRowText(row, "") },
                    onAi = ::translateRow,
                    onCopy = ::copy,
                    onPaste = ::paste,
                    onInsert = ::insert,
                    toastMessage = toastMessage,
                    showSettings = showSettings,
                    aiUrl = aiUrl,
                    aiApiKey = aiApiKey,
                    aiProtocol = aiProtocol,
                    aiModel = aiModel,
                    modelOptions = modelOptions,
                    modelFetchStatus = modelFetchStatus,
                    onOpenSettings = { showSettings = true },
                    onCloseSettings = { showSettings = false },
                    onAiUrlChange = { aiUrl = it },
                    onAiApiKeyChange = { aiApiKey = it },
                    onAiProtocolChange = { aiProtocol = it },
                    onAiModelChange = { aiModel = it },
                    onFetchModels = ::fetchModels,
                    onSaveSettings = ::saveSettings,
                )
            }
        }
    }

    fun createToolWindowContent(project: Project) {
        this.project = project
        insertStringsManager = InsertStringsManager.getInstance(project)
        loadSettings()
        insertStringsManager.setUiCallBack(this)
    }

    fun getRootPanel(): JComponent = rootPanel

    private fun copy() {
        insertStringsManager.copy()
        showToast("Copied")
    }

    private fun paste() {
        val selectedEditor = FileEditorManager.getInstance(project).selectedEditor
        if (selectedEditor == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        if (insertStringsManager.paste(selectedEditor.file)) {
            showToast("Pasted")
        } else {
            showToast("Nothing to paste")
        }
    }

    private fun insert() {
        val languages = insertStringsManager.languages
        if (languages == null) {
            Messages.showMessageDialog(
                "Please open a strings.xml first!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        if (stringName.isEmpty()) {
            Messages.showMessageDialog(
                "Name can't be empty!",
                "Error",
                Messages.getInformationIcon()
            )
            return
        }

        insertStringsManager.insert(
            project = project,
            stringName = stringName,
            stringsInfoList = rows.associate { it.language to it.text }
        )
        showToast("Inserted")
    }

    private fun updateRowText(rowIndex: Int, text: String) {
        if (rowIndex !in rows.indices) return
        rows[rowIndex] = rows[rowIndex].copy(text = text)
    }

    private fun translateRow(rowIndex: Int) {
        if (rowIndex !in rows.indices) return
        val sourceText = rows.firstOrNull { it.text.isNotEmpty() }?.text.orEmpty()
        val targetLanguage = rows[rowIndex].language.let {
            if (it.equals("values", ignoreCase = true)) "values-en" else it
        }
        updateRowText(rowIndex, "Translating...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.translate(targetLanguage, sourceText)
            SwingUtilities.invokeLater {
                updateRowText(rowIndex, result)
            }
        }
    }

    private fun loadSettings() {
        val settings = AiSettingsService.getInstance().state
        aiUrl = settings.url
        aiApiKey = settings.apiKey
        aiProtocol = AiProtocol.fromName(settings.protocol)
        aiModel = settings.model
    }

    private fun saveSettings() {
        AiSettingsService.getInstance().update(
            url = aiUrl,
            apiKey = aiApiKey,
            protocol = aiProtocol,
            model = aiModel,
        )
        modelFetchStatus = "Saved."
        showSettings = false
    }

    private fun fetchModels() {
        modelFetchStatus = "Loading models..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AITranslator.fetchModels(aiUrl, aiProtocol, aiApiKey)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { models ->
                        modelOptions.clear()
                        modelOptions.addAll(models)
                        if (aiModel.isBlank()) {
                            aiModel = models.firstOrNull().orEmpty()
                        }
                        modelFetchStatus = "Loaded ${models.size} models."
                    },
                    onFailure = {
                        modelFetchStatus = it.message ?: "Failed to load models."
                    }
                )
            }
        }
    }

    private fun showToast(message: String) {
        toastTimer?.stop()
        toastMessage = message
        toastTimer = Timer(1800) {
            toastMessage = ""
        }.apply {
            isRepeats = false
            start()
        }
    }

    override fun updateUI(
        @NotNull nodeName: String,
        @Nullable stringsList: List<StringsInfo>?
    ) {
        if (stringsList == null) return

        val newRows = stringsList
            .filter { it.language.isNotEmpty() }
            .map { StringRow(language = it.language, text = it.text) }

        SwingUtilities.invokeLater {
            stringName = nodeName
            rows.clear()
            rows.addAll(newRows)
            toolWindow.show()
        }
    }
}

private data class StringRow(
    val language: String,
    val text: String,
)

@Composable
private fun InsertStringsContent(
    stringName: String,
    rows: List<StringRow>,
    onStringNameChange: (String) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onInsert: () -> Unit,
    toastMessage: String,
    showSettings: Boolean,
    aiUrl: String,
    aiApiKey: String,
    aiProtocol: AiProtocol,
    aiModel: String,
    modelOptions: List<String>,
    modelFetchStatus: String,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onAiUrlChange: (String) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiProtocolChange: (AiProtocol) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveSettings: () -> Unit,
) {
    val colors = rememberIdeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.panel,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showSettings) {
                    AiSettingsContent(
                        aiUrl = aiUrl,
                        aiApiKey = aiApiKey,
                        aiProtocol = aiProtocol,
                        aiModel = aiModel,
                        modelOptions = modelOptions,
                        modelFetchStatus = modelFetchStatus,
                        onClose = onCloseSettings,
                        onAiUrlChange = onAiUrlChange,
                        onAiApiKeyChange = onAiApiKeyChange,
                        onAiProtocolChange = onAiProtocolChange,
                        onAiModelChange = onAiModelChange,
                        onFetchModels = onFetchModels,
                        onSave = onSaveSettings,
                        modifier = Modifier.fillMaxSize(),
                        colors = colors,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "<string name=",
                            color = colors.text,
                            style = compactTextStyle(colors.text),
                        )
                        CompactTextField(
                            value = stringName,
                            onValueChange = onStringNameChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = colors,
                        )
                        CompactButton(
                            text = "Settings",
                            onClick = onOpenSettings,
                            modifier = Modifier.width(72.dp),
                            colors = colors,
                        )
                    }

                    StringsTable(
                        rows = rows,
                        onTextChange = onTextChange,
                        onClear = onClear,
                        onAi = onAi,
                        modifier = Modifier.weight(1f),
                        colors = colors,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactButton(
                            text = "Copy",
                            onClick = onCopy,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Paste",
                            onClick = onPaste,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                        )
                        CompactButton(
                            text = "Insert",
                            onClick = onInsert,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                            primary = true,
                        )
                    }
                }
            }

            if (toastMessage.isNotEmpty()) {
                ToastMessage(
                    text = toastMessage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 42.dp),
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun AiSettingsContent(
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

@Composable
private fun SettingsLabel(text: String, colors: IdeColors) {
    Text(
        text = text,
        color = colors.secondaryText,
        style = compactTextStyle(colors.secondaryText),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ToastMessage(
    text: String,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Box(
        modifier = modifier
            .background(colors.toastBackground, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.toastText,
            style = compactTextStyle(colors.toastText),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProtocolDropdown(
    protocol: AiProtocol,
    onProtocolChange: (AiProtocol) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        DropdownFieldShell(
            text = protocol.displayName,
            expanded = expanded,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
        )
        StyledDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            colors = colors,
            modifier = modifier,
        ) {
            AiProtocol.entries.forEach { item ->
                DropdownOption(
                    text = item.displayName,
                    onClick = {
                        expanded = false
                        onProtocolChange(item)
                    },
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun ModelField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 26.dp)
                .background(colors.fieldBackground, RoundedCornerShape(3.dp))
                .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    singleLine = true,
                    maxLines = 1,
                    textStyle = compactTextStyle(colors.text),
                    cursorBrush = SolidColor(colors.accent),
                )
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { expanded = options.isNotEmpty() }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "▾",
                        color = colors.secondaryText,
                        style = compactTextStyle(colors.secondaryText),
                        maxLines = 1,
                    )
                }
            }
            StyledDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                maxHeight = 220,
            ) {
                options.forEach { model ->
                    DropdownOption(
                        text = model,
                        onClick = {
                            expanded = false
                            onValueChange(model)
                        },
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownFieldShell(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Row(
        modifier = modifier
            .height(26.dp)
            .background(colors.fieldBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = colors.text,
            style = compactTextStyle(colors.text),
            maxLines = 1,
        )
        Text(
            text = if (expanded) "▴" else "▾",
            color = colors.secondaryText,
            style = compactTextStyle(colors.secondaryText),
            maxLines = 1,
        )
    }
}

@Composable
private fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    colors: IdeColors,
    modifier: Modifier = Modifier,
    maxHeight: Int = 180,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            surface = colors.tableBackground,
            onSurface = colors.text,
            background = colors.tableBackground,
        )
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .heightIn(max = maxHeight.dp)
                .background(colors.tableBackground)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp)),
        ) {
            content()
        }
    }
}

@Composable
private fun DropdownOption(
    text: String,
    onClick: () -> Unit,
    colors: IdeColors,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .background(colors.tableBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = colors.text,
            style = compactTextStyle(colors.text),
            maxLines = 1,
        )
    }
}

@Composable
private fun StringsTable(
    rows: List<StringRow>,
    onTextChange: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAi: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
            .background(colors.tableBackground, RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.headerBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderText("language", modifier = Modifier.width(96.dp), colors = colors)
            HeaderText("text", modifier = Modifier.weight(1f), colors = colors)
            Spacer(Modifier.width(104.dp))
        }

        Divider(color = colors.border)

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No strings selected",
                    color = colors.secondaryText,
                    style = compactTextStyle(colors.secondaryText),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.language }) { index, row ->
                    StringTableRow(
                        row = row,
                        onTextChange = { onTextChange(index, it) },
                        onClear = { onClear(index) },
                        onAi = { onAi(index) },
                        colors = colors,
                    )
                    if (index != rows.lastIndex) {
                        Divider(color = colors.grid)
                    }
                }
            }
        }
    }
}

@Composable
private fun StringTableRow(
    row: StringRow,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onAi: () -> Unit,
    colors: IdeColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = row.language,
            modifier = Modifier.width(90.dp),
            color = colors.text,
            style = compactTextStyle(colors.text),
        )
        CompactTextField(
            value = row.text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            singleLine = false,
            maxLines = 2,
            colors = colors,
        )
        CompactButton(
            text = "Clear",
            onClick = onClear,
            modifier = Modifier.width(54.dp),
            colors = colors,
        )
        CompactButton(
            text = "AI",
            onClick = onAi,
            modifier = Modifier.width(44.dp),
            colors = colors,
        )
    }
}

@Composable
private fun HeaderText(
    text: String,
    modifier: Modifier = Modifier,
    colors: IdeColors,
) {
    Text(
        text = text,
        modifier = modifier,
        color = colors.secondaryText,
        style = compactTextStyle(colors.secondaryText),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean,
    maxLines: Int = 1,
    colors: IdeColors,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 26.dp)
            .background(colors.fieldBackground, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, colors.fieldBorder), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = compactTextStyle(colors.text),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = visualTransformation,
    )
}

@Composable
private fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IdeColors,
    primary: Boolean = false,
) {
    val background = if (primary) colors.accent else colors.buttonBackground
    val foreground = if (primary) colors.accentText else colors.text
    val border = if (primary) colors.accent else colors.buttonBorder
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(26.dp)
            .background(background, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            style = compactTextStyle(foreground),
            maxLines = 1,
        )
    }
}

@Composable
private fun compactTextStyle(color: Color): TextStyle {
    return MaterialTheme.typography.body2.merge(
        TextStyle(
            color = color,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    )
}

@Composable
private fun rememberIdeColors(): IdeColors {
    return remember {
        IdeColors(
            panel = uiColor("Panel.background", fallback = AwtColor(0xF2F2F2)),
            tableBackground = uiColor("Table.background", "Panel.background", fallback = AwtColor.WHITE),
            headerBackground = uiColor("TableHeader.background", "Panel.background", fallback = AwtColor(0xF2F2F2)),
            text = uiColor("Label.foreground", fallback = AwtColor(0x1F1F1F)),
            secondaryText = uiColor("Label.disabledForeground", "ContextHelp.foreground", fallback = AwtColor(0x6E6E6E)),
            fieldBackground = uiColor("TextField.background", "EditorPane.background", fallback = AwtColor.WHITE),
            fieldBorder = uiColor("TextField.borderColor", "Component.borderColor", fallback = AwtColor(0xBDBDBD)),
            buttonBackground = uiColor("Button.background", fallback = AwtColor(0xF5F5F5)),
            buttonBorder = uiColor("Button.borderColor", "Component.borderColor", fallback = AwtColor(0xBDBDBD)),
            border = uiColor("Component.borderColor", "Table.gridColor", fallback = AwtColor(0xC8C8C8)),
            grid = uiColor("Table.gridColor", "Component.borderColor", fallback = AwtColor(0xE0E0E0)),
            accent = uiColor("Component.focusColor", "Button.default.focusColor", "Actions.Blue", fallback = AwtColor(0x3574F0)),
            accentText = uiColor("Button.default.foreground", fallback = AwtColor.WHITE),
            toastBackground = uiColor("Notification.background", "GotItTooltip.background", fallback = AwtColor(0x323232)),
            toastText = uiColor("Notification.foreground", "GotItTooltip.foreground", fallback = AwtColor.WHITE),
        )
    }
}

private data class IdeColors(
    val panel: Color,
    val tableBackground: Color,
    val headerBackground: Color,
    val text: Color,
    val secondaryText: Color,
    val fieldBackground: Color,
    val fieldBorder: Color,
    val buttonBackground: Color,
    val buttonBorder: Color,
    val border: Color,
    val grid: Color,
    val accent: Color,
    val accentText: Color,
    val toastBackground: Color,
    val toastText: Color,
)

private fun uiColor(vararg keys: String, fallback: AwtColor): Color {
    val color = keys.firstNotNullOfOrNull(UIManager::getColor) ?: fallback
    return Color(color.red, color.green, color.blue, color.alpha)
}
