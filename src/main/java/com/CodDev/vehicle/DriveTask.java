package com.CodDev.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The engine. Every tick each vehicle reads its driver's live WASD input (PlayerInputEvent) and turns it
 * into velocity: W throttles, S brakes then reverses, A/D steer (sharper with speed, mirrored in reverse),
 * space is the handbrake, sneak leaves the vehicle. Crashes are detected from the gap between the velocity
 * we asked for and the distance the body actually moved; ramming hurts whatever is in the way.
 */
public final class DriveTask implements Runnable {

    private static final class State {
        double speed;            // blocks per second, negative = reverse
        double animPhase;        // driving/idle animation clock (ticks, fractional: advances with speed)
        float animRate = 1f;     // BetterModel playback speed (1 = Blockbench speed)
        String bmAnim;           // BetterModel animation currently playing
        String lastModel;        // custom_model_data string the body currently shows
        float yaw;
        Vector momentum;         // last velocity set (blocks per tick)
        Location last;           // position at the end of the previous tick
        int crashCooldown;
        boolean wasOnGround = true;
        float maxFall;
    }

    private final VehiclePlugin plugin;
    private final VehicleManager manager;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Map<UUID, Integer> ramCooldown = new HashMap<>();
    private int tick;

    public DriveTask(VehiclePlugin plugin, VehicleManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void input(Player player, Input input) { inputs.put(player.getUniqueId(), input); }
    public void forget(UUID player) { inputs.remove(player); }
    public void forgetVehicle(UUID base) { states.remove(base); }

    public double speedOf(LivingEntity base) {
        State st = states.get(base.getUniqueId());
        return st == null ? 0 : st.speed;
    }

    public float yawOf(LivingEntity base) {
        State st = states.get(base.getUniqueId());
        return st == null ? base.getLocation().getYaw() : st.yaw;
    }

    @Override
    public void run() {
        tick++;
        for (LivingEntity base : manager.all()) {
            if (base.isDead()) continue;
            if (base instanceof org.bukkit.entity.Pig) { manager.migrateLegacy(base); continue; }   // pre-1.5.0 vehicle
            VehicleType type = manager.typeOf(base);
            if (type == null) continue;   // type deleted while the entity survived: /vehicle remove cleans it up
            try {
                tickVehicle(base, type);
            } catch (Exception e) {
                plugin.getLogger().warning("Vehicle tick failed for " + base.getUniqueId() + ": " + e);
            }
        }
        if (tick % 100 == 0) {
            manager.sweepOrphans();
            states.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
            Iterator<Map.Entry<UUID, Integer>> it = ramCooldown.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() < tick) it.remove();
        }
    }

