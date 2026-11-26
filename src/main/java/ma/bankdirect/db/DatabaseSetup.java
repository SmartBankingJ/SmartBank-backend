package ma.bankdirect.db;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import ma.bankdirect.auth.AuthService;
import ma.bankdirect.config.AppConfig;
import ma.bankdirect.security.PasswordHasher;

/** Explicit developer commands. Starting the HTTP server never imports or overwrites data. */
public final class DatabaseSetup {
    public static void main(String[] args) throws Exception {
        String command=args.length==0?"--check":args[0];
        if(!java.util.Set.of("--check","--migrate","--init-demo").contains(command))
            throw new IllegalArgumentException("Use --check, --migrate or --init-demo");
        try(Connection c=Database.getConnection()) {
            if(command.equals("--check")) {
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT 1")){r.next();}
                System.out.println("Database connection OK.");return;
            }
            if(command.equals("--init-demo")) {
                String password=AppConfig.get("demo.password");
                AuthService.policy(password);
                script(c,"/sql/schema.sql");
                script(c,"/sql/seed.sql");
                AuthSchema.migrate(c);
                try(PreparedStatement query=c.prepareStatement("SELECT password_hash FROM users WHERE username='karim'");ResultSet r=query.executeQuery()) {
                    if(r.next() && r.getString(1).startsWith("pbkdf2-sha256$")) {
                        System.out.println("Existing secured demo user preserved. Use password recovery to change its password.");return;
                    }
                }
                String hash=new PasswordHasher().hash(password);
                try(PreparedStatement s=c.prepareStatement("UPDATE users SET password_hash=?,must_change_password=TRUE,failed_attempts=0,locked_until=NULL WHERE username='karim'")) {
                    s.setString(1,hash);
                    if(s.executeUpdate()==0)try(PreparedStatement insert=c.prepareStatement("INSERT INTO users(username,password_hash,role,status,customer_id,must_change_password) VALUES ('karim',?,'CUSTOMER','ACTIVE',1,TRUE)")) {
                        insert.setString(1,hash);insert.executeUpdate();
                    }
                }
                System.out.println("Demo user karim ready. Temporary password comes from SMARTBANK_DEMO_PASSWORD; change required on first login.");
            } else {AuthSchema.migrate(c);System.out.println("Additive authentication migration completed. Banking records preserved.");}
        } catch(SQLException e) {
            System.err.println("Database setup failed (SQL state: "+e.getSQLState()+"). Check local SMARTBANK_DB_* configuration and database permissions.");
            System.exit(1);
        }
    }
    private static void script(Connection c,String path) throws Exception {
        try(var in=DatabaseSetup.class.getResourceAsStream(path)) {
            if(in==null)throw new IllegalStateException("Missing SQL resource");
            String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$","");
            for(String part:sql.split(";")) {
                String statement=part.trim();
                if(statement.isEmpty() || statement.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE DATABASE")
                        || statement.toUpperCase(java.util.Locale.ROOT).startsWith("USE "))continue;
                try(Statement s=c.createStatement()){s.execute(statement);}
            }
        }
    }
}
