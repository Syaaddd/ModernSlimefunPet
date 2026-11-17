package id.alwination.slimefunpet.pets.tasks;

import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.AutoFeed;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.stats.PetStatKind;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleToIntFunction;

/**
 * Drains the food bar of every pet over time and kills the ones that run out.
 * <p>
 * The decay is worked out from the time that actually passed instead of being
 * added up tick by tick, so a pet keeps getting hungry while its owner is
 * offline and the bar is correct again the moment the data is loaded back.
 */
public class PetFoodTask {

    private HandleTask task;

    public void start() {
        this.stop();
        long intervalTicks = PetSettings.getFoodTaskIntervalSeconds() * 20L;
        // Main thread: pet state is only ever touched there (feeds, menus, the save queue)
        this.task = Executor.syncTimer(intervalTicks, intervalTicks, this::tick);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (PetPlayer petPlayer : PetManager.getAllPetPlayers()) {
            if (applyDecay(petPlayer, now, false)) {
                DatabaseQueueHandler.addObjectToSave(petPlayer);
            }
        }
    }

    /**
     * Catch every pet up with the time that passed while the server, or its
     * owner, was away. Called right after the pets are loaded from the database.
     */
    public static void applyOfflineDecay(Collection<PetPlayer> petPlayers) {
        if (!PetSettings.isOfflineDecay()) {
            // Reset the clock so the missed time is not applied later on either
            long now = System.currentTimeMillis();
            for (PetPlayer petPlayer : petPlayers) {
                petPlayer.setLastFoodUpdate(now);
            }
            return;
        }

        long now = System.currentTimeMillis();
        int starved = 0;
        for (PetPlayer petPlayer : petPlayers) {
            if (applyDecay(petPlayer, now, true) && petPlayer.isDead()) {
                starved++;
            }
        }
        if (starved > 0) {
            Logger.info(starved + " pets starved to death while the server was offline!");
        }
    }

    /**
     * Drain the food of a single pet based on how long ago it was last updated.
     *
     * @param offline whether this is the catch-up done on load, which is capped
     *                by 'food.max-offline-hours'
     * @return true when the pet changed and has to be saved
     */
    public static boolean applyDecay(PetPlayer petPlayer, long now, boolean offline) {
        // A dead pet has nothing left to lose
        if (petPlayer.isDead()) {
            petPlayer.setLastFoodUpdate(now);
            return false;
        }

        // A pet only gets hungry while it is actually out, one sitting in the
        // menu keeps its bar. Moving the stamp forward means the time it spent
        // unequipped is never charged to it later on.
        // The stamp is kept in memory only, writing it back every tick would
        // rewrite the whole table for nothing. A stale stamp in the database is
        // harmless because this same branch re-stamps the pet when it is loaded.
        if (!petPlayer.isEquipped()) {
            petPlayer.setLastFoodUpdate(now);
            return false;
        }

        long last = petPlayer.getLastFoodUpdate();
        // A pet that was never stamped starts counting from now
        if (last <= 0) {
            petPlayer.setLastFoodUpdate(now);
            return true;
        }

        double hours = (now - last) / 3_600_000.0;
        if (hours <= 0) return false;

        // Never punish a player for a month long break more than the config allows
        if (offline && PetSettings.getMaxOfflineHours() > 0) {
            hours = Math.min(hours, PetSettings.getMaxOfflineHours());
        }

        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(petPlayer.getId());
        double perHour = PetSettings.getDecayPerHour() * (pet == null ? 1 : pet.getFoodDecayMultiplier());

        // The world the owner is standing in right now can burn hunger faster,
        // only while they are online to actually be in one
        Player owner = Bukkit.getPlayer(petPlayer.getOwner());
        if (owner != null) {
            perHour *= PetSettings.getWorldDecayMultiplier(owner.getWorld().getName());
        }

        // The food saver stat slows the decay down, 100 stops it completely
        double saver = petPlayer.getStatValue(pet, PetStatKind.FOOD_SAVER);
        perHour *= Math.max(0, 1 - saver / 100.0);

        petPlayer.setLastFoodUpdate(now);
        if (perHour <= 0) return true;

        double lost = perHour * hours;
        if (lost <= 0) return true;

        // Auto-Feed eats whenever the bar is at its tier's threshold
        AutoFeed autoFeed = petPlayer.getAutoFeed();
        double feedAt = autoFeed.isActive()
                ? PetSettings.getMaxFood() * PetSettings.getAutoFeedThreshold(autoFeed.getTier()) / 100.0
                : -1;
        boolean hadFood = autoFeed.hasFood();

        boolean wasAlive = !petPlayer.isDead();
        int gained = burn(petPlayer, lost, feedAt,
                () -> autoFeed.eatOne(id -> autoFeedValue(id, pet)),
                burned -> grantHungerExp(petPlayer, pet, burned));

        if (hadFood && !autoFeed.hasFood()) {
            notifyAutoFeedEmpty(petPlayer, pet);
        }
        // setFood kills the pet once the bar empties out
        if (wasAlive && petPlayer.isDead()) {
            notifyDeath(petPlayer);
        } else if (gained > 0) {
            notifyLevelUp(petPlayer, pet, gained);
        }
        return true;
    }

