package com.CodDev.vehicle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * .bbmodel -> resource pack. Every Blockbench file in plugins/Vehicle/models/ (generic, Java block/item,
 * Bedrock or GeckoLib format - animations are ignored, only the rest pose is used) becomes one Java item
 * model with its embedded textures. The whole model is centred and scaled into the Java model range
 * [-16, 32] and the display entity compensates with the inverse scale, so vehicles can be any size.
 * Group/cube rotations: exact multiples of 90 degrees are folded into the box, the rest is snapped to a
 * single axis in 22.5-degree steps (the Java model limit).
 *
 * Output: plugins/Vehicle/generated/Vehicle-pack.zip (+ Vehicle-pack.sha1, pack-report.txt).
 */
public final class PackGenerator {

    /** How the display entity must be set up for a model: scale 1/fitScale, translation (tx,ty,tz) blocks
     *  so that Blockbench (0,0,0) sits exactly at the vehicle's position and 16 px = 1 block. */
    public record ModelInfo(String name, String path, double fitScale, double tx, double ty, double tz, int cubes,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }

    public record Result(int models, List<String> warnings, File zip, long bytes, String sha1, long millis) { }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final String NS = "vehicle";

    private final VehiclePlugin plugin;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, String> cmdToModel = new LinkedHashMap<>();
    /** composite mode: model name -> wrapper paths (one per group / free-rotated cube), shown as minecraft:composite. */
    private final Map<String, List<String>> compositeParts = new LinkedHashMap<>();
    /** model -> animation name -> [frames, frame-ticks] (composite mode only). */
    private final Map<String, Map<String, int[]>> animations = new LinkedHashMap<>();
    public Map<String, Map<String, int[]>> animations() { return animations; }
    private final Map<String, ModelInfo> infos = new LinkedHashMap<>();
    private final List<String> report = new ArrayList<>();

    public PackGenerator(VehiclePlugin plugin) { this.plugin = plugin; }

    public static File modelsDir(VehiclePlugin plugin) { return new File(plugin.getDataFolder(), "models"); }
    public static File generatedDir(VehiclePlugin plugin) { return new File(plugin.getDataFolder(), "generated"); }

    /** Model names = file names in models/ without .bbmodel, sorted. */
    public static List<String> scanNames(VehiclePlugin plugin) {
        List<String> names = new ArrayList<>();
        File[] list = modelsDir(plugin).listFiles();
        if (list != null) for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
                names.add(f.getName().substring(0, f.getName().length() - ".bbmodel".length()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** A resource-location-safe path: one illegal character would make the client reject the whole items file. */
    static String path(String name) {
        String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return p.isEmpty() ? "model" : p;
    }

    public Map<String, ModelInfo> infos() { return infos; }

    public Result generate() throws IOException {
        long t0 = System.currentTimeMillis();
        File dir = modelsDir(plugin);
        dir.mkdirs();
        File out = generatedDir(plugin);
        out.mkdirs();
        int models = 0;
        Set<String> usedPaths = new LinkedHashSet<>();
        for (String name : scanNames(plugin)) {
            File bb = new File(dir, name + ".bbmodel");
            try {
                String p = path(name);
                if (!usedPaths.add(p)) { warnings.add(name + ": skipped - another model already uses the path '" + p + "'"); continue; }
                convert(bb, name, p);
                models++;
            } catch (Exception e) {
                warnings.add(name + ": could not convert - " + e.getMessage());
                plugin.getLogger().warning("Model " + name + " could not be converted: " + e);
            }
        }
        String base = plugin.baseItem().getKey().getKey();
        itemsFile(base);

        JsonObject pack = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("description", plugin.getConfig().getString("pack.description", "Vehicle models (generated)"));
        int min = plugin.getConfig().getInt("pack.min-format", 46), max = plugin.getConfig().getInt("pack.max-format", 999);
        p.addProperty("pack_format", min);
        p.addProperty("min_format", min);
        p.addProperty("max_format", max);
        pack.add("pack", p);
        put("pack.mcmeta", GSON.toJson(pack));

        report.add(0, "Vehicle pack report - " + new java.util.Date());
        report.add(1, "model name (use in /vehicle edit <id> model <name>)  ->  model path");
        report.add("");
        report.add("warnings:");
        if (warnings.isEmpty()) report.add("  none"); else for (String w : warnings) report.add("  ! " + w);
        put("pack-report.txt", String.join("\n", report));
        try { Files.writeString(new File(out, "pack-report.txt").toPath(), String.join("\n", report)); } catch (IOException ignored) { }

        org.bukkit.configuration.file.YamlConfiguration idx = new org.bukkit.configuration.file.YamlConfiguration();
        for (var m : animations.entrySet()) for (var c : m.getValue().entrySet()) {
            idx.set(m.getKey() + "." + c.getKey() + ".frames", c.getValue()[0]);
            idx.set(m.getKey() + "." + c.getKey() + ".frame-ticks", c.getValue()[1]);
        }
        try { idx.save(new File(out, "anim-index.yml")); } catch (IOException ignored) { }
        File zip = new File(out, "Vehicle-pack.zip");
        File tmp = new File(out, "Vehicle-pack.zip.tmp");
        long bytes = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
                bytes += e.getValue().length;
            }
        }
        Files.move(tmp.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String sha1 = sha1(zip);
        Files.writeString(new File(out, "Vehicle-pack.sha1").toPath(), sha1 + "\n");
        return new Result(models, warnings, zip, bytes, sha1, System.currentTimeMillis() - t0);
    }

    private static String sha1(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { return ""; }
    }

    private void put(String path, String text) { files.put(path, text.getBytes(StandardCharsets.UTF_8)); }

    /** assets/minecraft/items/<base>.json: pick the model by the custom_model_data string. */
    private void itemsFile(String base) {
        JsonArray cases = new JsonArray();
        for (var e : cmdToModel.entrySet()) {
            List<String> parts = compositeParts.get(e.getKey());
            JsonObject c = new JsonObject();
            c.addProperty("when", e.getKey());
            if (parts != null) {
                JsonObject comp = new JsonObject();
                comp.addProperty("type", "minecraft:composite");
                JsonArray models = new JsonArray();
                for (String part : parts) {
                    if (!files.containsKey("assets/" + NS + "/models/item/" + part + ".json")) continue;
                    JsonObject m = new JsonObject();
                    m.addProperty("type", "minecraft:model");
                    m.addProperty("model", NS + ":item/" + part);
                    models.add(m);
                }
                if (models.isEmpty()) continue;
                comp.add("models", models);
                c.add("model", comp);
                cases.add(c);
                report.add("  \"" + e.getKey() + "\" -> composite of " + models.size() + " part(s) (exact rotations)");
                continue;
            }
            if (!files.containsKey("assets/" + NS + "/models/item/" + e.getValue() + ".json")) continue;
            JsonObject m = new JsonObject();
            m.addProperty("type", "minecraft:model");
            m.addProperty("model", NS + ":item/" + e.getValue());
            c.add("model", m);
            cases.add(c);
            report.add("  \"" + e.getKey() + "\" -> " + NS + ":item/" + e.getValue());
        }
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.add("cases", cases);
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "minecraft:model");
        fallback.addProperty("model", "minecraft:item/" + base);
        select.add("fallback", fallback);
        JsonObject root = new JsonObject();
        root.add("model", select);
        put("assets/minecraft/items/" + base + ".json", GSON.toJson(root));
    }

    // ------------------------------------------------------------------ bbmodel conversion

    private static final class Bone {
        String uuid, name; double[] origin = {0, 0, 0}; double[] rotation = {0, 0, 0};
        Bone parent; List<Bone> children = new ArrayList<>();
    }

    private static final class Cube {
        String uuid, name = ""; double[] from, to, origin = {0, 0, 0}, rotation = {0, 0, 0}; double inflate = 0;
        JsonObject faces; Bone bone; boolean mirror; double[] uvOffset = {0, 0}; boolean boxUv;
    }

    private void convert(File bb, String name, String mpath) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(bb.toPath())).getAsJsonObject();
        if (!mpath.equals(name)) warnings.add("'" + name + "' has upper-case letters or symbols - its files are written as '" + mpath + "' (the model name you use stays '" + name + "')");
        double resW = 16, resH = 16;
        if (root.has("resolution")) {
            JsonObject r = root.getAsJsonObject("resolution");
            resW = num(r.get("width"), 16); resH = num(r.get("height"), 16);
        }
        boolean metaBoxUv = root.has("meta") && root.getAsJsonObject("meta").has("box_uv") && root.getAsJsonObject("meta").get("box_uv").getAsBoolean();

