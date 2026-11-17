package id.alwination.slimefunpet;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.mckit.archrules.MinecraftRules;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("id.alwination.slimefunpet");

    static Stream<ArchRule> rules() {
        return MinecraftRules.all().stream();
    }

    @Test
    void productionClassesAreImported() {
        assertThat(PRODUCTION).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("rules")
    void productionCodeFollowsMinecraftRules(ArchRule rule) {
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    /**
     * Classes allowed to keep a world object in a field, each with its reason.
     * <ul>
     *   <li>PetDisplay: one display per online owner, holding that owner. The display manager
     *       destroys it on quit, world change and plugin disable, so it never outlives the player.</li>
     *   <li>Every menu in pets.gui: a FastInv menu holds its viewer (and the pet list menu the
     *       player whose pets it shows) only while its inventory is open; closing drops the menu,
     *       and onDisable closes every menu first.</li>
     * </ul>
     */
    private static final String MENU_PACKAGE = "id.alwination.slimefunpet.pets.gui";

    private static final Set<String> WORLD_OBJECT_FIELD_EXCEPTIONS = Set.of(
            "id.alwination.slimefunpet.packet.display.PetDisplay");

    @Test
    void noFieldHoldsAWorldPlayerEntityOrChunk() {
        ArchCondition<JavaField> notHoldWorldObjects = new ArchCondition<>("not hold World, Entity (Player) or Chunk") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (WORLD_OBJECT_FIELD_EXCEPTIONS.contains(field.getOwner().getName())) return;
                if (field.getOwner().getPackageName().equals(MENU_PACKAGE)) return;
                // generic arguments count too: a Map<UUID, Player> keeps players alive as well
                for (JavaClass type : field.getAllInvolvedRawTypes()) {
                    if (type.isAssignableTo(World.class) || type.isAssignableTo(Entity.class)
                            || type.isAssignableTo(Chunk.class)) {
                        events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " holds " + type.getName()));
                    }
                }
            }
        };
        fields().should(notHoldWorldObjects).allowEmptyShould(true).check(PRODUCTION);
    }
}
