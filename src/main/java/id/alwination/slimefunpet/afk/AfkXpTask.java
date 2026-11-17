package id.alwination.slimefunpet.afk;

import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.PetMood;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.listeners.PetActivityListener;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.managers.ProfileManager;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import id.alwination.slimefunpet.utils.Utils;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * AFK player XP (spec section D). Once a second, on the main thread:
 * <ol>
 *   <li>A player whose position moved more than 0.1 block or whose camera turned since the last
 *       check is active again, and so is one who chatted, ran a command, clicked, interacted, or
 *       broke or placed a block. Activity resets the partial hour to 0.</li>
 *   <li>After 'afk-xp.idle-minutes' without activity the player is AFK, and the hour starts to
 *       count.</li>
 *   <li>The hour only counts while the player has an equipped, living pet at least as happy as
 *       'required-mood', is not in combat, not in water or lava, and the pet is not on Stay far away
 *       or in an unloaded chunk. Otherwise it pauses (it is not reset).</li>
 *   <li>Each full interval pays 'xp-per-interval' XP points with giveExp (never levels), up to
 *       'daily-cap' per day; the day rolls over at 00:00 server time. The cap, the day and the
 *       partial hour live on the profile, so a restart resets none of them.</li>
 * </ol>
 * Paper has no PlayerIdleEvent on 26.3 (checked), and EssentialsX AFK is not used.
 */
public final class AfkXpTask implements Listener {

    private static final double MOVE_EPSILON_SQUARED = 0.1 * 0.1;
    private static final float TURN_EPSILON = 0.1f;
    private static final long MAX_STEP_MILLIS = 5_000L;

    /** Where a player was and looked at their last activity. */
    private record Anchor(String world, double x, double y, double z, float yaw, float pitch) {
    }

    private final ProfileManager profiles;
    private final PetTask petTask;
    private final PetActivityListener activity;
    private final Path logFile;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> resetPending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private long previousTick;
    private @Nullable HandleTask task;

    public AfkXpTask(ProfileManager profiles, PetTask petTask, PetActivityListener activity, Path logFile) {
        this.profiles = profiles;
        this.petTask = petTask;
        this.activity = activity;
        this.logFile = logFile;
    }

