package com.cloud360.pos.ui;

import com.cloud360.pos.model.Role;
import com.cloud360.pos.model.User;
import com.cloud360.pos.util.Session;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import java.awt.*;
import java.util.EnumSet;
import java.util.Set;

public class MainFrame extends JFrame {

    private static final Color SIDEBAR = new Color(0x0F172A);

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final ButtonGroup navGroup = new ButtonGroup();
    private String firstCard;

    public MainFrame() {
        super("Cloud 360 POS v2");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 780);
        setMinimumSize(new Dimension(1100, 650));
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        add(buildSidebar(), BorderLayout.WEST);
        add(content, BorderLayout.CENTER);

        // Real screens replace the placeholders step by step.
        addScreen("dashboard", new DashboardPanel());
        addScreen("pos", new PosPanel());
        addScreen("sales", new SalesPanel());
        addScreen("products", new ProductsPanel());
        addScreen("reports", placeholder("Reports"));
        addScreen("staff", placeholder("Staff"));
        addScreen("settings", placeholder("Shop Settings"));

        cards.show(content, firstCard != null ? firstCard : "dashboard");
    }

    private JPanel buildSidebar() {
        User user = Session.getUser();
        Role role = user.getRole();

        JPanel side = new JPanel();
        side.setBackground(SIDEBAR);
        side.setPreferredSize(new Dimension(230, 0));
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(20, 14, 20, 14));

        JLabel brand = new JLabel("Cloud 360 POS");
        brand.setForeground(Color.WHITE);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 20f));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(brand);
        side.add(Box.createVerticalStrut(24));

        // Who can see what
        Set<Role> sellers = EnumSet.of(Role.SUPER_ADMIN, Role.MANAGER, Role.CASHIER);
        Set<Role> stockEditors = EnumSet.of(Role.SUPER_ADMIN, Role.MANAGER);
        Set<Role> reportViewers = EnumSet.of(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT);
        Set<Role> adminOnly = EnumSet.of(Role.SUPER_ADMIN);

        addNav(side, role, "Dashboard", "dashboard", sellers);
        addNav(side, role, "New Sale", "pos", sellers);
        addNav(side, role, "Sales History", "sales", sellers);
        addNav(side, role, "Products & Stock", "products", stockEditors);
        addNav(side, role, "Reports", "reports", reportViewers);
        addNav(side, role, "Staff", "staff", adminOnly);
        addNav(side, role, "Shop Settings", "settings", adminOnly);

        side.add(Box.createVerticalGlue());

        JLabel who = new JLabel("<html>" + user.getFullName() + "<br><small>" + role.getLabel() + "</small></html>");
        who.setForeground(new Color(0xCBD5E1));
        who.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(who);
        side.add(Box.createVerticalStrut(10));

        JButton logout = new JButton("Sign out");
        logout.setAlignmentX(Component.LEFT_ALIGNMENT);
        logout.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        logout.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
        logout.addActionListener(e -> {
            Session.logout();
            new LoginFrame().setVisible(true);
            dispose();
        });
        side.add(logout);
        return side;
    }

    private void addNav(JPanel side, Role role, String text, String card, Set<Role> allowed) {
        if (!allowed.contains(role)) {
            return;
        }
        JToggleButton b = new JToggleButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setForeground(Color.WHITE);
        b.setBackground(SIDEBAR);
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; borderWidth: 0; focusWidth: 0; margin: 6,14,6,14; "
                        + "selectedBackground: #1E40AF; hoverBackground: #1E293B; selectedForeground: #FFFFFF");
        b.addActionListener(e -> cards.show(content, card));
        navGroup.add(b);
        side.add(b);
        side.add(Box.createVerticalStrut(4));

        if (firstCard == null) { // the first menu item this role can see opens by default
            firstCard = card;
            b.setSelected(true);
        }
    }

    private void addScreen(String name, JComponent screen) {
        content.add(screen, name);
    }

    private JComponent placeholder(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        JLabel l = new JLabel(title + " (coming in the next step)");
        l.setFont(l.getFont().deriveFont(Font.BOLD, 22f));
        p.add(l);
        return p;
    }
}