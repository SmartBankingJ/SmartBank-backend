package ma.bankdirect.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import ma.bankdirect.auth.RecoveryDelivery.Recipient;
import ma.bankdirect.auth.SmtpRecoveryDelivery.*;

class SmtpRecoveryDeliveryTest {
    static final String CODE="a".repeat(43);
    static Settings local(int port){return new Settings("127.0.0.1",port,null,null,"bank@example.test",Security.LOCAL_TEST);}

    /** In-process SMTP peer: no real recipient or external network connection. */
    static final class Peer implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final List<String> commands=new CopyOnWriteArrayList<>();
        final Future<String> message;
        Peer(boolean reject) throws IOException {
            listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));listener.setSoTimeout(7000);
            message=worker.submit(()->{
                try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(7000);
                    var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
                    out.print("220 localhost test SMTP\r\n");out.flush();String data="";
                    for(String line;(line=in.readLine())!=null;) {
                        commands.add(line);
                        if(line.startsWith("EHLO") || line.startsWith("HELO"))out.print("250-localhost\r\n250 SIZE 100000\r\n");
                        else if(line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") || line.equals("RSET"))out.print("250 OK\r\n");
                        else if(line.equals("DATA")) {
                            out.print("354 send message\r\n");out.flush();StringBuilder body=new StringBuilder();
                            for(String part;(part=in.readLine())!=null && !part.equals(".");)body.append(part.startsWith("..")?part.substring(1):part).append("\r\n");
                            data=body.toString();out.print(reject?"550 simulated recipient rejection\r\n":"250 accepted\r\n");
                        } else if(line.equals("QUIT")){out.print("221 bye\r\n");out.flush();break;}
                        else out.print("502 unsupported\r\n");
                        out.flush();
                    }
                    return data;
                }
            });
        }
        int port(){return listener.getLocalPort();}
        String received() throws Exception{return message.get(10,TimeUnit.SECONDS);}
        public void close() throws IOException{listener.close();worker.shutdownNow();}
    }

    @Test void deliversMimeMessageToSingleStoredAddress() throws Exception {
        try(Peer peer=new Peer(false)) {
            var delivery=new SmtpRecoveryDelivery(local(peer.port()));
            delivery.deliver(new Recipient(7,"client@example.test"),CODE);
            MimeMessage message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(peer.received().getBytes(StandardCharsets.UTF_8)));
            assertEquals("bank@example.test",message.getFrom()[0].toString());
            assertEquals(1,message.getAllRecipients().length);
            assertEquals("client@example.test",message.getAllRecipients()[0].toString());
            assertTrue(message.getSubject().contains("SmartBanking"));
            assertTrue(message.getContent().toString().contains(CODE));
            assertTrue(message.getContent().toString().contains("15 minutes"));
            assertEquals("email",delivery.channel());
        }
    }
    @Test void starttlsCannotFallBackToPlaintextOrTransmitCredentials() throws Exception {
        try(Peer peer=new Peer(false)) {
            var delivery=new SmtpRecoveryDelivery(new Settings("127.0.0.1",peer.port(),"test-user","test-secret","bank@example.test",Security.STARTTLS));
            IOException error=assertThrows(IOException.class,()->delivery.deliver(new Recipient(1,"client@example.test"),CODE));
            peer.received();
            assertEquals("SMTP delivery failed",error.getMessage());
            assertNull(error.getCause());
            assertFalse(peer.commands.stream().anyMatch(s->s.startsWith("AUTH") || s.startsWith("MAIL")));
        }
    }
    @Test void rejectsHeaderInjectionListsAndMissingAddressBeforeConnecting() {
        var delivery=new SmtpRecoveryDelivery(local(1));
        for(String email:new String[]{"", "bad-address", "a@example.test,b@example.test", "a@example.test\r\nBcc: b@example.test"}) {
            assertFalse(delivery.accepts(new Recipient(1,email)));
            assertThrows(IOException.class,()->delivery.deliver(new Recipient(1,email),CODE));
        }
        assertFalse(delivery.accepts(new Recipient(1,null)));
    }
    @Test void refusesUnsafeConfigurationAndRedactsPasswords() {
        assertThrows(IllegalArgumentException.class,()->new Settings("smtp.example.test",25,null,null,"bank@example.test",Security.LOCAL_TEST));
        assertThrows(IllegalArgumentException.class,()->new Settings("127.0.0.1",25,"user","secret","bank@example.test",Security.LOCAL_TEST));
        assertThrows(IllegalArgumentException.class,()->new Settings("smtp.example.test",587,"user","secret","bank@example.test\r\nBcc: x@example.test",Security.STARTTLS));
        var settings=new Settings("smtp.example.test",465,"user","very-sensitive-test-secret","bank@example.test",Security.TLS);
        assertFalse(settings.toString().contains("very-sensitive-test-secret"));
        var p=new SmtpRecoveryDelivery(settings).properties();
        assertEquals("true",p.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true",p.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("false",p.getProperty("mail.debug"));
        var starttls=new SmtpRecoveryDelivery(new Settings("smtp.example.test",587,"user","secret","bank@example.test",Security.STARTTLS)).properties();
        assertEquals("true",starttls.getProperty("mail.smtp.starttls.required"));
    }
    @Test void providerFailureDoesNotExposeRecipientOrCode() throws Exception {
        try(Peer peer=new Peer(true)) {
            var delivery=new SmtpRecoveryDelivery(local(peer.port()));
            IOException error=assertThrows(IOException.class,()->delivery.deliver(new Recipient(1,"client@example.test"),CODE));
            assertEquals("SMTP delivery failed",error.getMessage());assertNull(error.getCause());
        }
    }
    @Test void diagnosticsClassifyFailuresWithoutRetainingProviderDetails() {
        assertEquals(FailureKind.AUTHENTICATION,SmtpRecoveryDelivery.classify(new jakarta.mail.AuthenticationFailedException("sensitive provider response")));
        assertEquals(FailureKind.CONNECTION,SmtpRecoveryDelivery.classify(new jakarta.mail.MessagingException("connect",new java.net.ConnectException("sensitive host"))));
        assertEquals(FailureKind.TLS,SmtpRecoveryDelivery.classify(new jakarta.mail.MessagingException("handshake",new javax.net.ssl.SSLHandshakeException("sensitive certificate"))));
        assertEquals(FailureKind.REJECTED,SmtpRecoveryDelivery.classify(new jakarta.mail.SendFailedException("sensitive recipient")));
        var error=new DeliveryException(FailureKind.AUTHENTICATION);
        assertEquals("SMTP delivery failed",error.getMessage());assertNull(error.getCause());
    }
}
