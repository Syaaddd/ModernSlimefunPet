package id.alwination.slimefunpet.pets;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam S1 (SEAMS.md): how a pet stores the skin it wears, no server.
 */
class PetPlayerSkinTest {

    @Test
    void skinIsStoredAsTheModelFileNameAndBlankClearsIt() {
        PetPlayer pet = new PetPlayer(UUID.randomUUID(), "poorman_pet1");
        assertThat(pet.getSkin()).isNull();

        pet.setSkin("  RedPanda ");
        assertThat(pet.getSkin()).isEqualTo("redpanda");

        pet.setSkin("   ");
        assertThat(pet.getSkin()).isNull();

        pet.setSkin("cubee-air_dragon");
        pet.setSkin(null);
        assertThat(pet.getSkin()).isNull();
    }

}
