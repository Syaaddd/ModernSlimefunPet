package id.alwination.slimefunpet.packet.display;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Every .bbmodel in the plugin's models folder, keyed by file name.
 * <p>
 * Swapped as a whole on reload, so a display that is still running keeps the
 * model it was built with.
 */
// volatile publishes state between the server thread and a display timer thread; kept from pet-display.
@SuppressWarnings("PMD.AvoidUsingVolatile")
public final class PetModels {

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final String DEFAULT_MODEL = "models/redpanda.bbmodel";

    /**
     * What a pet looks like when it is drawn from a model.
     *
     * @param parts       the cubes and the face details this pet draws
     * @param blockStates the block state id each part is drawn with
     * @param scale       blocks per 16 model pixels
     * @param yOffset     blocks above the owner's feet
     * @param facing      radians turning the model's front to north, the way
     *                    the pet walks
     * @param effects     the particles the model's particle cubes give off
     */
    public record Settings(BBModel model, List<BBModel.Cube> parts, int[] blockStates, float scale, float yOffset,
                           float facing, List<Effect> effects) {}

    /**
     * A particle given off from a particle cube, see 'particles' in pets.yml.
     *
     * @param dust   the colour and size when the type is dust, else null
     * @param spread blocks each way the particles scatter
     * @param every  ticks between two puffs
     * @param motion the shape each puff draws around the cube
     */
    public record Effect(BBModel.Emitter emitter, Particle type, Particle.@Nullable DustOptions dust, int count, float spread,
                         float speed, int every, ParticleMotion motion) {}

    private static final int MAX_PARTICLE_COUNT = 50;

    private static final int DEFAULT_DETAIL = 40;
    private static final float DEFAULT_SCALE = 0.6f;
    // Past this many cubes a model is worth a warning, see load
    private static final int HEAVY_CUBES = 64;

    /**
     * Rough average colour of each block's texture, what the cube colours are
     * matched against. Only full, flat looking blocks, so every cube reads as
     * one solid colour.
     */
    static final Map<Material, Integer> PALETTE = palette();

    private static volatile Map<String, BBModel> models = Map.of();
    private static volatile Map<String, PetSkins.Look> looks = Map.of();
    // Each skin's entry under 'skins', keyed by lower case model name
    private static volatile Map<String, ConfigurationSection> skinEntries = Map.of();
    // Settings of the models worn as skins, built on first use, dropped on reload
    private static final Map<String, Settings> skinSettings = new ConcurrentHashMap<>();
    // Named after the relocated class, so a warning shows which plugin's copy it came from
    private static final Logger LOGGER = Logger.getLogger(PetModels.class.getName());

    private PetModels() {
    }

    /**
     * @param skins the 'skins' section, see {@link #settingsForSkin}
     */
    // A broken .bbmodel the owner dropped in must skip that one model, never stop the plugin.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static void load(JavaPlugin plugin, @Nullable ConfigurationSection skins) {
        File folder = new File(plugin.getDataFolder(), "models");
        // Only on the very first start, a deleted example stays deleted
        if (!folder.exists()) {
            if (folder.mkdirs()) {
                plugin.saveResource(DEFAULT_MODEL, false);
            } else {
                LOGGER.warning("Could not create " + folder + "; pets are drawn as heads");
            }
        }

