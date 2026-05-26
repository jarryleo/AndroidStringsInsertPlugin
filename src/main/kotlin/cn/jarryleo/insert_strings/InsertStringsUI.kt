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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.awt.Color as AwtColor
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

class InsertStringsUI(
    private val toolWindow: ToolWindow
) : UiCallback {
    private lateinit var project: Project
    private lateinit var insertStringsManager: InsertStringsManager

    private var stringName by mutableStateOf("")
    private val rows = mutableStateListOf<StringRow>()

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
                    onCopy = { insertStringsManager.copy() },
                    onPaste = ::paste,
                    onInsert = ::insert,
                )
            }
        }
    }

    fun createToolWindowContent(project: Project) {
        this.project = project
        insertStringsManager = InsertStringsManager.getInstance(project)
        insertStringsManager.setUiCallBack(this)
    }

    fun getRootPanel(): JComponent = rootPanel

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

        insertStringsManager.paste(selectedEditor.file)
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
        val result = AITranslator.translate(targetLanguage, sourceText)
        updateRowText(rowIndex, result)
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
) {
    val colors = rememberIdeColors()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.panel,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
)

private fun uiColor(vararg keys: String, fallback: AwtColor): Color {
    val color = keys.firstNotNullOfOrNull(UIManager::getColor) ?: fallback
    return Color(color.red, color.green, color.blue, color.alpha)
}
