package com.CodDev.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The SCALE attribute that turns the base entity's box into the type's hitbox (1.5.0: armor stand base). */
class BaseScaleTest {
    @Test
    void armorStandScaleMakesTheHitboxItsHeight() {
        assertEquals(1.8 / 1.975, VehicleManager.baseScale(1.8, true), 1e-9);
        assertEquals(1.0, VehicleManager.baseScale(1.975, true), 1e-9);
    }

    @Test
    void legacyPigScaleUnchanged() {
        assertEquals(2.0, VehicleManager.baseScale(1.8, false), 1e-9);
    }

    @Test
    void scaleIsClampedToTheAttributeRange() {
        assertEquals(0.1, VehicleManager.baseScale(0, true), 1e-9);
        assertEquals(16.0, VehicleManager.baseScale(1000, true), 1e-9);
    }
}
