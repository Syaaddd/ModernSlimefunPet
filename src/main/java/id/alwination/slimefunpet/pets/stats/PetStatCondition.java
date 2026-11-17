package id.alwination.slimefunpet.pets.stats;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** When a POTION_EFFECT stat is given: 'condition' under its pool entry. */
public enum PetStatCondition {
    /** Whenever the pet is out. */
    ALWAYS,
    /** Within 'stats.mining-seconds' of breaking a block. */
    MINING,
    /** While the owner's health is under 'health-below' percent of their max. */
    HEALTH_BELOW,
    /** While the owner has not hit or been hit for 'stats.combat-seconds'. */
    OUT_OF_COMBAT;

    public static @Nullable PetStatCondition fromString(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (PetStatCondition condition : values()) {
            if (condition.name().equalsIgnoreCase(name.trim().replace('-', '_').toUpperCase(Locale.ROOT))) {
                return condition;
            }
        }
        return null;
    }
}
