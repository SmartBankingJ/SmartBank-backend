package ma.bankdirect.dao;

import java.sql.*;
import java.time.Instant;
import ma.bankdirect.auth.AuthIdentity;

/** All SQL for authentication; callers own the connection and transaction. */
public final class AuthRepository {
    public record UserRow(long id, String username, String hash, String role, String status,
            int failures, Instant lockedUntil, boolean mustChange, Long customerId,
            String firstName, String lastName) {
        public AuthIdentity identity() {
            return new AuthIdentity(new AuthIdentity.PublicUser(id, username, role),
                    customerId == null ? null : new AuthIdentity.PublicCustomer(customerId, firstName, lastName), mustChange);
        }
    }
    public record SessionRow(String hash, long userId, Instant lastSeen, Instant expiresAt) {}
    public record ResetRow(String hash, long userId, Instant createdAt, Instant expiresAt) {}

    public UserRow byUsername(Connection c, String username) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM users WHERE username=? FOR UPDATE")) {
            s.setString(1, username);
            try (ResultSet r = s.executeQuery()) { return r.next() ? user(c, r) : null; }
        }
    }
    public UserRow byId(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM users WHERE id=? FOR UPDATE")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? user(c, r) : null; }
        }
    }
    private UserRow user(Connection c, ResultSet r) throws SQLException {
        long value = r.getLong("customer_id"); Long customer = r.wasNull() ? null : value;
        String first = null, last = null;
        if (customer != null) {
            try (PreparedStatement s = c.prepareStatement("SELECT first_name,last_name FROM customers WHERE id=?")) {
                s.setLong(1, customer);
                try (ResultSet cr = s.executeQuery()) { if (cr.next()) { first = cr.getString(1); last = cr.getString(2); } }
            }
        }
        return new UserRow(r.getLong("id"), r.getString("username"), r.getString("password_hash"),
                r.getString("role"), r.getString("status"), r.getInt("failed_attempts"),
                instant(r, "locked_until"), r.getBoolean("must_change_password"), customer, first, last);
    }
    public void failure(Connection c, long id, int attempts, Instant lockedUntil) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE users SET failed_attempts=?,locked_until=? WHERE id=?")) {
            s.setInt(1, attempts); timestamp(s, 2, lockedUntil); s.setLong(3, id); s.executeUpdate();
        }
    }
    public void password(Connection c, long id, String hash, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE users SET password_hash=?,must_change_password=FALSE,failed_attempts=0,locked_until=NULL,password_changed_at=? WHERE id=?")) {
            s.setString(1, hash); timestamp(s,2,now); s.setLong(3,id); s.executeUpdate();
        }
    }
    public void addSession(Connection c, String hash, long id, Instant now, Instant expires) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO auth_sessions(token_hash,user_id,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?)")) {
            s.setString(1,hash);s.setLong(2,id);timestamp(s,3,now);timestamp(s,4,now);timestamp(s,5,expires);s.executeUpdate();
        }
    }
    public SessionRow session(Connection c, String hash, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM auth_sessions WHERE token_hash=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1,hash);
            try (ResultSet r=s.executeQuery()) { return r.next() ? new SessionRow(hash,r.getLong("user_id"),instant(r,"last_seen_at"),instant(r,"expires_at")) : null; }
        }
    }
    public void touch(Connection c, String hash, Instant now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("UPDATE auth_sessions SET last_seen_at=? WHERE token_hash=?")) {
            timestamp(s,1,now);s.setString(2,hash);s.executeUpdate();
        }
    }
    public void deleteSession(Connection c, String hash) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("DELETE FROM auth_sessions WHERE token_hash=?")) { s.setString(1,hash);s.executeUpdate(); }
    }
    public void revokeSessions(Connection c, long userId) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("DELETE FROM auth_sessions WHERE user_id=?")) { s.setLong(1,userId);s.executeUpdate(); }
    }
    public ResetRow reset(Connection c, String hash) throws SQLException {
        return reset(c,hash,false);
    }
    public ResetRow reset(Connection c, String hash, boolean lock) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("SELECT * FROM password_resets WHERE token_hash=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1,hash);
            try (ResultSet r=s.executeQuery()) { return r.next() ? new ResetRow(hash,r.getLong("user_id"),instant(r,"created_at"),instant(r,"expires_at")) : null; }
        }
    }
    public ResetRow resetForUser(Connection c, long userId) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("SELECT * FROM password_resets WHERE user_id=?")) {
            s.setLong(1,userId);
            try (ResultSet r=s.executeQuery()) { return r.next() ? new ResetRow(r.getString("token_hash"),userId,instant(r,"created_at"),instant(r,"expires_at")) : null; }
        }
    }
    public void deleteResets(Connection c, long userId) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("DELETE FROM password_resets WHERE user_id=?")) { s.setLong(1,userId);s.executeUpdate(); }
    }
    public void addReset(Connection c, long userId, String hash, Instant now, Instant expires) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO password_resets(token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)")) {
            s.setString(1,hash);s.setLong(2,userId);timestamp(s,3,now);timestamp(s,4,expires);s.executeUpdate();
        }
    }
    public void audit(Connection c, Long userId, String event, Instant now) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO auth_events(user_id,event_type,created_at) VALUES (?,?,?)")) {
            if(userId==null)s.setNull(1,Types.BIGINT);else s.setLong(1,userId);
            s.setString(2,event);timestamp(s,3,now);s.executeUpdate();
        }
    }
    private static Instant instant(ResultSet r,String name) throws SQLException {
        Timestamp ts=r.getTimestamp(name); return ts==null?null:ts.toInstant();
    }
    private static void timestamp(PreparedStatement s,int index,Instant value) throws SQLException {
        if(value==null)s.setNull(index,Types.TIMESTAMP);else s.setTimestamp(index,Timestamp.from(value));
    }
}
