package id.alwination.slimefunpet.pets.tasks;

import id.alwination.slimefunpet.pets.PetPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam S1 (SEAMS.md): hunger burning with and without Auto-Feed, no server.
 * The bar is the default 100, PetSettings is never loaded here.
 */
class PetFoodBurnTest {

    private static final double NO_AUTO_FEED = -1;

    @Test
    void withoutAutoFeedTheBarDropsAndTheBurnedFoodIsDigested() {
        PetPlayer pet = pet(100);
        double[] digested = {0};

        PetFoodTask.burn(pet, 30, NO_AUTO_FEED, () -> -1, burned -> {
            digested[0] += burned;
            return 0;
        });

        assertThat(pet.getFood()).isEqualTo(70);
        assertThat(digested[0]).isEqualTo(30);
    }

    @Test
    void withoutAutoFeedAPetStarvesAndOnlyWhatItHadIsDigested() {
        PetPlayer pet = pet(20);
        double[] digested = {0};

        PetFoodTask.burn(pet, 50, NO_AUTO_FEED, () -> -1, burned -> {
            // exp is handed out while the pet is still alive to take it
            assertThat(pet.isDead()).isFalse();
            digested[0] += burned;
            return 0;
        });

        assertThat(pet.isDead()).isTrue();
        assertThat(digested[0]).isEqualTo(20);
    }

    @Test
    void aLongStretchEatsAsItGoesInsteadOfStarvingFirst() {
        // 72 hours away at 5 an hour: 360 food, far more than the bar holds
        PetPlayer pet = pet(100);
        Deque<Double> stored = new ArrayDeque<>(List.of(40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0));

        PetFoodTask.burn(pet, 360, 50, () -> stored.isEmpty() ? -1 : stored.pop(), burned -> 0);

        // 50 down to the line, then 7 x 40 eaten and burned, then 30 more off the line
        assertThat(stored).isEmpty();
        assertThat(pet.isDead()).isFalse();
        assertThat(pet.getFood()).isEqualTo(20);
    }

    @Test
    void itOnlyEatsOnceTheBarReachesTheThreshold() {
        PetPlayer pet = pet(80);
        Deque<Double> stored = new ArrayDeque<>(List.of(25.0));

        PetFoodTask.burn(pet, 20, 50, () -> stored.isEmpty() ? -1 : stored.pop(), burned -> 0);
        assertThat(pet.getFood()).isEqualTo(60);
        assertThat(stored).hasSize(1);

        PetFoodTask.burn(pet, 15, 50, () -> stored.isEmpty() ? -1 : stored.pop(), burned -> 0);
        // 10 to the line, a 25 treat, then the last 5
        assertThat(pet.getFood()).isEqualTo(70);
        assertThat(stored).isEmpty();
    }

    @Test
    void foodWorthNothingStillRunsOutInsteadOfLoopingForever() {
        PetPlayer pet = pet(50);
        Deque<Double> stored = new ArrayDeque<>(List.of(0.0, 0.0, 0.0));

        PetFoodTask.burn(pet, 60, 50, () -> stored.isEmpty() ? -1 : stored.pop(), burned -> 0);

        assertThat(stored).isEmpty();
        assertThat(pet.isDead()).isTrue();
    }

    private static PetPlayer pet(double food) {
        PetPlayer pet = new PetPlayer(UUID.randomUUID(), "poorman_pet1");
        pet.setFood(food);
        return pet;
    }

    @Test
    void slowBurnKeepsItsFractionInsteadOfRoundingToNothing() {
        PetPlayer pet = new PetPlayer(UUID.randomUUID(), UUID.randomUUID(), "pet", 1, 0, 10, 0L, false, List.of());

        int total = 0;
        for (int tick = 0; tick < 10; tick++) {
            total += PetFoodTask.wholeExp(pet, 0.3);
        }

        // 10 ticks of 0.3 EXP is 3 whole points; rounding per tick gave 0
        assertThat(total).isEqualTo(3);
        assertThat(pet.getExpFraction()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
