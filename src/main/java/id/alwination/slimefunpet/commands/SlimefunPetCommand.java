package id.alwination.slimefunpet.commands;

import com.muhammaddaffa.mdlib.commands.args.ArgSuggester;
import com.muhammaddaffa.mdlib.commands.args.builtin.IntArg;
import com.muhammaddaffa.mdlib.commands.args.builtin.OnlinePlayerArg;
import com.muhammaddaffa.mdlib.commands.args.builtin.StringArg;
import com.muhammaddaffa.mdlib.commands.commands.RoutedCommand;
import com.muhammaddaffa.mdlib.utils.Common;
import com.muhammaddaffa.mdlib.utils.Placeholder;
import id.alwination.slimefunpet.ModernSlimefunPet;
import id.alwination.slimefunpet.database.DatabaseQueueHandler;
import id.alwination.slimefunpet.pets.Pet;
import id.alwination.slimefunpet.pets.PetPlayer;
import id.alwination.slimefunpet.pets.gui.PetInventory;
import id.alwination.slimefunpet.pets.items.PetItem;
import id.alwination.slimefunpet.pets.items.PetItemRegistry;
import id.alwination.slimefunpet.pets.items.PetItemType;
import id.alwination.slimefunpet.pets.managers.PetManager;
import id.alwination.slimefunpet.pets.tasks.PetTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Admin command /slimefunpet (alias /sfpet). Crates and shops run givepet and givepetitem from the
 * console.
 * <ul>
 *   <li>givepet &lt;player&gt; &lt;pet&gt; [level]</li>
 *   <li>givepetitem &lt;player&gt; &lt;type&gt; &lt;id&gt; [amount]</li>
 *   <li>petmenu &lt;player&gt; [type] [id]: opens the pet list for them, or one item mode</li>
 *   <li>reload: every config, the pet types, and the timers</li>
 * </ul>
 */
public final class SlimefunPetCommand extends RoutedCommand {

    public static final String ADMIN = "modernslimefunpet.admin";

    public SlimefunPetCommand(PetManager petManager, PetTask petTask) {
        super("slimefunpet", "ModernSlimefunPet admin", "/slimefunpet <givepet|givepetitem|petmenu|reload>", ADMIN);
        alias("sfpet");

        sub("givepet").perm(ADMIN)
                .arg("target", new OnlinePlayerArg())
                .arg("pet_id", new StringArg(), ArgSuggester.ofDynamic((sender, input) -> new ArrayList<>(petManager.getPets())))
                .argOptional("level", new IntArg())
                .exec((sender, context) -> {
                    Player target = context.get("target", Player.class);
                    String petId = context.get("pet_id", String.class);
                    Integer level = context.get("level", Integer.class);
                    Pet pet = petManager.getPet(petId);
                    if (pet == null) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.invalid-pet");
                        return;
                    }
                    PetPlayer petPlayer = petManager.createPetPlayer(target.getUniqueId(), pet);
                    if (level != null) {
                        // Capped at the pet's max level and saved again (handoff section 10)
                        petPlayer.setLevel(Math.min(Math.max(1, level), pet.getMaxLevel()));
                        DatabaseQueueHandler.addObjectToSave(petPlayer);
                        DatabaseQueueHandler.saveNow();
                    }
                    Placeholder placeholder = new Placeholder()
                            .add("{pet}", Common.color(pet.getItemDisplayName()))
                            .add("{level}", Common.digits(petPlayer.getLevel()))
                            .add("{player}", target.getName());
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.give-pet", placeholder);
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(target, "messages.receive-pet", placeholder);
                });

        sub("givepetitem").perm(ADMIN)
                .arg("target", new OnlinePlayerArg())
                .arg("type", new StringArg(), ArgSuggester.ofList(typeNames()))
                .arg("id", new StringArg(), ArgSuggester.ofDynamic((sender, input, context) -> {
                    PetItemType type = PetItemType.fromString(context.get("type", String.class));
                    return type == null ? List.of() : PetItemRegistry.getIds(type);
                }))
                .argOptional("amount", new IntArg(), ArgSuggester.ofList(List.of("1", "8", "16", "32", "64")))
                .exec((sender, context) -> {
                    Player target = context.get("target", Player.class);
                    String rawType = context.get("type", String.class);
                    String id = context.get("id", String.class);
                    Integer amount = context.get("amount", Integer.class);
                    int count = amount == null ? 1 : Math.max(1, amount);
                    PetItemType type = PetItemType.fromString(rawType);
                    if (type == null) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.invalid-pet-item-type", new Placeholder()
                                .add("{type}", rawType)
                                .add("{types}", String.join(", ", typeNames())));
                        return;
                    }
                    ItemStack stack = PetItemRegistry.getItemStack(type, id, count);
                    if (stack == null) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.invalid-pet-item", new Placeholder()
                                .add("{id}", id)
                                .add("{ids}", String.join(", ", PetItemRegistry.getIds(type))));
                        return;
                    }
                    // A full inventory drops the rest at the player's feet instead of losing it
                    for (ItemStack left : target.getInventory().addItem(stack).values()) {
                        target.getWorld().dropItem(Objects.requireNonNull(target.getLocation()), left);
                    }
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.give-pet-item", new Placeholder()
                            .add("{player}", target.getName())
                            .add("{type}", type.name().toLowerCase(Locale.ROOT))
                            .add("{id}", id)
                            .add("{amount}", String.valueOf(count)));
                });

        sub("petmenu").perm(ADMIN)
                .arg("target", new OnlinePlayerArg())
                .argOptional("type", new StringArg(), ArgSuggester.ofList(typeNames()))
                .argOptional("id", new StringArg(), ArgSuggester.ofDynamic((sender, input, context) -> {
                    PetItemType type = PetItemType.fromString(context.get("type", String.class));
                    return type == null ? List.of() : PetItemRegistry.getIds(type);
                }))
                .exec((sender, context) -> {
                    Player target = context.get("target", Player.class);
                    String rawType = context.get("type", String.class);
                    String id = context.get("id", String.class);
                    if (rawType == null) {
                        new PetInventory(target, target, petManager, petTask).open(target);
                        return;
                    }
                    PetItemType type = PetItemType.fromString(rawType);
                    if (type == null) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.invalid-pet-item-type", new Placeholder()
                                .add("{type}", rawType)
                                .add("{types}", String.join(", ", typeNames())));
                        return;
                    }
                    List<String> ids = PetItemRegistry.getIds(type);
                    String itemId = id != null ? id : ids.isEmpty() ? "" : ids.get(0);
                    PetItem petItem = PetItemRegistry.get(type, itemId);
                    if (petItem == null) {
                        ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.invalid-pet-item", new Placeholder()
                                .add("{id}", itemId)
                                .add("{ids}", String.join(", ", ids)));
                        return;
                    }
                    new PetInventory(target, target, petManager, petTask, petItem).open(target);
                });

        sub("reload").perm(ADMIN)
                .exec((sender, context) -> {
                    ModernSlimefunPet.getInstance().reloadPets();
                    ModernSlimefunPet.PETS_CONFIG.sendMessage(sender, "messages.reloaded");
                });
    }

    private static List<String> typeNames() {
        return Arrays.stream(PetItemType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
