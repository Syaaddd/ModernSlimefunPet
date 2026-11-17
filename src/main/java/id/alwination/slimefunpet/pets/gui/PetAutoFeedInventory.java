package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.AutoFeed;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The Auto-Feed of one pet, opened from its options menu: going back on the
 * left, the upgrade next to it, the food it keeps in the middle five slots and
 * the on/off switch on the right.
 * <p>
 * The menu holds nothing. Every food slot draws what the pet has stored, and a
 * click moves food between the player and the pet in the same tick through
 * AutoFeed's locked calls, so closing the menu, or the server stopping, never
 * loses or doubles food.
 */
public final class PetAutoFeedInventory extends FastInv {

    private static final int BACK_SLOT = 0;
    private static final int UPGRADE_SLOT = 1;
    private static final int FIRST_FOOD_SLOT = 2;
    private static final int TOGGLE_SLOT = 8;

    private final Player viewer;
    private final PetOptionsInventory parent;
    private final PetPlayer petPlayer;

    PetAutoFeedInventory(Player viewer, PetOptionsInventory parent, PetPlayer petPlayer) {
        super(9, Common.color(ModernSlimefunPet.PET_AUTO_FEED_GUI_CONFIG.getString("title", "&8Auto-Feed")));
        this.viewer = viewer;
        this.parent = parent;
        this.petPlayer = petPlayer;
        this.draw();
    }

    private void draw() {
        FileConfiguration config = ModernSlimefunPet.PET_AUTO_FEED_GUI_CONFIG.getConfig();
        AutoFeed feed = this.petPlayer.getAutoFeed();
        int tier = feed.getTier();
        int open = PetSettings.getAutoFeedSlots(tier);
        boolean maxTier = tier >= PetSettings.getAutoFeedTiers() - 1;
        Pet pet = this.pet();

        Placeholder placeholder = new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .add("{tier}", String.valueOf(Math.min(tier, PetSettings.getAutoFeedTiers() - 1) + 1))
                .add("{max_tier}", String.valueOf(PetSettings.getAutoFeedTiers()))
                .add("{threshold}", Common.digits(PetSettings.getAutoFeedThreshold(tier)))
                .add("{slots}", String.valueOf(open))
                .add("{next_threshold}", Common.digits(PetSettings.getAutoFeedThreshold(tier + 1)))
                .add("{next_slots}", String.valueOf(PetSettings.getAutoFeedSlots(tier + 1)));

        this.clearItems();
        for (int slot = 0; slot < this.getInventory().getSize(); slot++) {
            this.setItem(slot, ItemBuilder.fromConfig(config, "filler").build());
        }

        this.setItem(BACK_SLOT, ItemBuilder.fromConfig(config, "back").placeholder(placeholder).build(), event -> {
            Utils.click(this.viewer);
            this.parent.reopen();
        });
        this.setItem(UPGRADE_SLOT, ItemBuilder.fromConfig(config, maxTier ? "upgrade-max" : "upgrade")
                .placeholder(placeholder).build(), event -> this.upgrade());

        // No handlers on the food slots, onClick serves them off the cursor
        for (int index = 0; index < AutoFeed.MAX_SLOTS; index++) {
            this.setItem(FIRST_FOOD_SLOT + index, this.foodSlotItem(config, feed, index, open, placeholder));
        }

        this.setItem(TOGGLE_SLOT, ItemBuilder.fromConfig(config, feed.isEnabled() ? "enabled" : "disabled")
                .placeholder(placeholder).build(), event -> this.toggle());
    }

