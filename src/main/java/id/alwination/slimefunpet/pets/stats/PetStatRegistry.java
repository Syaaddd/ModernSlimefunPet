package id.alwination.slimefunpet.pets.stats;

import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.ModernSlimefunPet;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Holds every stat a pet can roll, loaded from 'stats.pool' in pets.yml.
 */
public final class PetStatRegistry {

    private static final Map<String, PetStatDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static double totalWeight = 0;

    private PetStatRegistry() {
    }

    public static void load() {
        DEFINITIONS.clear();
        totalWeight = 0;

        FileConfiguration config = ModernSlimefunPet.PETS_CONFIG.getConfig();
        if (!config.isConfigurationSection("stats.pool")) {
            Logger.warning("There is no 'stats.pool' section in pets.yml, pets will roll no stats!");
            return;
        }

        for (String key : config.getConfigurationSection("stats.pool").getKeys(false)) {
            String path = "stats.pool." + key;

            // C2: every stat can be switched off on its own
            if (!config.getBoolean(path + ".enabled", true)) {
                Logger.info("Pet stat '" + key + "' is switched off (enabled: false)");
                continue;
            }
            String kindName = config.getString(path + ".kind");
            PetStatKind kind = PetStatKind.fromString(kindName);
            if (kind == null) {
                Logger.warning("Pet stat '" + key + "' has an invalid kind '" + kindName + "', skipping it! " +
                        "Valid kinds are: " + PetStatKind.names());
                continue;
            }

            // Skipped rather than defaulted, a typo would otherwise quietly turn a curse GOOD
            String typeName = config.getString(path + ".type", PetStatType.GOOD.name());
            PetStatType type = PetStatType.fromString(typeName);
            if (type == null) {
                Logger.warning("Pet stat '" + key + "' has an invalid type '" + typeName + "', skipping it! " +
                        "Valid types are: GOOD, BAD");
                continue;
            }

            PotionEffectType potionEffect = null;
            if (kind == PetStatKind.POTION_EFFECT) {
                String effectName = config.getString(path + ".potion-effect", "");
                potionEffect = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(effectName.toLowerCase(Locale.ROOT)));
                if (potionEffect == null) {
                    Logger.warning("Pet stat '" + key + "' has an invalid potion effect '" + effectName + "', skipping it!");
                    continue;
                }
            }

            Attribute attribute = null;
            AttributeModifier.Operation operation = null;
            if (kind == PetStatKind.ATTRIBUTE) {
                String attributeName = config.getString(path + ".attribute", "");
                attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(attributeName.toLowerCase(Locale.ROOT)));
                if (attribute == null) {
                    Logger.warning("Pet stat '" + key + "' has an invalid attribute '" + attributeName + "', skipping it!");
                    continue;
                }
                operation = operation(config.getString(path + ".operation", "ADD_NUMBER"));
                if (operation == null) {
                    Logger.warning("Pet stat '" + key + "' has an invalid operation, use ADD_NUMBER or ADD_SCALAR. Skipping it!");
                    continue;
                }
            }

            String conditionName = config.getString(path + ".condition", PetStatCondition.ALWAYS.name());
            PetStatCondition condition = PetStatCondition.fromString(conditionName);
            if (condition == null) {
                Logger.warning("Pet stat '" + key + "' has an invalid condition '" + conditionName + "', skipping it! "
                        + "Valid conditions are: ALWAYS, MINING, HEALTH_BELOW, OUT_OF_COMBAT");
                continue;
            }

            double weight = config.getDouble(path + ".weight", 10);
            if (weight <= 0) {
                Logger.warning("Pet stat '" + key + "' has a weight of 0 or less, it will never be rolled!");
            }

