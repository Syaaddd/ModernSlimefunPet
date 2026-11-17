package id.alwination.slimefunpet.pets;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the plugin remembers about a player apart from their pets: which pets are equipped, the
 * hide toggle, and the AFK XP progress that must survive a restart (the daily cap especially).
 * <p>
 * Takes the place of ModernMineGens' User for the pet code, so its pet methods keep User's names.
 * Only touched from the main thread.
 */
public final class PlayerProfile {

    private final UUID owner;
    private final Set<UUID> equippedPets = new LinkedHashSet<>();
    private boolean toggleHidePet;
    private long afkProgressMillis;
    private int dailyXp;
    /** Server-local date the daily XP counts for, as yyyy-MM-dd; empty when nothing was paid yet. */
    private String dailyDate = "";

    public PlayerProfile(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() {
        return this.owner;
    }

    /** Every pet the player has out, in the order they were equipped. */
    public Set<UUID> getEquippedPets() {
        return Collections.unmodifiableSet(this.equippedPets);
    }

    public void setEquippedPets(Collection<UUID> equippedPets) {
        this.equippedPets.clear();
        this.equippedPets.addAll(equippedPets);
    }

    public boolean isPetEquipped(@Nullable UUID uuid) {
        return uuid != null && this.equippedPets.contains(uuid);
    }

    public void equipPet(UUID uuid) {
        this.equippedPets.add(uuid);
    }

    public void unequipPet(UUID uuid) {
        this.equippedPets.remove(uuid);
    }

    public void clearEquippedPets() {
        this.equippedPets.clear();
    }

    /** The first equipped pet, for the places that only care about one. */
    public @Nullable UUID getEquippedPet() {
        return this.equippedPets.isEmpty() ? null : this.equippedPets.iterator().next();
    }

    public boolean isToggleHidePet() {
        return this.toggleHidePet;
    }

    public void setToggleHidePet(boolean toggleHidePet) {
        this.toggleHidePet = toggleHidePet;
    }

    public long getAfkProgressMillis() {
        return this.afkProgressMillis;
    }

    public void setAfkProgressMillis(long afkProgressMillis) {
        this.afkProgressMillis = afkProgressMillis;
    }

    public int getDailyXp() {
        return this.dailyXp;
    }

    public void setDailyXp(int dailyXp) {
        this.dailyXp = dailyXp;
    }

    public String getDailyDate() {
        return this.dailyDate;
    }

    public void setDailyDate(String dailyDate) {
        this.dailyDate = dailyDate;
    }
}
