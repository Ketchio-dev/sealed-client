package dev.sealedclient.common.rotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationControllerTest {
    private static final float EPSILON = 1.0e-4f;

    @Test
    void highestPriorityWinsRegardlessOfRequestOrder() {
        RotationController controller = new RotationController();
        controller.beginTick();

        controller.request("kill_aura", 60, 10.0f, 0.0f);
        controller.request("auto_crystal", 80, 90.0f, 45.0f);

        RotationRequest winner = controller.resolve().orElseThrow();
        assertEquals("auto_crystal", winner.owner());
        assertEquals(90.0f, winner.yaw(), EPSILON);
    }

    @Test
    void lowerPriorityCannotDisplaceAnEarlierWinner() {
        RotationController controller = new RotationController();
        controller.beginTick();

        assertTrue(controller.request("auto_crystal", 80, 90.0f, 45.0f));
        assertFalse(controller.request("kill_aura", 60, 10.0f, 0.0f));

        assertEquals("auto_crystal", controller.resolve().orElseThrow().owner());
    }

    @Test
    void equalPrioritiesKeepTheFirstBid() {
        RotationController controller = new RotationController();
        controller.beginTick();

        assertTrue(controller.request("bed_aura", 70, 30.0f, 10.0f));
        assertFalse(controller.request("anchor_aura", 70, 120.0f, -20.0f));

        RotationRequest winner = controller.resolve().orElseThrow();
        assertEquals("bed_aura", winner.owner());
        assertEquals(30.0f, winner.yaw(), EPSILON);
    }

    @Test
    void theSameOwnerMayRefineItsOwnBid() {
        RotationController controller = new RotationController();
        controller.beginTick();

        controller.request("bow_aim", 50, 10.0f, 0.0f);
        assertTrue(controller.request("bow_aim", 50, 12.0f, 3.0f));

        assertEquals(12.0f, controller.resolve().orElseThrow().yaw(), EPSILON,
                "A module that aims twice in a tick must be able to correct itself, "
                        + "which is how aim-then-restore sequences work");
        assertEquals(3.0f, controller.resolve().orElseThrow().pitch(), EPSILON);
    }

    @Test
    void beginTickDropsThePreviousTicksWinner() {
        RotationController controller = new RotationController();
        controller.beginTick();
        controller.request("kill_aura", 60, 10.0f, 0.0f);

        controller.beginTick();

        assertTrue(controller.resolve().isEmpty());
    }

    @Test
    void clearDropsThePendingBid() {
        RotationController controller = new RotationController();
        controller.beginTick();
        controller.request("kill_aura", 60, 10.0f, 0.0f);

        controller.clear();

        assertTrue(controller.resolve().isEmpty());
    }

    @Test
    void yawStepsTheShortWayAcrossTheSeam() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(10.0f);

        // 179 -> -179 is 2 degrees the short way, not 358 the long way. Yaw is
        // kept continuous rather than re-wrapped, which is what Minecraft does,
        // so the result reads as 181 and not -179.
        assertEquals(181.0f, controller.stepYaw(179.0f, -179.0f), EPSILON);
        assertEquals(-181.0f, controller.stepYaw(-179.0f, 179.0f), EPSILON);

        assertEquals(-179.0f, RotationController.wrapDegrees(controller.stepYaw(179.0f, -179.0f)), EPSILON);
        assertEquals(179.0f, RotationController.wrapDegrees(controller.stepYaw(-179.0f, 179.0f)), EPSILON);
    }

    @Test
    void yawNeverTakesTheLongWayAround() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(RotationController.UNLIMITED_DEGREES_PER_TICK);

        float stepped = controller.stepYaw(170.0f, -170.0f);
        assertEquals(20.0f, Math.abs(stepped - 170.0f), EPSILON,
                "Crossing the seam must move 20 degrees, not 340");
    }

    @Test
    void yawIsLimitedToTheConfiguredTurnRate() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(15.0f);

        assertEquals(15.0f, controller.stepYaw(0.0f, 90.0f), EPSILON);
        assertEquals(-15.0f, controller.stepYaw(0.0f, -90.0f), EPSILON);
    }

    @Test
    void yawReachesTheTargetWhenWithinTheTurnRate() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(15.0f);

        assertEquals(7.0f, controller.stepYaw(0.0f, 7.0f), EPSILON);
    }

    @Test
    void defaultTurnRateReachesAnyTargetInOneTick() {
        RotationController controller = new RotationController();

        assertEquals(RotationController.UNLIMITED_DEGREES_PER_TICK, controller.degreesPerTick(), EPSILON);
        assertEquals(120.0f, controller.stepYaw(0.0f, 120.0f), EPSILON);
        assertEquals(90.0f, controller.stepPitch(0.0f, 90.0f), EPSILON);
    }

    @Test
    void pitchIsClampedToTheVerticalLimits() {
        RotationController controller = new RotationController();

        assertEquals(90.0f, controller.stepPitch(0.0f, 140.0f), EPSILON);
        assertEquals(-90.0f, controller.stepPitch(0.0f, -140.0f), EPSILON);
    }

    @Test
    void pitchIsLimitedToTheConfiguredTurnRate() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(5.0f);

        assertEquals(5.0f, controller.stepPitch(0.0f, 45.0f), EPSILON);
    }

    @Test
    void wrapDegreesNormalisesToTheHalfOpenTurn() {
        assertEquals(0.0f, RotationController.wrapDegrees(360.0f), EPSILON);
        assertEquals(180.0f, RotationController.wrapDegrees(180.0f), EPSILON);
        assertEquals(180.0f, RotationController.wrapDegrees(-180.0f), EPSILON);
        assertEquals(-90.0f, RotationController.wrapDegrees(270.0f), EPSILON);
        assertEquals(1.0f, RotationController.wrapDegrees(721.0f), EPSILON);
    }

    @Test
    void turnRateMustBePositive() {
        RotationController controller = new RotationController();

        assertThrows(IllegalArgumentException.class, () -> controller.setDegreesPerTick(0.0f));
        assertThrows(IllegalArgumentException.class, () -> controller.setDegreesPerTick(-5.0f));
        assertThrows(IllegalArgumentException.class, () -> controller.setDegreesPerTick(Float.NaN));
    }

    @Test
    void turnRateIsCappedAtAHalfTurn() {
        RotationController controller = new RotationController();
        controller.setDegreesPerTick(1000.0f);

        assertEquals(RotationController.UNLIMITED_DEGREES_PER_TICK, controller.degreesPerTick(), EPSILON);
    }

    @Test
    void requestsRejectBlankOwnersAndNonFiniteAngles() {
        assertThrows(IllegalArgumentException.class, () -> new RotationRequest(" ", 0, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationRequest("owner", 0, Float.NaN, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationRequest("owner", 0, 0.0f, Float.POSITIVE_INFINITY));
    }
}
