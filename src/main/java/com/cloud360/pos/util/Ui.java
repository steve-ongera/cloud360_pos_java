package com.cloud360.pos.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import java.awt.*;

public final class Ui {

    public static final Color BRAND = new Color(0x1E40AF);

    private Ui() {}

    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Cloud 360 POS", JOptionPane.INFORMATION_MESSAGE);
    }

    public static JButton primary(String text) {
        JButton b = new JButton(text);
        b.setBackground(BRAND);
        b.setForeground(Color.WHITE);
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; borderWidth: 0; focusWidth: 0; margin: 7,16,7,16; font: bold");
        return b;
    }

    public static JButton secondary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 7,14,7,14");
        return b;
    }

    /** Adds "label | field" as one row of a GridBagLayout form. */
    public static void addRow(JPanel p, int y, String label, JComponent field) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = y;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(5, 0, 5, 12);
        gc.gridx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(5, 0, 5, 0);
        p.add(field, gc);
    }
}