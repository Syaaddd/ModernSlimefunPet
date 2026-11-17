package id.alwination.slimefunpet.pets.items;

import org.jspecify.annotations.Nullable;

/**
 * The kind of pet item a player can be holding, each one opens a menu of its
 * own so the layout, the title and the buttons of a feeding menu have nothing
 * to do with the ones of a revival menu.
 */
public enum PetItemType {

    /**
     * Restores food and gives exp to the pet.
     */
    FOOD("pet_food_gui.yml"),
    /**
     * Instantly levels the pet up.
     */
    CANDY("pet_candy_gui.yml"),
    /**
     * Evolves the pet into the next one of its chain, with a success rate.
     * <p>
     * This is the menu the pet is picked in, the evolution itself then happens
     * in 'pet_evolution_gui.yml'.
     */
    EVOLUTION("pet_evolution_select_gui.yml"),
    /**
     * Rolls a completely new set of stats.
     */
    REROLL("pet_reroll_gui.yml"),
    /**
     * Brings a dead pet back.
     */
    REVIVE("pet_revive_gui.yml"),
    /**
     * Protects one named current stat of a pet from a reroll. Never used on its
     * own: the stats to protect are picked in the stat lock menu reached from
     * the reroll confirmation, one charm per stat, and they are spent with the
     * reroll item, so it has no menu of its own either.
     */
    STAT_LOCK(null),
    /**
     * Unlocks the Auto-Feed of one pet. Used up by clicking the locked Auto-Feed
     * button in the pet options menu, so it has no menu of its own.
     */
    AUTO_FEED_UNLOCKER(null),
    /**
     * Raises the Auto-Feed of one pet a tier: it feeds earlier and opens more
     * food slots. Used up from the Auto-Feed menu, no menu of its own.
     */
    AUTO_FEED_UPGRADE(null);

    private final String guiFile;

    PetItemType(String guiFile) {
        this.guiFile = guiFile;
    }

    /**
     * The file under '/gui' holding the menu of this item, null for the types
     * that never open one.
     */
    @Nullable
    public String getGuiFile() {
        return this.guiFile;
    }

    /**
     * Whether using one of these opens a menu to pick a pet in.
     */
    public boolean hasGui() {
        return this.guiFile != null;
    }

    /**
     * The section holding these items in pets.yml.
     */
    public String getConfigSection() {
        return "items." + this.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PetItemType fromString(String name) {
        if (name == null) return null;
        for (PetItemType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

}
