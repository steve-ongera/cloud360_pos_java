package com.cloud360.pos.ui;

import com.cloud360.pos.dao.ProductDao;
import com.cloud360.pos.dao.SaleDao;
import com.cloud360.pos.model.CartLine;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleItem;
import com.cloud360.pos.model.SaleRequest;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AmendDialog extends JDialog {

    private static final DecimalFormat PLAIN = new DecimalFormat("#,##0.00");
    private static final Color RED = new Color(0xB91C1C);
    private static final Color GREEN = new Color(0x15803D);

    private final Sale sale;
    private boolean saved;
    private boolean refreshing;

    private final List<CartLine> lines = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Item", "Qty", "Price", "Amount"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) { return c == 1 || c == 3; }
    };
    private final JTable table = new JTable(model);

    private final JTextField custNameF = new JTextField();
    private final JTextField custPhoneF = new JTextField();
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"CASH", "MPESA", "CARD"});
    private final JTextField refF = new JTextField();
    private final JTextField discountF = new JTextField();
    private final JTextField paidF = new JTextField();
    private final JTextField reasonF = new JTextField();
    private final JLabel totalL = new JLabel();
    private final JLabel balanceL = new JLabel(" ");

    public AmendDialog(Window owner, Sale sale) {
        super(owner, "Amend " + sale.saleNumber(), Dialog.ModalityType.APPLICATION_MODAL);
        this.sale = sale;

        buildLines();

        custNameF.setText(sale.customerName());
        custPhoneF.setText(sale.customerPhone());
        methodBox.setSelectedItem(sale.paymentMethod());
        refF.setText(sale.referenceNumber());
        discountF.setText(sale.discount().toPlainString());
        paidF.setText(sale.amountPaid().toPlainString());
        reasonF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Why is this sale being corrected? (required)");

        onChange(discountF, this::recalc);
        onChange(paidF, this::recalc);

        // ---- form
        JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        Ui.addRow(form, y++, "Customer", custNameF);
        Ui.addRow(form, y++, "Phone", custPhoneF);
        Ui.addRow(form, y++, "Payment", methodBox);
        Ui.addRow(form, y++, "Reference", refF);
        Ui.addRow(form, y++, "Discount (KES)", discountF);
        Ui.addRow(form, y++, "Amount paid", paidF);
        Ui.addRow(form, y++, "Reason *", reasonF);

        // ---- lines table
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int col : new int[]{1, 2, 3}) {
            table.getColumnModel().getColumn(col).setCellRenderer(right);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        model.addTableModelListener(e -> {
            if (refreshing || e.getType() != TableModelEvent.UPDATE) return;
            onCellEdited(e.getFirstRow(), e.getColumn());
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(640, 170));

        JButton add = Ui.secondary("+ Add product");
        JButton remove = Ui.secondary("Remove line");
        add.addActionListener(e -> addProduct());
        remove.addActionListener(e -> removeLine());
        JPanel lineButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lineButtons.add(add);
        lineButtons.add(remove);
        lineButtons.add(new JLabel("   Edit Qty or Amount directly in the table."));

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(tableScroll, BorderLayout.CENTER);
        middle.add(lineButtons, BorderLayout.SOUTH);

        // ---- bottom
        totalL.setFont(totalL.getFont().deriveFont(Font.BOLD, 18f));
        JPanel totals = new JPanel(new GridLayout(0, 1));
        totals.add(totalL);
        totals.add(balanceL);

        JButton save = Ui.primary("Save amendment");
        JButton cancel = Ui.secondary("Cancel");
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(save);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(totals, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        root.add(form, BorderLayout.NORTH);
        root.add(middle, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(save);
        refreshTable();
        setSize(700, 690);
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() { return saved; }

    // ------------------------------------------------------------------ lines
    /** Groups the sale's rows (one row per batch) back into one cart line per product. */
    private void buildLines() {
        Map<Long, Product> products = new LinkedHashMap<>();
        Map<Long, Integer> qty = new LinkedHashMap<>();
        Map<Long, BigDecimal> amount = new LinkedHashMap<>();

        for (SaleItem i : sale.items()) {
            products.computeIfAbsent(i.productId(), id -> {
                Product p = new Product();
                p.setId(id);
                p.setName(i.productName());
                p.setUnit(i.unit());
                p.setUnitPrice(i.unitPrice());
                return p;
            });
            qty.merge(i.productId(), i.quantity(), Integer::sum);
            amount.merge(i.productId(), i.subtotal(), BigDecimal::add);
        }
        for (Map.Entry<Long, Product> e : products.entrySet()) {
            CartLine l = new CartLine(e.getValue(), qty.get(e.getKey()));
            l.setLineAmount(amount.get(e.getKey())); // keep the price the customer originally paid
            lines.add(l);
        }
    }

    private void onCellEdited(int row, int col) {
        if (row < 0 || row >= lines.size()) return;
        CartLine l = lines.get(row);
        String v = String.valueOf(model.getValueAt(row, col)).trim().replace(",", "");
        try {
            if (col == 1) {
                int q = Integer.parseInt(v);
                if (q > 0) l.setQuantity(q); // this re-prices the line at the catalogue price
            } else if (col == 3) {
                BigDecimal a = new BigDecimal(v);
                if (a.signum() >= 0) l.setLineAmount(a.setScale(2, RoundingMode.HALF_UP));
            }
        } catch (NumberFormatException ignored) {
            // invalid text: the table is redrawn with the previous value
        }
        SwingUtilities.invokeLater(this::refreshTable);
    }

    private void refreshTable() {
        refreshing = true;
        model.setRowCount(0);
        for (CartLine l : lines) {
            model.addRow(new Object[]{l.getProduct().getName(), l.getQuantity(),
                    PLAIN.format(l.getUnitPrice()), PLAIN.format(l.getLineTotal())});
        }
        refreshing = false;
        recalc();
    }

    private void addProduct() {
        Product p = pickProduct();
        if (p == null) return;
        for (CartLine l : lines) {
            if (l.getProduct().getId() == p.getId()) {
                l.setQuantity(l.getQuantity() + 1);
                refreshTable();
                return;
            }
        }
        lines.add(new CartLine(p, 1));
        refreshTable();
    }

    private void removeLine() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a line first.");
            return;
        }
        lines.remove(row);
        refreshTable();
    }

    private Product pickProduct() {
        JTextField q = new JTextField();
        q.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search name or barcode...");
        DefaultListModel<Product> lm = new DefaultListModel<>();
        JList<Product> list = new JList<>(lm);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                Product p = (Product) v;
                return super.getListCellRendererComponent(l,
                        p.getName() + "   -   KES " + PLAIN.format(p.getUnitPrice()) + "   (stock " + p.getStock() + ")",
                        i, sel, f);
            }
        });

        Runnable load = () -> {
            try {
                lm.clear();
                for (Product p : ProductDao.search(q.getText())) {
                    if (p.isVisible()) lm.addElement(p);
                }
            } catch (SQLException ex) {
                Ui.error(this, ex.getMessage());
            }
        };
        onChange(q, load);
        load.run();

        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(420, 260));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(q, BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);

        int ok = JOptionPane.showConfirmDialog(this, panel, "Add product",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return ok == JOptionPane.OK_OPTION ? list.getSelectedValue() : null;
    }

    // ----------------------------------------------------------------- totals
    private BigDecimal subtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CartLine l : lines) sum = sum.add(l.getLineTotal());
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal currentTotal() {
        BigDecimal disc = parse(discountF.getText());
        if (disc == null) disc = BigDecimal.ZERO;
        return subtotal().subtract(disc).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private void recalc() {
        BigDecimal total = currentTotal();
        totalL.setText("Total: KES " + PLAIN.format(total));

        BigDecimal paid = paidF.getText().isBlank() ? total : parse(paidF.getText());
        if (paid == null) {
            balanceL.setText(" ");
            return;
        }
        BigDecimal diff = total.subtract(paid);
        if (diff.signum() > 0) {
            balanceL.setForeground(RED);
            balanceL.setText("Balance owed: KES " + PLAIN.format(diff));
        } else if (diff.signum() < 0) {
            balanceL.setForeground(GREEN);
            balanceL.setText("Change: KES " + PLAIN.format(diff.negate()));
        } else {
            balanceL.setForeground(GREEN);
            balanceL.setText("Paid in full");
        }
    }

    // ------------------------------------------------------------------- save
    private void save() {
        if (lines.isEmpty()) {
            Ui.error(this, "A sale needs at least one item.");
            return;
        }
        String reason = reasonF.getText().trim();
        if (reason.isEmpty()) {
            Ui.error(this, "Please give a reason for the amendment.");
            return;
        }
        BigDecimal discount = discountF.getText().isBlank() ? BigDecimal.ZERO : parse(discountF.getText());
        if (discount == null) {
            Ui.error(this, "Enter a valid discount (0 or more).");
            return;
        }
        if (discount.compareTo(subtotal()) > 0) {
            Ui.error(this, "The discount cannot be more than the subtotal.");
            return;
        }
        BigDecimal paid = paidF.getText().isBlank() ? null : parse(paidF.getText());
        if (!paidF.getText().isBlank() && paid == null) {
            Ui.error(this, "Enter a valid amount paid.");
            return;
        }

        SaleRequest req = new SaleRequest(custNameF.getText().trim(), custPhoneF.getText().trim(), discount,
                String.valueOf(methodBox.getSelectedItem()), refF.getText().trim(), paid, new ArrayList<>(lines));
        try {
            SaleDao.amend(sale.id(), req, reason, Session.getUser());
            saved = true;
            dispose();
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers
    private static BigDecimal parse(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) return null;
        try {
            BigDecimal v = new BigDecimal(t);
            return v.signum() < 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void onChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }
}