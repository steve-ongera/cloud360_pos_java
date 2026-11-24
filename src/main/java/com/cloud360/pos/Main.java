package com.cloud360.pos;

import com.cloud360.pos.dao.UserDao;
import com.cloud360.pos.ui.LoginFrame;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;

public class Main {

    public static void main(String[] args) {
        FlatLightLaf.setup();
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);

        try {
            UserDao.ensureDefaultAdmin();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "Cannot connect to MySQL.\nCheck that MySQL is running and db.properties is correct.\n\n"
                            + e.getMessage(),
                    "Database error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}