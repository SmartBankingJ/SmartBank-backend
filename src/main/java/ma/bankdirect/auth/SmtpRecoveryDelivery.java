package ma.bankdirect.auth;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import ma.bankdirect.config.AppConfig;

/** SMTP delivery to the customer's stored address; never accepts a recipient from an HTTP request. */
public final class SmtpRecoveryDelivery implements RecoveryDelivery {
    public enum FailureKind { AUTHENTICATION, TLS, CONNECTION, REJECTED, OTHER }
    public static final class DeliveryException extends IOException {
        private final FailureKind kind;
        DeliveryException(FailureKind kind){super("SMTP delivery failed");this.kind=kind;}
        public FailureKind kind(){return kind;}
    }
    static FailureKind classify(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        var pending=new ArrayDeque<Throwable>();pending.add(failure);
        FailureKind result=FailureKind.OTHER;
        while(!pending.isEmpty() && seen.size()<32) {
            Throwable current=pending.remove();if(!seen.add(current))continue;
            if(current instanceof AuthenticationFailedException)return FailureKind.AUTHENTICATION;
            if(current instanceof javax.net.ssl.SSLException || (current instanceof MessagingException
                    && current.getMessage()!=null && current.getMessage().contains("STARTTLS")))result=FailureKind.TLS;
            else if(result==FailureKind.OTHER && (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException || current instanceof java.net.SocketTimeoutException))result=FailureKind.CONNECTION;
            else if(result==FailureKind.OTHER && current instanceof SendFailedException)result=FailureKind.REJECTED;
            if(current.getCause()!=null)pending.add(current.getCause());
            if(current instanceof MessagingException message && message.getNextException()!=null)pending.add(message.getNextException());
        }
        return result;
    }
    public enum Security { STARTTLS, TLS, LOCAL_TEST }
    public record Settings(String host,int port,String username,String password,String from,Security security) {
        public Settings {
            if(host==null || !host.matches("[a-zA-Z0-9.:-]+") || port<1 || port>65535 || security==null || !validAddress(from))
                throw new IllegalArgumentException("Invalid SMTP host, port, sender or security mode");
            if(security==Security.LOCAL_TEST) {
                if(!Set.of("127.0.0.1","localhost","::1").contains(host) || (username!=null && !username.isBlank()) || (password!=null && !password.isBlank()))
                    throw new IllegalArgumentException("LOCAL_TEST permits only unauthenticated loopback SMTP");
            } else if(username==null || username.isBlank() || password==null || password.isBlank())
                throw new IllegalArgumentException("SMTP username and password required");
        }
        // A record's generated toString would leak credentials in error/debug output.
        @Override public String toString(){return "SMTP settings [redacted]";}
    }
    private final Settings settings;
    public SmtpRecoveryDelivery(Settings settings){this.settings=Objects.requireNonNull(settings);}
    public static SmtpRecoveryDelivery fromConfig(){return from(AppConfig::get);}
    static SmtpRecoveryDelivery from(Function<String,String> config) {
        String security=config.apply("smtp.security");
        Security mode;
        try {mode=Security.valueOf(security==null?"STARTTLS":security.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("SMTP security must be starttls, tls or local_test");}
        String port=config.apply("smtp.port");
        int number;
        try {number=port==null?(mode==Security.TLS?465:587):Integer.parseInt(port);}
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid SMTP port");}
        return new SmtpRecoveryDelivery(new Settings(config.apply("smtp.host"),number,config.apply("smtp.user"),
                config.apply("smtp.password"),config.apply("smtp.from"),mode));
    }
    @Override public String channel(){return "email";}
    @Override public boolean accepts(Recipient recipient){return recipient!=null && validAddress(recipient.email());}
    static boolean validAddress(String value) {
        if(value==null || value.length()>254 || value.indexOf('\r')>=0 || value.indexOf('\n')>=0 || !value.matches("[^\\s@<>]+@[^\\s@<>]+"))return false;
        try {InternetAddress address=new InternetAddress(value,true);address.validate();return address.getPersonal()==null;}
        catch(AddressException e){return false;}
    }
    Properties properties() {
        Properties p=new Properties();
        p.setProperty("mail.smtp.auth",Boolean.toString(settings.security()!=Security.LOCAL_TEST));
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.ssl.protocols","TLSv1.3 TLSv1.2");
        p.setProperty("mail.smtp.connectiontimeout","3000");
        p.setProperty("mail.smtp.timeout","5000");
        p.setProperty("mail.smtp.writetimeout","5000");
        p.setProperty("mail.smtp.quitwait","false");
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(settings.security()==Security.STARTTLS));
        p.setProperty("mail.smtp.starttls.required",Boolean.toString(settings.security()==Security.STARTTLS));
        p.setProperty("mail.smtp.ssl.enable",Boolean.toString(settings.security()==Security.TLS));
        p.setProperty("mail.debug","false");
        return p;
    }
    @Override public void deliver(Recipient recipient,String code) throws IOException {
        if(!accepts(recipient) || code==null || !code.matches("[A-Za-z0-9_-]{43}"))throw new IOException("Invalid recovery message");
        Session session=Session.getInstance(properties());
        session.setDebug(false);
        try {
            MimeMessage message=new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.from()));
            message.setRecipient(Message.RecipientType.TO,new InternetAddress(recipient.email()));
            message.setSubject("SmartBanking - Recuperation de votre acces",StandardCharsets.UTF_8.name());
            message.setText("Bonjour,\n\nUne demande de reinitialisation du mot de passe SmartBanking a ete effectuee.\n\n"
                    +"Votre code de recuperation :\n"+code+"\n\nCe code expire apres 15 minutes et ne peut etre utilise qu'une fois.\n"
                    +"Collez-le dans l'application SmartBanking pour choisir un nouveau mot de passe.\n"
                    +"Ne communiquez ce code a personne. Si vous n'etes pas a l'origine de cette demande, ignorez cet e-mail.\n",
                    StandardCharsets.UTF_8.name());
            message.setSentDate(new Date());
            message.saveChanges();
            try(Transport transport=session.getTransport("smtp")) {
                transport.connect(settings.host(),settings.port(),settings.username(),settings.password());
                transport.sendMessage(message,message.getAllRecipients());
            }
        } catch(MessagingException e) {
            // Provider errors may contain recipient addresses or authentication details: don't propagate them.
            throw new DeliveryException(classify(e));
        }
    }
}
