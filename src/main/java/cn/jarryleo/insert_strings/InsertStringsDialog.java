package cn.jarryleo.insert_strings;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class InsertStringsDialog extends JDialog {
    private JTable table;
    private JButton insertButton;
    private JButton cancelButton;
    private JPanel rootPanel;
    private JLabel stringsName;
    private JTextField stringNameText;

    public InsertStringsDialog(String name, String[] languages, OnStringsInsertListener listener) {
        setTitle("Insert Strings");
        setContentPane(rootPanel);
        setModal(true);
        getRootPane().setDefaultButton(insertButton);
        insertButton.addActionListener(e -> {
            String nameText = stringNameText.getText();
            if (nameText.isEmpty()) {
                JOptionPane.showMessageDialog(
                        this,
                        "Name can't be empty!",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < languages.length; i++) {
                String language = languages[i];
                String text = (String) table.getValueAt(i, 1);
                map.put(language, text);
            }
            listener.onInsert(nameText, map);
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initUI(name, languages);
    }

    private void initUI(String name, String[] languages) {
        stringsName.setText("<string name=");
        stringNameText.setText(name);
        String[] columnNames = {"language", "text"};
        Object[][] data = new Object[languages.length][2];
        for (int i = 0; i < languages.length; i++) {
            data[i][0] = languages[i];
            data[i][1] = "";
        }
        table.setModel(new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }
        });
        table.setPreferredScrollableViewportSize(new Dimension(500, 200));
        table.setFillsViewportHeight(true);
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

    public static void showDialog(String name, String[] languages, OnStringsInsertListener listener) {
        EventQueue.invokeLater(() -> {
            InsertStringsDialog dialog = new InsertStringsDialog(name, languages, listener);
            dialog.pack();
            dialog.setSize(500, 500);
            final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            final int x = (screenSize.width - dialog.getWidth()) / 2;
            final int y = (screenSize.height - dialog.getHeight()) / 2;
            dialog.setLocation(x, y);
            dialog.setVisible(true);
        });
    }
}
