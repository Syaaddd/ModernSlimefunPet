package id.alwination.slimefunpet.pets.stats;

/**
 * Whether a rolled pet stat helps or hurts, picked in the config with
 * 'stats.pool.<key>.type'.
 * <p>
 * Set per pool entry rather than read off the kind or the sign of the value:
 * a curse shares its kind with the boost it mirrors, and a positive CORRUPTION
 * is as bad as a negative WARMTH.
 */
public enum PetStatType {

    GOOD,
    /**
     * A full reroll always rolls one of these, see
     * {@link id.alwination.slimefunpet.pets.PetPlayer#rerollStats}.
     */
    BAD;

    public static PetStatType fromString(String name) {
        if (name == null) return null;
        for (PetStatType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

}
