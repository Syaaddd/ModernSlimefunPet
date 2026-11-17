package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.evolution.EvolutionRequirements;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetService;
import id.alwination.slimefunpet.utils.ColorUtil;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu one pet is evolved from.
 * <p>
 * No slot of this menu ever holds anything belonging to the player. Every click
 * landing in it is refused, and the only thing that ever moves is what the
 * plugin puts there itself, so there is nothing here to pull out with a click,
 * a drag or a packet no normal client would send.
 */
public final class PetEvolutionInventory extends FastInv {

    private final Player viewer;
    private final PetPlayer petPlayer;

    /**
     * The stone the menu was opened with, taken from the hand on confirm.
     * Null when an admin opened the menu with a command, where the evolution
     * costs no stone at all.
     */
    private final PetItem stone;

    private final List<Integer> animationSlots;

    /**
     * Set once the roll has happened. From then on no click does anything,
     * which is what stops a second attempt from the same menu.
     */
    private boolean resolved = false;

    private HandleTask animationTask;

    public PetEvolutionInventory(Player viewer, PetPlayer petPlayer, PetItem stone) {
        super(ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getInt("option.size", 45),
                Common.color(title(petPlayer)));

        this.viewer = viewer;
        this.petPlayer = petPlayer;
        this.stone = stone;

        FileConfiguration config = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getConfig();
        this.animationSlots = config.getIntegerList("animation.slots");

        this.setAllItems();

    }

    private static String title(PetPlayer petPlayer) {
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(petPlayer.getId());
        String title = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getString("option.title", "&8Evolve a Pet");
        return new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .translate(title);
    }

    // ------------------------------------------------------------------
    //  Layout
    // ------------------------------------------------------------------

    /**
     * Draw the menu again from the config, with every number as it stands right
     * now, so the success rate on screen is never behind
     * what the attempt would really roll with.
     */
    void setAllItems() {
        if (this.resolved) return;

        FileConfiguration config = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getConfig();
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        if (pet == null) return;

        this.clearItems();

        for (int slot : this.animationSlots) {
            this.setItem(slot, pane(config.getString("animation.filler-material", "GRAY_STAINED_GLASS_PANE"), " "));
        }

        if (!config.isConfigurationSection("items")) return;

        for (String key : config.getConfigurationSection("items").getKeys(false)) {
            String path = "items." + key;
            String type = config.getString(path + ".type", "DUMMY");
            int slot = config.getInt(path + ".slot", -1);
            if (slot < 0) continue;

            if (type.equalsIgnoreCase("PET")) {
                this.setItem(slot, pet.getItem(this.petPlayer));
                continue;
            }

            ItemStack stack = ItemBuilder.fromConfig(config, path)
                    .placeholder(this.placeholder(pet))
                    .build();

            if (type.equalsIgnoreCase("CONFIRM")) {
                this.setItem(slot, withRequirements(stack, this.viewer, pet, this.petPlayer), event -> this.confirm());
                continue;
            }

            this.setItem(slot, withRequirements(stack, this.viewer, pet, this.petPlayer));
        }
    }

    /**
     * Everything the menu can show about this evolution.
     */
    private Placeholder placeholder(Pet pet) {
        return placeholders(pet, this.petPlayer, this.stoneBonus());
    }

    /**
     * The numbers of one attempt: base chance plus the stone's bonus.
     */
    static Placeholder placeholders(Pet pet, PetPlayer petPlayer, double stoneBonus) {
        Pet.PetEvolution evolution = pet.getEvolution();
        double chance = evolution == null ? 0 : evolution.chanceWith(stoneBonus);

        return new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{into}", evolution == null ? "" : evolution.displayName())
                .add("{base_chance}", Common.digits(evolution == null ? 0 : evolution.chance()))
                .add("{base_fail_chance}", Common.digits(evolution == null ? 100 : 100 - evolution.chance()))
                .add("{stone_bonus}", Common.digits(stoneBonus))
                .add("{chance}", Common.digits(chance))
                .add("{success_chance}", Common.digits(chance))
                .add("{fail_chance}", Common.digits(evolution == null
                        ? 100 : evolution.failChanceWith(stoneBonus)))
                .add("{required_level}", Common.digits(evolution == null ? 0 : evolution.requiredLevel()))
                .add("{level}", Common.digits(petPlayer.getLevel()))
                .add("{level_on_fail}", evolution != null && evolution.resetLevelOnFail()
                        ? "1" : Common.digits(petPlayer.getLevel()));
    }

