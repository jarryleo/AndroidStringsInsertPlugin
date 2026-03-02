package cn.jarryleo.insert_strings;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InsertStringsUI implements UiCallback {
    private final ToolWindow toolWindow;
    private JTable table;
    private JPanel rootPanel;
    private JLabel stringsName;
    private JTextField stringNameText;
    private JButton insertButton;
    private JButton copyButton;
    private JButton pasteButton;
    private Project project;
    private InsertStringsManager insertStringsManager;

    public void createToolWindowContent(Project project) {
        this.project = project;
        insertStringsManager = InsertStringsManager.getInstance(project);
        insertStringsManager.setUiCallBack(this);
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    public InsertStringsUI(ToolWindow toolWindow) {
        this.toolWindow = toolWindow;
        insertButton.addActionListener(e -> {
            String nameText = stringNameText.getText();
            if (nameText.isEmpty()) {
                Messages.showMessageDialog(
                        "Name can't be empty!",
                        "Error",
                        Messages.getInformationIcon()
                );
            } else {
                insert();
            }
        });
        copyButton.addActionListener(e -> insertStringsManager.copy());
        pasteButton.addActionListener(e -> paste());
    }

    private void paste() {
        FileEditor selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor();
        if (selectedEditor == null) {
            Messages.showMessageDialog(
                    "Please open a strings.xml first!",
                    "Error",
                    Messages.getInformationIcon()
            );
        } else {
            VirtualFile file = selectedEditor.getFile();
            insertStringsManager.paste(file);
        }
    }

    private void insert() {
        List<String> languages = insertStringsManager.getLanguages();
        if (languages == null) {
            Messages.showMessageDialog(
                    "Please open a strings.xml first!",
                    "Error",
                    Messages.getInformationIcon()
            );
            return;
        }
        String nameText = stringNameText.getText();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < languages.size(); i++) {
            String language = languages.get(i);
            String text = (String) table.getValueAt(i, 1);
            map.put(language, text);
        }
        insertStringsManager.insert(project, nameText, map);
    }

    @Override
    public void updateUI(@NotNull String nodeName,
                         @Nullable List<StringsInfo> stringsList) {
        if (stringsList == null) return;
        stringsList.removeIf(stringsInfo -> stringsInfo.getLanguage().isEmpty());
        List<String> languages = stringsList.stream().map(StringsInfo::getLanguage).toList();
        List<String> strings = stringsList.stream().map(StringsInfo::getText).toList();
        initUI(nodeName, languages, strings);
        toolWindow.show();
    }

    private void initUI(String name, List<String> languages, List<String> texts) {
        stringsName.setText("<string name=");
        stringNameText.setText(name);
        String[] columnNames = {"language", "text", "Clear", "AI"};
        int size = languages.size();
        Object[][] data = new Object[size][4];
        for (int i = 0; i < size; i++) {
            data[i][0] = languages.get(i);
            data[i][1] = texts.get(i);
            data[i][2] = "Clear";
            data[i][3] = "AI";
        }
        table.setModel(new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }
        });
        table.setPreferredScrollableViewportSize(new Dimension(500, 200));
        table.setFillsViewportHeight(true);
        TableColumn column = table.getColumnModel().getColumn(0);
        column.setMaxWidth(200);
        column.setMinWidth(100);
        column.setPreferredWidth(150);
        setTabSingleCLickEdit();

        // 获取第三列并应用自定义渲染/编辑器
        TableColumn clearColumn = table.getColumnModel().getColumn(2);
        clearColumn.setCellRenderer(new ClearButtonRenderer());
        clearColumn.setCellEditor(new ClearButtonEditor());
        // 调整列宽
        clearColumn.setMaxWidth(80);
        clearColumn.setMinWidth(60);

        // 获取第四列并应用自定义渲染/编辑器
        TableColumn aiColumn = table.getColumnModel().getColumn(3);
        aiColumn.setCellRenderer(new AIButtonRenderer());
        aiColumn.setCellEditor(new AIButtonEditor());
        // 调整列宽
        aiColumn.setMaxWidth(80);
        aiColumn.setMinWidth(60);
    }

    private void setTabSingleCLickEdit() {
        TableColumnModel columnModel = table.getColumnModel();
        TableColumn column = columnModel.getColumn(1);
        DefaultCellEditor defaultEditor = (DefaultCellEditor) column.getCellEditor();
        if (defaultEditor == null) {
            defaultEditor = new DefaultCellEditor(new JTextField());
            column.setCellEditor(defaultEditor);
        }
        defaultEditor.setClickCountToStart(1);
    }

    // 按钮渲染器（显示按钮样式）
    private static class ClearButtonRenderer extends JButton implements TableCellRenderer {
        public ClearButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
        ) {
            setText("Clear");
            return this;
        }
    }

    // 按钮编辑器（处理点击事件）
    private class ClearButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button;
        private int currentRow;

        public ClearButtonEditor() {
            button = new JButton("Clear");
            button.addActionListener(e -> {
                // 清除第二列文本
                table.setValueAt("", currentRow, 1);
                fireEditingStopped(); // 结束编辑状态
            });
        }

        @Override
        public Object getCellEditorValue() {
            return "Clear";
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            currentRow = row; // 记录当前行
            return button;
        }
    }

    // 按钮渲染器（显示按钮样式）
    private static class AIButtonRenderer extends JButton implements TableCellRenderer {
        public AIButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
        ) {
            setText("AI");
            return this;
        }
    }

    // 按钮编辑器（处理点击事件）
    private class AIButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button;
        private int currentRow;

        public AIButtonEditor() {
            button = new JButton("AI");
            button.addActionListener(e -> {
                String sourceText = "";
                for (int i = 0; i < table.getRowCount(); i++) {
                    String text = (String) table.getValueAt(i, 1);
                    if (text != null && !text.isEmpty()) {
                        sourceText = text;
                        break;
                    }
                }
                String targetLanguage = (String) table.getValueAt(currentRow, 0);
                if (targetLanguage.equalsIgnoreCase("values")) {
                    targetLanguage = "values-en";
                }
                String result = AITranslator.translate(targetLanguage, sourceText);
                table.setValueAt(result, currentRow, 1);
                fireEditingStopped(); // 结束编辑状态
            });
        }

        @Override
        public Object getCellEditorValue() {
            return "AI";
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            currentRow = row; // 记录当前行
            return button;
        }
    }
}
