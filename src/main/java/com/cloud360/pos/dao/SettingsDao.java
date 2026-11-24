package com.cloud360.pos.dao;

import com.cloud360.pos.config.Database;
import com.cloud360.pos.model.ShopSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class SettingsDao {

    private SettingsDao() {}

    /** True when this shop asks for batch expiry dates (pharmacy, supermarket...). */
    public static boolean tracksExpiry() throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT tracks_expiry FROM shop_settings WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Shop details for receipts. */
    public static ShopSettings load() throws SQLException {
        String sql = "SELECT name, address, phone, email, kra_pin, currency, receipt_footer "
                + "FROM shop_settings WHERE id = 1";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ShopSettings(
                        rs.getString("name"), rs.getString("address"), rs.getString("phone"),
                        rs.getString("email"), rs.getString("kra_pin"), rs.getString("currency"),
                        rs.getString("receipt_footer"));
            }
        }
        return new ShopSettings("My Shop", "", "", "", "", "KES", "Thank you for shopping with us.");
    }
}