    /**
     * Expand a '{requirements}' line of the lore into one line per requirement.
     */
    static ItemStack withRequirements(ItemStack stack, Player viewer, Pet pet, PetPlayer petPlayer) {
        if (stack == null || !stack.hasItemMeta()) return stack;

        var meta = stack.getItemMeta();
        var lore = meta.lore();
        if (lore == null) return stack;

        List<String> lines = new ArrayList<>();
        boolean found = false;
        for (var component : lore) {
            String line = ColorUtil.fromComponent(component);
            if (line.contains("{requirements}")) {
                lines.addAll(EvolutionRequirements.statusLines(viewer, pet, petPlayer));
                found = true;
                continue;
            }
            lines.add(line);
        }
        if (!found) return stack;

        meta.lore(lines.stream().map(ColorUtil::toComponent).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Take {@code taken} items off the slot that was clicked, clearing it when
     * that is all of them.
     */
    static void takeFrom(InventoryClickEvent event, ItemStack clicked, int taken) {
        int left = clicked.getAmount() - taken;
        if (left <= 0) {
            event.setCurrentItem(null);
            return;
        }
        ItemStack rest = clicked.clone();
        rest.setAmount(left);
        event.setCurrentItem(rest);
    }

    static ItemStack pane(String material, String name) {
        Material type = Material.matchMaterial(material == null ? "" : material);
        if (type == null) type = Material.GRAY_STAINED_GLASS_PANE;
        return new ItemBuilder(type).name(name).build();
    }

    // ------------------------------------------------------------------
    //  Clicks
    // ------------------------------------------------------------------

    /**
     * Turn a click or a drag down and put the client back in step with the
     * server.
     * <p>
     * Cancelling alone is not enough: the item has already moved on their
     * screen, and that ghost copy stays there until the server tells them
     * otherwise, which is how a pane out of the decoration looks like it can be
     * shift clicked out of the menu and kept.
     */
    private void refuse(Cancellable event) {
        event.setCancelled(true);
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            this.refuse(event);
            return;
        }

        // A hotbar swap would change the stone in hand mid attempt, and a double
        // click collects every matching stack to the cursor, the decoration of
        // the menu included, so neither is ever allowed
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND
                || click == ClickType.DOUBLE_CLICK || click.isCreativeAction()) {
            this.refuse(event);
            return;
        }

        // Nothing in this menu is ever an item the player may have, so every
        // click landing in it is turned down. The buttons still run, FastInv
        // calls their handler whether the click went through or not
        if (clicked.equals(this.getInventory())) {
            this.refuse(event);
            return;
        }

        if (this.resolved) {
            this.refuse(event);
            return;
        }

        // A shift-click would move the item into this menu (boosters used to be handed in that way)
        if (event.isShiftClick()) {
            this.refuse(event);
            return;
        }

        // Anything else they do to their own items is their business
        event.setCancelled(false);
    }

    @Override
    protected void onDrag(InventoryDragEvent event) {
        int size = this.getInventory().getSize();
        for (int slot : event.getRawSlots()) {
            // Their own inventory is not our problem, this menu is
            if (slot < size) {
                this.refuse(event);
                return;
            }
        }
        event.setCancelled(false);
    }

    // ------------------------------------------------------------------
    //  The attempt
    // ------------------------------------------------------------------

    private void confirm() {
        if (this.resolved) return;

        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        if (pet == null) return;

        // The stone has to still be on them. There is no stone at all when a
        // command opened this menu, so there is nothing to check
        if (this.stone != null && this.findStone() < 0) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-evolution-stone-gone");
            this.viewer.closeInventory();
            return;
        }

        PetService service = ModernSlimefunPet.getInstance().getPetService();
        PetService.EvolutionResult result = service.evolve(this.viewer, this.petPlayer, this.stone);

        // The attempt never happened, nothing was taken
        if (result == PetService.EvolutionResult.INVALID) {
            Utils.fail(this.viewer);
            return;
        }

