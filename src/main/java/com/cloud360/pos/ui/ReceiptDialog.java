package com.cloud360.pos.ui;

import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleItem;
import com.cloud360.pos.model.ShopSettings;

import javax.swing.*;
import java.awt.*;
import java.awt.print.PrinterException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;

public class ReceiptDialog extends JDialog {

    private static final int W = 42;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    public ReceiptDialog(Window owner, Sale sale, ShopSettings shop) {
        super(owner, "Receipt " + sale.saleNumber(), Dialog.ModalityType.APPLICATION_MODAL);

        JTextArea area = new JTextArea(build(sale, shop));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        area.setCaretPosition(0);

        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(440, 560));

        JButton print = Ui.primary("Print");
        JButton close = Ui.secondary("Close");
        print.addActionListener(e -> {
            try {
                area.print();
            } catch (PrinterException ex) {
                Ui.error(this, "Could not print: " + ex.getMessage());
            }
        });
        close.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(close);
        buttons.add(print);

        setLayout(new BorderLayout());
        add(sp, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(print);
        pack();
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------ receipt text
    private static String build(Sale s, ShopSettings shop) {
        StringBuilder sb = new StringBuilder();
        String rule = "-".repeat(W) + "\n";

        sb.append(center(shop.name().toUpperCase())).append('\n');
        if (!shop.address().isBlank()) sb.append(center(shop.address())).append('\n');
        if (!shop.phone().isBlank()) sb.append(center("Tel: " + shop.phone())).append('\n');
        if (!shop.kraPin().isBlank()) sb.append(center("KRA PIN: " + shop.kraPin())).append('\n');
        sb.append(rule);

        sb.append("Receipt : ").append(s.saleNumber()).append('\n');
        sb.append("Date    : ").append(s.soldAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        if (s.servedByName() != null) sb.append("Served  : ").append(s.servedByName()).append('\n');
        if (!s.customerName().isBlank()) sb.append("Customer: ").append(s.customerName()).append('\n');
        sb.append(rule);

        for (SaleItem i : s.items()) {
            sb.append(i.productName()).append('\n');
            sb.append(line("  " + i.quantity() + " x " + MONEY.format(i.unitPrice()), MONEY.format(i.subtotal())))
                    .append('\n');
        }
        sb.append(rule);

        sb.append(line("Subtotal", MONEY.format(s.subtotal()))).append('\n');
        if (s.discount().signum() > 0) {
            sb.append(line("Discount", "-" + MONEY.format(s.discount()))).append('\n');
        }
        sb.append(line("TOTAL (" + shop.currency() + ")", MONEY.format(s.totalAmount()))).append('\n');
        sb.append(line("Paid (" + s.paymentMethod() + ")", MONEY.format(s.amountPaid()))).append('\n');

        BigDecimal bal = s.balance();
        if (bal.signum() > 0) {
            sb.append(line("BALANCE DUE", MONEY.format(bal))).append('\n');
        } else if (bal.signum() < 0) {
            sb.append(line("Change", MONEY.format(bal.negate()))).append('\n');
        }
        if (!s.referenceNumber().isBlank()) {
            sb.append("Ref: ").append(s.referenceNumber()).append('\n');
        }
        sb.append(rule);
        sb.append(center(shop.receiptFooter())).append('\n');
        return sb.toString();
    }

    private static String line(String left, String right) {
        int pad = Math.max(1, W - left.length() - right.length());
        return left + " ".repeat(pad) + right;
    }

    private static String center(String text) {
        int pad = Math.max(0, (W - text.length()) / 2);
        return " ".repeat(pad) + text;
    }
}