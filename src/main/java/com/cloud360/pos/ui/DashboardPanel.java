package com.cloud360.pos.ui;

import com.cloud360.pos.dao.DashboardDao;
import com.cloud360.pos.model.DashboardData;
import com.cloud360.pos.model.DashboardData.DayPoint;
import com.cloud360.pos.model.DashboardData.LowStockItem;
import com.cloud360.pos.model.DashboardData.MethodTotal;
import com.cloud360.pos.model.DashboardData.RecentSale;
import com.cloud360.pos.model.DashboardData.TopProduct;
import com.cloud360.pos.model.Role;
import com.cloud360.pos.model.User;
import com.cloud360.pos.util.Session;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DashboardPanel extends JPanel {

    private static final int DAYS = 14;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat INT = new DecimalFormat("#,##0");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("dd MMM");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private static final Color MUTED = new Color(0x64748B);
    private static final Color RED = new Color(0xB91C1C);
    private static final Color ORANGE = new Color(0xC2410C);
    private static final Color GREEN = new Color(0x15803D);

    // header
    private final JLabel subtitle = new JLabel(" ");
    private final JLabel updated = new JLabel(" ");

    // KPI cards
    private final Kpi revenueKpi = new Kpi("Today's revenue", new Color(0x1E40AF));
    private final Kpi salesKpi = new Kpi("Sales today", new Color(0x0F766E));
    private final Kpi outstandingKpi = new Kpi("Outstanding balances", new Color(0xB91C1C));
    private final Kpi lowKpi = new Kpi("Low / out of stock", new Color(0xC2410C));
    private final Kpi expiryKpi = new Kpi("Expiring soon", new Color(0x7C3AED));
    private final Kpi rangeKpi = new Kpi("Revenue, last " + DAYS + " days", new Color(0x334155));

    // charts + tables
    private final TrendChart trendChart = new TrendChart();
    private final JPanel methodsBox = new JPanel();
    private final DefaultTableModel topModel = readOnly("Top sellers", "Qty", "Revenue");
    private final DefaultTableModel recentModel = readOnly("Recent sale", "Total", "Method");
    private final DefaultTableModel lowModel = readOnly("Low stock", "Stock", "Reorder");

    private final javax.swing.Timer autoRefresh = new javax.swing.Timer(60_000, e -> refresh());
    private boolean loading;

    public DashboardPanel() {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        // Refresh each time the screen is shown; keep it fresh every minute while visible.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    refresh();
                    autoRefresh.start();
                } else {
                    autoRefresh.stop();
                }
            }
        });
    }

    // ------------------------------------------------------------------ layout
    private JComponent buildHeader() {
        JLabel title = new JLabel("Dashboard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        subtitle.setForeground(MUTED);

        JPanel left = new JPanel(new GridLayout(2, 1, 0, 2));
        left.add(title);
        left.add(subtitle);

        JButton refresh = Ui.secondary("Refresh");
        refresh.addActionListener(e -> refresh());
        updated.setForeground(MUTED);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(updated);
        right.add(refresh);

        JPanel header = new JPanel(new BorderLayout());
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        ScrollablePanel body = new ScrollablePanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 14, 0);

        // KPI cards (2 x 3)
        JPanel kpis = new JPanel(new GridLayout(2, 3, 12, 12));
        kpis.setOpaque(false);
        kpis.add(revenueKpi);
        kpis.add(salesKpi);
        kpis.add(outstandingKpi);
        kpis.add(lowKpi);
        kpis.add(expiryKpi);
        kpis.add(rangeKpi);
        gc.gridy = 0;
        body.add(kpis, gc);

        // trend chart + payment split
        methodsBox.setLayout(new BoxLayout(methodsBox, BoxLayout.Y_AXIS));
        methodsBox.setOpaque(false);
        JPanel methodsWrap = new JPanel(new BorderLayout());
        methodsWrap.setOpaque(false);
        methodsWrap.add(methodsBox, BorderLayout.NORTH);

        JPanel charts = new JPanel(new GridBagLayout());
        charts.setOpaque(false);
        charts.setPreferredSize(new Dimension(10, 280));
        GridBagConstraints cg = new GridBagConstraints();
        cg.fill = GridBagConstraints.BOTH;
        cg.weighty = 1;
        cg.gridy = 0;
        cg.gridx = 0;
        cg.weightx = 0.65;
        cg.insets = new Insets(0, 0, 0, 12);
        charts.add(section("Revenue, last " + DAYS + " days", trendChart), cg);
        cg.gridx = 1;
        cg.weightx = 0.35;
        cg.insets = new Insets(0, 0, 0, 0);
        charts.add(section("Payment methods", methodsWrap), cg);
        gc.gridy = 1;
        body.add(charts, gc);

        // three tables
        JTable topTable = table(topModel);
        JTable recentTable = table(recentModel);
        JTable lowTable = table(lowModel);
        rightAlign(topTable, 1, 2);
        rightAlign(recentTable, 1);
        rightAlign(lowTable, 1, 2);
        lowTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                           boolean focus, int row, int col) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                l.setHorizontalAlignment(SwingConstants.RIGHT);
                l.setForeground("0".equals(String.valueOf(v)) ? RED : ORANGE);
                return l;
            }
        });

        JPanel tables = new JPanel(new GridLayout(1, 3, 12, 0));
        tables.setOpaque(false);
        tables.setPreferredSize(new Dimension(10, 290));
        tables.add(section("Top sellers (" + DAYS + " days)", scroll(topTable)));
        tables.add(section("Recent sales", scroll(recentTable)));
        tables.add(section("Needs restocking", scroll(lowTable)));
        gc.gridy = 2;
        body.add(tables, gc);

        // filler so everything sits at the top
        gc.gridy = 3;
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        body.add(new JLabel(), gc);

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(20);
        return sp;
    }

    // -------------------------------------------------------------------- data
    private void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        User me = Session.getUser();
        if (me == null) {
            loading = false;
            return;
        }
        Long scope = me.getRole() == Role.CASHIER ? me.getId() : null;

        new SwingWorker<DashboardData, Void>() {
            @Override
            protected DashboardData doInBackground() throws Exception {
                return DashboardDao.load(DAYS, scope);
            }

            @Override
            protected void done() {
                loading = false;
                try {
                    apply(get(), me, scope != null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    updated.setText("Could not load. Check the database connection.");
                }
            }
        }.execute();
    }

    private void apply(DashboardData d, User me, boolean ownOnly) {
        // header
        int hour = LocalDateTime.now().getHour();
        String greeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        String first = me.getFullName() == null || me.getFullName().isBlank()
                ? me.getUsername() : me.getFullName().trim().split("\\s+")[0];
        subtitle.setText(greeting + ", " + first + "   |   "
                + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
                + (ownOnly ? "   |   Showing your sales only" : ""));
        updated.setText("Updated " + LocalDateTime.now().format(HOUR));

        // KPI cards
        BigDecimal avg = d.todaySales() == 0 ? BigDecimal.ZERO
                : d.todayRevenue().divide(BigDecimal.valueOf(d.todaySales()), 2, RoundingMode.HALF_UP);
        revenueKpi.set("KES " + MONEY.format(d.todayRevenue()),
                d.todaySales() == 0 ? "No sales yet today" : "Average sale KES " + MONEY.format(avg), null);
        salesKpi.set(INT.format(d.todaySales()), INT.format(d.itemsSold()) + " items sold", null);

        boolean owed = d.outstanding().signum() > 0;
        outstandingKpi.set("KES " + MONEY.format(d.outstanding()),
                owed ? "Still owed by customers" : "Nothing outstanding", owed ? RED : GREEN);

        lowKpi.set(INT.format(d.lowStockCount()), INT.format(d.outOfStockCount()) + " out of stock",
                d.lowStockCount() > 0 ? ORANGE : GREEN);

        if (d.tracksExpiry()) {
            expiryKpi.set(INT.format(d.expiringCount()),
                    "batches within " + d.expiryAlertDays() + " days or expired",
                    d.expiringCount() > 0 ? RED : GREEN);
        } else {
            expiryKpi.set("Off", "Expiry tracking is disabled", MUTED);
        }

        BigDecimal rangeRevenue = BigDecimal.ZERO;
        int rangeSales = 0;
        for (DayPoint p : d.trend()) {
            rangeRevenue = rangeRevenue.add(p.revenue());
            rangeSales += p.sales();
        }
        rangeKpi.set("KES " + MONEY.format(rangeRevenue), INT.format(rangeSales) + " sales", null);

        // chart + payment split
        trendChart.setData(d.trend());
        renderMethods(d.methods());

        // tables
        topModel.setRowCount(0);
        for (TopProduct t : d.topProducts()) {
            topModel.addRow(new Object[]{t.name(), INT.format(t.quantity()), MONEY.format(t.revenue())});
        }
        recentModel.setRowCount(0);
        LocalDate today = LocalDate.now();
        for (RecentSale r : d.recentSales()) {
            String when = r.soldAt().toLocalDate().equals(today)
                    ? r.soldAt().format(HOUR) : r.soldAt().format(SHORT_DAY);
            recentModel.addRow(new Object[]{r.saleNumber().replace("OTC-", "") + "  " + when,
                    MONEY.format(r.total()), methodLabel(r.method())});
        }
        lowModel.setRowCount(0);
        for (LowStockItem l : d.lowStock()) {
            lowModel.addRow(new Object[]{l.name(), String.valueOf(l.stock()), String.valueOf(l.reorderLevel())});
        }
    }

    private void renderMethods(List<MethodTotal> methods) {
        methodsBox.removeAll();
        BigDecimal sum = BigDecimal.ZERO;
        for (MethodTotal m : methods) {
            sum = sum.add(m.total());
        }
        if (methods.isEmpty() || sum.signum() == 0) {
            JLabel none = new JLabel("No sales in the last " + DAYS + " days.");
            none.setForeground(MUTED);
            methodsBox.add(none);
        } else {
            for (MethodTotal m : methods) {
                int pct = m.total().multiply(BigDecimal.valueOf(100))
                        .divide(sum, 0, RoundingMode.HALF_UP).intValue();
                JLabel label = new JLabel(methodLabel(m.method()) + "   KES " + MONEY.format(m.total())
                        + "   (" + m.count() + " sales)");
                label.setAlignmentX(Component.LEFT_ALIGNMENT);

                JProgressBar bar = new JProgressBar(0, 100);
                bar.setValue(pct);
                bar.setStringPainted(true);
                bar.setString(pct + "%");
                bar.setAlignmentX(Component.LEFT_ALIGNMENT);
                bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

                methodsBox.add(label);
                methodsBox.add(Box.createVerticalStrut(4));
                methodsBox.add(bar);
                methodsBox.add(Box.createVerticalStrut(16));
            }
        }
        methodsBox.revalidate();
        methodsBox.repaint();
    }

    private static String methodLabel(String m) {
        return switch (m) {
            case "CASH" -> "Cash";
            case "MPESA" -> "M-Pesa";
            case "CARD" -> "Card";
            default -> m;
        };
    }

    // ----------------------------------------------------------------- helpers
    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(26);
        t.setShowVerticalLines(false);
        t.setRowSelectionAllowed(false);
        t.setFocusable(false);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    private static void rightAlign(JTable t, int... cols) {
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c : cols) {
            t.getColumnModel().getColumn(c).setCellRenderer(right);
        }
    }

    private static JScrollPane scroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Color.WHITE);
        return sp;
    }

    private static Card section(String title, JComponent body) {
        Card card = new Card(new BorderLayout(0, 8), null);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        card.add(l, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ------------------------------------------------------------ card widgets
    /** White rounded panel with an optional coloured accent stripe on the left. */
    private static class Card extends JPanel {
        private final Color accent;

        Card(LayoutManager layout, Color accent) {
            super(layout);
            this.accent = accent;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g2.setColor(new Color(0xE2E8F0));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            if (accent != null) {
                g2.setColor(accent);
                g2.fillRoundRect(0, 14, 5, Math.max(10, getHeight() - 28), 5, 5);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class Kpi extends Card {
        private final JLabel value = new JLabel("-");
        private final JLabel sub = new JLabel(" ");
        private final Color defaultColor;

        Kpi(String title, Color accent) {
            super(new GridLayout(3, 1, 0, 2), accent);
            setBorder(BorderFactory.createEmptyBorder(14, 22, 14, 14));

            JLabel t = new JLabel(title);
            t.setForeground(MUTED);
            value.setFont(value.getFont().deriveFont(Font.BOLD, 22f));
            defaultColor = value.getForeground();
            sub.setForeground(MUTED);
            sub.setFont(sub.getFont().deriveFont(12f));

            add(t);
            add(value);
            add(sub);
        }

        void set(String v, String s, Color color) {
            value.setText(v);
            value.setForeground(color == null ? defaultColor : color);
            sub.setText(s);
        }
    }

    // ------------------------------------------------------------ revenue chart
    /** Simple bar chart drawn with Swing: one bar per day, today highlighted, tooltip on hover. */
    private static class TrendChart extends JComponent {
        private static final int LEFT = 54;
        private static final int RIGHT = 6;
        private static final int TOP = 8;
        private static final int BOTTOM = 24;

        private List<DayPoint> points = List.of();
        private int hover = -1;

        TrendChart() {
            setPreferredSize(new Dimension(300, 200));
            setToolTipText(""); // registers the component for tooltips
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = indexAt(e.getX());
                    if (i != hover) {
                        hover = i;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            });
        }

        void setData(List<DayPoint> data) {
            this.points = data;
            repaint();
        }

        private int indexAt(int x) {
            int n = points.size();
            int cw = getWidth() - LEFT - RIGHT;
            if (n == 0 || cw <= 0 || x < LEFT || x >= LEFT + cw) {
                return -1;
            }
            return Math.min(n - 1, (int) ((x - LEFT) / ((double) cw / n)));
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = indexAt(e.getX());
            if (i < 0) {
                return null;
            }
            DayPoint p = points.get(i);
            return p.date().format(DateTimeFormatter.ofPattern("EEE d MMM")) + "  |  KES "
                    + MONEY.format(p.revenue()) + "  |  " + p.sales() + " sales";
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int cw = w - LEFT - RIGHT;
            int ch = h - TOP - BOTTOM;
            if (cw <= 0 || ch <= 0) {
                g2.dispose();
                return;
            }

            double max = 0;
            for (DayPoint p : points) {
                max = Math.max(max, p.revenue().doubleValue());
            }
            double axisMax = niceMax(max);
            FontMetrics fm = g2.getFontMetrics();

            // grid lines + y labels
            for (int i = 0; i <= 4; i++) {
                int y = TOP + ch - (int) Math.round(ch * i / 4.0);
                g2.setColor(new Color(0xE5E7EB));
                g2.drawLine(LEFT, y, LEFT + cw, y);
                String label = compact(axisMax * i / 4.0);
                g2.setColor(MUTED);
                g2.drawString(label, LEFT - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            }

            int n = points.size();
            if (n == 0) {
                g2.dispose();
                return;
            }
            double slot = (double) cw / n;
            int barW = (int) Math.max(4, slot * 0.6);

            for (int i = 0; i < n; i++) {
                DayPoint p = points.get(i);
                double v = p.revenue().doubleValue();
                int bh = (int) Math.round(ch * v / axisMax);
                if (v > 0 && bh < 2) {
                    bh = 2;
                }
                int x = LEFT + (int) Math.round(i * slot + (slot - barW) / 2.0);
                int y = TOP + ch - bh;

                Color c = i == hover ? new Color(0x3B82F6)
                        : i == n - 1 ? new Color(0x1E40AF) : new Color(0x93C5FD);
                g2.setColor(c);
                if (bh > 0) {
                    g2.fillRoundRect(x, y, barW, bh, 6, 6);
                }

                // x labels on every second day, always including today
                if ((n - 1 - i) % 2 == 0) {
                    String d = p.date().format(DateTimeFormatter.ofPattern("dd/MM"));
                    g2.setColor(MUTED);
                    g2.drawString(d, x + barW / 2 - fm.stringWidth(d) / 2, h - 8);
                }
            }
            g2.dispose();
        }

        private static double niceMax(double max) {
            if (max <= 0) {
                return 100;
            }
            double exp = Math.pow(10, Math.floor(Math.log10(max)));
            double f = max / exp;
            double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
            return nf * exp;
        }

        private static String compact(double v) {
            if (v >= 1_000_000) {
                return trim(v / 1_000_000) + "M";
            }
            if (v >= 1_000) {
                return trim(v / 1_000) + "K";
            }
            return trim(v);
        }

        private static String trim(double v) {
            return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
        }
    }

    // ------------------------------------------------- scroll panel (tracks width)
    private static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) { super(lm); }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}