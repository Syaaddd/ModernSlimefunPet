package id.alwination.slimefunpet;

import com.muhammaddaffa.mdlib.MDLib;
import com.muhammaddaffa.mdlib.fastinv.FastInvManager;
import com.muhammaddaffa.mdlib.utils.Config;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import id.alwination.slimefunpet.afk.AfkXpTask;
import id.alwination.slimefunpet.commands.PetCommand;
import id.alwination.slimefunpet.commands.SlimefunPetCommand;
import id.alwination.slimefunpet.database.DatabaseManager;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.database.DatabaseSettings;
import id.alwination.slimefunpet.database.DatabaseType;
import id.alwination.slimefunpet.database.PetStorage;
import id.alwination.slimefunpet.database.SqliteImporter;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.hooks.slimefun.SlimefunPetItemGuard;
import id.alwination.slimefunpet.pets.listeners.PetActivityListener;
import id.alwination.slimefunpet.pets.listeners.PetItemGuardListener;
import id.alwination.slimefunpet.pets.listeners.PetItemListener;
import id.alwination.slimefunpet.pets.listeners.PetPlayerListener;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.managers.PetService;
import id.alwination.slimefunpet.pets.managers.ProfileManager;
import id.alwination.slimefunpet.pets.tasks.PetEffectTask;
import id.alwination.slimefunpet.pets.tasks.PetFoodTask;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * ModernSlimefunPet main class: owns the configs, the database and the pet managers.
 * <p>
 * The static config and key fields keep ModernMineGens' names, so the ported pet code reads the
 * same. They are set in {@link #onEnable()} before anything else runs.
 */
public final class ModernSlimefunPet extends JavaPlugin {

    /** Flush period of the save queue: 30 seconds, as in ModernMineGens. */
    private static final long SAVE_PERIOD_TICKS = 600L;

    @SuppressWarnings("NullAway.Init")
    public static Config PETS_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_EVOLUTION_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_REROLL_CONFIRM_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_STAT_LOCK_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_OPTIONS_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_SKIN_GUI_CONFIG;
    @SuppressWarnings("NullAway.Init")
    public static Config PET_AUTO_FEED_GUI_CONFIG;

    /** The menu of every pet item type that opens one (food, candy, evolution, reroll, revive). */
    private static final Map<PetItemType, Config> PET_MODE_GUI_CONFIGS = new EnumMap<>(PetItemType.class);

    /** PDC keys every pet item carries: its type and its id under items.<type>.<id>. */
    @SuppressWarnings("NullAway.Init")
    public static NamespacedKey pet_item_type;
    @SuppressWarnings("NullAway.Init")
    public static NamespacedKey pet_item_id;

    @SuppressWarnings("NullAway.Init")
    private static ModernSlimefunPet instance;

    private final ProfileManager profileManager = new ProfileManager();
    private final PetManager petManager = new PetManager();
    private final PetService petService = new PetService(this.petManager);
    private final PetTask petTask = new PetTask();
    private final PetFoodTask petFoodTask = new PetFoodTask();
    private final PetActivityListener activityListener = new PetActivityListener();
    private final PetEffectTask petEffectTask = new PetEffectTask(this.activityListener);
    private final AfkXpTask afkXpTask = new AfkXpTask(this.profileManager, this.petTask, this.activityListener,
            this.getDataFolder().toPath().resolve("afk-payouts.log"));
    private @Nullable DatabaseManager database;

    public static ModernSlimefunPet getInstance() {
        return instance;
    }

    /** The menu a pet item type is picked in, the plain pet menu when it has none. */
    public static Config getPetGuiConfig(@Nullable PetItemType type) {
        if (type == null) {
            return PET_GUI_CONFIG;
        }
        return PET_MODE_GUI_CONFIGS.getOrDefault(type, PET_GUI_CONFIG);
    }

    @Override
    public void onEnable() {
        instance = this;
        // Money costs (revive, evolution) need Vault; MDLib only hooks it when asked before onEnable
        MDLib.LISTEN_VAULT = true;
        MDLib.onEnable(this);
        this.loadConfigs();
        pet_item_type = new NamespacedKey(this, "pet_item_type");
        pet_item_id = new NamespacedKey(this, "pet_item_id");

        // config.yml only holds the database settings, read once here
        PetStorage storage = this.openDatabase(new Config("config.yml", null, false));
        if (storage == null) {
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.petManager.loadPet();
        if (!this.loadData(storage)) {
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DatabaseQueueHandler.start(storage, this.profileManager, SAVE_PERIOD_TICKS);

        this.getServer().getPluginManager().registerEvents(new PetItemListener(this.petManager, this.petTask), this);
        this.getServer().getPluginManager().registerEvents(new PetPlayerListener(this.profileManager, this.petTask, this.petEffectTask), this);
        this.getServer().getPluginManager().registerEvents(this.activityListener, this);
        this.getServer().getPluginManager().registerEvents(this.afkXpTask, this);
        this.getServer().getPluginManager().registerEvents(new PetItemGuardListener(this), this);
        // The Slimefun hook loads Slimefun classes, so it is only touched when Slimefun is there
        if (this.getServer().getPluginManager().isPluginEnabled("Slimefun")) {
            this.getServer().getPluginManager().registerEvents(new SlimefunPetItemGuard(), this);
            this.getLogger().info("Slimefun found: pet items are guarded against Slimefun machines and menus.");
        } else {
            this.getLogger().info("Slimefun not found: only the menu, drop and vanilla guards are active.");
        }
        MDLib.getCommandRegistry().register(new PetCommand(this.petManager, this.petTask));
        MDLib.getCommandRegistry().register(new SlimefunPetCommand(this.petManager, this.petTask));

        this.petTask.start();
        this.petFoodTask.start();
        this.petEffectTask.start();
        this.afkXpTask.start();
        // After a reload the players are already online
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.petTask.setHidden(player, this.profileManager.getProfile(player.getUniqueId()).isToggleHidePet(), false);
            this.petTask.refreshFor(player);
        }
        this.getLogger().info("ModernSlimefunPet enabled.");
    }

    @Override
    public void onDisable() {
        // Menus first: a menu click must not run while the managers shut down
        FastInvManager.closeAll();
        this.afkXpTask.stop();
        this.petTask.stop();
        this.petFoodTask.stop();
        this.petEffectTask.stop();
        if (this.database != null) {
            // Every pet and profile is written on the way down, like ModernMineGens' save on stop
            for (PetPlayer pet : PetManager.getAllPetPlayers()) {
                DatabaseQueueHandler.addObjectToSave(pet);
            }
            for (PlayerProfile profile : this.profileManager.getProfiles()) {
                DatabaseQueueHandler.addObjectToSave(profile);
            }
            DatabaseQueueHandler.terminate();
            this.database.close();
        }
        MDLib.shutdown();
    }

    /** Reloads every config file and the pet types; used by /slimefunpet reload. */
    public void reloadPets() {
        Config.reload();
        this.loadModeConfigs();
        this.petManager.loadPet();
        this.petTask.start();
        this.petFoodTask.start();
        this.petEffectTask.start();
        this.afkXpTask.start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.petTask.refreshFor(player);
        }
    }

    public ProfileManager getProfileManager() {
        return this.profileManager;
    }

    public PetManager getPetManager() {
        return this.petManager;
    }

    public PetService getPetService() {
        return this.petService;
    }

    public PetTask getPetTask() {
        return this.petTask;
    }

    public PetEffectTask getPetEffectTask() {
        return this.petEffectTask;
    }

    public PetActivityListener getActivityListener() {
        return this.activityListener;
    }

    private void loadConfigs() {
        PETS_CONFIG = new Config("pets.yml", null, true);
        PET_GUI_CONFIG = new Config("pet_gui.yml", "gui", true);
        PET_EVOLUTION_GUI_CONFIG = new Config("pet_evolution_gui.yml", "gui", true);
        PET_REROLL_CONFIRM_GUI_CONFIG = new Config("pet_reroll_confirm_gui.yml", "gui", true);
        PET_STAT_LOCK_GUI_CONFIG = new Config("pet_stat_lock_gui.yml", "gui", true);
        PET_OPTIONS_GUI_CONFIG = new Config("pet_options_gui.yml", "gui", true);
        PET_SKIN_GUI_CONFIG = new Config("pet_skin_gui.yml", "gui", true);
        PET_AUTO_FEED_GUI_CONFIG = new Config("pet_auto_feed_gui.yml", "gui", true);
        this.loadModeConfigs();
    }

    private void loadModeConfigs() {
        PET_MODE_GUI_CONFIGS.clear();
        for (PetItemType type : PetItemType.values()) {
            String file = type.getGuiFile();
            if (file != null) {
                PET_MODE_GUI_CONFIGS.put(type, new Config(file, "gui", true));
            }
        }
    }

    /** Opens the pool and creates the tables. Without a database the plugin has nothing to run on. */
    private @Nullable PetStorage openDatabase(Config config) {
        try {
            DatabaseManager opened = DatabaseSettings.openConfigured(config, this.getDataFolder());
            this.database = opened;
            PetStorage storage = new PetStorage(opened);
            storage.createTables();
            if (opened.getType() == DatabaseType.MYSQL) {
                SqliteImporter.importIfNeeded(this.getDataFolder(), storage, this.getLogger());
            }
            this.getLogger().info("Database ready (" + opened.getType() + ").");
            return storage;
        } catch (SQLException | PoolInitializationException | IllegalArgumentException exception) {
            this.getLogger().severe("Cannot open the database: " + exception);
            return null;
        }
    }

    /** Loads every pet and profile, then charges the food the pets burned while the server was down. */
    private boolean loadData(PetStorage storage) {
        try {
            this.profileManager.load(storage.loadProfiles());
            this.petManager.loadPetData(storage.loadPets());
            return true;
        } catch (SQLException exception) {
            this.getLogger().severe("Cannot load pets from the database: " + exception);
            return false;
        }
    }
}
