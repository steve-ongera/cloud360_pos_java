package com.cloud360.pos.ui;

import com.cloud360.pos.dao.SaleDao;
import com.cloud360.pos.dao.SettingsDao;
import com.cloud360.pos.dao.UserDao;
import com.cloud360.pos.model.Role;
import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleFilter;
import com.cloud360.pos.model.User;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class SalesPanel extends JPanel {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Color RED = new Color(0xB91C1C);
    private static final String[] COLUMNS = {
            "Sale #", "Date", "Customer", "Method", "Total", "Paid", "Balance", "Served by"};

    private final JTextField fromF = new JTextField(9);
    private final JTextField toF = new JTextField(9);
    private final JTextField searchF = new JTextField(14);
    private final JComboBox<String> methodBox =
            new JComboBox<>(new String[]{"All methods", "CASH", "MPESA", "CARD"});
    private final JComboBox<Object> cashierBox = new JComboBox<>();
    private final JCheckBox unpaidCb = new JCheckBox("Unpaid only");

    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");
    private List<Sale> sales = new ArrayList<>();

    public SalesPanel() {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        LocalDate today = LocalDate.now();
        fromF.setText(today.minusDays(29).toString());
        toF.setText(today.toString());
        fromF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-MM-dd");
        toF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-MM-dd");
        searchF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Sale #, customer, phone, ref");

        add(buildTop(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        // Reload every time this screen is shown, so new sales appear without extra clicks.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                load();
            }
        });
    }

    // ------------------------------------------------------------------ UI
    private JComponent buildTop() {
        JLabel title = new JLabel("Sales History");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));

        cashierBox.addItem("All cashiers");
        try {
            for (User u : UserDao.listStaff()) {
                cashierBox.addItem(u);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        boolean isCashier = Session.getUser().getRole() == Role.CASHIER;
        cashierBox.setVisible(!isCashier); // cashiers only ever see their own sales

        JButton apply = Ui.primary("Search");
        apply.addActionListener(e -> load());
        fromF.addActionListener(e -> load());
        toF.addActionListener(e -> load());
        searchF.addActionListener(e -> load());
        methodBox.addActionListener(e -> load());
        cashierBox.addActionListener(e -> load());
        unpaidCb.addActionListener(e -> load());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.add(new JLabel("From"));
        filters.add(fromF);
        filters.add(new JLabel("To"));
        filters.add(toF);
        filters.add(methodBox);
        filters.add(cashierBox);
        filters.add(unpaidCb);
        filters.add(searchF);
        filters.add(apply);

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.add(title, BorderLayout.NORTH);
        top.add(filters, BorderLayout.CENTER);
        return top;
    }

    private JComponent buildTable() {
        table.setRowHeight(32);
        table.setShowVerticalLines(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().putClientProperty(FlatClientProperties.STYLE, "font: bold");

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(4).setCellRenderer(right);
        table.getColumnModel().getColumn(5).setCellRenderer(right);
        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                           boolean focus, int row, int col) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                l.setHorizontalAlignment(SwingConstants.RIGHT);
                if (!sel) {
                    l.setForeground("-".equals(String.valueOf(v)) ? t.getForeground() : RED);
                }
                return l;
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) showReceipt();
            }
        });
        return new JScrollPane(table);
    }

    private JComponent buildBottom() {
        JButton receipt = Ui.secondary("Receipt");
        JButton collect = Ui.secondary("Collect payment");
        JButton amend = Ui.secondary("Amend sale");
        JButton history = Ui.secondary("Amendment history");

        receipt.addActionListener(e -> showReceipt());
        collect.addActionListener(e -> collectPayment());
        amend.addActionListener(e -> amendSelected());
        history.addActionListener(e -> showHistory());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(receipt);
        buttons.add(collect);
        buttons.add(amend);
        buttons.add(history);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.WEST);
        bottom.add(summary, BorderLayout.EAST);
        return bottom;
    }

    // ---------------------------------------------------------------- data
    private void load() {
        LocalDate from;
        LocalDate to;
        try {
            from = parseDate(fromF.getText());
            to = parseDate(toF.getText());
        } catch (DateTimeParseException ex) {
            Ui.error(this, "Dates must look like 2026-11-24.");
            return;
        }
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }

        User me = Session.getUser();
        Object picked = cashierBox.getSelectedItem();
        SaleFilter filter = new SaleFilter(
                from, to,
                methodBox.getSelectedIndex() == 0 ? null : String.valueOf(methodBox.getSelectedItem()),
                picked instanceof User u ? u.getId() : null,
                unpaidCb.isSelected(),
                searchF.getText(),
                me.getRole() == Role.CASHIER ? me.getId() : null);

        new SwingWorker<List<Sale>, Void>() {
            @Override
            protected List<Sale> doInBackground() throws Exception {
                return SaleDao.search(filter);
            }

            @Override
            protected void done() {
                try {
                    sales = get();
                    render();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Ui.error(SalesPanel.this, "Could not load sales. Check the database connection.");
                }
            }
        }.execute();
    }

    private void render() {
        model.setRowCount(0);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Sale s : sales) {
            BigDecimal bal = s.balance();
            revenue = revenue.add(s.totalAmount());
            if (bal.signum() > 0) outstanding = outstanding.add(bal);
            model.addRow(new Object[]{
                    s.saleNumber(), s.soldAt().format(WHEN),
                    s.customerName().isBlank() ? "-" : s.customerName(),
                    s.paymentMethod(), MONEY.format(s.totalAmount()), MONEY.format(s.amountPaid()),
                    bal.signum() > 0 ? MONEY.format(bal) : "-",
                    s.servedByName() == null ? "-" : s.servedByName()});
        }
        summary.setText(sales.size() + " sales  |  Revenue KES " + MONEY.format(revenue)
                + "  |  Outstanding KES " + MONEY.format(outstanding));
    }

    private static LocalDate parseDate(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? null : LocalDate.parse(t);
    }

    private Window owner() { return SwingUtilities.getWindowAncestor(this); }

    private Sale selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a sale in the table first.");
            return null;
        }
        return sales.get(row);
    }

    // -------------------------------------------------------------- actions
    private void showReceipt() {
        Sale s = selected();
        if (s != null) showReceipt(s.id());
    }

    private void showReceipt(long saleId) {
        try {
            Sale full = SaleDao.findById(saleId);
            new ReceiptDialog(owner(), full, SettingsDao.load()).setVisible(true);
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    private void collectPayment() {
        Sale s = selected();
        if (s == null) return;
        BigDecimal balance = s.balance();
        if (balance.signum() <= 0) {
            Ui.info(this, "This sale is already fully paid.");
            return;
        }

        JTextField amount = new JTextField(balance.toPlainString());
        JTextField ref = new JTextField();
        ref.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "M-Pesa code (optional)");
        JPanel form = new JPanel(new GridBagLayout());
        Ui.addRow(form, 0, "Outstanding", new JLabel("KES " + MONEY.format(balance)));
        Ui.addRow(form, 1, "Amount received", amount);
        Ui.addRow(form, 2, "Reference", ref);

        int ok = JOptionPane.showConfirmDialog(this, form, "Collect payment: " + s.saleNumber(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        BigDecimal value;
        try {
            value = new BigDecimal(amount.getText().trim().replace(",", ""));
            if (value.signum() <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            Ui.error(this, "Enter a valid amount greater than zero.");
            return;
        }
        try {
            SaleDao.addPayment(s.id(), value, ref.getText(), Session.getUser().getId());
            load();
            showReceipt(s.id());
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    private void amendSelected() {
        Sale s = selected();
        if (s == null) return;

        User me = Session.getUser();
        boolean privileged = me.getRole() == Role.SUPER_ADMIN || me.getRole() == Role.MANAGER;
        if (!privileged && !s.soldAt().toLocalDate().equals(LocalDate.now())) {
            Ui.error(this, "Cashiers can only amend their own sales from today.");
            return;
        }
        try {
            Sale full = SaleDao.findById(s.id());
            AmendDialog d = new AmendDialog(owner(), full);
            d.setVisible(true);
            if (d.isSaved()) {
                load();
                showReceipt(s.id());
            }
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    private void showHistory() {
        Sale s = selected();
        if (s == null) return;
        new AmendmentsDialog(owner(), s).setVisible(true);
    }
}