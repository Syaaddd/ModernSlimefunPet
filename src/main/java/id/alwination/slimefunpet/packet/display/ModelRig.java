package id.alwination.slimefunpet.packet.display;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Player;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * One pet drawn from a {@link BBModel}: a block display per cube, all riding
 * one invisible base so a single teleport moves the whole model.
 * <p>
 * Every cube's pose is worked out here and sent as a display transformation,
 * which the client then smooths over one update interval. Not thread safe, it
 * belongs to its {@code PetDisplay} and is only touched under that lock.
 */
public final class ModelRig {

    // Display entity metadata, 1.20.2 and up
    private static final int META_INTERPOLATION_DELAY = 8;
    private static final int META_TRANSFORM_DURATION = 9;
    private static final int META_TELEPORT_DURATION = 10;
    private static final int META_TRANSLATION = 11;
    private static final int META_SCALE = 12;
    private static final int META_LEFT_ROTATION = 13;
    private static final int META_RIGHT_ROTATION = 14;
    private static final int META_BLOCK_STATE = 23;

    // Same dead zone and resync as the skull, see PetDisplay
    private static final double MIN_MOVE_DELTA_SQ = 0.02 * 0.02;
    private static final int PERIODIC_RESYNC_TICKS = 40;
    // Below this the pet plays 'idle' instead of 'walk', blocks per second
    private static final double WALK_SPEED = 0.6;
    // A cube that moved less than this since the last update is left alone
    private static final float CHANGE_EPSILON = 1e-4f;

    // Looking around while standing: how far the head turns, degrees, how
    // long a glance holds and how long it waits between glances, seconds
    private static final float LOOK_YAW = 50;
    private static final float LOOK_PITCH = 15;
    private static final double GLANCE_MIN = 1.5, GLANCE_MAX = 3;
    private static final double REST_MIN = 4, REST_MAX = 10;
    // How quickly the head eases toward where it looks, per second
    private static final float LOOK_SPEED = 4;

    private final PetModels.Settings settings;
    private final BBModel model;
    private final int interval;
    private final long startNanos = System.nanoTime();

    private final int baseId;
    private final UUID baseUuid = UUID.randomUUID();
    private final int[] cubeIds;
    private final UUID[] cubeUuids;

    // Scratch, reused every frame
    private final Matrix4f[] pose;
    private final Matrix4f world = new Matrix4f();
    private final Matrix4f cube = new Matrix4f();
    private final Matrix3f rotation = new Matrix3f();
    private final Vector3f c0 = new Vector3f();
    private final Vector3f c1 = new Vector3f();
    private final Vector3f c2 = new Vector3f();
    private final Vector3f c3 = new Vector3f();
    private final Quaternionf quaternion = new Quaternionf();

    /**
     * Translation (3), scale (3) and left rotation (4) per cube: the pose right
     * now, and the one every viewer was last sent.
     */
    private final float[][] current;
    private final float[][] sent;
    private boolean posed = false;

    // Each particle cube right now, blocks from the base, and the tick it puffs next
    private final Vector3f[] effectAt;
    private final long[] nextPuff;
    // Scratch for the points of one puff's animation
    private final Vector3f[] shape = new Vector3f[ParticleMotion.MAX_POINTS];
    private long ticks = 0;

    private double lastX, lastY, lastZ;
    private double prevX, prevZ;
    private int resyncCooldown = 0;

    // The bone that glances around, -1 when the model has no head
    private final int headBone;
    private float lookYaw, lookPitch;
    private float lookTargetYaw, lookTargetPitch;
    private long nextGlanceNanos = System.nanoTime() + seconds(REST_MIN, REST_MAX);
    private long lastPoseNanos = System.nanoTime();

