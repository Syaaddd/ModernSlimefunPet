package id.alwination.slimefunpet.packet.display;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam S1 (SEAMS.md): the rules behind pet skins, no server.
 */
class PetSkinsTest {

    @Test
    void permissionNodeIsTheLowerCaseModelName() {
        assertThat(PetSkins.permission("RedPanda")).isEqualTo("modernslimefunpet.skin.redpanda");
    }

    @Test
    void availableSkinsAreTheLoadedModelsThePlayerMayUseInOrder() {
        Set<String> models = Set.of("redpanda", "cubee-air_dragon", "cubee-ebony_cat");
        List<String> available = PetSkins.available(models,
                permission -> permission.equals("modernslimefunpet.skin.redpanda")
                        || permission.equals("modernslimefunpet.skin.cubee-air_dragon"));
        assertThat(available).containsExactly("cubee-air_dragon", "redpanda");
    }

    /**
     * Seam S6: how a skin is shown in the menu comes from 'skins' in pets.yml,
     * a model with no entry is named after its file.
     */
    @Test
    void skinLooksComeFromTheConfigWithDefaultsFromTheFileName() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                skins:
                  redpanda:
                    display-name: '&cRed Panda'
                    material: ORANGE_TERRACOTTA
                  cubee-air_dragon:
                    material: not_a_block
                """);
        Map<String, PetSkins.Look> looks = PetSkins.loadLooks(config.getConfigurationSection("skins"));

        assertThat(Objects.requireNonNull(looks.get("redpanda")).displayName()).isEqualTo("&cRed Panda");
        assertThat(Objects.requireNonNull(looks.get("redpanda")).material()).isEqualTo(Material.ORANGE_TERRACOTTA);
        // An unknown material falls back, the name still comes from the file
        assertThat(Objects.requireNonNull(looks.get("cubee-air_dragon")).material()).isEqualTo(PetSkins.DEFAULT_ICON);

        PetSkins.Look unlisted = PetSkins.lookOf(looks, "cubee-ebony_cat");
        assertThat(unlisted.displayName()).isEqualTo("Cubee Ebony Cat");
        assertThat(unlisted.material()).isEqualTo(PetSkins.DEFAULT_ICON);
    }

    /**
     * Seam S6: a skin is tuned with the same keys as a pet's 'model' section,
     * read from its own entry under 'skins'. Left out, a key keeps the default
     * a pet's model has; out of range, it is pinned to the range.
     */
    @Test
    void skinEntryTunesTheModelLikeAPetsModelSection() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                skins:
                  redpanda:
                    display-name: '&cRed Panda'
                    material: ORANGE_TERRACOTTA
                    scale: 1
                    y-offset: 0.25
                    detail: 60
                    facing: west
                  cubee-air_dragon:
                    scale: 99
                    detail: -5
                """);
        PetModels.Options tuned = PetModels.options(config.getConfigurationSection("skins.redpanda"));
        assertThat(tuned.scale()).isEqualTo(1f);
        assertThat(tuned.yOffset()).isEqualTo(0.25f);
        assertThat(tuned.detail()).isEqualTo(60);
        assertThat(tuned.facing()).isEqualTo("west");

        PetModels.Options pinned = PetModels.options(config.getConfigurationSection("skins.cubee-air_dragon"));
        assertThat(pinned.scale()).isEqualTo(4f);
        assertThat(pinned.detail()).isZero();

        PetModels.Options defaults = PetModels.options(null);
        assertThat(defaults.scale()).isEqualTo(0.6f);
        assertThat(defaults.yOffset()).isZero();
        assertThat(defaults.detail()).isEqualTo(40);
        assertThat(defaults.facing()).isEqualTo("north");
    }

    @Test
    void aSkinOnlyDressesAPetWithoutAModelOfItsOwnAndOnlyWhileItExists() {
        Set<String> models = Set.of("redpanda");
        // A plain pet wearing a loaded skin
        assertThat(PetSkins.skinToDraw(false, "redpanda", models)).isEqualTo("redpanda");
        // No skin chosen
        assertThat(PetSkins.skinToDraw(false, null, models)).isNull();
        // The skin's file was removed since it was chosen
        assertThat(PetSkins.skinToDraw(false, "cubee-air_dragon", models)).isNull();
        // A pet with its own model keeps it
        assertThat(PetSkins.skinToDraw(true, "redpanda", models)).isNull();
    }
}
