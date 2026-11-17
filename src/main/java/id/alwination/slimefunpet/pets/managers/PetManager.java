package id.alwination.slimefunpet.pets.managers;

import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.afk.AfkSettings;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.evolution.EvolutionRequirement;
import id.alwination.slimefunpet.pets.evolution.ItemEvolutionRequirement;
import id.alwination.slimefunpet.pets.evolution.MoneyEvolutionRequirement;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.packet.display.PetModels;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.pets.stats.PetStatRegistry;
import id.alwination.slimefunpet.pets.tasks.PetFoodTask;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PetManager {

    private final Map<String, Pet> petMap = new HashMap<>();
    private static final Map<UUID, PetPlayer> petPlayers = new ConcurrentHashMap<>();

    @Nullable
    public Pet getPet(String id) {
        return this.petMap.get(id);
    }

    @Nullable
    public Pet getPet(PetPlayer petPlayer) {
        return this.getPet(petPlayer.getId());
    }

    public Set<String> getPets() {
        return this.petMap.keySet();
    }

    @Nullable
    public static PetPlayer getPetPlayer(UUID uuid) {
        return petPlayers.get(uuid);
    }

    public static Collection<PetPlayer> getAllPetPlayers() {
        return petPlayers.values();
    }

    public List<PetPlayer> getPetPlayers(Player player) {
        return this.getPetPlayers(player.getUniqueId());
    }

    public List<PetPlayer> getPetPlayers(UUID uuid) {
        return petPlayers.values().stream()
                .filter(petPlayer -> petPlayer.getOwner().equals(uuid))
                .collect(Collectors.toList());
    }

    public synchronized PetPlayer createPetPlayer(UUID uuid, Pet pet) {
        PetPlayer petPlayer = new PetPlayer(uuid, pet.getId());
        // Every pet rolls its stats the moment it is obtained, out of the pool
        // it is allowed to roll, the same one a reroll and an evolution use
        petPlayer.setStats(PetStatRegistry.roll(pet.getStartingStats(), Collections.emptyList(), pet.getStatPool()));
        petPlayers.put(petPlayer.getUniqueId(), petPlayer);
        // A new pet is written at once, it may be the reward of a crate key
        DatabaseQueueHandler.addObjectToSave(petPlayer);
        DatabaseQueueHandler.saveNow();
        return petPlayer;
    }


    public void removePetPlayer(UUID uuid) {
        petPlayers.remove(uuid);
        DatabaseQueueHandler.deletePet(uuid);
        DatabaseQueueHandler.saveNow();
    }

    public void loadPet() {
        FileConfiguration config = ModernSlimefunPet.PETS_CONFIG.getConfig();
        // Clear the pet map and the compiled expressions first
        this.petMap.clear();
        Pet.clearExpressionCache();
        // Reload every tunable of the pet system
        PetSettings.load();
        AfkSettings.load();
        PetStatRegistry.load();
        PetItemRegistry.load();
        PetModels.load(ModernSlimefunPet.getInstance(), config.getConfigurationSection("skins"));
        // Check if there are any pets
        Logger.info("Starting to load all pets...");
        if (!config.isConfigurationSection("pets")) {
            Logger.warning("There is no pets data found!");
            return;
        }
        // Load all pets
        for (String id : config.getConfigurationSection("pets").getKeys(false)) {
            String path = "pets." + id;

            // The evolution is optional, a pet without one is the end of its chain
            Pet.PetEvolution evolution = null;
            if (config.isConfigurationSection(path + ".evolution")) {
                String evolutionPath = path + ".evolution";
                evolution = new Pet.PetEvolution(
                        config.getString(evolutionPath + ".into"),
                        config.getString(evolutionPath + ".display-name", config.getString(evolutionPath + ".into", "")),
                        config.getInt(evolutionPath + ".required-level", 1),
                        readItemIds(config, evolutionPath + ".item"),
                        config.getDouble(evolutionPath + ".chance", 100),
                        config.getInt(evolutionPath + ".bonus-stats", 1),
                        config.getBoolean(evolutionPath + ".keep-level", false),
                        config.getBoolean(evolutionPath + ".lose-item-on-fail", true),
                        config.getBoolean(evolutionPath + ".reset-level-on-fail", true),
                        this.loadEvolutionRequirements(id, config.getConfigurationSection(evolutionPath))
                );
            }

            this.petMap.put(id, new Pet(
                    id,
                    config.getString(path + ".skull"),
                    config.getInt(path + ".max-level", 1),
                    config.getString(path + ".level-cost", "1000"),
                    config.getString(path + ".item-display-name"),
                    config.getStringList(path + ".item-lore"),
                    config.getDouble(path + ".food-decay-multiplier", 1.0),
                    // Left out on purpose by the pets that follow the global rate
                    config.getString(path + ".exp-per-food", ""),
                    config.getInt(path + ".starting-stats", 1),
                    // 0 follows the global 'stats.max-stats'
                    config.getInt(path + ".max-stats", 0),
                    this.loadStatPool(id, config, path + ".stat-pool"),
                    this.loadGuaranteedStats(id, config.getConfigurationSection(path + ".guaranteed-stats")),
                    evolution,
                    PetModels.settingsFor(id, config.getConfigurationSection(path))
            ));
            Logger.info("Loaded pet '" + id + "'");
        }

        // Warn about evolutions pointing at a pet that doesn't exist
        for (Pet pet : this.petMap.values()) {
            Pet.PetEvolution evolution = pet.getEvolution();
            if (evolution != null && !this.petMap.containsKey(evolution.into())) {
                Logger.warning("Pet '" + pet.getId() + "' evolves into the unknown pet '" + evolution.into() + "'!");
            }
        }

        Logger.info("Successfully loaded " + this.petMap.size() + " pets!");
    }

    /**
     * Which stats a pet is allowed to roll, and what each one weighs for it.
     * <p>
     * Written either as a plain list, where every stat keeps the weight it has
     * in 'stats.pool':
     * <pre>
     *   stat-pool:
     *     - speed_boost
     *     - haste
     * </pre>
     * or as a section, where each stat can be made rarer or more common for
     * this pet alone:
     * <pre>
     *   stat-pool:
     *     speed_boost: 40
     *     haste: 5
     * </pre>
     * Leaving the key out lets the pet roll anything in 'stats.pool'.
     */
    private Map<String, Double> loadStatPool(String petId, FileConfiguration config, String path) {
        Map<String, Double> pool = new LinkedHashMap<>();

        if (config.isList(path)) {
            // A bare list keeps the weight every stat already has
            for (String key : config.getStringList(path)) {
                pool.put(key, -1.0);
            }
        } else if (config.isConfigurationSection(path)) {
            ConfigurationSection section = config.getConfigurationSection(path);
            for (String key : section.getKeys(false)) {
                pool.put(key, section.getDouble(key, -1));
            }
        } else {
            return pool;
        }

        // A typo here would quietly narrow what the pet can roll, or leave it
        // able to roll nothing at all, so say so at load
        pool.keySet().removeIf(key -> {
            if (PetStatRegistry.get(key) != null) return false;
            Logger.warning("Pet '" + petId + "' lists the unknown stat '" + key + "' in its stat-pool, ignoring it!");
            return true;
        });

        if (pool.isEmpty()) {
            Logger.warning("Pet '" + petId + "' has a stat-pool with nothing usable in it, it will roll no stats!");
            // An empty pool would read as 'anything goes', which is the
            // opposite of what the config asked for. Keep it impossible instead
            pool.put("", 0.0);
        }

        return pool;
    }

    /**
     * The stats a pet always has at a fixed value, written as stat key to value:
     * <pre>
     *   guaranteed-stats:
     *     money_boost: 1.5
     * </pre>
     * The value is what the stat is worth at level 1, scaled by level like a
     * rolled one, and rounded to the decimals of the stat.
     */
    private List<PetStat> loadGuaranteedStats(String petId, @Nullable ConfigurationSection section) {
        List<PetStat> stats = new ArrayList<>();
        if (section == null) return stats;
        for (String key : section.getKeys(false)) {
            PetStatDefinition definition = PetStatRegistry.get(key);
            if (definition == null) {
                Logger.warning("Pet '" + petId + "' lists the unknown stat '" + key + "' in its guaranteed-stats, ignoring it!");
                continue;
            }
            stats.add(new PetStat(key, definition.round(section.getDouble(key))));
        }
        return stats;
    }

    /**
     * The evolution items a pet takes, written either as a single id or as a
     * list of them so several stones can open the same evolution.
     */
    private static List<String> readItemIds(FileConfiguration config, String path) {
        if (config.isList(path)) {
            return config.getStringList(path);
        }
        String single = config.getString(path, "");
        return single.isBlank() ? List.of() : List.of(single);
    }

    /**
     * Read everything a player has to bring for an evolution, on top of the
     * level and the evolution item itself.
     * <p>
     * The item requirements are laid out exactly like the ones of a generator
     * upgrade: 'item-requirement' for a single one, 'item-requirements' for a
     * whole list of them.
     */
    private List<EvolutionRequirement> loadEvolutionRequirements(String petId, ConfigurationSection section) {
        List<EvolutionRequirement> requirements = new ArrayList<>();
        if (section == null) return requirements;

        double money = section.getDouble("money-cost", 0);
        if (money > 0) {
            requirements.add(new MoneyEvolutionRequirement(money));
        }

        ConfigurationSection single = section.getConfigurationSection("item-requirement");
        if (single != null) {
            this.addEvolutionItemRequirement(petId, requirements, "default", single);
        }

        ConfigurationSection multiple = section.getConfigurationSection("item-requirements");
        if (multiple != null) {
            for (String key : multiple.getKeys(false)) {
                ConfigurationSection entry = multiple.getConfigurationSection(key);
                if (entry == null) continue;
                this.addEvolutionItemRequirement(petId, requirements, key, entry);
            }
        }

        return requirements;
    }

    private void addEvolutionItemRequirement(String petId, List<EvolutionRequirement> requirements,
                                             String key, ConfigurationSection section) {
        String material = section.getString("material-name", section.getString("material"));
        // Leaving the display name out asks for the plain material, so a
        // vanilla stack of stone can be the price of an evolution
        String displayName = section.getString("display-name");
        int amount = section.getInt("amount");

        if (material == null || amount <= 0) {
            Logger.warning("Skipping evolution item requirement '" + key + "' for pet '" + petId + "'");
            Logger.warning("Reason: it must have material/material-name and amount greater than 0");
            return;
        }
        if (Material.matchMaterial(material) == null) {
            Logger.warning("Skipping evolution item requirement '" + key + "' for pet '" + petId + "'");
            Logger.warning("Reason: '" + material + "' is not a material");
            return;
        }

        requirements.add(new ItemEvolutionRequirement(material, displayName, amount));
    }

    /** Takes the pets read from the database at startup and charges the food burned while the server was down. */
    public void loadPetData(List<PetPlayer> loaded) {
        petPlayers.clear();
        for (PetPlayer petPlayer : loaded) {
            // The Stay spot is memory only, so no pet is still staying after a restart
            petPlayer.setStay(false);
            petPlayers.put(petPlayer.getUniqueId(), petPlayer);
        }
        PetFoodTask.applyOfflineDecay(petPlayers.values());
        Logger.info("Successfully loaded " + petPlayers.size() + " pet data!");
    }
}
