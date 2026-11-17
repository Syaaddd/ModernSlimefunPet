package id.alwination.slimefunpet.pets.items;

import id.alwination.slimefunpet.pets.Pet;

import java.util.List;
import java.util.Locale;

/**
 * A custom pet item defined under 'items.<type>.<id>' in pets.yml.
 * <p>
 * Not every field is used by every type, a food item uses the food and the exp
 * while a revive item uses the food and the money cost.
 *
 * @param type      what the item does
 * @param id        the config key, used by the give command
 * @param food      food restored (FOOD and REVIVE)
 * @param exp       exp given to the pet (FOOD)
 * @param levels    levels given to the pet (CANDY)
 * @param stats     what the item does to the stats of the pet (REROLL): a
 *                  positive amount rolls that many new stats on top of the ones
 *                  it has, a negative one takes that many away, and 0 rolls a
 *                  completely new set of 'stats.reroll-amount' stats
 * @param moneyCost money taken on use, 0 to disable (REVIVE and EVOLUTION)
 * @param chanceBonus percent added to the success rate of the evolution, so a
 *                    better stone is worth more than a plain one (EVOLUTION)
 * @param commands  console commands ran on a successful use
 * @param whitelistPets the pets this item may be used on, empty to allow any
 * @param outcomes  what a reroll item can land on (REROLL), one being picked
 *                  at random on every use. Empty when the item always does the
 *                  same thing, which is what its 'stats' says
 */
public record PetItem(
        PetItemType type,
        String id,
        double food,
        int exp,
        int levels,
        int stats,
        double moneyCost,
        double chanceBonus,
        List<String> commands,
        List<String> whitelistPets,
        List<RerollOutcome> outcomes
) {

    /**
     * Whether this item may be used on a pet.
     * <p>
     * An item without a whitelist of its own works on everything, which is what
     * every item did before the list existed.
     */
    public boolean allows(Pet pet) {
        if (this.whitelistPets == null || this.whitelistPets.isEmpty()) return true;
        if (pet == null) return false;

        for (String id : this.whitelistPets) {
            if (id != null && id.trim().toLowerCase(Locale.ROOT).equals(pet.getId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
