package id.alwination.slimefunpet.pets.stats;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * A stat that pets can roll, defined under 'stats.pool.&lt;key&gt;' in pets.yml. Read once per load and
 * never changed, so it stays a record.
 *
 * @param key          the config key of the stat
 * @param kind         what the stat does
 * @param type         whether the stat helps or hurts
 * @param display      the display name, '{value}' is replaced with the rolled value
 * @param minValue     lowest value that can be rolled
 * @param maxValue     highest value that can be rolled
 * @param decimals     how many decimals the rolled value keeps
 * @param weight       relative chance to be picked, a stat with weight 20 is
 *                     twice as likely as one with weight 10
 * @param potionEffect only used by the POTION_EFFECT kind
 * @param attribute    only used by the ATTRIBUTE kind
 * @param operation    only used by the ATTRIBUTE kind
 * @param percent      ATTRIBUTE: the value is divided by 100 before it is applied
 * @param condition    POTION_EFFECT: when the effect is given
 * @param healthBelow  POTION_EFFECT with HEALTH_BELOW, and HEAL: the health percent it works under
 * @param durationTicks POTION_EFFECT: how long one refresh of the effect lasts
 * @param intervalSeconds HEAL: seconds between two heals
 */
public record PetStatDefinition(
        String key,
        PetStatKind kind,
        PetStatType type,
        String display,
        double minValue,
        double maxValue,
        int decimals,
        double weight,
        @Nullable PotionEffectType potionEffect,
        @Nullable Attribute attribute,
        AttributeModifier.@Nullable Operation operation,
        boolean percent,
        PetStatCondition condition,
        double healthBelow,
        int durationTicks,
        int intervalSeconds
) {

    /**
     * Round a rolled value to the configured amount of decimals.
     */
    public double round(double value) {
        if (this.decimals <= 0) {
            return Math.round(value);
        }
        double factor = Math.pow(10, this.decimals);
        return Math.round(value * factor) / factor;
    }

}
