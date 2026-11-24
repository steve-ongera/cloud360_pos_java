package com.cloud360.pos.dao;

import com.cloud360.pos.config.Database;
import com.cloud360.pos.model.DashboardData;
import com.cloud360.pos.model.DashboardData.DayPoint;
import com.cloud360.pos.model.DashboardData.LowStockItem;
import com.cloud360.pos.model.DashboardData.MethodTotal;
import com.cloud360.pos.model.DashboardData.RecentSale;
import com.cloud360.pos.model.DashboardData.TopProduct;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DashboardDao {

    /** Visible products with their on-hand stock (sum of live batches). */
    private static final String STOCK_DERIVED =
            "SELECT p.name, p.unit, p.reorder_level, "
                    + "COALESCE((SELECT SUM(b.quantity_remaining) FROM product_batches b "
                    + "WHERE b.product_id = p.id AND b.is_deleted = 0), 0) AS stock "
                    + "FROM products p WHERE p.is_deleted = 0 AND p.is_visible = 1";

    private DashboardDao() {}

    /**
     * @param days        length of the trend / payment split / top sellers window (e.g. 14)
     * @param onlyUserId  set for cashiers so they only see their own sales; null = whole shop
     */
    public static DashboardData load(int days, Long onlyUserId) throws SQLException {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        Timestamp todayStart = Timestamp.valueOf(today.atStartOfDay());
        Timestamp tomorrow = Timestamp.valueOf(today.plusDays(1).atStartOfDay());
        Timestamp rangeStart = Timestamp.valueOf(from.atStartOfDay());
        String scope = onlyUserId == null ? "" : " AND s.served_by = ?";

        try (Connection c = Database.getConnection()) {

            // ---- today
            BigDecimal todayRevenue = BigDecimal.ZERO;
            int todaySales = 0;
            try (PreparedStatement ps = prep(c,
                    "SELECT COALESCE(SUM(s.total_amount), 0), COUNT(*) FROM otc_sales s "
                            + "WHERE s.sold_at >= ? AND s.sold_at < ?" + scope,
                    onlyUserId, todayStart, tomorrow);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                todayRevenue = rs.getBigDecimal(1);
                todaySales = rs.getInt(2);
            }

            long itemsSold;
            try (PreparedStatement ps = prep(c,
                    "SELECT COALESCE(SUM(i.quantity), 0) FROM otc_sale_items i "
                            + "JOIN otc_sales s ON s.id = i.sale_id "
                            + "WHERE s.sold_at >= ? AND s.sold_at < ?" + scope,
                    onlyUserId, todayStart, tomorrow);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                itemsSold = rs.getLong(1);
            }

            BigDecimal outstanding;
            try (PreparedStatement ps = prep(c,
                    "SELECT COALESCE(SUM(GREATEST(s.total_amount - s.amount_paid, 0)), 0) "
                            + "FROM otc_sales s WHERE 1 = 1" + scope,
                    onlyUserId);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                outstanding = rs.getBigDecimal(1);
            }

            // ---- shop settings (expiry alerts)
            boolean tracksExpiry = false;
            int alertDays = 90;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tracks_expiry, expiry_alert_days FROM shop_settings WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tracksExpiry = rs.getBoolean(1);
                    alertDays = rs.getInt(2);
                }
            }

            // ---- stock alerts (shop-wide)
            int lowCount;
            int outCount;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(t.stock = 0), 0) FROM (" + STOCK_DERIVED + ") t "
                            + "WHERE t.stock <= t.reorder_level");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                lowCount = rs.getInt(1);
                outCount = rs.getInt(2);
            }

            int expiring = 0;
            if (tracksExpiry) {
                try (PreparedStatement ps = prep(c,
                        "SELECT COUNT(*) FROM product_batches b JOIN products p ON p.id = b.product_id "
                                + "WHERE b.is_deleted = 0 AND p.is_deleted = 0 AND b.quantity_remaining > 0 "
                                + "AND b.expiry_date IS NOT NULL AND b.expiry_date <= ?",
                        null, java.sql.Date.valueOf(today.plusDays(alertDays)));
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    expiring = rs.getInt(1);
                }
            }

            // ---- trend (zero-filled, one point per day)
            Map<LocalDate, DayPoint> byDay = new HashMap<>();
            try (PreparedStatement ps = prep(c,
                    "SELECT DATE(s.sold_at), SUM(s.total_amount), COUNT(*) FROM otc_sales s "
                            + "WHERE s.sold_at >= ? AND s.sold_at < ?" + scope
                            + " GROUP BY DATE(s.sold_at)",
                    onlyUserId, rangeStart, tomorrow);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate d = rs.getDate(1).toLocalDate();
                    byDay.put(d, new DayPoint(d, rs.getBigDecimal(2), rs.getInt(3)));
                }
            }
            List<DayPoint> trend = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                trend.add(byDay.getOrDefault(d, new DayPoint(d, BigDecimal.ZERO, 0)));
            }

            // ---- payment split
            List<MethodTotal> methods = new ArrayList<>();
            try (PreparedStatement ps = prep(c,
                    "SELECT s.payment_method, SUM(s.total_amount), COUNT(*) FROM otc_sales s "
                            + "WHERE s.sold_at >= ? AND s.sold_at < ?" + scope
                            + " GROUP BY s.payment_method ORDER BY SUM(s.total_amount) DESC",
                    onlyUserId, rangeStart, tomorrow);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    methods.add(new MethodTotal(rs.getString(1), rs.getBigDecimal(2), rs.getInt(3)));
                }
            }

            // ---- top sellers
            List<TopProduct> top = new ArrayList<>();
            try (PreparedStatement ps = prep(c,
                    "SELECT p.name, p.unit, SUM(i.quantity) AS q, SUM(i.subtotal) AS r "
                            + "FROM otc_sale_items i JOIN otc_sales s ON s.id = i.sale_id "
                            + "JOIN products p ON p.id = i.product_id "
                            + "WHERE s.sold_at >= ? AND s.sold_at < ?" + scope
                            + " GROUP BY p.id, p.name, p.unit ORDER BY r DESC LIMIT 6",
                    onlyUserId, rangeStart, tomorrow);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    top.add(new TopProduct(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)));
                }
            }

            // ---- recent sales
            List<RecentSale> recent = new ArrayList<>();
            try (PreparedStatement ps = prep(c,
                    "SELECT s.sale_number, s.sold_at, s.customer_name, s.total_amount, s.amount_paid, "
                            + "s.payment_method FROM otc_sales s WHERE 1 = 1" + scope
                            + " ORDER BY s.sold_at DESC LIMIT 8",
                    onlyUserId);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    recent.add(new RecentSale(rs.getString(1), rs.getTimestamp(2).toLocalDateTime(),
                            rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6)));
                }
            }

            // ---- lowest stock first
            List<LowStockItem> low = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.name, t.unit, t.stock, t.reorder_level FROM (" + STOCK_DERIVED + ") t "
                            + "WHERE t.stock <= t.reorder_level ORDER BY t.stock, t.name LIMIT 8");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    low.add(new LowStockItem(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                }
            }

            return new DashboardData(todayRevenue, todaySales, itemsSold, outstanding,
                    lowCount, outCount, tracksExpiry, alertDays, expiring,
                    trend, methods, top, recent, low);
        }
    }

    /** Binds the given values in order, then the cashier scope (if any) as the last parameter. */
    private static PreparedStatement prep(Connection c, String sql, Long userScope, Object... base)
            throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        int i = 1;
        for (Object o : base) {
            ps.setObject(i++, o);
        }
        if (userScope != null) {
            ps.setLong(i, userScope);
        }
        return ps;
    }
}