    public ModelRig(PetModels.Settings settings, IntSupplier nextEntityId, int intervalTicks) {
        this.settings = settings;
        this.model = settings.model();
        this.interval = Math.max(1, intervalTicks);

        this.baseId = nextEntityId.getAsInt();
        int cubes = settings.parts().size();
        this.cubeIds = new int[cubes];
        this.cubeUuids = new UUID[cubes];
        for (int i = 0; i < cubes; i++) {
            cubeIds[i] = nextEntityId.getAsInt();
            cubeUuids[i] = UUID.randomUUID();
        }

        this.pose = new Matrix4f[model.getBoneCount()];
        for (int i = 0; i < pose.length; i++) pose[i] = new Matrix4f();
        this.current = new float[cubes][10];
        this.sent = new float[cubes][10];
        this.headBone = model.getHeadBone();
        this.effectAt = new Vector3f[settings.effects().size()];
        for (int i = 0; i < effectAt.length; i++) effectAt[i] = new Vector3f();
        this.nextPuff = new long[effectAt.length];
        for (int i = 0; i < shape.length; i++) shape[i] = new Vector3f();
    }

    /**
     * The model's top at rest, in blocks above its feet.
     */
    public double getHeight() {
        return model.getHeight() / 16.0 * settings.scale();
    }

    public int[] getEntityIds() {
        int[] ids = new int[cubeIds.length + 1];
        ids[0] = baseId;
        System.arraycopy(cubeIds, 0, ids, 1, cubeIds.length);
        return ids;
    }

    /**
     * Show the model to one player, in the pose everyone else sees right now.
     */
    public void spawn(Player viewer, double x, double y, double z, float yaw) {
        if (!posed) {
            this.lastX = x;
            this.prevX = x;
            this.lastY = y;
            this.lastZ = z;
            this.prevZ = z;
            this.computePose(false, false, yaw);
            copyInto(current, sent);
        }

        Location at = new Location(x, y, z, 0f, 0f);
        send(viewer, new WrapperPlayServerSpawnEntity(baseId, baseUuid, EntityTypes.BLOCK_DISPLAY, at, 0f, 0, null));
        List<EntityData<?>> baseMeta = new ArrayList<>();
        // No block state, so the base itself draws nothing
        baseMeta.add(new EntityData<>(META_TELEPORT_DURATION, EntityDataTypes.INT, interval));
        send(viewer, new WrapperPlayServerEntityMetadata(baseId, baseMeta));

        int[] blockStates = settings.blockStates();
        for (int i = 0; i < cubeIds.length; i++) {
            send(viewer, new WrapperPlayServerSpawnEntity(cubeIds[i], cubeUuids[i], EntityTypes.BLOCK_DISPLAY, at, 0f, 0, null));
            List<EntityData<?>> meta = transformMeta(sent[i], false);
            meta.add(new EntityData<>(META_RIGHT_ROTATION, EntityDataTypes.QUATERNION, new Quaternion4f(0, 0, 0, 1)));
            meta.add(new EntityData<>(META_BLOCK_STATE, EntityDataTypes.BLOCK_STATE, blockStates[i]));
            send(viewer, new WrapperPlayServerEntityMetadata(cubeIds[i], meta));
        }
        send(viewer, new WrapperPlayServerSetPassengers(baseId, cubeIds.clone()));
    }

    /**
     * Forget where the model was, it is about to be spawned again at this
     * spot. Without this the first update would read the jump as walking
     * speed and teleport the base for nothing.
     */
    public void jumpTo(double x, double y, double z) {
        this.lastX = x;
        this.prevX = x;
        this.lastY = y;
        this.lastZ = z;
        this.prevZ = z;
    }

