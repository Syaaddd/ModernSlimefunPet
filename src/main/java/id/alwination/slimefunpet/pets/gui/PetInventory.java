package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.AutoFeed;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.evolution.EvolutionRequirements;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.managers.PetService;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.gui.helpers.PetPagination;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.utils.ColorUtil;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// Identity on purpose: "is this exact inventory/menu open", the click safety of the menu rests on it.
@SuppressWarnings({"PMD.CompareObjectsWithEquals"})
public final class PetInventory extends FastInv {

    private final Player viewer;
    private final Player target;
    private final PetManager petManager;
    private final PetTask petTask;
    private boolean removeToggle = false;

    /**
     * The pet item the player right clicked to open this menu, which pins the
     * menu to that one item. Null in the normal equip mode, and null again when
     * a command opened it, where any item of the mode will do instead.
     */
    private final PetItem petItem;

    /**
     * What clicking a pet does, null in the normal equip mode.
     */
    private final PetItemType mode;

    /**
     * The item a click would use, worked out once per redraw so the lore of
     * every pet is not scanning the inventory over and over.
     */
    private PetItem activeItem;

    private final Map<Integer, UUID> petSlots = new HashMap<>();

    private PetPagination pagination;

    public PetInventory(Player viewer, Player target, PetManager petManager, PetTask petTask) {
        this(viewer, target, petManager, petTask, null, null);
    }

    public PetInventory(Player viewer, Player target, PetManager petManager, PetTask petTask, PetItem petItem) {
        this(viewer, target, petManager, petTask, petItem, petItem == null ? null : petItem.type());
    }

    /**
     * Open the menu straight into a mode, which is what '/gens opengui' does.
     * No one item is picked out up front: the menu works off whatever item of
     * that kind the player owns at the moment they click a pet, and takes it
     * just as if they had right clicked it themselves.
     */
    public PetInventory(Player viewer, Player target, PetManager petManager, PetTask petTask, PetItemType mode) {
        this(viewer, target, petManager, petTask, null, mode);
    }

    private PetInventory(Player viewer, Player target, PetManager petManager, PetTask petTask,
                         PetItem petItem, PetItemType mode) {
        super(size(mode), Common.color(title(mode)));
        this.viewer = viewer;
        this.target = target;
        this.petManager = petManager;
        this.petTask = petTask;
        this.petItem = petItem;
        this.mode = mode;

        this.setAllItems();
        this.sendModeInstructions();
    }

