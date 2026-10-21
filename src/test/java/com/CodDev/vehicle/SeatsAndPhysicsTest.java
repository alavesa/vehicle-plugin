package com.CodDev.vehicle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 1.7.0: driver on the left, overlap-free seat stacking, tilt/flip helpers. */
class SeatsAndPhysicsTest {
    @Test
    void driverSeatIsOnTheLeft() {
        VehicleType t = new VehicleType("car");
        assertTrue(t.seats.get(0).x < 0, "seat 1 (driver) must have negative x = left");
        assertTrue(t.seats.get(1).x > 0);
    }

    @Test
    void seatCountStacksEvenlyWithoutOverlap() {
        VehicleType t = new VehicleType("bus");
        t.setSeatCount(6);
        assertEquals(6, t.seats.size());
        assertTrue(VehicleType.overlappingSeats(t.seats).isEmpty());
        assertEquals(-0.4, t.seats.get(2).x, 1e-9);                 // row 2 starts on the left again
        assertEquals(t.seats.get(0).z - 0.9, t.seats.get(2).z, 1e-9);
        t.setSeatCount(2);
        assertEquals(2, t.seats.size());
    }

    @Test
    void newSeatsSkipSpotsTakenByEditedSeats() {
        VehicleType t = new VehicleType("van");
        t.seats.get(1).x = -0.4; t.seats.get(1).z = 0.3 - 0.9;     // passenger moved to row 2 left
        t.setSeatCount(4);
        assertTrue(VehicleType.overlappingSeats(t.seats).isEmpty());
        assertEquals(0.4, t.seats.get(2).x, 1e-9);                  // the free front-right spot is used first
        assertEquals(0.3, t.seats.get(2).z, 1e-9);
    }

    @Test
    void overlapDetection() {
        VehicleType t = new VehicleType("car");
        assertTrue(VehicleType.overlappingSeats(t.seats).isEmpty());
        t.seats.get(1).x = -0.3;
        List<int[]> o = VehicleType.overlappingSeats(t.seats);
        assertEquals(1, o.size()); assertEquals(1, o.get(0)[0]); assertEquals(2, o.get(0)[1]);
    }

    @Test
    void groundPlaneAngles() {
        double[] flat = DriveTask.planeAngles(64, 64, 64, 64, 1, 0.8);
        assertEquals(0, flat[0], 1e-9); assertEquals(0, flat[1], 1e-9);
        double[] uphill = DriveTask.planeAngles(65, 65, 64, 64, 1, 0.8);   // front one block higher over 2 blocks
        assertEquals(Math.toDegrees(Math.atan2(1, 2)), uphill[0], 1e-9); assertEquals(0, uphill[1], 1e-9);
        double[] leftLow = DriveTask.planeAngles(64, 65, 64, 65, 1, 0.8);  // right side higher
        assertTrue(leftLow[1] > 0);
    }


    @Test
    void seatOffsetsTiltWithTheBody() {
        double[] o = VehicleManager.tiltedOffset(0.4, 0.4, 0.3, 0, 0, 0.9);
        assertArrayEquals(new double[]{0.4, 0.4, 0.3}, o, 1e-9);
        double[] r = VehicleManager.tiltedOffset(0.4, 0.9, 0, 0, 90, 0.9);   // roll 90 about the centre height
        assertEquals(0, r[0], 1e-9); assertEquals(1.3, r[1], 1e-9);
    }
}
