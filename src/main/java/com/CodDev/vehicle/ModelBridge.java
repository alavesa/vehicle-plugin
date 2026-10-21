package com.CodDev.vehicle;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Bridge to RP Engine (optional): our .bbmodel files are mirrored into RP Engine's content folder as a pack of their
 * own (plugins/RPEngine/content/<prefix>/models/<model>.bbmodel - read as BetterModel-style blueprints (RP Engine reads that layout), each file one
 * bindable item "<prefix>:<model>"), RP Engine builds and serves the resource pack, and the body is a model BOUND to
 * our base entity (Models.bind) and animated with Models.animate. Nothing here is touched when RP Engine is absent.
 */
public final class ModelBridge {
    /** A model worn by one of our entities. */
    public static final class Handle {
        public final Entity host; public final String model; private boolean closed;
        Handle(Entity host, String model) { this.host = host; this.model = model; }
        public boolean isClosed() { return closed || host == null || !host.isValid(); }
    }

    private final JavaPlugin plugin;
    private final String prefix;

    public ModelBridge(JavaPlugin plugin, String prefix) { this.plugin = plugin; this.prefix = prefix; }

    public static boolean installed() {
        try { Class.forName("ai.resourcepack.engine.api.Models"); } catch (Throwable t) { return false; }
        return Bukkit.getPluginManager().getPlugin("RPEngine") != null;
    }

    public boolean active() { return installed() && plugin.getConfig().getBoolean("rpengine.enabled", plugin.getConfig().getBoolean("bettermodel.enabled", true)); }

    private static RPEnginePlugin engine() { return (RPEnginePlugin) Bukkit.getPluginManager().getPlugin("RPEngine"); }

