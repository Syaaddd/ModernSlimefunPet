package id.alwination.slimefunpet.afk;

import static org.assertj.core.api.Assertions.assertThat;

import id.alwination.slimefunpet.pets.PetMood;
import org.junit.jupiter.api.Test;

class AfkXpTaskTest {

    @Test
    void movingAFifthOfABlockOrTurningIsActivityButStandingStillIsNot() {
        assertThat(AfkXpTask.moved(0.2, 0, 0, 0, 0)).isTrue();
        assertThat(AfkXpTask.moved(0.05, 0.05, 0, 0, 0)).isFalse();
        assertThat(AfkXpTask.moved(0, 0, 0, 5, 0)).isTrue();
        assertThat(AfkXpTask.moved(0, 0, 0, 0, -3)).isTrue();
        assertThat(AfkXpTask.moved(0, 0, 0, 0.01f, 0)).isFalse();
    }

    @Test
    void theDailyTotalStartsOverOnANewDay() {
        assertThat(AfkXpTask.todayXp("2026-11-17", 90, "2026-11-17")).isEqualTo(90);
        assertThat(AfkXpTask.todayXp("2026-11-16", 240, "2026-11-17")).isZero();
        assertThat(AfkXpTask.todayXp("", 0, "2026-11-17")).isZero();
    }

    @Test
    void aPayoutNeverPassesTheDailyCap() {
        assertThat(AfkXpTask.payoutAmount(0, 30, 240)).isEqualTo(30);
        assertThat(AfkXpTask.payoutAmount(225, 30, 240)).isEqualTo(15);
        assertThat(AfkXpTask.payoutAmount(240, 30, 240)).isZero();
        assertThat(AfkXpTask.payoutAmount(250, 30, 240)).isZero();
    }

    @Test
    void eightHoursOfThirtyHitTheDefaultCapExactly() {
        int today = 0;
        for (int hour = 0; hour < 10; hour++) {
            today += AfkXpTask.payoutAmount(today, 30, 240);
        }
        assertThat(today).isEqualTo(240);
    }

    @Test
    void timeLeftIsShownInWholeMinutesRoundedUp() {
        assertThat(AfkXpTask.formatLeft(42 * 60_000L)).isEqualTo("42m");
        assertThat(AfkXpTask.formatLeft(41 * 60_000L + 1)).isEqualTo("42m");
        assertThat(AfkXpTask.formatLeft(0)).isEqualTo("1m");
    }

    @Test
    void moodFollowsTheFoodBar() {
        assertThat(PetMood.of(60, 60, 25)).isEqualTo(PetMood.HAPPY);
        assertThat(PetMood.of(59.9, 60, 25)).isEqualTo(PetMood.NEUTRAL);
        assertThat(PetMood.of(25, 60, 25)).isEqualTo(PetMood.NEUTRAL);
        assertThat(PetMood.of(24.9, 60, 25)).isEqualTo(PetMood.SAD);
        assertThat(PetMood.HAPPY.isAtLeast(PetMood.HAPPY)).isTrue();
        assertThat(PetMood.NEUTRAL.isAtLeast(PetMood.HAPPY)).isFalse();
        assertThat(PetMood.SAD.isAtLeast(PetMood.SAD)).isTrue();
    }
}
