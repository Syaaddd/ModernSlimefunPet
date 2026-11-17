package id.alwination.slimefunpet.pets;

import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.pets.stats.PetStatKind;
import id.alwination.slimefunpet.pets.stats.PetStatRegistry;
import id.alwination.slimefunpet.pets.stats.PetStatType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One pet owned by a player, with its own level, food bar and rolled stats.
 */
public class PetPlayer {

    private final UUID owner;
    private final UUID uuid;

    private String id;
    private int level = 1;
    private int exp;

    // Food system
    private double food;
    private long lastFoodUpdate;
    private boolean dead;

    // Rolled stats
    private final List<PetStat> stats = new ArrayList<>();

    // Stats a stat-lock item protected for the reroll in progress, cleared
    // right after that reroll and never persisted
    private final Set<String> lockedStats = new HashSet<>();

    /**
     * The model file this pet is dressed in, chosen by the player, null for the
     * pet's own look. Kept as the lower case file name, which is how
     * PetModels files them.
     */
    private @Nullable String skin;

    // The food this pet keeps to eat on its own, locked until an unlocker is used
    private final AutoFeed autoFeed = new AutoFeed();
    /** Stay mode: the pet waits where it was left instead of following its owner. */
    private boolean stay;
    /** EXP below one point carried to the next food tick; memory only, at most one point is lost on a restart. */
    private double expFraction;

    public PetPlayer(UUID owner, String id) {
        this.owner = owner;
        this.uuid = UUID.randomUUID();
        this.id = id;
        this.food = PetSettings.getMaxFood();
        this.lastFoodUpdate = System.currentTimeMillis();
    }

    public PetPlayer(UUID owner, UUID uuid, String id, int level, int exp,
                     double food, long lastFoodUpdate, boolean dead, List<PetStat> stats) {
        this.owner = owner;
        this.uuid = uuid;
        this.id = id;
        this.level = level;
        this.exp = exp;
        // Never trust the stored value. Lowering 'food.max' in the config, or
        // an older row, can leave a pet holding more food than the bar can
        // show, which then renders as a percentage over 100
        this.food = Math.max(0, Math.min(PetSettings.getMaxFood(), food));
        this.lastFoodUpdate = lastFoodUpdate;
        this.dead = dead;
        if (stats != null) {
            this.stats.addAll(stats);
        }
    }

