package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import id.alwination.slimefunpet.pets.stats.PetStatType;
import id.alwination.slimefunpet.utils.ColorUtil;
import id.alwination.slimefunpet.utils.PlayableSound;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One reroll attempt. The stat locks are picked in {@link PetStatLockInventory}
 * and payment stays in the player's inventory until confirm.
 * <p>
 * What the locks cost in charms is a total for the attempt, worked out by
 * {@link PetSettings#getStatLockCost(int)}, not one charm per stat.
 */
// Identity on purpose: "is this exact inventory/menu open", the click safety of the menu rests on it.
// navigating is read by the close handler that runs inside open(); PMD cannot see that.
@SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.UnusedAssignment"})
public final class PetRerollInventory extends FastInv {

    private static final int PET_SLOT = 13;
    private static final int LOCK_SLOT = 22;
    private static final int CANCEL_SLOT = 29;
    private static final int INFO_SLOT = 31;
    private static final int CONFIRM_SLOT = 33;
    private static final int DONE_SLOT = 40;

    // The reveal fills the middle row first, then the one under it
    private static final int FIRST_ROW_CENTER = 22;
    private static final int SECOND_ROW_CENTER = 31;
    private static final int ROW_WIDTH = 7;

    private static final List<String> DEFAULT_SPIN_ITEMS = List.of(
            "LIME_STAINED_GLASS_PANE", "LIGHT_BLUE_STAINED_GLASS_PANE",
            "MAGENTA_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE",
            "ORANGE_STAINED_GLASS_PANE", "PINK_STAINED_GLASS_PANE",
            "PURPLE_STAINED_GLASS_PANE", "CYAN_STAINED_GLASS_PANE");
    private static final List<String> DEFAULT_ROLLING_ITEMS = List.of(
            "EMERALD", "DIAMOND", "GOLD_INGOT", "AMETHYST_SHARD", "REDSTONE", "LAPIS_LAZULI");

    private final Player viewer;
    private final PetPlayer petPlayer;
    private final PetItem token;

    // Main-thread owned. Closing invalidates the attempt so queued clicks cannot spend twice.
    private boolean resolved;

    // True only while this menu is handing the player over to the stat lock
    // menu, which carries the attempt on rather than ending it.
    private boolean navigating;

    // Main-thread owned. The reveal only starts once the reroll is paid for and
    // saved, so it is purely for show: closing the menu cancels it and loses nothing.
    @Nullable
    private BukkitTask animation;
    private boolean revealDone;

    public PetRerollInventory(Player viewer, PetPlayer petPlayer, PetItem token) {
        super(45, Common.color(ModernSlimefunPet.PET_REROLL_CONFIRM_GUI_CONFIG.getString("title", "&8Confirm Pet Reroll")));
        this.viewer = viewer;
        this.petPlayer = petPlayer;
        this.token = token;
        this.addCloseHandler(event -> {
            // InventoryCloseEvent cannot be cancelled, so the reveal stays up by
            // reopening next tick, same as AlwiFix's repair menu. Not keyed on
            // the close reason: reopenAnimation decides whether it still can
            if (this.isAnimating()) {
                Executor.syncLater(1L, this::reopenAnimation);
                return;
            }
            if (this.animation != null) this.animation.cancel();
            if (this.navigating) return;
            this.resolved = true;
            // An abandoned attempt must never protect a stat in a later one
            this.petPlayer.clearLockedStats();
        });
        this.draw();
    }

    private void draw() {
        FileConfiguration config = ModernSlimefunPet.PET_REROLL_CONFIRM_GUI_CONFIG.getConfig();
        this.clearItems();
        for (int slot = 0; slot < this.getInventory().getSize(); slot++) {
            this.setItem(slot, ItemBuilder.fromConfig(config, "filler").build());
        }
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        if (pet != null) this.setItem(PET_SLOT, pet.getItem(this.petPlayer));

        int locked = this.petPlayer.getLockedStatCount();
        Placeholder placeholder = new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .add("{locked}", String.valueOf(locked))
                .add("{cost}", String.valueOf(PetSettings.getStatLockCost(locked)))
                .add("{stats}", String.valueOf(this.petPlayer.getStats().size()))
                .add("{charms}", String.valueOf(this.charmCount(this.charm())));

        this.setItem(LOCK_SLOT, this.withStatLines(config,
                ItemBuilder.fromConfig(config, "stat-lock").placeholder(placeholder).build()));
        this.setItem(CANCEL_SLOT, ItemBuilder.fromConfig(config, "cancel").placeholder(placeholder).build());
        this.setItem(INFO_SLOT, this.withStatLines(config,
                ItemBuilder.fromConfig(config, "info").placeholder(placeholder).build()));
        this.setItem(CONFIRM_SLOT, this.withStatLines(config,
                ItemBuilder.fromConfig(config, "confirm").placeholder(placeholder).build()));
    }

    /**
     * Expand a '{stat_list}', '{locked_stats}' or '{unlocked_stats}' line of
     * the lore into one line per stat, since a placeholder only ever stands
     * for a single line.
     */
    private ItemStack withStatLines(FileConfiguration config, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return stack;

        var meta = stack.getItemMeta();
        var lore = meta.lore();
        if (lore == null) return stack;

        List<String> lines = new ArrayList<>();
        boolean found = false;
        for (var component : lore) {
            String line = ColorUtil.fromComponent(component);
            if (line.contains("{stat_list}")) {
                lines.addAll(this.statLines(config, true, true));
            } else if (line.contains("{locked_stats}")) {
                lines.addAll(this.statLines(config, true, false));
            } else if (line.contains("{unlocked_stats}")) {
                lines.addAll(this.statLines(config, false, true));
            } else {
                lines.add(line);
                continue;
            }
            found = true;
        }
        if (!found) return stack;

        meta.lore(lines.stream().map(ColorUtil::toComponent).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * One line per stat of the pet, marked as locked or not, keeping only the
     * kinds asked for. Never empty, so the lore does not lose its line.
     */
    private List<String> statLines(FileConfiguration config, boolean withLocked, boolean withUnlocked) {
        String lockedFormat = config.getString("stat-lines.locked", "&a✔{stat}");
        String unlockedFormat = config.getString("stat-lines.unlocked", "&c✘{stat}");

        List<String> lines = new ArrayList<>();
        for (PetStat stat : this.petPlayer.getStats()) {
            boolean locked = this.petPlayer.isStatLocked(stat.key());
            if (locked ? !withLocked : !withUnlocked) continue;
            lines.add((locked ? lockedFormat : unlockedFormat)
                    .replace("{stat}", PetStatLockInventory.statLine(this.petPlayer, stat)));
        }
        if (lines.isEmpty()) lines.add(config.getString("stat-lines.none", " &8- &7None"));
        return lines;
    }

    private boolean isAnimating() {
        return this.animation != null && !this.animation.isCancelled();
    }

    /**
     * Put the reveal back on screen after it was closed, the way AlwiFix's
     * repair menu does. A player who left or died ends the reveal instead, so
     * the task never keeps running for nobody.
     */
    private void reopenAnimation() {
        if (!this.isAnimating()) return;
        if (this.viewer.isOnline() && !this.viewer.isDead()) {
            this.viewer.openInventory(this.getInventory());
            return;
        }
        this.animation.cancel();
    }

    /**
     * Redraw and show this menu again, used when walking back from the stat
     * lock menu.
     */
    void reopen() {
        this.draw();
        this.open(this.viewer);
    }

    PetPlayer petPlayer() {
        return this.petPlayer;
    }

    /**
     * The stat lock charm this attempt would pay with, or null when the player
     * holds none that may be used on this pet.
     */
    @Nullable
    PetItem charm() {
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        for (ItemStack stack : this.viewer.getInventory().getContents()) {
            PetItem item = PetItemRegistry.fromItemStack(stack);
            if (item != null && item.type() == PetItemType.STAT_LOCK && item.allows(pet)) return item;
        }
        return null;
    }

    /**
     * How many of that charm the player is holding, counted across every slot,
     * which is what the cost of the locks is measured against.
     */
    int charmCount(@Nullable PetItem charm) {
        if (charm == null) return 0;
        int total = 0;
        for (ItemStack stack : this.viewer.getInventory().getContents()) {
            PetItem item = PetItemRegistry.fromItemStack(stack);
            if (item != null && item.type() == charm.type() && item.id().equals(charm.id())) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Executor.syncLater(1L, this.viewer::updateInventory);
        if (!event.getWhoClicked().getUniqueId().equals(this.viewer.getUniqueId())) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) return;
        if (event.getClickedInventory() != this.getInventory()) return;

        if (this.revealDone) {
            if (event.getSlot() == DONE_SLOT) Executor.syncLater(1L, this.viewer::closeInventory);
            return;
        }
        if (this.resolved) return;

        switch (event.getSlot()) {
            case LOCK_SLOT -> this.openLockMenu();
            case CANCEL_SLOT -> Executor.syncLater(1L, this.viewer::closeInventory);
            case CONFIRM_SLOT -> {
                // Inventory transitions and configured commands must run outside InventoryClickEvent.
                this.resolved = true;
                Executor.syncLater(1L, this::confirm);
            }
            default -> { }
        }
    }

    @Override
    protected void onDrag(InventoryDragEvent event) {
        event.setCancelled(true);
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    private void openLockMenu() {
        if (this.petPlayer.getStats().isEmpty()) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-no-stats", new Placeholder()
                    .add("{pet}", this.petName()));
            return;
        }

        PetStatLockInventory menu = new PetStatLockInventory(this.viewer, this);
        this.navigating = true;
        try {
            menu.open(this.viewer);
        } finally {
            this.navigating = false;
        }
    }

    private String petName() {
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        return pet == null ? "" : pet.getItemDisplayName();
    }

    private void confirm() {
        if (!this.viewer.isOnline() || this.viewer.getOpenInventory().getTopInventory() != this.getInventory()) return;
        Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(this.petPlayer.getId());
        if (PetManager.getPetPlayer(this.petPlayer.getUniqueId()) != this.petPlayer
                || !this.petPlayer.getOwner().equals(this.viewer.getUniqueId()) || pet == null
                || this.petPlayer.isDead()) {
            Utils.fail(this.viewer);
            this.viewer.closeInventory();
            return;
        }

        // The charms the locks cost have to still be there now: they were
        // picked in another menu and nothing was reserved back then
        int cost = PetSettings.getStatLockCost(this.petPlayer.getLockedStatCount());
        PetItem charm = cost > 0 ? this.charm() : null;
        if (cost > 0 && this.charmCount(charm) < cost) {
            this.petPlayer.clearLockedStats();
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-stat-lock-gone");
            this.retry();
            return;
        }

        int tokenSlot = this.findItem(this.token);
        if (tokenSlot < 0) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-item-gone");
            this.viewer.closeInventory();
            return;
        }

        // Reserve payment before service commands can close menus or change the inventory.
        PlayerInventory inventory = this.viewer.getInventory();
        Map<Integer, ItemStack> reserved = new LinkedHashMap<>();
        this.take(reserved, tokenSlot, 1);
        if (cost > 0) this.takeCharms(reserved, charm, cost);

        // Read before the reroll, which clears the locks either way
        Set<String> kept = new HashSet<>();
        for (PetStat stat : this.petPlayer.getStats()) {
            if (this.petPlayer.isStatLocked(stat.key())) kept.add(stat.key());
        }

        boolean consumed;
        try {
            consumed = ModernSlimefunPet.getInstance().getPetService().reroll(this.viewer, this.petPlayer, this.token);
        } finally {
            // A failed attempt must never protect a stat in a later reroll.
            this.petPlayer.clearLockedStats();
        }
        if (!consumed) {
            reserved.forEach(inventory::setItem);
            this.retry();
            return;
        }
        // Service commands may have closed or swapped the menu in the meantime
        if (this.viewer.getOpenInventory().getTopInventory() != this.getInventory()) {
            Utils.success(this.viewer);
            return;
        }
        if (!ModernSlimefunPet.PET_REROLL_CONFIRM_GUI_CONFIG.getConfig().getBoolean("animation.enabled", true)) {
            Utils.success(this.viewer);
            this.viewer.closeInventory();
            return;
        }
        this.playReveal(pet, kept);
    }

    /**
     * Spin the menu, then show the new stats one by one, then a finish screen
     * with a button to close it. Every key has a default, since a server's
     * existing copy of the config never gains the 'animation' section by itself.
     */
    private void playReveal(Pet pet, Set<String> kept) {
        FileConfiguration config = ModernSlimefunPet.PET_REROLL_CONFIRM_GUI_CONFIG.getConfig();
        int spinTicks = Math.max(0, config.getInt("animation.spin-ticks", 20));
        int revealDelay = Math.max(1, config.getInt("animation.reveal-delay-ticks", 10));
        PlayableSound spinSound = sound(config, "animation.spin-sound", "BLOCK_NOTE_BLOCK_HAT", 0.6f);
        PlayableSound revealSound = sound(config, "animation.reveal-sound", "ENTITY_ENDER_DRAGON_SHOOT", 0.7f);
        PlayableSound finishSound = sound(config, "animation.finish-sound", "UI_TOAST_CHALLENGE_COMPLETE", 0.8f);

        List<PetStat> stats = new ArrayList<>(this.petPlayer.getStats());
        // ponytail: two rows of 7, a pet with more than 14 stats only reveals the first 14
        int shown = Math.min(stats.size(), ROW_WIDTH * 2);
        List<Integer> revealSlots = revealSlots(shown);
        List<Integer> frameSlots = new ArrayList<>();
        for (int slot = 0; slot < this.getInventory().getSize(); slot++) {
            if (slot != PET_SLOT && !revealSlots.contains(slot)) frameSlots.add(slot);
        }

        List<ItemStack> spinItems = new ArrayList<>();
        for (Material material : materials(config, "animation.spin-items", DEFAULT_SPIN_ITEMS)) {
            spinItems.add(pane(material));
        }
        // Every rolling item carries the 'rolling' name and lore, only its material changes
        ItemStack rolling = configItem(config, "animation.rolling", Material.BLACK_STAINED_GLASS_PANE,
                "&8&l???", List.of(), new Placeholder());
        List<ItemStack> rollingItems = new ArrayList<>();
        for (Material material : materials(config, "animation.rolling-items", DEFAULT_ROLLING_ITEMS)) {
            rollingItems.add(rolling.withType(material));
        }
        int finishAt = spinTicks + shown * revealDelay;

        this.clearItems();
        this.setItem(PET_SLOT, pet.getItem(this.petPlayer));
        this.animation = new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (!viewer.isOnline()) {
                    this.cancel();
                    return;
                }
                // Closed with Esc a moment ago: wait for reopenAnimation to put it
                // back (or end the reveal) instead of playing on to nobody
                if (viewer.getOpenInventory().getTopInventory() != getInventory()) return;

                // A slower flicker once the stats start landing, so they stand out
                if (tick % (tick < spinTicks ? 2 : 4) == 0) {
                    for (int slot : frameSlots) setItem(slot, random(spinItems));
                }

                if (tick < spinTicks) {
                    if (tick % 2 == 0) {
                        for (int slot : revealSlots) setItem(slot, random(rollingItems));
                        // Rising pitch builds up to the first reveal
                        play(spinSound, 0.5f + 1.5f * tick / spinTicks);
                    }
                } else if (tick < finishAt) {
                    int since = tick - spinTicks;
                    if (since % revealDelay == 0) {
                        int index = since / revealDelay;
                        PetStat stat = stats.get(index);
                        setItem(revealSlots.get(index), statItem(config, stat, kept.contains(stat.key())));
                        play(revealSound, 1.0f + 0.1f * index);
                    }
                } else {
                    finish(pet, frameSlots, finishSound);
                    this.cancel();
                    return;
                }
                tick++;
            }
        }.runTaskTimer(ModernSlimefunPet.getInstance(), 0L, 1L);
    }

    private void finish(Pet pet, List<Integer> frameSlots, PlayableSound finishSound) {
        FileConfiguration config = ModernSlimefunPet.PET_REROLL_CONFIRM_GUI_CONFIG.getConfig();
        ItemStack gold = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int slot : frameSlots) this.setItem(slot, gold);
        this.setItem(PET_SLOT, pet.getItem(this.petPlayer));
        this.setItem(DONE_SLOT, configItem(config, "animation.done", Material.LIME_STAINED_GLASS_PANE,
                "&a&lDone", List.of("&7Click to close."), new Placeholder()));
        this.play(finishSound, 1.0f);
        this.revealDone = true;
    }

    /**
     * Where the stats land: the middle row first, the ones past 7 in the row
     * below it.
     */
    private static List<Integer> revealSlots(int amount) {
        List<Integer> slots = new ArrayList<>();
        int first = Math.min(amount, ROW_WIDTH);
        centred(slots, FIRST_ROW_CENTER, first);
        centred(slots, SECOND_ROW_CENTER, amount - first);
        return slots;
    }

    /**
     * Lay out a count symmetrically around a row's centre slot. An odd count
     * sits on the centre, an even one leaves the centre empty so both sides
     * match (4 becomes 2 + gap + 2) instead of hanging one slot to the left.
     */
    private static void centred(List<Integer> slots, int center, int count) {
        int half = count / 2;
        for (int i = half; i > 0; i--) slots.add(center - i);
        if (count % 2 != 0) slots.add(center);
        for (int i = 1; i <= half; i++) slots.add(center + i);
    }

    /**
     * The materials listed at this path, the defaults when the list is missing
     * or none of its names is a real item.
     */
    private static List<Material> materials(FileConfiguration config, String path, List<String> defaults) {
        List<Material> materials = new ArrayList<>();
        for (String name : config.getStringList(path)) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem() && !material.isAir()) {
                materials.add(material);
            } else {
                Logger.warning("Invalid item '" + name + "' in '" + path + "' of pet_reroll_confirm_gui.yml, skipping it.");
            }
        }
        if (materials.isEmpty()) {
            for (String name : defaults) materials.add(Material.valueOf(name));
        }
        return materials;
    }

    private static ItemStack random(List<ItemStack> items) {
        return items.get(ThreadLocalRandom.current().nextInt(items.size()));
    }

    private ItemStack statItem(FileConfiguration config, PetStat stat, boolean kept) {
        PetStatDefinition definition = stat.getDefinition();
        boolean bad = definition != null && definition.type() == PetStatType.BAD;
        Placeholder placeholder = new Placeholder().add("{stat}", PetStatLockInventory.statLine(this.petPlayer, stat));
        if (kept) {
            return configItem(config, "animation.stat-kept", Material.NAME_TAG,
                    "{stat}", List.of("&bKept by a stat lock"), placeholder);
        }
        if (bad) {
            return configItem(config, "animation.stat-bad", Material.REDSTONE,
                    "{stat}", List.of("&cCursed stat"), placeholder);
        }
        return configItem(config, "animation.stat-good", Material.EMERALD,
                "{stat}", List.of("&aNew stat"), placeholder);
    }

    /**
     * The item at this config path, or one built from the given defaults when
     * the server's config is older than that path.
     */
    private static ItemStack configItem(FileConfiguration config, String path, Material material,
                                        String name, List<String> lore, Placeholder placeholder) {
        if (config.isConfigurationSection(path)) {
            return ItemBuilder.fromConfig(config, path).placeholder(placeholder).build();
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ColorUtil.toComponent(placeholder.translate(name)));
        meta.lore(lore.stream().map(line -> ColorUtil.toComponent(placeholder.translate(line))).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack pane(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(ColorUtil.toComponent(" "));
        stack.setItemMeta(meta);
        return stack;
    }

    private static PlayableSound sound(FileConfiguration config, String path, String name, float volume) {
        return new PlayableSound(config.getString(path + ".name", name),
                (float) config.getDouble(path + ".volume", volume),
                (float) config.getDouble(path + ".pitch", 1.0));
    }

    private void play(PlayableSound sound, float pitchScale) {
        new PlayableSound(sound.name(), sound.volume(), sound.pitch() * pitchScale).play(this.viewer);
    }

    /**
     * Nothing was spent, so hand the attempt back to the player with the locks
     * gone and the menu saying so.
     */
    private void retry() {
        this.resolved = false;
        Utils.fail(this.viewer);
        this.draw();
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    /**
     * Take items off one slot, remembering what was there first so a failed
     * attempt can put every slot it touched back exactly as it was.
     */
    private void take(Map<Integer, ItemStack> reserved, int slot, int amount) {
        PlayerInventory inventory = this.viewer.getInventory();
        ItemStack stack = inventory.getItem(slot);
        if (stack == null) return;

        reserved.putIfAbsent(slot, stack.clone());
        ItemStack left = stack.clone();
        left.setAmount(left.getAmount() - amount);
        inventory.setItem(slot, left.getAmount() <= 0 ? null : left);
    }

    /**
     * Take the charms for the locked stats, which can span several stacks. The
     * count was checked against the same inventory a moment earlier, so this
     * always finds them all.
     */
    private void takeCharms(Map<Integer, ItemStack> reserved, PetItem charm, int amount) {
        PlayerInventory inventory = this.viewer.getInventory();
        int left = amount;
        for (int slot = 0; slot < inventory.getSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            PetItem item = PetItemRegistry.fromItemStack(stack);
            if (item == null || item.type() != charm.type() || !item.id().equals(charm.id())) continue;

            int taken = Math.min(left, stack.getAmount());
            this.take(reserved, slot, taken);
            left -= taken;
        }
    }

    private int findItem(PetItem expected) {
        PlayerInventory inventory = this.viewer.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            PetItem item = PetItemRegistry.fromItemStack(stack);
            if (stack != null && stack.getAmount() > 0 && item != null
                    && item.type() == expected.type() && item.id().equals(expected.id())) return slot;
        }
        return -1;
    }
}
