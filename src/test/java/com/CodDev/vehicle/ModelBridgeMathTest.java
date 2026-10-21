package com.CodDev.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the RP Engine path (1.4.0 doubled every model and froze its heading). */
class ModelBridgeMathTest {

    @Test
    void modelScaleIgnoresTheEntityScaleAttribute() {
        // 1.4.0: RP Engine's default scaler multiplied by the base Pig's SCALE attribute (hitbox / 0.9 = 2.0)
        assertEquals(1.0f, ModelBridge.modelScale(1.0), 1e-6);
        assertEquals(0.5f, ModelBridge.modelScale(0.5), 1e-6);
        assertEquals(0.01f, ModelBridge.modelScale(0), 1e-6);      // never a zero-size model
        assertEquals(16.0f, ModelBridge.modelScale(99), 1e-6);
    }

    @Test
    void defaultModelYawKeepsTheCarFacingItsHeading() {
        // model-yaw 180 (the default: Blockbench north = front) is what RP Engine does on its own -> no extra turn
        assertEquals(0f, ModelBridge.modelYaw(0f, 180, 0), 1e-6);
        assertEquals(90f, ModelBridge.modelYaw(90f, 180, 0), 1e-6);
        assertEquals(-90f, ModelBridge.modelYaw(-90f, 180, 0), 1e-6);
    }

    @Test
    void modelYawFollowsTheEntityEveryTick() {
        // 1.4.0 passed the constant model-yaw as the whole rotation, so the model never turned with the car
        float a = ModelBridge.modelYaw(10f, 180, 0), b = ModelBridge.modelYaw(20f, 180, 0);
        assertNotEquals(a, b);
        assertEquals(10f, b - a, 1e-6);
    }

    @Test
    void modelYawAddsTypeAndConfigOffsetsAndNormalises() {
        assertEquals(-90f, ModelBridge.modelYaw(0f, 90, 0), 1e-6);     // model-yaw 90 = a quarter turn less
        assertEquals(180f, ModelBridge.modelYaw(0f, 180, 180), 1e-6);  // config yaw-offset flips a backwards model
        assertEquals(-170f, ModelBridge.modelYaw(170f, 180, 20), 1e-6); // wraps into -180..180
        assertEquals(0f, ModelBridge.modelYaw(360f, 180, 0), 1e-6);
    }
}
