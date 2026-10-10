package com.CodDev.vehicle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** All vehicle types, persisted in plugins/Vehicle/vehicles.yml. */
public final class TypeRegistry {

    private final VehiclePlugin plugin;
    private final Map<String, VehicleType> types = new LinkedHashMap<>();

    public TypeRegistry(VehiclePlugin plugin) { this.plugin = plugin; }

    private File file() { return new File(plugin.getDataFolder(), "vehicles.yml"); }

    public void load() {
        types.clear();
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("types");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            VehicleType t = new VehicleType(id);
            t.load(s);
            types.put(t.id, t);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(java.util.List.of(
            "Vehicle types - edit in game with /vehicle edit, or here and /vehicle reload.",
            "Seats are in model space: blocks = Blockbench pixels / 16 (x sideways, y up, z forward/back), before scale."));
        for (VehicleType t : types.values()) t.save(yml.createSection("types." + t.id));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save vehicles.yml: " + e.getMessage());
        }
    }

    public VehicleType get(String id) { return id == null ? null : types.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<VehicleType> all() { return types.values(); }
    public boolean exists(String id) { return get(id) != null; }

    public VehicleType create(String id) {
        VehicleType t = new VehicleType(id);
        types.put(t.id, t);
        save();
        return t;
    }

    public void delete(String id) {
        types.remove(id.toLowerCase(Locale.ROOT));
        save();
    }
}
