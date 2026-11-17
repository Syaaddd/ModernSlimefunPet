package id.alwination.slimefunpet.database;

import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.stats.PetStatSerializer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Reads and writes pets and player profiles.
 * <p>
 * Pet state lives on the main thread, so a save is two steps: {@link #petRow} and {@link #profileRow}
 * copy the values on the main thread, and {@link #writeRows} writes those copies from an async task.
 * Every other method blocks on the database and is called from an async task or at startup.
 * <p>
 * Booleans are stored as 0/1 and the equipped set as comma-separated UUIDs, which both SQLite and
 * MySQL accept without dialect switches.
 */
public final class PetStorage {

    public static final int SCHEMA_VERSION = 1;

    static final String PETS = "sfp_pets";
    static final String PROFILES = "sfp_players";
    static final String META = "sfp_meta";

    static final List<String> PET_COLUMNS = List.of("uuid", "owner", "type", "level", "exp", "food",
            "last_food_update", "dead", "stay", "skin", "stats", "auto_feed");
    static final List<String> PROFILE_COLUMNS = List.of("owner", "equipped", "hide_pets",
            "afk_progress_ms", "daily_xp", "daily_date");

    private final DatabaseManager database;

    public PetStorage(DatabaseManager database) {
        this.database = database;
    }

    /** Creates the tables when missing and records the schema version. */
    public void createTables() throws SQLException {
        this.database.update("CREATE TABLE IF NOT EXISTS " + META
                + " (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)");
        this.database.update("CREATE TABLE IF NOT EXISTS " + PETS + " ("
                + "uuid VARCHAR(36) PRIMARY KEY, owner VARCHAR(36) NOT NULL, type VARCHAR(64) NOT NULL, "
                + "level INT NOT NULL, exp DOUBLE NOT NULL, food DOUBLE NOT NULL, "
                + "last_food_update BIGINT NOT NULL, dead SMALLINT NOT NULL, stay SMALLINT NOT NULL, "
                + "skin VARCHAR(64), stats TEXT NOT NULL, auto_feed TEXT NOT NULL)");
        this.database.update("CREATE TABLE IF NOT EXISTS " + PROFILES + " ("
                + "owner VARCHAR(36) PRIMARY KEY, equipped TEXT NOT NULL, hide_pets SMALLINT NOT NULL, "
                + "afk_progress_ms BIGINT NOT NULL, daily_xp INT NOT NULL, daily_date VARCHAR(10) NOT NULL)");
        this.database.update(this.database.upsert(META, "meta_key", List.of("meta_key", "meta_value")),
                "schema_version", String.valueOf(SCHEMA_VERSION));
    }

    public List<PetPlayer> loadPets() throws SQLException {
        return this.database.query("SELECT " + String.join(", ", PET_COLUMNS) + " FROM " + PETS,
                PetStorage::readPet);
    }

    public List<PlayerProfile> loadProfiles() throws SQLException {
        return this.database.query("SELECT " + String.join(", ", PROFILE_COLUMNS) + " FROM " + PROFILES,
                PetStorage::readProfile);
    }

    /** Writes copied rows in one transaction: pets, then profiles, then deletes. */
    public void writeRows(List<Object[]> pets, List<Object[]> profiles, List<UUID> deletedPets) throws SQLException {
        if (pets.isEmpty() && profiles.isEmpty() && deletedPets.isEmpty()) {
            return;
        }
        String petSql = this.database.upsert(PETS, "uuid", PET_COLUMNS);
        String profileSql = this.database.upsert(PROFILES, "owner", PROFILE_COLUMNS);
        this.database.inTransaction(connection -> {
            for (Object[] pet : pets) {
                DatabaseManager.execute(connection, petSql, pet);
            }
            for (Object[] profile : profiles) {
                DatabaseManager.execute(connection, profileSql, profile);
            }
            for (UUID uuid : deletedPets) {
                DatabaseManager.execute(connection, "DELETE FROM " + PETS + " WHERE uuid = ?", uuid.toString());
            }
        });
    }

    /** Copies every row into this storage in one transaction; rows with the same key are overwritten. */
    public void importAll(List<PetPlayer> pets, List<PlayerProfile> profiles) throws SQLException {
        List<Object[]> petRows = new ArrayList<>();
        for (PetPlayer pet : pets) {
            petRows.add(petRow(pet));
        }
        List<Object[]> profileRows = new ArrayList<>();
        for (PlayerProfile profile : profiles) {
            profileRows.add(profileRow(profile));
        }
        this.writeRows(petRows, profileRows, List.of());
    }

    /** The pet's values in {@link #PET_COLUMNS} order. Main thread: reads live pet state. */
    public static Object[] petRow(PetPlayer pet) {
        return new Object[] {pet.getUniqueId().toString(), pet.getOwner().toString(), pet.getId(),
            pet.getLevel(), pet.getExp(), pet.getFood(), pet.getLastFoodUpdate(), pet.isDead() ? 1 : 0,
            pet.isStay() ? 1 : 0, pet.getSkin(), PetStatSerializer.serialize(pet.getStats()),
            nullToEmpty(pet.getAutoFeed().toJson())};
    }

    /** The profile's values in {@link #PROFILE_COLUMNS} order. Main thread. */
    public static Object[] profileRow(PlayerProfile profile) {
        StringJoiner equipped = new StringJoiner(",");
        for (UUID uuid : profile.getEquippedPets()) {
            equipped.add(uuid.toString());
        }
        return new Object[] {profile.getOwner().toString(), equipped.toString(),
            profile.isToggleHidePet() ? 1 : 0, profile.getAfkProgressMillis(), profile.getDailyXp(),
            profile.getDailyDate()};
    }

    private static String nullToEmpty(@org.jspecify.annotations.Nullable String text) {
        return text == null ? "" : text;
    }

    private static PetPlayer readPet(ResultSet row) throws SQLException {
        PetPlayer pet = new PetPlayer(UUID.fromString(row.getString("owner")), UUID.fromString(row.getString("uuid")),
                row.getString("type"), row.getInt("level"), (int) row.getDouble("exp"), row.getDouble("food"),
                row.getLong("last_food_update"), row.getInt("dead") != 0,
                PetStatSerializer.deserialize(row.getString("stats")));
        pet.setStay(row.getInt("stay") != 0);
        pet.setSkin(row.getString("skin"));
        String autoFeed = row.getString("auto_feed");
        pet.getAutoFeed().load(autoFeed == null || autoFeed.isEmpty() ? null : autoFeed);
        return pet;
    }

    private static PlayerProfile readProfile(ResultSet row) throws SQLException {
        PlayerProfile profile = new PlayerProfile(UUID.fromString(row.getString("owner")));
        List<UUID> equipped = new ArrayList<>();
        for (String uuid : row.getString("equipped").split(",")) {
            if (!uuid.isBlank()) {
                equipped.add(UUID.fromString(uuid.trim()));
            }
        }
        profile.setEquippedPets(equipped);
        profile.setToggleHidePet(row.getInt("hide_pets") != 0);
        profile.setAfkProgressMillis(row.getLong("afk_progress_ms"));
        profile.setDailyXp(row.getInt("daily_xp"));
        profile.setDailyDate(row.getString("daily_date"));
        return profile;
    }
}
