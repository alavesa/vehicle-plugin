package com.CodDev.vehicle;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One configurable vehicle type (saved in vehicles.yml). All distances are in blocks. */
public final class VehicleType {

    /** A seat: position in model space (blocks, i.e. Blockbench pixels / 16, before the type's scale),
     *  plus the size of the invisible click box that lets players enter from a distance. */
    public static final class Seat {
        public double x, y, z;
        public double width = 1.6, height = 1.2;

        public Seat(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

        public Seat copy() { Seat s = new Seat(x, y, z); s.width = width; s.height = height; return s; }
    }

    public final String id;
    public String name;
    /** Model name = file name of plugins/Vehicle/models/<model>.bbmodel (empty = no body). */
    public String model = "";

    // driving
    public double maxSpeed = 10.0;        // blocks per second
    public double acceleration = 6.0;     // blocks/s gained per second of throttle
    public double brake = 14.0;           // blocks/s lost per second of braking
    public double reverseFactor = 0.4;    // reverse top speed as a fraction of maxSpeed
    public double friction = 0.985;       // per-tick speed factor when coasting
    public double turnRate = 3.5;         // degrees per tick at full grip
    public double grip = 0.6;             // 1 = velocity follows the nose instantly, lower = slides

    // body
    public double scale = 1.0;            // model scale
    public double modelYaw = 180.0;       // rotation of the model around the vehicle (180 = Blockbench front/north faces forward)
    public double offsetX = 0, offsetY = 0, offsetZ = 0;   // nudge the body relative to the physics base
    /** Driving states and which .bbmodel animation loops in each (empty = fall back, see animationFor). */
    public static final List<String> STATES = List.of("idle", "drive", "reverse", "left", "right", "reverse_left", "reverse_right");
    public final java.util.Map<String, String> animations = new java.util.LinkedHashMap<>();

    /** Fallback chain: reverse_left -> reverse -> left -> drive; left/right/reverse -> drive; idle -> nothing. */
    private static final java.util.Map<String, List<String>> FALLBACK = java.util.Map.of(
        "idle", List.of("idle"),
        "drive", List.of("drive"),
        "reverse", List.of("reverse", "drive"),
        "left", List.of("left", "drive"),
        "right", List.of("right", "drive"),
        "reverse_left", List.of("reverse_left", "reverse", "left", "drive"),
        "reverse_right", List.of("reverse_right", "reverse", "right", "drive"));

    /** The animation to play in a driving state ("" = none). */
    public String animationFor(String state) {
        for (String k : FALLBACK.getOrDefault(state, List.of(state))) {
            String a = animations.get(k);
            if (a != null && !a.isEmpty()) return a;
        }
        return "";
    }

    /** Normalise a state word typed by a player: "reverse-left", "backward left", "back_right" ... -> the STATES key. */
    public static String stateKey(String word) {
        String w = word.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\-]+", "_").replace("backwards", "reverse").replace("backward", "reverse").replace("back", "reverse").replace("forward", "drive").replace("set", "drive");
        if (w.equals("reverse_reverse")) w = "reverse";
        return STATES.contains(w) ? w : null;
    }

    // physics
    public double hitbox = 1.8;           // collision box width (and height) in blocks
    public double stepHeight = 1.0;       // blocks the vehicle can climb without jumping
    public double health = 100.0;
    public boolean crash = true;          // crash/ram damage on or off for this type
    public double crashMultiplier = 1.0;  // scales crash damage (vehicle + riders)
    public double ramMultiplier = 1.0;    // scales damage dealt to things it hits

    // audio
    public String sound = "minecraft:entity.minecart.riding";
    public double soundVolume = 0.6;
    public String horn = "minecraft:item.goat_horn.sound.1";

    public List<Seat> seats = new ArrayList<>();

    public VehicleType(String id) {
        this.id = id.toLowerCase(Locale.ROOT);
        this.name = id;
        seats.add(new Seat(-0.4, 0.4, 0.3));   // driver: LEFT side (+x is the vehicle's right)
        seats.add(new Seat(0.4, 0.4, 0.3));
    }

    /** Seats closer than this (x/z) count as overlapping. */
    public static final double SEAT_MIN_GAP = 0.45;

