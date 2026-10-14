package com.CodDev.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class VehicleCommand implements org.bukkit.command.CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("create", "edit", "spawn", "remove", "delete", "list", "info", "models", "give", "pack", "reload", "help");
    private static final List<String> PROPS = List.of("name", "model", "speed", "acceleration", "brake", "reverse", "friction", "turn", "grip",
        "scale", "modelyaw", "offset", "animation", "hitbox", "stepheight", "health", "crash", "crashdamage", "ramdamage", "sound", "volume", "horn",
        "seats", "seat", "seatsize");

    private final VehiclePlugin plugin;

    public VehicleCommand(VehiclePlugin plugin) { this.plugin = plugin; }

    private static void ok(CommandSender s, String text) { s.sendMessage(Component.text("[Vehicle] ", NamedTextColor.AQUA).append(Component.text(text, NamedTextColor.GRAY))); }
    private static void err(CommandSender s, String text) { s.sendMessage(Component.text("[Vehicle] ", NamedTextColor.AQUA).append(Component.text(text, NamedTextColor.RED))); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vehicle.admin")) { err(sender, "You need the vehicle.admin permission."); return true; }
        if (args.length == 0) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "create" -> create(sender, args);
                case "edit" -> edit(sender, args);
                case "spawn" -> spawn(sender, args);
                case "check" -> {
                    if (args.length < 2) { err(sender, "Usage: /vehicle check <model>"); return true; }
                    java.io.File f = new java.io.File(new java.io.File(plugin.getDataFolder(), "models"), args[1] + ".bbmodel");
                    if (!f.exists()) { err(sender, "No models/" + args[1] + ".bbmodel"); return true; }
                    if (plugin.bridge() == null) { err(sender, "BetterModel is not active - /vehicle check is for the BetterModel copy of a model."); return true; }
                    ok(sender, "BetterModel check of " + args[1] + " (served as " + plugin.bridge().key(args[1]) + "):");
                    for (String n : plugin.bridge().check(f)) ok(sender, "  " + n);
                    ok(sender, "  loaded by BetterModel: " + (plugin.bridge().has(args[1]) ? "yes, animations " + plugin.bridge().animations(args[1]) : "NO - see BetterModel's console warnings"));
                }
                case "remove" -> remove(sender, args);
                case "delete" -> delete(sender, args);
                case "list" -> list(sender);
                case "info" -> info(sender, args);
                case "models" -> models(sender);
                case "give" -> give(sender, args);
                case "pack" -> pack(sender);
                case "reload" -> { plugin.reloadAll(); ok(sender, "Reloaded config, vehicle types and regenerated the resource pack."); }
                default -> help(sender);
            }
        } catch (NumberFormatException e) {
            err(sender, "That is not a number: " + e.getMessage().replace("For input string: ", ""));
        }
        return true;
    }

    private void help(CommandSender s) {
        ok(s, "Commands:");
        s.sendMessage(Component.text("/vehicle create <id> [model]   - new vehicle type", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/vehicle edit <id> <property> <value...>   - see /vehicle edit <id> for properties", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/vehicle spawn <id>   - spawn one where you stand", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/vehicle remove [radius|all]   - remove the vehicle you look at / nearby / all", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/vehicle delete <id>   - delete a type (and every spawned one)", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/vehicle list | info <id> | models | give <player> <id> [amount] | pack | reload", NamedTextColor.GRAY));
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /vehicle create <id> [model]"); return; }
        if (!VehicleType.validId(a[1])) { err(s, "Ids may only use letters, numbers, _ and - (max 32)."); return; }
        if (plugin.types().exists(a[1])) { err(s, "Type '" + a[1] + "' already exists."); return; }
        VehicleType t = plugin.types().create(a[1]);
        if (a.length >= 3) {
            t.model = a[2];
            if (plugin.modelInfo(a[2]) == null) ok(s, "Note: no model named '" + a[2] + "' was found in plugins/Vehicle/models/ - it will show once the file is there and the pack is regenerated.");
        }
        plugin.types().save();
        ok(s, "Created vehicle type '" + t.id + "'" + (t.model.isEmpty() ? " (no model yet: /vehicle edit " + t.id + " model <name>)" : " with model '" + t.model + "'") + ". Spawn it with /vehicle spawn " + t.id);
    }

    private void edit(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /vehicle edit <id> <property> <value...>"); return; }
        VehicleType t = plugin.types().get(a[1]);
        if (t == null) { err(s, "No vehicle type '" + a[1] + "'. /vehicle list"); return; }
        if (a.length < 3) {
            ok(s, "Properties for '" + t.id + "':");
            s.sendMessage(Component.text("name <text> | model <bbmodel name> | speed <blocks/s> | acceleration <b/s²> | brake <b/s²> | reverse <0-1> | friction <0-1> | turn <deg/tick> | grip <0-1>", NamedTextColor.GRAY));
            s.sendMessage(Component.text("scale <n> | modelyaw <deg> | offset <x> <y> <z> | hitbox <blocks> | stepheight <blocks> | health <hp> | crash <true|false> | crashdamage <mult> | ramdamage <mult>", NamedTextColor.GRAY));
            s.sendMessage(Component.text("sound <key> | volume <n> | horn <key> | seats <count> | seat <n> <x> <y> <z> | seat <n> x|y|z <v> | seat <n> size <w> <h> | seatsize <w> <h>", NamedTextColor.GRAY));
            return;
        }
        String prop = a[2].toLowerCase(Locale.ROOT);
        String[] v = Arrays.copyOfRange(a, 3, a.length);
        if (v.length == 0 && !prop.equals("name")) { err(s, "Missing value."); return; }
        switch (prop) {
            case "name" -> t.name = v.length == 0 ? t.id : String.join(" ", v);
            case "model" -> {
                t.model = v[0];
                if (!plugin.hasModel(v[0])) ok(s, "Note: no model named '" + v[0] + "' is " + (plugin.bmActive() ? "known to BetterModel yet (file in plugins/Vehicle/models? it is synced + reloaded on restart)" : "in the generated pack") + ". Models: " + String.join(", ", PackGenerator.scanNames(plugin)));
            }
            case "speed", "max-speed", "maxspeed" -> t.maxSpeed = Math.max(0.1, Double.parseDouble(v[0]));
            case "acceleration", "accel" -> t.acceleration = Math.max(0.1, Double.parseDouble(v[0]));
            case "brake" -> t.brake = Math.max(0.1, Double.parseDouble(v[0]));
            case "reverse" -> t.reverseFactor = Math.max(0, Math.min(1, Double.parseDouble(v[0])));
            case "friction" -> t.friction = Math.max(0, Math.min(1, Double.parseDouble(v[0])));
            case "turn", "turn-rate", "turnrate" -> t.turnRate = Math.max(0, Double.parseDouble(v[0]));
            case "grip" -> t.grip = Math.max(0.05, Math.min(1, Double.parseDouble(v[0])));
            case "scale" -> t.scale = Math.max(0.05, Math.min(50, Double.parseDouble(v[0])));
            case "modelyaw", "model-yaw", "rotation" -> t.modelYaw = Double.parseDouble(v[0]);
            case "animation", "anim" -> {
                String sub = v[0].toLowerCase(java.util.Locale.ROOT);
                java.util.Set<String> names = plugin.bmActive() ? plugin.bridge().animations(t.model) : plugin.animationNames(t.model);
                String stateKey = VehicleType.stateKey(sub);
                if (sub.equals("list")) {
                    ok(s, "Animations in model '" + t.model + "': " + (names.isEmpty() ? "(none - the .bbmodel has no animations, or the pack was built in baked mode)" : String.join(", ", names)));
                    StringBuilder b = new StringBuilder();
                    for (String st : VehicleType.STATES) b.append(st).append("=").append(t.animations.getOrDefault(st, "").isEmpty() ? "-" : t.animations.get(st)).append("  ");
                    ok(s, "States: " + b + "(unset states fall back: left/right/reverse -> drive, reverse_left -> reverse -> left -> drive)");
                } else if (sub.equals("clear") || sub.equals("none")) {
                    if (v.length >= 2 && VehicleType.stateKey(v[1]) != null) t.animations.remove(VehicleType.stateKey(v[1])); else t.animations.clear();
                } else if (stateKey != null) {
                    if (v.length < 2) { err(s, "Usage: animation " + stateKey + " <animation name>   (animation list shows them)"); return; }
                    String name = plugin.bmActive() ? v[1] : v[1].toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    t.animations.put(stateKey, name);
                    if (plugin.bmActive() ? !plugin.bridge().hasAnimation(t.model, v[1]) : !names.contains(name)) ok(s, "Note: '" + v[1] + "' is not in the pack for model '" + t.model + "'. Known: " + String.join(", ", names));
                } else { err(s, "Usage: animation <idle|drive|reverse|left|right|reverse_left|reverse_right> <name> | clear [state] | list"); return; }
            }
            case "offset" -> {
                if (v.length < 3) { err(s, "Usage: offset <x> <y> <z>"); return; }
                t.offsetX = Double.parseDouble(v[0]); t.offsetY = Double.parseDouble(v[1]); t.offsetZ = Double.parseDouble(v[2]);
            }
            case "hitbox" -> t.hitbox = Math.max(0.2, Math.min(14.0, Double.parseDouble(v[0])));
            case "stepheight", "step-height" -> t.stepHeight = Math.max(0, Math.min(10, Double.parseDouble(v[0])));
            case "health", "hp" -> t.health = Math.max(1, Math.min(1024, Double.parseDouble(v[0])));
            case "crash" -> t.crash = v[0].equalsIgnoreCase("true") || v[0].equalsIgnoreCase("on") || v[0].equalsIgnoreCase("yes");
            case "crashdamage", "crash-damage" -> t.crashMultiplier = Math.max(0, Double.parseDouble(v[0]));
            case "ramdamage", "ram-damage" -> t.ramMultiplier = Math.max(0, Double.parseDouble(v[0]));
            case "sound" -> t.sound = v[0].equalsIgnoreCase("none") ? "" : v[0];
            case "volume" -> t.soundVolume = Math.max(0, Math.min(4, Double.parseDouble(v[0])));
            case "horn" -> t.horn = v[0].equalsIgnoreCase("none") ? "" : v[0];
            case "seats" -> t.setSeatCount(Integer.parseInt(v[0]));
            case "seatsize" -> {
                if (v.length < 2) { err(s, "Usage: seatsize <width> <height>"); return; }
                double w = Double.parseDouble(v[0]), h = Double.parseDouble(v[1]);
                for (VehicleType.Seat seat : t.seats) { seat.width = Math.max(0.1, Math.min(8, w)); seat.height = Math.max(0.1, Math.min(8, h)); }
            }
            case "seat" -> {
                if (v.length < 2) { err(s, "Usage: seat <n> <x> <y> <z>  |  seat <n> x|y|z <value>  |  seat <n> size <w> <h>"); return; }
                int n = Integer.parseInt(v[0]) - 1;
                if (n < 0 || n >= t.seats.size()) { err(s, "Seat numbers go from 1 to " + t.seats.size() + " (change the count with: seats <n>)."); return; }
                VehicleType.Seat seat = t.seats.get(n);
                String k = v[1].toLowerCase(Locale.ROOT);
                if (k.equals("x") || k.equals("y") || k.equals("z")) {
                    if (v.length < 3) { err(s, "Usage: seat <n> " + k + " <value>"); return; }
                    double val = Double.parseDouble(v[2]);
                    if (k.equals("x")) seat.x = val; else if (k.equals("y")) seat.y = val; else seat.z = val;
                } else if (k.equals("size")) {
                    if (v.length < 4) { err(s, "Usage: seat <n> size <width> <height>"); return; }
                    seat.width = Math.max(0.1, Math.min(8, Double.parseDouble(v[2])));
                    seat.height = Math.max(0.1, Math.min(8, Double.parseDouble(v[3])));
                } else {
                    if (v.length < 4) { err(s, "Usage: seat <n> <x> <y> <z>"); return; }
                    seat.x = Double.parseDouble(v[1]); seat.y = Double.parseDouble(v[2]); seat.z = Double.parseDouble(v[3]);
                }
            }
            default -> { err(s, "Unknown property '" + prop + "'. /vehicle edit " + t.id + " lists them."); return; }
        }
        plugin.types().save();
        int live = 0;
        for (LivingEntity base : plugin.manager().all()) {
            if (t.id.equals(plugin.manager().typeIdOf(base))) { plugin.manager().applyType(base, t); live++; }
        }
        ok(s, "Set " + prop + " on '" + t.id + "'" + (live > 0 ? " (applied to " + live + " spawned vehicle(s))" : "") + ".");
    }

    private void spawn(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /vehicle spawn <id> [x y z [world]]"); return; }
        VehicleType t = plugin.types().get(a[1]);
        if (t == null) { err(s, "No vehicle type '" + a[1] + "'."); return; }
        Location at;
        if (a.length >= 5) {
            org.bukkit.World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : (s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0));
            if (w == null) { err(s, "No world '" + a[5] + "'."); return; }
            at = new Location(w, Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), s instanceof Player p ? p.getLocation().getYaw() : 0f, 0f);
        } else if (s instanceof Player p) {
            at = p.getLocation();
        } else { err(s, "From the console: /vehicle spawn <id> <x> <y> <z> [world]"); return; }
        plugin.manager().spawn(t, at, s instanceof Player p ? p.getUniqueId() : null);
        String note = t.model.isEmpty() ? " It has no model yet (/vehicle edit " + t.id + " model <name>)."
            : plugin.modelInfo(t.model) == null ? " Model '" + t.model + "' is not in the generated pack - it shows as a plain item." : "";
        ok(s, "Spawned " + t.name + "." + note);
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("all")) {
            int n = 0;
            for (LivingEntity b : plugin.manager().all()) { plugin.manager().remove(b); n++; }
            ok(s, "Removed " + n + " vehicle(s) in all worlds.");
            return;
        }
        if (!(s instanceof Player p)) { err(s, "Players only (or /vehicle remove all)."); return; }
        if (a.length >= 2) {
            double r = Double.parseDouble(a[1]);
            int n = 0;
            for (LivingEntity b : plugin.manager().all()) {
                if (b.getWorld() == p.getWorld() && b.getLocation().distanceSquared(p.getLocation()) <= r * r) { plugin.manager().remove(b); n++; }
            }
            ok(s, "Removed " + n + " vehicle(s) within " + r + " blocks.");
            return;
        }
        LivingEntity target = null;
        var ray = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 12, e -> plugin.manager().baseOf(e) != null);
        if (ray != null && ray.getHitEntity() != null) target = plugin.manager().baseOf(ray.getHitEntity());
        if (target == null) {
            double best = 8 * 8;
            for (LivingEntity b : plugin.manager().all()) {
                if (b.getWorld() != p.getWorld()) continue;
                double d = b.getLocation().distanceSquared(p.getLocation());
                if (d < best) { best = d; target = b; }
            }
        }
        if (target == null) { err(s, "No vehicle in sight or within 8 blocks. Use /vehicle remove <radius> or all."); return; }
        VehicleType t = plugin.manager().typeOf(target);
        plugin.manager().remove(target);
        ok(s, "Removed " + (t != null ? t.name : "vehicle") + ".");
    }

    private void delete(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /vehicle delete <id>"); return; }
        VehicleType t = plugin.types().get(a[1]);
        if (t == null) { err(s, "No vehicle type '" + a[1] + "'."); return; }
        int n = 0;
        for (LivingEntity b : plugin.manager().all()) if (t.id.equals(plugin.manager().typeIdOf(b))) { plugin.manager().remove(b); n++; }
        plugin.types().delete(t.id);
        ok(s, "Deleted type '" + t.id + "' and removed " + n + " spawned vehicle(s).");
    }

    private void list(CommandSender s) {
        if (plugin.types().all().isEmpty()) { ok(s, "No vehicle types yet. /vehicle create <id> [model]"); return; }
        ok(s, "Vehicle types:");
        for (VehicleType t : plugin.types().all()) {
            int live = 0;
            for (LivingEntity b : plugin.manager().all()) if (t.id.equals(plugin.manager().typeIdOf(b))) live++;
            s.sendMessage(Component.text(" - " + t.id + "  \"" + t.name + "\"  model=" + (t.model.isEmpty() ? "(none)" : t.model) + "  seats=" + t.seats.size() + "  speed=" + t.maxSpeed + "  spawned=" + live, NamedTextColor.GRAY));
        }
    }

    private void info(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /vehicle info <id>"); return; }
        VehicleType t = plugin.types().get(a[1]);
        if (t == null) { err(s, "No vehicle type '" + a[1] + "'."); return; }
        PackGenerator.ModelInfo mi = plugin.modelInfo(t.model);
        ok(s, t.id + " - \"" + t.name + "\"");
        s.sendMessage(Component.text("model=" + (t.model.isEmpty() ? "(none)" : t.model + (mi == null ? " (NOT in pack)" : " (" + mi.cubes() + " cubes, " + Math.round(mi.maxX() - mi.minX()) + "x" + Math.round(mi.maxY() - mi.minY()) + "x" + Math.round(mi.maxZ() - mi.minZ()) + " px)")) + " scale=" + t.scale + " modelyaw=" + t.modelYaw + " offset=" + t.offsetX + "," + t.offsetY + "," + t.offsetZ, NamedTextColor.GRAY));
        s.sendMessage(Component.text("speed=" + t.maxSpeed + " acceleration=" + t.acceleration + " brake=" + t.brake + " reverse=" + t.reverseFactor + " friction=" + t.friction + " turn=" + t.turnRate + " grip=" + t.grip, NamedTextColor.GRAY));
        s.sendMessage(Component.text("hitbox=" + t.hitbox + " stepheight=" + t.stepHeight + " health=" + t.health + " crash=" + t.crash + " crashdamage=" + t.crashMultiplier + " ramdamage=" + t.ramMultiplier, NamedTextColor.GRAY));
        s.sendMessage(Component.text("sound=" + t.sound + " volume=" + t.soundVolume + " horn=" + t.horn, NamedTextColor.GRAY));
        for (int i = 0; i < t.seats.size(); i++) {
            VehicleType.Seat seat = t.seats.get(i);
            s.sendMessage(Component.text("seat " + (i + 1) + (i == 0 ? " (driver)" : "") + ": x=" + seat.x + " y=" + seat.y + " z=" + seat.z + " size=" + seat.width + "x" + seat.height, NamedTextColor.GRAY));
        }
    }

    private void models(CommandSender s) {
        List<String> names = PackGenerator.scanNames(plugin);
        if (names.isEmpty()) { ok(s, "No .bbmodel files in plugins/Vehicle/models/ yet. Drop them there and run /vehicle pack."); return; }
        ok(s, "Models in plugins/Vehicle/models/ (use with: /vehicle edit <id> model <name>):");
        for (String n : names) {
            PackGenerator.ModelInfo mi = plugin.modelInfo(n);
            s.sendMessage(Component.text(" - " + n + (mi == null ? "  (not converted - see generated/pack-report.txt)" : "  " + mi.cubes() + " cubes, " + Math.round(mi.maxX() - mi.minX()) + "x" + Math.round(mi.maxY() - mi.minY()) + "x" + Math.round(mi.maxZ() - mi.minZ()) + " px"), NamedTextColor.GRAY));
        }
        if (plugin.lastPack() != null) s.sendMessage(Component.text("Pack: " + plugin.lastPack().zip().getPath() + "  sha1=" + plugin.lastPack().sha1(), NamedTextColor.DARK_GRAY));
    }

    private void give(CommandSender s, String[] a) {
        if (a.length < 3) { err(s, "Usage: /vehicle give <player> <id> [amount]"); return; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { err(s, "Player '" + a[1] + "' is not online."); return; }
        VehicleType t = plugin.types().get(a[2]);
        if (t == null) { err(s, "No vehicle type '" + a[2] + "'."); return; }
        int amount = a.length >= 4 ? Math.max(1, Integer.parseInt(a[3])) : 1;
        target.getInventory().addItem(plugin.manager().vehicleItem(t, amount)).values()
            .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        ok(s, "Gave " + amount + "x " + t.name + " to " + target.getName() + ".");
    }

    private void pack(CommandSender s) {
        PackGenerator.Result r = plugin.generatePack();
        if (r == null) { err(s, "Pack generation failed - see the console."); return; }
        plugin.reapplyAll();
        ok(s, "Pack written: " + r.zip().getPath() + " (" + r.models() + " model(s), " + (r.bytes() / 1024) + " KB, " + r.millis() + " ms). sha1: " + r.sha1());
        if (!r.warnings().isEmpty()) {
            ok(s, r.warnings().size() + " warning(s) - full list in generated/pack-report.txt:");
            for (int i = 0; i < Math.min(5, r.warnings().size()); i++) s.sendMessage(Component.text(" ! " + r.warnings().get(i), NamedTextColor.YELLOW));
        }
        ok(s, "Players need the new pack (and its sha1) to see changes.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vehicle.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (VehicleType t : plugin.types().all()) ids.add(t.id);
        switch (sub) {
            case "edit", "spawn", "delete", "info" -> {
                if (args.length == 2) return filter(ids, args[1]);
                if (sub.equals("edit")) {
                    if (args.length == 3) return filter(PROPS, args[2]);
                    if (args.length == 4 && (args[2].equalsIgnoreCase("animation") || args[2].equalsIgnoreCase("anim"))) return filter(new ArrayList<>(java.util.stream.Stream.concat(VehicleType.STATES.stream(), java.util.stream.Stream.of("set", "clear", "list")).toList()), args[3]);
                    if (args.length == 5 && (args[2].equalsIgnoreCase("animation") || args[2].equalsIgnoreCase("anim"))) {
                        VehicleType tt = plugin.types().get(args[1].toLowerCase(java.util.Locale.ROOT));
                        return tt == null ? List.of() : filter(new ArrayList<>(plugin.bmActive() ? plugin.bridge().animations(tt.model) : plugin.animationNames(tt.model)), args[4]);
                    }
                    String prop = args[2].toLowerCase(Locale.ROOT);
                    if (args.length == 4 && prop.equals("model")) return filter(PackGenerator.scanNames(plugin), args[3]);
                    if (args.length == 4 && prop.equals("crash")) return filter(List.of("true", "false"), args[3]);
                    if (prop.equals("seat")) {
                        VehicleType t = plugin.types().get(args[1]);
                        if (args.length == 4 && t != null) { List<String> n = new ArrayList<>(); for (int i = 1; i <= t.seats.size(); i++) n.add(String.valueOf(i)); return filter(n, args[3]); }
                        if (args.length == 5) return filter(List.of("x", "y", "z", "size", "<x>"), args[4]);
                    }
                }
            }
            case "create" -> { if (args.length == 3) return filter(PackGenerator.scanNames(plugin), args[2]); }
            case "give" -> {
                if (args.length == 2) { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); return filter(out, args[1]); }
                if (args.length == 3) return filter(ids, args[2]);
            }
            case "remove" -> { if (args.length == 2) return filter(List.of("all", "8", "16", "32"), args[1]); }
            default -> { }
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    @SuppressWarnings("unused")
    private static boolean isVehicleEntity(Entity e) { return VehicleManager.isBase(e) || VehicleManager.isPart(e); }
}
