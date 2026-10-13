package com.CodDev.vehicle;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spawns, finds and removes vehicles and their parts.
 *
 * A vehicle is an invisible Pig (the physics body: gravity, block collision, step height, a real hitbox
 * via the SCALE attribute, and a real health pool) plus separate entities that are moved along every tick:
 * an ItemDisplay showing the Blockbench model, and per seat an invisible marker ArmorStand (the rider sits
 * on it) and an Interaction entity (the click box that lets players enter from a distance).
 */
public final class VehicleManager {

    /** BetterModel trackers per vehicle base (only used when BetterModel renders the bodies). */
    private final java.util.Map<java.util.UUID, kr.toxicity.model.api.tracker.EntityTracker> trackers = new java.util.HashMap<>();

    /** Make sure a BetterModel tracker shows this vehicle's model (no-op when BetterModel is not active). */
    public void ensureTracker(Pig base, VehicleType type) {
        ModelBridge bridge = plugin.bridge();
        if (bridge == null) return;
        var cur = trackers.get(base.getUniqueId());
        if (cur != null && !cur.isClosed()) return;
        if (!bridge.has(type.model)) return;
        // the model turns with the base every tick (DriveTask sets its rotation); the Pig's own body yaw never follows
        // (no AI), so BetterModel's default body-yaw tracking is replaced by the base yaw + model-yaw
        var t = bridge.attach(base, type.model, () -> ModelBridge.modelYaw(base.getLocation().getYaw(), type.modelYaw, bridge.yawOffset()), type.scale);
        if (t == null) return;
        trackers.put(base.getUniqueId(), t);
        // a vehicle that existed before BetterModel still carries the item model on its body: hide it, BetterModel draws
        ItemDisplay body = body(base);
        if (body != null && body.getItemStack().getType() != Material.AIR) body.setItemStack(new ItemStack(Material.AIR));
    }

    public kr.toxicity.model.api.tracker.EntityTracker tracker(Pig base) { return plugin.bridge() == null ? null : trackers.get(base.getUniqueId()); }

    public void closeTracker(java.util.UUID id) {
        var t = trackers.remove(id);
        if (t != null && plugin.bridge() != null) plugin.bridge().close(t);
    }

    public void closeAllTrackers() { for (java.util.UUID id : new java.util.ArrayList<>(trackers.keySet())) closeTracker(id); }

    public static final String TAG_BASE = "vehicle.base";
    public static final String TAG_PART = "vehicle.part";
    public static final String TAG_SEAT = "vehicle.seat";
    public static final String TAG_CLICK = "vehicle.click";
    public static final String TAG_BODY = "vehicle.body";
    public static final String TAG_ANCHOR = "vehicle.anchor";

    public record SeatRef(int index, ArmorStand stand, Interaction click) {
        public Player rider() {
            for (Entity e : stand.getPassengers()) if (e instanceof Player p) return p;
            return null;
        }
    }

    private final VehiclePlugin plugin;
    final NamespacedKey typeKey, baseKey, seatKey, seatsKey, bodyKey, ownerKey, itemKey, anchorKey;

    public VehicleManager(VehiclePlugin plugin) {
        this.plugin = plugin;
        typeKey = new NamespacedKey(plugin, "type");
        baseKey = new NamespacedKey(plugin, "base");
        seatKey = new NamespacedKey(plugin, "seat");
        seatsKey = new NamespacedKey(plugin, "seats");
        bodyKey = new NamespacedKey(plugin, "body");
        ownerKey = new NamespacedKey(plugin, "owner");
        itemKey = new NamespacedKey(plugin, "item");
        anchorKey = new NamespacedKey(plugin, "anchor");
    }

    // ------------------------------------------------------------------ spawn / remove

