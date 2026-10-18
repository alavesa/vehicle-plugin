package com.CodDev.vehicle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 1.8.0: helicopters - kind, flight states, vertical targets, lean, bail-out. */
class HelicopterTest {
    @Test
    void kindWordsAndSoundFollowTheKind() {
        assertEquals(Boolean.TRUE, VehicleType.kindFromWord("Helicopter"));
        assertEquals(Boolean.TRUE, VehicleType.kindFromWord("heli"));
        assertEquals(Boolean.FALSE, VehicleType.kindFromWord("car"));
        assertNull(VehicleType.kindFromWord("jeep"));
        VehicleType t = new VehicleType("chopper");
        assertFalse(t.helicopter);
        assertEquals(VehicleType.CAR_SOUND, t.sound);
        t.setHelicopter(true);
        assertTrue(t.helicopter);
        assertEquals(VehicleType.HELICOPTER_SOUND, t.sound);
        t.sound = "my:rotor";
        t.setHelicopter(false);
        assertEquals("my:rotor", t.sound, "a custom sound is kept when the kind changes");
    }

    @Test
    void kindAndFlightNumbersSurviveSaveAndLoad() {
        VehicleType t = new VehicleType("chopper");
        t.setHelicopter(true); t.lift = 7.5; t.descent = 3.25;
        YamlConfiguration y = new YamlConfiguration();
        t.save(y.createSection("types.chopper"));
        assertEquals("helicopter", y.getString("types.chopper.kind"));
        VehicleType back = new VehicleType("chopper");
        back.load(y.getConfigurationSection("types.chopper"));
        assertTrue(back.helicopter);
        assertEquals(7.5, back.lift, 1e-9);
        assertEquals(3.25, back.descent, 1e-9);
        VehicleType car = new VehicleType("car");
        car.save(y.createSection("types.car"));
        VehicleType carBack = new VehicleType("car");
        carBack.load(y.getConfigurationSection("types.car"));
        assertFalse(carBack.helicopter);
    }

    @Test
    void flightStates() {
        assertEquals("idle", DriveTask.heliState(false, 0, 1, 5, 0));
        assertEquals("start", DriveTask.heliState(true, 0.5, 1, 0, 0));
        assertEquals("ascend", DriveTask.heliState(true, 1, 1, 5, 1));
        assertEquals("descend", DriveTask.heliState(true, 1, -1, 0, 0));
        assertEquals("hover", DriveTask.heliState(true, 1, 0, 0, 0));
        assertEquals("left", DriveTask.heliState(true, 1, 0, 0, -1));
        assertEquals("drive", DriveTask.heliState(true, 1, 0, 6, 0));
        assertEquals("reverse_right", DriveTask.heliState(true, 1, 0, -2, 1));
    }

    @Test
    void helicopterStatesFallBackToTheRotorAnimation() {
        VehicleType t = new VehicleType("chopper");
        t.animations.put("drive", "rotor");
        assertEquals("rotor", t.animationFor("hover"));
        assertEquals("rotor", t.animationFor("ascend"));
        assertEquals("rotor", t.animationFor("start"));
        assertEquals("", t.animationFor("idle"));
        t.animations.put("start", "spool");
        assertEquals("spool", t.animationFor("start"));
        assertEquals("ascend", VehicleType.stateKey("up"));
        assertEquals("descend", VehicleType.stateKey("Down"));
        assertEquals("start", VehicleType.stateKey("spool"));
        assertEquals("hover", VehicleType.stateKey("hover"));
        assertEquals("drive", VehicleType.stateKey("fly"));
        assertEquals("reverse_left", VehicleType.stateKey("backward-left"));   // car words still work
    }

    @Test
    void verticalTargets() {
        assertTrue(Double.isNaN(DriveTask.verticalTarget(false, 0, 1, 5, 6, true, 64, 300)), "engine off: gravity");
        assertTrue(Double.isNaN(DriveTask.verticalTarget(true, 0.4, 1, 5, 6, true, 64, 300)), "still spooling: no lift");
        assertEquals(5 / 20.0, DriveTask.verticalTarget(true, 1, 1, 5, 6, true, 64, 300), 1e-9);
        assertEquals(-6 / 20.0, DriveTask.verticalTarget(true, 1, -1, 5, 6, false, 64, 300), 1e-9);
        assertEquals(0, DriveTask.verticalTarget(true, 1, 0, 5, 6, false, 64, 300), 1e-9, "hover in the air");
        assertEquals(-0.05, DriveTask.verticalTarget(true, 1, 0, 5, 6, true, 64, 300), 1e-9, "hover on the ground presses down");
        assertEquals(0, DriveTask.verticalTarget(true, 1, 1, 5, 6, false, 300, 300), 1e-9, "ceiling");
    }

    @Test
    void rotorRateAndLean() {
        assertEquals(1.0, DriveTask.rotorRate(false, 0, 0, 10), 1e-9);
        assertEquals(0.5, DriveTask.rotorRate(true, 0.5, 0, 10), 1e-9);
        assertEquals(1.25, DriveTask.rotorRate(true, 1, 10, 10), 1e-9);
        assertEquals(0.15, DriveTask.rotorRate(false, 0.1, 0, 10), 1e-9, "winding down still turns");
        double[] level = DriveTask.flightLean(0, 10, 0, 12, 18);
        assertEquals(0, level[0], 1e-9); assertEquals(0, level[1], 1e-9);
        double[] fast = DriveTask.flightLean(10, 10, 0, 12, 18);
        assertEquals(-12, fast[0], 1e-9, "nose down at full speed");
        double[] leftTurn = DriveTask.flightLean(5, 10, -1, 12, 18);
        assertTrue(leftTurn[1] > 0, "left turn dips the left side (roll > 0 = left side down)");
        double[] back = DriveTask.flightLean(-4, 10, 0, 12, 18);
        assertTrue(back[0] > 0, "nose up when flying backwards");
    }

    @Test
    void doubleTapSneakBailsOut() {
        assertFalse(DriveTask.bailOut(100, Integer.MIN_VALUE / 2, 8));
        assertTrue(DriveTask.bailOut(100, 95, 8));
        assertFalse(DriveTask.bailOut(100, 80, 8));
    }
}
