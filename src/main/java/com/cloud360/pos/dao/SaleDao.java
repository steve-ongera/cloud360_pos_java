package com.cloud360.pos.dao;

import com.cloud360.pos.config.Database;
import com.cloud360.pos.model.CartLine;
import com.cloud360.pos.model.Role;
import com.cloud360.pos.model.Sale;
import com.cloud360.pos.model.SaleAmendment;
import com.cloud360.pos.model.SaleFilter;
import com.cloud360.pos.model.SaleItem;
import com.cloud360.pos.model.SaleRequest;
import com.cloud360.pos.model.User;
import com.google.gson.Gson;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SaleDao {

    /** Earliest expiry first; batches without expiry last, oldest first. */
    private static final String FEFO = "ORDER BY expiry_date IS NULL, expiry_date ASC, created_at ASC";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Gson GSON = new Gson();

    private SaleDao() {}

    // ================================================================ create
    /**
     * Records a sale in ONE transaction: header, line items, FEFO stock deduction and the stock ledger.
     * Anything that fails rolls everything back.
     */
    public static Sale create(SaleRequest r, long userId) throws SQLException {
        long saleId;
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String number = nextSaleNumber(c);
                saleId = insertHeader(c, number, r, userId);

                BigDecimal subtotal = BigDecimal.ZERO;
                for (CartLine line : r.lines()) {
                    subtotal = subtotal.add(addLine(c, saleId, number, line, userId));
                }
                finishSale(c, saleId, subtotal, r.discount(), r.amountPaid());
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return findById(saleId);
    }

    // ================================================================== read
    public static Sale findById(long id) throws SQLException {
        try (Connection c = Database.getConnection()) {
            return loadSale(c, id);
        }
    }

    /** Lightweight list for the history screen (no line items). Newest first, max 500 rows. */
    public static List<Sale> search(SaleFilter f) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT s.*, COALESCE(NULLIF(u.full_name, ''), u.username) AS served_by_name "
                        + "FROM otc_sales s LEFT JOIN users u ON u.id = s.served_by WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (f.from() != null) {
            sql.append(" AND s.sold_at >= ?");
            params.add(Timestamp.valueOf(f.from().atStartOfDay()));
        }
        if (f.to() != null) {
            sql.append(" AND s.sold_at < ?");
            params.add(Timestamp.valueOf(f.to().plusDays(1).atStartOfDay()));
        }
        if (f.method() != null) {
            sql.append(" AND s.payment_method = ?");
            params.add(f.method());
        }
        if (f.servedBy() != null) {
            sql.append(" AND s.served_by = ?");
            params.add(f.servedBy());
        }
        if (f.onlyUserId() != null) {
            sql.append(" AND s.served_by = ?");
            params.add(f.onlyUserId());
        }
        if (f.unpaidOnly()) {
            sql.append(" AND s.total_amount > s.amount_paid");
        }
        if (f.search() != null && !f.search().isBlank()) {
            sql.append(" AND (s.sale_number LIKE ? OR s.customer_name LIKE ? "
                    + "OR s.customer_phone LIKE ? OR s.reference_number LIKE ?)");
            String like = "%" + f.search().trim() + "%";
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        sql.append(" ORDER BY s.sold_at DESC LIMIT 500");

        List<Sale> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapHeader(rs, List.of()));
                }
            }
        }
        return out;
    }

    public static List<SaleAmendment> amendments(long saleId) throws SQLException {
        String sql = "SELECT a.id, a.reason, a.created_at, a.before_json, a.after_json, "
                + "COALESCE(NULLIF(u.full_name, ''), u.username) AS by_name "
                + "FROM otc_sale_amendments a LEFT JOIN users u ON u.id = a.amended_by "
                + "WHERE a.sale_id = ? ORDER BY a.created_at DESC, a.id DESC";
        List<SaleAmendment> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SaleAmendment(rs.getLong("id"), rs.getString("by_name"),
                            rs.getString("reason"), rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getString("before_json"), rs.getString("after_json")));
                }
            }
        }
        return out;
    }

    // ========================================================== add payment
    /** Collect (part of) an outstanding balance. Audited like any other change. */
    public static Sale addPayment(long saleId, BigDecimal amount, String reference, long userId)
            throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                BigDecimal total;
                BigDecimal paid;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT total_amount, amount_paid FROM otc_sales WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, saleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Sale not found.");
                        }
                        total = rs.getBigDecimal(1);
                        paid = rs.getBigDecimal(2);
                    }
                }
                BigDecimal balance = total.subtract(paid);
                if (balance.signum() <= 0) {
                    throw new SQLException("This sale is already fully paid.");
                }
                if (amount.compareTo(balance) > 0) {
                    throw new SQLException("Only KES " + balance.toPlainString() + " is outstanding.");
                }

                String before = snapshot(c, saleId);
                boolean hasRef = reference != null && !reference.isBlank();
                String sql = hasRef
                        ? "UPDATE otc_sales SET amount_paid = amount_paid + ?, reference_number = ? WHERE id = ?"
                        : "UPDATE otc_sales SET amount_paid = amount_paid + ? WHERE id = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setBigDecimal(1, amount);
                    if (hasRef) {
                        ps.setString(2, reference.trim());
                        ps.setLong(3, saleId);
                    } else {
                        ps.setLong(2, saleId);
                    }
                    ps.executeUpdate();
                }
                insertAmendment(c, saleId, userId, "Balance payment of " + amount.toPlainString(),
                        before, snapshot(c, saleId));
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return findById(saleId);
    }

    // ================================================================ amend
    /**
     * Corrects a processed sale: puts the old stock back, re-takes stock for the corrected lines (FEFO),
     * recalculates totals and writes a before/after audit row. All in one transaction.
     * Owners and managers can amend any sale; cashiers only their own sale from today.
     */
    public static Sale amend(long saleId, SaleRequest r, String reason, User actor) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String number;
                Long servedBy;
                LocalDateTime soldAt;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT sale_number, served_by, sold_at FROM otc_sales WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, saleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Sale not found.");
                        }
                        number = rs.getString(1);
                        long sb = rs.getLong(2);
                        servedBy = rs.wasNull() ? null : sb;
                        soldAt = rs.getTimestamp(3).toLocalDateTime();
                    }
                }

                boolean privileged = actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.MANAGER;
                if (!privileged && (servedBy == null || servedBy.longValue() != actor.getId()
                        || !soldAt.toLocalDate().equals(LocalDate.now()))) {
                    throw new SQLException("Cashiers can only amend their own sales from today.");
                }

                String before = snapshot(c, saleId);

                // 1) put the old stock back
                List<long[]> old = new ArrayList<>(); // {batchId, productId, quantity}
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT batch_id, product_id, quantity FROM otc_sale_items WHERE sale_id = ?")) {
                    ps.setLong(1, saleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long batchId = rs.getLong(1);
                            if (!rs.wasNull()) { // a deleted batch cannot take stock back
                                old.add(new long[]{batchId, rs.getLong(2), rs.getLong(3)});
                            }
                        }
                    }
                }
                for (long[] o : old) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE product_batches SET quantity_remaining = quantity_remaining + ? WHERE id = ?")) {
                        ps.setLong(1, o[2]);
                        ps.setLong(2, o[0]);
                        ps.executeUpdate();
                    }
                    logTxn(c, o[1], o[0], "ADJUSTMENT", (int) o[2], "Amend " + number + ": restore", actor.getId());
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM otc_sale_items WHERE sale_id = ?")) {
                    ps.setLong(1, saleId);
                    ps.executeUpdate();
                }

                // 2) re-take stock for the corrected lines
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE otc_sales SET customer_name = ?, customer_phone = ?, payment_method = ?, "
                                + "reference_number = ?, discount = ? WHERE id = ?")) {
                    ps.setString(1, r.customerName());
                    ps.setString(2, r.customerPhone());
                    ps.setString(3, r.paymentMethod());
                    ps.setString(4, r.referenceNumber());
                    ps.setBigDecimal(5, r.discount());
                    ps.setLong(6, saleId);
                    ps.executeUpdate();
                }
                BigDecimal subtotal = BigDecimal.ZERO;
                for (CartLine line : r.lines()) {
                    subtotal = subtotal.add(addLine(c, saleId, number, line, actor.getId()));
                }
                finishSale(c, saleId, subtotal, r.discount(), r.amountPaid());
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE otc_sales SET amended_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    ps.setLong(1, saleId);
                    ps.executeUpdate();
                }

                insertAmendment(c, saleId, actor.getId(), reason, before, snapshot(c, saleId));
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return findById(saleId);
    }

    // ============================================================== helpers
    private static Sale loadSale(Connection c, long id) throws SQLException {
        String head = "SELECT s.*, COALESCE(NULLIF(u.full_name, ''), u.username) AS served_by_name "
                + "FROM otc_sales s LEFT JOIN users u ON u.id = s.served_by WHERE s.id = ?";
        String itemSql = "SELECT i.product_id, i.quantity, i.unit_price, i.subtotal, p.name, p.unit "
                + "FROM otc_sale_items i JOIN products p ON p.id = i.product_id "
                + "WHERE i.sale_id = ? ORDER BY i.id";

        List<SaleItem> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(itemSql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new SaleItem(rs.getLong("product_id"), rs.getString("name"),
                            rs.getString("unit"), rs.getInt("quantity"),
                            rs.getBigDecimal("unit_price"), rs.getBigDecimal("subtotal")));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(head)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Sale not found.");
                }
                return mapHeader(rs, items);
            }
        }
    }

    private static Sale mapHeader(ResultSet rs, List<SaleItem> items) throws SQLException {
        return new Sale(
                rs.getLong("id"), rs.getString("sale_number"),
                rs.getString("customer_name"), rs.getString("customer_phone"),
                rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("amount_paid"),
                rs.getString("payment_method"), rs.getString("reference_number"),
                rs.getString("served_by_name"),
                rs.getTimestamp("sold_at").toLocalDateTime(), items);
    }

    /** JSON picture of a sale, stored before/after every correction. */
    private static String snapshot(Connection c, long saleId) throws SQLException {
        Sale s = loadSale(c, saleId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customer_name", s.customerName());
        m.put("customer_phone", s.customerPhone());
        m.put("payment_method", s.paymentMethod());
        m.put("reference_number", s.referenceNumber());
        m.put("discount", s.discount().toPlainString());
        m.put("subtotal", s.subtotal().toPlainString());
        m.put("total_amount", s.totalAmount().toPlainString());
        m.put("amount_paid", s.amountPaid().toPlainString());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SaleItem i : s.items()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("product", i.productName());
            im.put("quantity", i.quantity());
            im.put("unit_price", i.unitPrice().toPlainString());
            im.put("subtotal", i.subtotal().toPlainString());
            items.add(im);
        }
        m.put("items", items);
        return GSON.toJson(m);
    }

    private static void insertAmendment(Connection c, long saleId, long userId, String reason,
                                        String beforeJson, String afterJson) throws SQLException {
        String why = reason == null ? "" : reason.trim();
        if (why.length() > 255) {
            why = why.substring(0, 255);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO otc_sale_amendments (sale_id, amended_by, reason, before_json, after_json) "
                        + "VALUES (?,?,?,?,?)")) {
            ps.setLong(1, saleId);
            ps.setLong(2, userId);
            ps.setString(3, why);
            ps.setString(4, beforeJson);
            ps.setString(5, afterJson);
            ps.executeUpdate();
        }
    }

    private static void finishSale(Connection c, long saleId, BigDecimal subtotal, BigDecimal discount,
                                   BigDecimal amountPaid) throws SQLException {
        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal paid = amountPaid == null ? total : amountPaid;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE otc_sales SET subtotal = ?, total_amount = ?, amount_paid = ? WHERE id = ?")) {
            ps.setBigDecimal(1, subtotal);
            ps.setBigDecimal(2, total);
            ps.setBigDecimal(3, paid);
            ps.setLong(4, saleId);
            ps.executeUpdate();
        }
    }

    private static void logTxn(Connection c, long productId, long batchId, String type, int qty,
                               String reason, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO stock_transactions (product_id, batch_id, transaction_type, quantity, "
                        + "reason, performed_by) VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, productId);
            ps.setLong(2, batchId);
            ps.setString(3, type);
            ps.setInt(4, qty);
            ps.setString(5, reason);
            ps.setLong(6, userId);
            ps.executeUpdate();
        }
    }

    /** OTC-yyyyMMdd-0001, counting up each day. The UNIQUE key on sale_number guards against clashes. */
    private static String nextSaleNumber(Connection c) throws SQLException {
        String prefix = "OTC-" + LocalDate.now().format(DAY) + "-";
        String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(sale_number, ?) AS UNSIGNED)), 0) "
                + "FROM otc_sales WHERE sale_number LIKE ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, prefix.length() + 1);
            ps.setString(2, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return prefix + String.format("%04d", rs.getLong(1) + 1);
            }
        }
    }

    private static long insertHeader(Connection c, String number, SaleRequest r, long userId) throws SQLException {
        String sql = "INSERT INTO otc_sales (sale_number, customer_name, customer_phone, discount, "
                + "payment_method, reference_number, served_by) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, number);
            ps.setString(2, r.customerName());
            ps.setString(3, r.customerPhone());
            ps.setBigDecimal(4, r.discount());
            ps.setString(5, r.paymentMethod());
            ps.setString(6, r.referenceNumber());
            ps.setLong(7, userId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Adds one cart line (possibly across several batches) and returns the line total. */
    private static BigDecimal addLine(Connection c, long saleId, String saleNo, CartLine line, long userId)
            throws SQLException {
        long productId = line.getProduct().getId();
        int qty = line.getQuantity();

        // Re-read the product so the price and name always come from the database.
        BigDecimal price;
        String name;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, unit_price FROM products WHERE id = ? AND is_deleted = 0")) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("\"" + line.getProduct().getName() + "\" no longer exists.");
                }
                name = rs.getString("name");
                price = rs.getBigDecimal("unit_price");
            }
        }

        // Lock this product's batches in FEFO order.
        List<long[]> batches = new ArrayList<>(); // {batchId, remaining}
        String batchSql = "SELECT id, quantity_remaining FROM product_batches WHERE product_id = ? "
                + "AND is_deleted = 0 AND quantity_remaining > 0 " + FEFO + " FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(batchSql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batches.add(new long[]{rs.getLong(1), rs.getLong(2)});
                }
            }
        }
        long available = batches.stream().mapToLong(b -> b[1]).sum();
        if (available < qty) {
            throw new SQLException("Insufficient stock for " + name + " (only " + available + " left).");
        }

        BigDecimal lineTotal = line.getLineAmount() != null
                ? line.getLineAmount()
                : price.multiply(BigDecimal.valueOf(qty));

        int need = qty;
        BigDecimal allocated = BigDecimal.ZERO;
        for (long[] b : batches) {
            if (need <= 0) {
                break;
            }
            int take = (int) Math.min(b[1], need);
            need -= take;

            BigDecimal portion;
            BigDecimal unit;
            if (line.getLineAmount() == null) {
                unit = price;
                portion = price.multiply(BigDecimal.valueOf(take));
            } else {
                portion = need == 0
                        ? lineTotal.subtract(allocated)
                        : lineTotal.multiply(BigDecimal.valueOf(take))
                        .divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
                unit = portion.divide(BigDecimal.valueOf(take), 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(portion);

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO otc_sale_items (sale_id, product_id, batch_id, quantity, unit_price, subtotal) "
                            + "VALUES (?,?,?,?,?,?)")) {
                ps.setLong(1, saleId);
                ps.setLong(2, productId);
                ps.setLong(3, b[0]);
                ps.setInt(4, take);
                ps.setBigDecimal(5, unit);
                ps.setBigDecimal(6, portion);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE product_batches SET quantity_remaining = quantity_remaining - ? WHERE id = ?")) {
                ps.setInt(1, take);
                ps.setLong(2, b[0]);
                ps.executeUpdate();
            }
            logTxn(c, productId, b[0], "STOCK_OUT", take, "OTC sale " + saleNo, userId);
        }
        return lineTotal;
    }
}