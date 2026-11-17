package id.alwination.slimefunpet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every "messages.x" key the code sends exists in pets.yml (all messages live there). MDLib skips a
 * missing key silently, which is how ModernMineGens shipped messages nobody ever saw.
 */
class MessageKeysTest {

    private static final Pattern KEY = Pattern.compile("\"(messages\\.[a-z0-9-]+)\"");

    @Test
    void everySentMessageKeyIsShipped() throws Exception {
        YamlConfiguration pets = new YamlConfiguration();
        pets.load(new File("src/main/resources/pets.yml"));

        Set<String> missing = new TreeSet<>();
        for (String key : sentKeys()) {
            if (!pets.isSet(key)) {
                missing.add(key);
            }
        }

        assertThat(missing).isEmpty();
    }

    private static Set<String> sentKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        assertThat(keys).isNotEmpty();
        return keys;
    }
}
