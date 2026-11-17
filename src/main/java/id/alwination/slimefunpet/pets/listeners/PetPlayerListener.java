package id.alwination.slimefunpet.pets.listeners;

import com.muhammaddaffa.mdlib.utils.Executor;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.managers.ProfileManager;
import id.alwination.slimefunpet.pets.tasks.PetEffectTask;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Join, quit, respawn and world change: the pet display is rebuilt, and the profile is saved when
 * the player leaves. Ported from ModernMineGens' UserListener (pet parts only).
 */
public final class PetPlayerListener implements Listener {

    /** The client needs a moment after a join, respawn or world change before entities show. */
    private static final long REDRAW_DELAY_TICKS = 3L;

    private final ProfileManager profileManager;
    private final PetTask petTask;
    private final PetEffectTask petEffectTask;

    public PetPlayerListener(ProfileManager profileManager, PetTask petTask, PetEffectTask petEffectTask) {
        this.profileManager = profileManager;
        this.petTask = petTask;
        this.petEffectTask = petEffectTask;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = this.profileManager.getProfile(player);
        this.petTask.setHidden(player, profile.isToggleHidePet(), false);
        this.redrawLater(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = this.profileManager.findProfile(player.getUniqueId());
        if (profile != null) {
            DatabaseQueueHandler.addObjectToSave(profile);
        }
        this.petTask.clearRuntimeState(player);
        this.petEffectTask.forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        this.redrawLater(event.getPlayer().getUniqueId());
    }

    // A cross-world teleport is handled here alone: ModernMineGens found that also listening to the
    // teleport fired two redraws and left the first set of displays frozen at the arrival spot.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.redrawLater(event.getPlayer().getUniqueId());
    }

    private void redrawLater(UUID player) {
        Executor.syncLater(REDRAW_DELAY_TICKS, () -> {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                this.petTask.refreshFor(online);
            }
        });
    }
}
