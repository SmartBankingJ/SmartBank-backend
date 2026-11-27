package ma.bankdirect.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Delivery boundary. The local outbox is an explicit development simulation, not email. */
@FunctionalInterface
public interface RecoveryDelivery {
    record Recipient(long userId, String email) {}
    void deliver(Recipient recipient, String code) throws IOException;
    default boolean accepts(Recipient recipient) { return true; }
    default String channel() { return "local"; }

    static RecoveryDelivery localOutbox(Path directory) {
        return (recipient, code) -> {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,"reset-",".tmp");
            try {
                Files.writeString(temp,"SIMULATION LOCALE — NE PAS PARTAGER\nCode de récupération : " + code
                        + "\nValable 15 minutes, à usage unique.\n",StandardCharsets.UTF_8);
                Files.move(temp,directory.resolve("user-"+recipient.userId()+".txt"),StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        };
    }
}
