package id.alwination.slimefunpet.pets.gui.helpers;

import static org.assertj.core.api.Assertions.assertThat;

import id.alwination.slimefunpet.pets.PetPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PetPaginationTest {

    private static List<PetPlayer> pets(int amount) {
        List<PetPlayer> pets = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            pets.add(new PetPlayer(UUID.randomUUID(), UUID.randomUUID(), "pet" + i, 1, 0, 10, 0L, false, List.of()));
        }
        return pets;
    }

    private static List<Integer> slots(int amount) {
        return IntStream.range(0, amount).boxed().toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 44, 45, 46, 90, 91, 100})
    void everyPetIsOnExactlyOnePage(int amount) {
        List<PetPlayer> pets = pets(amount);
        PetPagination pagination = new PetPagination(pets, slots(45));

        List<PetPlayer> shown = new ArrayList<>();
        for (int page = 1; page <= pagination.totalPage; page++) {
            shown.addAll(pagination.getItems(page));
        }

        assertThat(shown).containsExactlyElementsOf(pets);
        assertThat(pagination.totalPage).isEqualTo((amount + 44) / 45);
    }

    @Test
    void noPetsIsOneEmptyPage() {
        PetPagination pagination = new PetPagination(List.of(), slots(45));

        assertThat(pagination.totalPage).isEqualTo(1);
        assertThat(pagination.getItems(1)).isEmpty();
        assertThat(pagination.nextPage()).isFalse();
    }
}
