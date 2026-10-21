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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
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
 * A vehicle is an invisible ArmorStand (the physics body: gravity, block collision, step height, a real hitbox
 * via the SCALE attribute, and a real health pool) plus separate entities that are moved along every tick:
 * an ItemDisplay showing the Blockbench model, and per seat an invisible marker ArmorStand (the rider sits
 * on it) and an Interaction entity (the click box that lets players enter from a distance).
 */
public final class VehicleManager {

    /** Tilt applied by seatLocation during tickParts (0 elsewhere, e.g. when seats are placed at spawn). */
    private double seatPitch, seatRoll;

    /** RP Engine model handles per vehicle base (only used when RP Engine renders the bodies). */
    private final java.util.Map<java.util.UUID, ModelBridge.Handle> trackers = new java.util.HashMap<>();

    /** Make sure the RP Engine model is worn by this vehicle's base (no-op when RP Engine is not active). */
    public void ensureTracker(LivingEntity base, VehicleType type) {
        ModelBridge bridge = plugin.bridge();
        if (bridge == null) return;
        var cur = trackers.get(base.getUniqueId());
        if (cur != null && !cur.isClosed()) return;
        if (!bridge.has(type.model)) return;
        // RP Engine draws the worn model where the base faces; DriveTask turns the base by yaw + model-yaw - 180 + offset
        var t = bridge.attach(base, type.model, null, type.scale);
        if (t != null) plugin.getLogger().info("RP Engine: vehicle " + type.id + " wears " + bridge.contentId(type.model) + " (scale " + type.scale + ")");
        if (t == null) return;
        trackers.put(base.getUniqueId(), t);
        // a vehicle that existed before BetterModel still carries the item model on its body: hide it, BetterModel draws
        ItemDisplay body = body(base);
        if (body != null && body.getItemStack().getType() != Material.AIR) body.setItemStack(new ItemStack(Material.AIR));
    }

    public ModelBridge.Handle tracker(LivingEntity base) { return plugin.bridge() == null ? null : trackers.get(base.getUniqueId()); }

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