    private void tickVehicle(LivingEntity base, VehicleType type) {
        State st = states.get(base.getUniqueId());
        if (st == null) {
            st = new State();
            st.yaw = base.getLocation().getYaw();
            st.momentum = new Vector();
            if (base instanceof org.bukkit.entity.Mob m) m.setAware(false);   // legacy pig bases only
            states.put(base.getUniqueId(), st);
        }
        List<VehicleManager.SeatRef> seats = manager.seats(base, type);
        Player driver = seats.isEmpty() ? null : seats.get(0).rider();
        Input in = driver != null ? inputs.get(driver.getUniqueId()) : null;

        Location at = base.getLocation();
        boolean onGround = base.isOnGround();
        boolean inWater = base.isInWater();
        World world = base.getWorld();

        // ---- crash: we asked for X blocks of movement, the body made far less of it
        if (st.crashCooldown > 0) st.crashCooldown--;
        double crashMin = plugin.getConfig().getDouble("crash.min-speed", 4.0);
        if (st.last != null && st.last.getWorld() == world && st.crashCooldown == 0) {
            double expected = Math.hypot(st.momentum.getX(), st.momentum.getZ());
            double actual = Math.hypot(at.getX() - st.last.getX(), at.getZ() - st.last.getZ());
            if (expected > 0.08 && actual < expected * 0.35 && Math.abs(st.speed) >= crashMin) {
                crash(base, type, st, seats, Math.abs(st.speed));
            }
        }

        // ---- falling
        if (!onGround) st.maxFall = Math.max(st.maxFall, base.getFallDistance());
        if (onGround && !st.wasOnGround) {
            double fd = st.maxFall;
            if (fd > 3.0 && type.crash) {
                double dmg = (fd - 3.0) * plugin.getConfig().getDouble("crash.fall-damage", 4.0) * type.crashMultiplier;
                world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.6f);
                world.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.5, 0.1, 0.5, 0.02);
                manager.hurt(base, dmg);
                if (base.isDead()) return;
            }
            st.maxFall = 0;
        }
        st.wasOnGround = onGround;

        // ---- throttle / brake / steering
        double speed = st.speed;
        float yaw = st.yaw;
        double accel = type.acceleration / 20.0, brake = type.brake / 20.0;
        double surface = inWater ? plugin.getConfig().getDouble("drive.water-speed-factor", 0.3) : 1.0;
        if (in != null) {
            boolean drive = onGround || inWater;
            if (in.isJump()) {
                speed *= 0.85;                                       // handbrake
            } else if (in.isForward() && drive) {
                speed += speed < -0.1 ? brake : accel * surface;
            } else if (in.isBackward() && drive) {
                speed -= speed > 0.1 ? brake : accel * 0.7 * surface;
            } else {
                speed *= drive ? type.friction : 0.995;
            }
            double steer = (in.isLeft() ? -1 : 0) + (in.isRight() ? 1 : 0);
            if (steer != 0 && Math.abs(speed) > 0.3) {
                double g = Math.min(1.0, Math.abs(speed) / Math.max(0.1, type.maxSpeed * 0.3));
                yaw += (float) (steer * type.turnRate * g * Math.signum(speed) * (drive ? 1.0 : 0.3));
            }
        } else {
            speed *= onGround ? 0.92 : 0.995;                        // nobody driving: roll to a stop
        }
        double max = type.maxSpeed * surface, min = -type.maxSpeed * type.reverseFactor * surface;
        speed = Math.max(min, Math.min(max, speed));
        if (Math.abs(speed) < 0.03) speed = 0;
        st.speed = speed;
        st.yaw = yaw;

        // ---- BetterModel renders + animates the body: just steer which animation plays and how fast
        ModelBridge bridge = plugin.bridge();
        if (bridge != null) {
            manager.ensureTracker(base, type);
            var tracker = manager.tracker(base);
            boolean moving = Math.abs(speed) > 0.3;
            String anim = moving ? type.driveAnimation : type.idleAnimation;
            double full = plugin.getConfig().getDouble("drive.animation-full-speed", 0);
            if (full <= 0) full = Math.max(0.1, type.maxSpeed);
            st.animRate = (float) (moving && plugin.getConfig().getBoolean("drive.animation-follows-speed", true) ? Math.max(0.15, Math.min(3.0, Math.abs(speed) / full)) : 1.0);
            if (tracker != null) {
                String want = anim == null || anim.isEmpty() || !bridge.hasAnimation(type.model, anim) ? null : anim;
                if (!java.util.Objects.equals(want, st.bmAnim)) {
                    if (st.bmAnim != null) bridge.stop(tracker, st.bmAnim);
                    final State fst = st;
                    if (want != null) bridge.animate(tracker, want, true, () -> fst.animRate);
                    st.bmAnim = want;
                }
            }
        } else
        // ---- animation frames baked from the .bbmodel (drive-animation while moving, idle-animation at rest)
        if (type.model != null && !type.model.isEmpty()) {
            boolean moving = Math.abs(speed) > 0.3;
            String anim = moving ? type.driveAnimation : type.idleAnimation;
            String want = type.model;
            if (anim != null && !anim.isEmpty()) {
                int[] a = plugin.animation(type.model, anim);
                if (a != null && a[0] > 0) {
                    // Playback rate: 1.0 = Blockbench speed, reached at `drive.animation-full-speed` blocks/s (0 = the
                    // type's max speed); below that the animation runs proportionally slower, above it faster.
                    double full = plugin.getConfig().getDouble("drive.animation-full-speed", 0);
                    if (full <= 0) full = Math.max(0.1, type.maxSpeed);
                    double rate = moving && plugin.getConfig().getBoolean("drive.animation-follows-speed", true)
                        ? Math.max(0.15, Math.min(3.0, Math.abs(speed) / full)) : 1.0;
                    st.animPhase += rate;
                    int frame = (int) (st.animPhase / Math.max(1, a[1])) % a[0] + 1;
                    want = type.model + "_" + anim + "_" + frame;
                }
            }
            if (!want.equals(st.lastModel)) {
                ItemDisplay body = manager.body(base);
                if (body != null) { body.setItemStack(manager.modelItem(want)); st.lastModel = want; }
            }
        }

        // ---- velocity: momentum chases the nose (grip)
        double rad = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        Vector desired = forward.clone().multiply(speed / 20.0);
        double grip = Math.max(0.05, Math.min(1.0, inWater ? type.grip * 0.5 : type.grip));
        Vector vel = st.momentum.clone().multiply(1.0 - grip).add(desired.multiply(grip));
        vel.setY(0);
        st.momentum = vel.clone();
        double vy = base.getVelocity().getY();
        vel.setY(Math.min(0.1, vy));
        base.setVelocity(vel);
        base.setRotation(yaw, 0);

        // ---- ramming things in the way
        if (type.crash && Math.abs(speed) >= 2.0) ram(base, type, st, seats, forward, driver);

        // ---- sound & dust
        if (Math.abs(speed) > 0.5 && tick % 6 == 0 && type.sound != null && !type.sound.isEmpty()) {
            float pitch = (float) (0.6 + Math.min(1.0, Math.abs(speed) / Math.max(0.1, type.maxSpeed)) * 0.9);
            try { world.playSound(at, type.sound, (float) type.soundVolume, pitch); } catch (Exception ignored) { }
        }
        if (Math.abs(speed) > 2.0 && tick % 3 == 0 && onGround) {
            Block ground = at.clone().subtract(0, 0.2, 0).getBlock();
            if (ground.isPassable()) ground = ground.getRelative(BlockFace.DOWN);
            if (!ground.isPassable()) world.spawnParticle(Particle.BLOCK,
                at.clone().add(forward.clone().multiply(-type.hitbox * 0.4)).add(0, 0.15, 0),
                3, type.hitbox * 0.3, 0.05, type.hitbox * 0.3, ground.getBlockData());
        }
        if (inWater && tick % 10 == 0 && Math.abs(speed) > 0.5) world.spawnParticle(Particle.SPLASH, at, 10, 0.5, 0.2, 0.5, 0);

        if (driver != null && plugin.getConfig().getBoolean("drive.speedometer", true)) hud(driver, base, type, Math.abs(speed));

        manager.tickParts(base, type, yaw, seats);
        st.last = base.getLocation();
    }

    private void crash(LivingEntity base, VehicleType type, State st, List<VehicleManager.SeatRef> seats, double impact) {
        World world = base.getWorld();
        Location at = base.getLocation().add(0, 0.5, 0);
        double crashMin = plugin.getConfig().getDouble("crash.min-speed", 4.0);
        st.crashCooldown = 10;
        st.speed = -st.speed * 0.1;
        st.momentum = new Vector();
        boolean hard = impact >= crashMin * 2.5;
        world.playSound(at, hard ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_ANVIL_LAND, hard ? 0.8f : 0.9f, hard ? 1.4f : 0.5f);
        world.playSound(at, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.6f);
        world.spawnParticle(hard ? Particle.EXPLOSION : Particle.CRIT, at, hard ? 1 : 20, 0.4, 0.3, 0.4, 0.1);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.5, 0.3, 0.5, 0.02);
        if (!type.crash) return;
        double vdmg = impact * plugin.getConfig().getDouble("crash.vehicle-damage", 1.5) * type.crashMultiplier;
        double rdmg = Math.max(0, impact - crashMin) * plugin.getConfig().getDouble("crash.rider-damage", 0.6) * type.crashMultiplier;
        for (VehicleManager.SeatRef seat : seats) {
            Player p = seat.rider();
            if (p == null || rdmg <= 0) continue;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            p.damage(rdmg);
            p.sendActionBar(Component.text(String.format(Locale.ROOT, "Crash! %.0f blocks/s", impact), NamedTextColor.RED));
        }
        manager.hurt(base, vdmg);
    }

    private void ram(LivingEntity base, VehicleType type, State st, List<VehicleManager.SeatRef> seats, Vector forward, Player driver) {
        BoundingBox box = base.getBoundingBox().expand(0.25);
        double speed = Math.abs(st.speed);
        for (Entity e : base.getWorld().getNearbyEntities(box, x -> x instanceof LivingEntity && x != base && !VehicleManager.isPart(x))) {
            if (e instanceof Player p && (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE)) continue;
            boolean occupant = false;
            for (VehicleManager.SeatRef s : seats) if (s.rider() == e) occupant = true;
            if (occupant) continue;
            Integer until = ramCooldown.get(e.getUniqueId());
            if (until != null && until > tick) continue;
            ramCooldown.put(e.getUniqueId(), tick + 20);
            if (VehicleManager.isBase(e)) {
                // vehicle vs vehicle: both get hurt by the impact, the other is shoved along
                VehicleType other = manager.typeOf((LivingEntity) e);
                double dmg = speed * plugin.getConfig().getDouble("crash.vehicle-damage", 1.5) * 0.5;
                e.setVelocity(forward.clone().multiply(speed / 20.0 * 0.8));
                manager.hurt((LivingEntity) e, other != null && other.crash ? dmg * other.crashMultiplier : 0);
                manager.hurt(base, dmg * type.crashMultiplier);
                base.getWorld().playSound(base.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 0.5f);
                st.speed *= 0.3;
                st.crashCooldown = 10;
                continue;
            }
            LivingEntity le = (LivingEntity) e;
            double dmg = speed * plugin.getConfig().getDouble("crash.ram-damage", 0.8) * type.ramMultiplier;
            if (driver != null) le.damage(dmg, driver); else le.damage(dmg, base);
            le.setVelocity(forward.clone().multiply(speed / 10.0).add(new Vector(0, 0.35, 0)));
            base.getWorld().playSound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
            st.speed *= 0.85;
        }
    }

    private void hud(Player driver, LivingEntity base, VehicleType type, double speed) {
        float hue = (float) (0.33 - 0.33 * Math.min(1.0, speed / Math.max(1.0, type.maxSpeed)));
        TextColor color = TextColor.color(java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f));
        double hp = Math.max(0, base.getHealth()), max = manager.maxHealth(base);
        Component line = Component.text(String.format(Locale.ROOT, "%.1f blocks/s", speed), color)
            .append(Component.text("   ", NamedTextColor.GRAY))
            .append(Component.text(String.format(Locale.ROOT, "❤ %.0f/%.0f", hp, max), hp / max > 0.5 ? NamedTextColor.GREEN : hp / max > 0.25 ? NamedTextColor.YELLOW : NamedTextColor.RED));
        driver.sendActionBar(line);
    }
}
