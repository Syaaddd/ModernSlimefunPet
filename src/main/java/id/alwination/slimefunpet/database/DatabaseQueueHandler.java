package id.alwination.slimefunpet.database;

import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.managers.ProfileManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Write-behind saving for pets and profiles, keeping ModernMineGens' {@code addObjectToSave} entry
 * point.
 * <ul>
 *   <li>Callers mark a pet or profile dirty. Every {@code period} ticks the main thread copies the
 *       dirty rows ({@link PetStorage#petRow}) and hands the copies to the writer.</li>
 *   <li>{@link #saveNow()} does the same at once: used right after an item was taken, so a crash
 *       cannot lose what the player paid for.</li>
 *   <li>The writer runs the batches one at a time, in the order they were made, on an async task,
 *       so an older copy can never land after a newer one. A failed batch marks its rows dirty again
 *       so the next flush retries them with fresh values.</li>
 *   <li>{@link #terminate()} flushes and drains every batch on the calling thread (plugin disable).</li>
 * </ul>
 * The dirty sets are swapped out before they are read, so a mark made during a flush is kept for
 * the next one (the handoff's queue wiped those).
 */
public final class DatabaseQueueHandler {

    private static final Set<UUID> DIRTY_PETS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> DIRTY_PROFILES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> DELETED_PETS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Runnable> BATCHES = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean WRITER_RUNNING = new AtomicBoolean();
    private static final Object WRITE_LOCK = new Object();

    private static @Nullable PetStorage storage;
    private static @Nullable ProfileManager profiles;
    private static @Nullable HandleTask task;

    private DatabaseQueueHandler() {
    }

    /** Starts the periodic flush on the main thread. */
    public static void start(PetStorage petStorage, ProfileManager profileManager, long periodTicks) {
        storage = petStorage;
        profiles = profileManager;
        HandleTask previous = task;
        task = Executor.syncTimer(periodTicks, periodTicks, DatabaseQueueHandler::flush);
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Marks a pet ({@link PetPlayer}) or a profile ({@link PlayerProfile}) for the next save. */
    public static void addObjectToSave(Object object) {
        if (object instanceof PetPlayer pet) {
            DIRTY_PETS.add(pet.getUniqueId());
        } else if (object instanceof PlayerProfile profile) {
            DIRTY_PROFILES.add(profile.getOwner());
        }
    }

    /** The pet row goes away at the next save; a pending save of it is dropped. */
    public static void deletePet(UUID uuid) {
        DIRTY_PETS.remove(uuid);
        DELETED_PETS.add(uuid);
    }

    /** Flushes now instead of at the next period. Main thread. */
    public static void saveNow() {
        flush();
    }

    /** Copies everything dirty and queues it for the writer. Main thread. */
    public static void flush() {
        Runnable batch = takeBatch();
        if (batch != null) {
            BATCHES.add(batch);
            startWriter();
        }
    }

    /** Stops the timer, then writes every pending batch on this thread. Plugin disable. */
    public static void terminate() {
        HandleTask running = task;
        task = null;
        if (running != null) {
            running.cancel();
        }
        Runnable last = takeBatch();
        if (last != null) {
            BATCHES.add(last);
        }
        drain();
    }

    private static @Nullable Runnable takeBatch() {
        PetStorage target = storage;
        ProfileManager profileManager = profiles;
        if (target == null || profileManager == null) {
            return null;
        }
        List<UUID> petIds = take(DIRTY_PETS);
        List<UUID> ownerIds = take(DIRTY_PROFILES);
        List<UUID> deleted = take(DELETED_PETS);
        if (petIds.isEmpty() && ownerIds.isEmpty() && deleted.isEmpty()) {
            return null;
        }
        List<Object[]> petRows = new ArrayList<>();
        for (UUID uuid : petIds) {
            PetPlayer pet = PetManager.getPetPlayer(uuid);
            if (pet != null) {
                petRows.add(PetStorage.petRow(pet));
            }
        }
        List<Object[]> profileRows = new ArrayList<>();
        for (UUID owner : ownerIds) {
            PlayerProfile profile = profileManager.findProfile(owner);
            if (profile != null) {
                profileRows.add(PetStorage.profileRow(profile));
            }
        }
        return () -> {
            try {
                target.writeRows(petRows, profileRows, deleted);
            } catch (SQLException exception) {
                Logger.severe("Saving " + petRows.size() + " pets and " + profileRows.size()
                        + " profiles failed, retrying at the next save: " + exception.getMessage());
                DIRTY_PETS.addAll(petIds);
                DIRTY_PROFILES.addAll(ownerIds);
                DELETED_PETS.addAll(deleted);
            }
        };
    }

    private static List<UUID> take(Set<UUID> dirty) {
        List<UUID> taken = new ArrayList<>(dirty);
        dirty.removeAll(taken);
        return taken;
    }

    private static void startWriter() {
        if (WRITER_RUNNING.compareAndSet(false, true)) {
            Executor.async(() -> {
                drain();
                WRITER_RUNNING.set(false);
                // A batch queued between the last poll and the flag reset still gets a writer
                if (!BATCHES.isEmpty()) {
                    startWriter();
                }
            });
        }
    }

    /** Runs queued batches in order; polling under the lock keeps them in order across threads. */
    private static void drain() {
        while (true) {
            synchronized (WRITE_LOCK) {
                Runnable batch = BATCHES.poll();
                if (batch == null) {
                    return;
                }
                batch.run();
            }
        }
    }
}
