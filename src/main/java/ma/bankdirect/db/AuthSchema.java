package ma.bankdirect.db;

import java.sql.*;

/** Additive, repeatable migration for an existing bankdirect schema. Never deletes banking data. */
public final class AuthSchema {
    private AuthSchema() {}
    public static void migrate(Connection c) throws SQLException {
        column(c,"locked_until","TIMESTAMP NULL");
        column(c,"must_change_password","BOOLEAN NOT NULL DEFAULT FALSE");
        column(c,"password_changed_at","TIMESTAMP NULL");
        try (Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS advisors (id BIGINT AUTO_INCREMENT PRIMARY KEY,full_name VARCHAR(120) NOT NULL,phone VARCHAR(30),email VARCHAR(120))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY,customer_id BIGINT NOT NULL,title VARCHAR(150) NOT NULL,message VARCHAR(500) NOT NULL,created_at DATETIME NOT NULL,read_at DATETIME NULL,FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE CASCADE)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS auth_sessions (token_hash CHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,last_seen_at TIMESTAMP NOT NULL,expires_at TIMESTAMP NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS password_resets (token_hash CHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL UNIQUE,created_at TIMESTAMP NOT NULL,expires_at TIMESTAMP NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS auth_events (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NULL,event_type VARCHAR(40) NOT NULL,created_at TIMESTAMP NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id))");
        }
    }
    private static void column(Connection c,String name,String definition) throws SQLException {
        try (ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,"users",name)) {
            if(r.next())return;
        }
        try(Statement s=c.createStatement()){ s.executeUpdate("ALTER TABLE users ADD COLUMN " + name + " " + definition); }
    }
}
