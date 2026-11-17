package id.alwination.slimefunpet.packet.display;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One floating pet, following its owner and shown to every player around it.
 * <p>
 * Nothing here is a real entity, the head and the hologram are armor stands
 * that only exist inside the packets sent to each viewer. Every viewer gets
 * the same position, so the pet is in the same spot for everybody.
 * <p>
 * A pet with a model is drawn as block displays instead of the head. Geyser
 * drops block displays, so Bedrock players still get the head, unless the
 * AlwiDisplayEntity Geyser extension said they have its pack.
 * <p>
 * Shared by every plugin that draws pets, so it knows nothing about what a
 * pet is: it gets a head, a model and the hologram text, and draws them.
 */
// volatile publishes state between the server thread and a display timer thread; kept from pet-display.
@SuppressWarnings("PMD.AvoidUsingVolatile")
public class PetDisplay {

    /**
     * How pets are drawn, from the plugin's config.
     *
     * @param updateIntervalTicks how often {@link #tick} runs
     * @param orbitRadius         blocks between pets spread around one owner
     * @param visibleToOthers     false shows a pet to its owner only
     * @param viewDistance        blocks from the owner others still see it from
     * @param hidesPets           whether a player turned every pet off for themselves
     * @param text                hologram text, colour codes included, to a component
     */
    public record Settings(int updateIntervalTicks, double orbitRadius, boolean visibleToOthers, double viewDistance,
                           Predicate<UUID> hidesPets, Function<String, Component> text) {}

    // ======= Position & animation =======
    private static final double BACK_OFFSET = 0.6;
    private static final double LERP_POS = 0.4;
    private static final double LERP_YAW = 0.25;
    private static final double BOB_AMPLITUDE = 0.05;
    private static final double BOB_SPEED = 6.0;
    private static final double PET_HEAD_OFFSET_Y = 1.05;

    // ======= Model pets =======
    // A model walks behind its owner rather than floating by their head
    private static final double MODEL_BACK_OFFSET = 1.0;
    // How high a model flies above its owner's feet while they are in the air
    private static final double MODEL_FLY_HEIGHT = 0.8;
    // In the air this long counts as flying, so a jump does not
    private static final double AIRBORNE_SECONDS = 0.5;
    // A model only turns to face where it goes above this, blocks per update
    private static final double TURN_MIN_STEP = 0.03;
    // Further than this from where it should be, the pet jumps there instead
    // of sliding, blocks. Even gliding, a pet trails its owner by only a few
    private static final double SNAP_DISTANCE = 16;

    // Dead zone before a movement packet is worth sending (0.02 block, 1 degree)
    private static final double MIN_MOVE_DELTA_SQ = 0.02 * 0.02;
    private static final float MIN_YAW_DELTA = 1.0f;

    // Resend the position every so many updates even when nothing moved
    private static final int PERIODIC_RESYNC_TICKS = 40;

    // ===== animation state =====
    private double animX, animY, animZ;
    private float animYaw = 0.0f;

    // ===== last position sent for the head =====
    private double lastHeadX, lastHeadY, lastHeadZ;
    private float lastHeadYaw;
    private int headResyncCooldown = 0;

    // ===== last position sent for the hologram =====
    private double lastHoloX, lastHoloY, lastHoloZ;
    private float lastHoloYaw;
    private int holoResyncCooldown = 0;

    // Owner & pet
    private final Player owner;
    // A player head, what a viewer that cannot see the model gets
    private final ItemStack head;
    // Null when the pet has no hologram
    private final @Nullable Supplier<String> hologram;
    private final Settings settings;

    /**
     * Where this pet sits in the ring around the owner, so several pets don't
     * stack on the same spot.
     */
    private final int slotIndex;
    private int slotCount;

    // Entity ids
    private final int entityIdHead;
    private final UUID entityUuidHead;
    private final int entityIdHologram;
    private final UUID entityUuidHologram;

    /**
     * The model the pet is drawn with, null when every viewer gets the head.
     */
    private final @Nullable ModelRig rig;
    private final double modelYOffset;
    // Updates the owner has spent off the ground in a row, see isAirborne
    private int airUpdates = 0;

