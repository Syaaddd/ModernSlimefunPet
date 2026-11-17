package id.alwination.slimefunpet.pets.evolution;

import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes out what an evolution is still waiting for, so both the pet menu and
 * the evolution menu show the very same lines.
 */
public final class EvolutionRequirements {

    private EvolutionRequirements() {
    }

    /**
     * One met/unmet line per thing the evolution asks for, the pet level first
     * because that is the one the player cannot buy their way out of.
     */
    public static List<String> statusLines(Player player, Pet pet, PetPlayer petPlayer) {
        Pet.PetEvolution evolution = pet == null ? null : pet.getEvolution();
        if (evolution == null) return List.of();

        List<String> lines = new ArrayList<>();
        lines.add(mark(petPlayer.getLevel() >= evolution.requiredLevel(), levelText(evolution, petPlayer)));

        for (EvolutionRequirement requirement : evolution.requirements()) {
            lines.add(requirement.getStatusLine(player));
        }
        return lines;
    }

    /**
     * Whether everything except the roll itself is in place, used to grey the
     * confirm button out before the player spends anything.
     */
    public static boolean allMet(Player player, Pet pet, PetPlayer petPlayer) {
        Pet.PetEvolution evolution = pet == null ? null : pet.getEvolution();
        if (evolution == null) return false;
        if (petPlayer.isDead() || petPlayer.getLevel() < evolution.requiredLevel()) return false;

        for (EvolutionRequirement requirement : evolution.requirements()) {
            if (!requirement.isMet(player)) return false;
        }
        return true;
    }

    private static String levelText(Pet.PetEvolution evolution, PetPlayer petPlayer) {
        String format = ModernSlimefunPet.PETS_CONFIG.getString("placeholders.requirement-level",
                "&fLevel {required_level} &8(now {level})");
        return new Placeholder()
                .add("{required_level}", Common.digits(evolution.requiredLevel()))
                .add("{level}", Common.digits(petPlayer.getLevel()))
                .translate(format);
    }

    private static String mark(boolean met, String requirement) {
        String format = ModernSlimefunPet.PETS_CONFIG.getString(met
                ? "placeholders.requirement-met" : "placeholders.requirement-unmet", " &8- &7{requirement}");
        return format.replace("{requirement}", requirement);
    }

}
