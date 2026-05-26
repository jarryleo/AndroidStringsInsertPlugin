package cn.jarryleo.insert_strings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import javax.swing.JComponent
import javax.swing.SwingUtilities

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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFFAFAFA),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "<string name=",
                    style = MaterialTheme.typography.body1,
                    color = Color(0xFF333333),
                )
                OutlinedTextField(
                    value = stringName,
                    onValueChange = onStringNameChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            StringsTable(
                rows = rows,
                onTextChange = onTextChange,
                onClear = onClear,
                onAi = onAi,
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Copy")
                }
                Button(
                    onClick = onPaste,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Paste")
                }
                Button(
                    onClick = onInsert,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2563EB))
                ) {
                    Text("Insert", color = Color.White)
                }
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, Color(0xFFD6D6D6)), RoundedCornerShape(6.dp))
            .background(Color.White, RoundedCornerShape(6.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF0F2F5))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderText("language", modifier = Modifier.width(112.dp))
            HeaderText("text", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(132.dp))
        }

        Divider(color = Color(0xFFD6D6D6))

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No strings selected",
                    color = Color(0xFF777777),
                    style = MaterialTheme.typography.body2,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.language }) { index, row ->
                    StringTableRow(
                        row = row,
                        onTextChange = { onTextChange(index, it) },
                        onClear = { onClear(index) },
                        onAi = { onAi(index) }
                    )
                    if (index != rows.lastIndex) {
                        Divider(color = Color(0xFFE6E6E6))
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = row.language,
            modifier = Modifier.width(104.dp),
            color = Color(0xFF444444),
            style = MaterialTheme.typography.body2,
        )
        OutlinedTextField(
            value = row.text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Button(
            onClick = onClear,
            modifier = Modifier.width(58.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Clear")
        }
        Button(
            onClick = onAi,
            modifier = Modifier.width(50.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("AI")
        }
    }
}

@Composable
private fun HeaderText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = Color(0xFF333333),
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.Bold,
    )
}