    /**
     * Spell out what the menu expects, in chat.
     * <p>
     * The GUI alone is not obvious enough, and Bedrock players in particular
     * cannot hover an item to read its lore, so the instruction has to be
     * somewhere they will always see it.
     */
    private void sendModeInstructions() {
        if (this.mode == null) return;
        String path = modePath(this.mode) + ".message";
        if (!ModernSlimefunPet.PETS_CONFIG.getConfig().isSet(path)) return;

        ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, path, this.modePlaceholder());
    }

    /**
     * The 'gui-modes.<type>' section describing how this mode looks.
     */
    private static String modePath(PetItemType mode) {
        return "gui-modes." + mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Everything a mode message or lore line can show about the item in hand.
     */
    private Placeholder modePlaceholder() {
        PetItem item = this.activeItem;

        // They have nothing of this kind on them, so there is no item to read
        // any of this from
        if (item == null) {
            return new Placeholder()
                    .add("{food}", Common.digits(PetSettings.getMaxFood()))
                    .add("{exp}", "0")
                    .add("{levels}", "1")
                    .add("{stats}", Common.digits(PetSettings.getRerollStatAmount()))
                    .add("{cost}", "0")
                    .add("{chance_bonus}", "0");
        }

        return new Placeholder()
                .add("{food}", Common.digits(item.food()))
                .add("{exp}", Common.digits(item.exp()))
                .add("{levels}", Common.digits(item.levels()))
                // A reroll item that adds or takes stats away says so with its
                // own amount, one that rolls a whole new set uses the default
                .add("{stats}", Common.digits(item.stats() != 0
                        ? Math.abs(item.stats()) : PetSettings.getRerollStatAmount()))
                .add("{cost}", Common.digits(item.moneyCost()))
                .add("{chance_bonus}", Common.digits(item.chanceBonus()));
    }

    /**
     * What the evolution of one specific pet looks like right now, so the
     * player can read the odds and what they still need before they click.
     */
    private Placeholder evolutionPlaceholder(PetPlayer petPlayer) {
        Placeholder placeholder = this.modePlaceholder();

        Pet pet = this.petManager.getPet(petPlayer.getId());
        Pet.PetEvolution evolution = pet == null ? null : pet.getEvolution();
        if (evolution == null) {
            return placeholder
                    .add("{chance}", "0")
                    .add("{success_chance}", "0")
                    .add("{fail_chance}", "0")
                    .add("{base_chance}", "0")
                    .add("{base_fail_chance}", "0")
                    .add("{level_on_fail}", Common.digits(petPlayer.getLevel()))
                    .add("{required_level}", "0")
                    .add("{into}", ModernSlimefunPet.PETS_CONFIG.getString("placeholders.no-evolution", "&7None"));
        }

        double stoneBonus = this.activeItem == null ? 0 : this.activeItem.chanceBonus();
        return placeholder
                .add("{chance}", Common.digits(evolution.chanceWith(stoneBonus)))
                .add("{success_chance}", Common.digits(evolution.chanceWith(stoneBonus)))
                .add("{fail_chance}", Common.digits(evolution.failChanceWith(stoneBonus)))
                .add("{base_chance}", Common.digits(evolution.chance()))
                .add("{base_fail_chance}", Common.digits(100 - evolution.chance()))
                .add("{level_on_fail}", evolution.resetLevelOnFail()
                        ? "1" : Common.digits(petPlayer.getLevel()))
                .add("{required_level}", Common.digits(evolution.requiredLevel()))
                .add("{into}", evolution.displayName());
    }

    /**
     * The met/unmet lines of everything this evolution asks for, the pet level
     * first because that is the one the player cannot buy their way out of.
     */
    private List<String> evolutionRequirementLines(PetPlayer petPlayer) {
        return EvolutionRequirements.statusLines(this.viewer,
                this.petManager.getPet(petPlayer.getId()), petPlayer);
    }

    /**
     * The file this menu is laid out by. Every item type has one of its own, so
     * the feeding menu and the revival menu share nothing but the code.
     */
    private FileConfiguration layout() {
        return ModernSlimefunPet.getPetGuiConfig(this.mode).getConfig();
    }

    private static int size(PetItemType mode) {
        int size = ModernSlimefunPet.getPetGuiConfig(mode).getInt("option.size");
        // An empty or nonsense size would throw on the way into the inventory
        if (size < 9 || size > 54 || size % 9 != 0) {
            size = ModernSlimefunPet.PET_GUI_CONFIG.getInt("option.size");
        }
        return size < 9 ? 54 : size;
    }

    /**
     * Each mode gets its own title so the player can see what they are doing.
     * It lives in the menu file of the mode, an older setup that still has it
     * in the 'gui-modes' section of pets.yml keeps working.
     */
    private static String title(PetItemType mode) {
        String fallback = ModernSlimefunPet.PET_GUI_CONFIG.getString("option.title");
        if (mode == null) return fallback;

        fallback = ModernSlimefunPet.PETS_CONFIG.getString(modePath(mode) + ".title", fallback);
        return ModernSlimefunPet.getPetGuiConfig(mode).getString("option.title", fallback);
    }

    private void setAllItems() {
        // What the pets are labelled with depends on the item they would be
        // used with, which the player can change while the menu is open
        this.activeItem = this.currentItem();

        FileConfiguration config = this.layout();
        List<Integer> slots = config.getIntegerList("pet-slots");

        List<PetPlayer> freshPetPlayers = this.petManager.getPetPlayers(this.target);
        PlayerProfile user = ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.target);

        int desiredPage = this.pagination != null ? Math.max(1, this.pagination.currentPage) : 1;

        this.pagination = new PetPagination(freshPetPlayers, slots);
        this.pagination.currentPage = Math.min(desiredPage, this.pagination.totalPage);

        this.clearItems();

        List<PetPlayer> items = this.pagination.getItems(this.pagination.currentPage);
        if (items == null) {
            items = Collections.emptyList();
        }

        for (int i = 0; i < items.size() && i < slots.size(); i++) {
            PetPlayer petPlayer = items.get(i);
            Integer slot = slots.get(i);

            Pet pet = this.petManager.getPet(petPlayer.getId());
            if (pet == null) {
                continue;
            }

            ItemStack stack = pet.getItem(petPlayer);

            // In an item mode the equip/delete hints are wrong, replace them
            // with what clicking this pet will actually do
            if (this.mode != null) {
                this.applyModeHint(stack, petPlayer);
            }

            if (user.isPetEquipped(petPlayer.getUniqueId())) {
                renameEquipped(stack);
            }

            if (petSlots.containsKey(slot)) {
                renameSelected(stack);
            }

            this.setItem(slot, stack, event -> {
                // Deleting pets in bulk
                if (this.removeToggle) {
                    if (petSlots.containsKey(slot)) {
                        petSlots.remove(slot);
                    } else {
                        if (user.isPetEquipped(petPlayer.getUniqueId())) {
                            Utils.fail(this.viewer);
                            return;
                        }
                        petSlots.put(slot, petPlayer.getUniqueId());
                    }

                    Utils.click(this.viewer);
                    setAllItems();
                    return;
                }

                // The menu was opened with a food, candy, evolution, reroll or
                // revive item, so the click uses it on this pet
                if (this.mode != null) {
                    this.usePetItem(petPlayer);
                    return;
                }

                // Everything else a pet can do, skins included, is in its
                // options menu; a right click stays the quick way to put it away
                if (event.getClick() == ClickType.LEFT) {
                    Utils.click(this.viewer);
                    new PetOptionsInventory(this.viewer, this, petPlayer).open(this.viewer);
                }

                if (event.getClick() == ClickType.RIGHT) {
                    if (!user.isPetEquipped(petPlayer.getUniqueId())) {
                        return;
                    }
                    this.unequip(user, petPlayer);
                }
            });
        }

        setAdditionalItems();
    }

    private void setAdditionalItems() {
        FileConfiguration config = this.layout();
        if (!config.isConfigurationSection("items")) {
            return;
        }

        PlayerProfile user = ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.target);

        for (String key : config.getConfigurationSection("items").getKeys(false)) {
            String type = config.getString("items." + key + ".type");
            List<Integer> itemSlotsList = getIntListOrSingle(config, "items." + key + ".slot");

            ItemStack stack = ItemBuilder.fromConfig(config, "items." + key)
                    .placeholder(new Placeholder()
                            .add("{remove_toggle}", this.removeToggle ? "&a&lON" : "&c&lOFF")
                            .add("{amount_remove}", String.valueOf(petSlots.size()))
                            .add("{toggle_pet_status}", user.isToggleHidePet() ? "&a✔ Hidden" : "&c✘ Unhidden")
                            .add("{equipped}", String.valueOf(user.getEquippedPets().size()))
                            .add("{max_equipped}", String.valueOf(PetSettings.getMaxEquipped(this.target))))
                    .build();

            this.setItems(itemSlotsList, stack, event -> {
                this.viewer.playSound(this.viewer.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                if (type == null || type.equalsIgnoreCase("DUMMY")) {
                    return;
                }

                if (type.equalsIgnoreCase("NEXT_PAGE")) {
                    if (this.pagination == null || !this.pagination.nextPage()) {
                        return;
                    }
                    this.pagination.currentPage++;
                    this.setAllItems();
                }

                if (type.equalsIgnoreCase("REMOVE_PET")) {
                    if (!this.removeToggle) {
                        this.removeToggle = true;
                        this.petSlots.clear();
                        Utils.click(this.viewer);
                        this.setAllItems();
                    } else {
                        this.removeToggle = false;
                        for (UUID uuid : new ArrayList<>(petSlots.values())) {
                            // the food it kept for Auto-Feed goes back to the owner, not away with it
                            PetPlayer removed = PetManager.getPetPlayer(uuid);
                            if (removed != null) {
                                for (int slot = 0; slot < AutoFeed.MAX_SLOTS; slot++) {
                                    PetAutoFeedInventory.giveBack(this.target, removed.getAutoFeed(), slot);
                                }
                            }
                            this.petManager.removePetPlayer(uuid);
                        }
                        petSlots.clear();
                        this.setAllItems();
                    }
                }

                if (type.equalsIgnoreCase("TOGGLE_PET")) {
                    user.setToggleHidePet(!user.isToggleHidePet());
                    this.petTask.setHidden(this.target, user.isToggleHidePet(), false);

                    DatabaseQueueHandler.addObjectToSave(user);
                    Utils.success(this.viewer);
                    this.setAllItems();
                }

                if (type.equalsIgnoreCase("UNEQUIP")) {
                    if (user.getEquippedPets().isEmpty()) {
                        Utils.fail(this.viewer);
                        return;
                    }

                    // Put every pet away at once
                    user.clearEquippedPets();
                    this.petTask.refreshFor(this.target);
                    DatabaseQueueHandler.addObjectToSave(user);
                    this.setAllItems();
                }

                if (type.equalsIgnoreCase("PREVIOUS_PAGE")) {
                    if (this.pagination == null || !this.pagination.previousPage()) {
                        return;
                    }
                    this.pagination.currentPage--;
                    this.setAllItems();
                }
            });
        }
    }

    /**
     * Take a pet out. Capped by the config and the slot permission, and a dead
     * pet has to be revived first. Also used by the pet's options menu.
     *
     * @return whether the pet went out
     */
    boolean equip(PetPlayer petPlayer) {
        PlayerProfile user = ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.target);
        Pet pet = this.petManager.getPet(petPlayer.getId());
        if (pet == null || user.isPetEquipped(petPlayer.getUniqueId())) {
            Utils.fail(this.viewer);
            return false;
        }

        // A dead pet has to be revived before it can be taken out again
        if (petPlayer.isDead()) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-dead");
            return false;
        }

        int max = PetSettings.getMaxEquipped(this.target);
        if (user.getEquippedPets().size() >= max) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-slots-full", new Placeholder()
                    .add("{max}", String.valueOf(max)));
            return false;
        }

        user.equipPet(petPlayer.getUniqueId());
        this.petTask.refreshFor(this.target);
        DatabaseQueueHandler.addObjectToSave(user);

        Utils.success(this.viewer);
        ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.equip-pet", new Placeholder()
                .add("{pet}", pet.getItemDisplayName())
                .add("{level}", petPlayer.getLevel()));

        setAllItems();
        return true;
    }

    /**
     * Put a pet away. Also used by the pet's options menu.
     */
    boolean unequip(PetPlayer petPlayer) {
        PlayerProfile user = ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.target);
        if (!user.isPetEquipped(petPlayer.getUniqueId())) {
            Utils.fail(this.viewer);
            return false;
        }
        this.unequip(user, petPlayer);
        return true;
    }

    private void unequip(PlayerProfile user, PetPlayer petPlayer) {
        user.unequipPet(petPlayer.getUniqueId());
        this.petTask.refreshFor(this.target);
        DatabaseQueueHandler.addObjectToSave(user);

        Utils.success(this.viewer);
        setAllItems();
    }

    /**
     * Show this menu again, freshly drawn, when a menu opened from it hands
     * the player back.
     */
    void reopen() {
        this.setAllItems();
        this.open(this.viewer);
    }

    Player target() {
        return this.target;
    }

    PetManager petManager() {
        return this.petManager;
    }

    PetTask petTask() {
        return this.petTask;
    }

    /**
     * Use the item this menu is about on a pet, taking one off the player only
     * when it actually did something.
     */
    private void usePetItem(PetPlayer petPlayer) {
        // The item has to be on them at the moment of the click. Nothing is ever
        // done for free: a menu opened by command works off whatever matching
        // item the player owns, exactly like one opened by right clicking, so
        // there is no way to spend an item that is not there
        PetItem item = this.currentItem();
        if (item == null) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-item-gone");
            this.viewer.closeInventory();
            return;
        }

        PetService service = ModernSlimefunPet.getInstance().getPetService();
        boolean consume;

        switch (this.mode) {
            case FOOD -> consume = service.feed(this.viewer, petPlayer, item);
            case CANDY -> consume = service.useCandy(this.viewer, petPlayer, item);
            case REROLL -> {
                Executor.syncLater(1L, () -> {
                    if (this.viewer.getOpenInventory().getTopInventory() != this.getInventory()) return;
                    new PetRerollInventory(this.viewer, petPlayer, item).open(this.viewer);
                });
                return;
            }
            case REVIVE -> consume = service.revive(this.viewer, petPlayer, item);
            case EVOLUTION -> {
                // The evolution has a menu of its own, where boosters can be
                // dropped in before anything is spent. The stone is taken there
                new PetEvolutionInventory(this.viewer, petPlayer, item).open(this.viewer);
                return;
            }
            default -> consume = false;
        }

        boolean spent = consume && this.takeItem(item);

        if (consume) {
            Utils.success(this.viewer);
        } else {
            Utils.fail(this.viewer);
        }

        // The last one was just spent, there is nothing left to click a pet with
        if (spent && this.currentItem() == null) {
            this.viewer.closeInventory();
            return;
        }

        // The pet may have evolved or died, rebuild the menu either way
        setAllItems();
    }

    /**
     * The item a click would use right now, or null when the player has none.
     * <p>
     * A menu opened by right clicking is tied to that exact item, so only the
     * very same one counts. A menu opened by command was not opened with
     * anything, so any item of the right kind will do.
     */
    @Nullable
    private PetItem currentItem() {
        int slot = this.findItem();
        return slot < 0 ? null : PetItemRegistry.fromItemStack(this.viewer.getInventory().getItem(slot));
    }

    /**
     * Where the item this menu is about is sitting right now, or -1 when the
     * player has none.
     * <p>
     * The hand is looked at first because that is where it almost always is,
     * but it is only matched by what it is, so moving the stack around while
     * the menu is open never takes the wrong item.
     */
    private int findItem() {
        if (this.mode == null) return -1;

        var inventory = this.viewer.getInventory();
        int held = inventory.getHeldItemSlot();
        if (this.matches(inventory.getItem(held))) return held;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (this.matches(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    /**
     * Whether a stack is an item this menu can use.
     */
    private boolean matches(ItemStack stack) {
        PetItem item = PetItemRegistry.fromItemStack(stack);
        if (item == null || item.type() != this.mode) return false;
        // Opened by right clicking, so it is that one item and nothing else
        return this.petItem == null || item.id().equals(this.petItem.id());
    }

    /**
     * Take a single pet item off the player.
     *
     * @return true when one was actually taken
     */
    private boolean takeItem(PetItem item) {
        var inventory = this.viewer.getInventory();

        int held = inventory.getHeldItemSlot();
        int slot = isSame(inventory.getItem(held), item) ? held : -1;
        if (slot < 0) {
            for (int i = 0; i < inventory.getSize(); i++) {
                if (isSame(inventory.getItem(i), item)) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0) return false;

        ItemStack stack = inventory.getItem(slot);
        stack.setAmount(stack.getAmount() - 1);
        return true;
    }

    /**
     * Whether a stack is exactly the pet item that was just used, so the one
     * taken is never a different one the player happens to also own.
     */
    private static boolean isSame(ItemStack stack, PetItem item) {
        PetItem found = PetItemRegistry.fromItemStack(stack);
        return found != null && found.type() == item.type() && found.id().equals(item.id());
    }

    /**
     * Swap the tail of the lore for the instruction of the current mode, and
     * mark the name so the action is readable without hovering.
     */
    private void applyModeHint(ItemStack stack, PetPlayer petPlayer) {
        if (stack == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        String path = modePath(this.mode);
        boolean evolutionMode = this.mode == PetItemType.EVOLUTION;
        Placeholder placeholder = evolutionMode ? this.evolutionPlaceholder(petPlayer) : this.modePlaceholder();

        // Drop the "Left-Click to equip / Right-Click to delete" hints, they do
        // not apply while an item is being used
        List<String> lore = new ArrayList<>();
        List<net.kyori.adventure.text.Component> currentLore = meta.lore();
        if (currentLore != null) {
            for (net.kyori.adventure.text.Component component : currentLore) {
                String line = ColorUtil.fromComponent(component);
                if (line.contains("Left-Click") || line.contains("Right-Click")) continue;
                lore.add(line);
            }
        }

        // A dead pet can only be clicked in the revive mode, say so instead of
        // promising an action that will just fail. An item that names the pets
        // it works on is the same story on every other pet
        boolean usable = this.mode == PetItemType.REVIVE ? petPlayer.isDead() : !petPlayer.isDead();
        if (usable && this.activeItem != null) {
            usable = this.activeItem.allows(this.petManager.getPet(petPlayer.getId()));
        }
        String lorePath = usable ? path + ".pet-lore" : path + ".pet-lore-unusable";

        List<String> hint = ModernSlimefunPet.PETS_CONFIG.getStringList(lorePath);
        if (!hint.isEmpty()) {
            // '{requirements}' stands for a whole block of lines, one per thing
            // the evolution asks for, so it has to be expanded before the rest
            // of the placeholders are filled in
            List<String> expanded = new ArrayList<>();
            for (String line : hint) {
                if (evolutionMode && line.contains("{requirements}")) {
                    expanded.addAll(this.evolutionRequirementLines(petPlayer));
                    continue;
                }
                expanded.add(line);
            }
            lore.addAll(placeholder.translate(expanded));
        }
        meta.lore(lore.stream().map(ColorUtil::toComponent).toList());

        String suffix = ModernSlimefunPet.PETS_CONFIG.getString(usable ? path + ".pet-suffix" : path + ".pet-suffix-unusable", "");
        if (!suffix.isBlank()) {
            meta.displayName(ColorUtil.toComponent(ColorUtil.fromComponent(meta.displayName()) + suffix));
        }
        stack.setItemMeta(meta);
    }

    private void renameSelected(ItemStack stack) {
        if (stack == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(ColorUtil.toComponent(ColorUtil.fromComponent(meta.displayName()) + " &8(&c&lSELECTED&8)"));
        stack.setItemMeta(meta);
    }

    private void renameEquipped(ItemStack stack) {
        if (stack == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(ColorUtil.toComponent(ColorUtil.fromComponent(meta.displayName()) + " &8(&a&lEQUIPPED&8)"));
        stack.setItemMeta(meta);
    }

    public static List<Integer> getIntListOrSingle(FileConfiguration config, String path) {
        Object raw = config.get(path);
        switch (raw) {
            case null -> {
                return List.of();
            }
            case Number n -> {
                return List.of(n.intValue());
            }
            case String s -> {
                return parseToken(s);
            }
            case List<?> list -> {
                LinkedHashSet<Integer> out = new LinkedHashSet<>();
                for (Object obj : list) {
                    if (obj instanceof Number n) {
                        out.add(n.intValue());
                    } else if (obj instanceof String s) {
                        out.addAll(parseToken(s));
                    }
                }
                return new ArrayList<>(out);
            }
            default -> {
            }
        }

        return List.of();
    }

    private static List<Integer> parseToken(String token) {
        String t = token.trim();
        if (t.matches("^-?\\d+\\s*-\\s*-?\\d+$")) {
            String[] parts = t.split("\\s*-\\s*");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            List<Integer> res = new ArrayList<>(end - start + 1);
            for (int i = start; i <= end; i++) {
                res.add(i);
            }
            return res;
        }

        if (t.matches("^-?\\d+$")) {
            return List.of(Integer.parseInt(t));
        }

        return List.of();
    }

}
