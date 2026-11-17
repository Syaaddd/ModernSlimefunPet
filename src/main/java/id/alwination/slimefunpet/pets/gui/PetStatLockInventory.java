package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * Picks which stats of a pet a reroll has to spare, opened from
 * {@link PetRerollInventory}.
 * <p>
 * One stat is on screen at a time and the up and down buttons walk through
 * them, so nothing has to be aimed at and no lore has to be hovered to be read.
 * That is what makes it usable on Bedrock, where both are awkward.
 * <p>
 * Locking here spends nothing: the charms are only taken when the reroll is
 * confirmed, and walking away from the attempt drops every lock again.
 * <p>
 * What the locks cost is a total for the attempt, not a price per stat, so a
 * second lock can be worth more than the first.
 */
// Identity on purpose: "is this exact inventory/menu open", the click safety of the menu rests on it.
// navigating is read by the close handler that runs inside open(); PMD cannot see that.
@SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.UnusedAssignment"})
public final class PetStatLockInventory extends FastInv {

    private static final int UP_SLOT = 4;
    private static final int TOGGLE_SLOT = 11;
    private static final int STAT_SLOT = 13;
    private static final int INFO_SLOT = 15;
    private static final int BACK_SLOT = 18;
    private static final int DOWN_SLOT = 22;

    private final Player viewer;
    private final PetPlayer petPlayer;
    private final PetRerollInventory parent;

    // Main-thread owned. Which stat of the pet is on screen.
    private int index;

    // True only while this menu is handing the player back to the reroll
    // confirmation, where the locks have to stay put.
    private boolean navigating;

    PetStatLockInventory(Player viewer, PetRerollInventory parent) {
        super(27, Common.color(ModernSlimefunPet.PET_STAT_LOCK_GUI_CONFIG.getString("title", "&8Stat Locks")));
        this.viewer = viewer;
        this.parent = parent;
        this.petPlayer = parent.petPlayer();
        this.addCloseHandler(event -> {
            if (this.navigating) return;
            this.petPlayer.clearLockedStats();
        });
        this.draw();
    }

    private void draw() {
        FileConfiguration config = ModernSlimefunPet.PET_STAT_LOCK_GUI_CONFIG.getConfig();
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        List<PetStat> stats = this.petPlayer.getStats();

        if (this.index >= stats.size()) this.index = 0;

        this.clearItems();
        for (int slot = 0; slot < this.getInventory().getSize(); slot++) {
            this.setItem(slot, ItemBuilder.fromConfig(config, "filler").build());
        }

        PetItem charm = this.parent.charm();
        int locked = this.petPlayer.getLockedStatCount();
        Placeholder placeholder = new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .add("{index}", String.valueOf(stats.isEmpty() ? 0 : this.index + 1))
                .add("{total}", String.valueOf(stats.size()))
                .add("{locked}", String.valueOf(locked))
                .add("{cost}", String.valueOf(PetSettings.getStatLockCost(locked)))
                .add("{next_cost}", String.valueOf(PetSettings.getStatLockCost(locked + 1)))
                .add("{charms}", String.valueOf(this.parent.charmCount(charm)))
                .add("{stat}", stats.isEmpty() ? "" : statLine(this.petPlayer, stats.get(this.index)));

        this.setItem(UP_SLOT, ItemBuilder.fromConfig(config, "up").placeholder(placeholder).build(),
                event -> this.move(-1));
        this.setItem(DOWN_SLOT, ItemBuilder.fromConfig(config, "down").placeholder(placeholder).build(),
                event -> this.move(1));
        this.setItem(INFO_SLOT, ItemBuilder.fromConfig(config, "info").placeholder(placeholder).build());
        this.setItem(BACK_SLOT, ItemBuilder.fromConfig(config, "back").placeholder(placeholder).build(),
                event -> this.back());

        if (stats.isEmpty()) {
            this.setItem(STAT_SLOT, ItemBuilder.fromConfig(config, "no-stats").placeholder(placeholder).build());
            return;
        }

        boolean statLocked = this.petPlayer.isStatLocked(stats.get(this.index).key());
        this.setItem(STAT_SLOT, ItemBuilder.fromConfig(config, statLocked ? "stat-locked" : "stat-unlocked")
                .placeholder(placeholder).build());

        String toggle = statLocked ? "unlock" : this.petPlayer.canLockAnother() ? "lock" : "lock-blocked";
        this.setItem(TOGGLE_SLOT, ItemBuilder.fromConfig(config, toggle)
                .placeholder(placeholder).build(), event -> this.toggle());
    }