    private ItemStack foodSlotItem(FileConfiguration config, AutoFeed feed, int index, int open, Placeholder placeholder) {
        String id = feed.getFoodId(index);
        int amount = feed.getAmount(index);
        if (id == null || amount <= 0) {
            return ItemBuilder.fromConfig(config, index < open ? "empty-slot" : "locked-slot").placeholder(placeholder).build();
        }

        ItemStack stack = PetItemRegistry.getItemStack(PetItemType.FOOD, id, amount);
        if (stack == null) {
            // Its food item was removed from pets.yml since it went in
            return ItemBuilder.fromConfig(config, "unknown-food").placeholder(placeholder).build();
        }

        ItemMeta meta = stack.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        Placeholder stored = new Placeholder().add("{amount}", String.valueOf(amount));
        for (String line : config.getStringList("stored-lore")) {
            lore.add(Common.color(stored.translate(line)));
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void upgrade() {
        if (this.petGone()) return;
        AutoFeed feed = this.petPlayer.getAutoFeed();
        if (feed.getTier() >= PetSettings.getAutoFeedTiers() - 1) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-max-tier");
            return;
        }
        if (!takeOne(this.viewer, PetItemType.AUTO_FEED_UPGRADE, this.pet())) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-needs-upgrade");
            return;
        }

        feed.upgrade();
        this.save();
        Utils.success(this.viewer);
        ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-upgraded", new Placeholder()
                .add("{threshold}", Common.digits(PetSettings.getAutoFeedThreshold(feed.getTier())))
                .add("{slots}", String.valueOf(PetSettings.getAutoFeedSlots(feed.getTier()))));
        this.redraw();
    }

    private void toggle() {
        if (this.petGone()) return;
        AutoFeed feed = this.petPlayer.getAutoFeed();
        feed.setEnabled(!feed.isEnabled());
        this.save();
        Utils.click(this.viewer);
        ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer,
                feed.isEnabled() ? "messages.pet-auto-feed-on" : "messages.pet-auto-feed-off");
        this.redraw();
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        ClickType click = event.getClick();
        // A hotbar swap or a double click would reach into the menu's slots,
        // and a creative click copies whatever it points at
        if (clicked == null || click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND
                || click == ClickType.DOUBLE_CLICK || click.isCreativeAction()) {
            this.refuse(event);
            return;
        }

        if (clicked.equals(this.getInventory())) {
            // Nothing in the menu is ever really taken or placed, a food slot is
            // served by hand below and the buttons by their own handlers
            this.refuse(event);
            int index = event.getSlot() - FIRST_FOOD_SLOT;
            if (index >= 0 && index < AutoFeed.MAX_SLOTS) {
                this.clickFoodSlot(event, index, click);
            }
            return;
        }

        // Their own inventory: shift clicking a food stores it without aiming
        if (event.isShiftClick()) {
            event.setCancelled(true);
            this.storeFromInventory(event);
            return;
        }
        event.setCancelled(false);
    }

    @Override
    protected void onDrag(InventoryDragEvent event) {
        int size = this.getInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < size) {
                this.refuse(event);
                return;
            }
        }
    }

    private void clickFoodSlot(InventoryClickEvent event, int index, ClickType click) {
        if (this.petGone()) return;
        AutoFeed feed = this.petPlayer.getAutoFeed();
        ItemStack cursor = event.getCursor();
        int open = PetSettings.getAutoFeedSlots(feed.getTier());

        // An empty hand takes the slot back
        if (cursor == null || cursor.getType().isAir()) {
            if (feed.getAmount(index) <= 0) {
                if (index >= open) {
                    Utils.fail(this.viewer);
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-slot-locked");
                }
                return;
            }
            if (giveBack(this.viewer, feed, index)) {
                this.save();
                Utils.click(this.viewer);
            } else {
                Utils.fail(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-unknown-food");
            }
            this.redraw();
            return;
        }

        if (index >= open) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-slot-locked");
            return;
        }
        PetItem food = this.usableFood(cursor);
        if (food == null) return;

        int wanted = click == ClickType.RIGHT ? 1 : cursor.getAmount();
        int taken = feed.deposit(index, food.id(), wanted, cursor.getMaxStackSize(), open);
        if (taken <= 0) {
            // Full, or holding another food
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-slot-taken");
            return;
        }

        int left = cursor.getAmount() - taken;
        if (left <= 0) {
            event.getView().setCursor(null);
        } else {
            ItemStack rest = cursor.clone();
            rest.setAmount(left);
            event.getView().setCursor(rest);
        }
        this.stored();
    }

    /**
     * Shift click from their own inventory: into the slots already holding
     * that food first, then into empty open ones.
     */
    private void storeFromInventory(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir() || this.petGone()) return;

        PetItem food = this.usableFood(current);
        if (food == null) {
            Executor.syncLater(1L, this.viewer::updateInventory);
            return;
        }

        AutoFeed feed = this.petPlayer.getAutoFeed();
        int open = PetSettings.getAutoFeedSlots(feed.getTier());
        int taken = 0;
        for (int pass = 0; pass < 2 && taken < current.getAmount(); pass++) {
            for (int index = 0; index < open && taken < current.getAmount(); index++) {
                boolean sameFood = food.id().equals(feed.getFoodId(index));
                if (pass == 0 ? !sameFood : feed.getFoodId(index) != null) continue;
                taken += feed.deposit(index, food.id(), current.getAmount() - taken, current.getMaxStackSize(), open);
            }
        }

        if (taken <= 0) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-full");
            Executor.syncLater(1L, this.viewer::updateInventory);
            return;
        }
        PetEvolutionInventory.takeFrom(event, current, taken);
        this.stored();
    }

    /**
     * The food a stack is, or null, after telling the player why, when it is
     * no food this pet may eat.
     */
    private PetItem usableFood(ItemStack stack) {
        PetItem food = PetItemRegistry.fromItemStack(stack);
        if (food == null || food.type() != PetItemType.FOOD) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-not-food");
            return null;
        }
        if (!food.allows(this.pet())) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-not-allowed");
            return null;
        }
        return food;
    }

    private void stored() {
        this.save();
        Utils.click(this.viewer);
        this.redraw();
    }

    private Pet pet() {
        return this.parent.parent().petManager().getPet(this.petPlayer.getId());
    }

    /**
     * Whether the pet was removed while this menu was open. Storing into it, or
     * saving it, would bring a deleted pet back with the food inside it, so the
     * menu closes instead.
     */
    private boolean petGone() {
        if (PetManager.getPetPlayer(this.petPlayer.getUniqueId()) == this.petPlayer) return false; // NOPMD - the same pet, not an equal one
        Utils.fail(this.viewer);
        this.viewer.closeInventory();
        return true;
    }

    /** Written at once: food moved between the player and the pantry. */
    private void save() {
        DatabaseQueueHandler.addObjectToSave(this.petPlayer);
        DatabaseQueueHandler.saveNow();
    }

    private void redraw() {
        this.draw();
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    /**
     * Turn a click or a drag down and put the client back in step with the
     * server, so no ghost item is left on their screen.
     */
    private void refuse(Cancellable event) {
        event.setCancelled(true);
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    /**
     * Hand the food in one slot back to a player, dropping what does not fit.
     *
     * @return false when its food item was removed from pets.yml, in which case
     * it stays stored rather than being lost
     */
    static boolean giveBack(Player player, AutoFeed feed, int slot) {
        String id = feed.getFoodId(slot);
        if (id == null) return true;

        ItemStack stack = PetItemRegistry.getItemStack(PetItemType.FOOD, id, 1);
        if (stack == null) return false;

        // Eating only lowers a slot, so whatever is taken here is this same food
        int amount = feed.take(slot);
        if (amount <= 0) return true;

        stack.setAmount(amount);
        Common.addInventoryItem(player, stack);
        return true;
    }

    /**
     * Use up one pet item of this type that may be used on the pet, the held
     * one first.
     *
     * @return false when the player has none
     */
    static boolean takeOne(Player player, PetItemType type, Pet pet) {
        PlayerInventory inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        int slot = matches(inventory.getItem(held), type, pet) ? held : -1;
        for (int i = 0; slot < 0 && i < inventory.getSize(); i++) {
            if (matches(inventory.getItem(i), type, pet)) slot = i;
        }
        if (slot < 0) return false;

        ItemStack stack = inventory.getItem(slot);
        stack.setAmount(stack.getAmount() - 1);
        return true;
    }

    private static boolean matches(ItemStack stack, PetItemType type, Pet pet) {
        PetItem item = PetItemRegistry.fromItemStack(stack);
        return item != null && item.type() == type && item.allows(pet);
    }
}
