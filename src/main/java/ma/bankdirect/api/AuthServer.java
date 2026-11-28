package ma.bankdirect.api;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import ma.bankdirect.auth.*;
import ma.bankdirect.config.AppConfig;
import ma.bankdirect.db.Database;
import ma.bankdirect.dashboard.DashboardRepository;
import ma.bankdirect.security.PasswordHasher;

/** Local development API. Binding is deliberately restricted to loopback (no remote HTTP passwords). */
public final class AuthServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(4);
    private final AuthService service;
    private final DashboardRepository dashboard=new DashboardRepository(Database::getConnection);
    private final Gson gson=new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Map<String,Window> limits=new LinkedHashMap<>();
    private record Window(long minute,int count) {}

    public AuthServer(AuthService service,int port) throws IOException {
        this.service=service;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
        server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    @Override public void close(){server.stop(0);executor.shutdownNow();}

    private void handle(HttpExchange exchange) throws IOException {
        long started=System.nanoTime();
        try {
            String path=exchange.getRequestURI().getPath();
            String route=exchange.getRequestMethod()+" "+path;
            if("GET /health".equals(route)){reply(exchange,200,Map.of("status","up"));return;}
            if(path.equals("/auth/login") || path.equals("/auth/forgot-password") || path.equals("/auth/reset-password") || path.equals("/auth/change-password")) {
                if(!allow(exchange,path.equals("/auth/login")?30:10))throw new AuthFailure(429,"RATE_LIMITED","Trop de tentatives. Réessayez dans une minute.");
            }
            Object response;
            switch(route) {
                case "POST /auth/login" -> { JsonObject b=body(exchange);response=service.login(text(b,"username"),text(b,"password")); }
                case "GET /auth/me" -> response=service.me(bearer(exchange));
                case "GET /dashboard" -> {
                    AuthIdentity identity=service.me(bearer(exchange));
                    if(!"CUSTOMER".equals(identity.user().role()) || identity.customer()==null)
                        throw new AuthFailure(403,"CUSTOMER_REQUIRED","Cette page est réservée aux clients.");
                    response=dashboard.load(identity.customer().id());
                }
                case "POST /auth/logout" -> {service.logout(bearer(exchange));response=Map.of("message","Déconnexion effectuée.");}
                case "POST /auth/change-password" -> {JsonObject b=body(exchange);response=service.changePassword(bearer(exchange),text(b,"currentPassword"),text(b,"newPassword"));}
                case "POST /auth/forgot-password" -> {
                    JsonObject b=body(exchange);service.requestReset(text(b,"username"));
                    response=Map.of("message","Si un compte correspond, des instructions de récupération seront transmises.","delivery",service.recoveryChannel());
                }
                case "POST /auth/reset-password" -> {
                    JsonObject b=body(exchange);service.resetPassword(text(b,"code"),text(b,"newPassword"));
                    response=Map.of("message","Mot de passe réinitialisé. Reconnectez-vous.");
                }
                default -> throw new AuthFailure(404,"NOT_FOUND","Ressource introuvable.");
            }
            if(path.equals("/auth/forgot-password"))minimumResetTime(started);
            reply(exchange,200,response);
        } catch(AuthFailure e) {
            if(exchange.getRequestURI().getPath().equals("/auth/forgot-password"))minimumResetTime(started);
            reply(exchange,e.status(),Map.of("code",e.code(),"message",e.getMessage()));
        } catch(JsonParseException | IllegalStateException | IllegalArgumentException e) {
            reply(exchange,400,Map.of("code","INVALID_INPUT","message","La requête est invalide."));
        } catch(SQLException e) {
            System.err.println("Authentication database unavailable (SQL state: "+e.getSQLState()+")");
            reply(exchange,503,Map.of("code","SERVICE_UNAVAILABLE","message","Le service de connexion est indisponible. Réessayez plus tard."));
        } catch(Exception e) {
            System.err.println("Authentication request failed: "+e.getClass().getSimpleName());
            reply(exchange,500,Map.of("code","INTERNAL_ERROR","message","Une erreur est survenue. Réessayez plus tard."));
        } finally {exchange.close();}
    }
    private JsonObject body(HttpExchange e) throws IOException {
        String content=e.getRequestHeaders().getFirst("Content-Type");
        if(content==null || !content.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new AuthFailure(415,"CONTENT_TYPE","Une requête JSON est nécessaire.");
        byte[] bytes=e.getRequestBody().readNBytes(8193);
        if(bytes.length>8192)throw new AuthFailure(413,"REQUEST_TOO_LARGE","La requête est trop volumineuse.");
        JsonElement element=gson.fromJson(new String(bytes,StandardCharsets.UTF_8),JsonElement.class);
        if(element==null || !element.isJsonObject())throw new JsonParseException("Object required");
        return element.getAsJsonObject();
    }
    private String text(JsonObject b,String name) {
        JsonElement v=b.get(name);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())throw new JsonParseException("String required");
        return v.getAsString();
    }
    private String bearer(HttpExchange e) {
        String header=e.getRequestHeaders().getFirst("Authorization");
        return header!=null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
    private synchronized boolean allow(HttpExchange e,int maximum) {
        long minute=System.currentTimeMillis()/60000;
        limits.entrySet().removeIf(entry -> entry.getValue().minute()<minute);
        String key=e.getRemoteAddress().getAddress().getHostAddress()+":"+e.getRequestURI().getPath();
        Window old=limits.get(key);
        if(old==null && limits.size()>=10000)return false;
        int count=old==null?1:old.count()+1;limits.put(key,new Window(minute,count));return count<=maximum;
    }
    private static void minimumResetTime(long start) {
        long remaining=250-(System.nanoTime()-start)/1_000_000;
        if(remaining>0)try{Thread.sleep(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    private void reply(HttpExchange e,int status,Object body) throws IOException {
        byte[] bytes=gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control","no-store");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
    }
    public static void main(String[] args) throws Exception {
        RecoveryDelivery delivery=null;
        String outbox=AppConfig.get("recovery.outbox");
        String mode=AppConfig.getOrDefault("recovery.mode",outbox!=null && !outbox.isBlank()?"local":"disabled");
        if("smtp".equals(mode)) {
            delivery=ma.bankdirect.auth.SmtpRecoveryDelivery.fromConfig();
            System.out.println("Recovery delivery: SMTP (email).");
        } else if("local".equals(mode)) {
            if(outbox==null || outbox.isBlank())throw new IllegalArgumentException("SMARTBANK_RECOVERY_OUTBOX required for local recovery");
            Path directory=Path.of(outbox).toAbsolutePath();
            delivery=RecoveryDelivery.localOutbox(directory);
            System.out.println("Development recovery simulation enabled: "+directory);
        } else if(!"disabled".equals(mode))throw new IllegalArgumentException("SMARTBANK_RECOVERY_MODE must be smtp, local or disabled");
        AuthService service=new AuthService(Database::getConnection,new PasswordHasher(),Clock.systemUTC(),delivery);
        AuthServer api=new AuthServer(service,Integer.parseInt(AppConfig.getOrDefault("api.port","8081")));
        Runtime.getRuntime().addShutdownHook(new Thread(api::close));api.start();
        System.out.println("SmartBanking authentication API: http://127.0.0.1:"+api.port());
    }
}
