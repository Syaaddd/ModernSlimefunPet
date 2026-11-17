package id.alwination.slimefunpet.packet.display;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam S6 (SEAMS.md): the 'blocks' overrides of a model, matched against the
 * red panda's real groups. Its tree:
 * <pre>
 *   root > head  (head, mouth x3)
 *               > ears > right_ear (ear), left_ear (ear)
 *        > body > body2 (body), tail (tail)
 *        > legs > front_left_leg (left_leg), back_left_leg (left_leg), ...
 * </pre>
 */
class BlockOverridesTest {

    private static BBModel model;

    @BeforeAll
    static void load() throws Exception {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(
                BlockOverridesTest.class.getResourceAsStream("/models/redpanda.bbmodel")), StandardCharsets.UTF_8)) {
            model = BBModel.parse("redpanda", reader, PetModels.PALETTE);
        }
    }

    private static List<Material> blocksOf(String cubeName, Map<String, Material> overrides) {
        return model.getCubes().stream()
                .filter(cube -> cube.name().equals(cubeName))
                .map(cube -> PetModels.blockFor(model, cube, overrides))
                .toList();
    }

    @Test
    void aCubeNameStillRecoloursEveryCubeOfThatName() {
        Map<String, Material> overrides = Map.of("ear", Material.SNOW_BLOCK);
        assertThat(blocksOf("ear", overrides)).containsOnly(Material.SNOW_BLOCK).hasSize(2);
        assertThat(blocksOf("head", overrides)).doesNotContain(Material.SNOW_BLOCK);
    }

    @Test
    void aGroupNameRecoloursEveryCubeInsideItAndItsSubGroups() {
        Map<String, Material> overrides = Map.of("head", Material.BLACK_CONCRETE);
        assertThat(blocksOf("head", overrides)).containsOnly(Material.BLACK_CONCRETE);
        assertThat(blocksOf("mouth", overrides)).containsOnly(Material.BLACK_CONCRETE).hasSize(3);
        // ears is a sub-group of head
        assertThat(blocksOf("ear", overrides)).containsOnly(Material.BLACK_CONCRETE);
        assertThat(blocksOf("body", overrides)).doesNotContain(Material.BLACK_CONCRETE);
    }

    @Test
    void aGroupPathPicksCubesInsideThatGroupOnly() {
        Map<String, Material> overrides = Map.of(
                "head.mouth", Material.PINK_CONCRETE,
                "legs.*", Material.GRAY_CONCRETE,
                "root.head.ears.right_ear.ear", Material.RED_CONCRETE);
        assertThat(blocksOf("mouth", overrides)).containsOnly(Material.PINK_CONCRETE);
        assertThat(blocksOf("left_leg", overrides)).containsOnly(Material.GRAY_CONCRETE).hasSize(2);
        assertThat(blocksOf("right_leg", overrides)).containsOnly(Material.GRAY_CONCRETE).hasSize(2);
        // Only the right ear, the left one keeps its own colour
        assertThat(blocksOf("ear", overrides)).containsOnlyOnce(Material.RED_CONCRETE);
    }

    @Test
    void theMostSpecificKeyWins() {
        Map<String, Material> overrides = Map.of(
                "head", Material.BLACK_CONCRETE,       // a group
                "ear", Material.WHITE_CONCRETE,        // a cube name beats a group
                "ears.ear", Material.RED_CONCRETE,     // a longer path beats a cube name
                "ears", Material.BLUE_CONCRETE);       // a deeper group beats an outer one
        assertThat(blocksOf("ear", overrides)).containsOnly(Material.RED_CONCRETE);
        assertThat(blocksOf("mouth", overrides)).containsOnly(Material.BLACK_CONCRETE);

        Map<String, Material> groupsOnly = Map.of("head", Material.BLACK_CONCRETE, "ears", Material.BLUE_CONCRETE);
        assertThat(blocksOf("ear", groupsOnly)).containsOnly(Material.BLUE_CONCRETE);
    }

    /**
     * Pins how Bukkit reads the keys back, which PetModels relies on: a
     * dotted key becomes nested sections, so only the leaves of getKeys(true)
     * are overrides, and '*' survives as a path part.
     */
    @Test
    void bukkitReadsDottedKeysBackAsLeafPaths() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                blocks:
                  cube: white_concrete
                  hat.cube: red_concrete
                  hat.*: black_concrete
                  head:
                    hat:
                      cube: blue_concrete
                """);
        ConfigurationSection blocks = Objects.requireNonNull(config.getConfigurationSection("blocks"));
        List<String> leaves = blocks.getKeys(true).stream().filter(key -> !blocks.isConfigurationSection(key)).toList();
        assertThat(leaves).containsExactlyInAnyOrder("cube", "hat.cube", "hat.*", "head.hat.cube");
        assertThat(blocks.getString("hat.*")).isEqualTo("black_concrete");
    }

    @Test
    void keysThatMatchNothingAreReported() {
        Map<String, Material> overrides = Map.of(
                "head", Material.BLACK_CONCRETE,
                "nothing", Material.BLACK_CONCRETE,
                "head.nope", Material.BLACK_CONCRETE,
                "legs.*", Material.BLACK_CONCRETE);
        assertThat(PetModels.unmatched(model, overrides)).containsExactlyInAnyOrder("nothing", "head.nope");
    }
}
