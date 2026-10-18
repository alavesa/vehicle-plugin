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
 * into velocity.
 *
 * Cars: W throttles, S brakes then reverses, A/D steer (sharper with speed, mirrored in reverse), space is the
 * handbrake, sneak leaves the vehicle.
 *
 * Helicopters: W climbs, S descends, A/D turn (on the spot when hovering), space accelerates, sneak slows down and
 * then flies backwards. The first W or space starts the engine; the rotor spools up for helicopter.spool-time
 * seconds before there is lift. Sneak only gets a rider out on the ground; a double tap bails out mid-air.
 *
 * Crashes are detected from the gap between the velocity we asked for and the distance the body actually moved;
 * ramming hurts whatever is in the way. Both kinds share the tilt/flip physics.
 */
public final class DriveTask implements Runnable {

    private static final class State {
        double speed;            // blocks per second, negative = reverse
        double animPhase;        // driving/idle animation clock (ticks, fractional: advances with speed)
        float animRate = 1f;     // BetterModel playback speed (1 = Blockbench speed)
        String bmAnim;           // BetterModel animation currently playing
        String lastModel;        // custom_model_data string the body currently shows
        float yaw;
        double steer;            // -1 left, 0 straight, 1 right (driver input this tick)
        double pitch, roll;      // body tilt in degrees: pitch > 0 nose up, roll > 0 right side up (left side down)
        double wRoll, wPitch;    // angular velocity (deg/tick) while airborne / flipping
        boolean flipped;         // lying on its roof
        int flipAge;
        Vector momentum;         // last velocity set (blocks per tick)
        Location last;           // position at the end of the previous tick
        int crashCooldown;
        boolean wasOnGround = true;
        float maxFall;
        // helicopter
        boolean engine;          // rotor running
        double spool;            // 0 = rotor still, 1 = full speed (lift available)
        double vy;               // vertical velocity we set this tick (blocks per tick)
        double lastVy;           // vertical velocity set the previous tick (touch-down speed)
        int vert;                // -1 descending, 0 hovering, 1 climbing (input this tick)
        int idleTicks;           // parked on the ground with no input (engine auto-stop)
    }

    /** Sneak presses per player, for the double-tap bail-out of helicopters. */
    private static final class Sneak {
        int lastPress = Integer.MIN_VALUE / 2;
        int bailUntil = -1;
        int hintAt = -100;
    }

    private final VehiclePlugin plugin;
    private final VehicleManager manager;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Map<UUID, Sneak> sneaks = new HashMap<>();
    private final Map<UUID, Integer> ramCooldown = new HashMap<>();
    private int tick;
    private boolean forced;   // a dismount started by the plugin itself (removal, flip, quit) is in progress: always allowed

