package com.cloud360.pos.ui;

import com.cloud360.pos.dao.UserDao;
import com.cloud360.pos.model.User;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import java.awt.*;

public class LoginFrame extends JFrame {

    private static final Color BRAND = new Color(0x1E40AF);

    private final JTextField loginField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JButton loginButton = new JButton("Sign in");
    private final JLabel statusLabel = new JLabel(" ");

    public LoginFrame() {
        super("Cloud 360 POS v2 - Sign in");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 480);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel root = new JPanel(new GridLayout(1, 2));
        root.add(buildBrandPanel());
        root.add(buildFormPanel());
        setContentPane(root);

        getRootPane().setDefaultButton(loginButton); // Enter key signs in
    }

    // ---- left side: branding ----
    private JPanel buildBrandPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(BRAND);

        JLabel title = new JLabel("Cloud 360");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 38f));

        JLabel sub = new JLabel("Point of Sale  v2");
        sub.setForeground(new Color(0xBFDBFE));
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 16f));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.gridy = 0;
        p.add(title, gc);
        gc.gridy = 1;
        p.add(sub, gc);
        return p;
    }

    // ---- right side: form ----
    private JPanel buildFormPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));

        JLabel heading = new JLabel("Welcome back");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 24f));

        JLabel hint = new JLabel("Sign in to continue");
        hint.setForeground(Color.GRAY);

        loginField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Email or username");
        passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Password");
        passwordField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        loginField.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 6,10,6,10");
        passwordField.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 6,10,6,10; showRevealButton: true");

        loginButton.setBackground(BRAND);
        loginButton.setForeground(Color.WHITE);
        loginButton.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; font: bold +1; margin: 8,12,8,12; borderWidth: 0; focusWidth: 0");
        loginButton.addActionListener(e -> doLogin());

        statusLabel.setForeground(new Color(0xB91C1C));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 0, 4, 0);

        gc.gridy = 0; p.add(heading, gc);
        gc.gridy = 1; gc.insets = new Insets(0, 0, 22, 0); p.add(hint, gc);
        gc.insets = new Insets(6, 0, 6, 0);
        gc.gridy = 2; p.add(loginField, gc);
        gc.gridy = 3; p.add(passwordField, gc);
        gc.gridy = 4; gc.insets = new Insets(14, 0, 6, 0); p.add(loginButton, gc);
        gc.gridy = 5; gc.insets = new Insets(6, 0, 0, 0); p.add(statusLabel, gc);
        return p;
    }

    // ---- sign in (runs off the UI thread so the window never freezes) ----
    private void doLogin() {
        String login = loginField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (login.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Enter your email/username and password.");
            return;
        }

        loginButton.setEnabled(false);
        statusLabel.setText(" ");

        new SwingWorker<User, Void>() {
            @Override
            protected User doInBackground() throws Exception {
                return UserDao.authenticate(login, password);
            }

            @Override
            protected void done() {
                loginButton.setEnabled(true);
                try {
                    User user = get();
                    if (user == null) {
                        statusLabel.setText("Invalid email/username or password.");
                        passwordField.setText("");
                        return;
                    }
                    Session.login(user);
                    new MainFrame().setVisible(true);
                    dispose();
                } catch (Exception ex) {
                    statusLabel.setText("Cannot reach the database. Check db.properties.");
                    ex.printStackTrace();
                }
            }
        }.execute();
    }
}