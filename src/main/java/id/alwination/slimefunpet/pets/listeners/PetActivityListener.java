package id.alwination.slimefunpet.pets.listeners;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.Nullable;

/**
 * Remembers when each player last fought and last mined, for the conditional pet effects
 * (Speed out of combat, Haste while mining) and the AFK XP pause in combat.
 * <p>
 * Combat: the player hit, or was hit by, a mob or another player (a projectile counts for whoever
 * shot it). Cleared on quit.
 */
public final class PetActivityListener implements Listener {

    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMining = new ConcurrentHashMap<>();

    public long getLastCombat(UUID player) {
        return this.lastCombat.getOrDefault(player, 0L);
    }

    public long getLastMining(UUID player) {
        return this.lastMining.getOrDefault(player, 0L);
    }

    /** Whether the player hit or was hit within the last {@code seconds} seconds. */
    public boolean isInCombat(UUID player, long now, int seconds) {
        return now - this.getLastCombat(player) < seconds * 1000L;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        LivingEntity attacker = attacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (attacker instanceof Player player) {
            this.lastCombat.put(player.getUniqueId(), now);
        }
        if (victim instanceof Player player) {
            this.lastCombat.put(player.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        this.lastMining.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        this.lastCombat.remove(player);
        this.lastMining.remove(player);
    }

    private static @Nullable LivingEntity attacker(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }
}
