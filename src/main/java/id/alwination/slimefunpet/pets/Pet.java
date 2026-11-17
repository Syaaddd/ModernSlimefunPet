package id.alwination.slimefunpet.pets;

import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.evolution.EvolutionRequirement;
import id.alwination.slimefunpet.packet.display.PetModels;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import redempt.crunch.CompiledExpression;
import redempt.crunch.Crunch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pet as defined under 'pets.<id>' in pets.yml.
 */
public class Pet {

    /**
     * Compiled booster/level-cost expressions, they are evaluated on every
     * generator tick so they must not be recompiled each time.
     */
    private static final Map<String, CompiledExpression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    private final String id;
    private final String skull;
    private final int maxLevel;
    private final String levelCost;
    private final String itemDisplayName;
    private final List<String> itemLore;

    // Food
    private final double foodDecayMultiplier;
    /**
     * The exp rate of this pet, blank when it just follows the global one.
     */
    private final String expPerFood;

    // Stats rolled when the pet is obtained
    private final int startingStats;
    /**
     * How many stats this pet can hold, 0 when it follows 'stats.max-stats'.
     */
    private final int maxStats;
    /**
     * The stats this pet is allowed to roll, mapped to what each one weighs for
     * it. Empty when it may roll anything in 'stats.pool'.
     */
    private final Map<String, Double> statPool;
    /**
     * Stats every pet of this id always has at a fixed value, on top of the
     * rolled ones. Read from the config rather than stored on the pet, so a
     * reroll, a burn or a stat lock can never touch them.
     */
    private final List<PetStat> guaranteedStats;

    // Evolution
    private final PetEvolution evolution;

    /**
     * The Blockbench model the pet is drawn with, null when it keeps the skull.
     */
    private final PetModels.@Nullable Settings model;

    public Pet(String id, String skull, int maxLevel, String levelCost,
               String itemDisplayName, List<String> itemLore, double foodDecayMultiplier,
               String expPerFood, int startingStats, int maxStats, Map<String, Double> statPool,
               List<PetStat> guaranteedStats, @Nullable PetEvolution evolution, PetModels.@Nullable Settings model) {
        this.id = id;
        this.skull = skull;
        this.maxLevel = maxLevel;
        this.levelCost = levelCost;
        this.itemDisplayName = itemDisplayName;
        this.itemLore = itemLore;
        this.foodDecayMultiplier = foodDecayMultiplier;
        this.expPerFood = expPerFood;
        this.startingStats = startingStats;
        this.maxStats = maxStats;
        this.statPool = statPool == null ? Map.of() : statPool;
        this.guaranteedStats = guaranteedStats == null ? List.of() : guaranteedStats;
        this.evolution = evolution;
        this.model = model;
    }

    public String getId() {
        return id;
    }

    public String getSkull() {
        return skull;
    }

    public PetModels.@Nullable Settings getModel() {
        return model;
    }

