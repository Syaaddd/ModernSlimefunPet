package id.alwination.slimefunpet.pets.evolution;

import com.muhammaddaffa.mdlib.hooks.VaultEconomy;
import id.alwination.slimefunpet.utils.Money;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import org.bukkit.entity.Player;

/**
 * Money taken for the evolution itself, on top of whatever the evolution item
 * costs to use.
 */
public record MoneyEvolutionRequirement(double amount) implements EvolutionRequirement {

    @Override
    public boolean isMet(Player player) {
        return Money.has(player, this.amount);
    }

    @Override
    public void consume(Player player) {
        Money.take(player, this.amount);
    }

    @Override
    public String failureMessageKey() {
        return "messages.pet-not-enough-money";
    }

    @Override
    public Placeholder failurePlaceholders(Player player) {
        return new Placeholder()
                .add("{cost}", Common.digits(this.amount))
                .add("{balance}", Common.digits(VaultEconomy.getBalance(player)));
    }

    @Override
    public String describe(Player player) {
        String format = ModernSlimefunPet.PETS_CONFIG.getString("placeholders.requirement-money",
                "&f${cost} &8(have ${balance})");
        return this.failurePlaceholders(player).translate(format);
    }

}
