package com.CodDev.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0: one animation per driving state with sensible fallbacks. */
class DriveAnimationStateTest {
    @Test
    void stateFromSpeedAndSteer() {
        assertEquals("idle", DriveTask.driveState(0, 0));
        assertEquals("idle", DriveTask.driveState(0.2, -1));
        assertEquals("drive", DriveTask.driveState(5, 0));
        assertEquals("left", DriveTask.driveState(5, -1));
        assertEquals("right", DriveTask.driveState(5, 1));
        assertEquals("reverse", DriveTask.driveState(-2, 0));
        assertEquals("reverse_left", DriveTask.driveState(-2, -1));
        assertEquals("reverse_right", DriveTask.driveState(-2, 1));
    }

    @Test
    void fallbackChain() {
        VehicleType t = new VehicleType("car");
        t.animations.put("drive", "roll");
        assertEquals("roll", t.animationFor("left"));
        assertEquals("roll", t.animationFor("reverse_right"));
        assertEquals("", t.animationFor("idle"));
        t.animations.put("reverse", "roll_back");
        assertEquals("roll_back", t.animationFor("reverse_left"));
        t.animations.put("reverse_left", "rl");
        assertEquals("rl", t.animationFor("reverse_left"));
        assertEquals("roll_back", t.animationFor("reverse_right"));
    }

    @Test
    void stateWordsTypedByPlayers() {
        assertEquals("reverse_left", VehicleType.stateKey("backward-left"));
        assertEquals("reverse_right", VehicleType.stateKey("Reverse Right"));
        assertEquals("drive", VehicleType.stateKey("set"));
        assertEquals("reverse", VehicleType.stateKey("backwards"));
        assertNull(VehicleType.stateKey("sideways"));
    }

    @Test
    void javaRotationRule() {
        assertTrue(ModelBridge.javaRotation(new double[]{0, 22.5, 0}));
        assertTrue(ModelBridge.javaRotation(new double[]{-45, 0, 0}));
        assertFalse(ModelBridge.javaRotation(new double[]{0, 30, 0}));
        assertFalse(ModelBridge.javaRotation(new double[]{22.5, 22.5, 0}));
        assertFalse(ModelBridge.javaRotation(new double[]{0, 0, 90}));
    }

    @Test
    void freeRotatedCubesGetTheirOwnBone() {
        String json = """
            {"elements":[{"name":"spoiler","uuid":"c1","from":[0,0,0],"to":[4,1,4],"origin":[2,0,2],"rotation":[0,-30,0]},
                         {"name":"ok","uuid":"c2","from":[0,0,0],"to":[1,1,1],"rotation":[0,22.5,0]}],
             "outliner":[{"name":"body","uuid":"g1","origin":[0,0,0],"children":["c1","c2"]}]}""";
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        java.util.List<String> notes = new java.util.ArrayList<>();
        assertEquals(1, ModelBridge.rewrite(root, notes));
        var body = root.getAsJsonArray("outliner").get(0).getAsJsonObject();
        var first = body.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("spoiler_rot", first.get("name").getAsString());
        assertEquals(-30.0, first.getAsJsonArray("rotation").get(1).getAsDouble(), 1e-9);
        assertEquals("c1", first.getAsJsonArray("children").get(0).getAsString());
        assertEquals("c2", body.getAsJsonArray("children").get(1).getAsString());
        assertEquals(0.0, root.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonArray("rotation").get(1).getAsDouble(), 1e-9);
    }
}
