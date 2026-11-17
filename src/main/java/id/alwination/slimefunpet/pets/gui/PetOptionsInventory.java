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
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.packet.display.PetModels;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.utils.Utils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * What can be done with one pet, opened by clicking it in {@link PetInventory}:
 * dress it in a skin, take it out, put it away.
 * <p>
 * One big button per action, each named on the button itself, so nothing has
 * to be hovered to be read. That is what keeps it usable on Bedrock.
 */
// Identity on purpose: "is this exact inventory/menu open", the click safety of the menu rests on it.
@SuppressWarnings({"PMD.CompareObjectsWithEquals"})
public final class PetOptionsInventory extends FastInv {

    private static final int PET_SLOT = 4;
    private static final int SKIN_SLOT = 10;
    private static final int EQUIP_SLOT = 12;
    private static final int UNEQUIP_SLOT = 14;
    private static final int AUTO_FEED_SLOT = 16;
    private static final int STAY_SLOT = 20;
    private static final int BACK_SLOT = 22;

    private final Player viewer;
    private final PetInventory parent;
    private final PetPlayer petPlayer;

    PetOptionsInventory(Player viewer, PetInventory parent, PetPlayer petPlayer) {
        super(27, Common.color(ModernSlimefunPet.PET_OPTIONS_GUI_CONFIG.getString("title", "&8Pet Options")));
        this.viewer = viewer;
        this.parent = parent;
        this.petPlayer = petPlayer;
        this.draw();
    }

    PetPlayer petPlayer() {
        return this.petPlayer;
    }

    private void draw() {
        FileConfiguration config = ModernSlimefunPet.PET_OPTIONS_GUI_CONFIG.getConfig();
        Pet pet = this.parent.petManager().getPet(this.petPlayer.getId());
        PlayerProfile user = ModernSlimefunPet.getInstance().getProfileManager().getProfile(this.parent.target());
        boolean equipped = user.isPetEquipped(this.petPlayer.getUniqueId());

        this.clearItems();
        for (int slot = 0; slot < this.getInventory().getSize(); slot++) {
            this.setItem(slot, ItemBuilder.fromConfig(config, "filler").build());
        }

        String skin = this.petPlayer.getSkin();
        Placeholder placeholder = new Placeholder()
                .add("{pet}", pet == null ? "" : pet.getItemDisplayName())
                .add("{level}", String.valueOf(this.petPlayer.getLevel()))
                .add("{skin}", skin == null ? config.getString("skin-none", "&7None") : PetModels.lookOf(skin).displayName());

        if (pet != null) {
            this.setItem(PET_SLOT, pet.getItem(this.petPlayer));
        }

        // A pet with a model of its own is never dressed in another one
        if (pet != null && pet.getModel() != null) {
            this.setItem(SKIN_SLOT, ItemBuilder.fromConfig(config, "skin-blocked").placeholder(placeholder).build(), event -> {
                Utils.fail(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-skin-not-supported");
            });
        } else {
            this.setItem(SKIN_SLOT, ItemBuilder.fromConfig(config, "skin").placeholder(placeholder).build(), event -> {
                Utils.click(this.viewer);
                new PetSkinInventory(this.viewer, this, 1).open(this.viewer);
            });
        }

        this.setItem(EQUIP_SLOT, ItemBuilder.fromConfig(config, equipped ? "equipped" : "equip").placeholder(placeholder).build(), event -> {
            if (this.parent.equip(this.petPlayer)) this.redraw();
        });
        this.setItem(UNEQUIP_SLOT, ItemBuilder.fromConfig(config, "unequip").placeholder(placeholder).build(), event -> {
            if (this.parent.unequip(this.petPlayer)) this.redraw();
        });

        // A pet_options_gui.yml from before Auto-Feed has no button for it, and
        // the button stays away until the server adds one
        AutoFeed feed = this.petPlayer.getAutoFeed();
        boolean feedButton = config.isConfigurationSection("auto-feed") && config.isConfigurationSection("auto-feed-locked");
        if (feedButton && feed.isUnlocked()) {
            Placeholder feedPlaceholder = new Placeholder()
                    .add("{status}", config.getString(feed.isEnabled() ? "auto-feed-on" : "auto-feed-off", ""))
                    .add("{threshold}", Common.digits(PetSettings.getAutoFeedThreshold(feed.getTier())));
            this.setItem(AUTO_FEED_SLOT, ItemBuilder.fromConfig(config, "auto-feed").placeholder(feedPlaceholder).build(), event -> {
                Utils.click(this.viewer);
                new PetAutoFeedInventory(this.viewer, this, this.petPlayer).open(this.viewer);
            });
        } else if (feedButton) {
            this.setItem(AUTO_FEED_SLOT, ItemBuilder.fromConfig(config, "auto-feed-locked").placeholder(placeholder).build(), event -> {
                // Someone else unlocked it while this button was showing, or the
                // pet was removed: nothing to use an unlocker on
                if (feed.isUnlocked() || PetManager.getPetPlayer(this.petPlayer.getUniqueId()) != this.petPlayer) { // NOPMD - the same pet, not an equal one
                    this.redraw();
                    return;
                }
                if (!PetAutoFeedInventory.takeOne(this.viewer, PetItemType.AUTO_FEED_UNLOCKER, pet)) {
                    Utils.fail(this.viewer);
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-needs-unlocker");
                    return;
                }
                feed.unlock();
                DatabaseQueueHandler.addObjectToSave(this.petPlayer);
                DatabaseQueueHandler.saveNow();
                Utils.success(this.viewer);
                ModernSlimefunPet.PETS_CONFIG.sendMessage(this.viewer, "messages.pet-auto-feed-unlocked");
                new PetAutoFeedInventory(this.viewer, this, this.petPlayer).open(this.viewer);
            });
        }
        // Stay only means something for a pet that is out; a config without the button keeps it away
        if (equipped && config.isConfigurationSection("stay") && config.isConfigurationSection("follow")) {
            boolean staying = this.petPlayer.isStay();
            this.setItem(STAY_SLOT, ItemBuilder.fromConfig(config, staying ? "follow" : "stay").placeholder(placeholder).build(), event -> {
                Player owner = Bukkit.getPlayer(this.parent.target().getUniqueId());
                if (owner == null || PetManager.getPetPlayer(this.petPlayer.getUniqueId()) != this.petPlayer) { // NOPMD - the same pet, not an equal one
                    Utils.fail(this.viewer);
                    return;
                }
                this.parent.petTask().setStay(owner, this.petPlayer, !this.petPlayer.isStay());
                Utils.click(this.viewer);
                this.redraw();
            });
        }
        this.setItem(BACK_SLOT, ItemBuilder.fromConfig(config, "back").placeholder(placeholder).build(), event -> {
            Utils.click(this.viewer);
            this.parent.reopen();
        });
    }

    /**
     * Show this menu again, freshly drawn, when the skin menu hands the player
     * back.
     */
    void reopen() {
        this.draw();
        this.open(this.viewer);
    }

    PetInventory parent() {
        return this.parent;
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
