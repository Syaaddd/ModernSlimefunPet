package id.alwination.slimefunpet.afk;

import com.muhammaddaffa.mdlib.utils.Logger;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.pets.PetMood;
import org.bukkit.configuration.file.FileConfiguration;

/** The 'afk-xp' and 'mood' sections of pets.yml. */
public final class AfkSettings {

    private static boolean enabled = true;
    private static long idleMillis = 3 * 60_000L;
    private static long intervalMillis = 60 * 60_000L;
    private static int xpPerInterval = 30;
    private static int dailyCap = 240;
    private static PetMood requiredMood = PetMood.HAPPY;
    private static double stayMaxDistance = 16;
    private static boolean log = true;
    private static double happyAt = 60;
    private static double neutralAt = 25;

    private AfkSettings() {
    }

    public static void load() {
        FileConfiguration config = ModernSlimefunPet.PETS_CONFIG.getConfig();
        enabled = config.getBoolean("afk-xp.enabled", true);
        idleMillis = Math.max(1, config.getInt("afk-xp.idle-minutes", 3)) * 60_000L;
        intervalMillis = Math.max(1, config.getInt("afk-xp.interval-minutes", 60)) * 60_000L;
        xpPerInterval = Math.max(0, config.getInt("afk-xp.xp-per-interval", 30));
        dailyCap = Math.max(0, config.getInt("afk-xp.daily-cap", 240));
        String moodName = config.getString("afk-xp.required-mood", "HAPPY");
        PetMood mood = PetMood.fromString(moodName);
        if (mood == null) {
            Logger.warning("pets.yml afk-xp.required-mood '" + moodName + "' is not HAPPY, NEUTRAL or SAD; using HAPPY");
            mood = PetMood.HAPPY;
        }
        requiredMood = mood;
        stayMaxDistance = Math.max(0, config.getDouble("afk-xp.stay-max-distance", 16));
        log = config.getBoolean("afk-xp.log-payouts", true);
        happyAt = config.getDouble("mood.happy-at", 60);
        neutralAt = config.getDouble("mood.neutral-at", 25);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static long getIdleMillis() {
        return idleMillis;
    }

    public static long getIntervalMillis() {
        return intervalMillis;
    }

    public static int getXpPerInterval() {
        return xpPerInterval;
    }

    public static int getDailyCap() {
        return dailyCap;
    }

    public static PetMood getRequiredMood() {
        return requiredMood;
    }

    public static double getStayMaxDistance() {
        return stayMaxDistance;
    }

    public static boolean isLogPayouts() {
        return log;
    }

    public static PetMood moodOf(double foodPercent) {
        return PetMood.of(foodPercent, happyAt, neutralAt);
    }
}
