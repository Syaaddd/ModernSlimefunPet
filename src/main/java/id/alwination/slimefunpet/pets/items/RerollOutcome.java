package id.alwination.slimefunpet.pets.items;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * One of the things a reroll item can land on, written under 'outcomes' of the
 * item in pets.yml.
 * <p>
 * An item with several of them picks one at random every time it is used, the
 * weight of an outcome being how likely it is against the others, so the same
 * token can reroll a pet most of the time and burn a single stat off it now and
 * then.
 *
 * @param kind   what happens to the stats
 * @param weight how likely this is against the other outcomes of the item
 * @param amount how many stats it touches, 0 on a REROLL falling back to
 *               'stats.reroll-amount'
 */
public record RerollOutcome(Kind kind, double weight, int amount) {

    public enum Kind {
        /**
         * Throw the stats away and roll a whole new set.
         */
        REROLL,
        /**
         * Roll extra stats on top of the ones the pet has, up to its 'max-stats'.
         */
        ADD,
        /**
         * Take stats off the pet, picked at random.
         */
        REMOVE;

        @Nullable
        public static Kind fromString(@Nullable String name) {
            if (name == null) return null;
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
