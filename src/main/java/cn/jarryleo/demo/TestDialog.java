package cn.jarryleo.demo;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class TestDialog extends JDialog {
    private JTable table;
    private JButton insertButton;
    private JButton cancelButton;
    private JPanel rootPanel;
    private JLabel stringsName;
    private JTextField stringNameText;

    public TestDialog() {
        setTitle("Test Dialog");
        setContentPane(rootPanel);
        setModal(true);
        getRootPane().setDefaultButton(insertButton);
        insertButton.addActionListener(e -> dispose());
        cancelButton.addActionListener(e -> dispose());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initTable();
    }

    private void initTable() {
        stringsName.setText("<string name=");
        String[] columnNames = {"language", "text"};
        Object[][] data = {
                {"en", ""},
                {"ar", ""},
                {"th", ""},
                {"zh", ""},
                {"tw", ""},
                {"es", ""},
        };
        table.setModel(new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }
        });
        table.setPreferredScrollableViewportSize(new Dimension(500, 200));
        table.setFillsViewportHeight(true);
    }

    public static void showDialog() {
        EventQueue.invokeLater(() -> {
            TestDialog dialog = new TestDialog();
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
