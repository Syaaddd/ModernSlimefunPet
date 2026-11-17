package id.alwination.slimefunpet.database;

import com.muhammaddaffa.mdlib.utils.Config;
import java.io.File;

/** Builds the pools from the database section of config.yml. */
public final class DatabaseSettings {

    public static final String SQLITE_FILE = "data.db";

    private DatabaseSettings() {
    }

    /** The database the plugin runs on, chosen by database.type. */
    public static DatabaseManager openConfigured(Config config, File dataFolder) {
        String type = config.getString("database.type", "sqlite");
        if ("mysql".equalsIgnoreCase(type)) {
            return openMysql(config);
        }
        if ("sqlite".equalsIgnoreCase(type)) {
            return openSqlite(dataFolder);
        }
        // A typo must not silently fall back to SQLite: after a move to MySQL that would split the data.
        throw new IllegalArgumentException("config.yml database.type must be sqlite or mysql, not '" + type + "'");
    }

    public static DatabaseManager openSqlite(File dataFolder) {
        return DatabaseManager.sqlite(new File(dataFolder, SQLITE_FILE));
    }

    /** The MySQL pool described in the mysql section. */
    public static DatabaseManager openMysql(Config config) {
        return DatabaseManager.mysql(
                config.getString("database.mysql.host", "localhost"),
                config.getInt("database.mysql.port", 3306),
                config.getString("database.mysql.database", "modernslimefunpet"),
                config.getString("database.mysql.username", "root"),
                config.getString("database.mysql.password", ""),
                config.getBoolean("database.mysql.use-ssl"),
                Math.max(1, config.getInt("database.mysql.pool-size", 10)));
    }
}
