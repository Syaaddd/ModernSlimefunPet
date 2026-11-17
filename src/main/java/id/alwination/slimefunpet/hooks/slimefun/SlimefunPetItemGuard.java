package id.alwination.slimefunpet.hooks.slimefun;

import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import io.github.thebusybiscuit.slimefun5.api.events.AsyncAutoEnchanterProcessEvent;
import io.github.thebusybiscuit.slimefun5.api.events.AutoDisenchantEvent;
import io.github.thebusybiscuit.slimefun5.api.events.AutoEnchantEvent;
import io.github.thebusybiscuit.slimefun5.api.events.BlockPlacerPlaceEvent;
import io.github.thebusybiscuit.slimefun5.api.events.MultiBlockCraftEvent;
import io.github.thebusybiscuit.slimefun5.api.events.MultiBlockInteractEvent;
import io.github.thebusybiscuit.slimefun5.api.events.PlayerRightClickEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * The Slimefun events that can be cancelled when a pet item is involved (SLIMEFUN-VECTORS.md,
 * "Recommended item form and guard set"). Registered only while Slimefun is enabled, so the plugin
 * runs without it. Auto-enchanter events can fire off the main thread; the check reads PDC only.
 */
public final class SlimefunPetItemGuard implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMultiBlockCraft(MultiBlockCraftEvent event) {
        for (ItemStack stack : event.getInput()) {
            if (PetItemRegistry.isPetItem(stack)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Table Saw, panning machine and the like read the held item by type alone. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMultiBlockInteract(MultiBlockInteractEvent event) {
        if (PetItemRegistry.isPetItem(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAutoEnchant(AutoEnchantEvent event) {
        if (PetItemRegistry.isPetItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAutoDisenchant(AutoDisenchantEvent event) {
        if (PetItemRegistry.isPetItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncAutoEnchanterProcess(AsyncAutoEnchanterProcessEvent event) {
        if (PetItemRegistry.isPetItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * A pet item never works a Slimefun block (Ancient Pedestal, Crucible, Composter...). Only the
     * block use is denied: Slimefun copies the result onto the Bukkit event, so denying everything
     * would also stop our own right-click use of the item.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRightClick(PlayerRightClickEvent event) {
        if (PetItemRegistry.isPetItem(event.getItem()) && event.getSlimefunBlock().isPresent()) {
            event.setUseBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlacerPlace(BlockPlacerPlaceEvent event) {
        if (PetItemRegistry.isPetItem(event.getItemStack())) {
            event.setCancelled(true);
        }
    }
}
