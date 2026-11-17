package id.alwination.slimefunpet.commands;

import com.muhammaddaffa.mdlib.commands.args.builtin.OnlinePlayerArg;
import com.muhammaddaffa.mdlib.commands.commands.RoutedCommand;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.gui.PetInventory;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import org.bukkit.entity.Player;

/** /pet and /pets: the pet list; /pets &lt;player&gt; shows someone else's (admin). */
public final class PetCommand extends RoutedCommand {

    public PetCommand(PetManager petManager, PetTask petTask) {
        super("pet", "Open your pets");
        root()
                .alias("pets")
                .argOptional("player", new OnlinePlayerArg())
                .exec((sender, context) -> {
                    if (!(sender instanceof Player player)) {
                        return;
                    }
                    Player other = context.get("player", Player.class);
                    if (other == null) {
                        new PetInventory(player, player, petManager, petTask).open(player);
                        return;
                    }
                    if (!sender.hasPermission(SlimefunPetCommand.ADMIN)) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.no-permission");
                        return;
                    }
                    new PetInventory(player, other, petManager, petTask).open(player);
                });
    }
}
