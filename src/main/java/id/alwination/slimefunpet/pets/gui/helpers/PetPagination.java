package id.alwination.slimefunpet.pets.gui.helpers;

import id.alwination.slimefunpet.pets.PetPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the pet list over pages of as many pets as the menu has pet slots. Pages start at 1; no
 * pets is one empty page. (ModernMineGens' version dropped the last page when it held a single
 * pet, for example 46 pets on 45 slots.)
 */
public class PetPagination {

    private final Map<Integer, List<PetPlayer>> pageItems = new HashMap<>();
    public int totalPage;
    public int currentPage = 1;

    public PetPagination(List<PetPlayer> pets, List<Integer> slots) {
        int perPage = Math.max(1, slots.size());
        for (int start = 0; start < pets.size(); start += perPage) {
            List<PetPlayer> page = new ArrayList<>(pets.subList(start, Math.min(pets.size(), start + perPage)));
            this.pageItems.put(this.pageItems.size() + 1, page);
        }
        if (this.pageItems.isEmpty()) {
            this.pageItems.put(1, new ArrayList<>());
        }
        this.totalPage = this.pageItems.size();
    }

    public boolean nextPage() {
        return this.pageItems.get(this.currentPage + 1) != null;
    }

    public boolean previousPage() {
        return this.pageItems.get(this.currentPage - 1) != null;
    }

    public List<PetPlayer> getItems(int page) {
        return this.pageItems.getOrDefault(page, List.of());
    }
}