    /**
     * What the stat reads as on the pet itself, so the same wording is used on
     * the pet, in this menu and in the reroll confirmation.
     */
    static String statLine(PetPlayer petPlayer, PetStat stat) {
        PetStatDefinition definition = stat.getDefinition();
        if (definition == null) return stat.key();
        return definition.display().replace("{value}", Common.digits(petPlayer.getScaledValue(stat)));
    }

    private void move(int delta) {
        int size = this.petPlayer.getStats().size();
        if (size <= 1) {
            Utils.fail(this.viewer);
            return;
        }
        this.index = Math.floorMod(this.index + delta, size);
        Utils.click(this.viewer);
        this.redraw();
    }

    private void toggle() {
        List<PetStat> stats = this.petPlayer.getStats();
        if (this.index >= stats.size()) {
            Utils.fail(this.viewer);
            return;
        }
        String key = stats.get(this.index).key();

        if (this.petPlayer.isStatLocked(key)) {
            this.petPlayer.unlockStat(key);
        } else {
            // Protecting every stat would spend the token, the charms and the
            // money on a reroll with nothing left to change
            if (this.petPlayer.isAtLockLimit()) {
                Utils.fail(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-stat-lock-max", new Placeholder()
                        .add("{max}", String.valueOf(PetSettings.getMaxStatLocks())));
                return;
            }
            if (!this.petPlayer.canLockAnother()) {
                Utils.fail(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-stat-lock-needs-one");
                return;
            }
            // What the whole attempt would cost with this stat locked too has
            // to be charms they are actually holding, so the menu can never
            // promise more than the confirm can take
            int cost = PetSettings.getStatLockCost(this.petPlayer.getLockedStatCount() + 1);
            int owned = this.parent.charmCount(this.parent.charm());
            if (owned < cost) {
                Utils.fail(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-stat-lock-no-charms", new Placeholder()
                        .add("{cost}", String.valueOf(cost))
                        .add("{charms}", String.valueOf(owned)));
                return;
            }
            this.petPlayer.lockStat(key);
        }
        Utils.click(this.viewer);
        this.redraw();
    }

    /**
     * Walk back to the reroll confirmation with every lock still standing. The
     * flag is cleared in a finally, so a menu that fails to open still leaves
     * this one dropping the locks.
     */
    private void back() {
        Utils.click(this.viewer);

        this.navigating = true;
        try {
            this.parent.reopen();
        } finally {
            this.navigating = false;
        }

        // Drop the locks if the walk back did not land after all, so no stat is
        // ever left protected with no menu holding it
        Executor.syncLater(1L, () -> {
            if (!this.viewer.isOnline()) {
                this.petPlayer.clearLockedStats();
                return;
            }
            this.viewer.updateInventory();
            InventoryHolder holder = this.viewer.getOpenInventory().getTopInventory().getHolder();
            if (holder != this.parent && holder != this) this.petPlayer.clearLockedStats();
        });
    }

    private void redraw() {
        this.draw();
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        // Nothing in this menu is ever an item the player may have, and a shift
        // click out of their own inventory aims straight at it. The buttons
        // still run: FastInv calls their handler whether the click went through
        // or not
        if (event.getClickedInventory() != this.viewer.getInventory() || event.isShiftClick()) {
            event.setCancelled(true);
            Executor.syncLater(1L, this.viewer::updateInventory);
        }
    }

    @Override
    protected void onDrag(InventoryDragEvent event) {
        int size = this.getInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < size) {
                event.setCancelled(true);
                Executor.syncLater(1L, this.viewer::updateInventory);
                return;
            }
        }
    }

}
