package id.alwination.slimefunpet.pets;

import static org.assertj.core.api.Assertions.assertThat;

import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.stats.PetStatCondition;
import id.alwination.slimefunpet.pets.stats.PetStatKind;
import java.io.File;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shipped pets.yml and menus parse and only name things the code knows. */
class ShippedConfigTest {

    private static final File RESOURCES = new File("src/main/resources");

    private static YamlConfiguration load(String path) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(RESOURCES, path));
        return config;
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        return Objects.requireNonNull(parent.getConfigurationSection(path), path);
    }

    @Test
    void everyPoolStatHasAKnownKindAndWhatItsKindNeeds() throws Exception {
        ConfigurationSection pool = section(load("pets.yml"), "stats.pool");
        assertThat(pool.getKeys(false)).contains("max_health", "armor", "attack_damage", "movement_speed",
                "knockback_resistance", "luck", "attack_speed", "haste", "regeneration", "resistance",
                "jump_boost", "night_vision", "fire_resistance", "water_breathing", "speed");
        for (String key : pool.getKeys(false)) {
            ConfigurationSection stat = section(pool, key);
            PetStatKind kind = PetStatKind.fromString(stat.getString("kind", ""));
            assertThat(kind).as(key).isNotNull();
            assertThat(PetStatCondition.fromString(stat.getString("condition", "ALWAYS"))).as(key).isNotNull();
            assertThat(stat.getDouble("min")).as(key).isEqualTo(stat.getDouble("max"));
            if (kind == PetStatKind.ATTRIBUTE) {
                assertThat(stat.getString("attribute")).as(key).isNotBlank();
                assertThat(stat.getString("operation")).as(key).isIn("ADD_NUMBER", "ADD_SCALAR");
            }
            if (kind == PetStatKind.POTION_EFFECT) {
                assertThat(stat.getString("potion-effect")).as(key).isNotBlank();
            }
            assertThat(stat.getString("type", "GOOD")).as(key).isEqualTo("GOOD");
        }
    }

    @Test
    void knockbackResistanceIsAFlatFivePercent() throws Exception {
        ConfigurationSection stat = section(load("pets.yml"), "stats.pool.knockback_resistance");

        assertThat(stat.getString("operation")).isEqualTo("ADD_NUMBER");
        assertThat(stat.getBoolean("percent")).isTrue();
        assertThat(stat.getDouble("max")).isEqualTo(5);
    }

    @Test
    void everyEvolutionPointsAtAShippedPetAndStone() throws Exception {
        YamlConfiguration config = load("pets.yml");
        ConfigurationSection pets = section(config, "pets");
        Set<String> stones = section(config, "items.evolution").getKeys(false);
        for (String id : pets.getKeys(false)) {
            ConfigurationSection evolution = pets.getConfigurationSection(id + ".evolution");
            if (evolution == null) {
                continue;
            }
            assertThat(pets.getKeys(false)).as(id).contains(evolution.getString("into"));
            assertThat(stones).as(id).containsAll(evolution.getStringList("item"));
        }
    }

    @Test
    void noBoosterIsShipped() throws Exception {
        assertThat(section(load("pets.yml"), "items").getKeys(false)).doesNotContain("booster");
        assertThat(load("gui/pet_evolution_gui.yml").saveToString()).doesNotContainIgnoringCase("booster");
    }

    @Test
    void everyItemModeMenuIsShipped() throws Exception {
        for (PetItemType type : PetItemType.values()) {
            if (type.getGuiFile() != null) {
                assertThat(load("gui/" + type.getGuiFile()).getKeys(false)).as(type.name()).isNotEmpty();
            }
        }
    }

    /** SLIMEFUN-VECTORS.md section 7: vanilla crafting ingredients a Vanilla Auto Crafter can eat. */
    @Test
    void noPetItemUsesAMaterialSlimefunCanConsume() throws Exception {
        Set<String> unsafe = Set.of("SUGAR", "HONEYCOMB", "HEART_OF_THE_SEA", "NETHER_STAR", "PAPER", "BOOK",
                "AMETHYST_SHARD", "FLINT", "HAY_BLOCK", "PLAYER_HEAD");
        ConfigurationSection items = section(load("pets.yml"), "items");
        for (String type : items.getKeys(false)) {
            ConfigurationSection kind = section(items, type);
            for (String id : kind.getKeys(false)) {
                assertThat(kind.getString(id + ".material", "")).as(type + "." + id).isNotIn(unsafe);
            }
        }
    }
}
