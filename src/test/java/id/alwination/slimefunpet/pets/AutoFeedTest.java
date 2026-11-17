package id.alwination.slimefunpet.pets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam S1 (SEAMS.md): the food a pet keeps for Auto-Feed, no server.
 */
class AutoFeedTest {

    @Test
    void aLockedAutoFeedTakesNothing() {
        AutoFeed feed = new AutoFeed();
        assertThat(feed.deposit(0, "small_treat", 10, 64, 3)).isZero();
        assertThat(feed.isActive()).isFalse();
    }

    @Test
    void foodStacksUpToTheStackSizeInOpenSlotsOnly() {
        AutoFeed feed = unlocked();

        assertThat(feed.deposit(0, "small_treat", 50, 64, 3)).isEqualTo(50);
        assertThat(feed.deposit(0, "small_treat", 50, 64, 3)).isEqualTo(14);
        assertThat(feed.getAmount(0)).isEqualTo(64);

        // another food never mixes into a taken slot, and a closed slot takes nothing
        assertThat(feed.deposit(0, "large_feast", 5, 64, 3)).isZero();
        assertThat(feed.deposit(3, "large_feast", 5, 64, 3)).isZero();
        assertThat(feed.deposit(2, "large_feast", 5, 64, 3)).isEqualTo(5);
    }

    @Test
    void takingASlotEmptiesIt() {
        AutoFeed feed = unlocked();
        feed.deposit(1, "small_treat", 7, 64, 3);

        assertThat(feed.take(1)).isEqualTo(7);
        assertThat(feed.getFoodId(1)).isNull();
        assertThat(feed.take(1)).isZero();
        assertThat(feed.hasFood()).isFalse();
    }

    @Test
    void eatsOneUsableFoodAtATimeAndSkipsTheRest() {
        AutoFeed feed = unlocked();
        feed.deposit(0, "removed_food", 3, 64, 3);
        feed.deposit(1, "small_treat", 2, 64, 3);

        Map<String, Double> foods = Map.of("small_treat", 25.0);
        assertThat(feed.eatOne(id -> foods.getOrDefault(id, -1.0))).isEqualTo(25.0);
        assertThat(feed.eatOne(id -> foods.getOrDefault(id, -1.0))).isEqualTo(25.0);
        assertThat(feed.eatOne(id -> foods.getOrDefault(id, -1.0))).isEqualTo(-1.0);

        // the food it could not use is left for the player to take back
        assertThat(feed.getAmount(0)).isEqualTo(3);
        assertThat(feed.getFoodId(1)).isNull();
    }

    @Test
    void savesAndLoadsEverything() {
        AutoFeed feed = unlocked();
        feed.upgrade();
        feed.setEnabled(false);
        feed.deposit(2, "large_feast", 4, 64, 4);

        AutoFeed loaded = new AutoFeed();
        loaded.load(feed.toJson());

        assertThat(loaded.isUnlocked()).isTrue();
        assertThat(loaded.isEnabled()).isFalse();
        assertThat(loaded.getTier()).isEqualTo(1);
        assertThat(loaded.getFoodId(2)).isEqualTo("large_feast");
        assertThat(loaded.getAmount(2)).isEqualTo(4);
    }

    @Test
    void aPetThatNeverUnlockedItStoresNothingAndLoadsLocked() {
        assertThat(new AutoFeed().toJson()).isNull();

        AutoFeed loaded = new AutoFeed();
        loaded.load(null);
        assertThat(loaded.isUnlocked()).isFalse();
    }

    @Test
    void tiersAreReadAndKeptInRange() {
        try {
            PetSettings.loadAutoFeedTiers(List.of(
                    Map.of("threshold", 50, "slots", 3),
                    Map.of("threshold", 150, "slots", 9)));

            assertThat(PetSettings.getAutoFeedTiers()).isEqualTo(2);
            assertThat(PetSettings.getAutoFeedThreshold(0)).isEqualTo(50);
            assertThat(PetSettings.getAutoFeedSlots(0)).isEqualTo(3);
            // feeding at a full bar would eat everything at once, and the menu has 5 slots
            assertThat(PetSettings.getAutoFeedThreshold(1)).isEqualTo(95);
            assertThat(PetSettings.getAutoFeedSlots(1)).isEqualTo(AutoFeed.MAX_SLOTS);
            // a tier past the last, after the config lost one, reads as the last
            assertThat(PetSettings.getAutoFeedSlots(7)).isEqualTo(AutoFeed.MAX_SLOTS);

            PetSettings.loadAutoFeedTiers(List.of());
            assertThat(PetSettings.getAutoFeedTiers()).isEqualTo(1);
        } finally {
            PetSettings.loadAutoFeedTiers(List.of());
        }
    }

    private static AutoFeed unlocked() {
        AutoFeed feed = new AutoFeed();
        feed.unlock();
        return feed;
    }
}
