package ma.bankdirect.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Delivery boundary. The local outbox is an explicit development simulation, not email. */
@FunctionalInterface
public interface RecoveryDelivery {
    void deliver(long userId, String code) throws IOException;

    static RecoveryDelivery localOutbox(Path directory) {
        return (id, code) -> {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,"reset-",".tmp");
            try {
                Files.writeString(temp,"SIMULATION LOCALE — NE PAS PARTAGER\nCode de récupération : " + code
                        + "\nValable 15 minutes, à usage unique.\n",StandardCharsets.UTF_8);
                Files.move(temp,directory.resolve("user-"+id+".txt"),StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        };
    }
}
