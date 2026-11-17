package id.alwination.slimefunpet.pets.stats;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.muhammaddaffa.mdlib.utils.Logger;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the stats of a pet into the json stored in the 'stats' column and back.
 */
public final class PetStatSerializer {

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<PetStat>>() {
    }.getType();

    private PetStatSerializer() {
    }

    public static String serialize(List<PetStat> stats) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        return GSON.toJson(stats, LIST_TYPE);
    }

    // A damaged stats column must load as no stats, never stop the pet from loading.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static List<PetStat> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<PetStat> stats = GSON.fromJson(json, LIST_TYPE);
            if (stats == null) {
                return new ArrayList<>();
            }
            // Drop the stats that were removed from the config in the meantime
            stats.removeIf(stat -> stat == null || stat.key() == null);
            return stats;
        } catch (Exception ex) {
            Logger.severe("Failed to read the pet stats '" + json + "': " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Read the stats and only keep the ones that still exist in the config.
     */
    public static List<PetStat> deserializeValid(String json) {
        List<PetStat> stats = deserialize(json);
        stats.removeIf(stat -> !stat.isValid());
        return stats;
    }

    public static List<PetStat> emptyList() {
        return Collections.emptyList();
    }

}