    public DriveTask(VehiclePlugin plugin, VehicleManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void input(Player player, Input input) {
        Input prev = inputs.put(player.getUniqueId(), input);
        boolean was = prev != null && prev.isSneak();
        if (input.isSneak() && !was) {
            Sneak s = sneaks.computeIfAbsent(player.getUniqueId(), k -> new Sneak());
            if (bailOut(tick, s.lastPress, plugin.getConfig().getInt("helicopter.bail-out-ticks", 8))) s.bailUntil = tick + 3;
            s.lastPress = tick;
        }
    }

    /** Two sneak presses within `window` ticks = bail out. */
    static boolean bailOut(int now, int lastPress, int window) { return now - lastPress <= Math.max(1, window); }

    public void forget(UUID player) { inputs.remove(player); sneaks.remove(player); }
    public void forgetVehicle(UUID base) { states.remove(base); }

    /** Run something that dismounts riders (removal, flip, quit): those dismounts are never refused. */
    public void forced(Runnable r) {
        boolean old = forced;
        forced = true;
        try { r.run(); } finally { forced = old; }
    }

    /** May this rider leave right now? Cars: always. Helicopters: when landed, when the engine is off on the ground, when
     *  bailing out (double-tap sneak), or when the plugin itself is taking the rider out. */
    public boolean mayLeave(Player p, LivingEntity base, VehicleType type) {
        if (forced || type == null || !type.helicopter) return true;
        if (p.isDead() || !base.isValid() || base.isDead()) return true;
        State st = states.get(base.getUniqueId());
        if (st == null) return true;
        boolean grounded = grounded(base, st);
        if (grounded && Math.abs(st.speed) < 1.5) return true;
        if (!st.engine && grounded) return true;
        Sneak s = sneaks.get(p.getUniqueId());
        if (s != null && tick <= s.bailUntil) return true;
        if (s == null) s = sneaks.computeIfAbsent(p.getUniqueId(), k -> new Sneak());
        if (tick - s.hintAt > 40) {
            s.hintAt = tick;
            p.sendActionBar(Component.text("Land first (hold S) - or double-tap sneak to bail out.", NamedTextColor.YELLOW));
        }
        return false;
    }

    public boolean engineOn(LivingEntity base) { State st = states.get(base.getUniqueId()); return st != null && st.engine; }

    public double speedOf(LivingEntity base) {
        State st = states.get(base.getUniqueId());
        return st == null ? 0 : st.speed;
    }

    public double pitchOf(LivingEntity base) { State st = states.get(base.getUniqueId()); return st == null ? 0 : st.pitch; }
    public double rollOf(LivingEntity base) { State st = states.get(base.getUniqueId()); return st == null ? 0 : st.roll; }
    public boolean isFlipped(LivingEntity base) { State st = states.get(base.getUniqueId()); return st != null && st.flipped; }
    /** Put a vehicle that lies on its roof back on its wheels (or skids). */
    public void unflip(LivingEntity base) {
        State st = states.get(base.getUniqueId());
        if (st == null) return;
        st.flipped = false; st.roll = 0; st.pitch = 0; st.wRoll = 0; st.wPitch = 0; st.flipAge = 0;
        st.engine = false; st.spool = 0; st.vy = 0;
        base.setVelocity(new Vector(0, 0.3, 0));
    }

    public float yawOf(LivingEntity base) {
        State st = states.get(base.getUniqueId());
        return st == null ? base.getLocation().getYaw() : st.yaw;
    }

    private double cfg(String key, double def) { return plugin.getConfig().getDouble(key, def); }

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

    /** The driving state for the animation table: idle, drive, reverse, left, right, reverse_left, reverse_right. */
    public static String driveState(double speed, double steer) {
        if (Math.abs(speed) <= 0.3) return "idle";
        boolean rev = speed < 0;
        if (steer < 0) return rev ? "reverse_left" : "left";
        if (steer > 0) return rev ? "reverse_right" : "right";
        return rev ? "reverse" : "drive";
    }

    /** The helicopter's state: idle (engine off), start (spooling up), ascend, descend, hover, or the car states while
     *  flying along (left/right also while turning on the spot). */
    public static String heliState(boolean engine, double spool, int vert, double speed, double steer) {
        if (!engine) return "idle";
        if (spool < 1.0) return "start";
        if (vert > 0) return "ascend";
        if (vert < 0) return "descend";
        if (Math.abs(speed) <= 0.3) return steer < 0 ? "left" : steer > 0 ? "right" : "hover";
        return driveState(speed, steer);
    }

    /** Vertical velocity (blocks per tick) a helicopter wants: NaN = gravity rules (engine off or still spooling), climb,
     *  descend, or hover. Hovering on the ground presses down a little so the entity keeps counting as on the ground;
     *  at the altitude ceiling climbing turns into hovering. */
    static double verticalTarget(boolean engine, double spool, int vert, double lift, double descent, boolean grounded, double y, double maxAlt) {
        if (!engine || spool < 1.0) return Double.NaN;
        if (vert > 0) return y >= maxAlt ? 0 : lift / 20.0;
        if (vert < 0) return -descent / 20.0;
        return grounded ? -0.05 : 0;
    }

    /** Rotor animation speed: the rotor follows the spool, a little faster at speed; 1 = Blockbench speed. */
    static double rotorRate(boolean engine, double spool, double speed, double maxSpeed) {
        if (!engine && spool <= 0) return 1.0;
        return Math.max(0.15, spool) * (1.0 + 0.25 * Math.min(1.0, Math.abs(speed) / Math.max(0.1, maxSpeed)));
    }

    /** Resting on something: vanilla onGround, or hovering within a few centimetres of a surface (vanilla needs a downward move
     *  to set onGround, a hovering helicopter does not make one). */
    private static boolean grounded(LivingEntity base, State st) {
        if (base.isOnGround() || base.isInWater()) return true;
        Location at = base.getLocation();
        double g = groundAt(at.getWorld(), at.getX(), at.getY(), at.getZ());
        return !Double.isNaN(g) && at.getY() - g <= 0.08 && st.vy <= 0.001;
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
        boolean heli = type.helicopter;
        boolean grounded = heli ? grounded(base, st) : onGround;

        // ---- crash: we asked for X blocks of movement, the body made far less of it
        if (st.crashCooldown > 0) st.crashCooldown--;
        double crashMin = cfg("crash.min-speed", 4.0);
        if (st.last != null && st.last.getWorld() == world && st.crashCooldown == 0) {
            double expected = Math.hypot(st.momentum.getX(), st.momentum.getZ());
            double actual = Math.hypot(at.getX() - st.last.getX(), at.getZ() - st.last.getZ());
            if (expected > 0.08 && actual < expected * 0.35 && Math.abs(st.speed) >= crashMin) {
                crash(base, type, st, seats, Math.abs(st.speed));
            }
            // helicopter into a ceiling: the climb stops
            if (heli && st.lastVy > 0.05 && (at.getY() - st.last.getY()) < st.lastVy * 0.3) {
                st.vy = 0;
                if (st.lastVy * 20 >= crashMin) world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f);
            }
        }

        // ---- falling / landing
        if (heli && st.engine) {
            base.setFallDistance(0);   // powered flight is not a fall
            st.maxFall = 0;
            if (onGround && !st.wasOnGround && type.crash) hardLanding(base, type, st, seats, -st.lastVy * 20.0);
        } else {
            if (!onGround) st.maxFall = Math.max(st.maxFall, base.getFallDistance());
            if (onGround && !st.wasOnGround) {
                double fd = st.maxFall;
                if (fd > 3.0 && type.crash) {
                    double dmg = (fd - 3.0) * cfg("crash.fall-damage", 4.0) * type.crashMultiplier;
                    world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.6f);
                    world.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.5, 0.1, 0.5, 0.02);
                    manager.hurt(base, dmg);
                    if (base.isDead()) return;
                }
                st.maxFall = 0;
            }
        }
        st.wasOnGround = onGround;

        double vyNow = base.getVelocity().getY();
        if (heli) fly(base, type, st, in, driver, grounded, inWater, at, vyNow);
        else drive(type, st, in, onGround, inWater);
        double speed = st.speed;
        float yaw = st.yaw;

        // ---- BetterModel renders + animates the body: just steer which animation plays and how fast
        String stateName = heli ? heliState(st.engine, st.spool, st.vert, speed, st.steer) : driveState(speed, st.steer);
        double full = cfg("drive.animation-full-speed", 0);
        if (full <= 0) full = Math.max(0.1, type.maxSpeed);
        boolean moving = Math.abs(speed) > 0.3;
        double rate = heli ? rotorRate(st.engine, st.spool, speed, type.maxSpeed)
            : moving && plugin.getConfig().getBoolean("drive.animation-follows-speed", true) ? Math.max(0.15, Math.min(3.0, Math.abs(speed) / full)) : 1.0;
        ModelBridge bridge = plugin.bridge();
        if (bridge != null) {
            manager.ensureTracker(base, type);
            var tracker = manager.tracker(base);
            String anim = type.animationFor(stateName);
            st.animRate = (float) rate;
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
        // ---- animation frames baked from the .bbmodel
        if (type.model != null && !type.model.isEmpty()) {
            String anim = type.animationFor(stateName);
            String want = type.model;
            if (anim != null && !anim.isEmpty()) {
                int[] a = plugin.animation(type.model, anim);
                if (a != null && a[0] > 0) {
                    // Playback rate: 1.0 = Blockbench speed, reached at `drive.animation-full-speed` blocks/s (0 = the
                    // type's max speed); below that the animation runs proportionally slower, above it faster.
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
        double grip = heli && !grounded ? cfg("helicopter.air-grip", 0.08) : inWater ? type.grip * 0.5 : type.grip;
        grip = Math.max(0.02, Math.min(1.0, grip));
        Vector vel = st.momentum.clone().multiply(1.0 - grip).add(desired.multiply(grip));
        vel.setY(0);
        st.momentum = vel.clone();
        if (heli) vel.setY(st.vy); else vel.setY(Math.min(0.1, vyNow));
        base.setVelocity(vel);
        base.setRotation(yaw, 0);
        st.lastVy = heli ? st.vy : 0;

        // ---- body orientation: four corner probes find the ground plane, the body follows it; airborne it keeps
        //      turning with its angular velocity (a helicopter leans into its flight); a hard sideways crash or a bad
        //      landing rolls it onto its roof
        if (plugin.getConfig().getBoolean("physics.enabled", true)) tilt(base, type, st, seats, at, yaw, grounded, vyNow);

        // ---- ramming things in the way
        if (type.crash && Math.abs(speed) >= 2.0) ram(base, type, st, seats, forward, driver);

        // ---- sound & dust
        if (heli) {
            if ((st.engine || st.spool > 0.02) && tick % 4 == 0 && type.sound != null && !type.sound.isEmpty()) {
                float pitch = (float) (0.6 + 1.4 * st.spool);
                try { world.playSound(at, type.sound, (float) (type.soundVolume * (0.4 + 0.6 * st.spool)), pitch); } catch (Exception ignored) { }
            }
            if (st.engine && st.spool > 0.5 && tick % 2 == 0 && plugin.getConfig().getBoolean("helicopter.rotor-wash", true)) {
                double g = groundAt(world, at.getX(), at.getY(), at.getZ());
                if (!Double.isNaN(g) && at.getY() - g < 3.5) {
                    Block ground = new Location(world, at.getX(), g - 0.1, at.getZ()).getBlock();
                    if (!ground.isPassable()) world.spawnParticle(Particle.BLOCK, new Location(world, at.getX(), g + 0.1, at.getZ()),
                        4, type.hitbox * 0.8, 0.05, type.hitbox * 0.8, ground.getBlockData());
                }
            }
        } else {
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
        }
        if (inWater && tick % 10 == 0 && Math.abs(speed) > 0.5) world.spawnParticle(Particle.SPLASH, at, 10, 0.5, 0.2, 0.5, 0);

        if (driver != null && plugin.getConfig().getBoolean("drive.speedometer", true)) hud(driver, base, type, st);

        manager.tickParts(base, type, yaw, seats);
        st.last = base.getLocation();
    }

    // ------------------------------------------------------------------ controls

    /** Car controls: W/S throttle and brake, A/D steer, space handbrake. */
    private void drive(VehicleType type, State st, Input in, boolean onGround, boolean inWater) {
        double speed = st.speed;
        float yaw = st.yaw;
        double accel = type.acceleration / 20.0, brake = type.brake / 20.0;
        double surface = inWater ? cfg("drive.water-speed-factor", 0.3) : 1.0;
        if (in != null && !st.flipped) {
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
            st.steer = steer;
            if (steer != 0 && Math.abs(speed) > 0.3) {
                double g = Math.min(1.0, Math.abs(speed) / Math.max(0.1, type.maxSpeed * 0.3));
                yaw += (float) (steer * type.turnRate * g * Math.signum(speed) * (drive ? 1.0 : 0.3));
            }
        } else {
            st.steer = 0;
            speed *= onGround ? 0.92 : 0.995;                        // nobody driving: roll to a stop
        }
        double max = type.maxSpeed * surface, min = -type.maxSpeed * type.reverseFactor * surface;
        speed = Math.max(min, Math.min(max, speed));
        if (Math.abs(speed) < 0.03) speed = 0;
        st.speed = speed;
        st.yaw = yaw;
        st.vert = 0;
    }

    /** Helicopter controls: W/S climb and descend, A/D turn, space accelerates, sneak slows down and reverses. */
    private void fly(LivingEntity base, VehicleType type, State st, Input in, Player driver, boolean grounded, boolean inWater, Location at, double vyNow) {
        World world = base.getWorld();
        double spoolTicks = Math.max(1.0, cfg("helicopter.spool-time", 1.5) * 20.0);
        double speed = st.speed;
        float yaw = st.yaw;
        int vert = 0;
        double steer = 0;
        boolean any = false;
        if (in != null && !st.flipped) {
            any = in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump() || in.isSneak();
            if (!st.engine && (in.isForward() || in.isJump())) {
                st.engine = true;
                st.idleTicks = 0;
                world.playSound(at, Sound.BLOCK_PISTON_EXTEND, 0.8f, 0.5f);
            }
        }
        if (st.flipped) st.engine = false;
        if (st.engine && driver == null && plugin.getConfig().getBoolean("helicopter.engine-off-when-empty", true)) st.engine = false;
        st.spool = st.engine ? Math.min(1.0, st.spool + 1.0 / spoolTicks) : Math.max(0.0, st.spool - 0.5 / spoolTicks);   // winds down slower
        boolean live = st.engine && st.spool >= 1.0 && in != null && !st.flipped;
        double accel = type.acceleration / 20.0, brake = type.brake / 20.0;
        double airFriction = cfg("helicopter.air-friction", 0.995);
        if (live) {
            if (in.isJump()) speed += speed < -0.1 ? brake : accel;
            else if (in.isSneak()) speed -= speed > 0.1 ? brake : accel * 0.5;
            else speed *= grounded ? type.friction : airFriction;
            steer = (in.isLeft() ? -1 : 0) + (in.isRight() ? 1 : 0);
            if (steer != 0) yaw += (float) (steer * type.turnRate * (grounded ? 0.5 : 1.0));   // hovering helicopters turn on the spot
            vert = (in.isForward() ? 1 : 0) + (in.isBackward() ? -1 : 0);
        } else {
            speed *= grounded ? 0.92 : airFriction;
        }
        st.steer = steer;
        st.vert = vert;
        double max = type.maxSpeed, min = -type.maxSpeed * type.reverseFactor;
        speed = Math.max(min, Math.min(max, speed));
        if (Math.abs(speed) < 0.03) speed = 0;
        if (grounded && !st.engine) speed *= 0.8;   // parked: no rolling about
        st.speed = speed;
        st.yaw = yaw;

        // vertical
        double maxAlt = cfg("helicopter.max-altitude", 0);
        if (maxAlt <= 0) maxAlt = world.getMaxHeight() - 2;
        double target = verticalTarget(st.engine, st.spool, vert, type.lift, type.descent, grounded, at.getY(), maxAlt);
        if (Double.isNaN(target)) st.vy = Math.min(0.1, vyNow);             // gravity: parked, winding down or falling
        else if (grounded && target <= 0) st.vy = target;                    // sitting on the ground
        else st.vy = approach(st.vy, target, 0.2);
        if (inWater && st.engine && st.vy < 0) st.vy *= 0.3;

        // engine auto-stop when parked and idle
        double stop = cfg("helicopter.idle-stop", 6.0) * 20.0;
        if (st.engine && grounded && !any) {
            st.idleTicks++;
            if (stop > 0 && st.idleTicks >= stop) { st.engine = false; st.idleTicks = 0; }
        } else st.idleTicks = 0;
    }

    /** Touching down faster than helicopter.hard-landing blocks/s: damage like a fall, and (like a crash) a chance to roll over. */
    private void hardLanding(LivingEntity base, VehicleType type, State st, List<VehicleManager.SeatRef> seats, double down) {
        double hard = cfg("helicopter.hard-landing", 7.0);
        if (down <= hard) return;
        World world = base.getWorld();
        Location at = base.getLocation();
        double dmg = (down - hard) * cfg("crash.fall-damage", 4.0) * type.crashMultiplier;
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.5f);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 14, 0.6, 0.1, 0.6, 0.02);
        double flipSpeed = cfg("physics.flip-speed", 8.0);
        if (plugin.getConfig().getBoolean("physics.enabled", true) && down >= flipSpeed && Math.random() < cfg("physics.flip-chance", 0.6)) {
            double torque = cfg("physics.flip-torque", 11.0) * Math.min(2.0, down / flipSpeed);
            st.wRoll = (Math.random() < 0.5 ? -1 : 1) * torque;
            st.wPitch = (Math.random() - 0.5) * torque * 0.4;
            st.engine = false;
        }
        double rdmg = Math.max(0, down - hard) * cfg("crash.rider-damage", 0.6) * type.crashMultiplier;
        for (VehicleManager.SeatRef seat : seats) {
            Player p = seat.rider();
            if (p == null || rdmg <= 0) continue;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            p.damage(rdmg);
            p.sendActionBar(Component.text(String.format(Locale.ROOT, "Hard landing! %.0f blocks/s", down), NamedTextColor.RED));
        }
        manager.hurt(base, dmg);
    }

    // ------------------------------------------------------------------ orientation physics

    /** Highest solid surface under (x, z) between y+1.5 and y-3, or NaN when there is none (a drop). */
    static double groundAt(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.5); by >= (int) Math.floor(y - 3); by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isPassable()) continue;
            double top = by;
            for (org.bukkit.util.BoundingBox box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, by + box.getMaxY());
            return top;
        }
        return Double.NaN;
    }

    /** Pitch/roll (degrees) of the plane through four corner heights: {pitch, roll}; nose up / right side up positive. */
    static double[] planeAngles(double fl, double fr, double bl, double br, double halfLength, double halfWidth) {
        double pitch = Math.toDegrees(Math.atan2((fl + fr) / 2 - (bl + br) / 2, 2 * halfLength));
        double roll = Math.toDegrees(Math.atan2((fr + br) / 2 - (fl + bl) / 2, 2 * halfWidth));
        return new double[]{pitch, roll};
    }

    static double approach(double cur, double target, double rate) { return cur + (target - cur) * Math.max(0, Math.min(1, rate)); }

    /** Helicopter lean in the air: {pitch, roll} targets - nose down with forward speed, banked into a turn. */
    static double[] flightLean(double speed, double maxSpeed, double steer, double noseAngle, double bankAngle) {
        double f = Math.max(-1.0, Math.min(1.0, speed / Math.max(0.1, maxSpeed)));
        double pitch = -noseAngle * f;
        double roll = -steer * bankAngle * (0.4 + 0.6 * Math.abs(f));
        return new double[]{pitch, roll};
    }

    private void tilt(LivingEntity base, VehicleType type, State st, List<VehicleManager.SeatRef> seats, Location at, float yaw, boolean onGround, double vy) {
        double rate = cfg("physics.tilt-rate", 0.25);
        double maxTilt = cfg("physics.max-ground-tilt", 50);
        double hw = type.hitbox * 0.45, hl = type.hitbox * 0.55;
        double rad = Math.toRadians(yaw);
        // corner (sx right, sz forward) -> world, same frame as the seats
        double[][] corners = {{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};   // FL FR BL BR
        double[] h = new double[4];
        boolean allGround = true;
        for (int i = 0; i < 4; i++) {
            double wx = at.getX() + (-corners[i][1] * Math.sin(rad) + corners[i][0] * Math.cos(rad));
            double wz = at.getZ() + ( corners[i][1] * Math.cos(rad) + corners[i][0] * Math.sin(rad));
            h[i] = groundAt(at.getWorld(), wx, at.getY(), wz);
            if (Double.isNaN(h[i])) allGround = false;
        }
        if (st.flipped) {
            // on the roof: settle flat on the surface, upside down, and slide to a stop
            st.flipAge++;
            double target = st.roll >= 0 ? 180 : -180;
            st.roll = approach(st.roll, target, 0.2);
            double[] ang = allGround ? planeAngles(h[0], h[1], h[2], h[3], hl, hw) : new double[]{0, 0};
            st.pitch = approach(st.pitch, -Math.max(-maxTilt, Math.min(maxTilt, ang[0])), 0.2);
            st.speed *= 0.9;
            return;
        }
        if (onGround && allGround) {
            double[] ang = planeAngles(h[0], h[1], h[2], h[3], hl, hw);
            st.pitch = approach(st.pitch, Math.max(-maxTilt, Math.min(maxTilt, ang[0])), rate);
            st.roll = approach(st.roll + st.wRoll, Math.max(-maxTilt, Math.min(maxTilt, ang[1])), rate);
            st.wRoll *= 0.6; st.wPitch *= 0.6;
        } else if (type.helicopter && st.engine && st.spool >= 1.0) {
            // powered flight: lean into the motion
            double[] lean = flightLean(st.speed, type.maxSpeed, st.steer, cfg("helicopter.nose-angle", 12), cfg("helicopter.bank-angle", 18));
            double r = cfg("helicopter.tilt-rate", 0.12);
            st.pitch = approach(st.pitch + st.wPitch, lean[0], r);
            st.roll = approach(st.roll + st.wRoll, lean[1], r);
            st.wRoll *= 0.985; st.wPitch *= 0.985;
        } else {
            // airborne (or over a drop): the nose follows the flight path, the roll keeps spinning
            double airPitch = Math.toDegrees(Math.atan2(vy * 20.0, Math.max(1.0, Math.abs(st.speed))));
            st.pitch = approach(st.pitch + st.wPitch, Math.max(-80, Math.min(80, airPitch)), cfg("physics.air-pitch-rate", 0.08));
            st.roll += st.wRoll;
            st.wRoll *= 0.985; st.wPitch *= 0.985;
        }
        if (st.roll > 180) st.roll -= 360; if (st.roll < -180) st.roll += 360;
        // past the tipping point and touching down: it stays on its roof
        if (Math.abs(st.roll) > cfg("physics.tip-angle", 95) && onGround) {
            st.flipped = true; st.flipAge = 0; st.wRoll = 0; st.wPitch = 0;
            st.speed = 0; st.momentum = new Vector();
            st.engine = false; st.vy = 0;
            final List<VehicleManager.SeatRef> fs = seats;
            forced(() -> {
                for (VehicleManager.SeatRef seat : fs) { Player p = seat.rider(); if (p != null) { seat.stand().eject(); p.sendActionBar(Component.text("The vehicle flipped over! Sneak + right-click it to turn it back.", NamedTextColor.RED)); } }
            });
            base.getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 1f, 0.6f);
        }
    }

    private void crash(LivingEntity base, VehicleType type, State st, List<VehicleManager.SeatRef> seats, double impact) {
        World world = base.getWorld();
        Location at = base.getLocation().add(0, 0.5, 0);
        double crashMin = cfg("crash.min-speed", 4.0);
        // a hard crash can throw the vehicle onto its side: roll impulse + a hop, the rest happens in tilt()
        double flipSpeed = cfg("physics.flip-speed", 8.0);
        if (plugin.getConfig().getBoolean("physics.enabled", true) && impact >= flipSpeed && Math.random() < cfg("physics.flip-chance", 0.6)) {
            double torque = cfg("physics.flip-torque", 11.0) * Math.min(2.0, impact / flipSpeed);
            st.wRoll = (st.steer != 0 ? -st.steer : (Math.random() < 0.5 ? -1 : 1)) * torque;
            st.wPitch = (Math.random() - 0.5) * torque * 0.4;
            base.setVelocity(base.getVelocity().setY(0.45));
            if (type.helicopter) { st.engine = false; st.vy = 0.45; }   // a crash that rolls a helicopter kills its engine
        }
        st.crashCooldown = 10;
        st.speed = -st.speed * 0.1;
        st.momentum = new Vector();
        boolean hard = impact >= crashMin * 2.5;
        world.playSound(at, hard ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_ANVIL_LAND, hard ? 0.8f : 0.9f, hard ? 1.4f : 0.5f);
        world.playSound(at, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.6f);
        world.spawnParticle(hard ? Particle.EXPLOSION : Particle.CRIT, at, hard ? 1 : 20, 0.4, 0.3, 0.4, 0.1);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.5, 0.3, 0.5, 0.02);
        if (!type.crash) return;
        double vdmg = impact * cfg("crash.vehicle-damage", 1.5) * type.crashMultiplier;
        double rdmg = Math.max(0, impact - crashMin) * cfg("crash.rider-damage", 0.6) * type.crashMultiplier;
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
                double dmg = speed * cfg("crash.vehicle-damage", 1.5) * 0.5;
                e.setVelocity(forward.clone().multiply(speed / 20.0 * 0.8));
                manager.hurt((LivingEntity) e, other != null && other.crash ? dmg * other.crashMultiplier : 0);
                manager.hurt(base, dmg * type.crashMultiplier);
                base.getWorld().playSound(base.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 0.5f);
                st.speed *= 0.3;
                st.crashCooldown = 10;
                continue;
            }
            LivingEntity le = (LivingEntity) e;
            double dmg = speed * cfg("crash.ram-damage", 0.8) * type.ramMultiplier;
            if (driver != null) le.damage(dmg, driver); else le.damage(dmg, base);
            le.setVelocity(forward.clone().multiply(speed / 10.0).add(new Vector(0, 0.35, 0)));
            base.getWorld().playSound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
            st.speed *= 0.85;
        }
    }

    private void hud(Player driver, LivingEntity base, VehicleType type, State st) {
        double speed = Math.abs(st.speed);
        float hue = (float) (0.33 - 0.33 * Math.min(1.0, speed / Math.max(1.0, type.maxSpeed)));
        TextColor color = TextColor.color(java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f));
        double hp = Math.max(0, base.getHealth()), max = manager.maxHealth(base);
        Component line = Component.text(String.format(Locale.ROOT, "%.1f blocks/s", speed), color);
        if (type.helicopter) {
            if (!st.engine) line = Component.text("ENGINE OFF - W or space to start   ", NamedTextColor.GRAY).append(line);
            else if (st.spool < 1.0) line = Component.text(String.format(Locale.ROOT, "starting %d%%   ", (int) (st.spool * 100)), NamedTextColor.YELLOW).append(line);
            line = line.append(Component.text(String.format(Locale.ROOT, "   %s %.1f   alt %d", st.vy > 0.01 ? "▲" : st.vy < -0.01 ? "▼" : "•", Math.abs(st.vy) * 20.0, (int) Math.floor(base.getLocation().getY())), NamedTextColor.AQUA));
        }
        line = line.append(Component.text("   ", NamedTextColor.GRAY))
            .append(Component.text(String.format(Locale.ROOT, "❤ %.0f/%.0f", hp, max), hp / max > 0.5 ? NamedTextColor.GREEN : hp / max > 0.25 ? NamedTextColor.YELLOW : NamedTextColor.RED));
        driver.sendActionBar(line);
    }
}
