package ma.bankdirect.auth;

import java.sql.*;
import java.time.*;
import ma.bankdirect.dao.AuthRepository;
import ma.bankdirect.dao.AuthRepository.*;
import ma.bankdirect.security.PasswordHasher;
import ma.bankdirect.auth.AuthIdentity.LoginResponse;

public final class AuthService {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private final Connections connections;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final RecoveryDelivery delivery;
    private final AuthRepository repo=new AuthRepository();
    private final String dummyHash;
    public static final Duration IDLE=Duration.ofMinutes(15), LIFETIME=Duration.ofHours(8);

    public AuthService(Connections connections, PasswordHasher passwords, Clock clock, RecoveryDelivery delivery) {
        this.connections=connections;this.passwords=passwords;this.clock=clock;this.delivery=delivery;
        dummyHash=passwords.hash(PasswordHasher.token());
    }

    public LoginResponse login(String username,String password) throws SQLException {
        if(username==null || username.isBlank() || username.length()>60 || password==null || password.isEmpty() || password.length()>128)
            throw invalidLogin();
        LoginResponse result=transaction(c -> {
            Instant now=clock.instant();
            UserRow user=repo.byUsername(c,username.trim());
            String encoded=user!=null && user.hash().startsWith("pbkdf2-sha256$") ? user.hash() : dummyHash;
            boolean matches=passwords.verify(password,encoded);
            if(user==null){repo.audit(c,null,"LOGIN_FAILED",now);return null;}
            if(!"ACTIVE".equals(user.status()) || (user.lockedUntil()!=null && now.isBefore(user.lockedUntil()))) {
                repo.audit(c,user.id(),"LOGIN_DENIED",now);return null;
            }
            if(!matches || encoded.equals(dummyHash)) {
                int failures=(user.lockedUntil()!=null ? 0 : user.failures())+1;
                repo.failure(c,user.id(),failures,failures>=5 ? now.plus(Duration.ofMinutes(15)) : null);
                repo.audit(c,user.id(),failures>=5 ? "LOGIN_TEMPORARILY_LOCKED" : "LOGIN_FAILED",now);
                return null;
            }
            repo.failure(c,user.id(),0,null);
            repo.audit(c,user.id(),"LOGIN_SUCCEEDED",now);
            return issue(c,user,now);
        });
        if(result==null)throw invalidLogin();
        return result;
    }

    public AuthIdentity me(String token) throws SQLException {
        return transaction(c -> {
            UserRow user=resolve(c,token);
            if(user.mustChange())throw new AuthFailure(403,"PASSWORD_CHANGE_REQUIRED","Modifiez votre mot de passe provisoire avant de continuer.");
            return user.identity();
        });
    }

    public void logout(String token) throws SQLException {
        if(!validToken(token))return;
        transaction(c -> {
            SessionRow session=repo.session(c,PasswordHasher.digest(token),false);
            if(session!=null) {
                repo.byId(c,session.userId());
                repo.deleteSession(c,session.hash());
                repo.audit(c,session.userId(),"LOGOUT",clock.instant());
            }
            return null;
        });
    }

    public LoginResponse changePassword(String token,String current,String replacement) throws SQLException {
        policy(replacement);
        return transaction(c -> {
            UserRow user=resolve(c,token);
            if(current==null || !passwords.verify(current,user.hash()))
                throw new AuthFailure(400,"CURRENT_PASSWORD_INVALID","Le mot de passe actuel est incorrect.");
            if(passwords.verify(replacement,user.hash()))
                throw new AuthFailure(400,"PASSWORD_REUSED","Choisissez un mot de passe différent du précédent.");
            Instant now=clock.instant();
            repo.password(c,user.id(),passwords.hash(replacement),now);
            repo.revokeSessions(c,user.id());repo.deleteResets(c,user.id());
            repo.audit(c,user.id(),"PASSWORD_CHANGED",now);
            return issue(c,repo.byId(c,user.id()),now);
        });
    }