    /** Seats that sit on top of each other (x/z closer than SEAT_MIN_GAP): pairs of 1-based numbers. */
    public static List<int[]> overlappingSeats(List<Seat> seats) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) for (int j = i + 1; j < seats.size(); j++)
            if (Math.hypot(seats.get(i).x - seats.get(j).x, seats.get(i).z - seats.get(j).z) < SEAT_MIN_GAP) out.add(new int[]{i + 1, j + 1});
        return out;
    }

    /** The first free slot of the even grid (rows 0.9 blocks apart behind the first seat, left seat then right seat)
     *  that does not overlap an existing seat - so edited seats are never buried under new ones. */
    public static double[] freeSeatSlot(List<Seat> seats) {
        double z0 = seats.isEmpty() ? 0.3 : seats.get(0).z;
        for (int row = 0; row < 64; row++) {
            for (double x : new double[]{-0.4, 0.4}) {
                double z = Math.round((z0 - 0.9 * row) * 1000.0) / 1000.0;
                boolean taken = false;
                for (Seat o : seats) if (Math.hypot(o.x - x, o.z - z) < SEAT_MIN_GAP) { taken = true; break; }
                if (!taken) return new double[]{x, z};
            }
        }
        return new double[]{0, z0 - 0.9 * 64};
    }

    public static boolean validId(String id) { return id != null && id.matches("[a-zA-Z0-9_-]{1,32}"); }

    public void load(ConfigurationSection s) {
        name = s.getString("name", name);
        model = s.getString("model", model);
        maxSpeed = s.getDouble("max-speed", maxSpeed);
        acceleration = s.getDouble("acceleration", acceleration);
        brake = s.getDouble("brake", brake);
        reverseFactor = s.getDouble("reverse-factor", reverseFactor);
        friction = s.getDouble("friction", friction);
        turnRate = s.getDouble("turn-rate", turnRate);
        grip = s.getDouble("grip", grip);
        scale = s.getDouble("scale", scale);
        modelYaw = s.getDouble("model-yaw", modelYaw);
        animations.clear();
        ConfigurationSection an = s.getConfigurationSection("animations");
        if (an != null) for (String k : an.getKeys(false)) { String key = stateKey(k); if (key != null) animations.put(key, an.getString(k, "")); }
        if (!s.getString("drive-animation", "").isEmpty() && !animations.containsKey("drive")) animations.put("drive", s.getString("drive-animation"));   // pre-1.6 keys
        if (!s.getString("idle-animation", "").isEmpty() && !animations.containsKey("idle")) animations.put("idle", s.getString("idle-animation"));
        offsetX = s.getDouble("offset.x", offsetX);
        offsetY = s.getDouble("offset.y", offsetY);
        offsetZ = s.getDouble("offset.z", offsetZ);
        hitbox = s.getDouble("hitbox", hitbox);
        stepHeight = s.getDouble("step-height", stepHeight);
        health = s.getDouble("health", health);
        crash = s.getBoolean("crash", crash);
        crashMultiplier = s.getDouble("crash-multiplier", crashMultiplier);
        ramMultiplier = s.getDouble("ram-multiplier", ramMultiplier);
        sound = s.getString("sound", sound);
        soundVolume = s.getDouble("sound-volume", soundVolume);
        horn = s.getString("horn", horn);
        ConfigurationSection ss = s.getConfigurationSection("seats");
        if (ss != null) {
            List<Seat> loaded = new ArrayList<>();
            int i = 0;
            while (ss.isConfigurationSection(String.valueOf(i))) {
                ConfigurationSection one = ss.getConfigurationSection(String.valueOf(i));
                Seat seat = new Seat(one.getDouble("x", 0), one.getDouble("y", 0.4), one.getDouble("z", 0));
                seat.width = one.getDouble("width", seat.width);
                seat.height = one.getDouble("height", seat.height);
                loaded.add(seat);
                i++;
            }
            if (!loaded.isEmpty()) seats = loaded;
        }
    }

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("model", model);
        s.set("max-speed", maxSpeed);
        s.set("acceleration", acceleration);
        s.set("brake", brake);
        s.set("reverse-factor", reverseFactor);
        s.set("friction", friction);
        s.set("turn-rate", turnRate);
        s.set("grip", grip);
        s.set("scale", scale);
        s.set("model-yaw", modelYaw);
        s.set("drive-animation", null); s.set("idle-animation", null);
        s.set("animations", null);
        for (var en : animations.entrySet()) if (!en.getValue().isEmpty()) s.set("animations." + en.getKey(), en.getValue());
        s.set("offset.x", offsetX);
        s.set("offset.y", offsetY);
        s.set("offset.z", offsetZ);
        s.set("hitbox", hitbox);
        s.set("step-height", stepHeight);
        s.set("health", health);
        s.set("crash", crash);
        s.set("crash-multiplier", crashMultiplier);
        s.set("ram-multiplier", ramMultiplier);
        s.set("sound", sound);
        s.set("sound-volume", soundVolume);
        s.set("horn", horn);
        s.set("seats", null);
        for (int i = 0; i < seats.size(); i++) {
            Seat seat = seats.get(i);
            String p = "seats." + i + ".";
            s.set(p + "x", seat.x);
            s.set(p + "y", seat.y);
            s.set(p + "z", seat.z);
            s.set(p + "width", seat.width);
            s.set(p + "height", seat.height);
        }
    }

    /** Resize the seat list, keeping existing seats and adding sensible new ones behind the last. */
    public void setSeatCount(int n) {
        n = Math.max(1, Math.min(16, n));
        while (seats.size() > n) seats.remove(seats.size() - 1);
        while (seats.size() < n) {
            Seat last = seats.isEmpty() ? new Seat(-0.4, 0.4, 0.3) : seats.get(seats.size() - 1);
            Seat next = last.copy();
            double[] slot = freeSeatSlot(seats);
            next.x = slot[0]; next.z = slot[1];
            seats.add(next);
        }
    }
}
