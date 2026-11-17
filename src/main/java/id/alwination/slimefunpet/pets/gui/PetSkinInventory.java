package id.alwination.slimefunpet.pets.gui;

import com.muhammaddaffa.mdlib.fastinv.FastInv;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.packet.display.PetModels;
import id.alwination.slimefunpet.packet.display.PetSkins;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The models a player may dress a pet in, opened from
 * {@link PetOptionsInventory}. One button per skin, named after the skin, the
 * one worn marked in its name, so nothing has to be hovered to be read.
 * <p>
 * Only the models the player has the permission for are listed, and the
 * permission is checked again on the click: the list can be older than a
 * permission change.
 * <p>
 * The title carries the page, which an open inventory cannot change, so
 * turning a page opens a fresh menu on that page.
 */
// Identity on purpose: "is this exact inventory/menu open", the click safety of the menu rests on it.
@SuppressWarnings({"PMD.CompareObjectsWithEquals"})
public final class PetSkinInventory extends FastInv {

    private final Player viewer;
    private final PetOptionsInventory parent;
    private final PetPlayer petPlayer;
    private final int page;

    PetSkinInventory(Player viewer, PetOptionsInventory parent, int page) {
        super(ModernSlimefunPet.PET_SKIN_GUI_CONFIG.getConfig().getInt("size", 54),
                Common.color(new Placeholder()
                        .add("{page}", String.valueOf(page))
                        .add("{pages}", String.valueOf(pages(viewer)))
                        .translate(ModernSlimefunPet.PET_SKIN_GUI_CONFIG.getString("title", "&8Pet Skins"))));
        this.viewer = viewer;
        this.parent = parent;
        this.petPlayer = parent.petPlayer();
        this.page = page;
        this.draw();
    }

    private static List<String> available(Player viewer) {
        return PetSkins.available(PetModels.modelNames(), viewer::hasPermission);
    }

    private static int pages(Player viewer) {
        int perPage = Math.max(1, ModernSlimefunPet.PET_SKIN_GUI_CONFIG.getConfig().getIntegerList("skin-slots").size());
        return Math.max(1, (available(viewer).size() + perPage - 1) / perPage);
    }

    private void draw() {
        FileConfiguration config = ModernSlimefunPet.PET_SKIN_GUI_CONFIG.getConfig();
        Pet pet = this.parent.parent().petManager().getPet(this.petPlayer.getId());
        List<Integer> slots = config.getIntegerList("skin-slots");
        List<String> skins = available(this.viewer);
        String worn = this.petPlayer.getSkin();

        String petName = pet == null ? "" : pet.getItemDisplayName();
        Placeholder placeholder = new Placeholder()
                .add("{pet}", petName)
                .add("{page}", String.valueOf(this.page))
                .add("{pages}", String.valueOf(pages(this.viewer)))
                .add("{worn}", worn == null ? "" : PetModels.lookOf(worn).displayName());

        this.clearItems();
        for (int slot : PetInventory.getIntListOrSingle(config, "filler.slot")) {
            this.setItem(slot, ItemBuilder.fromConfig(config, "filler").build());
        }

        int from = (this.page - 1) * slots.size();
        for (int i = 0; i < slots.size() && from + i < skins.size(); i++) {
            String skin = skins.get(from + i);
            PetSkins.Look look = PetModels.lookOf(skin);
            Placeholder named = new Placeholder()
                    .add("{pet}", petName)
                    .add("{skin}", look.displayName());
            this.setItem(slots.get(i), skinIcon(config, skin.equals(worn) ? "skin-worn" : "skin", look, named),
                    event -> this.wear(skin, look));
        }
        if (skins.isEmpty()) {
            this.button(config, "no-skins", placeholder, null);
        }

        // Always drawn, so the bottom row reads the same on every page; the
        // '-locked' look stands in when the button has nothing to do
        if (worn != null) {
            this.button(config, "remove", placeholder, () -> this.wear(null, null));
        } else {
            this.button(config, "remove-locked", placeholder, () -> Utils.fail(this.viewer));
        }
        if (this.page > 1) {
            this.button(config, "previous-page", placeholder, () -> this.turn(this.page - 1));
        } else {
            this.button(config, "previous-page-locked", placeholder, () -> Utils.fail(this.viewer));
        }
        if (from + slots.size() < skins.size()) {
            this.button(config, "next-page", placeholder, () -> this.turn(this.page + 1));
        } else {
            this.button(config, "next-page-locked", placeholder, () -> Utils.fail(this.viewer));
        }
        this.button(config, "back", placeholder, () -> {
            Utils.click(this.viewer);
            this.parent.reopen();
        });
    }

    /**
     * Put one configured button on every slot it lists.
     *
     * @param action run on a click, null for a button that only informs
     */
    private void button(FileConfiguration config, String path, Placeholder placeholder, @Nullable Runnable action) {
        for (int slot : PetInventory.getIntListOrSingle(config, path + ".slot")) {
            if (action == null) {
                this.setItem(slot, ItemBuilder.fromConfig(config, path).placeholder(placeholder).build());
            } else {
                this.setItem(slot, ItemBuilder.fromConfig(config, path).placeholder(placeholder).build(), event -> action.run());
            }
        }
    }

    /**
     * A skin's button, in the icon set for that skin in pets.yml.
     * <p>
     * The material has to be in place before MDLib builds the item. Changing
     * it afterwards (ItemBuilder#type) swaps the item under the meta the
     * builder holds, and the name and lore are lost when that meta is applied.
     */
    private static ItemStack skinIcon(FileConfiguration config, String path, PetSkins.Look look, Placeholder placeholder) {
        MemoryConfiguration entry = new MemoryConfiguration();
        ConfigurationSection template = config.getConfigurationSection(path);
        if (template != null) {
            for (String key : template.getKeys(true)) {
                if (!template.isConfigurationSection(key)) entry.set(key, template.get(key));
            }
        }
        entry.set("material", look.material().name());
        return ItemBuilder.fromConfig(entry, placeholder).build();
    }

    /**
     * Dress the pet, or give it its own look back with a null skin. The pet
     * out in the world is rebuilt right away, so the change shows at once.
     */
    private void wear(String skin, PetSkins.Look look) {
        Pet pet = this.parent.parent().petManager().getPet(this.petPlayer.getId());
        String petName = pet == null ? "" : pet.getItemDisplayName();

        if (skin != null && !this.viewer.hasPermission(PetSkins.permission(skin))) {
            Utils.fail(this.viewer);
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-skin-no-permission", new Placeholder()
                    .add("{skin}", look.displayName()));
            this.redraw();
            return;
        }

        this.petPlayer.setSkin(skin);
        DatabaseQueueHandler.addObjectToSave(this.petPlayer);
        this.parent.parent().petTask().refreshFor(this.parent.parent().target());

        Utils.success(this.viewer);
        if (skin == null) {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-skin-removed", new Placeholder()
                    .add("{pet}", petName));
        } else {
            ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-skin-set", new Placeholder()
                    .add("{pet}", petName)
                    .add("{skin}", look.displayName()));
        }
        this.redraw();
    }

    private void turn(int page) {
        Utils.click(this.viewer);
        new PetSkinInventory(this.viewer, this.parent, page).open(this.viewer);
    }

    private void redraw() {
        this.draw();
        Executor.syncLater(1L, this.viewer::updateInventory);
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        // See PetOptionsInventory: nothing here is ever the player's own item
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
