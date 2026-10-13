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
                byte[] data = Files.readAllBytes(f.toPath());
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
    public EntityTracker attach(Entity base, String model, Supplier<Float> yawSupplier, double scale) {
        Optional<ModelRenderer> r = BetterModel.model(key(model));
        if (r.isEmpty()) return null;
        EntityTracker t = r.get().create(BukkitAdapter.adapt(base), new TrackerModifier(true, false, false));
        t.scaler(kr.toxicity.model.api.tracker.ModelScaler.value(modelScale(scale)));
        if (yawSupplier != null) t.rotation(() -> new ModelRotation(0f, yawSupplier.get()));
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
