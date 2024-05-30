package cn.jarryleo.insert_strings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
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

public class InsertStringsUI implements ToolWindowFactory, UiCallback {
    private JTable table;
    private JPanel rootPanel;
    private JLabel stringsName;
    private JTextField stringNameText;
    private JButton insertButton;
    private JButton copyButton;
    private JButton pasteButton;
    private Project project;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.getComponent().add(rootPanel);
        this.project = project;
        InsertStringsManager.INSTANCE.setUiCallBack(this);
    }

    public InsertStringsUI() {
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
    }

    private void insert() {
        List<String> languages = InsertStringsManager.INSTANCE.getLanguages();
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
        InsertStringsManager.INSTANCE.onInsert(project, nameText, map);
    }

    @Override
    public void updateUI(@NotNull String nodeName,
                         @Nullable List<String> languages,
                         @Nullable List<StringsInfo> stringsList) {
        if (languages == null || stringsList == null) return;
        List<String> strings = stringsList.stream().map(StringsInfo::getText).toList();
        initUI(nodeName, languages, strings);
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
