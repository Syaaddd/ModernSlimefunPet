package id.alwination.slimefunpet.pets.listeners;

import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.gui.PetInventory;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Right clicking a pet item opens the menu of that item so it can be used on a
 * pet, and the item is only taken once it actually worked.
 * <p>
 * Stat-lock charms and Auto-Feed items are used from other menus, so they only
 * answer with a hint here.
 */
public record PetItemListener(
        PetManager petManager,
        PetTask petTask
) implements Listener {

    @EventHandler
    public void onUsePetItem(PlayerInteractEvent event) {
        // Only handle the main hand, the event fires for both hands
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack stack = event.getItem();
        PetItem petItem = PetItemRegistry.fromItemStack(stack);
        if (petItem == null) return;

        // It is one of our items, never let it place a block or eat itself
        event.setCancelled(true);

        Player player = event.getPlayer();

        // Stat locks are picked in the stat lock menu, reached from the reroll
        // confirmation.
        if (petItem.type() == PetItemType.STAT_LOCK) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(player, "messages.pet-stat-lock-usage");
            return;
        }

        // Used from a pet's options menu
        if (petItem.type() == PetItemType.AUTO_FEED_UNLOCKER || petItem.type() == PetItemType.AUTO_FEED_UPGRADE) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(player, "messages.pet-auto-feed-usage");
            return;
        }

        // Any other item without a pet menu of its own is used from a menu
        if (!petItem.type().hasGui()) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(player, "messages.pet-item-menu-usage");
            return;
        }

        List<PetPlayer> pets = this.petManager.getPetPlayers(player);
        if (pets.isEmpty()) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(player, "messages.no-pets");
            return;
        }

        new PetInventory(player, player, this.petManager, this.petTask, petItem)
                .open(player);
    }

}
