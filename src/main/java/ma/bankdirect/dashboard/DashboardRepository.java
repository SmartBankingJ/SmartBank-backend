package ma.bankdirect.dashboard;

import java.sql.*;
import java.util.*;

/** Read-only dashboard projection. The customer id always comes from the authenticated session. */
public final class DashboardRepository {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;

    public DashboardRepository(Connections connections) { this.connections=connections; }

    public Map<String,Object> load(long customerId) throws SQLException {
        try(Connection c=connections.open()) {
            List<Map<String,Object>> accounts=accounts(c,customerId);
            List<Map<String,Object>> transactions=transactions(c,customerId);
            int pending=pendingCount(c,customerId);
            Map<String,Object> notifications=notifications(c,customerId);
            Map<String,Object> advisor=advisor(c,customerId);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("accounts",accounts);
            result.put("recentTransactions",transactions);
            result.put("pendingRequests",Map.of("count",pending,"message",pending==0
                    ? "Aucun virement en attente pour le moment."
                    : pending+" demande(s) en attente de traitement."));
            result.put("notifications",notifications);
            result.put("advisor",advisor);
            return result;
        }
    }

    private List<Map<String,Object>> accounts(Connection c,long customerId) throws SQLException {
        String sql="SELECT id,type,rib,currency,balance,status FROM accounts WHERE customer_id=? "
                +"ORDER BY CASE type WHEN 'CURRENT' THEN 0 WHEN 'SAVINGS' THEN 1 ELSE 2 END,id";
        List<Map<String,Object>> rows=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setLong(1,customerId);
            try(ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    String type=r.getString("type");
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",r.getLong("id"));
                    row.put("type",type);
                    row.put("label",accountLabel(type));
                    row.put("rib",maskRib(r.getString("rib")));
                    row.put("currency",r.getString("currency"));
                    row.put("balance",r.getBigDecimal("balance"));
                    row.put("status",r.getString("status"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private List<Map<String,Object>> transactions(Connection c,long customerId) throws SQLException {
        String sql="SELECT t.id,t.account_id,t.type,t.direction,t.amount,t.label,t.status,t.created_at,a.currency "
                +"FROM transactions t JOIN accounts a ON a.id=t.account_id "
                +"WHERE a.customer_id=? AND t.status='COMPLETED' "
                +"ORDER BY t.created_at DESC,t.id DESC LIMIT 3";
        List<Map<String,Object>> rows=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setLong(1,customerId);
            try(ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    Timestamp timestamp=r.getTimestamp("created_at");
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",r.getLong("id"));
                    row.put("accountId",r.getLong("account_id"));
                    row.put("type",r.getString("type"));
                    row.put("direction",r.getString("direction"));
                    row.put("amount",r.getBigDecimal("amount"));
                    row.put("label",r.getString("label"));
                    row.put("status",r.getString("status"));
                    row.put("createdAt",timestamp==null?null:timestamp.toLocalDateTime().toString());
                    row.put("currency",r.getString("currency"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private int pendingCount(Connection c,long customerId) throws SQLException {
        String sql="SELECT COUNT(*) FROM transactions t JOIN accounts a ON a.id=t.account_id "
                +"WHERE a.customer_id=? AND t.status='PENDING'";
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setLong(1,customerId);
            try(ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    private Map<String,Object> notifications(Connection c,long customerId) throws SQLException {
        int unread;
        try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM notifications WHERE customer_id=? AND read_at IS NULL")) {
            s.setLong(1,customerId);
            try(ResultSet r=s.executeQuery()) { r.next(); unread=r.getInt(1); }
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("unreadCount",unread);
        result.put("badge",unread==0?"À jour":Integer.toString(unread));
        if(unread==0) result.put("message","Vous êtes à jour. Aucune nouvelle notification.");
        else {
            try(PreparedStatement s=c.prepareStatement("SELECT message FROM notifications WHERE customer_id=? AND read_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 1")) {
                s.setLong(1,customerId);
                try(ResultSet r=s.executeQuery()) {
                    result.put("message",r.next()?r.getString(1):unread+" notification(s) non lue(s).");
                }
            }
        }
        return result;
    }

    private Map<String,Object> advisor(Connection c,long customerId) throws SQLException {
        String sql="SELECT a.full_name,a.phone,a.email FROM customers c LEFT JOIN advisors a ON a.id=c.advisor_id WHERE c.id=?";
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setLong(1,customerId);
            try(ResultSet r=s.executeQuery()) {
                if(!r.next()) return Map.of("name","Conseiller non renseigné","phone","","email","");
                String name=r.getString("full_name");
                return Map.of("name",name==null?"Conseiller non renseigné":name,
                        "phone",Objects.toString(r.getString("phone"),""),
                        "email",Objects.toString(r.getString("email"),""));
            }
        }
    }

    private static String accountLabel(String type) {
        return switch(Objects.toString(type,"")) {
            case "CURRENT" -> "Compte chèque particulier";
            case "SAVINGS" -> "Compte sur carnet";
            default -> "Compte bancaire";
        };
    }
    private static String maskRib(String rib) {
        if(rib==null || rib.length()<8) return "RIB indisponible";
        return rib.substring(0,4)+" ... "+rib.substring(rib.length()-4);
    }
}
