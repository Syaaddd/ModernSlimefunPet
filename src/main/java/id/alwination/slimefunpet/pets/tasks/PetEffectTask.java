package id.alwination.slimefunpet.pets.tasks;

import com.muhammaddaffa.mdlib.task.handleTask.HandleTask;
import com.muhammaddaffa.mdlib.utils.Executor;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.PetSettings;
import id.alwination.slimefunpet.pets.PlayerProfile;
import id.alwination.slimefunpet.pets.listeners.PetActivityListener;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.stats.PetStat;
import id.alwination.slimefunpet.pets.stats.PetStatCondition;
import id.alwination.slimefunpet.pets.stats.PetStatDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * Gives the owner what their equipped, living pet's stats do: attribute modifiers, potion effects
 * with their condition, and the HEAL stat. Main thread, every 'stats.effect-interval' ticks, and at
 * once whenever the pet display is rebuilt (equip, unequip, death, evolve), so a pet that goes away
 * takes its stats with it straight away.
 * <ul>
 *   <li>Attributes are transient modifiers (never saved into the player file), one per attribute and
 *       operation, keyed {@code modernslimefunpet:pet_<attribute>_<operation>}.</li>
 *   <li>Effects last 'duration-seconds' and are refreshed before they run out. An effect from
 *       somewhere else that is stronger, or as strong and longer, is left alone. When a condition
 *       stops holding, the effect this task gave is taken off.</li>
 *   <li>Effect levels and the heal are never scaled by pet level; attributes are.</li>
 * </ul>
 */
public class PetEffectTask {

    /** What one modifier this task put on a player was. */
    private record AppliedModifier(Attribute attribute, double amount) {
    }

    private final PetActivityListener activity;
    private final Map<UUID, Map<NamespacedKey, AppliedModifier>> appliedModifiers = new HashMap<>();
    private final Map<UUID, Map<PotionEffectType, Integer>> appliedEffects = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastHeal = new HashMap<>();
    private @Nullable HandleTask task;

    public PetEffectTask(PetActivityListener activity) {
        this.activity = activity;
    }

    public void start() {
        if (this.task != null) {
            this.task.cancel();
        }
        long interval = PetSettings.getEffectIntervalTicks();
        this.task = Executor.syncTimer(interval, interval, this::tick);
    }

