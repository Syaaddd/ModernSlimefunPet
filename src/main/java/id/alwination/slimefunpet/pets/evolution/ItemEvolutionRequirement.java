package id.alwination.slimefunpet.pets.evolution;

import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * A custom item the player has to hand over, matched on its material and its
 * display name just like the 'upgrade.item-requirement' of a generator.
 */
public record ItemEvolutionRequirement(String material, String displayName, int amount)
        implements EvolutionRequirement {

    @Override
    public boolean isMet(Player player) {
        return this.count(player) >= this.amount;
    }

    @Override
    public void consume(Player player) {
        Utils.removeItemRequirement(player, this.material, this.displayName, this.amount);
    }

    @Override
    public String failureMessageKey() {
        return "messages.pet-evolution-need-items";
    }

    @Override
    public Placeholder failurePlaceholders(Player player) {
        return new Placeholder()
                .add("{item_name}", this.name())
                .add("{required_items}", Common.digits(this.amount))
                .add("{current_items}", Common.digits(this.count(player)));
    }

    @Override
    public String describe(Player player) {
        String format = ModernSlimefunPet.PETS_CONFIG.getString("placeholders.requirement-item",
                "&f{item_name} &7x{required_items} &8(have {current_items})");
        return this.failurePlaceholders(player).translate(format);
    }

    private int count(Player player) {
        return Utils.countItemRequirement(player, this.material, this.displayName);
    }

    /**
     * What to call this in a message or a menu line. A requirement asking for a
     * plain material has no name of its own, so the material is tidied up into
     * one: 'DIAMOND_BLOCK' reads as 'Diamond Block'.
     */
    private String name() {
        if (this.displayName != null && !this.displayName.isBlank()) {
            return this.displayName;
        }

        StringBuilder name = new StringBuilder();
        for (String word : this.material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

}