    /** Always returns the same public response for unknown, blocked or throttled users. */
    public void requestReset(String username) throws SQLException {
        if(delivery==null)throw new AuthFailure(503,"RECOVERY_UNAVAILABLE","La récupération des accès n’est pas disponible pour le moment.");
        if(username==null || username.isBlank() || username.length()>60)
            throw new AuthFailure(400,"INVALID_INPUT","Renseignez votre identifiant.");
        transaction(c -> {
            UserRow user=repo.byUsername(c,username.trim());
            if(user==null || !"ACTIVE".equals(user.status()))return null;
            Instant now=clock.instant();
            ResetRow previous=repo.resetForUser(c,user.id());
            if(previous!=null && now.isBefore(previous.createdAt().plusSeconds(60)))return null;
            String token=PasswordHasher.token();
            repo.deleteResets(c,user.id());repo.addReset(c,user.id(),PasswordHasher.digest(token),now,now.plus(Duration.ofMinutes(15)));
            try { delivery.deliver(user.id(),token); }
            catch(java.io.IOException e){throw new AuthFailure(503,"RECOVERY_UNAVAILABLE","La récupération des accès n’est pas disponible pour le moment.");}
            repo.audit(c,user.id(),"PASSWORD_RESET_REQUESTED",now);
            return null;
        });
    }

    public void resetPassword(String token,String replacement) throws SQLException {
        policy(replacement);
        if(!validToken(token))throw invalidReset();
        transaction(c -> {
            String hash=PasswordHasher.digest(token);
            ResetRow initial=repo.reset(c,hash);
            if(initial==null)throw invalidReset();
            // All concurrent password/session mutations acquire the user row first.
            UserRow user=repo.byId(c,initial.userId());
            ResetRow reset=repo.reset(c,hash,true);
            Instant now=clock.instant();
            if(reset==null || !now.isBefore(reset.expiresAt()) || !"ACTIVE".equals(user.status()))throw invalidReset();
            if(passwords.verify(replacement,user.hash()))throw new AuthFailure(400,"PASSWORD_REUSED","Choisissez un mot de passe différent du précédent.");
            repo.password(c,user.id(),passwords.hash(replacement),now);
            repo.revokeSessions(c,user.id());repo.deleteResets(c,user.id());
            repo.audit(c,user.id(),"PASSWORD_RESET_COMPLETED",now);
            return null;
        });
    }

    private UserRow resolve(Connection c,String token) throws SQLException {
        if(!validToken(token))throw expired();
        String hash=PasswordHasher.digest(token);
        SessionRow initial=repo.session(c,hash,false);
        if(initial==null)throw expired();
        UserRow user=repo.byId(c,initial.userId());
        SessionRow session=repo.session(c,hash,true);
        Instant now=clock.instant();
        if(session==null || user==null || !"ACTIVE".equals(user.status()) || !now.isBefore(session.expiresAt())
                || !now.isBefore(session.lastSeen().plus(IDLE)))throw expired();
        repo.touch(c,hash,now);
        return user;
    }

    private LoginResponse issue(Connection c,UserRow user,Instant now) throws SQLException {
        String token=PasswordHasher.token();
        Instant expires=now.plus(user.mustChange()?Duration.ofMinutes(10):LIFETIME);
        repo.addSession(c,PasswordHasher.digest(token),user.id(),now,expires);
        return new LoginResponse(token,expires.toString(),user.identity());
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try(Connection c=connections.open()) {
            c.setAutoCommit(false);
            try { T value=work.run(c);c.commit();return value; }
            catch(SQLException | RuntimeException e){c.rollback();throw e;}
        }
    }
    private static boolean validToken(String token){return token!=null && token.matches("[A-Za-z0-9_-]{43}");}
    public static void policy(String password) {
        if(password==null || password.length()<12 || password.length()>128 || password.isBlank())
            throw new AuthFailure(400,"PASSWORD_POLICY","Utilisez un mot de passe de 12 à 128 caractères.");
    }
    private static AuthFailure invalidLogin(){return new AuthFailure(401,"INVALID_CREDENTIALS","Identifiant ou mot de passe incorrect, ou accès temporairement indisponible.");}
    private static AuthFailure expired(){return new AuthFailure(401,"SESSION_EXPIRED","Votre session a expiré. Reconnectez-vous.");}
    private static AuthFailure invalidReset(){return new AuthFailure(400,"RESET_INVALID","Code de récupération invalide ou expiré.");}
}