    public UUID getOwner() {
        return owner;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public String getId() {
        return id;
    }

    /**
     * Only used by the evolution, a pet becomes another pet id.
     */
    public void setId(String id) {
        this.id = id;
    }

    public @Nullable String getSkin() {
        return skin;
    }

    /**
     * @param skin a model file name, blank or null to go back to the pet's own look
     */
    public void setSkin(@Nullable String skin) {
        String cleaned = skin == null ? "" : skin.trim().toLowerCase(Locale.ROOT);
        this.skin = cleaned.isEmpty() ? null : cleaned;
    }

    public double getExpFraction() {
        return this.expFraction;
    }

    public void setExpFraction(double expFraction) {
        this.expFraction = expFraction;
    }

    public boolean isStay() {
        return this.stay;
    }

    public void setStay(boolean stay) {
        this.stay = stay;
    }

    public AutoFeed getAutoFeed() {
        return autoFeed;
    }

    public int getLevel() {
        if (level < 1) {
            level = 1;
        }
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
    }

    public int getExp() {
        return exp;
    }

    public void setExp(int exp) {
        this.exp = Math.max(0, exp);
    }

    // ------------------------------------------------------------------
    //  Food
    // ------------------------------------------------------------------

    public double getFood() {
        return food;
    }

    public void setFood(double food) {
        this.food = Math.max(0, Math.min(PetSettings.getMaxFood(), food));
        if (this.food <= 0) {
            this.dead = true;
        }
    }

    public void addFood(double amount) {
        this.setFood(this.food + amount);
    }

    /**
     * The food bar as a value between 0.0 and 1.0.
     */
    public double getFoodProgress() {
        double max = PetSettings.getMaxFood();
        if (max <= 0) return 0;
        return Math.max(0, Math.min(1, this.food / max));
    }

    public long getLastFoodUpdate() {
        return lastFoodUpdate;
    }

    public void setLastFoodUpdate(long lastFoodUpdate) {
        this.lastFoodUpdate = lastFoodUpdate;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    /**
     * A pet only gives its boosts while it is alive and has food left.
     */
    public boolean isActive() {
        return !this.dead && this.food > 0;
    }

    /**
     * Bring a dead pet back with the configured amount of food.
     */
    public void revive(double foodRestored) {
        this.dead = false;
        this.food = Math.max(0, Math.min(PetSettings.getMaxFood(), foodRestored));
        this.lastFoodUpdate = System.currentTimeMillis();
        // A revive that restores nothing would let the pet die again right away
        if (this.food <= 0) {
            this.dead = true;
        }
    }

    // ------------------------------------------------------------------
    //  Stats
    // ------------------------------------------------------------------

    public List<PetStat> getStats() {
        return Collections.unmodifiableList(this.stats);
    }

    public void setStats(List<PetStat> stats) {
        this.stats.clear();
        if (stats != null) {
            this.stats.addAll(stats);
        }
    }

    /**
     * How many more stats this pet could still hold, 0 once it is full.
     */
    public int getFreeStatSlots(Pet pet) {
        int max = pet == null ? PetSettings.getMaxStats() : pet.getMaxStats();
        return Math.max(0, max - this.stats.size());
    }

    /**
     * Whether the pet is holding everything it is allowed to.
     */
    public boolean isStatsFull(Pet pet) {
        return this.getFreeStatSlots(pet) <= 0;
    }

    /**
     * Roll a completely new set of stats, used by the reroll item.
     * <p>
     * Never rolls more than the pet can hold, so a 5 stat reroll on a pet with
     * room for 3 lands on 3. Whatever a stat-lock item locked beforehand is
     * kept as-is instead of being thrown away with the rest.
     * <p>
     * When 2 or more new stats are rolled, one of them is always a BAD one, a
     * locked BAD stat not counting towards it. The rest roll from the whole
     * pool and may land BAD too. A single new stat (a 1-stat pet, or every
     * other stat locked) rolls by weight alone, otherwise it would be a curse
     * every time. A pet whose pool holds no BAD stat simply rolls without one.
     *
     * @return how many new stats were rolled, not counting the locked ones
     */
    public int rerollStats(Pet pet, int amount) {
        int max = pet == null ? PetSettings.getMaxStats() : pet.getMaxStats();
        Map<String, Double> pool = pet == null ? null : pet.getStatPool();

        List<PetStat> kept = new ArrayList<>();
        for (PetStat stat : this.stats) {
            if (this.lockedStats.contains(stat.key())) {
                kept.add(stat);
            }
        }

        int toRoll = Math.max(0, Math.min(amount, max) - kept.size());
        List<PetStat> rolled = PetStatRegistry.roll(toRoll >= 2 ? 1 : 0, kept, pool, PetStatType.BAD);
        List<PetStat> exclude = new ArrayList<>(kept);
        exclude.addAll(rolled);
        rolled.addAll(PetStatRegistry.roll(toRoll - rolled.size(), exclude, pool));
        this.stats.clear();
        this.stats.addAll(kept);
        this.stats.addAll(rolled);
        return rolled.size();
    }

    /**
     * Whether one more stat may be locked at all.
     * <p>
     * A reroll has to be left at least one stat it is allowed to touch. With
     * every stat protected a burn finds nothing to take and a reroll finds
     * nothing to replace, yet the token, the charms and the money are all spent
     * anyway, so locking the only stat of a pet is refused outright. On top of
     * that, no more than 'stats.max-stat-locks' stats per reroll.
     */
    public boolean canLockAnother() {
        return this.lockedStats.size() < this.stats.size() - 1 && !this.isAtLockLimit();
    }

    /**
     * Whether this attempt already protects as many stats as one reroll may.
     */
    public boolean isAtLockLimit() {
        return this.lockedStats.size() >= PetSettings.getMaxStatLocks();
    }

    /**
     * Lock one named stat of this pet so a reroll right after this spares it.
     * Used by a stat-lock item picked in the stat lock menu.
     *
     * @return true if a stat was actually locked, false when the pet is not
     *         holding that stat, it was already locked, or it is the last one
     *         the reroll could still touch
     */
    public boolean lockStat(String key) {
        if (key == null || !this.hasStat(key) || !this.canLockAnother()) return false;
        return this.lockedStats.add(key);
    }

    public boolean unlockStat(String key) {
        return key != null && this.lockedStats.remove(key);
    }

    public boolean isStatLocked(String key) {
        return key != null && this.lockedStats.contains(key);
    }

    /**
     * How many stats are protected for the reroll in progress, which is also
     * how many charms that attempt has to pay for.
     */
    public int getLockedStatCount() {
        return this.lockedStats.size();
    }

    private boolean hasStat(String key) {
        for (PetStat stat : this.stats) {
            if (stat.key().equals(key)) return true;
        }
        return false;
    }

    /**
     * Clear every stat-lock, whether or not the reroll they were locked for
     * actually happened.
     */
    public void clearLockedStats() {
        this.lockedStats.clear();
    }

    /**
     * Add stats the pet doesn't have yet, used when it evolves.
     * <p>
     * A pet never ends up holding more than its 'max-stats', so a pet already
     * full gains nothing at all.
     *
     * @return how many were actually added, which is less than asked for once
     *         the pet is full or its pool has nothing new left to give
     */
    public int rollExtraStats(Pet pet, int amount) {
        int room = this.getFreeStatSlots(pet);
        if (room <= 0 || amount <= 0) return 0;

        List<PetStat> rolled = PetStatRegistry.roll(Math.min(amount, room), this.stats,
                pet == null ? null : pet.getStatPool());
        this.stats.addAll(rolled);
        return rolled.size();
    }

    /**
     * Take stats away from the pet, picked at random. A locked stat is never
     * picked, so a reroll item that burns a stat off cannot take the one a
     * stat-lock item just protected.
     *
     * @return how many were actually taken, which is less than asked for when
     *         the pet did not have that many removable stats to begin with
     */
    public int removeRandomStats(int amount) {
        int removed = 0;
        while (removed < amount) {
            List<PetStat> removable = new ArrayList<>();
            for (PetStat stat : this.stats) {
                if (!this.lockedStats.contains(stat.key())) {
                    removable.add(stat);
                }
            }
            if (removable.isEmpty()) break;

            this.stats.remove(removable.get(ThreadLocalRandom.current().nextInt(removable.size())));
            removed++;
        }
        return removed;
    }

    /**
     * Every stat this pet gives: the guaranteed ones of its pet id first, then
     * the rolled ones. A guaranteed stat and a rolled one can share a key, both
     * count.
     */
    public List<PetStat> getActiveStats(Pet pet) {
        if (pet == null || pet.getGuaranteedStats().isEmpty()) return this.getStats();
        List<PetStat> active = new ArrayList<>(pet.getGuaranteedStats());
        active.addAll(this.stats);
        return active;
    }

    /**
     * The total value of every stat of a kind, guaranteed and rolled, scaled
     * by the level of the pet.
     */
    public double getStatValue(Pet pet, PetStatKind kind) {
        double total = 0;
        for (PetStat stat : this.getActiveStats(pet)) {
            PetStatDefinition definition = stat.getDefinition();
            if (definition != null && definition.kind() == kind) {
                total += this.getScaledValue(stat);
            }
        }
        return total;
    }

    /**
     * What a rolled stat is actually worth at the current level, a level 1 pet
     * is worth exactly what it rolled.
     */
    public double getScaledValue(PetStat stat) {
        PetStatDefinition definition = stat.getDefinition();
        // Effect levels and the heal never scale (C1): the item must show what the effect task gives
        if (definition != null && (definition.kind() == PetStatKind.POTION_EFFECT || definition.kind() == PetStatKind.HEAL)) {
            return stat.value();
        }
        return stat.value() * PetSettings.getLevelScaling(this.getLevel());
    }

    // ------------------------------------------------------------------
    //  Level
    // ------------------------------------------------------------------

    /**
     * Give exp to the pet, leveling it up as many times as the amount allows
     * and keeping the leftover exp for the next level.
     * <p>
     * Exp only comes from feeding the pet or from a candy item, a dead pet
     * gains nothing.
     *
     * @return how many levels the pet gained
     */
    public int addExp(Pet pet, int amount) {
        if (pet == null || amount <= 0 || !this.isActive()) {
            return 0;
        }

        int previousLevel = this.getLevel();
        int total = this.exp + amount;

        while (this.level < pet.getMaxLevel()) {
            int required = pet.getLevelCost(this.level);
            if (required <= 0 || total < required) break;
            total -= required;
            this.level++;
        }

        // A maxed pet keeps no leftover exp
        this.exp = this.level >= pet.getMaxLevel() ? 0 : total;
        return this.level - previousLevel;
    }

    /**
     * Level the pet up by a fixed amount, used by the candy item.
     *
     * @return how many levels the pet actually gained
     */
    public int addLevel(Pet pet, int amount) {
        if (pet == null || amount <= 0) return 0;
        int previousLevel = this.getLevel();
        this.setLevel(Math.min(this.level + amount, pet.getMaxLevel()));
        this.exp = 0;
        return this.level - previousLevel;
    }

    public boolean isMaxLevel(Pet pet) {
        return pet != null && this.getLevel() >= pet.getMaxLevel();
    }

    /**
     * The exp needed to reach the next level.
     */
    public int getRequiredExp(Pet pet) {
        return pet == null ? 0 : pet.getLevelCost(this.getLevel());
    }

    public boolean isOwnedBy(UUID uuid) {
        return this.owner.equals(uuid);
    }

    public boolean isEquipped() {
        return ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.owner).isPetEquipped(this.uuid);
    }

}
