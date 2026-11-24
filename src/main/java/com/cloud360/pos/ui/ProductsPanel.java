package com.cloud360.pos.ui;

import com.cloud360.pos.dao.ProductDao;
import com.cloud360.pos.dao.SettingsDao;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.model.ProductBatch;
import com.cloud360.pos.util.Fmt;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ProductsPanel extends JPanel {

    private static final String[] COLUMNS = {
            "Name", "Category", "Unit", "Price", "Stock", "Reorder", "Status", "Barcode"};

    private final JTextField search = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");
    private final javax.swing.Timer searchTimer = new javax.swing.Timer(300, e -> reload());
    private List<Product> products = new ArrayList<>();

    public ProductsPanel() {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        searchTimer.setRepeats(false);

        add(buildTop(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        // Reload every time this screen is shown, so stock sold at the till is always up to date.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                reload();
            }
        });
    }

    // ------------------------------------------------------------------ UI
    private JComponent buildTop() {
        JLabel title = new JLabel("Products & Stock");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));

        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search name, barcode or SKU...");
        search.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 6,10,6,10");
        search.setPreferredSize(new Dimension(300, 36));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { searchTimer.restart(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { searchTimer.restart(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { searchTimer.restart(); }
        });

        JButton add = Ui.primary("+ Add product");
        add.addActionListener(e -> {
            ProductDialog d = new ProductDialog(owner(), null);
            d.setVisible(true);
            if (d.isSaved()) reload();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(search);
        right.add(add);

        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildTable() {
        table.setRowHeight(34);
        table.setShowVerticalLines(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().putClientProperty(FlatClientProperties.STYLE, "font: bold");

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int col : new int[]{3, 4, 5}) {
            table.getColumnModel().getColumn(col).setCellRenderer(right);
        }

        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                           boolean focus, int row, int col) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                l.setHorizontalAlignment(SwingConstants.CENTER);
                if (sel) {
                    l.setForeground(t.getSelectionForeground());
                } else {
                    switch (String.valueOf(v)) {
                        case "OUT" -> l.setForeground(new Color(0xB91C1C));
                        case "LOW" -> l.setForeground(new Color(0xC2410C));
                        default -> l.setForeground(new Color(0x15803D));
                    }
                }
                return l;
            }
        });

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });

        return new JScrollPane(table);
    }

    private JComponent buildBottom() {
        JButton edit = Ui.secondary("Edit");
        JButton stockIn = Ui.secondary("Stock in");
        JButton setStock = Ui.secondary("Set stock (stock-take)");
        JButton batches = Ui.secondary("View batches");
        JButton delete = Ui.secondary("Delete");

        edit.addActionListener(e -> editSelected());
        stockIn.addActionListener(e -> stockInSelected());
        setStock.addActionListener(e -> setStockSelected());
        batches.addActionListener(e -> showBatches());
        delete.addActionListener(e -> deleteSelected());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(edit);
        buttons.add(stockIn);
        buttons.add(setStock);
        buttons.add(batches);
        buttons.add(delete);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.WEST);
        bottom.add(summary, BorderLayout.EAST);
        return bottom;
    }

    // ---------------------------------------------------------------- data
    private void reload() {
        String q = search.getText();
        new SwingWorker<List<Product>, Void>() {
            @Override
            protected List<Product> doInBackground() throws Exception {
                return ProductDao.search(q);
            }

            @Override
            protected void done() {
                try {
                    products = get();
                    model.setRowCount(0);
                    int low = 0;
                    for (Product p : products) {
                        if (!p.getStatus().equals("OK")) low++;
                        model.addRow(new Object[]{
                                p.getName(), p.getCategory(), p.getUnit(), Fmt.money(p.getUnitPrice()),
                                p.getStock(), p.getReorderLevel(), p.getStatus(), p.getBarcode()});
                    }
                    summary.setText(products.size() + " products  |  " + low + " low or out of stock");
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Ui.error(ProductsPanel.this, "Could not load products. Check the database connection.");
                }
            }
        }.execute();
    }

    private Window owner() { return SwingUtilities.getWindowAncestor(this); }

    private Product selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Ui.info(this, "Select a product in the table first.");
            return null;
        }
        return products.get(row);
    }

    // -------------------------------------------------------------- actions
    private void editSelected() {
        Product p = selected();
        if (p == null) return;
        ProductDialog d = new ProductDialog(owner(), p);
        d.setVisible(true);
        if (d.isSaved()) reload();
    }

    private void stockInSelected() {
        Product p = selected();
        if (p == null) return;
        StockInDialog d = new StockInDialog(owner(), p);
        d.setVisible(true);
        if (d.isSaved()) reload();
    }

    private void setStockSelected() {
        Product p = selected();
        if (p == null) return;

        JSpinner qty = new JSpinner(new SpinnerNumberModel(p.getStock(), 0, 1_000_000, 1));
        JTextField reason = new JTextField("Stock take");
        JPanel form = new JPanel(new GridBagLayout());
        Ui.addRow(form, 0, "Counted quantity", qty);
        Ui.addRow(form, 1, "Reason", reason);

        int ok = JOptionPane.showConfirmDialog(this, form, "Set stock: " + p.getName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        try {
            ProductDao.setStock(p.getId(), (Integer) qty.getValue(), reason.getText(),
                    Session.getUser().getId(), SettingsDao.tracksExpiry());
            reload();
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    private void showBatches() {
        Product p = selected();
        if (p == null) return;
        try {
            List<ProductBatch> list = ProductDao.batches(p.getId());
            DefaultTableModel m = new DefaultTableModel(
                    new String[]{"Batch", "Received", "Remaining", "Expiry", "Received on"}, 0) {
                @Override
                public boolean isCellEditable(int r, int c) { return false; }
            };
            for (ProductBatch b : list) {
                m.addRow(new Object[]{b.getBatchNumber(), b.getQuantityReceived(), b.getQuantityRemaining(),
                        b.getExpiryDate() == null ? "-" : b.getExpiryDate().toString(),
                        b.getReceivedDate() == null ? "-" : b.getReceivedDate().toString()});
            }
            JTable t = new JTable(m);
            t.setRowHeight(28);
            JScrollPane sp = new JScrollPane(t);
            sp.setPreferredSize(new Dimension(580, 240));
            JOptionPane.showMessageDialog(this, sp, "Batches (earliest expiry first): " + p.getName(),
                    JOptionPane.PLAIN_MESSAGE);
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }

    private void deleteSelected() {
        Product p = selected();
        if (p == null) return;
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete \"" + p.getName() + "\"?\nPast sales keep their history.",
                "Confirm delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            ProductDao.softDelete(p.getId());
            reload();
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }
}