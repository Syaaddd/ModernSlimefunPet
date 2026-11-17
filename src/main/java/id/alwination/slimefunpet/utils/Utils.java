package id.alwination.slimefunpet.utils;

import com.google.common.base.Strings;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Small helpers the pet menus and evolution requirements share: item requirements, bars, sounds. */
public final class Utils {

    public static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .hexColors()
            .extractUrls()
            .build();

    private Utils() {
    }

    /**
     * Whether a stack is the item a requirement is asking for.
     * <p>
     * A requirement that names no item at all asks for the plain material, so
     * it counts the stone a player dug up but never a renamed one. A stack
     * somebody bothered to name is somebody's custom item, and taking those to
     * pay for a plain material requirement would be the surprising thing to do.
     */
    private static boolean matchesRequirement(@Nullable ItemStack stack, Material material, @Nullable String serializedName) {
        if (stack == null || stack.getType() != material) return false;

        boolean named = stack.hasItemMeta() && stack.getItemMeta().hasDisplayName();
        if (serializedName == null) return !named;
        if (!named) return false;

        return serializedName.equals(LEGACY_SERIALIZER.serialize(stack.getItemMeta().displayName()));
    }

    /**
     * The name to match on, null when the requirement did not give one and any
     * unnamed item of the material will do.
     */
    private static @Nullable String serializedRequirementName(@Nullable String displayName) {
        if (displayName == null || displayName.isBlank()) return null;
        return LEGACY_SERIALIZER.serialize(LEGACY_SERIALIZER.deserialize(displayName));
    }

    public static int countItemRequirement(Player player, @Nullable String materialName, @Nullable String displayName) {
        if (materialName == null) return 0;
        Material material = Material.matchMaterial(materialName);
        if (material == null) return 0;

        String serialized = serializedRequirementName(displayName);
        int total = 0;
        for (ItemStack stack : player.getInventory()) {
            if (stack != null && matchesRequirement(stack, material, serialized)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public static void removeItemRequirement(Player player, @Nullable String materialName, @Nullable String displayName, int amount) {
        if (materialName == null || amount <= 0) return;
        Material material = Material.matchMaterial(materialName);
        if (material == null) return;

        String serialized = serializedRequirementName(displayName);
        int remaining = amount;
        for (ItemStack stack : player.getInventory()) {
            if (stack == null || !matchesRequirement(stack, material, serialized)) continue;

            int stackAmount = stack.getAmount();
            if (stackAmount > remaining) {
                stack.setAmount(stackAmount - remaining);
                return;
            }
            remaining -= stackAmount;
            stack.setAmount(0);
            if (remaining <= 0) return;
        }
    }

    public static String getProgressBar(int current, int max, int totalBars, char symbol, ChatColor completedColor, ChatColor notCompletedColor){
        float percent = (float) current/max;
        // Past the max (exp over a level cost a config lowered, food over a lowered
        // max) or a max of 0 must still draw a full or empty bar, never a negative count
        int progressBars = Math.clamp((long) (totalBars * percent), 0, totalBars);

        return Strings.repeat("" + completedColor + symbol, progressBars) +
                Strings.repeat("" + notCompletedColor + symbol, totalBars - progressBars);
    }

    public static void success(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    public static void fail(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    public static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
    }

    public static void click(Player player, float volume) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, volume, 1.0f);
    }

}
