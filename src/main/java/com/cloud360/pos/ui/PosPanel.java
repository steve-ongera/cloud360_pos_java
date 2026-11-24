package com.cloud360.pos.ui;

import com.cloud360.pos.dao.ProductDao;
import com.cloud360.pos.dao.SaleDao;
import com.cloud360.pos.dao.SettingsDao;
import com.cloud360.pos.model.CartLine;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleRequest;
import com.cloud360.pos.model.ShopSettings;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class PosPanel extends JPanel {

    private static final int MAX_CARDS = 60;
    private static final Color RED = new Color(0xB91C1C);
    private static final Color GREEN = new Color(0x15803D);
    private static final DecimalFormat PLAIN = new DecimalFormat("#,##0.00");

    private record SaleResult(Sale sale, ShopSettings shop) {}

    // product side
    private final JTextField search = new JTextField();
    private final JPanel grid = new JPanel(new GridLayout(0, 3, 10, 10));
    private final JLabel hint = new JLabel(" ");
    private final javax.swing.Timer searchTimer = new javax.swing.Timer(250, e -> reloadProducts());

    // cart side
    private final List<CartLine> cart = new ArrayList<>();
    private final DefaultTableModel cartModel = new DefaultTableModel(
            new String[]{"Item", "Qty", "Price", "Amount"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable cartTable = new JTable(cartModel);

    private final JTextField custNameF = new JTextField();
    private final JTextField custPhoneF = new JTextField();
    private final JTextField discountF = new JTextField("0");
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"CASH", "MPESA", "CARD"});
    private final JTextField refF = new JTextField();
    private final JTextField paidF = new JTextField();
    private final JLabel subtotalL = new JLabel("KES 0.00");
    private final JLabel totalL = new JLabel("KES 0.00");
    private final JLabel balanceL = new JLabel(" ");
    private final JButton payBtn = Ui.primary("Complete sale");

    private boolean paidEdited;
    private boolean updating;

    public PosPanel() {
        super(new BorderLayout(16, 0));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        searchTimer.setRepeats(false);

        add(buildProductSide(), BorderLayout.CENTER);
        add(buildCartSide(), BorderLayout.EAST);

        // Refresh the grid every time this screen is shown (stock may have changed elsewhere).
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                reloadProducts();
            }
        });
        recalc();
    }

    // ------------------------------------------------------------ product side
    private JComponent buildProductSide() {
        JLabel title = new JLabel("New Sale");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));

        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Search name, or scan / type a barcode and press Enter");
        search.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 6,10,6,10");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchTimer.restart(); }
            public void removeUpdate(DocumentEvent e) { searchTimer.restart(); }
            public void changedUpdate(DocumentEvent e) { searchTimer.restart(); }
        });
        search.addActionListener(e -> onSearchEnter());

        hint.setForeground(Color.GRAY);

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.add(title, BorderLayout.NORTH);
        top.add(search, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        ScrollablePanel holder = new ScrollablePanel(new BorderLayout());
        holder.add(grid, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(24);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void reloadProducts() {
        String q = search.getText();
        new SwingWorker<List<Product>, Void>() {
            @Override
            protected List<Product> doInBackground() throws Exception {
                return ProductDao.search(q);
            }

            @Override
            protected void done() {
                try {
                    List<Product> visible = get().stream().filter(Product::isVisible).toList();
                    renderGrid(visible);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    hint.setText("Could not load products. Check the database connection.");
                }
            }
        }.execute();
    }

    private void renderGrid(List<Product> visible) {
        grid.removeAll();
        int shown = Math.min(visible.size(), MAX_CARDS);
        for (int i = 0; i < shown; i++) {
            grid.add(card(visible.get(i)));
        }
        // keep the 3 columns even with 1-2 results
        for (int i = shown; i % 3 != 0 && shown > 0; i++) {
            grid.add(new JLabel());
            shown++;
        }
        hint.setText(visible.size() > MAX_CARDS
                ? "Showing the first " + MAX_CARDS + " of " + visible.size() + " products. Type to narrow down."
                : visible.size() + " products");
        grid.revalidate();
        grid.repaint();
    }

    private JButton card(Product p) {
        String stock = p.getStock() > 0 ? "Stock: " + p.getStock() : "Out of stock";
        JButton b = new JButton("<html><div style='width:115px'><b>" + esc(p.getName()) + "</b><br>"
                + "KES " + PLAIN.format(p.getUnitPrice()) + "<br><small>" + stock + "</small></div></html>");
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setPreferredSize(new Dimension(140, 88));
        b.putClientProperty(FlatClientProperties.STYLE, "arc: 14; margin: 8,10,8,10");
        b.setEnabled(p.getStock() > 0);
        b.addActionListener(e -> addToCart(p));
        return b;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Enter in the search box: add the exact barcode match, or the only result. */
    private void onSearchEnter() {
        String text = search.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            List<Product> found = ProductDao.search(text).stream().filter(Product::isVisible).toList();
            Product match = found.stream().filter(p -> text.equalsIgnoreCase(p.getBarcode())).findFirst()
                    .orElse(found.size() == 1 ? found.get(0) : null);
            if (match != null) {
                addToCart(match);
                search.setText("");
            }
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    // --------------------------------------------------------------- cart side
    private JComponent buildCartSide() {
        JLabel title = new JLabel("Current sale");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        cartTable.setRowHeight(30);
        cartTable.setShowVerticalLines(false);
        cartTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cartTable.getTableHeader().setReorderingAllowed(false);
        cartTable.getTableHeader().putClientProperty(FlatClientProperties.STYLE, "font: bold");
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int col : new int[]{1, 2, 3}) {
            cartTable.getColumnModel().getColumn(col).setCellRenderer(right);
        }
        cartTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        cartTable.getColumnModel().getColumn(1).setPreferredWidth(40);
        cartTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        cartTable.getColumnModel().getColumn(3).setPreferredWidth(90);

        JScrollPane cartScroll = new JScrollPane(cartTable);
        cartScroll.setPreferredSize(new Dimension(420, 200));

        JButton minus = Ui.secondary("-");
        JButton plus = Ui.secondary("+");
        JButton amount = Ui.secondary("Line amount");
        JButton remove = Ui.secondary("Remove");
        JButton clear = Ui.secondary("Clear");
        minus.addActionListener(e -> changeQty(-1));
        plus.addActionListener(e -> changeQty(1));
        amount.addActionListener(e -> setLineAmount());
        remove.addActionListener(e -> removeSelected());
        clear.addActionListener(e -> clearCart());

        JPanel cartButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cartButtons.add(minus);
        cartButtons.add(plus);
        cartButtons.add(amount);
        cartButtons.add(remove);
        cartButtons.add(clear);

        // checkout form
        custNameF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "optional");
        custPhoneF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "optional");
        refF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "M-Pesa code / card auth (optional)");

        DocumentListener discountListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { recalc(); }
            public void removeUpdate(DocumentEvent e) { recalc(); }
            public void changedUpdate(DocumentEvent e) { recalc(); }
        };
        discountF.getDocument().addDocumentListener(discountListener);

        paidF.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onPaidChanged(); }
            public void removeUpdate(DocumentEvent e) { onPaidChanged(); }
            public void changedUpdate(DocumentEvent e) { onPaidChanged(); }
        });

        JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        Ui.addRow(form, y++, "Customer", custNameF);
        Ui.addRow(form, y++, "Phone", custPhoneF);
        Ui.addRow(form, y++, "Discount (KES)", discountF);
        Ui.addRow(form, y++, "Payment", methodBox);
        Ui.addRow(form, y++, "Reference", refF);
        Ui.addRow(form, y++, "Amount paid", paidF);

        totalL.setFont(totalL.getFont().deriveFont(Font.BOLD, 22f));
        JPanel totals = new JPanel(new GridLayout(0, 2, 0, 4));
        totals.add(new JLabel("Subtotal"));
        subtotalL.setHorizontalAlignment(SwingConstants.RIGHT);
        totals.add(subtotalL);
        totals.add(new JLabel("Total"));
        totalL.setHorizontalAlignment(SwingConstants.RIGHT);
        totals.add(totalL);
        balanceL.setHorizontalAlignment(SwingConstants.RIGHT);
        totals.add(new JLabel(" "));
        totals.add(balanceL);

        payBtn.setFont(payBtn.getFont().deriveFont(Font.BOLD, 15f));
        payBtn.setEnabled(false);
        payBtn.addActionListener(e -> completeSale());

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.add(form);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(totals);
        bottom.add(Box.createVerticalStrut(10));
        payBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        payBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        bottom.add(payBtn);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(cartScroll, BorderLayout.CENTER);
        middle.add(cartButtons, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setPreferredSize(new Dimension(440, 0));
        p.add(title, BorderLayout.NORTH);
        p.add(middle, BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------ cart actions
    private void addToCart(Product p) {
        for (CartLine l : cart) {
            if (l.getProduct().getId() == p.getId()) {
                if (l.getQuantity() + 1 > p.getStock()) {
                    Ui.info(this, "Only " + p.getStock() + " of " + p.getName() + " in stock.");
                    return;
                }
                l.setQuantity(l.getQuantity() + 1);
                refreshCart();
                return;
            }
        }
        if (p.getStock() < 1) {
            Ui.info(this, p.getName() + " is out of stock.");
            return;
        }
        cart.add(new CartLine(p, 1));
        refreshCart();
        cartTable.setRowSelectionInterval(cart.size() - 1, cart.size() - 1);
    }

    private void changeQty(int delta) {
        int row = cartTable.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a line in the cart first.");
            return;
        }
        CartLine l = cart.get(row);
        int q = l.getQuantity() + delta;
        if (q <= 0) {
            cart.remove(row);
        } else {
            if (q > l.getProduct().getStock()) {
                Ui.info(this, "Only " + l.getProduct().getStock() + " of "
                        + l.getProduct().getName() + " in stock.");
                return;
            }
            l.setQuantity(q);
        }
        refreshCart();
    }

    private void setLineAmount() {
        int row = cartTable.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a line in the cart first.");
            return;
        }
        CartLine l = cart.get(row);
        String input = JOptionPane.showInputDialog(this,
                "Total for " + l.getQuantity() + " x " + l.getProduct().getName()
                        + " (leave empty to use the normal price):",
                l.getLineTotal().setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (input == null) {
            return;
        }
        if (input.isBlank()) {
            l.setLineAmount(null);
        } else {
            BigDecimal v = parseAmount(input);
            if (v == null) {
                Ui.error(this, "Enter a valid amount, e.g. 450 or 99.50.");
                return;
            }
            l.setLineAmount(v);
        }
        refreshCart();
    }

    private void removeSelected() {
        int row = cartTable.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a line in the cart first.");
            return;
        }
        cart.remove(row);
        refreshCart();
    }

    private void clearCart() {
        cart.clear();
        custNameF.setText("");
        custPhoneF.setText("");
        refF.setText("");
        discountF.setText("0");
        methodBox.setSelectedIndex(0);
        paidEdited = false;
        refreshCart();
    }

    private void refreshCart() {
        int sel = cartTable.getSelectedRow();
        cartModel.setRowCount(0);
        for (CartLine l : cart) {
            cartModel.addRow(new Object[]{
                    l.getProduct().getName(), l.getQuantity(),
                    PLAIN.format(l.getUnitPrice()), PLAIN.format(l.getLineTotal())});
        }
        if (sel >= 0 && sel < cart.size()) {
            cartTable.setRowSelectionInterval(sel, sel);
        }
        recalc();
    }

    // ----------------------------------------------------------------- totals
    private BigDecimal subtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CartLine l : cart) {
            sum = sum.add(l.getLineTotal());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal currentTotal() {
        BigDecimal disc = parseAmount(discountF.getText());
        if (disc == null) disc = BigDecimal.ZERO;
        return subtotal().subtract(disc).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private void recalc() {
        BigDecimal total = currentTotal();
        subtotalL.setText("KES " + PLAIN.format(subtotal()));
        totalL.setText("KES " + PLAIN.format(total));
        if (!paidEdited) {
            updating = true;
            paidF.setText(total.toPlainString());
            updating = false;
        }
        refreshBalance();
        payBtn.setText("Complete sale   KES " + PLAIN.format(total));
        payBtn.setEnabled(!cart.isEmpty());
    }

    private void onPaidChanged() {
        if (!updating) {
            paidEdited = true;
            refreshBalance();
        }
    }

    private void refreshBalance() {
        BigDecimal total = currentTotal();
        String text = paidF.getText().trim();
        BigDecimal paid = text.isEmpty() ? total : parseAmount(text);
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

    /** Parses a non-negative money amount; null when blank, invalid or negative. */
    private static BigDecimal parseAmount(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return v.signum() < 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ complete sale
    private void completeSale() {
        if (cart.isEmpty()) {
            return;
        }
        BigDecimal sub = subtotal();

        String dText = discountF.getText().trim();
        BigDecimal discount = dText.isEmpty() ? BigDecimal.ZERO : parseAmount(dText);
        if (discount == null) {
            Ui.error(this, "Enter a valid discount (0 or more).");
            return;
        }
        if (discount.compareTo(sub) > 0) {
            Ui.error(this, "The discount cannot be more than the subtotal.");
            return;
        }
        BigDecimal total = sub.subtract(discount);

        String pText = paidF.getText().trim();
        BigDecimal paid = pText.isEmpty() ? null : parseAmount(pText);
        if (!pText.isEmpty() && paid == null) {
            Ui.error(this, "Enter a valid amount paid.");
            return;
        }
        BigDecimal effectivePaid = paid == null ? total : paid;
        if (effectivePaid.compareTo(total) < 0) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "The customer will still owe KES " + PLAIN.format(total.subtract(effectivePaid))
                            + ".\nComplete the sale anyway?",
                    "Unpaid balance", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }

        SaleRequest req = new SaleRequest(
                custNameF.getText().trim(), custPhoneF.getText().trim(), discount,
                String.valueOf(methodBox.getSelectedItem()), refF.getText().trim(), paid,
                new ArrayList<>(cart));

        payBtn.setEnabled(false);
        new SwingWorker<SaleResult, Void>() {
            @Override
            protected SaleResult doInBackground() throws Exception {
                Sale sale = SaleDao.create(req, Session.getUser().getId());
                return new SaleResult(sale, SettingsDao.load());
            }

            @Override
            protected void done() {
                try {
                    SaleResult res = get();
                    clearCart();
                    reloadProducts();
                    new ReceiptDialog(SwingUtilities.getWindowAncestor(PosPanel.this),
                            res.sale(), res.shop()).setVisible(true);
                } catch (Exception ex) {
                    payBtn.setEnabled(!cart.isEmpty());
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Ui.error(PosPanel.this, cause.getMessage());
                    reloadProducts(); // stock may have changed
                }
            }
        }.execute();
    }

    // ------------------------------------------------- scroll panel (tracks width)
    private static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) { super(lm); }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}