    /**
     * Burn {@code lost} food off the bar, eating stored food each time the bar
     * gets down to {@code feedAt}. Burning and eating take turns, so a long
     * offline stretch eats as it goes instead of starving first and finding the
     * food after.
     * <p>
     * Every piece burned is digested into exp before the bar is lowered, while
     * the pet is still alive to take it.
     *
     * @param feedAt the bar at or below which the pet eats, below 0 when Auto-Feed is off
     * @param eat    eats one stored food and returns what it restored, -1 once none is left
     * @param digest turns burned food into exp, returns the levels gained
     * @return the levels gained
     */
    static int burn(PetPlayer petPlayer, double lost, double feedAt, DoubleSupplier eat, DoubleToIntFunction digest) {
        int gained = 0;
        boolean canEat = feedAt >= 0;
        double left = lost;
        while (left > 0 && !petPlayer.isDead()) {
            double food = petPlayer.getFood();
            if (canEat && food <= feedAt) {
                double restored = eat.getAsDouble();
                if (restored < 0) {
                    canEat = false;
                } else {
                    petPlayer.addFood(restored);
                }
                continue;
            }

            // Down to the line it eats at next, or to empty once nothing is left
            double room = canEat ? food - feedAt : food;
            boolean reachesLine = left >= room;
            double drop = reachesLine ? room : left;
            gained += digest.applyAsInt(drop);
            left -= drop;
            // Land on the line exactly, not a rounding error above it, so the
            // next turn eats; an empty bar kills the pet
            petPlayer.setFood(canEat && reachesLine ? feedAt : food - drop);
        }
        return gained;
    }

    /**
     * The food a stored item restores, -1 when the pet cannot eat it: the item
     * was removed from pets.yml or is not allowed on this pet. Its commands are
     * not ran, those need the owner online and the main thread.
     */
    private static double autoFeedValue(String id, Pet pet) {
        PetItem item = PetItemRegistry.get(PetItemType.FOOD, id);
        if (item == null || !item.allows(pet)) return -1;
        return item.food();
    }

    /**
     * Turn the food a pet just burned into exp.
     * <p>
     * Every 1 food is worth a flat amount of exp, so a pet levels up by staying
     * fed and equipped rather than by being fed a lot at once. Because the exp
     * does not scale with the level cost, a 'level-cost' that climbs per level
     * is what makes the late levels take longer.
     *
     * @return how many levels the pet gained
     */
    private static int grantHungerExp(PetPlayer petPlayer, Pet pet, double burned) {
        if (pet == null || burned <= 0) return 0;

        // Every pet has its own rate, which can be an expression, so it is read
        // at the level the pet is on right now rather than being one flat
        // number for the whole climb
        double expPerFood = pet.getExpPerFood(petPlayer.getLevel());
        if (expPerFood <= 0) return 0;

        // The pet exp stat makes digesting the same food worth more
        double bonus = petPlayer.getStatValue(pet, PetStatKind.PET_EXP);

        int exp = wholeExp(petPlayer, expPerFood * burned * (1 + bonus / 100.0));
        if (exp <= 0) return 0;

        return petPlayer.addExp(pet, exp);
    }

    /**
     * Tell the owner their pet grew, if they are around to hear it.
     */
    /**
     * The whole EXP points out of an exact amount, keeping the fraction on the pet for the next
     * tick. Rounding every tick on its own let a slow burn round to 0 forever (handoff section 10).
     */
    static int wholeExp(PetPlayer petPlayer, double exact) {
        double total = exact + petPlayer.getExpFraction();
        // The epsilon keeps 0.3 x 10 (2.9999...) from flooring to 2
        int whole = (int) Math.floor(total + 1e-9);
        petPlayer.setExpFraction(Math.max(0, total - whole));
        return whole;
    }

    private static void notifyLevelUp(PetPlayer petPlayer, Pet pet, int gained) {
        Executor.sync(() -> {
            Player owner = Bukkit.getPlayer(petPlayer.getOwner());
            if (owner == null) return;

            ModernSlimefunPet.getInstance().getPetService().onLevelUp(owner, pet, petPlayer, gained);
        });
    }

    /**
     * Tell the owner the Auto-Feed of a pet just ate its last food.
     */
    private static void notifyAutoFeedEmpty(PetPlayer petPlayer, Pet pet) {
        if (pet == null) return;
        Executor.sync(() -> {
            Player owner = Bukkit.getPlayer(petPlayer.getOwner());
            if (owner == null) return;

            ModernSlimefunPet.PETS_CONFIG.sendMessage(owner, "messages.pet-auto-feed-empty", new Placeholder()
                    .add("{pet}", pet.getItemDisplayName()));
        });
    }

    /**
     * Tell the owner their pet starved, and take it off their shoulder.
     */
    private static void notifyDeath(PetPlayer petPlayer) {
        Executor.sync(() -> {
            PlayerProfile profile = ModernSlimefunPet.getInstance().getProfileManager().getProfile(petPlayer.getOwner());
            profile.unequipPet(petPlayer.getUniqueId());
            // Saved, or after a restart the dead pet would come back equipped (handoff section 10)
            DatabaseQueueHandler.addObjectToSave(profile);

            Player owner = Bukkit.getPlayer(petPlayer.getOwner());
            if (owner == null) return;

            ModernSlimefunPet.getInstance().getPetTask().refreshFor(owner);

            Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(petPlayer.getId());
            if (pet == null || !ModernSlimefunPet.PETS_CONFIG.getConfig().isSet("messages.pet-starved")) return;

            ModernSlimefunPet.PETS_CONFIG.sendMessage(owner, "messages.pet-starved", new Placeholder()
                    .add("{pet}", pet.getItemDisplayName()));
        });
    }

}
