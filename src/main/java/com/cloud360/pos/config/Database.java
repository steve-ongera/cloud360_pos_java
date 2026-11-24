package com.cloud360.pos.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class Database {

    private static final Properties PROPS = new Properties();

    static {
        try {
            // 1) a db.properties next to the jar / in the working folder wins
            Path external = Paths.get("db.properties");
            if (Files.isRegularFile(external)) {
                try (InputStream in = Files.newInputStream(external)) {
                    PROPS.load(in);
                }
            } else {
                // 2) otherwise use the one bundled in src/main/resources
                try (InputStream in = Database.class.getResourceAsStream("/db.properties")) {
                    if (in == null) {
                        throw new IllegalStateException(
                                "db.properties not found (put it in src/main/resources or next to the jar)");
                    }
                    PROPS.load(in);
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Database() {}

    /** Opens a new connection. Always use it in try-with-resources. */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                PROPS.getProperty("db.url"),
                PROPS.getProperty("db.user"),
                PROPS.getProperty("db.password"));
    }
}