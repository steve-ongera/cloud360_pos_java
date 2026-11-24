package com.cloud360.pos.dao;

import com.cloud360.pos.config.Database;
import com.cloud360.pos.model.Role;
import com.cloud360.pos.model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class UserDao {

    private UserDao() {}

    /** Returns the user if the email-or-username and password match, otherwise null. */
    public static User authenticate(String login, String password) throws SQLException {
        String sql = "SELECT * FROM users "
                + "WHERE (LOWER(username) = ? OR LOWER(email) = ?) AND is_active = 1";
        String key = login.trim().toLowerCase();

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (BCrypt.checkpw(password, rs.getString("password_hash"))) {
                        return map(rs);
                    }
                }
            }
        }
        return null;
    }

    /** Active staff, A-Z by name. Used by filter dropdowns. */
    public static List<User> listStaff() throws SQLException {
        List<User> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM users WHERE is_active = 1 ORDER BY full_name, username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return out;
    }

    /** Creates admin / admin123 when the users table is empty. Change this password after first login. */
    public static void ensureDefaultAdmin() throws SQLException {
        try (Connection c = Database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
            String sql = "INSERT INTO users (username, email, full_name, password_hash, role) "
                    + "VALUES (?, ?, ?, ?, 'SUPER_ADMIN')";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, "admin");
                ps.setString(2, "admin@cloud360.local");
                ps.setString(3, "System Admin");
                ps.setString(4, BCrypt.hashpw("admin123", BCrypt.gensalt(10)));
                ps.executeUpdate();
            }
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setFullName(rs.getString("full_name"));
        u.setPhone(rs.getString("phone"));
        u.setRole(Role.valueOf(rs.getString("role")));
        u.setActive(rs.getBoolean("is_active"));
        return u;
    }
}