package id.alwination.slimefunpet.pets.evolution;

import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import org.bukkit.entity.Player;

/**
 * Something a player has to bring before a pet is allowed to evolve, defined
 * under 'pets.&lt;id&gt;.evolution' in pets.yml.
 * <p>
 * Modelled on the generator upgrade requirements, so the config looks the same
 * on both sides.
 */
public interface EvolutionRequirement {

    boolean isMet(Player player);

    /**
     * Take what the requirement asks for. Only ever called once every
     * requirement is met, and the payment happens before the chance is rolled,
     * exactly like the money cost does.
     */
    void consume(Player player);

    /**
     * The 'messages.&lt;key&gt;' told to the player when they are missing this.
     */
    String failureMessageKey();

    Placeholder failurePlaceholders(Player player);

    /**
     * What this requirement asks for, written out for the menu, without the
     * met/unmet marker around it.
     */
    String describe(Player player);

    /**
     * One menu line for this requirement, marked as met or not.
     */
    default String getStatusLine(Player player) {
        String path = this.isMet(player)
                ? "placeholders.requirement-met"
                : "placeholders.requirement-unmet";
        String format = ModernSlimefunPet.PETS_CONFIG.getString(path, " &8- &7{requirement}");
        return format.replace("{requirement}", this.describe(player));
    }

}
