package id.alwination.slimefunpet.pets.tasks;

import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.ItemBuilder;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.packet.display.PetDisplay;
import id.alwination.slimefunpet.packet.display.PetModels;
import id.alwination.slimefunpet.packet.display.PetSkins;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.utils.ColorUtil;
import id.alwination.slimefunpet.utils.Utils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Draws every equipped pet with the packet display and keeps it next to its owner.
 * <p>
 * Everything here runs on the main thread: the timer, {@link #refreshFor} and the hide toggle. With
 * one pet per player the packets are cheap, and reading player positions on the main thread needs
 * none of the race handling the async ModernMineGens ticker had.
 */
public class PetTask {

    private final Map<UUID, List<PetDisplay>> activeSessions = new HashMap<>();
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    /** Where each pet on Stay waits, by pet UUID. Memory only: Stay ends when the owner quits. */
    private final Map<UUID, Location> stayLocations = new HashMap<>();
    private @Nullable HandleTask bukkitTask;

    public void start() {
        if (this.bukkitTask != null) {
            this.bukkitTask.cancel();
        }
        this.bukkitTask = Executor.syncTimer(0L, PetSettings.getUpdateIntervalTicks(), this::tickAllSessions);
    }

    public void stop() {
        if (this.bukkitTask != null) {
            this.bukkitTask.cancel();
            this.bukkitTask = null;
        }
        for (List<PetDisplay> sessions : this.activeSessions.values()) {
            for (PetDisplay session : sessions) {
                session.destroy();
            }
        }
        this.activeSessions.clear();
        this.hiddenPlayers.clear();
    }

    /** Rebuilds the owner's pet displays from what they have equipped right now. */
    public void refreshFor(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        this.hideFor(owner);
        // Stats follow what is equipped right now, whether the pet is drawn or hidden
        ModernSlimefunPet.getInstance().getPetEffectTask().refresh(owner);
        if (this.isHidden(owner)) {
            return;
        }
        PlayerProfile profile = ModernSlimefunPet.getInstance().getProfileManager().getProfile(owner);
        PetManager petManager = ModernSlimefunPet.getInstance().getPetManager();
        this.enforceSlotLimit(owner, profile);

        // Only the pets that still exist and are alive get a display
        List<PetPlayer> toShow = new ArrayList<>();
        for (UUID uuid : profile.getEquippedPets()) {
            PetPlayer petPlayer = PetManager.getPetPlayer(uuid);
            if (petPlayer == null || petPlayer.isDead() || petManager.getPet(petPlayer.getId()) == null) {
                continue;
            }
            toShow.add(petPlayer);
        }
        if (toShow.isEmpty()) {
            return;
        }
        PetDisplay.Settings settings = new PetDisplay.Settings(PetSettings.getUpdateIntervalTicks(),
                PetSettings.getOrbitRadius(), PetSettings.isVisibleToOthers(), PetSettings.getViewDistance(),
                this::isHidden, ColorUtil::toComponent);
        List<PetDisplay> sessions = new ArrayList<>();
        for (int i = 0; i < toShow.size(); i++) {
            PetPlayer petPlayer = toShow.get(i);
            Pet pet = petManager.getPet(petPlayer.getId());
            if (pet == null) {
                continue;
            }
            PetModels.Settings model = pet.getModel();
            // A skin is only drawn while the owner still holds its permission (handoff section 10)
            String skin = PetSkins.skinToDraw(model != null, petPlayer.getSkin(), PetModels.modelNames());
            if (skin != null && owner.hasPermission(PetSkins.permission(skin))) {
                model = PetModels.settingsForSkin(skin);
            }
            PetDisplay display = new PetDisplay(owner, new ItemBuilder(Material.PLAYER_HEAD).skull(pet.getSkull()).build(),
                    model, () -> hologramText(owner, pet, petPlayer), i, toShow.size(), settings);
            Location stay = this.stayLocations.get(petPlayer.getUniqueId());
            if (petPlayer.isStay() && stay != null && owner.getWorld().equals(stay.getWorld())) {
                display.setStayAt(stay);
            }
            display.spawn();
            sessions.add(display);
        }
        this.activeSessions.put(owner.getUniqueId(), sessions);
    }

    private static String hologramText(Player owner, Pet pet, PetPlayer petPlayer) {
        String format = petPlayer.isDead()
                ? ModernSlimefunPet.PETS_CONFIG.getString("display.hologram-dead", "&c&lDEAD &7{name}")
                : ModernSlimefunPet.PETS_CONFIG.getString("display.hologram", "&eLv {level} &7{name} &8[{food_percent}%]");
        // The food bar can never be worth more than 100%, so a bad stored value only ever looks full
        double percent = Math.max(0, Math.min(100, petPlayer.getFoodProgress() * 100));
        return Common.color(new Placeholder()
                .add("{name}", pet.getItemDisplayName())
                .add("{level}", number(petPlayer.getLevel()))
                .add("{max_level}", number(pet.getMaxLevel()))
                .add("{owner}", owner.getName())
                .add("{food}", number(petPlayer.getFood()))
                .add("{max_food}", number(PetSettings.getMaxFood()))
                .add("{food_percent}", String.valueOf(Math.round(percent)))
                .translate(format));
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    /** Puts away the newest pets past the equip limit, for a limit lowered by a config change. */
    private void enforceSlotLimit(Player owner, PlayerProfile profile) {
        int max = PetSettings.getMaxEquipped(owner);
        if (profile.getEquippedPets().size() <= max) {
            return;
        }
        List<UUID> equipped = new ArrayList<>(profile.getEquippedPets());
        for (int i = max; i < equipped.size(); i++) {
            profile.unequipPet(equipped.get(i));
        }
        DatabaseQueueHandler.addObjectToSave(profile);
        ModernSlimefunPet.PETS_CONFIG.sendMessage(owner, "messages.pet-slots-lost", new Placeholder()
                .add("{max}", String.valueOf(max))
                .add("{amount}", String.valueOf(equipped.size() - max)));
    }

    public void hideFor(Player owner) {
        List<PetDisplay> existing = this.activeSessions.remove(owner.getUniqueId());
        if (existing == null) {
            return;
        }
        for (PetDisplay session : existing) {
            session.destroy();
        }
    }

    public boolean isHidden(Player player) {
        return this.isHidden(player.getUniqueId());
    }

    public boolean isHidden(UUID player) {
        return this.hiddenPlayers.contains(player);
    }

    public void hidePet(Player player) {
        this.setHidden(player, true, true);
    }

    public void showPet(Player player) {
        this.setHidden(player, false, true);
    }

    public void setHidden(Player player, boolean hidden, boolean notify) {
        if (hidden) {
            boolean changed = this.hiddenPlayers.add(player.getUniqueId());
            this.hideFor(player);
            if (notify && changed) {
                player.sendMessage(Common.color(ModernSlimefunPet.PETS_CONFIG.getString("messages.pet-hidden",
                        "&8[&e&l!&8] &fYou have hidden your pet")));
            }
            return;
        }
        boolean changed = this.hiddenPlayers.remove(player.getUniqueId());
        if (changed) {
            this.refreshFor(player);
            if (notify) {
                player.sendMessage(Common.color(ModernSlimefunPet.PETS_CONFIG.getString("messages.pet-shown",
                        "&8[&e&l!&8] &fYou have unhidden your pet")));
            }
        }
    }

    /** Drops everything kept for a player who quit; their pets stop staying. */
    public void clearRuntimeState(Player player) {
        this.hideFor(player);
        this.hiddenPlayers.remove(player.getUniqueId());
        for (PetPlayer petPlayer : ModernSlimefunPet.getInstance().getPetManager().getPetPlayers(player.getUniqueId())) {
            if (petPlayer.isStay() || this.stayLocations.containsKey(petPlayer.getUniqueId())) {
                petPlayer.setStay(false);
                this.stayLocations.remove(petPlayer.getUniqueId());
                DatabaseQueueHandler.addObjectToSave(petPlayer);
            }
        }
    }

    /** Stay on: the pet waits where its owner stands now. Stay off: it follows again. */
    public void setStay(Player owner, PetPlayer petPlayer, boolean stay) {
        petPlayer.setStay(stay);
        if (stay) {
            this.stayLocations.put(petPlayer.getUniqueId(), Utils.locationOf(owner).clone());
        } else {
            this.stayLocations.remove(petPlayer.getUniqueId());
        }
        DatabaseQueueHandler.addObjectToSave(petPlayer);
        this.refreshFor(owner);
    }

    public @Nullable Location getStayLocation(UUID pet) {
        return this.stayLocations.get(pet);
    }

    private void tickAllSessions() {
        Iterator<Map.Entry<UUID, List<PetDisplay>>> iterator = this.activeSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, List<PetDisplay>> entry = iterator.next();
            Player owner = Bukkit.getPlayer(entry.getKey());
            // The owner left: take their pets down
            if (owner == null || !owner.isOnline()) {
                for (PetDisplay session : entry.getValue()) {
                    session.destroy();
                }
                iterator.remove();
                continue;
            }
            for (PetDisplay session : entry.getValue()) {
                // One broken display must not stop every other pet from moving (handoff section 10)
                try {
                    session.tick();
                } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException exception) {
                    Logger.warning("Pet display of " + owner.getName() + " failed to move: " + exception);
                }
            }
        }
    }
}
