package id.alwination.slimefunpet.pets.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import id.alwination.slimefunpet.pets.stats.PetStatCondition;
import org.junit.jupiter.api.Test;

class PetEffectTaskTest {

    private static final long NOW = 1_000_000L;

    @Test
    void miningHoldsOnlyWithinTheMiningWindow() {
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.MINING, 100, 100, NOW, 0, NOW - 4_999, 10, 5)).isTrue();
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.MINING, 100, 100, NOW, 0, NOW - 5_000, 10, 5)).isFalse();
    }

    @Test
    void outOfCombatNeedsTheFullCombatTimeWithoutHits() {
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.OUT_OF_COMBAT, 100, 100, NOW, NOW - 9_999, 0, 10, 5)).isFalse();
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.OUT_OF_COMBAT, 100, 100, NOW, NOW - 10_000, 0, 10, 5)).isTrue();
    }

    @Test
    void healthBelowIsStrictlyBelowTheLine() {
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.HEALTH_BELOW, 29.9, 30, NOW, 0, 0, 10, 5)).isTrue();
        assertThat(PetEffectTask.conditionHolds(PetStatCondition.HEALTH_BELOW, 30, 30, NOW, 0, 0, 10, 5)).isFalse();
    }

    @Test
    void percentAttributesAreDividedByOneHundred() {
        assertThat(PetEffectTask.attributeAmount(2, true)).isEqualTo(0.02);
        assertThat(PetEffectTask.attributeAmount(5, true)).isEqualTo(0.05);
        assertThat(PetEffectTask.attributeAmount(0.5, false)).isEqualTo(0.5);
    }

    @Test
    void aStrongerOrEndlessEffectFromElsewhereIsKept() {
        assertThat(PetEffectTask.shouldReplace(1, 100, false, 0, 100)).isFalse();
        assertThat(PetEffectTask.shouldReplace(0, 100, true, 0, 100)).isFalse();
    }

    @Test
    void sameLevelIsOnlyReplacedWhenItRunsOutSooner() {
        assertThat(PetEffectTask.shouldReplace(0, 40, false, 0, 100)).isTrue();
        assertThat(PetEffectTask.shouldReplace(0, 6000, false, 0, 100)).isFalse();
        assertThat(PetEffectTask.shouldReplace(0, 6000, false, 1, 100)).isTrue();
    }

    @Test
    void healWaitsForTheIntervalAndOnlyBelowTheLine() {
        // 13 of 20 health is 65%: below a 70% line
        assertThat(PetEffectTask.healDue(13, 20, 70, NOW, NOW - 8_000, 8)).isTrue();
        assertThat(PetEffectTask.healDue(13, 20, 70, NOW, NOW - 7_999, 8)).isFalse();
        // 14 of 20 is exactly 70%: not below
        assertThat(PetEffectTask.healDue(14, 20, 70, NOW, 0, 8)).isFalse();
        assertThat(PetEffectTask.healDue(0, 20, 70, NOW, 0, 8)).isFalse();
    }
}
