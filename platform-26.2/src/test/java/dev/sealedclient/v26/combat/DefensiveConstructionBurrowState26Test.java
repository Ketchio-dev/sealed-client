package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefensiveConstructionBurrowState26Test {
    @Test
    void normalJumpPlacementNeedsExplicitSendAndServerConfirmation() {
        DefensiveConstructionDecisionEngine26.BurrowStateMachine state =
                new DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine();
        assertTrue(state.begin(8L, 64.0, 10L, 12));
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.JUMP,
                state.evaluate(64.0, true, true, 10L, true, 1.0)
        );
        // Losing arbitration leaves the jump ready and consumes nothing.
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.JUMP,
                state.evaluate(64.0, true, true, 11L, true, 1.0)
        );

        assertTrue(state.markJumpSent());
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.WAIT,
                state.evaluate(64.8, false, true, 12L, true, 1.0)
        );
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.PLACE,
                state.evaluate(65.0, false, true, 13L, true, 1.0)
        );
        assertTrue(state.markPlacementSent());
        assertFalse(state.confirm(9L));
        assertTrue(state.confirm(8L));
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Phase.CONFIRMED,
                state.snapshot().phase()
        );
    }

    @Test
    void manualJumpModeWaitsForRealVerticalRise() {
        DefensiveConstructionDecisionEngine26.BurrowStateMachine state =
                new DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine();
        assertTrue(state.begin(1L, 20.0, 0L, 10));
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.WAIT,
                state.evaluate(20.0, true, true, 0L, false, 0.8)
        );
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.PLACE,
                state.evaluate(20.8, false, true, 1L, false, 0.8)
        );
    }

    @Test
    void timeoutAndBlockedTargetFailWithoutSuccess() {
        DefensiveConstructionDecisionEngine26.BurrowStateMachine timeout =
                new DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine();
        assertTrue(timeout.begin(1L, 20.0, 5L, 4));
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.FAILED,
                timeout.evaluate(20.0, true, true, 9L, true, 1.0)
        );

        DefensiveConstructionDecisionEngine26.BurrowStateMachine blocked =
                new DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine();
        assertTrue(blocked.begin(2L, 20.0, 0L, 10));
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Directive.FAILED,
                blocked.evaluate(20.0, true, false, 1L, true, 1.0)
        );
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Phase.FAILED,
                blocked.snapshot().phase()
        );
    }

    @Test
    void resetAllowsIndependentTransaction() {
        DefensiveConstructionDecisionEngine26.BurrowStateMachine state =
                new DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine();
        assertTrue(state.begin(1L, 0.0, 0L, 4));
        state.fail();
        state.reset();
        assertEquals(
                DefensiveConstructionDecisionEngine26
                        .BurrowStateMachine.Phase.IDLE,
                state.snapshot().phase()
        );
        assertTrue(state.begin(2L, 1.0, 2L, 4));
    }
}