    /**
     * The model file this pet is drawn with, null when it floats as a skull.
     * A plain String for AlwiPetCosmetic: PetModels.Settings is relocated into
     * each plugin's own package, so it cannot cross between them.
     */
    public @Nullable String getModelName() {
        return model == null ? null : model.model().getName();
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public String getItemDisplayName() {
        return itemDisplayName;
    }

    public List<String> getItemLore() {
        return itemLore;
    }

    public double getFoodDecayMultiplier() {
        return foodDecayMultiplier;
    }

    public int getStartingStats() {
        return Math.min(startingStats, this.getMaxStats());
    }

    /**
     * How many stats this pet can hold at once. A pet that sets no 'max-stats'
     * of its own follows the global 'stats.max-stats'.
     */
    public int getMaxStats() {
        return this.maxStats > 0 ? this.maxStats : PetSettings.getMaxStats();
    }

    /**
     * The stats this pet may roll and what each one weighs for it, empty when
     * the whole 'stats.pool' is fair game.
     */
    public Map<String, Double> getStatPool() {
        return statPool;
    }

    public List<PetStat> getGuaranteedStats() {
        return guaranteedStats;
    }

    @Nullable
    public PetEvolution getEvolution() {
        return evolution;
    }

    public boolean canEvolve() {
        return this.evolution != null;
    }

    /**
     * The exp needed to go from the given level to the next one.
     * The config value can be a plain number or an expression using '{level}'.
     */
    public int getLevelCost(int level) {
        return (int) evaluate(this.levelCost, level, 0);
    }

    /**
     * How much exp this pet gains for every 1 food it burns.
     * <p>
     * Every pet can set its own rate, which is how a rare pet is made to climb
     * faster than a common one on the very same food. A pet that leaves the key
     * out follows the global 'food.exp-per-food' instead.
     */
    public double getExpPerFood(int level) {
        if (this.expPerFood == null || this.expPerFood.isBlank()) {
            return PetSettings.getExpPerFood(level);
        }
        return Math.max(0, evaluate(this.expPerFood, level, 0));
    }

    /**
     * Evaluate a '{level}' based value, which is either a plain number or a
     * formula. The compiled form is kept so the same level is never compiled
     * twice, the food task walks every pet on a timer.
     *
     * @param fallback used when the expression does not compile, so a typo in
     *                 one pet never breaks the whole food task
     */
    // A broken formula in pets.yml must fall back to a number, never break the pet.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static double evaluate(String expression, int level, double fallback) {
        if (expression == null || expression.isBlank()) return fallback;
        String parsed = expression.replace("{level}", String.valueOf(level));
        // A broken expression is cached as its fallback, so the console is told
        // about it once instead of on every single lookup
        return EXPRESSION_CACHE.computeIfAbsent(parsed, key -> {
            try {
                return Crunch.compileExpression(key);
            } catch (Exception ex) {
                Logger.severe("Invalid pet expression (" + expression + "): " + ex.getMessage());
                return Crunch.compileExpression(String.valueOf(fallback));
            }
        }).evaluate();
    }

    public static void clearExpressionCache() {
        EXPRESSION_CACHE.clear();
    }

    /**
     * The GUI/inventory item for one owned pet.
     */
    public ItemStack getItem(PetPlayer petPlayer) {
        int level = petPlayer.getLevel();
        int exp = petPlayer.getExp();
        int required = this.getLevelCost(level);

        String progressBar = Utils.getProgressBar(exp, Math.max(1, required), 20, '|',
                ChatColor.GREEN, ChatColor.RED);
        if (level >= this.maxLevel) {
            progressBar = ModernSlimefunPet.PETS_CONFIG.getString("placeholders.max-level", "&c&lMAX LEVEL");
        }

        String foodBar = Utils.getProgressBar((int) petPlayer.getFood(), (int) PetSettings.getMaxFood(), 20, '|',
                ChatColor.GOLD, ChatColor.DARK_GRAY);

        String status = petPlayer.isDead()
                ? ModernSlimefunPet.PETS_CONFIG.getString("placeholders.dead", "&c&lDEAD")
                : ModernSlimefunPet.PETS_CONFIG.getString("placeholders.alive", "&a&lALIVE");

        // Build the lore, expanding the {stats} line into one line per stat
        List<String> lore = new ArrayList<>();
        for (String line : this.itemLore) {
            if (line.contains("{stats}")) {
                lore.addAll(this.buildStatLines(petPlayer));
                continue;
            }
            lore.add(line);
        }

        return new ItemBuilder(Material.PLAYER_HEAD)
                .skull(this.skull)
                .name(this.itemDisplayName)
                .lore(lore)
                .placeholder(new Placeholder()
                        .add("{max_level}", Common.digits(this.maxLevel))
                        .add("{exp_needed}", Common.digits(required))
                        .add("{stat_scaling}", Common.digits(PetSettings.getLevelScaling(level) * 100))
                        .add("{level}", Common.digits(level))
                        .add("{exp}", Common.digits(exp))
                        .add("{progress_bar}", progressBar)
                        .add("{food}", Common.digits(petPlayer.getFood()))
                        .add("{max_food}", Common.digits(PetSettings.getMaxFood()))
                        .add("{food_percent}", Common.digits(petPlayer.getFoodProgress() * 100))
                        .add("{food_bar}", foodBar)
                        .add("{status}", status)
                        .add("{evolution}", this.evolution == null
                                ? ModernSlimefunPet.PETS_CONFIG.getString("placeholders.no-evolution", "&7None")
                                : this.evolution.displayName())
                        // The odds are worth knowing before a stone is spent
                        .add("{evolution_chance}", this.evolution == null
                                ? "0" : Common.digits(this.evolution.chance()))
                        .add("{evolution_level}", this.evolution == null
                                ? "0" : Common.digits(this.evolution.requiredLevel())))
                .build();
    }

