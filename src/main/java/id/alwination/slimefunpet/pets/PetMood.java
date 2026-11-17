package id.alwination.slimefunpet.pets;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How a pet feels, from its food bar: Happy at 'mood.happy-at' percent or more, Neutral from
 * 'mood.neutral-at', Sad below that. AFK XP is only paid while the mood is at least
 * 'afk-xp.required-mood'.
 */
public enum PetMood {
    SAD,
    NEUTRAL,
    HAPPY;

    public static PetMood of(double foodPercent, double happyAt, double neutralAt) {
        if (foodPercent >= happyAt) {
            return HAPPY;
        }
        return foodPercent >= neutralAt ? NEUTRAL : SAD;
    }

    public boolean isAtLeast(PetMood other) {
        return this.ordinal() >= other.ordinal();
    }

    public static @Nullable PetMood fromString(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (PetMood mood : values()) {
            if (mood.name().equals(name.trim().toUpperCase(Locale.ROOT))) {
                return mood;
            }
        }
        return null;
    }
}
