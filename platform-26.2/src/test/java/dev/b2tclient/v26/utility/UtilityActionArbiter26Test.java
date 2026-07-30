package dev.b2tclient.v26.utility;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityActionArbiter26Test {
    @Test
    void grantsCompleteBundlesByPriorityAndNeverPartially() {
        UtilityActionArbiter26 arbiter = new UtilityActionArbiter26();
        arbiter.beginTick(UtilityActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "fast_use",
                20,
                Set.of(UtilityActionArbiter26.Channel.USE)
        );
        arbiter.submit(
                "auto_mend",
                60,
                Set.of(
                        UtilityActionArbiter26.Channel.USE,
                        UtilityActionArbiter26.Channel.HOTBAR,
                        UtilityActionArbiter26.Channel.ROTATION
                )
        );

        arbiter.resolve();

        assertTrue(arbiter.ownsAll(
                "auto_mend",
                Set.of(
                        UtilityActionArbiter26.Channel.USE,
                        UtilityActionArbiter26.Channel.HOTBAR,
                        UtilityActionArbiter26.Channel.ROTATION
                )
        ));
        assertFalse(arbiter.owns(
                "fast_use",
                UtilityActionArbiter26.Channel.USE
        ));
        assertEquals(
                UtilityActionArbiter26.DecisionStatus.DENIED,
                arbiter.decision("fast_use").status()
        );
    }

    @Test
    void externalCombatReservationCannotBePreempted() {
        UtilityActionArbiter26 arbiter = new UtilityActionArbiter26();
        arbiter.beginTick(
                UtilityActionArbiter26.SafetyContext.ready(),
                Set.of(
                        UtilityActionArbiter26.Channel.HOTBAR,
                        UtilityActionArbiter26.Channel.USE
                )
        );
        arbiter.submit(
                "auto_mend",
                Integer.MAX_VALUE,
                Set.of(
                        UtilityActionArbiter26.Channel.HOTBAR,
                        UtilityActionArbiter26.Channel.USE
                )
        );

        arbiter.resolve();

        assertFalse(arbiter.owns(
                "auto_mend",
                UtilityActionArbiter26.Channel.HOTBAR
        ));
        assertEquals(
                UtilityActionArbiter26.EXTERNAL_OWNER,
                arbiter.decision("auto_mend")
                        .blockers()
                        .get(UtilityActionArbiter26.Channel.HOTBAR)
        );
    }

    @Test
    void equalPriorityOrderingIsIndependentOfSubmissionOrder() {
        UtilityActionArbiter26 first = equalPriority(false);
        UtilityActionArbiter26 second = equalPriority(true);

        assertTrue(first.owns(
                "alpha",
                UtilityActionArbiter26.Channel.INVENTORY
        ));
        assertTrue(second.owns(
                "alpha",
                UtilityActionArbiter26.Channel.INVENTORY
        ));
    }

    @Test
    void safetyBlocksCollectionAndAllGrantsExpireNextTick() {
        UtilityActionArbiter26 arbiter = new UtilityActionArbiter26();
        arbiter.beginTick(UtilityActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "auto_craft",
                10,
                Set.of(UtilityActionArbiter26.Channel.INVENTORY)
        );
        arbiter.resolve();
        assertTrue(arbiter.owns(
                "auto_craft",
                UtilityActionArbiter26.Channel.INVENTORY
        ));

        arbiter.beginTick(new UtilityActionArbiter26.SafetyContext(
                true,
                true,
                true,
                false
        ));

        assertFalse(arbiter.submit(
                "auto_craft",
                10,
                Set.of(UtilityActionArbiter26.Channel.INVENTORY)
        ));
        assertFalse(arbiter.owns(
                "auto_craft",
                UtilityActionArbiter26.Channel.INVENTORY
        ));
        assertEquals(
                UtilityActionArbiter26.SafetyBlock.NETWORK_UNREADY,
                arbiter.decision("auto_craft").safetyBlock()
        );
    }

    @Test
    void phaseAndInputValidationFailClosed() {
        UtilityActionArbiter26 arbiter = new UtilityActionArbiter26();
        assertThrows(
                IllegalStateException.class,
                () -> arbiter.submit(
                        "x",
                        1,
                        Set.of(UtilityActionArbiter26.Channel.USE)
                )
        );
        arbiter.beginTick(UtilityActionArbiter26.SafetyContext.ready());
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit(" ", 1, Set.of(
                        UtilityActionArbiter26.Channel.USE
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit("x", 1, Set.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> arbiter.submit(
                        UtilityActionArbiter26.EXTERNAL_OWNER,
                        1,
                        Set.of(UtilityActionArbiter26.Channel.USE)
                )
        );
        arbiter.resolve();
        assertTrue(arbiter.snapshot().reservedChannels().isEmpty());
        assertThrows(IllegalStateException.class, arbiter::resolve);
    }

    private static UtilityActionArbiter26 equalPriority(boolean reverse) {
        UtilityActionArbiter26 arbiter = new UtilityActionArbiter26();
        arbiter.beginTick(UtilityActionArbiter26.SafetyContext.ready());
        if (reverse) {
            submit(arbiter, "zeta");
            submit(arbiter, "alpha");
        } else {
            submit(arbiter, "alpha");
            submit(arbiter, "zeta");
        }
        arbiter.resolve();
        return arbiter;
    }

    private static void submit(
            UtilityActionArbiter26 arbiter,
            String owner
    ) {
        arbiter.submit(
                owner,
                10,
                Set.of(UtilityActionArbiter26.Channel.INVENTORY)
        );
    }
}