    /**
     * Move and animate the model one update further.
     *
     * @param flying the owner is in the air, the model plays 'fly' (or 'idle'
     *               when it has none) instead of walking on nothing
     * @return the packets every viewer of the model has to get
     */
    public List<PacketWrapper<?>> tick(double x, double y, double z, float yaw, boolean flying) {
        List<PacketWrapper<?>> packets = new ArrayList<>();

        double dx = x - prevX;
        double dz = z - prevZ;
        boolean walking = Math.sqrt(dx * dx + dz * dz) / (interval / 20.0) > WALK_SPEED;
        this.prevX = x;
        this.prevZ = z;

        double mx = x - lastX;
        double my = y - lastY;
        double mz = z - lastZ;
        if (mx * mx + my * my + mz * mz > MIN_MOVE_DELTA_SQ || resyncCooldown >= PERIODIC_RESYNC_TICKS) {
            packets.add(new WrapperPlayServerEntityTeleport(baseId, new Location(x, y, z, 0f, 0f), false));
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.resyncCooldown = 0;
        } else {
            this.resyncCooldown++;
        }

        this.computePose(walking, flying, yaw);
        for (int i = 0; i < cubeIds.length; i++) {
            if (!changed(current[i], sent[i])) continue;
            System.arraycopy(current[i], 0, sent[i], 0, 10);
            packets.add(new WrapperPlayServerEntityMetadata(cubeIds[i], transformMeta(sent[i], true)));
        }

        this.ticks += interval;
        List<PetModels.Effect> effects = settings.effects();
        for (int i = 0; i < effects.size(); i++) {
            PetModels.Effect effect = effects.get(i);
            if (ticks < nextPuff[i]) continue;
            nextPuff[i] = ticks + effect.every();
            Vector3f at = effectAt[i];
            int points = effect.motion().positions(ticks, shape);
            for (int p = 0; p < points; p++) {
                packets.add(PetParticles.puff(effect, x + at.x + shape[p].x, y + at.y + shape[p].y, z + at.z + shape[p].z));
            }
        }
        return packets;
    }

    private void computePose(boolean walking, boolean flying, float yaw) {
        this.posed = true;
        long now = System.nanoTime();
        float seconds = (now - startNanos) / 1e9f;

        BBModel.Animation animation;
        if (flying) {
            animation = model.getAnimation("fly");
            if (animation == null) animation = model.getAnimation("idle");
        } else {
            animation = model.getAnimation(walking ? "walk" : "idle");
        }

        this.glance(!walking && !flying, now);
        model.pose(animation, seconds, pose, headBone, lookYaw, lookPitch);

        // The model's front is turned to north (-z) first, then north is
        // turned to the pet's yaw, where 0 faces south
        world.identity()
                .rotateY((float) (Math.PI - Math.toRadians(yaw)))
                .scale(settings.scale() / 16f)
                .rotateY(settings.facing());

        List<BBModel.Cube> cubes = settings.parts();
        for (int i = 0; i < cubes.size(); i++) {
            BBModel.Cube c = cubes.get(i);
            cube.set(world).mul(pose[c.bone()]).mul(c.local());
            decompose(cube, current[i]);
        }

        // Particle cubes ride their bone like any cube, they are only never drawn
        List<PetModels.Effect> effects = settings.effects();
        for (int i = 0; i < effects.size(); i++) {
            BBModel.Emitter emitter = effects.get(i).emitter();
            cube.set(world).mul(pose[emitter.bone()]).transformPosition(emitter.point(), effectAt[i]);
        }
    }

    /**
     * Split a cube's matrix into what a display transformation can hold:
     * translation, rotation, then scale along the rotated axes.
     * <p>
     * ponytail: Gram-Schmidt drops any shear. Shear only appears when a bone
     * scales unevenly and something under it rotates, which reads as a slightly
     * off cube. A full SVD split into left and right rotation fixes that.
     */
    private void decompose(Matrix4f m, float[] out) {
        c0.set(m.m00(), m.m01(), m.m02());
        c1.set(m.m10(), m.m11(), m.m12());
        c2.set(m.m20(), m.m21(), m.m22());

        out[0] = m.m30();
        out[1] = m.m31();
        out[2] = m.m32();

        float sx = c0.length();
        // A bone hidden by scaling to nothing has no axes to take a rotation
        // from; dividing by that length would send NaN to the client, which
        // never draws the part again. Hidden is scale 0 and no rotation
        if (sx < 1e-5f) {
            hidden(out);
            return;
        }
        c0.div(sx);
        c1.fma(-c0.dot(c1), c0);
        float sy = c1.length();
        if (sy < 1e-5f) {
            hidden(out);
            return;
        }
        c1.div(sy);
        c0.cross(c1, c3);
        float sz = c2.dot(c3);

        rotation.set(c0, c1, c3);
        quaternion.setFromNormalized(rotation);

        out[3] = sx;
        out[4] = sy;
        out[5] = sz;
        out[6] = quaternion.x;
        out[7] = quaternion.y;
        out[8] = quaternion.z;
        out[9] = quaternion.w;
    }

