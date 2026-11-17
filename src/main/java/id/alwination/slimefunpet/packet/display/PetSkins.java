package id.alwination.slimefunpet.packet.display;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The rules behind pet skins: a pet without a model of its own can be dressed
 * in any loaded model the player has the permission for.
 * <p>
 * Pure, so it is tested without a server (seam S1). Who is asking and what
 * they may use come in as values.
 */
public final class PetSkins {

    private static final String PERMISSION_PREFIX = "modernslimefunpet.skin.";

    /**
     * The icon of a skin the config gives no icon for.
     */
    public static final Material DEFAULT_ICON = Material.ARMOR_STAND;

    /**
     * How a skin is shown in the menu.
     */
    public record Look(String displayName, Material material) {}

    private PetSkins() {
    }

    /**
     * The looks set in the 'skins' section of pets.yml, keyed by model file
     * name. A model left out gets one from {@link #lookOf}.
     */
    public static Map<String, Look> loadLooks(@Nullable ConfigurationSection skins) {
        Map<String, Look> looks = new HashMap<>();
        if (skins == null) return looks;
        for (String key : skins.getKeys(false)) {
            String model = key.toLowerCase(Locale.ROOT);
            String displayName = skins.getString(key + ".display-name", defaultName(model));
            Material material = Material.matchMaterial(skins.getString(key + ".material", ""));
            if (material == null) material = DEFAULT_ICON;
            looks.put(model, new Look(displayName, material));
        }
        return looks;
    }

    public static Look lookOf(Map<String, Look> looks, String model) {
        Look look = looks.get(model);
        return look != null ? look : new Look(defaultName(model), DEFAULT_ICON);
    }

    /**
     * 'cubee-ebony_cat' reads as 'Cubee Ebony Cat'.
     */
    private static String defaultName(String model) {
        StringBuilder name = new StringBuilder();
        for (String word : model.split("[-_ ]+")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    /**
     * The permission node that unlocks a model as a skin, one per model file.
     */
    public static String permission(String model) {
        return PERMISSION_PREFIX + model.toLowerCase(Locale.ROOT);
    }

    /**
     * The loaded models the player may wear, in name order.
     *
     * @param permitted whether the player holds a permission node
     */
    public static List<String> available(Collection<String> models, Predicate<String> permitted) {
        List<String> available = new ArrayList<>();
        for (String model : models) {
            if (permitted.test(permission(model))) available.add(model);
        }
        Collections.sort(available);
        return available;
    }

    /**
     * The model a pet is drawn with instead of its skull, from its skin.
     *
     * @param ownModel whether the pet has a model of its own, which always wins
     * @param skin     the skin on the pet, see PetPlayer#getSkin
     * @return the model file to draw, null for the pet's usual look
     */
    public static @Nullable String skinToDraw(boolean ownModel, @Nullable String skin, Collection<String> models) {
        if (ownModel || skin == null || !models.contains(skin)) return null;
        return skin;
    }

}
