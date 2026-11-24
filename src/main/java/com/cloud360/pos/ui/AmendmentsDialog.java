package com.cloud360.pos.ui;

import com.cloud360.pos.dao.SaleDao;
import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleAmendment;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Shows every correction made to a sale, with what it looked like before and after. */
public class AmendmentsDialog extends JDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<SaleAmendment> list = new ArrayList<>();
    private final JTextArea beforeArea = textArea();
    private final JTextArea afterArea = textArea();

    public AmendmentsDialog(Window owner, Sale sale) {
        super(owner, "Amendment history: " + sale.saleNumber(), Dialog.ModalityType.APPLICATION_MODAL);

        try {
            list.addAll(SaleDao.amendments(sale.id()));
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }

        DefaultTableModel model = new DefaultTableModel(new String[]{"When", "By", "Reason"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        for (SaleAmendment a : list) {
            model.addRow(new Object[]{a.createdAt().format(WHEN),
                    a.amendedByName() == null ? "-" : a.amendedByName(), a.reason()});
        }
        JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                beforeArea.setText(format(list.get(row).beforeJson()));
                afterArea.setText(format(list.get(row).afterJson()));
                beforeArea.setCaretPosition(0);
                afterArea.setCaretPosition(0);
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(820, 150));

        JPanel compare = new JPanel(new GridLayout(1, 2, 10, 0));
        compare.add(titled("Before", beforeArea));
        compare.add(titled("After", afterArea));

        JButton close = Ui.secondary("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(close);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setBorder(BorderFactory.createEmptyBorder(14, 14, 0, 14));
        center.add(tableScroll, BorderLayout.NORTH);
        center.add(compare, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        if (list.isEmpty()) {
            beforeArea.setText("This sale has not been amended.");
        } else {
            table.setRowSelectionInterval(0, 0);
        }

        setSize(860, 580);
        setLocationRelativeTo(owner);
    }

    private static JTextArea textArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return a;
    }

    private static JComponent titled(String title, JTextArea area) {
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createTitledBorder(title));
        return sp;
    }

    private static String format(String json) {
        if (json == null || json.isBlank()) {
            return "-";
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            sb.append("Customer : ").append(str(o, "customer_name")).append('\n');
            sb.append("Phone    : ").append(str(o, "customer_phone")).append('\n');
            sb.append("Payment  : ").append(str(o, "payment_method")).append('\n');
            sb.append("Reference: ").append(str(o, "reference_number")).append("\n\n");
            sb.append("Items:\n");
            for (JsonElement el : o.getAsJsonArray("items")) {
                JsonObject it = el.getAsJsonObject();
                sb.append("  ").append(str(it, "quantity")).append(" x ").append(str(it, "product"))
                        .append(" @ ").append(str(it, "unit_price"))
                        .append(" = ").append(str(it, "subtotal")).append('\n');
            }
            sb.append('\n');
            sb.append("Subtotal : ").append(str(o, "subtotal")).append('\n');
            sb.append("Discount : ").append(str(o, "discount")).append('\n');
            sb.append("Total    : ").append(str(o, "total_amount")).append('\n');
            sb.append("Paid     : ").append(str(o, "amount_paid")).append('\n');
            return sb.toString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return "-";
        }
        String s = e.getAsString();
        return s.isBlank() ? "-" : s;
    }
}