        // --- textures
        Map<String, Integer> texIndex = new HashMap<>();
        List<double[]> texRes = new ArrayList<>();
        JsonObject textures = new JsonObject();
        int ti = 0;
        if (root.has("textures")) for (JsonElement te : root.getAsJsonArray("textures")) {
            JsonObject t = te.getAsJsonObject();
            String tname = str(t.get("name"), "tex" + ti).replaceAll("\\.png$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (tname.isEmpty()) tname = "tex" + ti;
            String src = str(t.get("source"), "");
            byte[] png = null;
            if (src.contains("base64,")) {
                try { png = Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7)); } catch (Exception ignored) { }
            }
            if (png == null) {
                // not embedded: look next to the model file, then at the path Blockbench recorded
                List<File> candidates = new ArrayList<>();
                String orig = str(t.get("name"), "");
                if (!orig.isEmpty()) { candidates.add(new File(bb.getParentFile(), orig)); candidates.add(new File(bb.getParentFile(), orig.endsWith(".png") ? orig : orig + ".png")); candidates.add(new File(new File(bb.getParentFile(), "textures"), orig.endsWith(".png") ? orig : orig + ".png")); }
                String rel = str(t.get("relative_path"), "");
                if (!rel.isEmpty()) candidates.add(new File(bb.getParentFile(), rel));
                String abs = str(t.get("path"), "");
                if (!abs.isEmpty()) candidates.add(new File(abs));
                for (File c : candidates) if (c.isFile()) { png = Files.readAllBytes(c.toPath()); break; }
            }
            if (png == null) warnings.add(name + ": texture '" + tname + "' is not embedded and was not found next to the model - the model will show missing-texture purple there");
            else files.put("assets/" + NS + "/textures/item/" + mpath + "/" + tname + ".png", png);
            textures.addProperty(String.valueOf(ti), NS + ":item/" + mpath + "/" + tname);
            if (ti == 0) textures.addProperty("particle", NS + ":item/" + mpath + "/" + tname);
            texIndex.put(str(t.get("uuid"), "u" + ti), ti);
            texIndex.put(str(t.get("id"), String.valueOf(ti)), ti);
            texIndex.put(String.valueOf(ti), ti);
            texRes.add(new double[]{ num(t.get("uv_width"), resW), num(t.get("uv_height"), resH) });
            ti++;
        }
        if (ti == 0) {
            textures.addProperty("0", "minecraft:block/iron_block");
            textures.addProperty("particle", "minecraft:block/iron_block");
            texRes.add(new double[]{resW, resH});
            warnings.add(name + ": no textures in the file - rendered with iron blocks");
        }

        // --- cubes
        Map<String, Cube> cubes = new LinkedHashMap<>();
        int meshes = 0;
        List<String> meshNames = new ArrayList<>();
        if (root.has("elements")) for (JsonElement ee : root.getAsJsonArray("elements")) {
            JsonObject e = ee.getAsJsonObject();
            if (e.has("visibility") && !e.get("visibility").getAsBoolean()) continue;
            if (e.has("export") && !e.get("export").getAsBoolean()) continue;
            if (e.has("type") && "mesh".equals(str(e.get("type"), "cube"))) {
                // Blockbench MESH (cylinder wheels, bevels...): Java item models only hold cubes - use its bounding box
                // so the part exists, and report it (rebuild from cubes for the real shape).
                Cube m = meshBox(e, cubes.size());
                if (m != null) { cubes.put(m.uuid, m); meshes++; meshNames.add(m.name); }
                continue;
            }
            if (e.has("type") && !"cube".equals(str(e.get("type"), "cube"))) continue;
            if (!e.has("from") || !e.has("to")) continue;
            Cube c = new Cube();
            c.uuid = str(e.get("uuid"), "e" + cubes.size());
            c.name = str(e.get("name"), "");
            c.from = vec(e.get("from")); c.to = vec(e.get("to"));
            if (e.has("origin")) c.origin = vec(e.get("origin"));
            if (e.has("inflate")) c.inflate = num(e.get("inflate"), 0);
            if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
            c.mirror = e.has("mirror_uv") && e.get("mirror_uv").isJsonPrimitive() && e.get("mirror_uv").getAsBoolean();
            c.boxUv = e.has("box_uv") ? e.get("box_uv").getAsBoolean() : metaBoxUv;
            if (e.has("uv_offset") && e.get("uv_offset").isJsonArray()) { JsonArray u = e.getAsJsonArray("uv_offset"); c.uvOffset = new double[]{ num(u.get(0), 0), num(u.get(1), 0) }; }
            c.faces = e.has("faces") && e.get("faces").isJsonObject() ? e.getAsJsonObject("faces") : new JsonObject();
            cubes.put(c.uuid, c);
        }
        if (meshes > 0) warnings.add(name + ": " + meshes + " MESH element(s) " + meshNames + " shown as their bounding boxes - Java item models only hold cubes; rebuild wheels/round parts from cubes for the real shape");
        if (cubes.isEmpty()) throw new IOException("no cube elements");

        // --- bones (outliner groups)
        Map<String, Bone> bones = new HashMap<>();
        if (root.has("outliner")) for (JsonElement oe : root.getAsJsonArray("outliner")) parseOutliner(oe, null, bones, cubes);

        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (Cube c : cubes.values()) for (int i = 0; i < 3; i++) {
            mn[i] = Math.min(mn[i], Math.min(c.from[i], c.to[i]) - c.inflate);
            mx[i] = Math.max(mx[i], Math.max(c.from[i], c.to[i]) + c.inflate);
        }
        if (!plugin.getConfig().getString("pack.mode", "composite").equalsIgnoreCase("baked")) {
            // COMPOSITE: every group (and every cube with a free rotation angle) is its own sub-model whose display
            // transform carries the EXACT rotation and position; no 22.5-degree rounding, no global shrinking.
            int parts = emitComposite(name, mpath, cubes, bones, textures, texIndex, texRes, null, 0, "");
            cmdToModel.put(name, mpath + "__p0");
            // animations: every animation in the file is baked into frames <name>_<anim>_<i>; the plugin plays the one
            // chosen with /vehicle edit <id> animation set <anim> while driving (loops), idle <anim> while standing.
            if (root.has("animations") && root.get("animations").isJsonArray()) {
                int frameTicks = Math.max(1, plugin.getConfig().getInt("pack.frame-ticks", 1));
                int maxFrames = Math.max(2, plugin.getConfig().getInt("pack.max-frames", 40));
                Map<String, int[]> clips = new LinkedHashMap<>();
                for (JsonElement ae : root.getAsJsonArray("animations")) {
                    JsonObject a = ae.getAsJsonObject();
                    Clip clip = new Clip();
                    clip.name = str(a.get("name"), "anim");
                    clip.length = num(a.get("length"), 0);
                    String key = clip.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    if (key.isEmpty() || clips.containsKey(key) || clip.length <= 0) continue;
                    if (a.has("animators")) for (var en : a.getAsJsonObject("animators").entrySet()) {
                        if (!en.getValue().isJsonObject()) continue;
                        JsonObject an = en.getValue().getAsJsonObject();
                        if (!an.has("keyframes")) continue;
                        for (JsonElement ke : an.getAsJsonArray("keyframes")) {
                            JsonObject k = ke.getAsJsonObject();
                            String ch = str(k.get("channel"), "");
                            if (ch.equals("rotation")) clip.rot.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                            else if (ch.equals("position")) clip.pos.computeIfAbsent(en.getKey(), x -> new ArrayList<>()).add(k);
                        }
                    }
                    int n = 0;
                    if (a.has("snapping")) { double snap = num(a.get("snapping"), 0); if (snap > 0) n = (int) Math.ceil(clip.length * snap); }
                    if (n <= 0) n = (int) Math.ceil(clip.length * 20.0 / frameTicks);
                    n = Math.max(1, Math.min(maxFrames, n));
                    int clipTicks = Math.max(1, (int) Math.round(clip.length * 20.0 / n));
                    for (int i = 1; i <= n; i++) {
                        double t = n == 1 ? clip.length : (i - 1) * (clip.length / (n - 1));
                        String suffix = "_" + key + "_" + i;
                        emitComposite(name + suffix, mpath, cubes, bones, textures, texIndex, texRes, clip, t, suffix);
                        cmdToModel.put(name + suffix, mpath + suffix + "__p0");
                    }
                    clips.put(key, new int[]{n, clipTicks});
                }
                if (!clips.isEmpty()) {
                    animations.put(name, clips);
                    StringBuilder sb = new StringBuilder(name + " animations:");
                    for (var ce : clips.entrySet()) sb.append(' ').append(ce.getKey()).append('(').append(ce.getValue()[0]).append('x').append(ce.getValue()[1]).append(')');
                    report.add(sb.toString());
                }
            }
            infos.put(name, new ModelInfo(name, mpath, 1.0, 0, 0, 0, cubes.size(), mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]));
            report.add(name + ": " + cubes.size() + " cubes, " + ti + " texture(s), size " + Math.round(mx[0] - mn[0]) + "x" + Math.round(mx[1] - mn[1]) + "x" + Math.round(mx[2] - mn[2]) + " px, " + parts + " composite part(s), rotations exact");
            return;
        }
        // --- baked mode: fit into the Java range [-16, 32] (always centred so the display translation is exact)
        double extent = Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2]));
        double scale = 1.0;
        double[] shift = {0, 0, 0};
        boolean outside = false;
        for (int i = 0; i < 3; i++) if (mn[i] < -16 || mx[i] > 32) outside = true;
        if (outside && extent > 0) {
            scale = Math.min(1.0, 44.0 / extent);
            for (int i = 0; i < 3; i++) shift[i] = 8 - ((mn[i] + mx[i]) / 2) * scale;
        }
        final double S = scale; final double[] SH = shift;
        for (Cube c : cubes.values()) { c.from = fit(c.from, S, SH); c.to = fit(c.to, S, SH); c.origin = fit(c.origin, S, SH); c.inflate *= S; }
        for (Bone b : bones.values()) b.origin = fit(b.origin, S, SH);
        // translation that puts Blockbench (0,0,0) at the entity position when the display scale is 1/S
        double[] T = { (8 - SH[0]) / (16 * S), (8 - SH[1]) / (16 * S), (8 - SH[2]) / (16 * S) };

        JsonObject model = bake(name, cubes, bones, textures, texIndex, texRes);
        model.add("display", defaultDisplay(Math.min(4.0, 1.0 / S)));
        put("assets/" + NS + "/models/item/" + mpath + ".json", GSON.toJson(model));
        cmdToModel.put(name, mpath);
        infos.put(name, new ModelInfo(name, mpath, S, T[0], T[1], T[2], cubes.size(), mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]));
        report.add(name + ": " + cubes.size() + " cubes, " + ti + " texture(s), size " + Math.round(mx[0] - mn[0]) + "x" + Math.round(mx[1] - mn[1]) + "x" + Math.round(mx[2] - mn[2]) + " px"
            + (S < 1 ? " (fitted x" + Math.round(S * 1000) / 1000.0 + ", display compensates)" : ""));
    }

    // ------------------------------------------------------------------ composite mode

    private static final class Part {
        Bone bone; double[] pivot; double[][] extra = identity(); Cube soloCube; List<Cube> cubes = new ArrayList<>(); double k = 1; String suffix;
    }

    /** Can a Java element hold this cube's rotation exactly (one axis, multiple of 22.5, |a| <= 45)? */
    private static boolean exactRotation(Cube c) {
        if (c.rotation[0] == 0 && c.rotation[1] == 0 && c.rotation[2] == 0) return true;
        double[][] R = rot3(c.rotation);
        int[][] P = nearestPerm(R);
        double[] e = euler3(mul3(transpose3(P), R));
        int a = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(e[i]) > Math.abs(e[a])) a = i;
        for (int i = 0; i < 3; i++) if (i != a && Math.abs(e[i]) > 0.5) return false;
        double snapped = Math.round(e[a] / 22.5) * 22.5;
        return Math.abs(e[a] - snapped) < 0.5 && Math.abs(snapped) <= 45;
    }

    private static final class Clip {
        String name; double length; Map<String, List<JsonObject>> rot = new HashMap<>(), pos = new HashMap<>();   // bone uuid -> keyframes
    }

    /** Linear sample of a channel's keyframes at time t (seconds); zeros if none. */
    private static double[] sample(List<JsonObject> keys, double t) {
        if (keys == null || keys.isEmpty()) return new double[]{0, 0, 0};
        keys.sort((a, b) -> Double.compare(num(a.get("time"), 0), num(b.get("time"), 0)));
        JsonObject prev = null, next = null;
        for (JsonObject k : keys) {
            double kt = num(k.get("time"), 0);
            if (kt <= t) prev = k;
            if (kt >= t) { next = k; break; }
        }
        if (prev == null) return point(next);
        if (next == null || prev == next) return point(prev);
        double t0 = num(prev.get("time"), 0), t1 = num(next.get("time"), 0);
        double f = t1 - t0 < 1e-6 ? 0 : (t - t0) / (t1 - t0);
        double[] a = point(prev), b = point(next);
        return new double[]{ a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f };
    }
    private static double[] point(JsonObject k) {
        if (k == null || !k.has("data_points") || !k.get("data_points").isJsonArray()) return new double[]{0, 0, 0};
        JsonArray dp = k.getAsJsonArray("data_points");
        if (dp.isEmpty()) return new double[]{0, 0, 0};
        JsonObject p = dp.get(0).getAsJsonObject();
        return new double[]{ num(p.get("x"), 0), num(p.get("y"), 0), num(p.get("z"), 0) };
    }

    /** Exact world matrix of a group at time t of a clip (rest pose when clip == null): T(origin+pos) * R(rest+anim) * T(-origin), chained. */
    private static double[][] world(Bone b, Clip clip, double t, Map<Bone, double[][]> cache) {
        if (b == null) return identity();
        double[][] w = cache.get(b);
        if (w != null) return w;
        double[] r = b.rotation.clone(), p = {0, 0, 0};
        if (clip != null) {
            double[] ar = sample(clip.rot.get(b.uuid), t), ap = sample(clip.pos.get(b.uuid), t);
            for (int i = 0; i < 3; i++) { r[i] += ar[i]; p[i] += ap[i]; }
        }
        double[][] local = mul(mul(translate(b.origin[0] + p[0], b.origin[1] + p[1], b.origin[2] + p[2]), rotZYX(r)), translate(-b.origin[0], -b.origin[1], -b.origin[2]));
        w = b.parent == null ? local : mul(world(b.parent, clip, t, cache), local);
        cache.put(b, w);
        return w;
    }

    private int emitComposite(String cmd, String mpath, Map<String, Cube> cubes, Map<String, Bone> bones, JsonObject textures,
                              Map<String, Integer> texIndex, List<double[]> texRes, Clip clip, double t, String suffix) {
        String name = cmd;
        Bone root = new Bone(); root.name = "root"; root.uuid = "__root"; root.origin = new double[]{8, 8, 8};
        Map<Bone, Part> byBone = new LinkedHashMap<>();
        List<Part> parts = new ArrayList<>();
        int solo = 0;
        for (Cube c : cubes.values()) {
            Bone b = c.bone == null ? root : c.bone;
            if (!exactRotation(c)) {
                Part sp = new Part(); sp.bone = b; sp.soloCube = c; sp.pivot = c.origin.clone();
                sp.extra = mul(mul(translate(c.origin[0], c.origin[1], c.origin[2]), rotZYX(c.rotation)), translate(-c.origin[0], -c.origin[1], -c.origin[2]));
                sp.cubes.add(c); sp.suffix = "c" + (solo++); parts.add(sp);
                continue;
            }
            Part part = byBone.get(b);
            if (part == null) { part = new Part(); part.bone = b; part.pivot = b.origin.clone(); part.suffix = "" + byBone.size(); byBone.put(b, part); parts.add(part); }
            part.cubes.add(c);
        }
        if (solo > 0 && clip == null) report.add(name + ": " + solo + " cube(s) with a free rotation angle became their own exact part");
        Map<Bone, double[][]> cache = new HashMap<>();
        List<String> wrappers = new ArrayList<>();
        int clampedPivots = 0;
        for (Part part : parts) {
            String geom = mpath + "__g" + part.suffix, wrap = mpath + suffix + "__p" + part.suffix;
            if (!files.containsKey("assets/" + NS + "/models/item/" + geom + ".json")) writePartGeometry(name, geom, part, textures, texIndex, texRes);
            else part.k = partFit(part);
            double[][] M = mul(world(part.bone, clip, t, cache), part.extra);
            double[] Pw = apply(M, part.pivot);
            double[][] Rw = {{M[0][0], M[0][1], M[0][2]}, {M[1][0], M[1][1], M[1][2]}, {M[2][0], M[2][1], M[2][2]}};
            double[] eul = eulerXYZ(Rw);
            // FIXED context: 16 px = 1 block; the group's pivot (Blockbench px) IS the display translation, so
            // Blockbench (0,0,0) lands on the entity position. Translation is clamped by the client at +-80 px.
            double[] tr = new double[3];
            for (int i = 0; i < 3; i++) { tr[i] = Pw[i]; if (Math.abs(tr[i]) > 80) { tr[i] = Math.max(-80, Math.min(80, tr[i])); clampedPivots++; } }
            double sc = Math.min(4.0, 1.0 / part.k);
            JsonObject disp = new JsonObject();
            disp.add("fixed", transform(eul, tr, new double[]{sc, sc, sc}));
            disp.add("gui", transform(new double[]{eul[0] + 30, eul[1] + 225, eul[2]}, new double[]{tr[0] * 0.25, tr[1] * 0.25, tr[2] * 0.25}, new double[]{sc * 0.25, sc * 0.25, sc * 0.25}));
            disp.add("ground", transform(eul, new double[]{tr[0] * 0.25, tr[1] * 0.25 + 2, tr[2] * 0.25}, new double[]{sc * 0.25, sc * 0.25, sc * 0.25}));
            disp.add("thirdperson_righthand", transform(eul, new double[]{tr[0] * 0.25, tr[1] * 0.25 + 3, tr[2] * 0.25 + 1}, new double[]{sc * 0.25, sc * 0.25, sc * 0.25}));
            disp.add("firstperson_righthand", transform(eul, new double[]{tr[0] * 0.25 + 1, tr[1] * 0.25, tr[2] * 0.25 + 1}, new double[]{sc * 0.25, sc * 0.25, sc * 0.25}));
            JsonObject w = new JsonObject();
            w.addProperty("parent", NS + ":item/" + geom);
            w.add("display", disp);
            put("assets/" + NS + "/models/item/" + wrap + ".json", GSON.toJson(w));
            wrappers.add(wrap);
        }
        if (clampedPivots > 0 && clip == null) warnings.add(name + ": " + clampedPivots + " part(s) have their pivot more than 80 px (5 blocks) from the Blockbench origin - the client cannot place them that far; move the model closer to (0,0,0)");
        compositeParts.put(cmd, wrappers);
        return parts.size();
    }

    /** The fit factor a part's geometry was written with (recomputed for animation frames: geometry is shared). */
    private static double partFit(Part part) {
        double[] shift = {8 - part.pivot[0], 8 - part.pivot[1], 8 - part.pivot[2]};
        double far = 0;
        for (Cube c : part.cubes) {
            int[][] Pe = part.soloCube == c ? identityPerm() : nearestPerm(rot3(c.rotation));
            for (int corner = 0; corner < 8; corner++) {
                double[] v = { ((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                               ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                               ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2] };
                for (int i = 0; i < 3; i++) far = Math.max(far, Math.abs(Pe[i][0]*v[0]+Pe[i][1]*v[1]+Pe[i][2]*v[2] + c.origin[i] + shift[i] - 8));
            }
        }
        return far > 23.9 ? 23.9 / far : 1.0;
    }

    /** One part's geometry in part-local space: pivot moved to the model centre (8,8,8), shrunk by k if it would leave
     *  the Java range (the wrapper's display scale compensates), the cube's own exact 90-degree part applied. */
    private void writePartGeometry(String name, String geom, Part part, JsonObject textures, Map<String, Integer> texIndex, List<double[]> texRes) {
        double[] shift = {8 - part.pivot[0], 8 - part.pivot[1], 8 - part.pivot[2]};
        // extents from the corners AS WRITTEN (the cube's exact 90-degree part applied about its own origin): a cube
        // rotated 90 deg around a far pivot lands elsewhere, and one coordinate outside the Java range makes the client
        // reject the whole part (a purple cube in the wrong place)
        double far = 0;
        for (Cube c : part.cubes) {
            int[][] Pe = part.soloCube == c ? identityPerm() : nearestPerm(rot3(c.rotation));
            for (int corner = 0; corner < 8; corner++) {
                double[] v = { ((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                               ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                               ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2] };
                for (int i = 0; i < 3; i++) far = Math.max(far, Math.abs(Pe[i][0]*v[0]+Pe[i][1]*v[1]+Pe[i][2]*v[2] + c.origin[i] + shift[i] - 8));
            }
        }
        part.k = far > 23.9 ? 23.9 / far : 1.0;
        JsonArray elements = new JsonArray();
        for (Cube c : part.cubes) {
            boolean solo = part.soloCube == c;
            double[][] Re = rot3(solo ? new double[]{0, 0, 0} : c.rotation);
            int[][] Pe = nearestPerm(Re);
            double[] eulE = euler3(mul3(transpose3(Pe), Re));
            int ca = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(eulE[i]) > Math.abs(eulE[ca])) ca = i;
            double cs = Math.max(-45, Math.min(45, Math.round(eulE[ca] / 22.5) * 22.5));
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int corner = 0; corner < 8; corner++) {
                double[] v = { ((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                               ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                               ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2] };
                double[] w = { Pe[0][0]*v[0]+Pe[0][1]*v[1]+Pe[0][2]*v[2] + c.origin[0], Pe[1][0]*v[0]+Pe[1][1]*v[1]+Pe[1][2]*v[2] + c.origin[1], Pe[2][0]*v[0]+Pe[2][1]*v[1]+Pe[2][2]*v[2] + c.origin[2] };
                for (int i = 0; i < 3; i++) { double q = 8 + (w[i] + shift[i] - 8) * part.k; lo[i] = Math.min(lo[i], q); hi[i] = Math.max(hi[i], q); }
            }
            JsonObject srcFaces = c.faces;
            boolean needBox = c.boxUv;
            if (!needBox) for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && !srcFaces.getAsJsonObject(f).has("uv")) needBox = true;
            if (needBox) {
                boolean anyUv = false;
                for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && srcFaces.getAsJsonObject(f).has("uv")) anyUv = true;
                if (!anyUv) srcFaces = boxUvFaces(c, srcFaces);
            }
            JsonObject cfaces = remapFaces(srcFaces, Pe);
            for (int i = 0; i < 3; i++) { lo[i] = Math.max(-16, Math.min(32, lo[i])); hi[i] = Math.max(-16, Math.min(32, hi[i])); }
            JsonObject e = new JsonObject();
            e.add("from", arr(lo));
            e.add("to", arr(hi));
            if (cs != 0) {
                JsonObject rotation = new JsonObject();
                rotation.add("origin", arr(8 + (c.origin[0] + shift[0] - 8) * part.k, 8 + (c.origin[1] + shift[1] - 8) * part.k, 8 + (c.origin[2] + shift[2] - 8) * part.k));
                rotation.addProperty("axis", ca == 0 ? "x" : ca == 1 ? "y" : "z");
                rotation.addProperty("angle", cs);
                e.add("rotation", rotation);
            }
            JsonObject faces = new JsonObject();
            for (String f : FACE) {
                if (!cfaces.has(f) || !cfaces.get(f).isJsonObject()) continue;
                JsonObject bf = cfaces.getAsJsonObject(f);
                JsonElement tex = bf.get("texture");
                if (tex == null || tex.isJsonNull()) continue;
                Integer idx = tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isNumber() ? Integer.valueOf(tex.getAsInt()) : texIndex.get(tex.getAsString());
                if (idx == null || idx >= texRes.size()) idx = 0;
                double[] res = texRes.get(idx);
                double[] uv = bf.has("uv") && bf.get("uv").isJsonArray() ? vec4(bf.get("uv")) : new double[]{0, 0, res[0], res[1]};
                JsonObject face = new JsonObject();
                face.add("uv", arr(uv[0] * 16 / res[0], uv[1] * 16 / res[1], uv[2] * 16 / res[0], uv[3] * 16 / res[1]));
                face.addProperty("texture", "#" + idx);
                if (bf.has("rotation")) { int r = ((int) num(bf.get("rotation"), 0) % 360 + 360) % 360; if (r != 0) face.addProperty("rotation", r); }
                faces.add(f, face);
            }
            e.add("faces", faces);
            elements.add(e);
        }
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        put("assets/" + NS + "/models/item/" + geom + ".json", GSON.toJson(model));
    }

    private static double[][] rotXYZ(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0},{0,Math.cos(x),-Math.sin(x)},{0,Math.sin(x),Math.cos(x)}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y)},{0,1,0},{-Math.sin(y),0,Math.cos(y)}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0},{Math.sin(z),Math.cos(z),0},{0,0,1}};
        return mul3(mul3(rx, ry), rz);
    }
    /** Euler angles (deg) with rotXYZ(e) == M - what Minecraft's display "rotation" expects. */
    private static double[] eulerXYZ(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, m[0][2])));
        double x = Math.atan2(-m[1][2], m[2][2]);
        double z = Math.atan2(-m[0][1], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }

    private static void parseOutliner(JsonElement oe, Bone parent, Map<String, Bone> bones, Map<String, Cube> cubes) {
        if (oe.isJsonPrimitive()) {
            Cube c = cubes.get(oe.getAsString());
            if (c != null && parent != null) c.bone = parent;
            return;
        }
        if (!oe.isJsonObject()) return;
        JsonObject o = oe.getAsJsonObject();
        Bone b = new Bone();
        b.uuid = str(o.get("uuid"), "b" + bones.size());
        b.name = str(o.get("name"), b.uuid);
        if (o.has("origin")) b.origin = vec(o.get("origin"));
        if (o.has("rotation")) b.rotation = vec(o.get("rotation"));
        b.parent = parent;
        bones.put(b.uuid, b);
        if (parent != null) parent.children.add(b);
        if (o.has("children")) for (JsonElement ce : o.getAsJsonArray("children")) parseOutliner(ce, b, bones, cubes);
    }

    private static double[] fit(double[] v, double s, double[] sh) {
        return new double[]{ v[0] * s + sh[0], v[1] * s + sh[1], v[2] * s + sh[2] };
    }

    /** One baked bone: single-axis snapped rotation about its pivot + exact 90-degree parts. */
    private static final class Baked {
        int axis = -1; double angle = 0;
        double[] pivot;
        double[][] coarse;
        int[][] perm;
    }

    private Baked bakeBone(Bone b, Map<Bone, Baked> cache, String name, Set<String> warned) {
        Baked done = cache.get(b);
        if (done != null) return done;
        Baked parent = b.parent == null ? null : bakeBone(b.parent, cache, name, warned);
        double[] r = b.rotation;
        double[][] R = rot3(r);
        int[][] P = nearestPerm(R);
        double[] eul = euler3(mul3(transpose3(P), R));
        int axis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(eul[i]) > Math.abs(eul[axis])) axis = i;
        double raw = eul[axis];
        double snapped = Math.max(-45, Math.min(45, Math.round(raw / 22.5) * 22.5));
        boolean multi = false;
        for (int i = 0; i < 3; i++) if (i != axis && Math.abs(eul[i]) > 3) multi = true;
        if (multi && warned.add(b.name + ":multi")) warnings.add(name + ": group '" + b.name + "' rotates on several axes at once - Java cubes keep one axis, the rest is approximated");
        if (Math.abs(raw - snapped) > 6 && warned.add(b.name + ":snap")) warnings.add(name + ": group '" + b.name + "' rotation " + Math.round(r[axis]) + " deg rounded to the nearest 22.5 step (Java limit)");
        Baked out = new Baked();
        double[][] parentCoarse = parent == null ? identity() : parent.coarse;
        int[][] parentPerm = parent == null ? identityPerm() : parent.perm;
        double[] pivotLocal = {b.origin[0], b.origin[1], b.origin[2]};
        out.pivot = apply(parentCoarse, pivotLocal);
        out.coarse = mul(parentCoarse, mul(mul(translate(pivotLocal[0], pivotLocal[1], pivotLocal[2]), perm4(P)), translate(-b.origin[0], -b.origin[1], -b.origin[2])));
        out.perm = mul3i(parentPerm, P);
        if (snapped != 0) { out.axis = axis; out.angle = snapped; }
        cache.put(b, out);
        return out;
    }

    // --- face conventions (direction, texture up, texture right) for remapping rotated cubes
    private static final String[] FACE = {"north", "south", "east", "west", "up", "down"};
    private static final int[][] DIR   = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    private static final int[][] FUP   = {{0,1,0},{0,1,0},{0,1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][] FRIGHT= {{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{1,0,0},{1,0,0}};

    private static int faceIndex(int[] dir) {
        for (int i = 0; i < 6; i++) if (DIR[i][0] == dir[0] && DIR[i][1] == dir[1] && DIR[i][2] == dir[2]) return i;
        return 0;
    }
    private static int[] applyPerm(int[][] P, int[] v) {
        return new int[]{ P[0][0]*v[0]+P[0][1]*v[1]+P[0][2]*v[2], P[1][0]*v[0]+P[1][1]*v[1]+P[1][2]*v[2], P[2][0]*v[0]+P[2][1]*v[1]+P[2][2]*v[2] };
    }
    private static int dot(int[] a, int[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static int[] neg(int[] v) { return new int[]{-v[0], -v[1], -v[2]}; }

    private static JsonObject remapFaces(JsonObject faces, int[][] P) {
        boolean identity = true;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) if (P[i][j] != (i == j ? 1 : 0)) identity = false;
        if (identity) return faces;
        JsonObject out = new JsonObject();
        for (int i = 0; i < 6; i++) {
            if (!faces.has(FACE[i]) || !faces.get(FACE[i]).isJsonObject()) continue;
            JsonObject f = faces.getAsJsonObject(FACE[i]).deepCopy();
            int ni = faceIndex(applyPerm(P, DIR[i]));
            int[] up = applyPerm(P, FUP[i]), right = applyPerm(P, FRIGHT[i]);
            int k;
            if (dot(up, FUP[ni]) > 0) k = 0; else if (dot(up, FRIGHT[ni]) > 0) k = 90; else if (dot(up, FUP[ni]) < 0) k = 180; else k = 270;
            int[] expectRight = switch (k) { case 0 -> FRIGHT[ni]; case 90 -> neg(FUP[ni]); case 180 -> neg(FRIGHT[ni]); default -> FUP[ni]; };
            boolean mirror = dot(right, expectRight) < 0;
            int rotation = (int) ((f.has("rotation") ? num(f.get("rotation"), 0) : 0) + k) % 360;
            if (rotation != 0) f.addProperty("rotation", rotation); else f.remove("rotation");
            if (mirror && f.has("uv") && f.get("uv").isJsonArray()) {
                JsonArray uv = f.getAsJsonArray("uv");
                double u1 = num(uv.get(0), 0), u2 = num(uv.get(2), 0);
                JsonArray n = new JsonArray(); n.add(u2); n.add(num(uv.get(1), 0)); n.add(u1); n.add(num(uv.get(3), 0));
                f.add("uv", n);
            }
            out.add(FACE[ni], f);
        }
        return out;
    }

    /** Box-UV fallback (Bedrock/GeckoLib style models whose faces carry no uv): the classic Minecraft layout. */
    /** Bounding-box cube for a Blockbench mesh element (vertices are relative to its origin). */
    private static Cube meshBox(JsonObject e, int index) {
        if (!e.has("vertices") || !e.get("vertices").isJsonObject()) return null;
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (var v : e.getAsJsonObject("vertices").entrySet()) {
            if (!v.getValue().isJsonArray()) continue;
            double[] p = vec(v.getValue());
            for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
        }
        if (mn[0] > mx[0]) return null;
        Cube c = new Cube();
        c.uuid = str(e.get("uuid"), "m" + index);
        c.name = str(e.get("name"), "mesh");
        c.origin = e.has("origin") ? vec(e.get("origin")) : new double[]{0, 0, 0};
        if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
        c.from = new double[]{ c.origin[0] + mn[0], c.origin[1] + mn[1], c.origin[2] + mn[2] };
        c.to = new double[]{ c.origin[0] + mx[0], c.origin[1] + mx[1], c.origin[2] + mx[2] };
        for (int i = 0; i < 3; i++) if (c.to[i] - c.from[i] < 0.05) c.to[i] = c.from[i] + 0.05;
        JsonElement tex = null;
        if (e.has("faces") && e.get("faces").isJsonObject())
            for (var f : e.getAsJsonObject("faces").entrySet()) if (f.getValue().isJsonObject() && f.getValue().getAsJsonObject().has("texture")) { tex = f.getValue().getAsJsonObject().get("texture"); break; }
        c.faces = new JsonObject();
        for (String f : FACE) { JsonObject face = new JsonObject(); face.add("texture", tex == null ? new com.google.gson.JsonPrimitive(0) : tex); c.faces.add(f, face); }
        c.boxUv = false;
        return c;
    }

    private static JsonObject boxUvFaces(Cube c, JsonObject faces) {
        double dx = Math.abs(c.to[0] - c.from[0]), dy = Math.abs(c.to[1] - c.from[1]), dz = Math.abs(c.to[2] - c.from[2]);
        double u = c.uvOffset[0], v = c.uvOffset[1];
        Map<String, double[]> uv = new LinkedHashMap<>();
        uv.put("up",    new double[]{u + dz, v, u + dz + dx, v + dz});
        uv.put("down",  new double[]{u + dz + dx, v + dz, u + dz + 2 * dx, v});
        uv.put("west",  new double[]{u, v + dz, u + dz, v + dz + dy});
        uv.put("north", new double[]{u + dz, v + dz, u + dz + dx, v + dz + dy});
        uv.put("east",  new double[]{u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy});
        uv.put("south", new double[]{u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy});
        if (c.mirror) { double[] e = uv.get("east"), w = uv.get("west"); uv.put("east", w); uv.put("west", e); }
        JsonObject out = new JsonObject();
        for (String f : FACE) {
            JsonObject face = faces.has(f) && faces.get(f).isJsonObject() ? faces.getAsJsonObject(f).deepCopy() : new JsonObject();
            if (!face.has("uv") || !face.get("uv").isJsonArray()) {
                double[] r = uv.get(f);
                JsonArray a = new JsonArray(); for (double d : r) a.add(d);
                face.add("uv", a);
            }
            if (!face.has("texture")) face.addProperty("texture", 0);
            out.add(f, face);
        }
        return out;
    }

    /** Build the rest-pose model. Bone rotations are snapped to what a Java cube can do and the SAME snapped
     *  rotation is applied to every cube of the bone about the bone's pivot, so parts never drift apart. */
    private JsonObject bake(String name, Map<String, Cube> cubes, Map<String, Bone> bones, JsonObject textures,
                            Map<String, Integer> texIndex, List<double[]> texRes) {
        Map<Bone, Baked> baked = new HashMap<>();
        Set<String> warned = new LinkedHashSet<>();
        JsonArray elements = new JsonArray();
        int clamped = 0;
        for (Cube c : cubes.values()) {
            Baked bb = c.bone == null ? null : bakeBone(c.bone, baked, name, warned);
            int axis = -1; double angle = 0; double[] pivot = null;
            double[][] place = bb == null ? identity() : bb.coarse;
            int[][] perm = bb == null ? identityPerm() : bb.perm;
            for (Bone w = c.bone; w != null; w = w.parent) {
                Baked wb = baked.get(w);
                if (wb == null || wb.axis < 0) continue;
                if (axis < 0) { axis = wb.axis; angle = wb.angle; pivot = wb.pivot; }
                else if (wb.axis == axis) angle = Math.max(-45, Math.min(45, angle + wb.angle));
                else if (warned.add(c.uuid + ":nest")) warnings.add(name + ": nested groups rotate on different axes around '" + w.name + "' - approximated");
            }
            double[][] Re = rot3(c.rotation);
            int[][] Pe = nearestPerm(Re);
            double[] eulE = euler3(mul3(transpose3(Pe), Re));
            int ca = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(eulE[i]) > Math.abs(eulE[ca])) ca = i;
            double cs = Math.max(-45, Math.min(45, Math.round(eulE[ca] / 22.5) * 22.5));
            if (cs != 0) {
                if (axis < 0) { axis = ca; angle = cs; pivot = apply(place, c.origin); }
                else if (ca == axis) angle = Math.max(-45, Math.min(45, angle + cs));
            }
            if (Math.abs(eulE[ca] - cs) > 6 && warned.add(c.uuid + ":snap")) warnings.add(name + ": cube '" + c.name + "' rotated " + Math.round(c.rotation[ca]) + " deg was rounded to the nearest 22.5 step");
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int corner = 0; corner < 8; corner++) {
                double[] v = { ((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                               ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                               ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2] };
                double[] w = { Pe[0][0]*v[0]+Pe[0][1]*v[1]+Pe[0][2]*v[2] + c.origin[0], Pe[1][0]*v[0]+Pe[1][1]*v[1]+Pe[1][2]*v[2] + c.origin[1], Pe[2][0]*v[0]+Pe[2][1]*v[1]+Pe[2][2]*v[2] + c.origin[2] };
                double[] q = apply(place, w);
                for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], q[i]); hi[i] = Math.max(hi[i], q[i]); }
            }
            JsonObject srcFaces = c.faces;
            boolean needBox = c.boxUv;
            if (!needBox) for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && !srcFaces.getAsJsonObject(f).has("uv")) needBox = true;
            if (needBox) {
                boolean anyUv = false;
                for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && srcFaces.getAsJsonObject(f).has("uv")) anyUv = true;
                if (!anyUv) srcFaces = boxUvFaces(c, srcFaces);
            }
            JsonObject cfaces = remapFaces(srcFaces, mul3i(perm, Pe));
            JsonObject rotation = null;
            if (axis >= 0 && angle != 0) {
                rotation = new JsonObject();
                rotation.add("origin", arr(pivot));
                rotation.addProperty("axis", axis == 0 ? "x" : axis == 1 ? "y" : "z");
                rotation.addProperty("angle", angle);
            }
            boolean inRange = true;
            for (int i = 0; i < 3; i++) if (lo[i] < -16 || lo[i] > 32 || hi[i] < -16 || hi[i] > 32) inRange = false;
            if (!inRange) {
                for (int i = 0; i < 3; i++) { lo[i] = Math.max(-16, Math.min(32, lo[i])); hi[i] = Math.max(-16, Math.min(32, hi[i])); }
                clamped++;
            }
            JsonObject e = new JsonObject();
            e.add("from", arr(lo));
            e.add("to", arr(hi));
            if (rotation != null) e.add("rotation", rotation);
            JsonObject faces = new JsonObject();
            for (String f : FACE) {
                if (!cfaces.has(f) || !cfaces.get(f).isJsonObject()) continue;
                JsonObject bf = cfaces.getAsJsonObject(f);
                JsonElement tex = bf.get("texture");
                if (tex == null || tex.isJsonNull()) continue;
                Integer idx = tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isNumber() ? Integer.valueOf(tex.getAsInt()) : texIndex.get(tex.getAsString());
                if (idx == null) idx = 0;
                if (idx >= texRes.size()) idx = 0;
                double[] res = texRes.get(idx);
                double[] uv = bf.has("uv") && bf.get("uv").isJsonArray() ? vec4(bf.get("uv")) : new double[]{0, 0, res[0], res[1]};
                JsonObject face = new JsonObject();
                face.add("uv", arr(uv[0] * 16 / res[0], uv[1] * 16 / res[1], uv[2] * 16 / res[0], uv[3] * 16 / res[1]));
                face.addProperty("texture", "#" + idx);
                if (bf.has("rotation")) { int r = ((int) num(bf.get("rotation"), 0) % 360 + 360) % 360; if (r != 0) face.addProperty("rotation", r); }
                faces.add(f, face);
            }
            e.add("faces", faces);
            elements.add(e);
        }
        if (clamped > 0) warnings.add(name + ": " + clamped + " rotated cube(s) poke outside the Java model range and were clamped");
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    private static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(Math.round(d * 1000.0) / 1000.0);
        return a;
    }

    private static JsonObject transform(double[] rot, double[] tr, double[] sc) {
        JsonObject o = new JsonObject();
        o.add("rotation", arr(rot));
        o.add("translation", arr(tr));
        o.add("scale", arr(sc));
        return o;
    }

    /** Display transforms for the item form (inventory icon, hand): the fitted model shown like a block. */
    private static JsonObject defaultDisplay(double s) {
        s = Math.min(s, 4.0);
        double g = Math.min(4.0, 0.5 * s), h = Math.min(4.0, 0.4 * s), f = Math.min(4.0, 0.5 * s);
        JsonObject d = new JsonObject();
        d.add("gui", transform(new double[]{30, 225, 0}, new double[]{0, 0, 0}, new double[]{g, g, g}));
        d.add("ground", transform(new double[]{0, 0, 0}, new double[]{0, 3, 0}, new double[]{Math.min(4, 0.25 * s), Math.min(4, 0.25 * s), Math.min(4, 0.25 * s)}));
        d.add("fixed", transform(new double[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{f, f, f}));
        d.add("thirdperson_righthand", transform(new double[]{75, 45, 0}, new double[]{0, 2.5, 0}, new double[]{h, h, h}));
        d.add("thirdperson_lefthand", transform(new double[]{75, 45, 0}, new double[]{0, 2.5, 0}, new double[]{h, h, h}));
        d.add("firstperson_righthand", transform(new double[]{0, 45, 0}, new double[]{0, 0, 0}, new double[]{h, h, h}));
        d.add("firstperson_lefthand", transform(new double[]{0, 225, 0}, new double[]{0, 0, 0}, new double[]{h, h, h}));
        return d;
    }

    // ------------------------------------------------------------------ rotation helpers

    private static double[][] rot3(double[] deg) {
        double[][] m = rotZYX(deg);
        return new double[][]{{m[0][0], m[0][1], m[0][2]}, {m[1][0], m[1][1], m[1][2]}, {m[2][0], m[2][1], m[2][2]}};
    }
    private static double[][] mul3(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    private static double[][] transpose3(int[][] p) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = p[j][i];
        return r;
    }
    private static int[][] identityPerm() { return new int[][]{{1,0,0},{0,1,0},{0,0,1}}; }
    private static int[][] mul3i(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { int x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    private static double[][] perm4(int[][] p) {
        double[][] m = identity();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] = p[i][j];
        return m;
    }
    /** The signed axis permutation (a rotation by multiples of 90 degrees) nearest to R; identity if none is close. */
    private static int[][] nearestPerm(double[][] R) {
        int[][] P = new int[3][3];
        boolean[] usedCol = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int bj = 0;
            for (int j = 1; j < 3; j++) if (Math.abs(R[i][j]) > Math.abs(R[i][bj])) bj = j;
            if (usedCol[bj] || Math.abs(R[i][bj]) < 0.5) return identityPerm();
            usedCol[bj] = true;
            P[i][bj] = R[i][bj] >= 0 ? 1 : -1;
        }
        int det = P[0][0]*(P[1][1]*P[2][2]-P[1][2]*P[2][1]) - P[0][1]*(P[1][0]*P[2][2]-P[1][2]*P[2][0]) + P[0][2]*(P[1][0]*P[2][1]-P[1][1]*P[2][0]);
        return det == 1 ? P : identityPerm();
    }
    private static double[] euler3(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }
    private static double[][] identity() { return new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}}; }
    private static double[][] translate(double x, double y, double z) { double[][] m = identity(); m[0][3] = x; m[1][3] = y; m[2][3] = z; return m; }
    private static double[][] rotZYX(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0,0},{0,Math.cos(x),-Math.sin(x),0},{0,Math.sin(x),Math.cos(x),0},{0,0,0,1}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y),0},{0,1,0,0},{-Math.sin(y),0,Math.cos(y),0},{0,0,0,1}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0,0},{Math.sin(z),Math.cos(z),0,0},{0,0,1,0},{0,0,0,1}};
        return mul(mul(rz, ry), rx);
    }
    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
        return r;
    }
    private static double[] apply(double[][] m, double[] v) {
        return new double[]{ m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3] };
    }

    // ------------------------------------------------------------------ json helpers

    private static double num(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) return def;
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return Double.parseDouble(e.getAsString().trim());
            return e.getAsDouble();
        } catch (Exception ex) { return def; }
    }
    private static String str(JsonElement e, String def) { return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? def : e.getAsString(); }
    private static double[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new double[]{0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return new double[]{0, 0, 0};
        return new double[]{ num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0) };
    }
    private static double[] vec4(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 4) return new double[]{0, 0, 16, 16};
        return new double[]{ num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0), num(a.get(3), 0) };
    }

    @SuppressWarnings("unused")
    private static String join(double[] v) { return Arrays.toString(v); }
}
