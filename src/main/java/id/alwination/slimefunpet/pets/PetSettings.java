package id.alwination.slimefunpet.pets;

import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.ModernSlimefunPet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import redempt.crunch.Crunch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every tunable of the pet system, read once on load instead of hitting the
 * config on every tick.
 */
public final class PetSettings {

    // Food
    private static double maxFood = 100;
    private static double decayPerHour = 5;
    private static boolean offlineDecay = true;
    private static double maxOfflineHours = 72;
    private static String expPerFood = "30";
    /**
     * The exp rate per level, worked out once on load instead of on every tick.
     */
    private static final Map<Integer, Double> EXP_PER_FOOD_CACHE = new ConcurrentHashMap<>();
    /**
     * Decay multiplier per world name, 1.0 (no change) for a world not listed.
     */
    private static Map<String, Double> worldDecayMultipliers = new HashMap<>();

    // Equipping
    private static int maxEquipped = 1;

    // Display
    private static int viewDistance = 32;
    private static int updateIntervalTicks = 2;
    private static boolean visibleToOthers = true;
    private static double orbitRadius = 1.0;

    // Stats
    private static int rerollStatAmount = 3;
    /**
     * How many stats a pet can hold at once, for every pet that does not set a
     * lower one of its own.
     */
    private static int maxStats = 5;
    private static String levelScaling = "1 + (({level} - 1) * 0.05)";
    /**
     * Scaling per level, worked out once on load instead of on every lookup.
     */
    private static final Map<Integer, Double> LEVEL_SCALING_CACHE = new ConcurrentHashMap<>();
    /**
     * What a whole reroll attempt costs in stat lock charms, as a total for the
     * number of stats it protects rather than a price per stat.
     */
    private static String statLockCost = "{locked}";
    private static final Map<Integer, Double> STAT_LOCK_COST_CACHE = new ConcurrentHashMap<>();
    /**
     * Fixed charm cost per number of locked stats, winning over the formula for
     * every count it lists. Swapped whole on load, never edited in place.
     */
    private static Map<Integer, Integer> statLockCostTable = new HashMap<>();
    /**
     * How many stats one reroll may protect at most, on top of the rule that
     * at least one stat always stays free. 0 turns stat locks off.
     */
    private static int maxStatLocks = 4;

    // Tasks
    private static int foodTaskIntervalSeconds = 60;

    /**
     * Auto-Feed tiers, the first being what an unlock gives: {threshold percent,
     * open slots} each. Swapped whole on load, never edited in place.
     */
    private static double[][] autoFeedTiers = {{50, AutoFeed.MAX_SLOTS}};

    private PetSettings() {
    }

    private static int combatSeconds = 10;
    private static int miningSeconds = 5;
    private static int effectIntervalTicks = 40;

