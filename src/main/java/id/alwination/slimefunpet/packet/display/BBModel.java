package id.alwination.slimefunpet.packet.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A Blockbench model (.bbmodel) boiled down to what a pet built out of block
 * displays needs: the bone tree, one block per cube and the keyframes.
 * <p>
 * Nothing is textured. Every cube is drawn as one solid block, picked by
 * letting each pixel of its texture vote for the closest block colour.
 * <p>
 * Immutable once parsed, so any thread may sample it. Coordinates stay in
 * Blockbench pixels (16 per block) until the renderer scales them.
 */
public final class BBModel {

    // A model is dropped in by an admin, but one careless file must not be able
    // to spawn hundreds of entities for every viewer of every pet
    // Everything drawn: solid cubes plus the rectangles cut-out shapes need
    static final int MAX_CUBES = 256;
    private static final int MAX_BONES = 256;
    private static final int MAX_KEYFRAMES = 8192;
    // Every cube in the file, hidden ones included: only bounds the parsing
    private static final int MAX_RAW_CUBES = 2048;

    /**
     * The animations a pet ever plays, see ModelRig. Anything else in the file
     * (attacks, emotes) is never read.
     */
    static final Set<String> PLAYED_ANIMATIONS = Set.of("idle", "walk", "fly");
    private static final int MAX_TEXTURE_SIDE = 1024;
    // The major format version from which keyframes are stored as they play
    private static final int FIRST_UNFLIPPED_FORMAT = 5;

    // A cube whose texture is mostly see-through is an overlay (a hat or a
    // second skin layer), drawn solid it would hide the cube underneath
    private static final double MIN_OPAQUE_SHARE = 0.5;
    // Flat cubes (a plane in Blockbench) still need some thickness to be seen
    private static final float MIN_CUBE_SIZE = 0.1f;

    // A plane (thinner than this, pixels) whose texture is less than
    // CUTOUT_SHARE opaque is a cut-out: a wing, a spine, a claw, shaped by
    // its see-through pixels. Drawn as its opaque pixels, not one slab
    private static final float FLAT_SIZE = 0.5f;
    private static final double CUTOUT_SHARE = 0.9;
    // A cut-out bigger than this many pixels is drawn at 2x2 pixel steps,
    // half the entities for a slightly chunkier outline
    private static final int CUTOUT_FULL_RES_CELLS = 64;

    // How many face details are kept per model, a pet picks how many it draws
    static final int MAX_DETAILS = 128;
    // How far a detail stands out of its face, in pixels. Enough to never
    // flicker against the face, too little to be seen as a step
    private static final float DETAIL_DEPTH = 0.15f;
    // Flat planes stacked closer than this (an eye white and its pupil, a
    // hundredth of a pixel apart in Blockbench) are spread to this far apart:
    // closer than that, the game cannot tell which is in front at a distance
    // and they flicker through each other
    private static final float LAYER_GAP = 0.15f;
    // How thick a cut-out plane is drawn, it only needs to be seen
    private static final float PLANE_THICKNESS = 0.05f;
    // Two colours closer than this (redmean distance) count as the same, so
    // the shading inside a colour does not turn into details
    private static final double SAME_COLOUR = 20000;
    // A bone scaled under this on any axis is hidden outright, see pose. High,
    // because an eye shrinking toward a pivot inside the head is already sunk
    // into it at three quarters; the squash and stretch these models breathe
    // with stays around 0.9
    static final float BLINK_SCALE = 0.75f;
    // An overlay texel has nothing under it to stand out from, so it ranks
    // as a strong contrast: an eye drawn on an overlay beats fur shading
    private static final double OVERLAY_CONTRAST = 150000;
    // A face is what a creature is read by: details on the bone named 'head'
    // (and everything under it) count this much more, so its eyes and nose
    // win the budget over stripes on a tail
    private static final double HEAD_WEIGHT = 3;
    private static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");
    private static final Pattern EMISSIVE_NAME = Pattern.compile("_e(\\.png)?$", Pattern.CASE_INSENSITIVE);
    // A cube whose name starts with this is never drawn, it only marks where
    // a particle comes from (particle, particle_flame, particle_left_wing)
    static final String EMITTER_PREFIX = "particle";
    // Every emitter is a particle packet per update for every viewer
    private static final int MAX_EMITTERS = 16;

    record Bone(String name, int parent, Vector3f pivot, Vector3f rotation) {}

    /**
     * @param local  the cube's own placement inside its bone: the element
     *               rotation around its origin, then the unit block stretched
     *               over from..to
     * @param detail a slab laid on a face of the cube it is named after, rather
     *               than the cube itself
     */
    record Cube(String name, int bone, Matrix4fc local, Material block, boolean detail) {}

    /**
     * Where a particle cube sits: its centre inside its bone, model pixels.
     */
    record Emitter(String name, int bone, Vector3fc point) {}

    /**
     * One face of a cube as texels, cells[row][column] seen from outside,
     * null where the texture is see-through.
     */
    private record Face(String direction, @Nullable Material[][] cells) {}

    /**
     * A cube while the model is being read, before its details are cut. The
     * block is null for an overlay: a mostly see-through layer (a second skin,
     * a face drawn over a head) that is never drawn solid, only its details.
     */
    private record Drawn(String name, int bone, Matrix4fc placed, Vector3f min, Vector3f size, @Nullable Material block,
                         List<Face> faces, boolean cutout) {}

    private record Scored(double score, Cube detail) {}

    record Keyframe(float time, float x, float y, float z, String interpolation) {}

    /**
     * Keyframes indexed by bone, an empty array when the bone has no channel.
     */
    record Animation(float length, Keyframe[][] rotation, Keyframe[][] position, Keyframe[][] scale) {}

    private final String name;
    // Parents always come before their children, so a pose is one pass
    private final List<Bone> bones;
    private final List<Cube> cubes;
    /**
     * Face details, the most visible first.
     */
    private final List<Cube> details;
    private final List<Emitter> emitters;
    private final Map<String, Animation> animations;
    private final float height;
    private final int headBone;
    /**
     * Keyframe values that were Molang instead of a number and are played as 0.
     */
    private final int unsupportedValues;

