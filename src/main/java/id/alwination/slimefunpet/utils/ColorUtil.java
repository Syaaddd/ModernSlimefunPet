package id.alwination.slimefunpet.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mendukung:
 *  - Warna legacy: &0..&f, &k, &l, &m, &n, &o, &r
 *  - Hex: &#RRGGBB atau #RRGGBB
 *  - Bentuk &x&R&R&G&G&B&B (tetap dihormati; akan diproses normal)
 */
public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)(?:&?#)([0-9A-F]{6})");
    /**
     * The '&x&R&R&G&G&B&B' form spigot writes hex in, which a client does not
     * understand inside a component.
     */
    private static final Pattern UNUSUAL_HEX_PATTERN = Pattern.compile("(?i)&x((?:&[0-9A-F]){6})");
    /**
     * A hex written on its own, '#RRGGBB' with no '&' in front of it.
     */
    private static final Pattern BARE_HEX_PATTERN = Pattern.compile("(?i)(?<!&)#([0-9A-F]{6})");
    public static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .hexColors()
            .extractUrls()
            .build();

    private ColorUtil() {}

    /** Ubah semua &-codes + hex ke '§' codes (Bukkit-ready). */
    public static String translateToSectionColors(String input) {
        if (input == null || input.isEmpty()) return input;
        String withAmpersandHexExpanded = expandHexToAmpersandXForm(input);
        // Setelah di-expand, terjemahkan & menjadi §
        return ChatColor.translateAlternateColorCodes('&', withAmpersandHexExpanded);
    }

    /** Ubah string &-codes + hex menjadi Adventure Component. */
    public static Component toComponent(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        return LEGACY_SERIALIZER.deserialize(normalizeCodes(input)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Bring every way a color can be written in this plugin down to the one
     * form the serializer reads, '&' codes with '&#RRGGBB' hex.
     * <p>
     * A string can arrive here already translated to '§' codes, with its hex
     * spelled out as '§x§R§R§G§G§B§B', which is what spigot and MDLib write.
     * That form only means anything to a server, a client shown a component
     * holding it just drops the color, which is why an RGB hologram or item
     * name came out plain. It is folded back into a real hex here so the
     * component carries the color itself.
     */
    private static String normalizeCodes(String input) {
        // '§' and '&' mean the same thing to us, and only '&' is read below
        String normalized = input.replace('§', '&');

        normalized = UNUSUAL_HEX_PATTERN.matcher(normalized)
                .replaceAll(match -> "&#" + match.group(1).replace("&", ""));

        // A bare '#RRGGBB', which is how the configs of this plugin write hex
        return BARE_HEX_PATTERN.matcher(normalized).replaceAll("&#$1");
    }

    /** Serialize Adventure Component ke legacy '§' string. */
    public static String fromComponent(Component component) {
        if (component == null) return "";
        return LEGACY_SERIALIZER.serialize(component);
    }

    /**
     * What a player reads on an item: its material, name and lore flattened to
     * '&' codes. Tells two items apart by how they look, not by how their text
     * happens to be encoded.
     * <p>
     * MDLib up to 2.3.8 set names and lore as legacy strings, which the server
     * keeps as an unstyled line holding styled pieces. MDLib 2.4 sets the styled
     * component itself. One config line comes out as two components that read
     * exactly the same, so comparing the components themselves splits one item
     * into two that never stack or match.
     */
    public static String itemText(Material material, @Nullable Component name, @Nullable List<Component> lore) {
        StringBuilder text = new StringBuilder(material.name()).append('\n').append(fromComponent(name));
        if (lore != null) {
            for (Component line : lore) {
                text.append('\n').append(fromComponent(line));
            }
        }
        return text.toString();
    }

    public static String itemText(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return itemText(stack.getType(), null, null);
        return itemText(stack.getType(), meta.displayName(), meta.lore());
    }

    /** Batch helper: terapkan translateToSectionColors ke list. */
    public static List<String> translateListToSectionColors(List<String> lines) {
        if (lines == null) return null;
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(translateToSectionColors(line));
        }
        return result;
    }

    /** Hapus semua warna (legacy + hex) ke text polos. */
    public static String stripColors(String input) {
        if (input == null) return null;
        // Pastikan hex sudah di-expand dulu biar §-based
        String colored = translateToSectionColors(input);
        return ChatColor.stripColor(colored);
    }

    // === Internal ===

    /**
     * Expand hex (#RRGGBB atau &#RRGGBB) jadi bentuk &x&R&R&G&G&B&B
     * yang dikenali oleh ChatColor.translateAlternateColorCodes.
     */
    private static String expandHexToAmpersandXForm(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1); // RRGGBB
            String replacement =
                    "&x&" + hex.charAt(0) +
                            "&"  + hex.charAt(1) +
                            "&"  + hex.charAt(2) +
                            "&"  + hex.charAt(3) +
                            "&"  + hex.charAt(4) +
                            "&"  + hex.charAt(5);
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