    public static void load() {
        FileConfiguration config = ModernSlimefunPet.PETS_CONFIG.getConfig();

        maxFood = Math.max(1, config.getDouble("food.max", 100));
        decayPerHour = Math.max(0, config.getDouble("food.decay-per-hour", 5));
        offlineDecay = config.getBoolean("food.offline-decay", true);
        maxOfflineHours = Math.max(0, config.getDouble("food.max-offline-hours", 72));
        foodTaskIntervalSeconds = Math.max(1, config.getInt("food.check-interval", 60));
        // A plain number in the config still works, it is just an expression
        // without any placeholder in it
        expPerFood = config.getString("food.exp-per-food", "30");
        EXP_PER_FOOD_CACHE.clear();

        Map<String, Double> loadedMultipliers = new HashMap<>();
        ConfigurationSection worldSection = config.getConfigurationSection("food.world-multipliers");
        if (worldSection != null) {
            for (String worldName : worldSection.getKeys(false)) {
                loadedMultipliers.put(worldName,
                        Math.max(0, config.getDouble("food.world-multipliers." + worldName + ".decay-multiplier", 1.0)));
            }
        }
        worldDecayMultipliers = loadedMultipliers;

        maxEquipped = Math.max(1, config.getInt("equip.max-equipped", 1));

        visibleToOthers = config.getBoolean("display.visible-to-others", true);
        viewDistance = Math.max(1, config.getInt("display.view-distance", 32));
        updateIntervalTicks = Math.max(1, config.getInt("display.update-interval", 2));
        orbitRadius = config.getDouble("display.orbit-radius", 1.0);

        combatSeconds = Math.max(0, config.getInt("stats.combat-seconds", 10));
        miningSeconds = Math.max(0, config.getInt("stats.mining-seconds", 5));
        effectIntervalTicks = Math.max(1, config.getInt("stats.effect-interval", 40));
        rerollStatAmount = Math.max(0, config.getInt("stats.reroll-amount", 3));
        maxStats = Math.max(1, config.getInt("stats.max-stats", 5));
        levelScaling = config.getString("stats.level-scaling", "1 + (({level} - 1) * 0.05)");
        LEVEL_SCALING_CACHE.clear();
        statLockCost = config.getString("stats.stat-lock-cost", "{locked}");
        STAT_LOCK_COST_CACHE.clear();

        Map<Integer, Integer> loadedLockCosts = new HashMap<>();
        ConfigurationSection lockCostSection = config.getConfigurationSection("stats.stat-lock-costs");
        if (lockCostSection != null) {
            for (String key : lockCostSection.getKeys(false)) {
                try {
                    loadedLockCosts.put(Integer.parseInt(key), Math.max(0, lockCostSection.getInt(key)));
                } catch (NumberFormatException ex) {
                    Logger.warning("Invalid 'stats.stat-lock-costs' entry '" + key
                            + "', it has to be a number of locked stats. Skipping it.");
                }
            }
        }
        statLockCostTable = loadedLockCosts;
        maxStatLocks = Math.max(0, config.getInt("stats.max-stat-locks", 4));

        // Work the whole curve out now, so a formula that does not compile is
        // reported on load rather than at the first reroll, and the console
        // shows what the server actually charges
        int mostLocks = Math.min(maxStats - 1, maxStatLocks);
        StringBuilder lockCosts = new StringBuilder();
        for (int locked = 1; locked <= mostLocks; locked++) {
            if (locked > 1) lockCosts.append(", ");
            lockCosts.append(getStatLockCost(locked));
        }
        if (mostLocks > 0) {
            Logger.info("Stat lock charm cost for 1 to " + mostLocks + " locked stats: " + lockCosts
                    + " (stat-lock-cost: '" + statLockCost + "', stat-lock-costs: " + statLockCostTable + ")");
        }

        // Out of range values are pulled back in, so the console shows what the
        // server actually uses
        loadAutoFeedTiers(config.getMapList("auto-feed.tiers"));
        StringBuilder tiers = new StringBuilder();
        for (int tier = 0; tier < getAutoFeedTiers(); tier++) {
            if (tier > 0) tiers.append(", ");
            tiers.append(getAutoFeedThreshold(tier)).append("%/").append(getAutoFeedSlots(tier)).append(" slots");
        }
        Logger.info("Auto-Feed tiers: " + tiers);

        Logger.info("Successfully loaded the pet settings!");
    }

