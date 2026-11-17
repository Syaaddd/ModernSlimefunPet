package id.alwination.slimefunpet.pets.stats;

/**
 * What a rolled pet stat actually does.
 * The kind is picked in the config with 'stats.pool.<key>.kind'.
 */
public enum PetStatKind {

    /**
     * Changes a player attribute (max health, armor, ...) while the pet is out,
     * the value scaled by the pet's level.
     */
    ATTRIBUTE,
    /**
     * Heals the owner every few seconds while their health is low, the value
     * is the health points healed. Never scaled by level.
     */
    HEAL,

    /**
     * Gives a potion effect while the pet is equipped and alive and its
     * condition holds, the value is the level (1 = level I). Never scaled.
     */
    POTION_EFFECT,
    /**
     * Multiplies the exp the pet gains when it is fed, the value is a percentage.
     */
    PET_EXP,
    /**
     * Slows the food decay down, the value is a percentage (100 = never gets hungry).
     */
    FOOD_SAVER;

    /**
     * Every kind by name, for a message telling someone what they may write.
     */
    public static String names() {
        StringBuilder builder = new StringBuilder();
        for (PetStatKind kind : values()) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(kind.name());
        }
        return builder.toString();
    }

    public static PetStatKind fromString(String name) {
        if (name == null) return null;
        for (PetStatKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }


}
