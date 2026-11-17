package id.alwination.slimefunpet.database;

import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PlayerProfile;
import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * One-time move from SQLite to MySQL, done at startup so no write can fall between the copy and
 * the switch. When the plugin runs on MySQL, the MySQL tables are empty and data.db exists, the
 * rows are copied and the file is renamed to data.db.migrated (kept as a backup, never deleted).
 */
public final class SqliteImporter {

    public static final String MIGRATED_SUFFIX = ".migrated";

    private SqliteImporter() {
    }

    /** Blocking; throws when the copy fails, so the plugin does not start on a half-filled database. */
    public static void importIfNeeded(File dataFolder, PetStorage target, Logger logger) throws SQLException {
        File sqliteFile = new File(dataFolder, DatabaseSettings.SQLITE_FILE);
        if (!sqliteFile.isFile()) {
            return;
        }
        if (!target.loadPets().isEmpty() || !target.loadProfiles().isEmpty()) {
            logger.warning(sqliteFile.getName() + " exists but MySQL already has data, so it was not imported. "
                    + "Move the file away once you are sure MySQL is the right copy.");
            return;
        }
        DatabaseManager source = DatabaseManager.sqlite(sqliteFile);
        try {
            PetStorage sourceStorage = new PetStorage(source);
            sourceStorage.createTables();
            List<PetPlayer> pets = sourceStorage.loadPets();
            List<PlayerProfile> profiles = sourceStorage.loadProfiles();
            target.importAll(pets, profiles);
            logger.info("Imported " + pets.size() + " pets and " + profiles.size() + " profiles from SQLite into MySQL.");
        } finally {
            source.close();
        }
        if (!sqliteFile.renameTo(new File(dataFolder, DatabaseSettings.SQLITE_FILE + MIGRATED_SUFFIX))) {
            logger.warning("Could not rename " + sqliteFile.getName() + "; it will be skipped next start because MySQL now has data.");
        }
    }
}
