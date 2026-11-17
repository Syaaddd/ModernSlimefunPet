package id.alwination.slimefunpet.database;

import static org.assertj.core.api.Assertions.assertThat;

import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.stats.PetStat;
import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PetStorageTest {

    @TempDir
    Path folder;

    private DatabaseManager database;
    private PetStorage storage;

    @BeforeEach
    void open() throws Exception {
        this.database = DatabaseManager.sqlite(this.folder.resolve("a.db").toFile());
        this.storage = new PetStorage(this.database);
        this.storage.createTables();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private static PetPlayer pet(UUID owner) {
        PetPlayer pet = new PetPlayer(owner, UUID.randomUUID(), "common_pet", 7, 12, 25.25,
                1_700_000_000_000L, true, List.of(new PetStat("armor", 1.5)));
        pet.setStay(true);
        pet.setSkin("redpanda");
        return pet;
    }

    @Test
    void petSurvivesSaveAndLoad() throws Exception {
        PetPlayer saved = pet(UUID.randomUUID());
        this.storage.importAll(List.of(saved), List.of());

        PetPlayer loaded = this.storage.loadPets().get(0);

        assertThat(loaded.getUniqueId()).isEqualTo(saved.getUniqueId());
        assertThat(loaded.getOwner()).isEqualTo(saved.getOwner());
        assertThat(loaded.getId()).isEqualTo("common_pet");
        assertThat(loaded.getLevel()).isEqualTo(7);
        assertThat(loaded.getExp()).isEqualTo(12);
        assertThat(loaded.getFood()).isEqualTo(25.25);
        assertThat(loaded.getLastFoodUpdate()).isEqualTo(1_700_000_000_000L);
        assertThat(loaded.isDead()).isTrue();
        assertThat(loaded.isStay()).isTrue();
        assertThat(loaded.getSkin()).isEqualTo("redpanda");
        assertThat(loaded.getStats()).extracting(PetStat::key).containsExactly("armor");
        assertThat(loaded.getStats().get(0).value()).isEqualTo(1.5);
    }

    @Test
    void savingTwiceUpdatesTheSameRowAndKeepsNullSkin() throws Exception {
        PetPlayer saved = pet(UUID.randomUUID());
        this.storage.importAll(List.of(saved), List.of());
        saved.setLevel(8);
        saved.setSkin(null);
        this.storage.writeRows(List.<Object[]>of(PetStorage.petRow(saved)), List.of(), List.of());

        List<PetPlayer> loaded = this.storage.loadPets();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getLevel()).isEqualTo(8);
        assertThat(loaded.get(0).getSkin()).isNull();
    }

    @Test
    void deletedPetIsGoneEvenWhenSavedInTheSameBatch() throws Exception {
        PetPlayer saved = pet(UUID.randomUUID());
        this.storage.importAll(List.of(saved), List.of());

        this.storage.writeRows(List.<Object[]>of(PetStorage.petRow(saved)), List.of(), List.of(saved.getUniqueId()));

        assertThat(this.storage.loadPets()).isEmpty();
    }

    @Test
    void profileKeepsEquippedOrderAndDailyCap() throws Exception {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        profile.equipPet(first);
        profile.equipPet(second);
        profile.setToggleHidePet(true);
        profile.setAfkProgressMillis(1_234_567L);
        profile.setDailyXp(90);
        profile.setDailyDate("2026-11-17");
        this.storage.importAll(List.of(), List.of(profile));

        PlayerProfile loaded = this.storage.loadProfiles().get(0);

        assertThat(loaded.getEquippedPets()).containsExactly(first, second);
        assertThat(loaded.isToggleHidePet()).isTrue();
        assertThat(loaded.getAfkProgressMillis()).isEqualTo(1_234_567L);
        assertThat(loaded.getDailyXp()).isEqualTo(90);
        assertThat(loaded.getDailyDate()).isEqualTo("2026-11-17");
    }

    @Test
    void emptyEquippedSetLoadsEmpty() throws Exception {
        this.storage.importAll(List.of(), List.of(new PlayerProfile(UUID.randomUUID())));

        assertThat(this.storage.loadProfiles().get(0).getEquippedPets()).isEmpty();
    }

    @Test
    void importCopiesEverythingAndIsSafeToRunTwice() throws Exception {
        PetPlayer saved = pet(UUID.randomUUID());
        PlayerProfile profile = new PlayerProfile(saved.getOwner());
        profile.equipPet(saved.getUniqueId());
        this.storage.importAll(List.of(saved), List.of(profile));

        DatabaseManager other = DatabaseManager.sqlite(this.folder.resolve("b.db").toFile());
        try {
            PetStorage target = new PetStorage(other);
            target.createTables();
            target.importAll(this.storage.loadPets(), this.storage.loadProfiles());
            target.importAll(this.storage.loadPets(), this.storage.loadProfiles());

            assertThat(target.loadPets()).hasSize(1);
            assertThat(target.loadProfiles().get(0).getEquippedPets()).containsExactly(saved.getUniqueId());
        } finally {
            other.close();
        }
    }

    @Test
    void mysqlUpsertUsesDuplicateKeySyntaxAndSqliteUsesConflict() {
        List<String> columns = List.of("id", "value");

        assertThat(DatabaseType.MYSQL.upsert("t", "id", columns))
                .isEqualTo("INSERT INTO t (id, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)");
        assertThat(DatabaseType.SQLITE.upsert("t", "id", columns))
                .isEqualTo("INSERT INTO t (id, value) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET value = excluded.value");
    }

    @Test
    void sqliteRunsInWalMode() throws Exception {
        assertThat(this.database.query("PRAGMA journal_mode", row -> row.getString(1))).containsExactly("wal");
    }

    @Test
    void importerCopiesOnceAndRenamesTheFile() throws Exception {
        PetPlayer saved = pet(UUID.randomUUID());
        this.storage.importAll(List.of(saved), List.of());
        this.database.close();
        File dataFolder = this.folder.toFile();
        new File(dataFolder, "a.db").renameTo(new File(dataFolder, DatabaseSettings.SQLITE_FILE));

        DatabaseManager target = DatabaseManager.sqlite(new File(dataFolder, "target.db"));
        try {
            PetStorage targetStorage = new PetStorage(target);
            targetStorage.createTables();
            SqliteImporter.importIfNeeded(dataFolder, targetStorage, Logger.getAnonymousLogger());

            assertThat(targetStorage.loadPets()).hasSize(1);
            assertThat(new File(dataFolder, DatabaseSettings.SQLITE_FILE)).doesNotExist();
            assertThat(new File(dataFolder, DatabaseSettings.SQLITE_FILE + SqliteImporter.MIGRATED_SUFFIX)).exists();
        } finally {
            target.close();
        }
        this.database = DatabaseManager.sqlite(new File(dataFolder, "again.db"));
    }

    @Test
    void importerLeavesDataDbAloneWhenTargetAlreadyHasData() throws Exception {
        PetPlayer existing = pet(UUID.randomUUID());
        this.storage.importAll(List.of(existing), List.of());
        File dataFolder = this.folder.toFile();
        new File(dataFolder, DatabaseSettings.SQLITE_FILE).createNewFile();

        SqliteImporter.importIfNeeded(dataFolder, this.storage, Logger.getAnonymousLogger());

        assertThat(new File(dataFolder, DatabaseSettings.SQLITE_FILE)).exists();
        assertThat(this.storage.loadPets()).hasSize(1);
    }
}
