package com.cloud360.pos.ui;

import com.cloud360.pos.dao.ProductDao;
import com.cloud360.pos.dao.SettingsDao;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class ProductDialog extends JDialog {

    private final Product editing; // null = adding a new product
    private boolean saved;

    private final JTextField nameF = new JTextField();
    private final JTextField genericF = new JTextField();
    private final JTextField categoryF = new JTextField();
    private final JComboBox<String> unitBox = new JComboBox<>(new String[]{
            "piece", "pack", "bottle", "box", "carton", "dozen", "kg", "litre", "tablet"});
    private final JTextField priceF = new JTextField();
    private final JSpinner reorderSpin = new JSpinner(new SpinnerNumberModel(20, 0, 1_000_000, 1));
    private final JTextField barcodeF = new JTextField();
    private final JTextField skuF = new JTextField();
    private final JCheckBox visibleCb = new JCheckBox("Show in the POS grid", true);

    // only used when adding
    private final JSpinner qtySpin = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JTextField batchF = new JTextField();
    private final JTextField expiryF = new JTextField();

    public ProductDialog(Window owner, Product editing) {
        super(owner, editing == null ? "Add product" : "Edit product", Dialog.ModalityType.APPLICATION_MODAL);
        this.editing = editing;

        unitBox.setEditable(true);
        expiryF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-MM-dd (if the item expires)");
        batchF.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Leave empty to auto-generate");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 8, 22));
        int y = 0;
        Ui.addRow(form, y++, "Name *", nameF);
        Ui.addRow(form, y++, "Generic / alt. name", genericF);
        Ui.addRow(form, y++, "Category", categoryF);
        Ui.addRow(form, y++, "Unit", unitBox);
        Ui.addRow(form, y++, "Selling price (KES) *", priceF);
        Ui.addRow(form, y++, "Reorder level", reorderSpin);
        Ui.addRow(form, y++, "Barcode", barcodeF);
        Ui.addRow(form, y++, "SKU", skuF);
        Ui.addRow(form, y++, "", visibleCb);

        if (editing == null) {
            Ui.addRow(form, y++, "Opening quantity", qtySpin);
            Ui.addRow(form, y++, "Batch number", batchF);
            Ui.addRow(form, y++, "Expiry date", expiryF);
        } else {
            fill(editing);
        }

        JButton save = Ui.primary("Save");
        JButton cancel = Ui.secondary("Cancel");
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 14, 8, 14));
        buttons.add(cancel);
        buttons.add(save);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        setMinimumSize(new Dimension(480, 0));
        pack();
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() { return saved; }

    private void fill(Product p) {
        nameF.setText(p.getName());
        genericF.setText(p.getGenericName());
        categoryF.setText(p.getCategory());
        unitBox.setSelectedItem(p.getUnit());
        priceF.setText(p.getUnitPrice().toPlainString());
        reorderSpin.setValue(p.getReorderLevel());
        barcodeF.setText(p.getBarcode());
        skuF.setText(p.getSku());
        visibleCb.setSelected(p.isVisible());
    }

    private void save() {
        String name = nameF.getText().trim();
        if (name.isEmpty()) {
            Ui.error(this, "Name is required.");
            return;
        }

        BigDecimal price;
        try {
            price = new BigDecimal(priceF.getText().trim());
            if (price.signum() < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            Ui.error(this, "Enter a valid price, e.g. 250 or 99.50.");
            return;
        }

        String barcode = barcodeF.getText().trim();
        LocalDate expiry = null;
        String expiryText = expiryF.getText().trim();
        if (editing == null && !expiryText.isEmpty()) {
            try {
                expiry = LocalDate.parse(expiryText);
            } catch (DateTimeParseException ex) {
                Ui.error(this, "Expiry date must look like 2027-03-31.");
                return;
            }
        }

        try {
            if (ProductDao.barcodeInUse(barcode, editing == null ? null : editing.getId())) {
                Ui.error(this, "Another product already uses this barcode.");
                return;
            }

            Product p = new Product();
            if (editing != null) p.setId(editing.getId());
            p.setName(name);
            p.setGenericName(genericF.getText().trim());
            p.setCategory(categoryF.getText().trim());
            p.setUnit(String.valueOf(unitBox.getSelectedItem()).trim());
            p.setUnitPrice(price);
            p.setReorderLevel((Integer) reorderSpin.getValue());
            p.setBarcode(barcode);
            p.setSku(skuF.getText().trim());
            p.setVisible(visibleCb.isSelected());

            if (editing == null) {
                int qty = (Integer) qtySpin.getValue();
                if (qty > 0 && expiry == null && SettingsDao.tracksExpiry()) {
                    Ui.error(this, "Expiry date is required for this shop.");
                    return;
                }
                ProductDao.create(p, qty, batchF.getText(), expiry, Session.getUser().getId());
            } else {
                ProductDao.update(p);
            }
            saved = true;
            dispose();
        } catch (SQLException ex) {
            Ui.error(this, ex.getMessage());
        }
    }
}