    public Pig spawn(VehicleType type, Location at, UUID owner) {
        Location loc = at.clone();
        loc.setPitch(0);
        Pig base = loc.getWorld().spawn(loc, Pig.class, pig -> {
            pig.setInvisible(true);
            pig.setSilent(true);
            pig.setPersistent(true);
            pig.setRemoveWhenFarAway(false);
            pig.setAdult();
            pig.setBreed(false);
            pig.setCanPickupItems(false);
            pig.customName(Component.text(type.name));
            pig.setCustomNameVisible(false);
            pig.addScoreboardTag(TAG_BASE);
            pig.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id);
            if (owner != null) pig.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
        // AI stays ON (NoAI freezes velocity processing), aware=false stops the pig's own decisions for good.
        base.setAware(false);
        Bukkit.getMobGoals().removeAllGoals(base);
        applyAttributes(base, type);
        base.setHealth(Math.min(base.getHealth(), maxHealth(base)));
        base.setHealth(maxHealth(base));
        ensureParts(base, type);
        tickParts(base, type, loc.getYaw());
        return base;
    }

    public void applyAttributes(Pig base, VehicleType type) {
        double scale = Math.max(0.1, Math.min(16.0, type.hitbox / 0.9));
        set(base, Attribute.SCALE, scale);
        set(base, Attribute.STEP_HEIGHT, Math.max(0.0, Math.min(10.0, type.stepHeight)));
        set(base, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        double hp = Math.max(1.0, Math.min(1024.0, type.health));
        set(base, Attribute.MAX_HEALTH, hp);
        if (base.getHealth() > hp) base.setHealth(hp);
    }

    private static void set(Pig base, Attribute attribute, double value) {
        var inst = base.getAttribute(attribute);
        if (inst == null) base.registerAttribute(attribute);
        inst = base.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    public double maxHealth(Pig base) {
        var inst = base.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20.0 : inst.getValue();
    }

    /** Remove a vehicle and all of its parts; riders are set free first. */
    public void remove(Pig base) {
        closeTracker(base.getUniqueId());
        for (Entity e : parts(base)) {
            e.eject();
            e.remove();
        }
        for (Entity p : new ArrayList<>(base.getPassengers())) if (isPart(p)) p.remove();
        base.eject();
        base.remove();
        plugin.task().forgetVehicle(base.getUniqueId());
    }

    /** Boom: effects, then removal. */
    public void destroy(Pig base) {
        World w = base.getWorld();
        Location l = base.getLocation().add(0, 0.5, 0);
        w.spawnParticle(Particle.EXPLOSION, l, 2, 0.5, 0.3, 0.5, 0);
        w.spawnParticle(Particle.LARGE_SMOKE, l, 30, 0.8, 0.5, 0.8, 0.02);
        w.spawnParticle(Particle.FLAME, l, 20, 0.6, 0.3, 0.6, 0.03);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        for (Player p : occupants(base)) p.sendActionBar(Component.text("The vehicle was destroyed!", NamedTextColor.RED));
        remove(base);
    }

    /** Lower the vehicle's health without a damage event; destroys it at zero (if configured). */
    public void hurt(Pig base, double amount) {
        if (amount <= 0 || base.isDead()) return;
        double h = base.getHealth() - amount;
        if (h <= 0.0) {
            if (plugin.getConfig().getBoolean("crash.destroy-at-zero-health", true)) destroy(base);
            else base.setHealth(1.0);
            return;
        }
        base.setHealth(h);
    }

    // ------------------------------------------------------------------ lookup

    public VehicleType typeOf(Pig base) {
        String id = base.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : plugin.types().get(id);
    }

    public String typeIdOf(Pig base) {
        return base.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    public UUID ownerOf(Pig base) {
        String s = base.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public static boolean isBase(Entity e) { return e instanceof Pig && e.getScoreboardTags().contains(TAG_BASE); }
    public static boolean isPart(Entity e) { return e.getScoreboardTags().contains(TAG_PART); }

    /** The vehicle any of its entities belongs to (base, body, seat or click box), else null. */
    public Pig baseOf(Entity any) {
        if (any == null) return null;
        if (isBase(any)) return (Pig) any;
        if (!isPart(any)) return null;
        String id = any.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(id));
            return isBase(e) ? (Pig) e : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    public List<Pig> all() {
        List<Pig> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (Pig p : w.getEntitiesByClass(Pig.class)) if (isBase(p)) out.add(p);
        return out;
    }

    public List<Player> occupants(Pig base) {
        List<Player> out = new ArrayList<>();
        for (Entity e : parts(base)) for (Entity p : e.getPassengers()) if (p instanceof Player pl) out.add(pl);
        return out;
    }

    /** All currently loaded part entities of a vehicle. */
    public List<Entity> parts(Pig base) {
        List<Entity> out = new ArrayList<>();
        Entity body = entity(base.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING));
        if (body != null) out.add(body);
        Entity anchor = entity(base.getPersistentDataContainer().get(anchorKey, PersistentDataType.STRING));
        if (anchor != null) out.add(anchor);
        for (String pair : seatList(base)) {
            String[] ids = pair.split(",");
            for (String id : ids) { Entity e = entity(id); if (e != null) out.add(e); }
        }
        return out;
    }

    public boolean references(Pig base, Entity part) {
        String id = part.getUniqueId().toString();
        if (id.equals(base.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING))) return true;
        if (id.equals(base.getPersistentDataContainer().get(anchorKey, PersistentDataType.STRING))) return true;
        for (String pair : seatList(base)) for (String s : pair.split(",")) if (s.equals(id)) return true;
        return false;
    }

    private static Entity entity(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        try { return Bukkit.getEntity(UUID.fromString(uuid)); } catch (IllegalArgumentException e) { return null; }
    }

    private List<String> seatList(Pig base) {
        String s = base.getPersistentDataContainer().get(seatsKey, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(";")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ parts

    public ItemDisplay body(Pig base) {
        Entity e = entity(base.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING));
        return e instanceof ItemDisplay d ? d : null;
    }

    /** Make sure the body and exactly type.seats.size() seats exist; respawn anything missing. */
    public void ensureParts(Pig base, VehicleType type) {
        // Vanilla never shoves an entity that carries a passenger (Entity.push checks isVehicle), so an
        // invisible marker stand rides the base: seated drivers inside the hitbox, bystanders and other
        // vehicles can no longer nudge a parked vehicle around. The base still pushes them.
        boolean hasAnchor = false;
        for (Entity p : base.getPassengers()) if (p.getScoreboardTags().contains(TAG_ANCHOR)) hasAnchor = true;
        if (!hasAnchor) {
            ArmorStand anchor = base.getWorld().spawn(base.getLocation(), ArmorStand.class, a -> {
                a.setInvisible(true);
                a.setMarker(true);
                a.setSmall(true);
                a.setGravity(false);
                a.setBasePlate(false);
                a.setInvulnerable(true);
                a.setPersistent(true);
                a.setSilent(true);
                a.addScoreboardTag(TAG_PART);
                a.addScoreboardTag(TAG_ANCHOR);
                a.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, base.getUniqueId().toString());
            });
            base.addPassenger(anchor);
            base.getPersistentDataContainer().set(anchorKey, PersistentDataType.STRING, anchor.getUniqueId().toString());
        }
        ItemDisplay body = body(base);
        if (body == null) {
            body = base.getWorld().spawn(base.getLocation(), ItemDisplay.class, d -> {
                d.addScoreboardTag(TAG_PART);
                d.addScoreboardTag(TAG_BODY);
                d.setPersistent(true);
                d.setBillboard(Display.Billboard.FIXED);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);   // composite parts carry their pose in display.fixed
                d.setTeleportDuration(1);
                d.setInterpolationDuration(0);
                d.setShadowRadius(0f);
                d.setViewRange(1.5f);
                d.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, base.getUniqueId().toString());
            });
            base.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, body.getUniqueId().toString());
            applyBody(body, type);
        }
        ensureTracker(base, type);
        List<String> list = seatList(base);
        boolean changed = false;
        int want = Math.max(1, type.seats.size());
        // extra seats go away (riders are set free)
        while (list.size() > want) {
            String[] ids = list.remove(list.size() - 1).split(",");
            for (String id : ids) { Entity e = entity(id); if (e != null) { e.eject(); e.remove(); } }
            changed = true;
        }
        for (int i = 0; i < want; i++) {
            String[] ids = i < list.size() ? list.get(i).split(",") : new String[0];
            Entity stand = ids.length > 0 ? entity(ids[0]) : null;
            Entity click = ids.length > 1 ? entity(ids[1]) : null;
            if (!(stand instanceof ArmorStand) || !(click instanceof Interaction)) {
                if (stand != null && !(stand instanceof ArmorStand)) stand = null;
                if (click != null && !(click instanceof Interaction)) click = null;
                final int index = i;
                if (stand == null) stand = base.getWorld().spawn(base.getLocation(), ArmorStand.class, s -> {
                    s.setInvisible(true);
                    s.setMarker(true);
                    s.setSmall(true);
                    s.setGravity(false);
                    s.setBasePlate(false);
                    s.setInvulnerable(true);
                    s.setPersistent(true);
                    s.setSilent(true);
                    s.addScoreboardTag(TAG_PART);
                    s.addScoreboardTag(TAG_SEAT);
                    s.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, base.getUniqueId().toString());
                    s.getPersistentDataContainer().set(seatKey, PersistentDataType.INTEGER, index);
                });
                if (click == null) click = base.getWorld().spawn(base.getLocation(), Interaction.class, c -> {
                    c.setResponsive(true);
                    c.setPersistent(true);
                    c.addScoreboardTag(TAG_PART);
                    c.addScoreboardTag(TAG_CLICK);
                    c.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, base.getUniqueId().toString());
                    c.getPersistentDataContainer().set(seatKey, PersistentDataType.INTEGER, index);
                });
                String pair = stand.getUniqueId() + "," + click.getUniqueId();
                if (i < list.size()) list.set(i, pair); else list.add(pair);
                changed = true;
            }
        }
        if (changed) base.getPersistentDataContainer().set(seatsKey, PersistentDataType.STRING, String.join(";", list));
    }

    /** Seats in index order (0 = driver). Missing parts are respawned first. */
    public List<SeatRef> seats(Pig base, VehicleType type) {
        ensureParts(base, type);
        List<SeatRef> out = new ArrayList<>();
        List<String> list = seatList(base);
        for (int i = 0; i < list.size(); i++) {
            String[] ids = list.get(i).split(",");
            Entity stand = ids.length > 0 ? entity(ids[0]) : null;
            Entity click = ids.length > 1 ? entity(ids[1]) : null;
            if (stand instanceof ArmorStand s && click instanceof Interaction c) out.add(new SeatRef(i, s, c));
        }
        return out;
    }

    public int seatIndex(Entity part) {
        return part.getPersistentDataContainer().getOrDefault(seatKey, PersistentDataType.INTEGER, -1);
    }

    /** Item + transformation of the body for a type (model fit scale compensated, Blockbench origin at the base). */
    public void applyBody(ItemDisplay d, VehicleType type) {
        if (plugin.bridge() != null) {
            d.setItemStack(new ItemStack(Material.AIR));   // BetterModel draws the body
            Pig base = entity(d.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING)) instanceof Pig p ? p : null;
            if (base != null) { closeTracker(base.getUniqueId()); ensureTracker(base, type); }
            return;
        }
        d.setItemStack(modelItem(type.model));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        PackGenerator.ModelInfo mi = plugin.modelInfo(type.model);
        double inv = mi != null ? 1.0 / mi.fitScale() : 1.0;
        double tx = mi != null ? mi.tx() : 0.5, ty = mi != null ? mi.ty() : 0.5, tz = mi != null ? mi.tz() : 0.5;
        float s = (float) (inv * type.scale);
        Vector3f translation = new Vector3f((float) (tx * type.scale + type.offsetX), (float) (ty * type.scale + type.offsetY), (float) (tz * type.scale + type.offsetZ));
        d.setTransformation(new Transformation(translation, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
    }

    /** Re-apply a type's settings to a live vehicle (after /vehicle edit). */
    public void applyType(Pig base, VehicleType type) {
        applyAttributes(base, type);
        ensureParts(base, type);
        ItemDisplay body = body(base);
        if (body != null) applyBody(body, type);
    }

    /** Per-tick placement of body, seats and click boxes around the base. */
    public void tickParts(Pig base, VehicleType type, float yaw) { tickParts(base, type, yaw, seats(base, type)); }

    public void tickParts(Pig base, VehicleType type, float yaw, List<SeatRef> seats) {
        Location at = base.getLocation();
        ItemDisplay body = body(base);
        if (body != null) {
            Location bl = at.clone();
            bl.setYaw((float) (yaw + type.modelYaw));
            bl.setPitch(0);
            body.teleport(bl);
        }
        double riderOffset = plugin.getConfig().getDouble("seat-rider-offset", 0.6);
        for (SeatRef seat : seats) {
            VehicleType.Seat s = seat.index() < type.seats.size() ? type.seats.get(seat.index()) : type.seats.get(type.seats.size() - 1);
            Location sl = seatLocation(at, type, s, yaw);
            Location standLoc = sl.clone().add(0, riderOffset, 0);
            standLoc.setYaw(yaw);
            if (seat.stand().getPassengers().isEmpty()) seat.stand().teleport(standLoc);
            else seat.stand().teleport(standLoc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            Interaction c = seat.click();
            float w = (float) Math.max(0.1, s.width), h = (float) Math.max(0.1, s.height);
            if (Math.abs(c.getInteractionWidth() - w) > 1e-3) c.setInteractionWidth(w);
            if (Math.abs(c.getInteractionHeight() - h) > 1e-3) c.setInteractionHeight(h);
            Location cl = sl.clone().add(0, 0.5 - h / 2.0, 0);
            c.teleport(cl);
        }
    }

    /** World position of a seat: model-space offset (scaled, nudged by the type offset) rotated with the body. */
    public Location seatLocation(Location base, VehicleType type, VehicleType.Seat s, float yaw) {
        double lx = s.x * type.scale + type.offsetX, ly = s.y * type.scale + type.offsetY, lz = s.z * type.scale + type.offsetZ;
        double rad = Math.toRadians(yaw + type.modelYaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double wx = lx * cos - lz * sin;
        double wz = lx * sin + lz * cos;
        return new Location(base.getWorld(), base.getX() + wx, base.getY() + ly, base.getZ() + wz, yaw, 0);
    }

    /** Remove parts whose vehicle no longer exists or no longer lists them. */
    public void sweepOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!isPart(e)) continue;
                Pig base = baseOf(e);
                if (base == null || !references(base, e)) {
                    e.eject();
                    e.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------ items

    /** The base item showing a model (what the body displays). Empty model = nothing. */
    public ItemStack modelItem(String model) {
        if (model == null || model.isEmpty()) return new ItemStack(Material.AIR);
        ItemStack item = new ItemStack(plugin.baseItem());
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** A placeable vehicle item (/vehicle give): right-click a block to spawn the vehicle. */
    public ItemStack vehicleItem(VehicleType type, int amount) {
        ItemStack item = new ItemStack(plugin.baseItem(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (type.model != null && !type.model.isEmpty()) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of(type.model));
            meta.setCustomModelDataComponent(cmd);
        }
        meta.displayName(Component.text(type.name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Vehicle: " + type.id, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Right-click a block to place it", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.id);
        item.setItemMeta(meta);
        return item;
    }

    public String itemTypeId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }
}
