package id.alwination.slimefunpet.utils;

import com.muhammaddaffa.mdlib.hooks.VaultEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Money costs through Vault. With no economy hooked, MDLib reports the balance as NaN and refuses
 * withdrawals; both are answered "cannot pay" here, so a missing economy never makes a priced action
 * free (ModernMineGens compared NaN &lt; cost, which is false, and never checked the withdrawal).
 */
public final class Money {

    private Money() {
    }

    public static boolean has(Player player, double cost) {
        if (cost <= 0) {
            return true;
        }
        // Without Vault, MDLib's hook would load Vault classes that are not there
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return false;
        }
        double balance = VaultEconomy.getBalance(player);
        return !Double.isNaN(balance) && balance >= cost;
    }

    /** Takes the money; false when the economy refused, and then nothing was taken. */
    public static boolean take(Player player, double cost) {
        if (cost <= 0) {
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return false;
        }
        return VaultEconomy.withdraw(player, cost).transactionSuccess();
    }
}
