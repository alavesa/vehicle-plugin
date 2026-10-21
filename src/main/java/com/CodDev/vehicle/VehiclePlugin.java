package com.CodDev.vehicle;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public final class VehiclePlugin extends JavaPlugin {

    private TypeRegistry types;
    private VehicleManager manager;
    private DriveTask task;
    private Material baseItem = Material.TURTLE_SCUTE;
    private Map<String, PackGenerator.ModelInfo> models = Collections.emptyMap();
    private Map<String, Map<String, int[]>> animations = Collections.emptyMap();
    private ModelBridge bridge;
    private boolean bmActive;
    private PackGenerator.Result lastPack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new File(getDataFolder(), "models").mkdirs();
        new File(getDataFolder(), "generated").mkdirs();
        readBaseItem();
        types = new TypeRegistry(this);
        types.load();
        manager = new VehicleManager(this);
        task = new DriveTask(this, manager);
        generatePack();
        Bukkit.getPluginManager().registerEvents(new VehicleListener(this, manager, task), this);
        VehicleCommand cmd = new VehicleCommand(this);
        getCommand("vehicle").setExecutor(cmd);
        getCommand("vehicle").setTabCompleter(cmd);
        Bukkit.getScheduler().runTaskTimer(this, task, 1L, 1L);
        // bodies of already-spawned vehicles get the (possibly changed) model fit on every start
        Bukkit.getScheduler().runTask(this, this::reapplyAll);
        getLogger().info("Vehicle " + getDescription().getVersion() + " enabled - " + types.all().size() + " type(s), " + models.size() + " model(s)");
    }

    private void readBaseItem() {
        String key = getConfig().getString("base-item", "minecraft:turtle_scute");
        Material m = Material.matchMaterial(key);
        if (m == null || !m.isItem()) {
            getLogger().warning("base-item '" + key + "' is not an item - using turtle_scute");
            m = Material.TURTLE_SCUTE;
        }
        baseItem = m;
    }

    /** Scan models/ and write generated/Vehicle-pack.zip. Safe to call again (/vehicle pack, /vehicle reload). */
    public PackGenerator.Result generatePack() {
        // RP Engine installed: mirror our models into its folder (it builds the one pack) and let it render + animate.
        bmActive = false;
        if (ModelBridge.installed()) {
            bridge = new ModelBridge(this, "vehicle");
            if (bridge.active()) {
                bmActive = true;
                try {
                    int n = bridge.sync(PackGenerator.modelsDir(this));
                    getLogger().info("RP Engine detected: vehicle models are rendered by RP Engine (" + n + " model file(s) synced to plugins/RPEngine/content/vehicle/models). Our own pack is not needed.");
                } catch (Throwable t) { getLogger().warning("RP Engine sync failed, falling back to the built-in pack: " + t); bmActive = false; }
            }
        }
        PackGenerator gen = new PackGenerator(this);
        try {
            lastPack = gen.generate();
            models = gen.infos();
            animations = gen.animations();
            getLogger().info("Resource pack: " + lastPack.models() + " model(s) -> " + lastPack.zip().getPath() + " (" + (lastPack.bytes() / 1024) + " KB, sha1 " + lastPack.sha1() + ")");
            // per-cube rounding notes are only noise in the console: one line per model, details in pack-report.txt
            Map<String, Integer> rounded = new java.util.LinkedHashMap<>();
            for (String w : lastPack.warnings()) {
                if (w.contains("rounded to the nearest 22.5 step")) rounded.merge(w.substring(0, w.indexOf(':')), 1, Integer::sum);
                else getLogger().warning("pack: " + w);
            }
            for (var e : rounded.entrySet()) getLogger().warning("pack: " + e.getKey() + ": " + e.getValue() + " cube/group rotation(s) rounded to 22.5-degree steps (Java model limit) - list in generated/pack-report.txt");
            return lastPack;
        } catch (Exception e) {
            getLogger().severe("Resource pack generation failed: " + e);
            return null;
        }
    }

    public void reapplyAll() {
        for (LivingEntity base : manager.all()) {
            base = manager.migrateLegacy(base);
            if (base == null) continue;
            VehicleType t = manager.typeOf(base);
            if (t != null) manager.applyType(base, t);
        }
    }

    public void reloadAll() {
        reloadConfig();
        readBaseItem();
        types.load();
        generatePack();
        reapplyAll();
    }

    public TypeRegistry types() { return types; }
    public VehicleManager manager() { return manager; }
    public DriveTask task() { return task; }
    public Material baseItem() { return baseItem; }
    @Override
    public void onDisable() {
        try { if (bmActive && manager != null) manager.closeAllTrackers(); } catch (Throwable ignored) { }
    }

    public PackGenerator.ModelInfo modelInfo(String model) { return model == null ? null : models.get(model); }
    public ModelBridge bridge() { return bmActive ? bridge : null; }
    public boolean bmActive() { return bmActive; }
    /** Is this model available to render (RP Engine or our pack)? */
    public boolean hasModel(String model) { return bmActive ? bridge.has(model) : modelInfo(model) != null; }
    /** [frames, frame-ticks] of a model's animation, or null. */
    public int[] animation(String model, String anim) { if (model == null || anim == null) return null; Map<String, int[]> m = animations.get(model); return m == null ? null : m.get(anim); }
    public java.util.Set<String> animationNames(String model) { Map<String, int[]> m = model == null ? null : animations.get(model); return m == null ? java.util.Set.of() : m.keySet(); }
    public Map<String, PackGenerator.ModelInfo> models() { return models; }
    public PackGenerator.Result lastPack() { return lastPack; }
}
