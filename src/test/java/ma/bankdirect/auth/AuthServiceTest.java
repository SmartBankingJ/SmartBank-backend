package ma.bankdirect.auth;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import ma.bankdirect.api.AuthServer;
import ma.bankdirect.db.AuthSchema;
import ma.bankdirect.security.PasswordHasher;
import java.net.*;
import java.net.http.*;
import com.google.gson.*;

class AuthServiceTest {
    static final String PASSWORD="Test-only-password-2026!", NEXT="Different-test-password-2026!";
    static final PasswordHasher HASHER=new PasswordHasher();
    static final String HASH=HASHER.hash(PASSWORD);
    String url;
    String mysqlServer, mysqlSchema, databaseUser="sa", databasePassword="";
    MutableClock clock;
    AuthService service;
    AtomicReference<String> delivered;
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-26T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(Duration time){now=now.plus(time);}
    }
    Connection open() throws SQLException{return DriverManager.getConnection(url,databaseUser,databasePassword);}
    @BeforeEach void setup() throws Exception {
        mysqlServer=System.getenv("SMARTBANK_MYSQL_TEST_SERVER");
        if(mysqlServer!=null && !mysqlServer.isBlank()) {
            if(!mysqlServer.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"))
                throw new IllegalArgumentException("SMARTBANK_MYSQL_TEST_SERVER must be a local server URL ending in / without a database or parameters");
            databaseUser=Objects.requireNonNull(System.getenv("SMARTBANK_MYSQL_TEST_USER"),"SMARTBANK_MYSQL_TEST_USER required");
            databasePassword=System.getenv().getOrDefault("SMARTBANK_MYSQL_TEST_PASSWORD", "");
            mysqlSchema="smartbank_test_"+UUID.randomUUID().toString().replace("-", "");
            try(Connection c=DriverManager.getConnection(mysqlServer,databaseUser,databasePassword);Statement s=c.createStatement()) {
                s.execute("CREATE DATABASE "+mysqlSchema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            url=mysqlServer+mysqlSchema+"?connectTimeout=5000&socketTimeout=20000&serverTimezone=UTC";
        } else url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        clock=new MutableClock();delivered=new AtomicReference<>();
        try(Connection c=open();Statement s=c.createStatement()) {
            s.execute("CREATE TABLE customers(id BIGINT PRIMARY KEY,first_name VARCHAR(80),last_name VARCHAR(80))");
            s.execute("INSERT INTO customers VALUES (1,'Karim','Alami')");
            s.execute("CREATE TABLE users(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(60) UNIQUE NOT NULL,password_hash VARCHAR(255) NOT NULL,role VARCHAR(20) NOT NULL,status VARCHAR(20) NOT NULL,failed_attempts INT NOT NULL DEFAULT 0,customer_id BIGINT NULL)");
            AuthSchema.migrate(c);AuthSchema.migrate(c);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO users(id,username,password_hash,role,status,customer_id) VALUES (1,'karim',?,'CUSTOMER','ACTIVE',1)")) {p.setString(1,HASH);p.executeUpdate();}
        }
        service=new AuthService(this::open,HASHER,clock,(id,code)->delivered.set(code));
    }
    @AfterEach void closeDatabase() throws Exception {
        if(mysqlSchema!=null) {
            // Only the randomly generated schema owned by this test is removed; never the configured application database.
            try(Connection c=DriverManager.getConnection(mysqlServer,databaseUser,databasePassword);Statement s=c.createStatement()) {
                s.execute("DROP DATABASE "+mysqlSchema);
            }
        } else if(url!=null)try(Connection c=open();Statement s=c.createStatement()){s.execute("SHUTDOWN");}
    }
    AuthFailure failure(RunnableSql action) {return assertThrows(AuthFailure.class,action::run);}
    @FunctionalInterface interface RunnableSql {void run() throws Exception;}
    void sql(String sql) throws Exception {try(Connection c=open();Statement s=c.createStatement()){s.execute(sql);}}
    int count(String table) throws Exception {try(Connection c=open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}}

    @Test void saltedPasswordsAndNoLegacyPlaintext() {
        String hash=HASHER.hash(PASSWORD);
        assertNotEquals(HASH,hash);assertTrue(HASHER.verify(PASSWORD,hash));
        assertFalse(HASHER.verify("wrong",hash));assertFalse(HASHER.verify("123456","123456"));
        assertFalse(HASHER.verify(PASSWORD,"pbkdf2-sha256$999999999$bad$bad"));
    }
    @Test void loginUsesPublicDtosAndHashedPersistentTokens() throws Exception {
        var login=service.login("karim",PASSWORD);
        assertEquals("Karim",login.identity().customer().firstName());
        assertEquals("CUSTOMER",login.identity().user().role());
        String json=new Gson().toJson(login);assertFalse(json.contains("passwordHash"));assertFalse(json.contains(HASH));
        assertEquals(1,count("auth_sessions"));
        try(Connection c=open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT token_hash FROM auth_sessions")) {
            r.next();assertNotEquals(login.token(),r.getString(1));assertEquals(64,r.getString(1).length());
        }
        assertEquals(1,service.me(login.token()).user().id());
    }
    @Test void genericFailuresAndPersistentTemporaryLock() throws Exception {
        String unknown=failure(()->service.login("absent",PASSWORD)).getMessage();
        for(int i=0;i<5;i++)assertEquals(unknown,failure(()->service.login("karim","incorrect")).getMessage());
        service=new AuthService(this::open,HASHER,clock,(id,code)->delivered.set(code));
        assertEquals(unknown,failure(()->service.login("karim",PASSWORD)).getMessage());
        clock.advance(Duration.ofMinutes(15));assertNotNull(service.login("karim",PASSWORD).token());
    }
    @Test void logoutRevokesServerSession() throws Exception {
        String token=service.login("karim",PASSWORD).token();service.logout(token);service.logout(token);
        assertEquals("SESSION_EXPIRED",failure(()->service.me(token)).code());assertEquals(0,count("auth_sessions"));
    }
    @Test void idleExpiresExactlyAtFifteenMinutes() throws Exception {
        String token=service.login("karim",PASSWORD).token();clock.advance(Duration.ofMinutes(15));
        assertEquals(401,failure(()->service.me(token)).status());
    }
    @Test void activityRenewsIdleButNotAbsoluteLifetime() throws Exception {
        String token=service.login("karim",PASSWORD).token();
        for(int i=0;i<34;i++){clock.advance(Duration.ofMinutes(14));service.me(token);}
        clock.advance(Duration.ofMinutes(5));assertEquals(401,failure(()->service.me(token)).status());
    }
    @Test void disabledUserCannotReuseSession() throws Exception {
        String token=service.login("karim",PASSWORD).token();sql("UPDATE users SET status='LOCKED' WHERE id=1");
        assertEquals(401,failure(()->service.me(token)).status());failure(()->service.login("karim",PASSWORD));
    }
    @Test void firstLoginRestrictedUntilChangeAndRotatesAllSessions() throws Exception {
        sql("UPDATE users SET must_change_password=TRUE WHERE id=1");
        var login=service.login("karim",PASSWORD);assertTrue(login.identity().mustChangePassword());
        assertEquals("PASSWORD_CHANGE_REQUIRED",failure(()->service.me(login.token())).code());
        failure(()->service.changePassword(login.token(),"wrong",NEXT));
        var changed=service.changePassword(login.token(),PASSWORD,NEXT);
        assertFalse(changed.identity().mustChangePassword());assertEquals(1,service.me(changed.token()).user().id());
        failure(()->service.me(login.token()));failure(()->service.login("karim",PASSWORD));
        assertNotNull(service.login("karim",NEXT));
    }
    @Test void resetDoesNotDiscloseUnknownUserAndIsSingleUse() throws Exception {
        service.requestReset("absent");assertNull(delivered.get());
        String oldSession=service.login("karim",PASSWORD).token();service.requestReset("karim");
        String code=delivered.get();assertNotNull(code);
        service.requestReset("karim");assertEquals(code,delivered.get());
        service.resetPassword(code,NEXT);failure(()->service.resetPassword(code,PASSWORD));
        failure(()->service.me(oldSession));failure(()->service.login("karim",PASSWORD));assertNotNull(service.login("karim",NEXT));
    }
    @Test void expiredAndReplacedResetCodesAreRejected() throws Exception {
        service.requestReset("karim");String first=delivered.get();
        clock.advance(Duration.ofSeconds(61));service.requestReset("karim");String second=delivered.get();
        assertNotEquals(first,second);failure(()->service.resetPassword(first,NEXT));
        clock.advance(Duration.ofMinutes(15));failure(()->service.resetPassword(second,NEXT));
        assertNotNull(service.login("karim",PASSWORD));
    }
    @Test void weakPasswordsAndSqlInjectionAreRejected() throws Exception {
        assertEquals("PASSWORD_POLICY",failure(()->service.changePassword("bad",PASSWORD,"short")).code());
        failure(()->service.login("karim' OR '1'='1",PASSWORD));
        assertNotNull(service.login("karim",PASSWORD));
    }
    @Test void concurrentResetIsConsumedOnlyOnce() throws Exception {
        service.requestReset("karim");String code=delivered.get();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first=()->{try{service.resetPassword(code,NEXT);return true;}catch(AuthFailure e){return false;}};
            Callable<Boolean> second=()->{try{service.resetPassword(code,"Another-test-password-2026!");return true;}catch(AuthFailure e){return false;}};
            var futures=executor.invokeAll(List.of(first,second));
            int success=0;for(var future:futures)if(future.get())success++;
            assertEquals(1,success);
        } finally{executor.shutdownNow();}
    }
    @Test void concurrentFailuresDoNotLoseCounterUpdates() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(5);
        try {
            List<Callable<Boolean>> calls=new ArrayList<>();
            for(int i=0;i<5;i++)calls.add(()->{try{service.login("karim","wrong");return false;}catch(AuthFailure e){return true;}});
            for(var result:executor.invokeAll(calls))assertTrue(result.get());
            failure(()->service.login("karim",PASSWORD));
        } finally{executor.shutdownNow();}
    }
    @Test void recoveryUnavailableFailsExplicitlyWithoutIssuingCode() throws Exception {
        AuthService disabled=new AuthService(this::open,HASHER,clock,null);
        assertEquals(503,failure(()->disabled.requestReset("karim")).status());
        assertEquals(0,count("password_resets"));
    }
    @Test void sessionsSurviveServiceRestartButNotPasswordChange() throws Exception {
        String first=service.login("karim",PASSWORD).token();String second=service.login("karim",PASSWORD).token();
        service=new AuthService(this::open,HASHER,clock,(id,code)->delivered.set(code));
        service.me(first);
        String replacement=service.changePassword(first,PASSWORD,NEXT).token();
        failure(()->service.me(first));failure(()->service.me(second));service.me(replacement);
    }
    @Test void deliveryFailureRollsBackResetRequest() throws Exception {
        AuthService unavailable=new AuthService(this::open,HASHER,clock,(id,code)->{throw new java.io.IOException("simulated");});
        assertEquals(503,failure(()->unavailable.requestReset("karim")).status());assertEquals(0,count("password_resets"));
    }
    @Test void httpRateLimitIsEnforced() throws Exception {
        try(AuthServer api=new AuthServer(service,0)) {
            api.start();HttpClient client=HttpClient.newHttpClient();URI uri=URI.create("http://127.0.0.1:"+api.port()+"/auth/login");
            for(int i=0;i<30;i++)assertEquals(415,client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(429,client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void httpLoginMeLogoutAndMalformedRequests() throws Exception {
        try(AuthServer api=new AuthServer(service,0)) {
            api.start();HttpClient client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+api.port();
            HttpRequest login=HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(Map.of("username","karim","password",PASSWORD)))).build();
            var response=client.send(login,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
            String token=JsonParser.parseString(response.body()).getAsJsonObject().get("token").getAsString();
            var me=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/me")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,me.statusCode());assertFalse(me.body().contains("password"));
            var missing=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/me")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,missing.statusCode());
            var malformed=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{bad}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(400,malformed.statusCode());
            var large=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("x".repeat(9000))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(413,large.statusCode());
            client.send(HttpRequest.newBuilder(URI.create(base+"/auth/logout")).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            failure(()->service.me(token));
        }
    }
}