    /**
     * Everyone currently seeing this pet, the owner included.
     */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    /**
     * The viewers that were shown the model instead of the head, always a
     * subset of 'viewers'.
     */
    private final Set<UUID> modelViewers = ConcurrentHashMap.newKeySet();

    // Hologram cache, so the metadata packet is only resent when the text changes
    private String cachedHologramText = "";

    /**
     * Written by whichever thread refreshes the pets and read by the ticker, so
     * both have to be volatile.
     * <p>
     * 'destroyed' is one way on purpose: once a display has been taken down it
     * must never come back. The ticker used to be able to run updateViewers
     * right after a destroy, which spawned the very same entity ids again on
     * every client while the display was already off the map, leaving a pet
     * frozen in place that nothing would ever tick or remove.
     */
    private volatile boolean spawned = false;
    private volatile boolean destroyed = false;

    /**
     * @param head      a player head item, never changed here
     * @param model     null to draw the head for everyone
     * @param hologram  the name tag text, read every tick off the main
     *                  thread; null for no name tag
     * @param slotIndex where this pet sits among the owner's slotCount pets
     */
    public PetDisplay(Player owner, ItemStack head, PetModels.@Nullable Settings model, @Nullable Supplier<String> hologram,
                      int slotIndex, int slotCount, Settings settings) {
        this.owner = owner;
        this.head = head;
        this.hologram = hologram;
        this.settings = settings;
        this.slotIndex = slotIndex;
        this.slotCount = Math.max(1, slotCount);

        this.entityIdHead = nextEntityId();
        this.entityUuidHead = UUID.randomUUID();
        this.entityIdHologram = nextEntityId();
        this.entityUuidHologram = UUID.randomUUID();

        this.rig = model == null ? null : new ModelRig(model, PetDisplay::nextEntityId, settings.updateIntervalTicks());
        this.modelYOffset = model == null ? 0 : model.yOffset();
    }

