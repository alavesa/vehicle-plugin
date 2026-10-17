package com.CodDev.vehicle;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * BetterModel bridge: when BetterModel is installed, the .bbmodel files in plugins/Vehicle/models are mirrored into
 * BetterModel's own models folder (prefixed "vehicle_") so ONE plugin builds the resource pack, and vehicle bodies are
 * rendered + animated by BetterModel (entity animations with Blockbench semantics) instead of our own frame baking.
 * Everything in here touches the BetterModel API, so it is only loaded when {@link #installed()} is true.
 */
public final class ModelBridge {

    private final JavaPlugin plugin;
    private final String prefix;

    public ModelBridge(JavaPlugin plugin, String prefix) { this.plugin = plugin; this.prefix = prefix; }

    public static boolean installed() {
        if (Bukkit.getPluginManager().getPlugin("BetterModel") == null) return false;
        try { Class.forName("kr.toxicity.model.api.BetterModel"); return true; } catch (Throwable t) { return false; }
    }

    public boolean active() { return installed() && plugin.getConfig().getBoolean("bettermodel.enabled", true); }

    /** The BetterModel model name for one of our model files. */
    public String key(String model) { return prefix + "_" + model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"); }

    /** Mirror models/*.bbmodel into BetterModel's models folder; reload BetterModel if anything changed. Returns the change count. */
    public int sync(File modelsDir) {
        File bm = new File(BetterModel.platform().dataFolder(), "models");
        bm.mkdirs();
        int changed = 0;
        File[] src = modelsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        java.util.Set<String> wanted = new java.util.HashSet<>();
        if (src != null) for (File f : src) {
            String name = f.getName().substring(0, f.getName().length() - ".bbmodel".length());
            File target = new File(bm, key(name) + ".bbmodel");
            wanted.add(target.getName());
            try {
                byte[] data = prepare(f, name);
                if (!target.exists() || !java.util.Arrays.equals(data, Files.readAllBytes(target.toPath()))) { Files.write(target.toPath(), data); changed++; }
            } catch (Exception e) { plugin.getLogger().warning("Could not copy " + f.getName() + " to BetterModel: " + e); }
        }
        File[] stale = bm.listFiles((d, n) -> n.startsWith(prefix + "_") && n.endsWith(".bbmodel") && !wanted.contains(n));
        if (stale != null) for (File s : stale) if (s.delete()) changed++;
        if (changed > 0) {
            try { BetterModelBukkit.platform().reload(); } catch (Throwable t) { plugin.getLogger().warning("BetterModel reload failed: " + t); }
        }
        return changed;
    }

    // ------------------------------------------------------------------ BetterModel-safe model files

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

    /** The .bbmodel bytes handed to BetterModel. Every cube whose rotation is not a legal Java element rotation is
     *  moved into its own bone (origin = the cube's own pivot, rotation = the cube's rotation, cube rotation zeroed):
     *  a bone rotation is exact in BetterModel, a free cube rotation is approximated and comes out deformed. */
    byte[] prepare(File f, String name) throws java.io.IOException {
        String text = Files.readString(f.toPath());
        com.google.gson.JsonObject root;
        try { root = com.google.gson.JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return text.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        java.util.List<String> notes = new java.util.ArrayList<>();
        int wrapped = rewrite(root, notes);
        for (String n : notes) plugin.getLogger().info("BetterModel model " + key(name) + ": " + n);
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

    /** /vehicle check <model>: what the BetterModel copy of a model looks like. */
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

    public boolean has(String model) { return model != null && !model.isEmpty() && BetterModel.model(key(model)).isPresent(); }

    /** Animation names are matched like our pack does: case-insensitive, spaces/dashes = underscores ("drive_bounce" finds "Drive Bounce"). */
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\-]+", "_"); }

    /** The animation's real name in the BetterModel model, or null when the model has no such animation. */
    public String resolve(String model, String anim) {
        if (anim == null || anim.isEmpty()) return null;
        Optional<ModelRenderer> r = BetterModel.model(key(model));
        if (r.isEmpty()) return null;
        if (r.get().animation(anim).isPresent()) return anim;
        String want = norm(anim);
        for (String n : r.get().animations().keySet()) if (norm(n).equals(want)) return n;
        return null;
    }

    public boolean hasAnimation(String model, String anim) { return resolve(model, anim) != null; }

    public java.util.Set<String> animations(String model) {
        Optional<ModelRenderer> r = BetterModel.model(key(model));
        if (r.isEmpty()) return java.util.Set.of();
        return new java.util.TreeSet<>(r.get().animations().keySet());
    }

    /** BetterModel turns every model 180 degrees itself (Blockbench north = the entity's front), the same job our
     *  ItemDisplay pipeline did with model-yaw 180. So the yaw BetterModel gets = entity yaw + (model-yaw - 180) + the
     *  config nudge `bettermodel.yaw-offset`, normalised to -180..180. */
    public static float modelYaw(float entityYaw, double modelYaw, double yawOffset) {
        double y = (entityYaw + modelYaw - 180.0 + yawOffset) % 360.0;
        if (y > 180) y -= 360; if (y <= -180) y += 360;
        return (float) y;
    }

    /** BetterModel's default scaler follows the ENTITY's scale attribute - our base Pig carries hitbox/0.9 (2.0 by
     *  default) for its collision box, which doubled every model. The model must use the type's own scale only. */
    public static float modelScale(double typeScale) { return (float) Math.max(0.01, Math.min(16.0, typeScale)); }

    public double yawOffset() { return plugin.getConfig().getDouble("bettermodel.yaw-offset", 0.0); }

    /** Attach the model to an entity; BetterModel then follows the entity. yawSupplier = the ABSOLUTE model yaw per tick
     *  (see modelYaw), scale = the model scale (independent of the entity's scale attribute). */
    /** BetterModel only has pitch + yaw. Roll past 90 degrees (on the roof) is shown as pitch 180 - pitch with the yaw
     *  turned 180 (Rz(180) = Ry(180) Rx(180)); smaller rolls cannot be shown and are dropped. Returns {bmPitch, yawAdd}. */
    public static double[] bmRotation(double pitch, double roll, double pitchSign) {
        double p = -pitch * pitchSign;   // entity pitch: positive = nose down
        if (Math.abs(roll) > 90) return new double[]{180 - p, 180};
        return new double[]{p, 0};
    }

    /** rotSupplier = {pitch, absolute yaw} per tick (degrees). */
    public EntityTracker attach(Entity base, String model, Supplier<float[]> rotSupplier, double scale) {
        Optional<ModelRenderer> r = BetterModel.model(key(model));
        if (r.isEmpty()) return null;
        EntityTracker t = r.get().create(BukkitAdapter.adapt(base), new TrackerModifier(true, false, false));
        t.scaler(kr.toxicity.model.api.tracker.ModelScaler.value(modelScale(scale)));
        if (rotSupplier != null) t.rotation(() -> { float[] pr = rotSupplier.get(); return new ModelRotation(pr[0], pr[1]); });
        return t;
    }

    private static String resolveOn(EntityTracker t, String anim) {
        if (t.renderer().animation(anim).isPresent()) return anim;
        String want = norm(anim);
        for (String n : t.renderer().animations().keySet()) if (norm(n).equals(want)) return n;
        return null;
    }

    public void animate(EntityTracker t, String anim, boolean loop, Supplier<Float> speed) {
        if (t == null || t.isClosed() || anim == null || anim.isEmpty()) return;
        String real = resolveOn(t, anim);
        if (real == null) return;
        t.animate(real, AnimationModifier.builder()
            .type(loop ? AnimationIterator.Type.LOOP : AnimationIterator.Type.PLAY_ONCE)
            .speed(() -> speed == null ? 1f : speed.get())
            .build());
    }

    public void animateHold(EntityTracker t, String anim) {
        if (t == null || t.isClosed() || anim == null || anim.isEmpty()) return;
        String real = resolveOn(t, anim);
        if (real != null) t.animate(real, AnimationModifier.builder().type(AnimationIterator.Type.HOLD_ON_LAST).build());
    }

    public void stop(EntityTracker t, String anim) {
        if (t == null || t.isClosed() || anim == null || anim.isEmpty()) return;
        String real = resolveOn(t, anim);
        if (real != null) t.stopAnimation(real);
    }

    public void close(EntityTracker t) { if (t != null && !t.isClosed()) t.close(); }
}
