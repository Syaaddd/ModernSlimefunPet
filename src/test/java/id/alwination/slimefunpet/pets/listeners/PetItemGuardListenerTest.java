package id.alwination.slimefunpet.pets.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The click rule while a holder-less chest (Slimefun) menu is open. */
class PetItemGuardListenerTest {

    @Test
    void aPetItemOnTheCursorCanNeverBePutIntoTheMenuOrOwnInventory() {
        assertThat(PetItemGuardListener.shouldBlockClick(true, false, true, false)).isTrue();
        assertThat(PetItemGuardListener.shouldBlockClick(false, false, true, false)).isTrue();
    }

    @Test
    void swappingAPetItemInWithANumberKeyOrOffHandIsRefused() {
        assertThat(PetItemGuardListener.shouldBlockClick(true, false, false, true)).isTrue();
    }

    @Test
    void clickingAPetItemInOwnInventoryIsRefusedSoShiftClickCannotMoveItIn() {
        assertThat(PetItemGuardListener.shouldBlockClick(false, true, false, false)).isTrue();
    }

    @Test
    void aPetItemThatEndedUpInTheMenuCanBeTakenOut() {
        assertThat(PetItemGuardListener.shouldBlockClick(true, true, false, false)).isFalse();
    }

    @Test
    void clicksWithoutPetItemsAreLeftAlone() {
        assertThat(PetItemGuardListener.shouldBlockClick(true, false, false, false)).isFalse();
        assertThat(PetItemGuardListener.shouldBlockClick(false, false, false, false)).isFalse();
    }
}