        // From here the menu is locked against a second attempt
        this.resolved = true;
        if (this.stone != null && result.shouldConsumeItem()) {
            this.takeStone();
        }

        this.playAnimation(result == PetService.EvolutionResult.SUCCESS);
    }

    /**
     * What the stone in hand is worth, nothing when there is no stone.
     */
    private double stoneBonus() {
        return this.stone == null ? 0 : this.stone.chanceBonus();
    }

    /**
     * Where the stone this menu was opened with is sitting, or -1 when the
     * player no longer has it. The hand is looked at first because that is
     * where it almost always is, but only the stone itself ever matches, so
     * moving the stack around never spends the wrong item.
     */
    private int findStone() {
        if (this.stone == null) return -1;

        var inventory = this.viewer.getInventory();
        int held = inventory.getHeldItemSlot();
        if (this.isStone(inventory.getItem(held))) return held;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (this.isStone(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    private boolean isStone(ItemStack stack) {
        PetItem item = PetItemRegistry.fromItemStack(stack);
        return item != null && item.type() == PetItemType.EVOLUTION && item.id().equals(this.stone.id());
    }

    private void takeStone() {
        int slot = this.findStone();
        if (slot < 0) return;

        ItemStack stack = this.viewer.getInventory().getItem(slot);
        stack.setAmount(stack.getAmount() - 1);
    }

    // ------------------------------------------------------------------
    //  Animation
    // ------------------------------------------------------------------

    /**
     * Blink the border for a moment before showing what happened, so the roll
     * feels like it was rolled instead of just being announced.
     */
    private void playAnimation(boolean success) {
        FileConfiguration config = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getConfig();
        List<String> blink = config.getStringList("animation.blink-materials");
        if (blink.isEmpty()) {
            blink = List.of("YELLOW_STAINED_GLASS_PANE", "ORANGE_STAINED_GLASS_PANE");
        }
        int blinks = Math.max(1, config.getInt("animation.blinks", 8));
        long interval = Math.max(1, config.getInt("animation.interval", 3));

        List<String> materials = blink;
        int[] step = {0};

        this.animationTask = Executor.syncTimer(1L, interval, () -> {
            // They walked away, the outcome already happened either way
            if (!this.isStillOpen()) {
                this.stopAnimation();
                return;
            }

            if (step[0] >= blinks) {
                this.stopAnimation();
                this.showResult(success);
                return;
            }

            String material = materials.get(step[0] % materials.size());
            for (int slot : this.animationSlots) {
                this.setItem(slot, pane(material, " "));
            }
            this.viewer.playSound(this.viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
            step[0]++;
        });
    }

    private void showResult(boolean success) {
        FileConfiguration config = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getConfig();

        String material = config.getString(success
                ? "animation.success-material" : "animation.fail-material",
                success ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE");
        String name = ModernSlimefunPet.PET_EVOLUTION_GUI_CONFIG.getString(success
                ? "animation.success-name" : "animation.fail-name", " ");

        for (int slot : this.animationSlots) {
            this.setItem(slot, pane(material, name));
        }

        // The pet itself changed, or died trying, so redraw it
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        if (pet != null && config.isConfigurationSection("items")) {
            for (String key : config.getConfigurationSection("items").getKeys(false)) {
                if (!"PET".equalsIgnoreCase(config.getString("items." + key + ".type"))) continue;
                int slot = config.getInt("items." + key + ".slot", -1);
                if (slot >= 0) this.setItem(slot, pet.getItem(this.petPlayer));
            }
        }

        this.viewer.playSound(this.viewer.getLocation(), success
                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO, 1.0f, success ? 1.2f : 0.8f);

        long hold = Math.max(1, config.getInt("animation.result-time", 50));
        Executor.syncLater(hold, () -> {
            if (this.isStillOpen()) this.viewer.closeInventory();
        });
    }

    private void stopAnimation() {
        if (this.animationTask == null) return;
        this.animationTask.cancel();
        this.animationTask = null;
    }

    private boolean isStillOpen() {
        return this.viewer.isOnline()
                && this.viewer.getOpenInventory().getTopInventory().equals(this.getInventory());
    }

}