    /** The file / path part of one of our model files inside RP Engine. */
    public String key(String model) { return model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"); }
    /** The RP Engine content id of one of our models: "<prefix>:<model>". */
    public String contentId(String model) { return prefix + ":" + key(model); }
    private Optional<ContentId> id(String model) { return ContentId.of(prefix, key(model)); }

    /** Mirror models/*.bbmodel into plugins/RPEngine/content/<prefix>/models; reload RP Engine if anything changed. Returns the change count. */
    public int sync(File modelsDir) {
        File pack = new File(new File(engine().getDataFolder(), "content"), prefix);
        File rm = new File(pack, "models");
        rm.mkdirs();
        int changed = 0;
        File[] src = modelsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        java.util.Set<String> wanted = new java.util.HashSet<>();
        if (src != null) for (File f : src) {
            String name = f.getName().substring(0, f.getName().length() - ".bbmodel".length());
            File target = new File(rm, key(name) + ".bbmodel");
            wanted.add(target.getName());
            try {
                byte[] data = prepare(f, name);
                if (!target.exists() || !java.util.Arrays.equals(data, Files.readAllBytes(target.toPath()))) { Files.write(target.toPath(), data); changed++; }
            } catch (Exception e) { plugin.getLogger().warning("Could not copy " + f.getName() + " to RP Engine: " + e); }
        }
        File[] stale = rm.listFiles((d, n) -> n.endsWith(".bbmodel") && !wanted.contains(n));
        if (stale != null) for (File s : stale) if (s.delete()) changed++;
        if (changed > 0) {
            plugin.getLogger().info("RP Engine: " + changed + " model file(s) changed in content/" + prefix + "/models - reloading RP Engine (rebuilds its pack once).");
            try { engine().reload(); } catch (Throwable t) { plugin.getLogger().warning("RP Engine reload failed: " + t); }
        }
        return changed;
    }

    // ------------------------------------------------------------------ RP Engine-safe model files

    /** A cube rotation a Java item model can hold as-is: one axis only, a multiple of 22.5 within -45..45. */
    public static boolean javaRotation(double[] r) {
        int axes = 0;
        for (double v : r) {
            if (Math.abs(v) < 1e-6) continue;
            axes++;
            if (Math.abs(v) > 45 + 1e-6 || Math.abs(v / 22.5 - Math.round(v / 22.5)) > 1e-4) return false;
        }
        return axes <= 1;
    }

    /** The .bbmodel bytes handed to RP Engine. Every cube whose rotation is not a legal Java element rotation is
     *  moved into its own bone (origin = the cube's own pivot, rotation = the cube's rotation, cube rotation zeroed):
     *  a bone rotation is exact, a free cube rotation may be approximated. */
    byte[] prepare(File f, String name) throws java.io.IOException {
        String text = Files.readString(f.toPath());
        com.google.gson.JsonObject root;
        try { root = com.google.gson.JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return text.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        java.util.List<String> notes = new java.util.ArrayList<>();
        int wrapped = rewrite(root, notes);
        for (String n : notes) plugin.getLogger().info("RP Engine model " + contentId(name) + ": " + n);
        if (wrapped == 0) return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Analyse + fix a Blockbench model object in place; returns the number of cubes wrapped into bones. */
    static int rewrite(com.google.gson.JsonObject root, java.util.List<String> notes) {
        java.util.Map<String, com.google.gson.JsonObject> wrap = new java.util.LinkedHashMap<>();   // cube uuid -> new group
        int cubes = 0, meshes = 0, big = 0;
        if (root.has("elements") && root.get("elements").isJsonArray()) for (var el : root.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject e = el.getAsJsonObject();
            String type = e.has("type") ? e.get("type").getAsString() : "cube";
            if (type.equals("mesh")) { meshes++; continue; }
            if (!type.equals("cube")) continue;
            cubes++;
            if (e.has("from") && e.has("to")) {
                double[] fr = vec(e.get("from")), to = vec(e.get("to"));
                for (int i = 0; i < 3; i++) if (Math.abs(to[i] - fr[i]) > 48) { big++; break; }
            }
            if (!e.has("rotation") || !e.get("rotation").isJsonArray()) continue;
            double[] r = vec(e.get("rotation"));
            if (javaRotation(r)) continue;
            com.google.gson.JsonObject g = new com.google.gson.JsonObject();
            String cname = e.has("name") ? e.get("name").getAsString() : "cube";
            g.addProperty("name", cname + "_rot");
            g.add("origin", e.has("origin") ? e.get("origin").deepCopy() : arr(8, 8, 8));
            g.add("rotation", e.get("rotation").deepCopy());
            g.addProperty("uuid", java.util.UUID.randomUUID().toString());
            g.addProperty("export", true); g.addProperty("visibility", true); g.addProperty("isOpen", false); g.addProperty("autouv", 0);
            com.google.gson.JsonArray ch = new com.google.gson.JsonArray(); ch.add(e.get("uuid").getAsString()); g.add("children", ch);
            e.add("rotation", arr(0, 0, 0));
            wrap.put(e.get("uuid").getAsString(), g);
        }
        int wrappedCount = wrap.size();
        if (!wrap.isEmpty() && root.has("outliner")) root.add("outliner", wrapOutliner(root.getAsJsonArray("outliner"), wrap));
        // cubes never reached through the outliner still get wrapped by appending their groups at the root
        if (!wrap.isEmpty()) { if (!root.has("outliner")) root.add("outliner", new com.google.gson.JsonArray()); for (var g : wrap.values()) root.getAsJsonArray("outliner").add(g); }
        notes.add(cubes + " cube(s), " + meshes + " mesh(es)" + (wrappedCount == 0 ? "" : ", " + wrappedCount + " free-rotated cube(s) moved into their own bones") + (big > 0 ? ", " + big + " cube(s) over 48 px long (BetterModel scales those bones down - check them)" : ""));
        return wrappedCount;
    }

    private static com.google.gson.JsonArray wrapOutliner(com.google.gson.JsonArray list, java.util.Map<String, com.google.gson.JsonObject> wrap) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (var el : list) {
            if (el.isJsonPrimitive()) {
                com.google.gson.JsonObject g = wrap.remove(el.getAsString());
                out.add(g != null ? g : el);
            } else if (el.isJsonObject()) {
                com.google.gson.JsonObject g = el.getAsJsonObject();
                if (g.has("children") && g.get("children").isJsonArray()) g.add("children", wrapOutliner(g.getAsJsonArray("children"), wrap));
                out.add(g);
            } else out.add(el);
        }
        return out;
    }

    private static double[] vec(com.google.gson.JsonElement e) {
        double[] v = new double[3];
        if (e != null && e.isJsonArray()) for (int i = 0; i < 3 && i < e.getAsJsonArray().size(); i++) try { v[i] = e.getAsJsonArray().get(i).getAsDouble(); } catch (Exception ignored) { }
        return v;
    }
    private static com.google.gson.JsonArray arr(double a, double b, double c) { com.google.gson.JsonArray x = new com.google.gson.JsonArray(); x.add(a); x.add(b); x.add(c); return x; }

    /** /vehicle check <model>: what the RP Engine copy of a model looks like. */
    public java.util.List<String> check(File f) {
        java.util.List<String> notes = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
            String fmt = root.has("meta") && root.getAsJsonObject("meta").has("format_version") ? root.getAsJsonObject("meta").get("format_version").getAsString() : "?";
            String mf = root.has("meta") && root.getAsJsonObject("meta").has("model_format") ? root.getAsJsonObject("meta").get("model_format").getAsString() : "?";
            notes.add("Blockbench " + fmt + ", format " + mf + (mf.equals("java_block") ? " (Java Block/Item: Blockbench itself limits rotations; prefer Generic Model for BetterModel)" : ""));
            rewrite(root, notes);
            int anims = root.has("animations") && root.get("animations").isJsonArray() ? root.getAsJsonArray("animations").size() : 0;
            notes.add(anims + " animation(s)");
        } catch (Exception e) { notes.add("not readable as a .bbmodel: " + e); }
        return notes;
    }

    public boolean has(String model) {
        if (model == null || model.isEmpty()) return false;
        String cid = contentId(model), k = key(model);
        try { for (String i : engine().models().ids()) if (i.equalsIgnoreCase(cid) || i.equalsIgnoreCase(k)) return true; } catch (Throwable ignored) { }
        return false;
    }

    private String modelId(String model) {
        String cid = contentId(model), k = key(model);
        try { for (String i : engine().models().ids()) if (i.equalsIgnoreCase(cid) || i.equalsIgnoreCase(k)) return i; } catch (Throwable ignored) { }
        return cid;
    }

    /** Animation names are matched like our pack does: case-insensitive, spaces/dashes = underscores ("drive_bounce" finds "Drive Bounce"). */
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_"); }

    /** The animation's real name in the RP Engine model, or null when the model has no such animation. */
    public String resolve(String model, String anim) {
        if (anim == null || anim.isEmpty()) return null;
        String want = norm(anim);
        try { for (String n : engine().models().animationsOf(modelId(model))) if (n.equals(anim) || norm(n).equals(want)) return n; } catch (Throwable ignored) { }
        return null;
    }

    public boolean hasAnimation(String model, String anim) { return resolve(model, anim) != null; }

    public java.util.Set<String> animations(String model) {
        try { return new java.util.TreeSet<>(engine().models().animationsOf(modelId(model))); } catch (Throwable t) { return java.util.Set.of(); }
    }

    /** The host yaw RP Engine gets = our yaw + (model-yaw - 180) + config `rpengine.yaw-offset`, normalised to -180..180:
     *  a worn model faces where its host faces, and (like BetterModel) Blockbench north is taken as the model's front. */
    public static float modelYaw(float entityYaw, double modelYaw, double yawOffset) {
        double y = (entityYaw + modelYaw - 180.0 + yawOffset) % 360.0;
        if (y > 180) y -= 360; if (y <= -180) y += 360;
        return (float) y;
    }

    /** The model scale handed to RP Engine (bind scale) - never the base entity's scale attribute. */
    public static float modelScale(double typeScale) { return (float) Math.max(0.01, Math.min(16.0, typeScale)); }

    public double yawOffset() { return plugin.getConfig().getDouble("rpengine.yaw-offset", plugin.getConfig().getDouble("bettermodel.yaw-offset", 0.0)); }

    /** Put the model on an entity; RP Engine then follows it. The host's own yaw is what the model faces (see modelYaw). */
    public Handle attach(Entity base, String model, Supplier<float[]> rotSupplier, double scale) {
        Optional<ContentId> cid = id(model);
        if (cid.isEmpty() || !has(model)) return null;
        try {
            if (!engine().models().bind(base, cid.get(), modelScale(scale))) return null;
        } catch (Throwable t) { plugin.getLogger().warning("RP Engine bind failed for " + contentId(model) + ": " + t); return null; }
        return new Handle(base, model);
    }

    public void animate(Handle t, String anim, boolean loop, Supplier<Float> speed) {
        if (t == null || t.isClosed() || anim == null || anim.isEmpty()) return;
        String real = resolve(t.model, anim);
        if (real == null) return;
        try { engine().models().animate(t.host, real); } catch (Throwable ignored) { }
    }

    public void animateHold(Handle t, String anim) { animate(t, anim, false, null); }

    public void stop(Handle t, String anim) {
        if (t == null || t.isClosed()) return;
        try { engine().models().stopAnimating(t.host); } catch (Throwable ignored) { }
    }

    public void close(Handle t) {
        if (t == null || t.closed) return;
        t.closed = true;
        try { if (t.host != null && t.host.isValid()) engine().models().unbind(t.host); } catch (Throwable ignored) { }
    }
}