    /** Stops the timer and takes every modifier and effect this task gave off the online players. */
    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.applyAttributes(player, Map.of());
            this.applyEffects(player, Map.of());
        }
        this.appliedModifiers.clear();
        this.appliedEffects.clear();
        this.lastHeal.clear();
    }

    /** Forgets a player who quit; transient modifiers leave with them. */
    public void forget(UUID player) {
        this.appliedModifiers.remove(player);
        this.appliedEffects.remove(player);
        this.lastHeal.remove(player);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.refresh(player);
        }
    }

    /** Applies exactly what the player's pets give right now. Main thread. */
    public void refresh(Player player) {
        long now = System.currentTimeMillis();
        Map<NamespacedKey, AppliedModifier> modifiers = new HashMap<>();
        Map<PotionEffectType, int[]> effects = new HashMap<>();
        List<PetStat> heals = new ArrayList<>();
        PlayerProfile profile = ModernSlimefunPet.getInstance().getProfileManager().findProfile(player.getUniqueId());
        if (profile != null && !player.isDead()) {
            double healthPercent = healthPercent(player);
            for (UUID uuid : profile.getEquippedPets()) {
                PetPlayer petPlayer = PetManager.getPetPlayer(uuid);
                if (petPlayer == null || !petPlayer.isActive()) {
                    continue;
                }
                Pet pet = ModernSlimefunPet.getInstance().getPetManager().getPet(petPlayer.getId());
                if (pet == null) {
                    continue;
                }
                for (PetStat stat : petPlayer.getActiveStats(pet)) {
                    PetStatDefinition definition = stat.getDefinition();
                    if (definition == null) {
                        continue;
                    }
                    switch (definition.kind()) {
                        case ATTRIBUTE -> this.addModifier(modifiers, definition,
                                attributeAmount(petPlayer.getScaledValue(stat), definition.percent()));
                        case POTION_EFFECT -> {
                            boolean holds = conditionHolds(definition.condition(), healthPercent, definition.healthBelow(),
                                    now, this.activity.getLastCombat(player.getUniqueId()),
                                    this.activity.getLastMining(player.getUniqueId()),
                                    PetSettings.getCombatSeconds(), PetSettings.getMiningSeconds());
                            if (holds && definition.potionEffect() != null) {
                                int amplifier = Math.max(0, (int) stat.value() - 1);
                                effects.merge(definition.potionEffect(), new int[] {amplifier, definition.durationTicks()},
                                        (a, b) -> new int[] {Math.max(a[0], b[0]), Math.max(a[1], b[1])});
                            }
                        }
                        case HEAL -> heals.add(stat);
                        default -> {
                            // PET_EXP and FOOD_SAVER work on the pet, read by the food task
                        }
                    }
                }
            }
        }
        this.applyAttributes(player, modifiers);
        this.applyEffects(player, effects);
        this.heal(player, heals, now);
    }

    private void addModifier(Map<NamespacedKey, AppliedModifier> modifiers, PetStatDefinition definition, double amount) {
        Attribute attribute = definition.attribute();
        AttributeModifier.Operation operation = definition.operation();
        if (attribute == null || operation == null) {
            return;
        }
        NamespacedKey key = new NamespacedKey(ModernSlimefunPet.getInstance(),
                "pet_" + attribute.getKey().getKey() + "_" + operation.name().toLowerCase(Locale.ROOT));
        modifiers.merge(key, new AppliedModifier(attribute, amount),
                (a, b) -> new AppliedModifier(a.attribute(), a.amount() + b.amount()));
    }

    private void applyAttributes(Player player, Map<NamespacedKey, AppliedModifier> wanted) {
        Map<NamespacedKey, AppliedModifier> applied = this.appliedModifiers.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        for (Map.Entry<NamespacedKey, AppliedModifier> entry : new ArrayList<>(applied.entrySet())) {
            AppliedModifier want = wanted.get(entry.getKey());
            if (want != null && want.amount() == entry.getValue().amount()) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(entry.getValue().attribute());
            if (instance != null) {
                instance.removeModifier(entry.getKey());
            }
            applied.remove(entry.getKey());
        }
        for (Map.Entry<NamespacedKey, AppliedModifier> entry : wanted.entrySet()) {
            AttributeInstance instance = player.getAttribute(entry.getValue().attribute());
            if (instance == null) {
                continue;
            }
            // Asked of the player, not of the map: a respawn drops transient modifiers (B11 re-applies them)
            AttributeModifier existing = instance.getModifier(entry.getKey());
            if (existing != null && existing.getAmount() == entry.getValue().amount()) {
                applied.put(entry.getKey(), entry.getValue());
                continue;
            }
            AttributeModifier.Operation operation = operationOf(entry.getKey());
            instance.removeModifier(entry.getKey());
            instance.addTransientModifier(new AttributeModifier(entry.getKey(), entry.getValue().amount(), operation));
            applied.put(entry.getKey(), entry.getValue());
        }
        // Losing max health must not leave the player above their new max
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && !player.isDead() && player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
    }

    private static AttributeModifier.Operation operationOf(NamespacedKey key) {
        return key.getKey().endsWith("_add_scalar") ? AttributeModifier.Operation.ADD_SCALAR
                : key.getKey().endsWith("_multiply_scalar_1") ? AttributeModifier.Operation.MULTIPLY_SCALAR_1
                : AttributeModifier.Operation.ADD_NUMBER;
    }

    private void applyEffects(Player player, Map<PotionEffectType, int[]> wanted) {
        Map<PotionEffectType, Integer> applied = this.appliedEffects.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        for (Map.Entry<PotionEffectType, int[]> entry : wanted.entrySet()) {
            int amplifier = entry.getValue()[0];
            int duration = entry.getValue()[1];
            PotionEffect current = player.getPotionEffect(entry.getKey());
            boolean apply = current == null
                    || shouldReplace(current.getAmplifier(), current.getDuration(), current.isInfinite(), amplifier, duration);
            if (apply) {
                player.addPotionEffect(new PotionEffect(entry.getKey(), duration, amplifier, true, false, true));
                applied.put(entry.getKey(), amplifier);
            }
        }
        // A condition stopped holding (or the pet went away): take off what this task gave
        for (Map.Entry<PotionEffectType, Integer> entry : new ArrayList<>(applied.entrySet())) {
            if (wanted.containsKey(entry.getKey())) {
                continue;
            }
            PotionEffect current = player.getPotionEffect(entry.getKey());
            if (current != null && current.isAmbient() && !current.isInfinite()
                    && current.getAmplifier() == entry.getValue()) {
                player.removePotionEffect(entry.getKey());
            }
            applied.remove(entry.getKey());
        }
    }

    private void heal(Player player, List<PetStat> heals, long now) {
        if (heals.isEmpty() || player.isDead()) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        Map<String, Long> last = this.lastHeal.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        for (PetStat stat : heals) {
            PetStatDefinition definition = stat.getDefinition();
            if (definition == null) {
                continue;
            }
            double max = maxHealth.getValue();
            if (!healDue(player.getHealth(), max, definition.healthBelow(), now,
                    last.getOrDefault(stat.key(), 0L), definition.intervalSeconds())) {
                continue;
            }
            player.setHealth(Math.min(max, player.getHealth() + stat.value()));
            last.put(stat.key(), now);
        }
    }

    private static double healthPercent(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20 : maxHealth.getValue();
        return max <= 0 ? 100 : player.getHealth() / max * 100;
    }

    /** Whether an effect's condition holds right now. */
    static boolean conditionHolds(PetStatCondition condition, double healthPercent, double healthBelow, long now,
                                  long lastCombat, long lastMining, int combatSeconds, int miningSeconds) {
        return switch (condition) {
            case ALWAYS -> true;
            case MINING -> now - lastMining < miningSeconds * 1000L;
            case HEALTH_BELOW -> healthPercent < healthBelow;
            case OUT_OF_COMBAT -> now - lastCombat >= combatSeconds * 1000L;
        };
    }

    /** The amount a modifier adds: the stat value, divided by 100 when it is written as a percent. */
    static double attributeAmount(double value, boolean percent) {
        return percent ? value / 100.0 : value;
    }

    /**
     * Whether the pet's effect may replace the one the player has: never a stronger one, never an
     * endless one, and at the same level only when the current one runs out sooner.
     */
    static boolean shouldReplace(int currentAmplifier, int currentDuration, boolean currentInfinite,
                                 int amplifier, int duration) {
        if (currentInfinite || currentAmplifier > amplifier) {
            return false;
        }
        return currentAmplifier < amplifier || currentDuration < duration;
    }

    /** Whether a HEAL stat heals now: below its health line and its interval has passed. */
    static boolean healDue(double health, double maxHealth, double healthBelow, long now, long lastHeal, int intervalSeconds) {
        if (health <= 0 || maxHealth <= 0 || health >= maxHealth) {
            return false;
        }
        return health / maxHealth * 100 < healthBelow && now - lastHeal >= intervalSeconds * 1000L;
    }
}