        Map<String, BBModel> loaded = new HashMap<>();
        File[] files = folder.listFiles((dir, file) -> file.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        for (File file : files == null ? new File[0] : files) {
            String name = file.getName().substring(0, file.getName().length() - ".bbmodel".length()).toLowerCase(Locale.ROOT);
            if (file.length() > MAX_FILE_BYTES) {
                LOGGER.warning("Skipped model '" + file.getName() + "', it is bigger than " + MAX_FILE_BYTES / 1024 / 1024 + " MB");
                continue;
            }
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                BBModel model = BBModel.parse(name, reader, PALETTE);
                loaded.put(name, model);
                LOGGER.info("Loaded pet model '" + name + "' (" + model.getCubes().size() + " cubes, "
                        + model.getDetailCount() + " face details)");
                if (model.getCubes().size() > HEAVY_CUBES) {
                    LOGGER.warning("Pet model '" + name + "' has " + model.getCubes().size() + " cubes, every one is an"
                            + " entity sent to each player near the pet; lower 'detail' on the pets using it if it lags");
                }
                if (model.getUnsupportedValues() > 0) {
                    LOGGER.warning("Pet model '" + name + "' has " + model.getUnsupportedValues()
                            + " Molang keyframe values, only plain numbers are played, those stay at 0");
                }
            } catch (IOException | RuntimeException ex) {
                LOGGER.warning("Skipped model '" + file.getName() + "', it " + ex.getMessage());
            }
        }
        models = Map.copyOf(loaded);
        skinSettings.clear();
        looks = Map.copyOf(PetSkins.loadLooks(skins));
        Map<String, ConfigurationSection> entries = new HashMap<>();
        if (skins != null) {
            for (String key : skins.getKeys(false)) {
                ConfigurationSection entry = skins.getConfigurationSection(key);
                if (entry != null) entries.put(key.toLowerCase(Locale.ROOT), entry);
            }
        }
        skinEntries = Map.copyOf(entries);
    }

    /**
     * The file names of every loaded model, what a skin can be.
     */
    public static Set<String> modelNames() {
        return models.keySet();
    }

    public static PetSkins.Look lookOf(String model) {
        return PetSkins.lookOf(looks, model);
    }

    /**
     * How a pet dressed in a skin is drawn, tuned by the skin's entry under
     * 'skins' (scale, y-offset, detail, facing, blocks, like a
     * pet's 'model' section), the defaults when it has none. Built once per
     * model.
     *
     * @return null when there is no such model any more
     */
    public static @Nullable Settings settingsForSkin(String model) {
        BBModel loaded = models.get(model);
        if (loaded == null) return null;
        return skinSettings.computeIfAbsent(model, key -> build("skin " + key, loaded, skinEntries.get(key)));
    }

    /**
     * How a pet is drawn, from its 'model' key in pets.yml.
     * <p>
     * Written either as just the file name, or as a section:
     * <pre>
     *   model:
     *     file: redpanda
     *     scale: 0.6
     *     y-offset: 0
     *     facing: north
     *     detail: 40
     *     blocks:
     *       tail: orange_terracotta
     * </pre>
     * A pet without the key uses the model named after its id when there is
     * one, 'none' turns that off.
     *
     * @return null when the pet keeps the floating skull
     */
    public static @Nullable Settings settingsFor(String petId, @Nullable ConfigurationSection pet) {
        if (pet == null) return null;
        ConfigurationSection section = pet.getConfigurationSection("model");
        String file = section != null ? section.getString("file", "") : pet.getString("model", petId);
        if (file.isEmpty() || file.equalsIgnoreCase("none")) return null;

        BBModel model = models.get(file.toLowerCase(Locale.ROOT));
        if (model == null) {
            // Only worth a warning when the model was asked for by name
            if (pet.contains("model")) {
                LOGGER.warning("Pet '" + petId + "' uses the model '" + file + "' but there is no models/" + file + ".bbmodel");
            }
            return null;
        }
        return build(petId, model, section);
    }

    /**
     * @param petId   who the settings are for, only for the warnings
     * @param section the 'model' section, null for the defaults
     */
    private static Settings build(String petId, BBModel model, @Nullable ConfigurationSection section) {
        Map<String, Material> overrides = new HashMap<>();
        ConfigurationSection blocks = section == null ? null : section.getConfigurationSection("blocks");
        if (blocks != null) {
            // Every leaf, a dotted key like 'hat.cube' is read back as nested sections
            for (String key : blocks.getKeys(true)) {
                if (blocks.isConfigurationSection(key)) continue;
                Material material = Material.matchMaterial(blocks.getString(key, ""));
                if (material == null || !material.isBlock()) {
                    LOGGER.warning("Pet '" + petId + "' has an unknown block '" + blocks.getString(key) + "' for '" + key + "'");
                    continue;
                }
                overrides.put(key, material);
            }
            for (String key : unmatched(model, overrides)) {
                LOGGER.warning("Pet '" + petId + "' sets a block for '" + key + "' but no cube or group of the model matches it");
            }
        }

        Options options = options(section);
        List<BBModel.Cube> parts = model.getParts(options.detail());
        int[] blockStates = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            BBModel.Cube part = parts.get(i);
            // An override recolours the cube, its eyes and stripes stay as they are
            Material material = part.detail() ? part.block() : blockFor(model, part, overrides);
            blockStates[i] = SpigotConversionUtil.fromBukkitBlockData(material.createBlockData()).getGlobalId();
        }

        return new Settings(model, List.copyOf(parts), blockStates, options.scale(), options.yOffset(),
                facing(petId, options.facing()),
                effects(petId, model, section == null ? null : section.getConfigurationSection("particles")));
    }

    /**
     * What each particle cube gives off. A cube is looked up by its name under
     * 'particles', either as a section or just the particle; with no entry the
     * particle is read from the name itself, particle_flame gives off flame.
     */
    private static List<Effect> effects(String petId, BBModel model, @Nullable ConfigurationSection particles) {
        List<Effect> effects = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (BBModel.Emitter emitter : model.getEmitters()) {
            String name = emitter.name();
            ConfigurationSection entry = particles == null ? null : particles.getConfigurationSection(name);
            String type;
            if (entry != null) {
                type = entry.getString("type", "");
            } else if (particles != null && particles.isString(name)) {
                type = particles.getString(name, "");
            } else {
                type = name.substring(BBModel.EMITTER_PREFIX.length()).replaceFirst("^_", "");
            }
            if (particles != null && particles.contains(name)) used.add(name);

            Particle particle = particle(petId, name, type);
            if (particle == null) continue;
            effects.add(new Effect(emitter, particle, particle == Particle.DUST ? dust(petId, name, entry) : null,
                    entry == null ? 1 : Math.max(0, Math.min(MAX_PARTICLE_COUNT, entry.getInt("count", 1))),
                    entry == null ? 0 : (float) Math.max(0, Math.min(4, entry.getDouble("spread", 0))),
                    entry == null ? 0 : (float) Math.max(0, Math.min(10, entry.getDouble("speed", 0))),
                    entry == null ? 1 : Math.max(1, entry.getInt("interval", 1)),
                    motion(petId, name, entry)));
        }
        if (particles != null) {
            for (String key : particles.getKeys(false)) {
                if (!used.contains(key)) {
                    LOGGER.warning("Pet '" + petId + "' sets particles for '" + key + "' but the model has no particle cube with that name");
                }
            }
        }
        return List.copyOf(effects);
    }

    /**
     * @return null, after a warning, when it cannot be shown
     */
    private static @Nullable Particle particle(String petId, String cube, String type) {
        Particle particle;
        try {
            particle = Particle.valueOf(type.toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
        } catch (IllegalArgumentException ex) {
            LOGGER.warning("Pet '" + petId + "' has the particle cube '" + cube + "' but '" + type
                    + "' is not a particle; set one under particles." + cube + ".type");
            return null;
        }
        if (particle != Particle.DUST && particle.getDataType() != Void.class) {
            LOGGER.warning("Pet '" + petId + "' uses '" + type + "' for '" + cube + "', particles that need extra data"
                    + " (a block, an item, a colour) are not supported except dust");
            return null;
        }
        if (!PetParticles.isSendable(particle)) {
            LOGGER.warning("Pet '" + petId + "' uses '" + type + "' for '" + cube + "', which this server version cannot send");
            return null;
        }
        return particle;
    }

    /**
     * The 'animation' of a particle cube and the keys shaping it, a plain puff
     * when it has none.
     */
    static ParticleMotion motion(String petId, String cube, @Nullable ConfigurationSection entry) {
        if (entry == null || !entry.contains("animation")) return ParticleMotion.POINT;
        String name = entry.getString("animation", "point");
        ParticleMotion.Kind kind;
        try {
            kind = ParticleMotion.Kind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOGGER.warning("Pet '" + petId + "' has the particle animation '" + name + "' for '" + cube + "', use one of "
                    + Arrays.toString(ParticleMotion.Kind.values()).toLowerCase(Locale.ROOT));
            return ParticleMotion.POINT;
        }
        return new ParticleMotion(kind,
                (float) Math.max(0, Math.min(4, entry.getDouble("radius", 0.5))),
                (float) Math.max(0, Math.min(4, entry.getDouble("height", 1))),
                Math.max(1, Math.min(1200, entry.getInt("period", 40))),
                Math.max(1, Math.min(ParticleMotion.MAX_POINTS, entry.getInt("points", 12))));
    }

    /**
     * Dust's colour ('#RRGGBB', white by default) and size from the cube's entry.
     */
    private static Particle.DustOptions dust(String petId, String cube, @Nullable ConfigurationSection entry) {
        String hex = entry == null ? "FFFFFF" : entry.getString("color", "FFFFFF").replace("#", "");
        int rgb;
        try {
            rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            LOGGER.warning("Pet '" + petId + "' has the colour '" + hex + "' for '" + cube + "', write it as '#RRGGBB'");
            rgb = 0xFFFFFF;
        }
        float size = entry == null ? 1 : (float) Math.max(0.01, Math.min(4, entry.getDouble("size", 1)));
        return new Particle.DustOptions(Color.fromRGB(rgb), size);
    }

    /**
     * The block a cube is drawn with, after the 'blocks' overrides.
     * <p>
     * A key is a dotted path. Its last part is a cube name, or '*' for every
     * cube; the parts before it are groups that have to follow each other in
     * the cube's group path. A single part is a cube name or a group name.
     * <pre>
     *   ear              cubes named ear, anywhere
     *   head             every cube in the head group and its sub-groups
     *   ears.ear         cubes named ear inside ears
     *   legs.*           every cube inside legs
     * </pre>
     * When several keys match, the most specific wins: one naming the cube
     * beats a group-only one, a longer path beats a shorter one, a deeper
     * group beats an outer one.
     *
     * @param overrides dotted key to block, as written under 'blocks'
     */
    static Material blockFor(BBModel model, BBModel.Cube cube, Map<String, Material> overrides) {
        List<String> groups = model.groupPath(cube.bone());
        Material best = cube.block();
        long bestScore = -1;
        for (Map.Entry<String, Material> entry : overrides.entrySet()) {
            long score = matchScore(entry.getKey(), cube.name(), groups);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        return best;
    }

    /**
     * The override keys that match no cube of the model, worth a warning.
     */
    static Set<String> unmatched(BBModel model, Map<String, Material> overrides) {
        Set<String> unmatched = new HashSet<>(overrides.keySet());
        for (BBModel.Cube cube : model.getCubes()) {
            List<String> groups = model.groupPath(cube.bone());
            unmatched.removeIf(key -> matchScore(key, cube.name(), groups) >= 0);
        }
        return unmatched;
    }

    /**
     * How specifically a key matches a cube, -1 when it does not.
     */
    private static long matchScore(String key, String cubeName, List<String> groups) {
        String[] parts = key.split("\\.");
        if (parts.length == 1) {
            // A bare name: the cube itself, or a group it sits in, the deepest counting most
            if (parts[0].equals(cubeName)) return 2_000;
            int depth = groups.lastIndexOf(parts[0]);
            return depth < 0 ? -1 : 1_000 + depth;
        }

        String last = parts[parts.length - 1];
        boolean wholeGroup = last.equals("*");
        if (!wholeGroup && !last.equals(cubeName)) return -1;
        List<String> chain = Arrays.asList(parts).subList(0, parts.length - 1);
        int end = endOfChain(groups, chain);
        if (end < 0) return -1;
        // Naming the cube beats '*', a longer path beats a shorter one
        return (wholeGroup ? 1_000 : 3_000) + parts.length * 100L + end;
    }

    /**
     * Where the chain of groups ends in the cube's group path when it appears
     * there as consecutive groups, the deepest such place; -1 when it does not.
     */
    private static int endOfChain(List<String> groups, List<String> chain) {
        for (int start = groups.size() - chain.size(); start >= 0; start--) {
            if (groups.subList(start, start + chain.size()).equals(chain)) return start + chain.size() - 1;
        }
        return -1;
    }

    /**
     * The tuning keys a pet's 'model' section and a skin's entry share.
     *
     * @param facing as written, north / east / south / west
     */
    record Options(float scale, float yOffset, int detail, String facing) {}

    /**
     * @param section a pet's 'model' section or a skin's entry, null for the defaults
     */
    static Options options(@Nullable ConfigurationSection section) {
        if (section == null) return new Options(DEFAULT_SCALE, 0f, DEFAULT_DETAIL, "north");
        return new Options(
                (float) Math.max(0.05, Math.min(4, section.getDouble("scale", DEFAULT_SCALE))),
                (float) section.getDouble("y-offset", 0),
                Math.max(0, Math.min(BBModel.MAX_DETAILS, section.getInt("detail", DEFAULT_DETAIL))),
                section.getString("facing", "north"));
    }

    /**
     * Models are built facing any way. 'facing' names where the front points
     * in Blockbench (north is -Z), and this turns that to north.
     */
    private static float facing(String petId, String facing) {
        return switch (facing.toLowerCase(Locale.ROOT)) {
            case "north" -> 0f;
            case "east" -> (float) Math.toRadians(90);
            case "south" -> (float) Math.PI;
            case "west" -> (float) Math.toRadians(-90);
            default -> {
                LOGGER.warning("Pet '" + petId + "' has facing '" + facing + "', use north, east, south or west");
                yield 0f;
            }
        };
    }

    private static Map<Material, Integer> palette() {
        Map<Material, Integer> palette = new EnumMap<>(Material.class);
        palette.put(Material.WHITE_CONCRETE, 0xCFD5D6);
        palette.put(Material.ORANGE_CONCRETE, 0xE06101);
        palette.put(Material.MAGENTA_CONCRETE, 0xA9309F);
        palette.put(Material.LIGHT_BLUE_CONCRETE, 0x2489C7);
        palette.put(Material.YELLOW_CONCRETE, 0xF1AF15);
        palette.put(Material.LIME_CONCRETE, 0x5EA918);
        palette.put(Material.PINK_CONCRETE, 0xD5658F);
        palette.put(Material.GRAY_CONCRETE, 0x373A3E);
        palette.put(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        palette.put(Material.CYAN_CONCRETE, 0x157788);
        palette.put(Material.PURPLE_CONCRETE, 0x64209C);
        palette.put(Material.BLUE_CONCRETE, 0x2D2F8F);
        palette.put(Material.BROWN_CONCRETE, 0x603C20);
        palette.put(Material.GREEN_CONCRETE, 0x495B24);
        palette.put(Material.RED_CONCRETE, 0x8E2121);
        palette.put(Material.BLACK_CONCRETE, 0x080A0F);
        palette.put(Material.TERRACOTTA, 0x985E44);
        palette.put(Material.WHITE_TERRACOTTA, 0xD2B2A1);
        palette.put(Material.ORANGE_TERRACOTTA, 0xA25426);
        palette.put(Material.MAGENTA_TERRACOTTA, 0x96586D);
        palette.put(Material.LIGHT_BLUE_TERRACOTTA, 0x716D8A);
        palette.put(Material.YELLOW_TERRACOTTA, 0xBA8523);
        palette.put(Material.LIME_TERRACOTTA, 0x687635);
        palette.put(Material.PINK_TERRACOTTA, 0xA24E4F);
        palette.put(Material.GRAY_TERRACOTTA, 0x3A2A24);
        palette.put(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        palette.put(Material.CYAN_TERRACOTTA, 0x575B5B);
        palette.put(Material.PURPLE_TERRACOTTA, 0x764656);
        palette.put(Material.BLUE_TERRACOTTA, 0x4A3C5B);
        palette.put(Material.BROWN_TERRACOTTA, 0x4D3324);
        palette.put(Material.GREEN_TERRACOTTA, 0x4C532A);
        palette.put(Material.RED_TERRACOTTA, 0x8F3D2F);
        palette.put(Material.BLACK_TERRACOTTA, 0x251710);
        palette.put(Material.SNOW_BLOCK, 0xF9FEFE);
        palette.put(Material.QUARTZ_BLOCK, 0xECE6DF);
        palette.put(Material.SMOOTH_SANDSTONE, 0xE0D6AA);
        return palette;
    }

}
