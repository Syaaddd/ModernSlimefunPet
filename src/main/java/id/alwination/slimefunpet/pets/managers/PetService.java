package id.alwination.slimefunpet.pets.managers;

import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.evolution.EvolutionRequirement;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.RerollOutcome;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.pets.stats.PetStatKind;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything that can be done to a pet: feeding it, leveling it with a candy,
 * evolving it, rerolling its stats and reviving it, plus the boost every
 * equipped pet of a player adds up to.
 */
public class PetService {

    private final PetManager petManager;

    public PetService(PetManager petManager) {
        this.petManager = petManager;
    }

    // ------------------------------------------------------------------
    //  Boosts
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  Feeding
    // ------------------------------------------------------------------

    /**
     * Feed a pet, restoring food and giving exp.
     *
     * @return true when the food was used
     */
    public boolean feed(Player player, PetPlayer petPlayer, PetItem food) {
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null) return false;
        if (!this.allowsPet(player, pet, food)) return false;

        if (petPlayer.isDead()) {
            this.message(player, "messages.pet-dead");
            return false;
        }
        if (petPlayer.getFood() >= PetSettings.getMaxFood()) {
            this.message(player, "messages.pet-not-hungry");
            return false;
        }

        // Food only fills the bar back up, the exp comes from burning it later
        // while the pet is equipped, see PetFoodTask
        petPlayer.addFood(food == null ? PetSettings.getMaxFood() : food.food());
        petPlayer.setLastFoodUpdate(System.currentTimeMillis());

        this.runCommands(player, food == null ? null : food.commands());
        this.save(petPlayer);