            DEFINITIONS.put(key, new PetStatDefinition(
                    key,
                    kind,
                    type,
                    config.getString(path + ".display", "&7" + key + ": &f{value}"),
                    config.getDouble(path + ".min", 1),
                    config.getDouble(path + ".max", 1),
                    config.getInt(path + ".decimals", 2),
                    weight,
                    potionEffect,
                    attribute,
                    operation,
                    config.getBoolean(path + ".percent"),
                    condition,
                    config.getDouble(path + ".health-below", kind == PetStatKind.HEAL ? 70 : 100),
                    Math.max(1, config.getInt(path + ".duration-seconds", 5)) * 20,
                    Math.max(1, config.getInt(path + ".interval-seconds", 8))
            ));
            totalWeight += Math.max(0, weight);
        }

        Logger.info("Successfully loaded " + DEFINITIONS.size() + " pet stats!");
    }

    private static AttributeModifier.@Nullable Operation operation(String name) {
        for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
            if (operation.name().equalsIgnoreCase(name.trim())) {
                return operation;
            }
        }
        return null;
    }

    @Nullable
    public static PetStatDefinition get(String key) {
        return DEFINITIONS.get(key);
    }

    public static Collection<PetStatDefinition> getAll() {
        return DEFINITIONS.values();
    }

    public static boolean isEmpty() {
        return DEFINITIONS.isEmpty() || totalWeight <= 0;
    }

    /**
     * Roll a given amount of stats out of the whole pool.
     */
    public static List<PetStat> roll(int amount, Collection<PetStat> exclude) {
        return roll(amount, exclude, null);
    }

    /**
     * Roll a given amount of stats, never rolling the same stat twice.
     *
     * @param amount  how many stats to roll
     * @param exclude stats the pet already has, they will not be rolled again
     * @param pool    the stats this pet is allowed to roll and what each one
     *                weighs for it, null or empty to allow the whole pool with
     *                the weights it was loaded with
     */
    public static List<PetStat> roll(int amount, Collection<PetStat> exclude, @Nullable Map<String, Double> pool) {
        return roll(amount, exclude, pool, null);
    }

    /**
     * Same as {@link #roll(int, Collection, Map)}, only rolling stats of the
     * given type, or any type when it is null. Rolls fewer than asked, possibly
     * none, when the pool runs out of stats of that type.
     */
    public static List<PetStat> roll(int amount, Collection<PetStat> exclude, @Nullable Map<String, Double> pool,
                                     @Nullable PetStatType type) {
        List<PetStat> rolled = new ArrayList<>();
        if (amount <= 0 || DEFINITIONS.isEmpty()) {
            return rolled;
        }

        // Only keep the stats this pet may roll and does not have yet
        List<PetStatDefinition> available = new ArrayList<>();
        for (PetStatDefinition definition : DEFINITIONS.values()) {
            if (type != null && definition.type() != type) continue;
            if (!allows(pool, definition)) continue;
            if (weightOf(pool, definition) <= 0) continue;
            available.add(definition);
        }
        for (PetStat stat : exclude) {
            available.removeIf(definition -> definition.key().equals(stat.key()));
        }

        for (int i = 0; i < amount && !available.isEmpty(); i++) {
            PetStatDefinition definition = pickWeighted(available, pool);
            if (definition == null) break;
            // Never roll the same stat twice on one pet
            available.remove(definition);
            rolled.add(rollValue(definition));
        }
        return rolled;
    }

    /**
     * Whether a pet with this pool is allowed to roll the stat. A pet without a
     * pool of its own may roll anything.
     */
    private static boolean allows(@Nullable Map<String, Double> pool, PetStatDefinition definition) {
        return pool == null || pool.isEmpty() || pool.containsKey(definition.key());
    }

    /**
     * What a stat weighs for this pet. A pool entry with no weight of its own
     * keeps the weight the stat was loaded with.
     */
    private static double weightOf(@Nullable Map<String, Double> pool, PetStatDefinition definition) {
        if (pool == null) return definition.weight();
        Double weight = pool.get(definition.key());
        return weight == null || weight < 0 ? definition.weight() : weight;
    }

    /**
     * Roll a value for a single stat definition.
     */
    public static PetStat rollValue(PetStatDefinition definition) {
        double min = Math.min(definition.minValue(), definition.maxValue());
        double max = Math.max(definition.minValue(), definition.maxValue());
        double value = min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
        return new PetStat(definition.key(), definition.round(value));
    }

    @Nullable
    private static PetStatDefinition pickWeighted(List<PetStatDefinition> available, @Nullable Map<String, Double> pool) {
        double total = 0;
        for (PetStatDefinition definition : available) {
            total += weightOf(pool, definition);
        }
        if (total <= 0) return null;

        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (PetStatDefinition definition : available) {
            roll -= weightOf(pool, definition);
            if (roll <= 0) {
                return definition;
            }
        }
        return available.get(available.size() - 1);
    }

}