    /**
     * One lore line per stat, the guaranteed ones first and marked as such,
     * using the display of each stat.
     */
    private List<String> buildStatLines(PetPlayer petPlayer) {
        List<String> lines = new ArrayList<>();
        if (petPlayer.getStats().isEmpty() && this.guaranteedStats.isEmpty()) {
            lines.add(ModernSlimefunPet.PETS_CONFIG.getString("placeholders.no-stats", " &8- &7None"));
            return lines;
        }
        String guaranteedFormat = ModernSlimefunPet.PETS_CONFIG.getString("placeholders.guaranteed-stat", "{stat} &6✦");
        for (PetStat stat : this.guaranteedStats) {
            PetStatDefinition definition = stat.getDefinition();
            if (definition == null) continue;
            lines.add(guaranteedFormat.replace("{stat}",
                    definition.display().replace("{value}", Common.digits(petPlayer.getScaledValue(stat)))));
        }
        for (PetStat stat : petPlayer.getStats()) {
            PetStatDefinition definition = stat.getDefinition();
            if (definition == null) continue;
            lines.add(definition.display().replace("{value}", Common.digits(petPlayer.getScaledValue(stat))));
        }
        return lines;
    }

    /**
     * The evolution of a pet, defined under 'pets.<id>.evolution'.
     *
     * @param into          the pet id this pet turns into
     * @param displayName   shown in the GUI
     * @param requiredLevel the level the pet must reach first
     * @param items         the ids of the evolution items that are accepted,
     *                      empty when any evolution item will do
     * @param chance        success rate in percent
     * @param bonusStats    extra stats rolled on a successful evolution
     * @param keepLevel     keep the level instead of resetting it to 1
     * @param loseItemOnFail consume the item even when the evolution fails
     * @param resetLevelOnFail send the pet back to level 1 when the evolution
     *                      fails, so a failed attempt costs the levels too
     * @param requirements  everything else the player has to bring, taken when
     *                      the attempt is made
     */
    public record PetEvolution(
            String into,
            String displayName,
            int requiredLevel,
            List<String> items,
            double chance,
            int bonusStats,
            boolean keepLevel,
            boolean loseItemOnFail,
            boolean resetLevelOnFail,
            List<EvolutionRequirement> requirements
    ) {

        /**
         * The success rate of this evolution once the item being used has had
         * its say, kept inside 0-100 so a generous item cannot go past certain.
         */
        public double chanceWith(double bonus) {
            return Math.max(0, Math.min(100, this.chance + bonus));
        }

        /**
         * How likely the same attempt is to fail, the other side of
         * {@link #chanceWith(double)}.
         */
        public double failChanceWith(double bonus) {
            return 100 - this.chanceWith(bonus);
        }

        /**
         * Whether this evolution takes the given item. A pet that names no item
         * at all takes any of them, which is how the chain worked before the
         * better stones existed.
         */
        public boolean accepts(String itemId) {
            if (this.items == null || this.items.isEmpty()) return true;
            for (String accepted : this.items) {
                if (accepted != null && accepted.equalsIgnoreCase(itemId)) return true;
            }
            return false;
        }

    }

}