    private BBModel(String name, List<Bone> bones, List<Cube> cubes, List<Cube> details, List<Emitter> emitters,
                    Map<String, Animation> animations, float height, int headBone, int unsupportedValues) {
        this.name = name;
        this.bones = bones;
        this.cubes = cubes;
        this.details = details;
        this.emitters = emitters;
        this.animations = animations;
        this.height = height;
        this.headBone = headBone;
        this.unsupportedValues = unsupportedValues;
    }

    public String getName() {
        return name;
    }

    List<Cube> getCubes() {
        return cubes;
    }

    int getDetailCount() {
        return details.size();
    }

    List<Emitter> getEmitters() {
        return emitters;
    }

    /**
     * Every cube, then the best 'detail' face details on top.
     */
    List<Cube> getParts(int detail) {
        List<Cube> parts = new ArrayList<>(cubes);
        parts.addAll(details.subList(0, Math.max(0, Math.min(detail, details.size()))));
        return parts;
    }

    int getBoneCount() {
        return bones.size();
    }

    /**
     * The highest point of the model at rest, in pixels.
     */
    public float getHeight() {
        return height;
    }

    public int getUnsupportedValues() {
        return unsupportedValues;
    }

    public Set<String> getCubeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Cube cube : cubes) names.add(cube.name());
        return names;
    }

    @Nullable Animation getAnimation(String animation) {
        return animations.get(animation);
    }

    /**
     * The names of the groups a bone sits in, outermost first, the bone's own
     * last. Cubes outside of any group have an empty path.
     */
    List<String> groupPath(int bone) {
        List<String> path = new ArrayList<>();
        for (int i = bone; i > 0; i = bones.get(i).parent()) {
            path.add(0, bones.get(i).name());
        }
        return path;
    }

    /**
     * @return the bone that glances around and whose details come first, -1
     * when the model has none
     */
    int getHeadBone() {
        return headBone;
    }

    /**
     * The first bone named 'head', or else the first carrying the ModelEngine
     * head prefix 'h_' (h_head).
     */
    private static int findHeadBone(List<Bone> bones) {
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).name().equalsIgnoreCase("head")) return i;
        }
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).name().startsWith("h_")) return i;
        }
        return -1;
    }

    /**
     * Where every bone is at this point of the animation, in model pixels.
     *
     * @param out one matrix per bone, overwritten
     */
    /**
     * @param turnBone  a bone turned on top of the animation, -1 for none
     * @param turnYaw   degrees about the bone's up axis
     * @param turnPitch degrees about its sideways axis
     */
    void pose(@Nullable Animation animation, float seconds, Matrix4f[] out, int turnBone, float turnYaw, float turnPitch) {
        float time = 0;
        if (animation != null && animation.length() > 0) {
            time = seconds % animation.length();
        }

        Vector3f rot = new Vector3f();
        Vector3f pos = new Vector3f();
        Vector3f scale = new Vector3f();
        Vector3f v = new Vector3f();
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            Matrix4f m = bone.parent() < 0 ? out[i].identity() : out[i].set(out[bone.parent()]);

            rot.set(bone.rotation());
            pos.zero();
            scale.set(1);
            if (animation != null) {
                // Blockbench adds keyframes to the bone as they are, in the same
                // sense as the group's own rotation. Files that store them
                // flipped were already put right by readAnimation
                if (sample(animation.rotation()[i], time, v)) rot.add(v);
                if (sample(animation.position()[i], time, v)) pos.set(v);
                if (sample(animation.scale()[i], time, v)) {
                    // A bone shrunk this far is on its way to nothing, the way
                    // these models blink. Drawn on the way it slides into the
                    // head (the pivot usually sits inside it), so it goes
                    // straight to hidden instead and the blink is a clean wink
                    boolean blink = v.x < BLINK_SCALE || v.y < BLINK_SCALE || v.z < BLINK_SCALE;
                    scale.set(blink ? 0 : v.x, blink ? 0 : v.y, blink ? 0 : v.z);
                }
            }
            if (i == turnBone) rot.add(turnPitch, turnYaw, 0);

            Vector3f pivot = bone.pivot();
            m.translate(pivot.x + pos.x, pivot.y + pos.y, pivot.z + pos.z)
                    .rotateZ((float) Math.toRadians(rot.z))
                    .rotateY((float) Math.toRadians(rot.y))
                    .rotateX((float) Math.toRadians(rot.x))
                    .scale(scale)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
        }
    }

    /**
     * The value of one channel at this time, the way Blockbench interpolates it.
     *
     * @return false when the channel has no keyframes
     */
    static boolean sample(Keyframe[] frames, float time, Vector3f out) {
        if (frames.length == 0) return false;

        int after = 0;
        while (after < frames.length && frames[after].time() <= time) after++;
        if (after == 0) return set(out, frames[0]);
        if (after == frames.length) return set(out, frames[frames.length - 1]);

        int before = after - 1;
        Keyframe a = frames[before];
        Keyframe b = frames[after];
        float span = b.time() - a.time();
        float t = span <= 0 ? 0 : (time - a.time()) / span;

        if (a.interpolation().equals("step")) return set(out, a);
        if (a.interpolation().equals("catmullrom") || b.interpolation().equals("catmullrom")) {
            Keyframe p0 = before > 0 ? frames[before - 1] : a;
            Keyframe p3 = after + 1 < frames.length ? frames[after + 1] : b;
            out.set(catmullRom(p0.x(), a.x(), b.x(), p3.x(), t),
                    catmullRom(p0.y(), a.y(), b.y(), p3.y(), t),
                    catmullRom(p0.z(), a.z(), b.z(), p3.z(), t));
            return true;
        }
        // linear, and bezier which is played as linear
        out.set(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t);
        return true;
    }

    private static boolean set(Vector3f out, Keyframe frame) {
        out.set(frame.x(), frame.y(), frame.z());
        return true;
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }

    // ===== Parsing =====

    /**
     * @throws IllegalArgumentException with a readable reason when the file
     *                                  cannot be used
     */
    static BBModel parse(String name, Reader reader, Map<Material, Integer> palette) {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalArgumentException("not a valid .bbmodel file (" + ex.getMessage() + ")", ex);
        }

        float uvWidth = 16;
        float uvHeight = 16;
        if (root.has("resolution")) {
            JsonObject resolution = root.getAsJsonObject("resolution");
            uvWidth = resolution.get("width").getAsFloat();
            uvHeight = resolution.get("height").getAsFloat();
        }

        float[] largestUv = largestUvPerTexture(root);
        List<BufferedImage> textures = new ArrayList<>();
        List<float[]> textureUv = new ArrayList<>();
        JsonArray textureArray = array(root, "textures");
        boolean[] emissive = new boolean[textureArray.size()];
        for (int i = 0; i < textureArray.size(); i++) {
            JsonObject texture = textureArray.get(i).getAsJsonObject();
            BufferedImage image = readTexture(texture);
            textures.add(image);
            // A glow layer drawn over the real texture, not a surface of its
            // own. Blockbench flags it; ModelEngine names it '<texture>_e'
            String textureName = texture.has("name") ? texture.get("name").getAsString() : "";
            String renderMode = texture.has("render_mode") ? texture.get("render_mode").getAsString() : "default";
            emissive[i] = renderMode.equals("emissive") || EMISSIVE_NAME.matcher(textureName).find();
            if (texture.has("uv_width")) {
                textureUv.add(new float[]{texture.get("uv_width").getAsFloat(), texture.get("uv_height").getAsFloat()});
            } else if (image != null && largestUv[i] > Math.max(uvWidth, uvHeight) + 0.01f) {
                // Files from before per-texture UV sizes: faces are meant in the
                // project resolution, but a texture bigger than it has faces
                // reaching past it, which only fits the texture's own pixels
                textureUv.add(new float[]{image.getWidth(), image.getHeight()});
            } else {
                textureUv.add(new float[]{uvWidth, uvHeight});
            }
        }

        // Newer files keep the group data apart from the tree, older ones inline it
        Map<String, JsonObject> groups = new HashMap<>();
        for (JsonElement element : array(root, "groups")) {
            JsonObject group = element.getAsJsonObject();
            groups.put(group.get("uuid").getAsString(), group);
        }
        Map<String, JsonObject> elements = new HashMap<>();
        for (JsonElement element : array(root, "elements")) {
            JsonObject object = element.getAsJsonObject();
            elements.put(object.get("uuid").getAsString(), object);
        }

        List<Bone> bones = new ArrayList<>();
        Map<String, Integer> boneByUuid = new HashMap<>();
        List<JsonObject> cubeElements = new ArrayList<>();
        List<Integer> cubeBones = new ArrayList<>();

        // Cubes outside of any group hang off a bone that never moves
        bones.add(new Bone("", -1, new Vector3f(), new Vector3f()));
        walk(array(root, "outliner"), 0, groups, elements, bones, boneByUuid, cubeElements, cubeBones);

        int[] unsupported = {0};
        boolean flipped = storesFlippedKeyframes(root);
        Map<String, Animation> animations = new HashMap<>();
        for (JsonElement element : array(root, "animations")) {
            JsonObject animation = element.getAsJsonObject();
            String animationName = animation.get("name").getAsString();
            if (!PLAYED_ANIMATIONS.contains(animationName)) continue;
            animations.put(animationName, readAnimation(animation, bones.size(), boneByUuid, flipped, unsupported));
        }
        boolean[] hidden = hiddenBones(bones, animations);

        // First every cube with its block, the details need to know them all
        // to tell which faces are hidden inside another cube
        List<Drawn> drawn = new ArrayList<>();
        List<Emitter> emitters = new ArrayList<>();
        for (int i = 0; i < cubeElements.size(); i++) {
            JsonObject element = cubeElements.get(i);
            // Never on screen, often effects only an attack animation shows
            if (hidden[cubeBones.get(i)]) continue;
            Vector3f from = vector(element, "from", 0);
            Vector3f to = vector(element, "to", 0);
            Vector3f origin = vector(element, "origin", 0);
            Vector3f rotation = vector(element, "rotation", 0);

            // The element rotation around its origin, shared by the cube and its details
            Matrix4f placed = new Matrix4f()
                    .translate(origin)
                    .rotateZ((float) Math.toRadians(rotation.z))
                    .rotateY((float) Math.toRadians(rotation.y))
                    .rotateX((float) Math.toRadians(rotation.x))
                    .translate(-origin.x, -origin.y, -origin.z);
            String cubeName = element.has("name") ? element.get("name").getAsString() : "";

            if (isEmitter(element)) {
                if (emitters.size() >= MAX_EMITTERS) {
                    throw new IllegalArgumentException("has more than " + MAX_EMITTERS + " particle cubes");
                }
                Vector3f centre = placed.transformPosition(new Vector3f(from).add(to).mul(0.5f));
                emitters.add(new Emitter(cubeName, cubeBones.get(i), centre));
                continue;
            }

            List<Face> faces = sampleFaces(element, textures, textureUv, emissive, palette);
            Material block = pickBlock(faces);
            // Blockbench grows a cube by its inflate on every side, what outer
            // layers use to sit just around the cube they cover
            float inflate = element.has("inflate") ? element.get("inflate").getAsFloat() : 0;
            Vector3f min = new Vector3f(from).min(to).sub(inflate, inflate, inflate);
            Vector3f size = new Vector3f(from).max(to).add(inflate, inflate, inflate).sub(min);
            // A flat plane is padded to MIN_CUBE_SIZE evenly on both sides, so
            // it stays where it was put rather than shifting half the padding
            for (int axis = 0; axis < 3; axis++) {
                float pad = MIN_CUBE_SIZE - size.get(axis);
                if (pad > 0) {
                    min.setComponent(axis, min.get(axis) - pad / 2);
                    size.setComponent(axis, MIN_CUBE_SIZE);
                }
            }
            boolean flat = Math.min(size.x, Math.min(size.y, size.z)) < FLAT_SIZE;
            boolean cutout = flat && opaqueShare(faces) < CUTOUT_SHARE && opaqueShare(faces) > 0;
            drawn.add(new Drawn(cubeName, cubeBones.get(i), placed, min, size, cutout ? null : block, faces, cutout));
        }

        drawn = separateLayers(drawn);

        int head = findHeadBone(bones);
        boolean[] onHead = headBones(bones, head);
        List<Cube> cubes = new ArrayList<>();
        List<Scored> candidates = new ArrayList<>();
        float height = 0;
        for (Drawn cube : drawn) {
            if (cube.cutout()) {
                addCutout(cube, palette, cubes);
                height = Math.max(height, cube.min().y + cube.size().y);
                continue;
            }
            addDetails(cube, drawn, palette, onHead[cube.bone()] ? HEAD_WEIGHT : 1, candidates);
            if (cube.block() == null) continue;
            cubes.add(new Cube(cube.name(), cube.bone(), new Matrix4f(cube.placed()).translate(cube.min()).scale(cube.size()),
                    cube.block(), false));
            height = Math.max(height, cube.min().y + cube.size().y);
        }

        if (cubes.isEmpty()) throw new IllegalArgumentException("has no visible cubes");
        if (cubes.size() > MAX_CUBES) {
            throw new IllegalArgumentException("needs " + cubes.size() + " blocks to draw, the limit is " + MAX_CUBES);
        }

        candidates.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Cube> details = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_DETAILS, candidates.size()); i++) details.add(candidates.get(i).detail());

        return new BBModel(name, List.copyOf(bones), List.copyOf(cubes), List.copyOf(details), List.copyOf(emitters),
                Map.copyOf(animations), height, head, unsupported[0]);
    }

    private static boolean isEmitter(JsonObject element) {
        return element.has("name") && element.get("name").getAsString().toLowerCase(Locale.ROOT).startsWith(EMITTER_PREFIX);
    }

    private static void walk(JsonArray children, int parent, Map<String, JsonObject> groups, Map<String, JsonObject> elements,
                             List<Bone> bones, Map<String, Integer> boneByUuid,
                             List<JsonObject> cubeElements, List<Integer> cubeBones) {
        for (JsonElement child : children) {
            if (child.isJsonPrimitive()) {
                JsonObject element = elements.get(child.getAsString());
                // Only cubes can be drawn, meshes, locators and the like are skipped
                if (element == null || (element.has("type") && !element.get("type").getAsString().equals("cube"))) continue;
                if (element.has("export") && !element.get("export").getAsBoolean()) continue;
                // A particle cube is often hidden in Blockbench to keep it out of the way
                if (!isEmitter(element) && element.has("visibility") && !element.get("visibility").getAsBoolean()) continue;
                if (cubeElements.size() >= MAX_RAW_CUBES) {
                    throw new IllegalArgumentException("has more than " + MAX_RAW_CUBES + " cubes");
                }
                cubeElements.add(element);
                cubeBones.add(parent);
                continue;
            }

            JsonObject node = child.getAsJsonObject();
            String uuid = node.get("uuid").getAsString();
            JsonObject group = groups.getOrDefault(uuid, node);
            if (group.has("export") && !group.get("export").getAsBoolean()) continue;
            // The collision box of ModelEngine models, never meant to be seen
            if (group.has("name") && group.get("name").getAsString().equalsIgnoreCase("hitbox")) continue;
            if (bones.size() >= MAX_BONES) throw new IllegalArgumentException("has more than " + MAX_BONES + " bones");

            int index = bones.size();
            bones.add(new Bone(group.has("name") ? group.get("name").getAsString() : uuid, parent,
                    vector(group, "origin", 0), vector(group, "rotation", 0)));
            boneByUuid.put(uuid, index);
            walk(array(node, "children"), index, groups, elements, bones, boneByUuid, cubeElements, cubeBones);
        }
    }

    /**
     * The largest UV coordinate any face uses on each texture.
     */
    private static float[] largestUvPerTexture(JsonObject root) {
        float[] largest = new float[array(root, "textures").size()];
        for (JsonElement element : array(root, "elements")) {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("faces")) continue;
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("faces").entrySet()) {
                JsonObject face = entry.getValue().getAsJsonObject();
                JsonElement index = face.get("texture");
                if (index == null || !index.isJsonPrimitive() || !index.getAsJsonPrimitive().isNumber() || !face.has("uv")) continue;
                int texture = index.getAsInt();
                if (texture < 0 || texture >= largest.length) continue;
                for (JsonElement value : face.getAsJsonArray("uv")) {
                    largest[texture] = Math.max(largest[texture], value.getAsFloat());
                }
            }
        }
        return largest;
    }

    /**
     * Files saved before Blockbench 5 store position x and rotation x and y
     * negated, it flips them when it opens such a file (bbmodel.js). A file
     * with no version at all is read as current.
     */
    private static boolean storesFlippedKeyframes(JsonObject root) {
        if (!root.has("meta")) return false;
        JsonObject meta = root.getAsJsonObject("meta");
        JsonElement version = meta.has("format_version") ? meta.get("format_version") : meta.get("format");
        if (version == null) return false;
        String text = version.getAsString();
        try {
            return Integer.parseInt(text.split("\\.", 2)[0]) < FIRST_UNFLIPPED_FORMAT;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("has an unreadable format version '" + text + "'", ex);
        }
    }

    private static Animation readAnimation(JsonObject animation, int boneCount, Map<String, Integer> boneByUuid,
                                           boolean flipped, int[] unsupported) {
        Keyframe[][] rotation = new Keyframe[boneCount][];
        Keyframe[][] position = new Keyframe[boneCount][];
        Keyframe[][] scale = new Keyframe[boneCount][];
        List<List<Keyframe>> r = new ArrayList<>();
        List<List<Keyframe>> p = new ArrayList<>();
        List<List<Keyframe>> s = new ArrayList<>();
        for (int i = 0; i < boneCount; i++) {
            r.add(new ArrayList<>());
            p.add(new ArrayList<>());
            s.add(new ArrayList<>());
        }

        int total = 0;
        if (animation.has("animators")) {
            for (Map.Entry<String, JsonElement> entry : animation.getAsJsonObject("animators").entrySet()) {
                Integer bone = boneByUuid.get(entry.getKey());
                // Effect animators and bones that were not exported
                if (bone == null) continue;

                for (JsonElement element : array(entry.getValue().getAsJsonObject(), "keyframes")) {
                    total++;
                    if (total > MAX_KEYFRAMES) throw new IllegalArgumentException("has more than " + MAX_KEYFRAMES + " keyframes");
                    JsonObject keyframe = element.getAsJsonObject();
                    JsonArray points = array(keyframe, "data_points");
                    if (points.isEmpty()) continue;

                    JsonObject point = points.get(0).getAsJsonObject();
                    String channel = keyframe.get("channel").getAsString();
                    float fallback = channel.equals("scale") ? 1 : 0;
                    float x = number(point, "x", fallback, unsupported);
                    float y = number(point, "y", fallback, unsupported);
                    if (flipped && (channel.equals("rotation") || channel.equals("position"))) x = -x;
                    if (flipped && channel.equals("rotation")) y = -y;
                    Keyframe frame = new Keyframe(keyframe.get("time").getAsFloat(), x, y,
                            number(point, "z", fallback, unsupported),
                            keyframe.has("interpolation") ? keyframe.get("interpolation").getAsString() : "linear");
                    switch (channel) {
                        case "rotation" -> r.get(bone).add(frame);
                        case "position" -> p.get(bone).add(frame);
                        case "scale" -> s.get(bone).add(frame);
                        default -> { }
                    }
                }
            }
        }

        Comparator<Keyframe> byTime = Comparator.comparingDouble(Keyframe::time);
        for (int i = 0; i < boneCount; i++) {
            r.get(i).sort(byTime);
            p.get(i).sort(byTime);
            s.get(i).sort(byTime);
            rotation[i] = r.get(i).toArray(new Keyframe[0]);
            position[i] = p.get(i).toArray(new Keyframe[0]);
            scale[i] = s.get(i).toArray(new Keyframe[0]);
        }
        float length = animation.has("length") ? animation.get("length").getAsFloat() : 0;
        return new Animation(length, rotation, position, scale);
    }

    /**
     * A keyframe value. Blockbench stores them as text since they may be Molang,
     * anything that isn't a plain number is played as the fallback.
     */
    private static float number(JsonObject point, String key, float fallback, int[] unsupported) {
        if (!point.has(key)) return fallback;
        String text = point.get(key).getAsString().trim();
        if (text.isEmpty()) return fallback;
        try {
            float value = Float.parseFloat(text);
            if (Float.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // Molang, counted below
        }
        unsupported[0]++;
        return fallback;
    }

    private static @Nullable BufferedImage readTexture(JsonObject texture) {
        String source = texture.has("source") ? texture.get("source").getAsString() : "";
        int comma = source.indexOf(',');
        if (!source.startsWith("data:image/png;base64,") || comma < 0) return null;

        // Checked on the header before the pixels are decoded, a huge image is
        // the one thing in here that could eat the memory
        byte[] png = Base64.getDecoder().decode(source.substring(comma + 1));
        if (png.length < 24) return null;
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        if (width <= 0 || height <= 0 || width > MAX_TEXTURE_SIDE || height > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException("has a " + width + "x" + height + " texture, the limit is "
                    + MAX_TEXTURE_SIDE + "x" + MAX_TEXTURE_SIDE);
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException ex) {
            throw new IllegalArgumentException("has a texture that is not a readable png", ex);
        }
    }

    /**
     * The texture of every face of a cube, one palette block per texel laid out
     * the way the face is seen from outside: cells[row][column], row 0 at the
     * top, column 0 on the left.
     */
    // NullAway cannot track @Nullable elements of a 2D array yet; null means a see-through cell here.
    @SuppressWarnings("NullAway")
    private static List<Face> sampleFaces(JsonObject element, List<BufferedImage> textures, List<float[]> textureUv,
                                          boolean[] emissive, Map<Material, Integer> palette) {
        List<Face> result = new ArrayList<>();
        JsonObject faces = element.has("faces") ? element.getAsJsonObject("faces") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
            if (!FACES.contains(entry.getKey())) continue;
            JsonObject face = entry.getValue().getAsJsonObject();
            JsonElement textureIndex = face.get("texture");
            if (textureIndex == null || !textureIndex.isJsonPrimitive() || !textureIndex.getAsJsonPrimitive().isNumber()) continue;
            int index = textureIndex.getAsInt();
            if (index < 0 || index >= textures.size() || textures.get(index) == null || !face.has("uv")) continue;
            // A face with only a glow layer on it has no surface to draw. The
            // helper cube ModelEngine models keep at their origin is all such faces
            if (emissive[index]) continue;

            BufferedImage image = textures.get(index);
            JsonArray uv = face.getAsJsonArray("uv");
            float sx = image.getWidth() / textureUv.get(index)[0];
            float sy = image.getHeight() / textureUv.get(index)[1];
            // Left to right and top to bottom as seen, a mirrored face has u0 > u1
            float u0 = uv.get(0).getAsFloat() * sx;
            float v0 = uv.get(1).getAsFloat() * sy;
            float u1 = uv.get(2).getAsFloat() * sx;
            float v1 = uv.get(3).getAsFloat() * sy;

            // Quarter turns clockwise, a turned face shows its texture sideways
            int turns = Math.floorMod(face.has("rotation") ? face.get("rotation").getAsInt() / 90 : 0, 4);
            int texelsU = Math.max(1, Math.min(MAX_TEXTURE_SIDE, Math.round(Math.abs(u1 - u0))));
            int texelsV = Math.max(1, Math.min(MAX_TEXTURE_SIDE, Math.round(Math.abs(v1 - v0))));
            int columns = turns % 2 == 0 ? texelsU : texelsV;
            int rows = turns % 2 == 0 ? texelsV : texelsU;

            @Nullable Material[][] cells = new Material[rows][columns];
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    float s = (column + 0.5f) / columns;
                    float t = (row + 0.5f) / rows;
                    float a = switch (turns) { case 1 -> t; case 2 -> 1 - s; case 3 -> 1 - t; default -> s; };
                    float b = switch (turns) { case 1 -> 1 - s; case 2 -> 1 - t; case 3 -> s; default -> t; };
                    int x = Math.max(0, Math.min(image.getWidth() - 1, (int) Math.floor(u0 + (u1 - u0) * a)));
                    int y = Math.max(0, Math.min(image.getHeight() - 1, (int) Math.floor(v0 + (v1 - v0) * b)));
                    int argb = image.getRGB(x, y);
                    cells[row][column] = (argb >>> 24) < 128 ? null : closest(argb, palette);
                }
            }
            result.add(new Face(entry.getKey(), cells));
        }
        return result;
    }

    /**
     * The block a cube is drawn with: every opaque texel on its faces votes for
     * the palette block closest to it.
     *
     * @return null when the cube is an overlay, or untextured, and is not drawn
     */
    private static @Nullable Material pickBlock(List<Face> faces) {
        Map<Material, Integer> votes = new EnumMap<>(Material.class);
        int total = 0;
        int opaque = 0;
        for (Face face : faces) {
            for (Material[] row : face.cells()) {
                for (Material cell : row) {
                    total++;
                    if (cell == null) continue;
                    opaque++;
                    votes.merge(cell, 1, Integer::sum);
                }
            }
        }

        // No texture on any face is how Blockbench shows nothing (a hitbox, a
        // helper), so it is treated as an overlay with nothing painted on it
        if (total == 0 || opaque < total * MIN_OPAQUE_SHARE) return null;
        return Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    /**
     * The parts of a cube's texture that stand out from its block, as thin
     * slabs laid on its faces: eyes, a nose, stripes.
     * <p>
     * Texels that look like the cube's own block are left out, the rest are
     * merged into as few rectangles as possible, and each rectangle is scored so
     * a small but sharp detail like an eye beats a big faint one.
     */
    private static void addDetails(Drawn cube, List<Drawn> all, Map<Material, Integer> palette, double weight,
                                   List<Scored> out) {
        boolean overlay = cube.block() == null;
        int base = overlay ? 0 : palette.getOrDefault(cube.block(), 0xFFFFFF);
        for (Face face : cube.faces()) {
            // Nobody looks at a pet from below
            if (face.direction().equals("down") || isCovered(cube, face.direction(), all)) continue;

            @Nullable Material[][] cells = face.cells();
            int rows = cells.length;
            int columns = rows == 0 ? 0 : cells[0].length;
            boolean[][] used = new boolean[rows][columns];
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    Material block = cells[row][column];
                    if (used[row][column] || block == null) continue;
                    int colour = palette.getOrDefault(block, 0xFFFFFF);
                    // On an overlay every drawn texel was put there on purpose
                    if (!overlay && distance(colour, base) < SAME_COLOUR) continue;

                    // Grow right, then down, as long as the colour holds
                    int lastColumn = column;
                    while (lastColumn + 1 < columns && !used[row][lastColumn + 1]
                            && similar(cells[row][lastColumn + 1], colour, palette)) lastColumn++;
                    int lastRow = row;
                    while (lastRow + 1 < rows && rowSimilar(cells[lastRow + 1], used[lastRow + 1], column, lastColumn, colour, palette)) {
                        lastRow++;
                    }
                    for (int r = row; r <= lastRow; r++) {
                        for (int c = column; c <= lastColumn; c++) used[r][c] = true;
                    }

                    Matrix4f local = detailBox(cube, face.direction(),
                            column / (float) columns, (lastColumn + 1) / (float) columns,
                            row / (float) rows, (lastRow + 1) / (float) rows, DETAIL_DEPTH);
                    int area = (lastColumn - column + 1) * (lastRow - row + 1);
                    double contrast = overlay ? OVERLAY_CONTRAST : distance(colour, base);
                    out.add(new Scored(Math.sqrt(area) * contrast * weight,
                            new Cube(cube.name(), cube.bone(), local, block, true)));
                }
            }
        }
    }

    /**
     * Bones every played animation keeps scaled to nothing, and everything
     * under them. A model hides geometry that way that only other animations
     * show, like the fire and impact effects of an attack.
     * <p>
     * A bone counts only when each played animation has scale keyframes for
     * it and every one of them flattens at least one axis; an animation that
     * leaves the bone alone shows it at full size.
     */
    private static boolean[] hiddenBones(List<Bone> bones, Map<String, Animation> animations) {
        boolean[] hidden = new boolean[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            int parent = bones.get(i).parent();
            boolean flattened = !animations.isEmpty();
            for (Animation animation : animations.values()) {
                Keyframe[] scale = animation.scale()[i];
                if (scale.length == 0 || !Arrays.stream(scale).allMatch(BBModel::flat)) {
                    flattened = false;
                    break;
                }
            }
            hidden[i] = flattened || (parent >= 0 && hidden[parent]);
        }
        return hidden;
    }

    private static boolean flat(Keyframe frame) {
        return Math.abs(frame.x()) < 1e-3 || Math.abs(frame.y()) < 1e-3 || Math.abs(frame.z()) < 1e-3;
    }

    /**
     * Which bones are the head bone or hang under it. Parents come before
     * their children, so one pass settles it.
     */
    private static boolean[] headBones(List<Bone> bones, int head) {
        boolean[] onHead = new boolean[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            int parent = bones.get(i).parent();
            onHead[i] = i == head || (parent >= 0 && onHead[parent]);
        }
        return onHead;
    }

    /**
     * Spread stacked flat planes apart, painted all over (an eye white) or
     * cut out (a wing). A plane just in front of a solid face moves out past
     * that face's details, and planes stacked on it each move out LAYER_GAP
     * past the one before. Out is away from the middle of the solid cubes,
     * where the layers are meant to be seen from. Each plane is drawn
     * PLANE_THICKNESS thick behind its new position.
     * <p>
     * ponytail: compares the planes in the model at rest and only planes with
     * the same element rotation; stacks inside rotated or animated bones that
     * still end up closer than the gap are left as they are.
     */
    private static List<Drawn> separateLayers(List<Drawn> drawn) {
        Vector3f low = new Vector3f(Float.MAX_VALUE);
        Vector3f high = new Vector3f(-Float.MAX_VALUE);
        for (Drawn cube : drawn) {
            if (!isBody(cube)) continue;
            low.min(cube.min());
            high.max(new Vector3f(cube.min()).add(cube.size()));
        }
        Vector3f middle = low.x > high.x ? new Vector3f() : new Vector3f(low).add(high).mul(0.5f);

        // Where each cut-out's plane sits along its thin axis, pushed out as needed
        List<Drawn> result = new ArrayList<>(drawn);
        List<Integer> planes = new ArrayList<>();
        for (int i = 0; i < drawn.size(); i++) {
            if (isPlane(drawn.get(i))) planes.add(i);
        }
        float[] position = new float[drawn.size()];
        int[] axis = new int[drawn.size()];
        float[] outward = new float[drawn.size()];
        for (int i : planes) {
            Drawn cube = drawn.get(i);
            axis[i] = thinAxis(cube.size());
            position[i] = cube.min().get(axis[i]) + cube.size().get(axis[i]) / 2;
            outward[i] = position[i] >= middle.get(axis[i]) ? 1 : -1;
        }

        // Every layer clears the details painted on the one behind it
        float clearance = DETAIL_DEPTH + LAYER_GAP;
        // Innermost first, so each plane is pushed past the ones behind it
        planes.sort(Comparator.comparingDouble(i -> position[i] * outward[i]));
        for (int k = 0; k < planes.size(); k++) {
            int i = planes.get(k);
            // A plane just in front of a solid face, a painted eye on a head
            for (Drawn solid : drawn) {
                if (!isBody(solid) || !solid.placed().equals(drawn.get(i).placed(), 1e-4f)) continue;
                if (!overlaps(solid, drawn.get(i), axis[i])) continue;
                float face = outward[i] > 0 ? solid.min().get(axis[i]) + solid.size().get(axis[i]) : solid.min().get(axis[i]);
                float ahead = (position[i] - face) * outward[i];
                // Sunk into the face by less than its own padding still counts as on it
                if (ahead >= -MIN_CUBE_SIZE && ahead < clearance) position[i] = face + clearance * outward[i];
            }
            for (int j = 0; j < k; j++) {
                int other = planes.get(j);
                if (axis[other] != axis[i] || outward[other] != outward[i]) continue;
                if (!drawn.get(other).placed().equals(drawn.get(i).placed(), 1e-4f)) continue;
                if (!overlaps(drawn.get(other), drawn.get(i), axis[i])) continue;
                float ahead = (position[i] - position[other]) * outward[i];
                if (ahead < clearance) position[i] = position[other] + clearance * outward[i];
            }
        }

        for (int i : planes) {
            Drawn cube = drawn.get(i);
            Vector3f min = new Vector3f(cube.min());
            Vector3f size = new Vector3f(cube.size());
            // Behind the plane, seen from its outer side
            float back = outward[i] > 0 ? position[i] - PLANE_THICKNESS : position[i];
            min.setComponent(axis[i], back);
            size.setComponent(axis[i], PLANE_THICKNESS);
            result.set(i, new Drawn(cube.name(), cube.bone(), cube.placed(), min, size, cube.block(), cube.faces(), cube.cutout()));
        }
        return result;
    }

    /**
     * A flat plane that is drawn: a cut-out, or one painted all over.
     */
    private static boolean isPlane(Drawn cube) {
        Vector3f size = cube.size();
        return (cube.cutout() || cube.block() != null) && Math.min(size.x, Math.min(size.y, size.z)) < FLAT_SIZE;
    }

    /**
     * A solid cube with some depth, what planes are laid on.
     */
    private static boolean isBody(Drawn cube) {
        return cube.block() != null && !isPlane(cube);
    }

    private static int thinAxis(Vector3f size) {
        if (size.x <= size.y && size.x <= size.z) return 0;
        return size.y <= size.z ? 1 : 2;
    }

    /**
     * Whether two planes on this axis cover some of the same area.
     */
    private static boolean overlaps(Drawn a, Drawn b, int axis) {
        for (int other = 0; other < 3; other++) {
            if (other == axis) continue;
            float aLow = a.min().get(other), aHigh = aLow + a.size().get(other);
            float bLow = b.min().get(other), bHigh = bLow + b.size().get(other);
            if (Math.min(aHigh, bHigh) - Math.max(aLow, bLow) <= 1e-3f) return false;
        }
        return true;
    }

    private static double opaqueShare(List<Face> faces) {
        int total = 0;
        int opaque = 0;
        for (Face face : faces) {
            for (Material[] row : face.cells()) {
                for (Material cell : row) {
                    total++;
                    if (cell != null) opaque++;
                }
            }
        }
        return total == 0 ? 0 : opaque / (double) total;
    }

    /**
     * A cut-out plane as slabs as thick as the plane, one per rectangle of
     * like coloured opaque pixels, so it keeps its outline. Both sides of a
     * plane show the same picture, so only its biggest face is read.
     */
    private static void addCutout(Drawn cube, Map<Material, Integer> palette, List<Cube> out) {
        // By its size in the model, not its pixels: every face of a plane can
        // map the same texture area, the thin edges included. Of the two big
        // sides the more painted one wins, some planes only paint their front
        Face face = null;
        float largest = -1;
        int painted = -1;
        Vector3f size = cube.size();
        for (Face candidate : cube.faces()) {
            float area = switch (candidate.direction()) {
                case "north", "south" -> size.x * size.y;
                case "east", "west" -> size.z * size.y;
                default -> size.x * size.z;
            };
            @Nullable Material[][] candidateCells = candidate.cells();
            if (candidateCells.length == 0 || candidateCells[0].length == 0) continue;
            int candidatePainted = countPainted(candidateCells);
            boolean bigger = area > largest + 1e-4f;
            boolean sameSizeMorePainted = Math.abs(area - largest) <= 1e-4f && candidatePainted > painted;
            if (bigger || sameSizeMorePainted) {
                largest = area;
                painted = candidatePainted;
                face = candidate;
            }
        }
        if (face == null) return;

        @Nullable Material[][] cells = face.cells();
        if (cells.length * cells[0].length > CUTOUT_FULL_RES_CELLS) cells = halve(cells);
        float thickness = switch (face.direction()) {
            case "north", "south" -> size.z;
            case "east", "west" -> size.x;
            default -> size.y;
        };
        int rows = cells.length;
        int columns = cells[0].length;
        boolean[][] used = new boolean[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Material block = cells[row][column];
                if (used[row][column] || block == null) continue;
                int colour = palette.getOrDefault(block, 0xFFFFFF);

                int lastColumn = column;
                while (lastColumn + 1 < columns && !used[row][lastColumn + 1]
                        && similar(cells[row][lastColumn + 1], colour, palette)) lastColumn++;
                int lastRow = row;
                while (lastRow + 1 < rows && rowSimilar(cells[lastRow + 1], used[lastRow + 1], column, lastColumn, colour, palette)) {
                    lastRow++;
                }
                for (int r = row; r <= lastRow; r++) {
                    for (int c = column; c <= lastColumn; c++) used[r][c] = true;
                }

                Matrix4f local = detailBox(cube, face.direction(),
                        column / (float) columns, (lastColumn + 1) / (float) columns,
                        row / (float) rows, (lastRow + 1) / (float) rows, -thickness);
                out.add(new Cube(cube.name(), cube.bone(), local, block, false));
            }
        }
    }

    private static int countPainted(@Nullable Material[][] cells) {
        int painted = 0;
        for (Material[] row : cells) {
            for (Material cell : row) {
                if (cell != null) painted++;
            }
        }
        return painted;
    }

    /**
     * The picture at half the resolution: a 2x2 block of pixels becomes the
     * block most of its opaque pixels are, when at least two are opaque.
     */
    // NullAway cannot track @Nullable elements of a 2D array yet; null means a see-through cell here.
    @SuppressWarnings("NullAway")
    private static @Nullable Material[][] halve(@Nullable Material[][] cells) {
        int rows = (cells.length + 1) / 2;
        int columns = (cells[0].length + 1) / 2;
        @Nullable Material[][] out = new Material[rows][columns];
        Map<Material, Integer> votes = new EnumMap<>(Material.class);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                votes.clear();
                int opaque = 0;
                for (int r = row * 2; r < Math.min(row * 2 + 2, cells.length); r++) {
                    for (int c = column * 2; c < Math.min(column * 2 + 2, cells[0].length); c++) {
                        if (cells[r][c] == null) continue;
                        opaque++;
                        votes.merge(cells[r][c], 1, Integer::sum);
                    }
                }
                out[row][column] = opaque >= 2 ? Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey() : null;
            }
        }
        return out;
    }

    private static boolean similar(@Nullable Material block, int colour, Map<Material, Integer> palette) {
        return block != null && distance(palette.getOrDefault(block, 0xFFFFFF), colour) < SAME_COLOUR;
    }

    private static boolean rowSimilar(Material[] row, boolean[] used, int from, int to, int colour, Map<Material, Integer> palette) {
        for (int c = from; c <= to; c++) {
            if (used[c] || !similar(row[c], colour, palette)) return false;
        }
        return true;
    }

    /**
     * A face that lies inside another cube can never be seen, the top of a leg
     * under the body for one. Tested on the model at rest, element rotations
     * ignored.
     */
    // Identity on purpose: a cube must skip itself, not an equal-looking twin.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean isCovered(Drawn cube, String direction, List<Drawn> all) {
        Vector3f min = cube.min();
        Vector3f max = new Vector3f(min).add(cube.size());
        Vector3f point = new Vector3f(min).add(max).mul(0.5f);
        float nudge = 0.05f;
        switch (direction) {
            case "north" -> point.z = min.z - nudge;
            case "south" -> point.z = max.z + nudge;
            case "west" -> point.x = min.x - nudge;
            case "east" -> point.x = max.x + nudge;
            case "up" -> point.y = max.y + nudge;
            default -> point.y = min.y - nudge;
        }
        for (Drawn other : all) {
            // An overlay hides nothing, it is mostly holes
            if (other == cube || other.block() == null) continue;
            Vector3f oMin = other.min();
            Vector3f oMax = new Vector3f(oMin).add(other.size());
            if (point.x > oMin.x && point.x < oMax.x && point.y > oMin.y && point.y < oMax.y
                    && point.z > oMin.z && point.z < oMax.z) return true;
        }
        return false;
    }

    /**
     * The slab covering part of one face, from s0..s1 left to right and t0..t1
     * top to bottom as the face is seen from outside, standing d pixels proud
     * of it; a negative d reaches into the cube instead.
     * <p>
     * Which way is left on each face follows Java block models: north runs
     * east to west, east runs south to north, up has north at the top.
     */
    private static Matrix4f detailBox(Drawn cube, String direction, float s0, float s1, float t0, float t1, float d) {
        Vector3f min = cube.min();
        Vector3f size = cube.size();
        float x0 = min.x, z0 = min.z;
        float x1 = min.x + size.x, y1 = min.y + size.y, z1 = min.z + size.z;

        Vector3f a;
        Vector3f b;
        switch (direction) {
            case "north" -> {
                a = new Vector3f(x1 - size.x * s0, y1 - size.y * t0, z0);
                b = new Vector3f(x1 - size.x * s1, y1 - size.y * t1, z0 - d);
            }
            case "south" -> {
                a = new Vector3f(x0 + size.x * s0, y1 - size.y * t0, z1);
                b = new Vector3f(x0 + size.x * s1, y1 - size.y * t1, z1 + d);
            }
            case "east" -> {
                a = new Vector3f(x1, y1 - size.y * t0, z1 - size.z * s0);
                b = new Vector3f(x1 + d, y1 - size.y * t1, z1 - size.z * s1);
            }
            case "west" -> {
                a = new Vector3f(x0, y1 - size.y * t0, z0 + size.z * s0);
                b = new Vector3f(x0 - d, y1 - size.y * t1, z0 + size.z * s1);
            }
            default -> { // up
                a = new Vector3f(x0 + size.x * s0, y1, z0 + size.z * t0);
                b = new Vector3f(x0 + size.x * s1, y1 + d, z0 + size.z * t1);
            }
        }
        Vector3f boxMin = new Vector3f(a).min(b);
        Vector3f boxSize = new Vector3f(a).max(b).sub(boxMin);
        return new Matrix4f(cube.placed()).translate(boxMin).scale(boxSize);
    }

    /**
     * The palette block closest to a colour, weighted the way the eye sees
     * red/green/blue differences ("redmean").
     */
    static Material closest(int rgb, Map<Material, Integer> palette) {
        Material best = Material.WHITE_CONCRETE;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Material, Integer> entry : palette.entrySet()) {
            double distance = distance(rgb, entry.getValue());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static double distance(int rgb, int other) {
        int r = (rgb >> 16) & 0xFF;
        int pr = (other >> 16) & 0xFF;
        int dr = r - pr;
        int dg = ((rgb >> 8) & 0xFF) - ((other >> 8) & 0xFF);
        int db = (rgb & 0xFF) - (other & 0xFF);
        double mean = (r + pr) / 2.0;
        return (2 + mean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - mean) / 256) * db * db;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static Vector3f vector(JsonObject object, String key, float fallback) {
        JsonArray array = array(object, key);
        if (array.size() < 3) return new Vector3f(fallback);
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

}
