package id.alwination.slimefunpet.pets;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.muhammaddaffa.mdlib.utils.Logger;
import org.jspecify.annotations.Nullable;

import java.util.function.ToDoubleFunction;

/**
 * The food one pet keeps for itself, eaten on its own whenever its bar drops to
 * the threshold of its tier. Unlocked per pet with an unlocker item, and
 * upgraded tier by tier, each tier feeding earlier and opening more slots
 * ('auto-feed.tiers' in pets.yml).
 * <p>
 * Thread ownership: the menu changes it on the main thread while PetFoodTask
 * eats from it on its async timer, so every method locks this object. A slot is
 * only ever emptied by a single locked call, which is what keeps food taken out
 * through the menu from also being eaten, or the other way round.
 */
public class AutoFeed {

    private static final Gson GSON = new Gson();

    /**
     * The food slots the menu has room for, the middle of one row.
     */
    public static final int MAX_SLOTS = 5;

    private boolean unlocked;
    private boolean enabled = true;
    private int tier;
    // Per slot: the food item id ('items.food.<id>') and how many are stored,
    // null and 0 for an empty slot
    private String[] foods = new String[MAX_SLOTS];
    private int[] amounts = new int[MAX_SLOTS];

    public synchronized boolean isUnlocked() {
        return this.unlocked;
    }

    public synchronized void unlock() {
        this.unlocked = true;
    }

    public synchronized boolean isEnabled() {
        return this.enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Whether it should be eating: unlocked and switched on.
     */
    public synchronized boolean isActive() {
        return this.unlocked && this.enabled;
    }

    /**
     * 0 for the tier an unlock gives, see PetSettings.getAutoFeedThreshold.
     */
    public synchronized int getTier() {
        return this.tier;
    }

    public synchronized void upgrade() {
        this.tier++;
    }

    public synchronized @Nullable String getFoodId(int slot) {
        return this.foods[slot];
    }

    public synchronized int getAmount(int slot) {
        return this.amounts[slot];
    }

    public synchronized boolean hasFood() {
        for (int amount : this.amounts) {
            if (amount > 0) return true;
        }
        return false;
    }

    /**
     * Store up to {@code amount} of a food in a slot. A slot holds one food at a
     * time, at most a stack of it.
     *
     * @param openSlots how many slots the tier opens, the ones past it take nothing
     * @return how many were taken, 0 when none fit
     */
    public synchronized int deposit(int slot, String foodId, int amount, int maxStack, int openSlots) {
        if (!this.unlocked || slot < 0 || slot >= Math.min(openSlots, MAX_SLOTS) || amount <= 0) return 0;
        if (this.foods[slot] != null && !this.foods[slot].equals(foodId)) return 0;

        int taken = Math.min(amount, maxStack - this.amounts[slot]);
        if (taken <= 0) return 0;

        this.foods[slot] = foodId;
        this.amounts[slot] += taken;
        return taken;
    }

    /**
     * Empty a slot. Read {@link #getFoodId} first: the id of a slot only changes
     * through {@link #deposit}, which runs on the main thread like the menu.
     *
     * @return how many were in it
     */
    public synchronized int take(int slot) {
        int taken = this.amounts[slot];
        this.foods[slot] = null;
        this.amounts[slot] = 0;
        return taken;
    }

    /**
     * Eat one food from the first slot holding one the pet can use.
     *
     * @param foodValue the food an id restores, below 0 for one it cannot eat
     *                  (removed from pets.yml, or not allowed on this pet)
     * @return the food restored, -1 when nothing usable is left
     */
    public synchronized double eatOne(ToDoubleFunction<String> foodValue) {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            double restored = this.amounts[slot] > 0 ? foodValue.applyAsDouble(this.foods[slot]) : -1;
            if (restored >= 0) {
                this.amounts[slot]--;
                if (this.amounts[slot] <= 0) {
                    this.foods[slot] = null;
                }
                return restored;
            }
        }
        return -1;
    }

    /**
     * @return the saved form, null for a pet that never unlocked it
     */
    public synchronized @Nullable String toJson() {
        if (!this.unlocked) return null;

        Saved saved = new Saved();
        saved.unlocked = true;
        saved.enabled = this.enabled;
        saved.tier = this.tier;
        saved.foods = this.foods.clone();
        saved.amounts = this.amounts.clone();
        return GSON.toJson(saved);
    }

    /**
     * Replace everything with what was saved. A missing or broken value leaves
     * it locked and empty.
     */
    public synchronized void load(@Nullable String json) {
        Saved saved = null;
        if (json != null && !json.isBlank()) {
            try {
                saved = GSON.fromJson(json, Saved.class);
            } catch (JsonParseException ex) {
                Logger.severe("Failed to read the pet auto-feed '" + json + "': " + ex.getMessage());
            }
        }

        this.foods = new String[MAX_SLOTS];
        this.amounts = new int[MAX_SLOTS];
        if (saved == null) {
            this.unlocked = false;
            this.enabled = true;
            this.tier = 0;
            return;
        }

        this.unlocked = saved.unlocked;
        this.enabled = saved.enabled;
        this.tier = Math.max(0, saved.tier);
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            boolean stored = saved.foods != null && saved.amounts != null
                    && slot < saved.foods.length && slot < saved.amounts.length
                    && saved.foods[slot] != null && saved.amounts[slot] > 0;
            if (stored) {
                this.foods[slot] = saved.foods[slot];
                this.amounts[slot] = saved.amounts[slot];
            }
        }
    }

    /**
     * The row in the 'auto_feed' column, written and read as json. Its own
     * class so nothing reaches into an AutoFeed without its lock.
     */
    private static final class Saved {
        private boolean unlocked;
        private boolean enabled = true;
        private int tier;
        private String[] foods;
        private int[] amounts;
    }
}