        this.message(player, "messages.pet-fed", new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{restored}", Common.digits(food == null ? PetSettings.getMaxFood() : food.food()))
                .add("{food}", Common.digits(petPlayer.getFood()))
                .add("{max_food}", Common.digits(PetSettings.getMaxFood())));
        return true;
    }

    /**
     * Instantly level a pet up with a candy.
     */
    public boolean useCandy(Player player, PetPlayer petPlayer, PetItem candy) {
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null) return false;
        if (!this.allowsPet(player, pet, candy)) return false;

        if (petPlayer.isDead()) {
            this.message(player, "messages.pet-dead");
            return false;
        }
        if (petPlayer.isMaxLevel(pet)) {
            this.message(player, "messages.pet-max-level");
            return false;
        }

        int gained = petPlayer.addLevel(pet, Math.max(1, candy == null ? 1 : candy.levels()));
        if (gained <= 0) {
            this.message(player, "messages.pet-max-level");
            return false;
        }

        this.runCommands(player, candy == null ? null : candy.commands());
        this.save(petPlayer);
        this.onLevelUp(player, pet, petPlayer, gained);
        return true;
    }

    // ------------------------------------------------------------------
    //  Evolution
    // ------------------------------------------------------------------

    /**
     * Try to evolve a pet, the evolution can fail based on its chance.
     *
     * @return the outcome, so the caller knows whether to take the item
     */
    public EvolutionResult evolve(Player player, PetPlayer petPlayer, @Nullable PetItem evolutionItem) {
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null) return EvolutionResult.INVALID;
        if (!this.allowsPet(player, pet, evolutionItem)) return EvolutionResult.INVALID;

        Pet.PetEvolution evolution = pet.getEvolution();
        if (evolution == null) {
            this.message(player, "messages.pet-no-evolution");
            return EvolutionResult.INVALID;
        }
        if (petPlayer.isDead()) {
            this.message(player, "messages.pet-dead");
            return EvolutionResult.INVALID;
        }
        if (petPlayer.getLevel() < evolution.requiredLevel()) {
            this.message(player, "messages.pet-evolution-level", new Placeholder()
                    .add("{level}", Common.digits(evolution.requiredLevel())));
            return EvolutionResult.INVALID;
        }
        // The pet only takes the evolution items it names. A menu opened by
        // command has no stone behind it, so there is nothing to match
        if (evolutionItem != null && !evolution.accepts(evolutionItem.id())) {
            this.message(player, "messages.pet-wrong-evolution-item");
            return EvolutionResult.INVALID;
        }

        Pet target = this.petManager.getPet(evolution.into());
        if (target == null) {
            this.message(player, "messages.pet-no-evolution");
            return EvolutionResult.INVALID;
        }

        // Everything the evolution asks for on top of the item, checked before
        // anything at all is taken so a player is never left half charged
        for (EvolutionRequirement requirement : evolution.requirements()) {
            if (!requirement.isMet(player)) {
                this.message(player, requirement.failureMessageKey(), requirement.failurePlaceholders(player));
                return EvolutionResult.INVALID;
            }
        }

        // Take the money cost before rolling the chance
        double cost = evolutionItem == null ? 0 : evolutionItem.moneyCost();
        if (!Money.has(player, cost) || !Money.take(player, cost)) {
            this.message(player, "messages.pet-not-enough-money", new Placeholder()
                    .add("{cost}", Common.digits(cost)));
            return EvolutionResult.INVALID;
        }

        // The requirements are the price of the attempt, not of the success,
        // so they are taken before the roll just like the money cost is
        for (EvolutionRequirement requirement : evolution.requirements()) {
            requirement.consume(player);
        }

        // A better stone makes the same evolution more likely to work
        double chance = evolution.chanceWith(evolutionItem == null ? 0 : evolutionItem.chanceBonus());

        // Roll the success rate
        if (ThreadLocalRandom.current().nextDouble(100) >= chance) {
            // A failed attempt can cost the levels as well as the stone, which
            // is what makes waiting for a better stone worth doing
            int lostLevels = 0;
            if (evolution.resetLevelOnFail()) {
                lostLevels = petPlayer.getLevel() - 1;
                petPlayer.setLevel(1);
                petPlayer.setExp(0);
            }

            Placeholder placeholder = new Placeholder()
                    .add("{pet}", pet.getItemDisplayName())
                    .add("{chance}", Common.digits(chance))
                    .add("{success_chance}", Common.digits(chance))
                    .add("{fail_chance}", Common.digits(100 - chance))
                    .add("{lost_levels}", Common.digits(lostLevels))
                    .add("{level}", Common.digits(petPlayer.getLevel()));

            this.message(player, "messages.pet-evolution-failed", placeholder);
            // Only worth saying when the reset actually cost them something
            if (lostLevels > 0) {
                this.message(player, "messages.pet-evolution-levels-lost", placeholder);
            }

            this.save(petPlayer);
            // The level is part of the name tag, so a reset has to be redrawn
            if (lostLevels > 0) {
                this.refreshDisplay(player, petPlayer);
            }
            return evolution.loseItemOnFail() ? EvolutionResult.FAILED_CONSUME : EvolutionResult.FAILED_KEEP;
        }

        // The pet becomes the next one of the chain, keeping its stats
        petPlayer.setId(target.getId());
        if (!evolution.keepLevel()) {
            petPlayer.setLevel(1);
            petPlayer.setExp(0);
        } else {
            petPlayer.setLevel(Math.min(petPlayer.getLevel(), target.getMaxLevel()));
        }
        // Evolving rolls extra stats on top of the ones it already has, out of
        // the pool of the pet it just became
        petPlayer.rollExtraStats(target, evolution.bonusStats());

        this.runCommands(player, evolutionItem == null ? null : evolutionItem.commands());
        this.save(petPlayer);
        this.refreshDisplay(player, petPlayer);

        this.message(player, "messages.pet-evolved", new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{new_pet}", target.getItemDisplayName())
                .add("{chance}", Common.digits(chance))
                .add("{success_chance}", Common.digits(chance))
                .add("{fail_chance}", Common.digits(100 - chance)));
        return EvolutionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Reroll & revive
    // ------------------------------------------------------------------

    /**
     * Use a reroll item on a pet.
     * <p>
     * An item with an 'outcomes' section rolls one of them every time it is
     * used, so the same token can reroll the stats most of the time and burn a
     * single one off now and then. An item without one always does what its
     * 'stats' says: a positive amount rolls that many new stats on top of the
     * ones the pet has, a negative one takes that many away, and 0 throws the
     * whole set away and rolls a fresh one.
     */
    public boolean reroll(Player player, PetPlayer petPlayer, PetItem rerollItem) {
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null) return false;
        if (!this.allowsPet(player, pet, rerollItem)) return false;

        RerollOutcome outcome = this.pickOutcome(petPlayer, pet, rerollItem);

        // Nothing this item can do would change anything on this pet: it only
        // takes stats off a pet that has none, or only adds to a full one
        if (outcome == null) {
            this.message(player, petPlayer.getStats().isEmpty()
                            ? "messages.pet-no-stats" : "messages.pet-stats-full",
                    new Placeholder()
                            .add("{pet}", pet.getItemDisplayName())
                            .add("{max}", Common.digits(pet.getMaxStats())));
            return false;
        }

        double cost = rerollItem == null ? 0 : rerollItem.moneyCost();
        if (!Money.has(player, cost) || !Money.take(player, cost)) {
            this.message(player, "messages.pet-not-enough-money", new Placeholder()
                    .add("{cost}", Common.digits(cost)));
            return false;
        }

        String messageKey;
        int amount;
        switch (outcome.kind()) {
            case ADD -> {
                amount = petPlayer.rollExtraStats(pet, outcome.amount());
                messageKey = "messages.pet-stats-added";
            }
            case REMOVE -> {
                amount = petPlayer.removeRandomStats(outcome.amount());
                messageKey = "messages.pet-stats-removed";
            }
            default -> {
                int asked = outcome.amount() > 0 ? outcome.amount() : PetSettings.getRerollStatAmount();
                amount = petPlayer.rerollStats(pet, asked);
                messageKey = "messages.pet-rerolled";
            }
        }

        this.runCommands(player, rerollItem == null ? null : rerollItem.commands());
        this.save(petPlayer);

        this.message(player, messageKey, new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{amount}", Common.digits(amount))
                .add("{stats}", Common.digits(petPlayer.getStats().size()))
                .add("{max}", Common.digits(pet.getMaxStats())));
        return true;
    }

    /**
     * What this use of the item does, or null when there is nothing it could do
     * to this pet.
     * <p>
     * Only the outcomes that would actually change something are rolled, so an
     * item that mostly takes a stat away never wastes itself on a pet with no
     * stats, and one that adds never wastes itself on a full pet.
     */
    @Nullable
    private RerollOutcome pickOutcome(PetPlayer petPlayer, Pet pet, @Nullable PetItem item) {
        List<RerollOutcome> usable = new ArrayList<>();
        for (RerollOutcome outcome : this.outcomesOf(item)) {
            if (outcome.kind() == RerollOutcome.Kind.REMOVE && petPlayer.getStats().isEmpty()) continue;
            if (outcome.kind() == RerollOutcome.Kind.ADD && petPlayer.isStatsFull(pet)) continue;
            usable.add(outcome);
        }
        if (usable.isEmpty()) return null;
        if (usable.size() == 1) return usable.get(0);

        double total = 0;
        for (RerollOutcome outcome : usable) {
            total += outcome.weight();
        }

        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (RerollOutcome outcome : usable) {
            roll -= outcome.weight();
            if (roll <= 0) return outcome;
        }
        return usable.get(usable.size() - 1);
    }

    /**
     * The outcomes of an item, an item without any being read as the single
     * outcome its 'stats' amount describes.
     */
    private List<RerollOutcome> outcomesOf(@Nullable PetItem item) {
        if (item != null && item.outcomes() != null && !item.outcomes().isEmpty()) {
            return item.outcomes();
        }

        int stats = item == null ? 0 : item.stats();
        if (stats > 0) {
            return List.of(new RerollOutcome(RerollOutcome.Kind.ADD, 1, stats));
        }
        if (stats < 0) {
            return List.of(new RerollOutcome(RerollOutcome.Kind.REMOVE, 1, -stats));
        }
        return List.of(new RerollOutcome(RerollOutcome.Kind.REROLL, 1, 0));
    }

    /**
     * Whether an item may be used on this pet, telling the player when it may
     * not. An item with no 'whitelist-pets' of its own works on every pet.
     */
    private boolean allowsPet(Player player, Pet pet, PetItem item) {
        if (item == null || item.allows(pet)) return true;

        this.message(player, "messages.pet-item-not-allowed", new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .add("{item}", item.id()));
        return false;
    }

    public boolean revive(Player player, PetPlayer petPlayer, PetItem reviveItem) {
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null) return false;
        if (!this.allowsPet(player, pet, reviveItem)) return false;

        if (!petPlayer.isDead()) {
            this.message(player, "messages.pet-not-dead");
            return false;
        }

        double cost = reviveItem == null ? 0 : reviveItem.moneyCost();
        if (!Money.has(player, cost) || !Money.take(player, cost)) {
            this.message(player, "messages.pet-not-enough-money", new Placeholder()
                    .add("{cost}", Common.digits(cost)));
            return false;
        }

        double restored = reviveItem != null && reviveItem.food() > 0
                ? reviveItem.food() : PetSettings.getMaxFood();
        petPlayer.revive(restored);

        this.runCommands(player, reviveItem == null ? null : reviveItem.commands());
        this.save(petPlayer);
        this.refreshDisplay(player, petPlayer);

        this.message(player, "messages.pet-revived", new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{food}", Common.digits(petPlayer.getFood())));
        return true;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    public void onLevelUp(Player player, Pet pet, PetPlayer petPlayer, int gained) {
        this.message(player, "messages.pet-level-up", new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{level}", Common.digits(petPlayer.getLevel()))
                .add("{gained}", Common.digits(gained)));
        this.refreshDisplay(player, petPlayer);
    }

    private void refreshDisplay(Player player, PetPlayer petPlayer) {
        if (player == null || !petPlayer.isEquipped()) return;
        ModernSlimefunPet.getInstance().getPetTask().refreshFor(player);
    }

    /** Written at once: these actions take an item or money the player cannot get back. */
    private void save(PetPlayer petPlayer) {
        DatabaseQueueHandler.addObjectToSave(petPlayer);
        DatabaseQueueHandler.saveNow();
    }

    private void runCommands(Player player, List<String> commands) {
        if (commands == null || commands.isEmpty()) return;
        Placeholder placeholder = new Placeholder().add("{player}", player.getName());
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), placeholder.translate(command));
        }
    }

    private void message(Player player, String path) {
        this.message(player, path, new Placeholder());
    }

    private void message(Player player, String path, Placeholder placeholder) {
        if (player == null) return;
        if (!ModernSlimefunPet.PETS_CONFIG.getConfig().isSet(path)) return;
        ModernSlimefunPet.PETS_CONFIG.sendMessage(player, path, placeholder);
    }

    /**
     * What happened when a player tried to evolve a pet.
     */
    public enum EvolutionResult {
        /**
         * The evolution worked, take the item.
         */
        SUCCESS,
        /**
         * The evolution failed and the item is consumed anyway.
         */
        FAILED_CONSUME,
        /**
         * The evolution failed but the player keeps the item.
         */
        FAILED_KEEP,
        /**
         * The evolution could not even be attempted, keep the item.
         */
        INVALID;

        public boolean shouldConsumeItem() {
            return this == SUCCESS || this == FAILED_CONSUME;
        }
    }

}