    public LivingEntity spawn(VehicleType type, Location at, UUID owner) {
        Location loc = at.clone();
        loc.setPitch(0);
        // The base is an invisible ARMOR STAND: it has gravity, block collision, a step height and a real hitbox like a
        // mob, but no AI, no sounds, no breeding/leashing/targeting, and it can be made invulnerable (config invincible).
        ArmorStand base = loc.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setSilent(true);
            a.setPersistent(true);
            a.setMarker(false);          // marker stands have no physics - the car must collide with blocks
            a.setGravity(true);
            a.setBasePlate(false);
            a.setArms(false);
            a.setSmall(false);
            a.setCanPickupItems(false);
            a.setDisabledSlots(EquipmentSlot.values());   // nobody can dress the car
            a.setInvulnerable(invincible());
            a.customName(Component.text(type.name));
            a.setCustomNameVisible(false);
            a.addScoreboardTag(TAG_BASE);
            a.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id);
            if (owner != null) a.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
        applyAttributes(base, type);
        base.setHealth(Math.min(base.getHealth(), maxHealth(base)));
        base.setHealth(maxHealth(base));
        ensureParts(base, type);
        tickParts(base, type, loc.getYaw());
        return base;
    }

    /** Config `invincible` (default true): the vehicle takes no damage at all - not from players, bullets, explosions,
     *  fire, crashes or ramming. false = the old health pool (crash damage, destroyed at zero health). */
    public boolean invincible() { return plugin.getConfig().getBoolean("invincible", false); }

    /** The SCALE attribute that gives the base its `hitbox`: an armor stand is 0.5 x 1.975 blocks (4:1), so the hitbox
     *  value is its HEIGHT and the width comes out at about a quarter of it (a pig was 0.9 x 0.9, hitbox = both). */
    public static double baseScale(double hitbox, boolean armorStand) {
        return Math.max(0.1, Math.min(16.0, hitbox / (armorStand ? 1.975 : 0.9)));
    }

    public void applyAttributes(LivingEntity base, VehicleType type) {
        double scale = baseScale(type.hitbox, base instanceof ArmorStand);
        set(base, Attribute.SCALE, scale);
        base.setInvulnerable(invincible());
        set(base, Attribute.STEP_HEIGHT, Math.max(0.0, Math.min(10.0, type.stepHeight)));
        set(base, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        double hp = Math.max(1.0, Math.min(1024.0, type.health));
        set(base, Attribute.MAX_HEALTH, hp);
        if (base.getHealth() > hp) base.setHealth(hp);
    }

    private static void set(LivingEntity base, Attribute attribute, double value) {
        var inst = base.getAttribute(attribute);
        if (inst == null) base.registerAttribute(attribute);
        inst = base.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    public double maxHealth(LivingEntity base) {
        var inst = base.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20.0 : inst.getValue();
    }

    /** Remove a vehicle and all of its parts; riders are set free first. */
    public void remove(LivingEntity base) {
        closeTracker(base.getUniqueId());
        forced(() -> {
            for (Entity e : parts(base)) {
                e.eject();
                e.remove();
            }
            for (Entity p : new ArrayList<>(base.getPassengers())) if (isPart(p)) p.remove();
            base.eject();
        });
        base.remove();
        if (plugin.task() != null) plugin.task().forgetVehicle(base.getUniqueId());
    }

    /** Dismounts the plugin performs itself (removal, destruction) must never be refused by the helicopter rules. */
    private void forced(Runnable r) { if (plugin.task() != null) plugin.task().forced(r); else r.run(); }

    /** Boom: effects, then removal. */
    public void destroy(LivingEntity base) {
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
    public void hurt(LivingEntity base, double amount) {
        if (amount <= 0 || base.isDead() || invincible()) return;
        double h = base.getHealth() - amount;
        if (h <= 0.0) {
            if (plugin.getConfig().getBoolean("crash.destroy-at-zero-health", true)) destroy(base);
            else base.setHealth(1.0);
            return;
        }
        base.setHealth(h);
    }

    /** Vehicles from before 1.5.0 are invisible pigs: rebuild each one as an armor-stand vehicle of the same type, owner,
     *  position and heading (riders are put down). Returns the new base, or the old one if it is not a legacy pig. */
    public LivingEntity migrateLegacy(LivingEntity base) {
        if (!(base instanceof org.bukkit.entity.Pig)) return base;
        VehicleType type = typeOf(base);
        if (type == null) { remove(base); return null; }
        UUID owner = ownerOf(base);
        Location loc = base.getLocation();
        remove(base);
        LivingEntity fresh = spawn(type, loc, owner);
        plugin.getLogger().info("Migrated vehicle " + type.id + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " from a pig base to an armor stand.");
        return fresh;
    }

    // ------------------------------------------------------------------ lookup

    public VehicleType typeOf(LivingEntity base) {
        String id = base.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : plugin.types().get(id);
    }

    public String typeIdOf(LivingEntity base) {
        return base.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    public UUID ownerOf(LivingEntity base) {
        String s = base.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public static boolean isBase(Entity e) { return e instanceof LivingEntity && e.getScoreboardTags().contains(TAG_BASE); }
    public static boolean isPart(Entity e) { return e.getScoreboardTags().contains(TAG_PART); }

    /** The vehicle any of its entities belongs to (base, body, seat or click box), else null. */
    public LivingEntity baseOf(Entity any) {
        if (any == null) return null;
        if (isBase(any)) return (LivingEntity) any;
        if (!isPart(any)) return null;
        String id = any.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(id));
            return isBase(e) ? (LivingEntity) e : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    public List<LivingEntity> all() {
        List<LivingEntity> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (LivingEntity p : w.getEntitiesByClass(LivingEntity.class)) if (isBase(p)) out.add(p);
        return out;
    }

    public List<Player> occupants(LivingEntity base) {
        List<Player> out = new ArrayList<>();
        for (Entity e : parts(base)) for (Entity p : e.getPassengers()) if (p instanceof Player pl) out.add(pl);
        return out;
    }

    /** All currently loaded part entities of a vehicle. */
    public List<Entity> parts(LivingEntity base) {
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

    public boolean references(LivingEntity base, Entity part) {
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

    private List<String> seatList(LivingEntity base) {
        String s = base.getPersistentDataContainer().get(seatsKey, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(";")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ parts

    public ItemDisplay body(LivingEntity base) {
        Entity e = entity(base.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING));
        return e instanceof ItemDisplay d ? d : null;
    }

    /** Make sure the body and exactly type.seats.size() seats exist; respawn anything missing. */
    public void ensureParts(LivingEntity base, VehicleType type) {
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
            forced(() -> { for (String id : ids) { Entity e = entity(id); if (e != null) { e.eject(); e.remove(); } } });
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
    public List<SeatRef> seats(LivingEntity base, VehicleType type) {
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
            d.setItemStack(new ItemStack(Material.AIR));   // RP Engine draws the body
            LivingEntity base = entity(d.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING)) instanceof LivingEntity p ? p : null;
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
    public void applyType(LivingEntity base, VehicleType type) {
        applyAttributes(base, type);
        ensureParts(base, type);
        ItemDisplay body = body(base);
        if (body != null) applyBody(body, type);
    }

    /** Per-tick placement of body, seats and click boxes around the base. */
    public void tickParts(LivingEntity base, VehicleType type, float yaw) { tickParts(base, type, yaw, seats(base, type)); }

    public void tickParts(LivingEntity base, VehicleType type, float yaw, List<SeatRef> seats) {
        Location at = base.getLocation();
        ItemDisplay body = body(base);
        double pitch = plugin.task().pitchOf(base), roll = plugin.task().rollOf(base);
        if (body != null) {
            Location bl = at.clone();
            bl.setYaw((float) (yaw + type.modelYaw));
            bl.setPitch(0);
            body.teleport(bl);
            if (plugin.bridge() == null) applyTilt(body, type, pitch, roll);
        }
        double riderOffset = plugin.getConfig().getDouble("seat-rider-offset", 0.6);
        seatPitch = pitch; seatRoll = roll;
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
        seatPitch = 0; seatRoll = 0;
    }

    /** The body's rest transformation (fit scale + Blockbench origin at the base) with the tilt applied around the
     *  body centre: display = translation + L * (scale * v), so L = Ry(-modelYaw) Rx(pitch) Rz(roll) Ry(modelYaw) in
     *  the display's own frame and translation' = c - L c + L t_fit with c = the centre (half the hitbox up). */
    private void applyTilt(ItemDisplay d, VehicleType type, double pitch, double roll) {
        Transformation cur = d.getTransformation();
        Vector3f tfit = restTranslation(type);
        float ps = (float) plugin.getConfig().getDouble("physics.pitch-sign", 1), rs = (float) plugin.getConfig().getDouble("physics.roll-sign", 1);
        float my = (float) Math.toRadians(type.modelYaw);
        Quaternionf L = new Quaternionf().rotateY(-my).rotateX((float) Math.toRadians(pitch) * ps).rotateZ((float) Math.toRadians(roll) * rs).rotateY(my);
        Vector3f c = new Vector3f(0f, (float) (type.hitbox * 0.5), 0f);
        Vector3f t = new Vector3f(c).sub(L.transform(new Vector3f(c))).add(L.transform(new Vector3f(tfit)));
        if (Math.abs(pitch) < 0.01 && Math.abs(roll) < 0.01) { t = tfit; L = new Quaternionf(); }
        if (!cur.getLeftRotation().equals(L, 1e-4f) || cur.getTranslation().distance(t) > 1e-4) d.setTransformation(new Transformation(t, L, cur.getScale(), new Quaternionf()));
    }

    private Vector3f restTranslation(VehicleType type) {
        PackGenerator.ModelInfo mi = plugin.modelInfo(type.model);
        double tx = mi != null ? mi.tx() : 0.5, ty = mi != null ? mi.ty() : 0.5, tz = mi != null ? mi.tz() : 0.5;
        return new Vector3f((float) (tx * type.scale + type.offsetX), (float) (ty * type.scale + type.offsetY), (float) (tz * type.scale + type.offsetZ));
    }

    /** Seat offset (right, up, forward in the vehicle's frame) tilted with the body around its centre: {dx, dy, dz} world. */
    static double[] tiltedOffset(double right, double up, double forward, double pitchDeg, double rollDeg, double centreY) {
        double p = Math.toRadians(pitchDeg), r = Math.toRadians(rollDeg);
        double y = up - centreY;
        // roll about the forward axis (right/up), then pitch about the right axis (up/forward)
        double x1 = right * Math.cos(r) - y * Math.sin(r), y1 = right * Math.sin(r) + y * Math.cos(r);
        double y2 = y1 * Math.cos(p) + forward * Math.sin(p), z2 = -y1 * Math.sin(p) + forward * Math.cos(p);
        return new double[]{x1, y2 + centreY, z2};
    }

    /** World position of a seat: model-space offset (scaled, nudged by the type offset) rotated with the body. */
    public Location seatLocation(Location base, VehicleType type, VehicleType.Seat s, float yaw) {
        double lx = s.x * type.scale + type.offsetX, ly = s.y * type.scale + type.offsetY, lz = s.z * type.scale + type.offsetZ;
        double pitch = seatPitch, roll = seatRoll;
        if (pitch != 0 || roll != 0) { double[] o = tiltedOffset(lx, ly, -lz, pitch, roll, type.hitbox * 0.5); lx = o[0]; ly = o[1]; lz = -o[2]; }
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
                LivingEntity base = baseOf(e);
                if (base == null || !references(base, e)) {
                    forced(() -> { e.eject(); e.remove(); });
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