    private static int nextEntityId() {
        return 100000 + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE - 100000);
    }

    public void setSlotCount(int slotCount) {
        this.slotCount = Math.max(1, slotCount);
    }

    // ===== Lifecycle =====

    public synchronized void spawn() {
        if (this.destroyed || this.spawned) return;

        org.bukkit.Location target = computeTarget(false);
        this.animX = target.getX();
        this.animY = target.getY();
        this.animZ = target.getZ();
        this.animYaw = normalizeYaw(target.getYaw());

        this.lastHeadX = animX;
        this.lastHeadY = animY;
        this.lastHeadZ = animZ;
        this.lastHeadYaw = animYaw;
        this.headResyncCooldown = 0;

        org.bukkit.Location hologramAt = computeHologramTargetOverPet();
        this.lastHoloX = hologramAt.getX();
        this.lastHoloY = hologramAt.getY();
        this.lastHoloZ = hologramAt.getZ();
        this.lastHoloYaw = hologramAt.getYaw();
        this.holoResyncCooldown = 0;

        this.cachedHologramText = this.hologram == null ? "" : this.hologram.get();
        this.spawned = true;

        // Show it to everyone that should already be seeing it
        this.updateViewers();
    }

    public synchronized void destroy() {
        // Mark it first, so a tick that is already running cannot spawn the
        // entities back after the destroy packets have gone out
        this.destroyed = true;
        if (!spawned) return;

        this.spawned = false;
        for (UUID uuid : new ArrayList<>(this.viewers)) {
            Player viewer = org.bukkit.Bukkit.getPlayer(uuid);
            if (viewer != null) {
                sendDestroy(viewer);
            }
        }
        this.viewers.clear();
        this.modelViewers.clear();
    }

    /**
     * Move the pet and keep the list of players seeing it up to date.
     */
    public synchronized void tick() {
        if (destroyed || !spawned || !owner.isOnline()) return;

        // Work out where the pet should be
        boolean airborne = this.isAirborne();
        org.bukkit.Location target = computeTarget(airborne);

        // The owner jumped (a teleport): slide there and it takes seconds,
        // while the client stops ticking anything left in the chunks it just
        // unloaded, so a display stuck there never moves again. Jump instead,
        // and respawn the pet for everyone at the new spot
        double jumpX = target.getX() - animX;
        double jumpY = target.getY() - animY;
        double jumpZ = target.getZ() - animZ;
        if (jumpX * jumpX + jumpY * jumpY + jumpZ * jumpZ > SNAP_DISTANCE * SNAP_DISTANCE) {
            this.snapTo(target);
        }

        double fromX = animX;
        double fromZ = animZ;
        animX += (target.getX() - animX) * LERP_POS;
        animY += (target.getY() - animY) * LERP_POS;
        animZ += (target.getZ() - animZ) * LERP_POS;

        float targetYaw = normalizeYaw(target.getYaw());
        if (this.rig != null) {
            // A model faces the way it moves, and keeps facing that way when it
            // stops, rather than copying every turn of the owner's camera
            double stepX = animX - fromX;
            double stepZ = animZ - fromZ;
            targetYaw = stepX * stepX + stepZ * stepZ > TURN_MIN_STEP * TURN_MIN_STEP
                    ? normalizeYaw((float) Math.toDegrees(Math.atan2(-stepX, stepZ)))
                    : animYaw;
        }
        animYaw = normalizeYaw(animYaw + shortestYawDelta(animYaw, targetYaw) * (float) LERP_YAW);

        // Add or drop viewers before sending anything
        this.updateViewers();
        if (this.viewers.isEmpty()) return;

        double bob = Math.sin((System.nanoTime() / 1e9) * BOB_SPEED) * BOB_AMPLITUDE;
        List<Player> everyone = this.getOnlineViewers();
        List<Player> modelPlayers = new ArrayList<>();
        List<Player> headPlayers = new ArrayList<>();
        for (Player viewer : everyone) {
            if (this.modelViewers.contains(viewer.getUniqueId())) modelPlayers.add(viewer);
            else headPlayers.add(viewer);
        }

        // ===== HEAD =====
        sendMovement(entityIdHead, animX, animY + bob, animZ, animYaw, true, headPlayers);

        // ===== MODEL =====
        if (this.rig != null && !modelPlayers.isEmpty()) {
            List<PacketWrapper<?>> packets = this.rig.tick(animX, animY, animZ, animYaw, airborne);
            if (!packets.isEmpty()) {
                // Bundled, so the client applies every part's new pose in the
                // same frame. Applied one packet at a time, a part could start
                // its move a frame before the part it sits on and sink into it
                for (Player viewer : modelPlayers) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerBundle());
                    for (PacketWrapper<?> packet : packets) {
                        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
                    }
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerBundle());
                }
            }
        }

        // ===== HOLOGRAM =====
        if (this.hologram == null) return;
        org.bukkit.Location holo = computeHologramTargetOverPet();
        sendMovement(entityIdHologram, holo.getX(), holo.getY(), holo.getZ(), holo.getYaw(), false, everyone);

        // ===== TEXT =====
        String text = this.hologram.get();
        if (!text.equals(this.cachedHologramText)) {
            this.cachedHologramText = text;
            for (Player viewer : everyone) {
                sendHologramMetadata(viewer, text);
            }
        }
    }

    /**
     * Put the pet straight at the target and take it down for every viewer;
     * the updateViewers that follows in the same tick spawns it again there.
     */
    private void snapTo(org.bukkit.Location target) {
        this.animX = target.getX();
        this.animY = target.getY();
        this.animZ = target.getZ();
        if (this.rig != null) {
            this.rig.jumpTo(this.animX, this.animY, this.animZ);
        }
        for (UUID uuid : new ArrayList<>(this.viewers)) {
            Player viewer = Bukkit.getPlayer(uuid);
            if (viewer != null) {
                sendDestroy(viewer);
            }
            this.viewers.remove(uuid);
        }
    }

    // ===== Viewers =====

    /**
     * Spawn the pet for players that came into range and destroy it for the
     * ones that walked away.
     */
    private void updateViewers() {
        // A destroyed display has no business spawning anything again
        if (this.destroyed) return;

        Set<UUID> shouldSee = this.computeShouldSee();

        // Someone walked out of range or left the server
        for (UUID uuid : new ArrayList<>(this.viewers)) {
            if (shouldSee.contains(uuid)) continue;
            Player viewer = org.bukkit.Bukkit.getPlayer(uuid);
            if (viewer != null) {
                sendDestroy(viewer);
            }
            this.viewers.remove(uuid);
            this.modelViewers.remove(uuid);
        }

        // Someone came into range
        for (UUID uuid : shouldSee) {
            if (!this.viewers.add(uuid)) continue;
            Player viewer = org.bukkit.Bukkit.getPlayer(uuid);
            if (viewer == null) {
                this.viewers.remove(uuid);
                continue;
            }
            this.sendSpawn(viewer);
        }
    }

    /**
     * Who should be able to see this pet right now.
     */
    private Set<UUID> computeShouldSee() {
        Set<UUID> result = new HashSet<>();
        if (!owner.isOnline()) return result;

        // The owner always sees their own pet
        result.add(owner.getUniqueId());

        if (!this.settings.visibleToOthers()) {
            return result;
        }

        double range = this.settings.viewDistance();
        double rangeSq = range * range;
        for (Player nearby : owner.getWorld().getPlayers()) {
            if (nearby.getUniqueId().equals(owner.getUniqueId())) continue;
            // Hiding pets hides everyone's pets for that player, not only their own
            if (this.settings.hidesPets().test(nearby.getUniqueId())) continue;
            if (Objects.requireNonNull(nearby.getLocation()).distanceSquared(Objects.requireNonNull(owner.getLocation())) > rangeSq) continue;
            result.add(nearby.getUniqueId());
        }
        return result;
    }

    private List<Player> getOnlineViewers() {
        List<Player> players = new ArrayList<>(this.viewers.size());
        for (UUID uuid : this.viewers) {
            Player viewer = org.bukkit.Bukkit.getPlayer(uuid);
            if (viewer != null && viewer.isOnline()) {
                players.add(viewer);
            }
        }
        return players;
    }

    // ===== Packets =====

    private void sendSpawn(Player viewer) {
        if (this.rig != null && (!isBedrock(viewer) || BEDROCK_WITH_PACK.contains(viewer.getUniqueId()))) {
            this.modelViewers.add(viewer.getUniqueId());
            this.rig.spawn(viewer, animX, animY, animZ, animYaw);
        } else {
            Location petLocation = new Location(animX, animY, animZ, animYaw, 0f);
            spawnHeadStand(viewer, petLocation);
            sendHeadItem(viewer);
        }

        if (this.hologram == null) return;
        org.bukkit.Location hologramAt = computeHologramTargetOverPet();
        spawnHologramStand(viewer, new Location(hologramAt.getX(), hologramAt.getY(), hologramAt.getZ(), hologramAt.getYaw(), 0f));
        sendHologramMetadata(viewer, this.cachedHologramText);
    }

    private void sendDestroy(Player viewer) {
        this.modelViewers.remove(viewer.getUniqueId());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityIdHead));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(entityIdHologram));
        if (this.rig != null) {
            // Harmless for a viewer that was shown the head, the ids are unknown to it
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerDestroyEntities(this.rig.getEntityIds()));
        }
    }

    /**
     * Send a packet to everyone currently seeing this pet.
     * <p>
     * The parameter has to stay a PacketWrapper. PlayerManager also has a
     * sendPacket(Object, Object) taking a raw byte buffer, and java picks the
     * overload from the declared type, so widening this to Object silently
     * sends the packet down the wrong path and nothing reaches the client.
     */
    private static void broadcast(PacketWrapper<?> packet, List<Player> targets) {
        for (Player viewer : targets) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    /**
     * Move an entity for every viewer.
     * <p>
     * This always teleports instead of sending relative moves. A relative move
     * is a smaller packet, but the delta is encoded as a short of 1/4096 blocks
     * and any mismatch between what the server sends and what the client draws
     * is permanent, which is how the pets ended up frozen in place. A pet is
     * only a couple of packets per viewer, so the bigger packet is well worth
     * being able to trust the position.
     */
    private void sendMovement(int entityId, double newX, double newY, double newZ, float newYaw, boolean allowLook,
                              List<Player> targets) {
        boolean isHead = (entityId == entityIdHead);

        double lastX = isHead ? lastHeadX : lastHoloX;
        double lastY = isHead ? lastHeadY : lastHoloY;
        double lastZ = isHead ? lastHeadZ : lastHoloZ;
        float lastYaw = isHead ? lastHeadYaw : lastHoloYaw;
        int resyncCooldown = isHead ? headResyncCooldown : holoResyncCooldown;

        double dx = newX - lastX;
        double dy = newY - lastY;
        double dz = newZ - lastZ;

        double distSq = dx * dx + dy * dy + dz * dz;
        float yawDiff = Math.abs(((newYaw - lastYaw + 540f) % 360f) - 180f);

        boolean moveChanged = distSq > MIN_MOVE_DELTA_SQ;
        boolean yawChanged = allowLook && yawDiff > MIN_YAW_DELTA;
        // Resend now and then even when nothing changed, so a viewer that
        // missed a packet is not left looking at a pet stuck in mid air
        boolean mustResync = resyncCooldown >= PERIODIC_RESYNC_TICKS;

        // A pet standing still costs nothing
        if (!moveChanged && !yawChanged && !mustResync) {
            if (isHead) headResyncCooldown = resyncCooldown + 1;
            else holoResyncCooldown = resyncCooldown + 1;
            return;
        }

        broadcast(new WrapperPlayServerEntityTeleport(entityId, new Location(newX, newY, newZ, newYaw, 0f), false), targets);

        if (isHead) {
            lastHeadX = newX;
            lastHeadY = newY;
            lastHeadZ = newZ;
            lastHeadYaw = newYaw;
            headResyncCooldown = 0;
        } else {
            lastHoloX = newX;
            lastHoloY = newY;
            lastHoloZ = newZ;
            lastHoloYaw = newYaw;
            holoResyncCooldown = 0;
        }
    }

    private void spawnHeadStand(Player viewer, Location at) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerSpawnEntity(
                entityIdHead, entityUuidHead, EntityTypes.ARMOR_STAND, at, 0.0f, 0, null));

        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));   // invisible
        meta.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));       // no gravity
        meta.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) 0x01));  // small, not a marker so the helmet shows

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityIdHead, meta));
    }

    private void sendHeadItem(Player viewer) {
        com.github.retrooper.packetevents.protocol.item.ItemStack peHead =
                SpigotConversionUtil.fromBukkitItemStack(this.head);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityEquipment(entityIdHead, List.of(new Equipment(EquipmentSlot.HELMET, peHead))));
    }

    private void spawnHologramStand(Player viewer, Location at) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerSpawnEntity(
                entityIdHologram, entityUuidHologram, EntityTypes.ARMOR_STAND, at, 0.0f, 0, null));
    }

    private void sendHologramMetadata(Player viewer, String text) {
        Component component = this.settings.text().apply(text);

        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));  // invisible
        meta.add(new EntityData<>(4, EntityDataTypes.BOOLEAN, true));      // silent
        meta.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true));      // no gravity
        meta.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) (0x01 | 0x08 | 0x10))); // small | no base | marker
        meta.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(component)));
        meta.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, true));      // name visible

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityIdHologram, meta));
    }

    // ===== Position =====

    private org.bukkit.Location computeHologramTargetOverPet() {
        if (this.rig != null) {
            // Never lower than over the head, a Bedrock viewer still sees that
            double over = Math.max(PET_HEAD_OFFSET_Y, this.rig.getHeight() + 0.4);
            return new org.bukkit.Location(owner.getWorld(), animX, animY + over, animZ, animYaw, 0f);
        }
        double bob = Math.sin((System.nanoTime() / 1e9) * BOB_SPEED) * BOB_AMPLITUDE;
        return new org.bukkit.Location(owner.getWorld(), animX, animY + bob + PET_HEAD_OFFSET_Y, animZ, animYaw, 0f);
    }

    /**
     * Whether the owner is flying, gliding, or has been off the ground longer
     * than a jump lasts. Counted in updates, so only call it once per tick.
     */
    private boolean isAirborne() {
        this.airUpdates = owner.isOnGround() ? 0 : this.airUpdates + 1;
        double airborneUpdates = AIRBORNE_SECONDS * 20 / this.settings.updateIntervalTicks();
        return owner.isFlying() || owner.isGliding() || this.airUpdates > airborneUpdates;
    }

    /**
     * Where the pet wants to be: behind the owner, spread around them in a ring
     * so several pets never overlap.
     * <p>
     * A skull floats by the owner's head and follows where they look. A model
     * walks at their feet behind where their body faces, so looking around
     * does not swing it about, and rises beside them while they fly.
     */
    private org.bukkit.Location computeTarget(boolean airborne) {
        org.bukkit.Location eye = owner.getEyeLocation();
        boolean model = this.rig != null;
        double yawRad = Math.toRadians(model ? owner.getBodyYaw() : eye.getYaw());

        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = Math.cos(yawRad);
        double rz = Math.sin(yawRad);

        // Spread the pets evenly on the arc behind the owner
        double radius = this.settings.orbitRadius();
        double sideOffset = radius;
        if (this.slotCount > 1) {
            // -1 .. 1 across the slots, so 3 pets sit at -r, 0 and +r
            double spread = (this.slotIndex / (double) (this.slotCount - 1)) * 2 - 1;
            sideOffset = spread * radius * this.slotCount * 0.5;
        }

        double back = model ? MODEL_BACK_OFFSET : BACK_OFFSET;
        double x = eye.getX() - fx * back + rx * sideOffset;
        double z = eye.getZ() - fz * back + rz * sideOffset;
        double y = eye.getY() - 0.5;
        if (model) {
            double feet = Objects.requireNonNull(owner.getLocation()).getY();
            double bob = Math.sin((System.nanoTime() / 1e9) * BOB_SPEED) * BOB_AMPLITUDE;
            y = feet + this.modelYOffset + (airborne ? MODEL_FLY_HEIGHT + bob : 0);
        }

        return new org.bukkit.Location(eye.getWorld(), x, y, z, eye.getYaw(), 0.0f);
    }

    // ===== Bedrock =====

    /**
     * Bedrock players the AlwiDisplayEntity extension reported as able to draw
     * block displays this session. Cleared when they quit.
     */
    private static final Set<UUID> BEDROCK_WITH_PACK = ConcurrentHashMap.newKeySet();

    /**
     * @return false when nothing changed
     */
    public static boolean setHasPack(UUID player, boolean hasPack) {
        return hasPack ? BEDROCK_WITH_PACK.add(player) : BEDROCK_WITH_PACK.remove(player);
    }

    public static boolean hasPack(UUID player) {
        return BEDROCK_WITH_PACK.contains(player);
    }

    /**
     * Take this pet down for one viewer, so the next tick shows it to them
     * again the way they should see it now.
     */
    public synchronized void resendTo(UUID viewer) {
        if (!this.viewers.remove(viewer)) return;
        Player player = Bukkit.getPlayer(viewer);
        if (player != null) {
            sendDestroy(player);
        }
        this.modelViewers.remove(viewer);
    }

    private static volatile boolean floodgateResolved = false;
    private static @Nullable Object floodgateApi;
    private static @Nullable Method isFloodgatePlayer;

    /**
     * Whether a player joined through Geyser/Floodgate. Floodgate is optional,
     * so it is looked up once by reflection instead of compiled against.
     * Without Floodgate every player counts as Java.
     */
    // A reflective call into Floodgate can throw anything; answering "Java" is the safe fallback.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static boolean isBedrock(Player player) {
        if (!floodgateResolved) {
            resolveFloodgate();
        }
        Object api = floodgateApi;
        Method method = isFloodgatePlayer;
        if (api == null || method == null) return false;
        try {
            return (boolean) method.invoke(api, player.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Answering Java only costs a Bedrock player the pet, not worth
            // failing the whole display over
            return false;
        }
    }

    /**
     * Written before 'floodgateResolved' is set, so the volatile write
     * publishes both fields to the ticker thread.
     */
    // Floodgate's own class loader on purpose (it is another plugin); any failure only disables the Bedrock check.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.UseProperClassLoader"})
    private static synchronized void resolveFloodgate() {
        if (floodgateResolved) return;
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate != null) {
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                        floodgate.getClass().getClassLoader());
                floodgateApi = apiClass.getMethod("getInstance").invoke(null);
                isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Bukkit.getLogger().warning("[PetDisplay] Floodgate is installed but its API could not be reached,"
                        + " Bedrock players will be sent model pets they cannot see (" + ex + ")");
            }
        }
        floodgateResolved = true;
    }

    private float normalizeYaw(float yaw) {
        yaw %= 360f;
        if (yaw < 0) yaw += 360f;
        return yaw;
    }

    private float shortestYawDelta(float from, float to) {
        float diff = to - from;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

}
