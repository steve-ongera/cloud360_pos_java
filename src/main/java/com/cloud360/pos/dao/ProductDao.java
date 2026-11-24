package com.cloud360.pos.dao;

import com.cloud360.pos.config.Database;
import com.cloud360.pos.model.Product;
import com.cloud360.pos.model.ProductBatch;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ProductDao {

    private static final String STOCK_SUBQUERY =
            "COALESCE((SELECT SUM(b.quantity_remaining) FROM product_batches b "
                    + "WHERE b.product_id = p.id AND b.is_deleted = 0), 0)";

    /** Earliest expiry first; batches without expiry last, oldest first. */
    private static final String FEFO_ORDER = "ORDER BY expiry_date IS NULL, expiry_date ASC, created_at ASC";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ProductDao() {}

    // ------------------------------------------------------------------ read
    public static List<Product> search(String q) throws SQLException {
        boolean hasQuery = q != null && !q.isBlank();
        String sql = "SELECT p.*, " + STOCK_SUBQUERY + " AS stock FROM products p WHERE p.is_deleted = 0"
                + (hasQuery ? " AND (p.name LIKE ? OR p.generic_name LIKE ? OR p.barcode LIKE ? OR p.sku LIKE ?)" : "")
                + " ORDER BY p.name";

        List<Product> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (hasQuery) {
                String like = "%" + q.trim() + "%";
                for (int i = 1; i <= 4; i++) {
                    ps.setString(i, like);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapProduct(rs));
                }
            }
        }
        return out;
    }

    public static boolean barcodeInUse(String barcode, Long excludeId) throws SQLException {
        if (barcode == null || barcode.isBlank()) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM products WHERE is_deleted = 0 AND barcode = ?"
                + (excludeId != null ? " AND id <> ?" : "");
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, barcode);
            if (excludeId != null) {
                ps.setLong(2, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public static List<ProductBatch> batches(long productId) throws SQLException {
        String sql = "SELECT * FROM product_batches WHERE product_id = ? AND is_deleted = 0 "
                + "AND quantity_remaining > 0 " + FEFO_ORDER;
        List<ProductBatch> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProductBatch b = new ProductBatch();
                    b.setId(rs.getLong("id"));
                    b.setProductId(rs.getLong("product_id"));
                    b.setBatchNumber(rs.getString("batch_number"));
                    b.setQuantityReceived(rs.getInt("quantity_received"));
                    b.setQuantityRemaining(rs.getInt("quantity_remaining"));
                    Date exp = rs.getDate("expiry_date");
                    b.setExpiryDate(exp == null ? null : exp.toLocalDate());
                    Date rec = rs.getDate("received_date");
                    b.setReceivedDate(rec == null ? null : rec.toLocalDate());
                    out.add(b);
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- write
    /** Creates the product and, when qty > 0, its first batch plus a STOCK_IN ledger row. */
    public static long create(Product p, int qty, String batchNo, LocalDate expiry, long userId)
            throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String sql = "INSERT INTO products (name, generic_name, category, unit, unit_price, "
                        + "reorder_level, barcode, sku, is_visible) VALUES (?,?,?,?,?,?,?,?,?)";
                long productId;
                try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    bindProduct(ps, p);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        productId = keys.getLong(1);
                    }
                }
                if (qty > 0) {
                    String no = (batchNo == null || batchNo.isBlank()) ? autoBatchNo("B") : batchNo.trim();
                    long batchId = insertBatch(c, productId, no, qty, expiry);
                    logTxn(c, productId, batchId, "STOCK_IN", qty, "Opening stock", userId);
                }
                c.commit();
                return productId;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static void update(Product p) throws SQLException {
        String sql = "UPDATE products SET name=?, generic_name=?, category=?, unit=?, unit_price=?, "
                + "reorder_level=?, barcode=?, sku=?, is_visible=? WHERE id=?";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindProduct(ps, p);
            ps.setLong(10, p.getId());
            ps.executeUpdate();
        }
    }

    public static void softDelete(long productId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE products SET is_deleted = 1 WHERE id = ?")) {
            ps.setLong(1, productId);
            ps.executeUpdate();
        }
    }

    /** Receive new stock as a batch. */
    public static void stockIn(long productId, String batchNo, int qty, LocalDate expiry, long userId)
            throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String no = (batchNo == null || batchNo.isBlank()) ? autoBatchNo("B") : batchNo.trim();
                long batchId = insertBatch(c, productId, no, qty, expiry);
                logTxn(c, productId, batchId, "STOCK_IN", qty, "Stock received", userId);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Stock-take: force on-hand to an exact number using ADJUSTMENT rows (FEFO removal). */
    public static void setStock(long productId, int target, String reason, long userId, boolean tracksExpiry)
            throws SQLException {
        String why = (reason == null || reason.isBlank()) ? "Stock take" : reason.trim();
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<long[]> rows = new ArrayList<>(); // {batchId, remaining}
                String sql = "SELECT id, quantity_remaining FROM product_batches WHERE product_id = ? "
                        + "AND is_deleted = 0 AND quantity_remaining > 0 " + FEFO_ORDER + " FOR UPDATE";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, productId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
                        }
                    }
                }
                int current = rows.stream().mapToInt(r -> (int) r[1]).sum();
                int diff = target - current;

                if (diff > 0) {
                    if (rows.isEmpty()) {
                        LocalDate exp = tracksExpiry ? LocalDate.now().plusDays(365) : null;
                        long batchId = insertBatch(c, productId, autoBatchNo("ADJ"), diff, exp);
                        logTxn(c, productId, batchId, "ADJUSTMENT", diff, why, userId);
                    } else {
                        long batchId = rows.get(rows.size() - 1)[0];
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE product_batches SET quantity_remaining = quantity_remaining + ?, "
                                        + "quantity_received = quantity_received + ? WHERE id = ?")) {
                            ps.setInt(1, diff);
                            ps.setInt(2, diff);
                            ps.setLong(3, batchId);
                            ps.executeUpdate();
                        }
                        logTxn(c, productId, batchId, "ADJUSTMENT", diff, why, userId);
                    }
                } else if (diff < 0) {
                    int toRemove = -diff;
                    for (long[] r : rows) {
                        if (toRemove <= 0) break;
                        int take = (int) Math.min(r[1], toRemove);
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE product_batches SET quantity_remaining = quantity_remaining - ? WHERE id = ?")) {
                            ps.setInt(1, take);
                            ps.setLong(2, r[0]);
                            ps.executeUpdate();
                        }
                        logTxn(c, productId, r[0], "ADJUSTMENT", -take, why, userId);
                        toRemove -= take;
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // --------------------------------------------------------------- helpers
    private static long insertBatch(Connection c, long productId, String batchNo, int qty, LocalDate expiry)
            throws SQLException {
        String sql = "INSERT INTO product_batches (product_id, batch_number, quantity_received, "
                + "quantity_remaining, expiry_date) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, productId);
            ps.setString(2, batchNo);
            ps.setInt(3, qty);
            ps.setInt(4, qty);
            if (expiry == null) {
                ps.setNull(5, Types.DATE);
            } else {
                ps.setDate(5, Date.valueOf(expiry));
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLIntegrityConstraintViolationException dup) {
            throw new SQLException("Batch number '" + batchNo + "' already exists for this product.");
        }
    }

    private static void logTxn(Connection c, long productId, Long batchId, String type, int qty,
                               String reason, long userId) throws SQLException {
        String sql = "INSERT INTO stock_transactions (product_id, batch_id, transaction_type, quantity, "
                + "reason, performed_by) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, productId);
            if (batchId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, batchId);
            }
            ps.setString(3, type);
            ps.setInt(4, qty);
            ps.setString(5, reason);
            ps.setLong(6, userId);
            ps.executeUpdate();
        }
    }

    private static String autoBatchNo(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(STAMP);
    }

    private static void bindProduct(PreparedStatement ps, Product p) throws SQLException {
        ps.setString(1, p.getName());
        ps.setString(2, p.getGenericName());
        ps.setString(3, p.getCategory());
        ps.setString(4, p.getUnit());
        ps.setBigDecimal(5, p.getUnitPrice());
        ps.setInt(6, p.getReorderLevel());
        ps.setString(7, p.getBarcode());
        ps.setString(8, p.getSku());
        ps.setBoolean(9, p.isVisible());
    }

    private static Product mapProduct(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getLong("id"));
        p.setName(rs.getString("name"));
        p.setGenericName(rs.getString("generic_name"));
        p.setCategory(rs.getString("category"));
        p.setUnit(rs.getString("unit"));
        BigDecimal price = rs.getBigDecimal("unit_price");
        p.setUnitPrice(price == null ? BigDecimal.ZERO : price);
        p.setReorderLevel(rs.getInt("reorder_level"));
        p.setBarcode(rs.getString("barcode"));
        p.setSku(rs.getString("sku"));
        p.setVisible(rs.getBoolean("is_visible"));
        p.setStock(rs.getInt("stock"));
        return p;
    }
}