    /**
     * Read 'auto-feed.tiers', one {threshold, slots} entry per tier. The
     * threshold stays at or below 95 percent, feeding at a full bar would eat
     * every stored food at once, and the slots between 1 and the 5 the menu has.
     * No tiers at all falls back to a single one, feeding at 50% with every slot open.
     */
    static void loadAutoFeedTiers(List<Map<?, ?>> entries) {
        List<double[]> loaded = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            double threshold = entry.get("threshold") instanceof Number number ? number.doubleValue() : 50;
            int slots = entry.get("slots") instanceof Number number ? number.intValue() : AutoFeed.MAX_SLOTS;
            loaded.add(new double[]{
                    Math.max(0, Math.min(95, threshold)),
                    Math.max(1, Math.min(AutoFeed.MAX_SLOTS, slots))
            });
        }
        if (loaded.isEmpty()) {
            loaded.add(new double[]{50, AutoFeed.MAX_SLOTS});
        }
        autoFeedTiers = loaded.toArray(new double[0][]);
    }

    public static int getAutoFeedTiers() {
        return autoFeedTiers.length;
    }

    /**
     * The food percent at or below which a pet on this tier eats. A tier past
     * the last, left by a config that lost one, reads as the last.
     */
    public static double getAutoFeedThreshold(int tier) {
        double[][] tiers = autoFeedTiers;
        return tiers[Math.max(0, Math.min(tier, tiers.length - 1))][0];
    }

    public static int getAutoFeedSlots(int tier) {
        double[][] tiers = autoFeedTiers;
        return (int) tiers[Math.max(0, Math.min(tier, tiers.length - 1))][1];
    }

    public static double getMaxFood() {
        return maxFood;
    }

    public static double getDecayPerHour() {
        return decayPerHour;
    }

    public static boolean isOfflineDecay() {
        return offlineDecay;
    }

    public static double getMaxOfflineHours() {
        return maxOfflineHours;
    }

    /**
     * How much exp a pet gains for every 1 food it burns.
     * <p>
     * This is a flat amount, not a share of the level cost, which is what makes
     * a rising 'level-cost' actually take longer to climb.
     * <p>
     * The config value can be a plain number or an expression using '{level}'.
     */
    public static double getExpPerFood(int level) {
        return evaluate(EXP_PER_FOOD_CACHE, expPerFood, "food.exp-per-food", "{level}", level, 30);
    }

    public static int getFoodTaskIntervalSeconds() {
        return foodTaskIntervalSeconds;
    }

    /**
     * How much faster (or slower) hunger burns while standing in this world,
     * 1.0 for a world not listed in 'food.world-multipliers'.
     */
    public static double getWorldDecayMultiplier(String worldName) {
        return worldDecayMultipliers.getOrDefault(worldName, 1.0);
    }

    public static int getViewDistance() {
        return viewDistance;
    }

    public static int getUpdateIntervalTicks() {
        return updateIntervalTicks;
    }

    public static boolean isVisibleToOthers() {
        return visibleToOthers;
    }

    public static double getOrbitRadius() {
        return orbitRadius;
    }

    /** Seconds a player stays in combat after a hit, given or taken. */
    public static int getCombatSeconds() {
        return combatSeconds;
    }

    /** Seconds a player counts as mining after breaking a block. */
    public static int getMiningSeconds() {
        return miningSeconds;
    }

    /** Ticks between two checks of the pet effects, attributes and heals. */
    public static int getEffectIntervalTicks() {
        return effectIntervalTicks;
    }

    public static int getRerollStatAmount() {
        return rerollStatAmount;
    }

    /**
     * How many stats a pet can hold when it does not set its own 'max-stats'.
     */
    public static int getMaxStatLocks() {
        return maxStatLocks;
    }

    public static int getMaxStats() {
        return maxStats;
    }

    /**
     * How much the rolled stats of a pet are worth at the given level.
     * A level 1 pet returns 1.0, so its stats are worth exactly what they rolled.
     */
    public static double getLevelScaling(int level) {
        return evaluate(LEVEL_SCALING_CACHE, levelScaling, "stats.level-scaling", "{level}", level, 1.0);
    }

    /**
     * How many stat lock charms one reroll attempt costs when it protects this
     * many stats.
     * <p>
     * The config value is the total for the attempt, not a price per stat, so
     * the second and third lock can cost more than the first. A count listed
     * in 'stats.stat-lock-costs' uses that fixed amount, any other count the
     * 'stats.stat-lock-cost' formula, rounded up because charms come whole.
     */
    public static int getStatLockCost(int locked) {
        if (locked <= 0) return 0;
        Integer fixed = statLockCostTable.get(locked);
        if (fixed != null) return fixed;
        return (int) Math.ceil(evaluate(STAT_LOCK_COST_CACHE, statLockCost,
                "stats.stat-lock-cost", "{locked}", locked, locked));
    }

    /**
     * Evaluate a placeholder based config expression, keeping the result of
     * every input that was asked for so the expression is only compiled once
     * per input.
     *
     * @param fallback used when the expression does not compile, so a typo in
     *                 the config never breaks the pet system
     */
    // A broken formula in pets.yml must fall back to a number, never break the pet.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static double evaluate(Map<Integer, Double> cache, String expression, String configKey,
                                   String placeholder, int level, double fallback) {
        return cache.computeIfAbsent(Math.max(1, level), key -> {
            try {
                String parsed = expression.replace(placeholder, String.valueOf(key));
                return Math.max(0, Crunch.compileExpression(parsed).evaluate());
            } catch (Exception ex) {
                Logger.severe("Invalid '" + configKey + "' (" + expression + "): " + ex.getMessage());
                return fallback;
            }
        });
    }

    /**
     * How many pets a player can equip at once: 'equip.max-equipped', 1 by default. The owner chose
     * one active pet so the stats never stack, so no permission raises it.
     */
    public static int getMaxEquipped(Player player) {
        return maxEquipped;
    }

}
