package id.alwination.slimefunpet.afk;

import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends one line per AFK XP payout to plugins/ModernSlimefunPet/afk-payouts.log, for auditing alt
 * farming. The line is built on the main thread and written from an async task; writes are
 * serialised so lines never interleave.
 */
public final class AfkPayoutLog {

    private static final Object LOCK = new Object();

    private AfkPayoutLog() {
    }

    public static void append(Path file, String line) {
        Executor.async(() -> {
            synchronized (LOCK) {
                try {
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException exception) {
                    Logger.warning("Could not write the AFK payout log: " + exception.getMessage());
                }
            }
        });
    }
}