    /**
     * Scale 0 and no rotation, the translation left as it is.
     */
    private static void hidden(float[] out) {
        Arrays.fill(out, 3, 9, 0f);
        out[9] = 1;
    }

    private List<EntityData<?>> transformMeta(float[] t, boolean interpolate) {
        List<EntityData<?>> meta = new ArrayList<>();
        // A delay of 0 is what makes the client start sliding to the new pose
        if (interpolate) meta.add(new EntityData<>(META_INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        meta.add(new EntityData<>(META_TRANSFORM_DURATION, EntityDataTypes.INT, interval));
        meta.add(new EntityData<>(META_TRANSLATION, EntityDataTypes.VECTOR3F,
                new com.github.retrooper.packetevents.util.Vector3f(t[0], t[1], t[2])));
        meta.add(new EntityData<>(META_SCALE, EntityDataTypes.VECTOR3F,
                new com.github.retrooper.packetevents.util.Vector3f(t[3], t[4], t[5])));
        meta.add(new EntityData<>(META_LEFT_ROTATION, EntityDataTypes.QUATERNION, new Quaternion4f(t[6], t[7], t[8], t[9])));
        return meta;
    }

    /**
     * Now and then turn the head somewhere else for a moment, only while
     * standing; moving brings it straight back to the front.
     */
    private void glance(boolean standing, long now) {
        float elapsed = Math.min(1, (now - lastPoseNanos) / 1e9f);
        this.lastPoseNanos = now;
        if (headBone < 0) return;

        boolean looking = lookTargetYaw != 0 || lookTargetPitch != 0;
        if (!standing) {
            lookTargetYaw = 0;
            lookTargetPitch = 0;
            // Start a fresh wait once it stops, not a glance the moment it does
            nextGlanceNanos = now + seconds(REST_MIN, REST_MAX);
        } else if (now >= nextGlanceNanos) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (looking) {
                lookTargetYaw = 0;
                lookTargetPitch = 0;
                nextGlanceNanos = now + seconds(REST_MIN, REST_MAX);
            } else {
                lookTargetYaw = (float) random.nextDouble(-LOOK_YAW, LOOK_YAW);
                lookTargetPitch = (float) random.nextDouble(-LOOK_PITCH, LOOK_PITCH);
                nextGlanceNanos = now + seconds(GLANCE_MIN, GLANCE_MAX);
            }
        }

        float step = Math.min(1, elapsed * LOOK_SPEED);
        lookYaw += (lookTargetYaw - lookYaw) * step;
        lookPitch += (lookTargetPitch - lookPitch) * step;
    }

    private static long seconds(double min, double max) {
        return (long) (ThreadLocalRandom.current().nextDouble(min, max) * 1e9);
    }

    private static boolean changed(float[] now, float[] before) {
        for (int i = 0; i < now.length; i++) {
            if (Math.abs(now[i] - before[i]) > CHANGE_EPSILON) return true;
        }
        return false;
    }

    private static void copyInto(float[][] from, float[][] to) {
        for (int i = 0; i < from.length; i++) System.arraycopy(from[i], 0, to[i], 0, from[i].length);
    }

    /**
     * Must stay a PacketWrapper, see PetDisplay#broadcast for why.
     */
    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

}
