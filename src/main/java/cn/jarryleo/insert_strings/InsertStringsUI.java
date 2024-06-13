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
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
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
        String[] columnNames = {"language", "text"};
        int size = languages.size();
        Object[][] data = new Object[size][2];
        for (int i = 0; i < size; i++) {
            data[i][0] = languages.get(i);
            data[i][1] = texts.get(i);
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
}
