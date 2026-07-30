package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovementInputAutomation26Test {
    private final MovementActionArbiter26 arbiter =
            new MovementActionArbiter26();
    private final MovementInputAutomation26 automation =
            new MovementInputAutomation26();

    @AfterEach
    void releaseStaticHooks() {
        automation.release(arbiter);
    }

    @Test
    void absentClientFailsClosedWithoutClaimingHorizontalChannel() {
        arbiter.beginTick(MovementActionArbiter26.SafetyContext.ready());
        boolean submitted = automation.submit(
                null,
                true,
                true,
                true,
                activeSafety(),
                arbiter
        );
        arbiter.resolve();

        assertFalse(submitted);
        assertFalse(arbiter.owns(
                MovementInputAutomation26.GROUND_SPEED_OWNER,
                MovementActionArbiter26.Channel.HORIZONTAL
        ));
        assertFalse(
                MovementInputAutomation26.shouldBypassItemSlowdown(null)
        );
        assertFalse(
                MovementInputAutomation26.shouldPreserveServerYaw(null)
        );
        assertFalse(automation.execute(null, arbiter).applied());
    }

    @Test
    void configurationRoundTripsAndRejectsInvalidNestedValues() {
        MovementInputAutomation26.Configuration configuration =
                new MovementInputAutomation26.Configuration(
                        0.34,
                        0.05,
                        true,
                        false
                );
        automation.setConfiguration(configuration);

        assertEquals(configuration, automation.configuration());
        assertEquals(0.34, automation.configuration().groundSpeed().targetSpeed());
        assertFalse(automation.configuration().noRotate().preservePitch());
        assertThrows(
                NullPointerException.class,
                () -> automation.setConfiguration(null)
        );
    }

    @Test
    void releaseImmediatelyDisablesPublishedHooks() {
        arbiter.beginTick(MovementActionArbiter26.SafetyContext.ready());
        automation.submit(
                null,
                false,
                true,
                true,
                activeSafety(),
                arbiter
        );
        automation.release(arbiter);

        MovementInputAutomation26.HookSnapshot hooks =
                MovementInputAutomation26.hookSnapshot();
        assertFalse(hooks.noSlowEnabled());
        assertFalse(hooks.noRotateEnabled());
        assertFalse(hooks.sessionActive());
    }

    private static MovementSafetyPolicy26.Decision activeSafety() {
        return new MovementSafetyPolicy26.Decision(
                MovementSafetyPolicy26.State.ACTIVE,
                MovementSafetyPolicy26.Reason.STABLE,
                1.0,
                0,
                0,
                0
        );
    }
}
