package com.cloud360.pos.ui;

import com.cloud360.pos.dao.ProductDao;
import com.cloud360.pos.dao.SettingsDao;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class StockInDialog extends JDialog {

    private final Product product;
    private boolean saved;

    private final JSpinner qtySpin = new JSpinner(new SpinnerNumberModel(1, 1, 1_000_000, 1));
    private final JTextField batchF = new JTextField();
    private final JTextField expiryF = new JTextField();

    public StockInDialog(Window owner, Product product) {
        super(owner, "Stock in: " + product.getName(), Dialog.ModalityType.APPLICATION_MODAL);
        this.product = product;

        batchF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Leave empty to auto-generate");
        expiryF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-MM-dd");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 8, 22));
        Ui.addRow(form, 0, "Quantity received *", qtySpin);
        Ui.addRow(form, 1, "Batch number", batchF);
        Ui.addRow(form, 2, "Expiry date", expiryF);

        JButton save = Ui.primary("Receive stock");
        JButton cancel = Ui.secondary("Cancel");
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(save);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        setMinimumSize(new Dimension(420, 0));
        pack();
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() { return saved; }

    private void save() {
        LocalDate expiry = null;
        String text = expiryF.getText().trim();
        if (!text.isEmpty()) {
            try {
                expiry = LocalDate.parse(text);
            } catch (DateTimeParseException ex) {
                Ui.error(this, "Expiry date must look like 2027-03-31.");
                return;
            }
        }
        try {
            if (expiry == null && SettingsDao.tracksExpiry()) {
                Ui.error(this, "Expiry date is required for this shop.");
                return;
            }
            ProductDao.stockIn(product.getId(), batchF.getText(), (Integer) qtySpin.getValue(),
                    expiry, Session.getUser().getId());
            saved = true;
            dispose();
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }
}