    public void start() {
        if (this.task != null) {
            this.task.cancel();
        }
        this.previousTick = 0;
        this.task = Executor.syncTimer(20L, 20L, this::tick);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long step = this.previousTick == 0 ? 1000L : Math.min(MAX_STEP_MILLIS, Math.max(0, now - this.previousTick));
        this.previousTick = now;
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.update(player, now, step);
        }
    }

    private void update(Player player, long now, long step) {
        UUID uuid = player.getUniqueId();
        Location location = Utils.locationOf(player);
        Anchor anchor = this.anchors.get(uuid);
        if (anchor == null) {
            this.anchors.put(uuid, anchorOf(location));
            this.lastActivity.putIfAbsent(uuid, now);
        } else if (moved(anchor, location)) {
            this.anchors.put(uuid, anchorOf(location));
            this.markActive(uuid, now);
        }
        PlayerProfile profile = this.profiles.getProfile(uuid);
        if (this.resetPending.remove(uuid) && profile.getAfkProgressMillis() > 0) {
            profile.setAfkProgressMillis(0);
            DatabaseQueueHandler.addObjectToSave(profile);
        }
        if (!AfkSettings.isEnabled() || now - this.lastActivity.getOrDefault(uuid, now) < AfkSettings.getIdleMillis()) {
            return;
        }

        String today = LocalDate.now().toString();
        int todayXp = todayXp(profile.getDailyDate(), profile.getDailyXp(), today);
        if (payoutAmount(todayXp, AfkSettings.getXpPerInterval(), AfkSettings.getDailyCap()) <= 0) {
            Common.actionBar(player, ModernSlimefunPet.PETS_CONFIG.getString("messages.afk-xp-capped", ""));
            return;
        }
        if (!this.isEligible(player, profile, now)) {
            Common.actionBar(player, ModernSlimefunPet.PETS_CONFIG.getString("messages.afk-xp-paused", ""));
            return;
        }
        long progress = profile.getAfkProgressMillis() + step;
        profile.setAfkProgressMillis(progress);
        DatabaseQueueHandler.addObjectToSave(profile);
        if (progress >= AfkSettings.getIntervalMillis()) {
            this.pay(player, profile, todayXp, today);
        }
        long left = Math.max(0, AfkSettings.getIntervalMillis() - profile.getAfkProgressMillis());
        Common.actionBar(player, ModernSlimefunPet.PETS_CONFIG.getString("messages.afk-xp-next", ""),
                new Placeholder().add("{time}", formatLeft(left)));
    }

    private boolean isEligible(Player player, PlayerProfile profile, long now) {
        if (this.activity.isInCombat(player.getUniqueId(), now, PetSettings.getCombatSeconds())) {
            return false;
        }
        if (player.isInWater() || player.isInLava()) {
            return false;
        }
        Location location = Utils.locationOf(player);
        if (!player.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        for (UUID uuid : profile.getEquippedPets()) {
            PetPlayer pet = PetManager.getPetPlayer(uuid);
            if (pet == null || !pet.isActive()) {
                continue;
            }
            PetMood mood = AfkSettings.moodOf(pet.getFoodProgress() * 100);
            if (mood.isAtLeast(AfkSettings.getRequiredMood()) && this.isNearOwner(player, pet)) {
                return true;
            }
        }
        return false;
    }

    /** A pet on Stay counts only within 'stay-max-distance', in the same world, in a loaded chunk. */
    private boolean isNearOwner(Player player, PetPlayer pet) {
        if (!pet.isStay()) {
            return true;
        }
        Location stay = this.petTask.getStayLocation(pet.getUniqueId());
        if (stay == null || stay.getWorld() == null || !stay.getWorld().equals(player.getWorld())) {
            return false;
        }
        if (!stay.getWorld().isChunkLoaded(stay.getBlockX() >> 4, stay.getBlockZ() >> 4)) {
            return false;
        }
        double max = AfkSettings.getStayMaxDistance();
        return stay.distanceSquared(Utils.locationOf(player)) <= max * max;
    }

    private void pay(Player player, PlayerProfile profile, int todayXp, String today) {
        int amount = payoutAmount(todayXp, AfkSettings.getXpPerInterval(), AfkSettings.getDailyCap());
        int total = todayXp + amount;
        // Points, not levels, and never into Mending: the vanilla curve decides what they are worth
        player.giveExp(amount, false);
        profile.setDailyXp(total);
        profile.setDailyDate(today);
        profile.setAfkProgressMillis(Math.max(0, profile.getAfkProgressMillis() - AfkSettings.getIntervalMillis()));
        DatabaseQueueHandler.addObjectToSave(profile);
        DatabaseQueueHandler.saveNow();
        ModernSlimefunPet.PETS_CONFIG.sendMessage(player, "messages.afk-xp-paid", new Placeholder()
                .add("{amount}", String.valueOf(amount))
                .add("{today}", String.valueOf(total))
                .add("{cap}", String.valueOf(AfkSettings.getDailyCap())));
        if (AfkSettings.isLogPayouts()) {
            Location at = Utils.locationOf(player);
            InetSocketAddress address = player.getAddress();
            AfkPayoutLog.append(this.logFile, LocalDateTime.now() + " " + player.getName() + " " + player.getUniqueId()
                    + " amount=" + amount + " today=" + total + " world=" + player.getWorld().getName()
                    + " x=" + at.getBlockX() + " y=" + at.getBlockY() + " z=" + at.getBlockZ()
                    + " ip=" + (address == null ? "?" : address.getAddress().getHostAddress()));
        }
    }

    private void markActive(UUID player, long now) {
        this.lastActivity.put(player, now);
        this.resetPending.add(player);
    }

    private void markActive(Player player) {
        this.markActive(player.getUniqueId(), System.currentTimeMillis());
    }

    // A join is not activity: the saved partial hour carries on once the player idles again
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        this.anchors.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        this.lastActivity.remove(uuid);
        this.resetPending.remove(uuid);
        this.anchors.remove(uuid);
    }

    // Async: only touches the concurrent maps
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        this.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        this.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        this.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        this.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            this.markActive(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        this.markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        this.markActive(event.getPlayer());
    }

    private static Anchor anchorOf(Location location) {
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        return new Anchor(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static boolean moved(Anchor anchor, Location location) {
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        return !anchor.world().equals(world) || moved(location.getX() - anchor.x(), location.getY() - anchor.y(),
                location.getZ() - anchor.z(), location.getYaw() - anchor.yaw(), location.getPitch() - anchor.pitch());
    }

    /** Moved more than 0.1 block, or turned the camera at all (beyond float noise). */
    static boolean moved(double dx, double dy, double dz, float dyaw, float dpitch) {
        return dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQUARED
                || Math.abs(dyaw) > TURN_EPSILON || Math.abs(dpitch) > TURN_EPSILON;
    }

    /** XP already paid today: the stored total, or 0 once the stored day is not today (00:00 rollover). */
    static int todayXp(String storedDate, int storedXp, String today) {
        return today.equals(storedDate) ? storedXp : 0;
    }

    /** What the next payout gives: the interval's XP, cut down to what is left of the daily cap. */
    static int payoutAmount(int todayXp, int xpPerInterval, int dailyCap) {
        return Math.max(0, Math.min(xpPerInterval, dailyCap - todayXp));
    }

    /** "42m" for the action bar; the last minute counts as 1m. */
    static String formatLeft(long millis) {
        return Math.max(1, (millis + 59_999) / 60_000) + "m";
    }
}
