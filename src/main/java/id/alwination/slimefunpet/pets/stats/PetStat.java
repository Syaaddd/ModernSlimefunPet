package id.alwination.slimefunpet.pets.stats;

/**
 * A stat that has been rolled onto a pet.
 * <p>
 * Only the key and the value are stored in the database, everything else
 * (display name, kind, potion effect...) is read back from the config through
 * {@link PetStatRegistry}, so changing the config updates every existing pet.
 */
public record PetStat(String key, double value) {

    /**
     * The definition of this stat, or null when the key was removed
     * from the config.
     */
    public PetStatDefinition getDefinition() {
        return PetStatRegistry.get(this.key);
    }

    public boolean isValid() {
        return this.getDefinition() != null;
    }

}
