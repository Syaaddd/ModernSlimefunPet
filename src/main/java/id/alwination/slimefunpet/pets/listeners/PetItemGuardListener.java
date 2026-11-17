package id.alwination.slimefunpet.pets.listeners;

import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Keeps pet items out of Slimefun (owner rule, SLIMEFUN-VECTORS.md) and out of vanilla uses.
 * <ul>
 *   <li>Menus: every Slimefun menu, backpack and dustbin is a CHEST inventory without a holder
 *       (Bukkit.createInventory(null, ...)), while our FastInv menus and vanilla chests have one.
 *       While such a menu is open, no click or drag may move a pet item into it or put one on the
 *       cursor; taking a pet item out of it is allowed. HIGHEST, because Slimefun's menu listener
 *       at NORMAL overwrites any earlier cancel. Other plugins' holder-less chest menus are caught
 *       too (accepted by the owner).</li>
 *   <li>Dropped items carry Slimefun's "no_pickup" marker, which every Slimefun and Networks
 *       vacuum, magnet and android skips. Players can still pick them up: the marker is lifted for
 *       a player's pickup at LOWEST (before Slimefun's NORMAL check) and put back at MONITOR if the
 *       pickup did not happen. Hoppers cannot collect dropped pet items (Slimefun cancels those).</li>
 *   <li>Vanilla: no crafting, eating, placing, throwing, mob renaming or feeding, brewing, and the
 *       revive totem never saves its holder.</li>
 * </ul>
 * Cargo and the Networks grabbers read vanilla chests without any event, so a pet item put in a
 * chest can still be moved; the item materials are chosen so no machine there can use them.
 */
public final class PetItemGuardListener implements Listener {

    /** Slimefun's marker key (SlimefunUtils.NO_PICKUP_METADATA); any plugin's value counts. */
    public static final String NO_PICKUP = "no_pickup";

    private final Plugin plugin;

    public PetItemGuardListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // ----- Menus -----

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!isForeignMenu(view.getTopInventory())) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        boolean clickedTop = clicked != null && clicked.equals(view.getTopInventory());
        if (shouldBlockClick(clickedTop, PetItemRegistry.isPetItem(event.getCurrentItem()),
                PetItemRegistry.isPetItem(event.getCursor()), PetItemRegistry.isPetItem(swapItem(event)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isForeignMenu(event.getView().getTopInventory()) && PetItemRegistry.isPetItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /** A Slimefun-style menu: a chest-type inventory nobody holds. */
    static boolean isForeignMenu(Inventory top) {
        return top.getType() == InventoryType.CHEST && top.getHolder(false) == null;
    }

    /** The item a number-key or off-hand swap would move, null for other clicks. */
    private static @Nullable ItemStack swapItem(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            return who.getInventory().getItem(event.getHotbarButton());
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return who.getInventory().getItemInOffHand();
        }
        return null;
    }

    /**
     * While a foreign menu is open: a pet item on the cursor or being swapped in is always refused;
     * a pet item clicked in the player's own inventory is refused (a shift-click would move it in, and
     * Slimefun's player-inventory handlers see the click); a pet item clicked in the menu may be taken.
     */
    static boolean shouldBlockClick(boolean clickedTop, boolean currentIsPetItem, boolean cursorIsPetItem,
                                    boolean swapIsPetItem) {
        if (cursorIsPetItem || swapIsPetItem) {
            return true;
        }
        return currentIsPetItem && !clickedTop;
    }

    // ----- Dropped items -----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        this.markIfPetItem(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        this.markIfPetItem(event.getItemDrop());
    }

    /** Bukkit metadata is memory only: put it back on items in chunks that load again. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                this.markIfPetItem(item);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPickupEarly(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player && PetItemRegistry.isPetItem(event.getItem().getItemStack())) {
            event.getItem().removeMetadata(NO_PICKUP, this.plugin);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPickupLate(EntityPickupItemEvent event) {
        if (event.isCancelled() || event.getItem().isValid()) {
            this.markIfPetItem(event.getItem());
        }
    }

    private void markIfPetItem(Item item) {
        if (PetItemRegistry.isPetItem(item.getItemStack())) {
            item.setMetadata(NO_PICKUP, new FixedMetadataValue(this.plugin, true));
        }
    }

    // ----- Vanilla uses -----

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        for (ItemStack stack : Objects.requireNonNull(event.getInventory().getMatrix())) {
            if (PetItemRegistry.isPetItem(stack)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (PetItemRegistry.isPetItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (PetItemRegistry.isPetItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** The off hand is not a pet-item use: no throwing (wind charge), reading (knowledge book) or eating. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND && PetItemRegistry.isPetItem(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** No renaming a mob with the stat-lock charm, no feeding an animal pet food. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (PetItemRegistry.isPetItem(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    /** The revive totem revives pets, not its holder. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityResurrect(EntityResurrectEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != null && event.getEntity() instanceof Player player
                && PetItemRegistry.isPetItem(player.getInventory().getItem(hand))) {
            event.setCancelled(true);
        }
    }

    /** A hopper or Cargo can put the golden carrot into a brewing stand: it never brews. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBrew(BrewEvent event) {
        if (PetItemRegistry.isPetItem(event.getContents().getIngredient())) {
            event.setCancelled(true);
        }
    }
}
