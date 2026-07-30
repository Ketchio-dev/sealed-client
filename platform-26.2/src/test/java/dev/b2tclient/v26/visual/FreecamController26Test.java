package dev.b2tclient.v26.visual;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreecamController26Test {
    @Test
    void forwardMovementUsesCameraYaw() {
        Vec3 north = FreecamController26.movementVector(
                true, false, false, false, false, false, 0.0, 0.5
        );
        Vec3 west = FreecamController26.movementVector(
                true, false, false, false, false, false, 90.0, 0.5
        );

        assertEquals(0.0, north.x, 1.0E-9);
        assertEquals(0.5, north.z, 1.0E-9);
        assertEquals(-0.5, west.x, 1.0E-9);
        assertEquals(0.0, west.z, 1.0E-9);
    }

    @Test
    void combinedInputNeverExceedsConfiguredSpeed() {
        Vec3 movement = FreecamController26.movementVector(
                true, false, true, false, true, false, 37.0, 2.0
        );

        assertEquals(2.0, movement.length(), 1.0E-9);
        assertTrue(movement.y > 0.0);
    }

    @Test
    void contradictoryAndInvalidInputsFailClosed() {
        Vec3 stopped = FreecamController26.movementVector(
                true, true, true, true, true, true, 0.0, 1.0
        );
        Vec3 invalid = FreecamController26.movementVector(
                true, false, false, false, false, false, Double.NaN, 1.0
        );

        assertEquals(Vec3.ZERO, stopped);
        assertEquals(Vec3.ZERO, invalid);
        assertFalse(FreecamController26.redirectMouseTurn(
                null,
                1.0,
                1.0
        ));
    }

    @Test
    void configurationEnforcesHardMovementBounds() {
        FreecamController26 controller = new FreecamController26();
        FreecamController26.Configuration configured =
                new FreecamController26.Configuration(1.25, 3.0);
        controller.setConfiguration(configured);

        assertEquals(configured, controller.configuration());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FreecamController26.Configuration(5.01, 1.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FreecamController26.Configuration(0.5, 5.01)
        );
        assertThrows(
                NullPointerException.class,
                () -> controller.setConfiguration(null)
        );
    }
}
