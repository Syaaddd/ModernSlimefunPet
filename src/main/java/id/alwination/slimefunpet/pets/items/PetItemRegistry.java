package id.alwination.slimefunpet.pets.items;

import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.ModernSlimefunPet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every custom pet item from pets.yml and turns them into tagged
 * item stacks that can be recognised again when a player right clicks.
 */
public final class PetItemRegistry {

    /**
     * Keyed by '<type>:<id>' so two types can share an id.
     */
    private static final Map<String, PetItem> ITEMS = new LinkedHashMap<>();

    private PetItemRegistry() {
    }

    public static void load() {
        ITEMS.clear();

        FileConfiguration config = ModernSlimefunPet.PETS_CONFIG.getConfig();
        for (PetItemType type : PetItemType.values()) {
            String section = type.getConfigSection();
            if (!config.isConfigurationSection(section)) continue;

            for (String id : config.getConfigurationSection(section).getKeys(false)) {
                String path = section + "." + id;
                ITEMS.put(key(type, id), new PetItem(
                        type,
                        id,
                        config.getDouble(path + ".food", 0),
                        config.getInt(path + ".exp", 0),
                        config.getInt(path + ".levels", 0),
                        config.getInt(path + ".stats", 0),
                        config.getDouble(path + ".money-cost", 0),
                        config.getDouble(path + ".chance-bonus", 0),
                        config.getStringList(path + ".commands"),
                        config.getStringList(path + ".whitelist-pets"),
                        loadOutcomes(id, config, path + ".outcomes")
                ));
            }
        }

        Logger.info("Successfully loaded " + ITEMS.size() + " pet items!");
    }

    /**
     * The outcomes a reroll item can land on, written as a section with one
     * entry per outcome:
     * <pre>
     *   outcomes:
     *     reroll:
     *       kind: REROLL
     *       weight: 70
     *     burn:
     *       kind: REMOVE
     *       weight: 30
     *       amount: 1
     * </pre>
     * An item without the section always does what its 'stats' says.
     */
    private static List<RerollOutcome> loadOutcomes(String itemId, FileConfiguration config, String path) {
        List<RerollOutcome> outcomes = new ArrayList<>();
        if (!config.isConfigurationSection(path)) return outcomes;

        for (String key : config.getConfigurationSection(path).getKeys(false)) {
            String entry = path + "." + key;

            RerollOutcome.Kind kind = RerollOutcome.Kind.fromString(config.getString(entry + ".kind"));
            if (kind == null) {
                Logger.warning("Pet item '" + itemId + "' has the outcome '" + key
                        + "' with an unknown kind, skipping it!");
                continue;
            }

            double weight = config.getDouble(entry + ".weight", 1);
            if (weight <= 0) {
                Logger.warning("Pet item '" + itemId + "' has the outcome '" + key
                        + "' with a weight of 0 or less, it will never be picked!");
                continue;
            }

            outcomes.add(new RerollOutcome(kind, weight, config.getInt(entry + ".amount", 1)));
        }
        return outcomes;
    }

    @Nullable
    public static PetItem get(PetItemType type, String id) {
        return ITEMS.get(key(type, id));
    }

    public static List<PetItem> getAll() {
        return new ArrayList<>(ITEMS.values());
    }

    public static List<String> getIds(PetItemType type) {
        List<String> ids = new ArrayList<>();
        for (PetItem item : ITEMS.values()) {
            if (item.type() == type) {
                ids.add(item.id());
            }
        }
        return ids;
    }

    /**
     * Build the item stack of a pet item, the looks come straight from its
     * config section so servers can change material, name, lore and model.
     */
    @Nullable
    public static ItemStack getItemStack(PetItemType type, String id, int amount) {
        PetItem item = get(type, id);
        if (item == null) return null;

        ConfigurationSection section = ModernSlimefunPet.PETS_CONFIG.getConfig()
                .getConfigurationSection(type.getConfigSection() + "." + id);
        if (section == null) return null;

        ItemStack stack = ItemBuilder.fromConfig(section)
                .amount(Math.max(1, amount))
                .pdc(ModernSlimefunPet.pet_item_type, type.name())
                .pdc(ModernSlimefunPet.pet_item_id, id)
                .build();
        // 'glint: true' shines without a real enchantment (the Auto Disenchanter would take one off)
        if (section.getBoolean("glint")) {
            ItemMeta meta = stack.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * The first item of this kind the player owns, the hand looked at first,
     * or null when they have none.
     * <p>
     * A pet menu is never free: it always works off a real item, whether it was
     * opened by right clicking one or by a command.
     */
    @Nullable
    public static PetItem findOwned(Player player, PetItemType type) {
        if (player == null || type == null) return null;

        PlayerInventory inventory = player.getInventory();
        PetItem held = fromItemStack(inventory.getItemInMainHand());
        if (held != null && held.type() == type) return held;

        for (ItemStack stack : inventory.getContents()) {
            PetItem item = fromItemStack(stack);
            if (item != null && item.type() == type) return item;
        }
        return null;
    }

    /**
     * Read the pet item a stack represents, or null when it is not one.
     */
    @Nullable
    /**
     * Whether a stack is any item this plugin issued, registered or not (an item whose section was
     * removed from pets.yml still counts). Reads the PDC only, so it is safe in async Slimefun events.
     */
    public static boolean isPetItem(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getPersistentDataContainer().has(ModernSlimefunPet.pet_item_type, PersistentDataType.STRING);
    }

    public static PetItem fromItemStack(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;

        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String rawType = pdc.get(ModernSlimefunPet.pet_item_type, PersistentDataType.STRING);
        String id = pdc.get(ModernSlimefunPet.pet_item_id, PersistentDataType.STRING);
        if (rawType == null || id == null) return null;

        PetItemType type = PetItemType.fromString(rawType);
        if (type == null) return null;

        PetItem item = get(type, id);
        // An item handed out before its section moved still carries the old
        // type in its tag, so fall back to the id, which is what names it
        return item != null ? item : byId(id);
    }

    /**
     * The one item with this id, or null when no type or more than one has it,
     * which leaves no honest way to tell which was meant.
     */
    @Nullable
    private static PetItem byId(String id) {
        PetItem found = null;
        for (PetItem item : ITEMS.values()) {
            if (!item.id().equals(id)) continue;
            if (found != null) return null;
            found = item;
        }
        return found;
    }

    private static String key(PetItemType type, String id) {
        return type.name() + ":" + id;
    }

}
