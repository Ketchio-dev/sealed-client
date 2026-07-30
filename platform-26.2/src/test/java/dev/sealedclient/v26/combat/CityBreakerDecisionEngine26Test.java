package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBreakerDecisionEngine26Test {
    private static final CityBreakerDecisionEngine26.Limits LIMITS =
            new CityBreakerDecisionEngine26.Limits(16, 8.0, 4.5);

    @Test
    void selectsClosestReachableObsidianAndUsesStableKeyTieBreak() {
        long selected = CityBreakerDecisionEngine26.selectBest(
                List.of(
                        candidate(8, 4.0, 3.0, 8.0F),
                        candidate(4, 3.0, 3.5, 8.0F),
                        candidate(2, 3.0, 3.5, 8.0F)
                ),
                LIMITS
        );

        assertEquals(2L, selected);
    }

    @Test
    void rejectsFriendsNonObsidianAndUnreachableCityFaces() {
        CityBreakerDecisionEngine26.Candidate friend =
                candidate(1, 2.0, 2.0, 10.0F);
        friend = new CityBreakerDecisionEngine26.Candidate(
                friend.key(),
                friend.targetId(),
                friend.targetDistance(),
                friend.blockDistance(),
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                10.0F
        );
        CityBreakerDecisionEngine26.Candidate stone =
                replaceFlags(candidate(2, 2.0, 2.0, 10.0F),
                        false, true, true);
        CityBreakerDecisionEngine26.Candidate hidden =
                replaceFlags(candidate(3, 2.0, 2.0, 10.0F),
                        true, true, false);

        assertEquals(-1L, CityBreakerDecisionEngine26.selectBest(
                List.of(friend, stone, hidden),
                LIMITS
        ));
    }

    @Test
    void enclosedTargetIsAllowedOnlyWhenExactCityBlockHasLos() {
        CityBreakerDecisionEngine26.Candidate enclosed =
                candidate(9, 3.0, 3.5, 12.0F);
        enclosed = new CityBreakerDecisionEngine26.Candidate(
                enclosed.key(),
                enclosed.targetId(),
                enclosed.targetDistance(),
                enclosed.blockDistance(),
                true,
                false,
                false,
                true,
                true,
                true,
                2,
                12.0F
        );

        assertTrue(CityBreakerDecisionEngine26.valid(enclosed, LIMITS));
    }

    @Test
    void scanBoundCannotBeBypassedByLatePerfectCandidate() {
        List<CityBreakerDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        candidates.add(candidate(10, 5.0, 4.0, 5.0F));
        candidates.add(candidate(11, 4.0, 4.0, 5.0F));
        candidates.add(candidate(1, 1.0, 1.0, 50.0F));

        assertEquals(11L, CityBreakerDecisionEngine26.selectBest(
                candidates,
                new CityBreakerDecisionEngine26.Limits(
                        2,
                        8.0,
                        4.5
                )
        ));
    }

    @Test
    void confirmationRetriesOnlyAfterTimeoutAndConfirmsExactKey() {
        CityBreakerDecisionEngine26.Confirmation confirmation =
                new CityBreakerDecisionEngine26.Confirmation(4, 1);
        assertTrue(confirmation.begin(77L, 10L));
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.NONE,
                confirmation.observe(78L, true, 11L)
        );
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.CONTINUE,
                confirmation.observe(77L, false, 13L)
        );
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.RETRY,
                confirmation.observe(77L, false, 14L)
        );
        // Losing arbitration does not consume the ready retry.
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.RETRY,
                confirmation.observe(77L, false, 15L)
        );
        assertEquals(0, confirmation.snapshot().retries());
        assertTrue(confirmation.markRetried(15L));
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.CONFIRMED,
                confirmation.observe(77L, true, 16L)
        );
    }

    @Test
    void confirmationHasBoundedFailureAfterRetry() {
        CityBreakerDecisionEngine26.Confirmation confirmation =
                new CityBreakerDecisionEngine26.Confirmation(2, 1);
        assertTrue(confirmation.begin(3L, 1L));
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.RETRY,
                confirmation.observe(3L, false, 3L)
        );
        assertTrue(confirmation.markRetried(3L));
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.FAILED,
                confirmation.observe(3L, false, 5L)
        );
        // FAILED is terminal and remains visible until the live adapter
        // executes STOP and resets the transaction.
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.FAILED,
                confirmation.observe(3L, false, 6L)
        );
    }

    @Test
    void explicitRetryFailureRemainsTerminalForLeaseCleanup() {
        CityBreakerDecisionEngine26.Confirmation confirmation =
                new CityBreakerDecisionEngine26.Confirmation(2, 1);
        assertTrue(confirmation.begin(55L, 10L));
        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.RETRY,
                confirmation.observe(55L, false, 12L)
        );
        confirmation.fail();

        assertEquals(
                CityBreakerDecisionEngine26.Confirmation.Directive.FAILED,
                confirmation.observe(55L, false, 13L)
        );
    }

    @Test
    void manualStopLatchSurvivesArbiterLossUntilStopExecutes() {
        CityBreakerDecisionEngine26.StopLatch latch =
                new CityBreakerDecisionEngine26.StopLatch();
        CombatActionArbiter26 arbiter = new CombatActionArbiter26();
        latch.request();

        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                "higher.priority",
                100,
                java.util.Set.of(
                        CombatActionArbiter26.Channel.ATTACK,
                        CombatActionArbiter26.Channel.HOTBAR
                )
        );
        arbiter.submit(
                CombatSiegeAutomation26.CITY_OWNER,
                65,
                CombatSiegeAutomation26.requiredChannels(false)
        );
        arbiter.resolve();
        assertFalse(arbiter.ownsAll(
                CombatSiegeAutomation26.CITY_OWNER,
                CombatSiegeAutomation26.requiredChannels(false)
        ));
        assertTrue(latch.requested());

        arbiter.beginTick(CombatActionArbiter26.SafetyContext.ready());
        arbiter.submit(
                CombatSiegeAutomation26.CITY_OWNER,
                65,
                CombatSiegeAutomation26.requiredChannels(false)
        );
        arbiter.resolve();
        assertTrue(arbiter.ownsAll(
                CombatSiegeAutomation26.CITY_OWNER,
                CombatSiegeAutomation26.requiredChannels(false)
        ));
        latch.complete();
        assertFalse(latch.requested());
    }

    @Test
    void citySuccessRequiresAReflectedOpeningNotArbitraryReplacement() {
        assertTrue(CityBreakerDecisionEngine26.reflectedOpening(
                true,
                true
        ));
        assertTrue(CityBreakerDecisionEngine26.reflectedOpening(
                false,
                true
        ));
        assertFalse(CityBreakerDecisionEngine26.reflectedOpening(
                false,
                false
        ));
    }

    @Test
    void hotbarLeaseRestoresOnlyItsOwnAppliedSelection() {
        assertFalse(CityBreakerDecisionEngine26.selectionWasReplaced(
                2, 6, 6
        ));
        assertTrue(CityBreakerDecisionEngine26.selectionWasReplaced(
                2, 6, 4
        ));
        assertEquals(
                2,
                CityBreakerDecisionEngine26.restorationSlot(2, 6, 6)
        );
        assertEquals(
                -1,
                CityBreakerDecisionEngine26.restorationSlot(2, 6, 4)
        );
    }

    @Test
    void siegeConfigurationRejectsUnboundedValues() {
        assertEquals(
                4.5,
                CombatSiegeAutomation26.Configuration.defaults()
                        .cityMineRange()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatSiegeAutomation26.Configuration(
                        8.0,
                        9.0,
                        4.5,
                        5.0,
                        8.0,
                        12.0,
                        6.0,
                        12.0,
                        4.0,
                        6.0,
                        6.0,
                        5,
                        240,
                        1,
                        4,
                        40,
                        8,
                        1,
                        6,
                        60,
                        80
                )
        );
    }

    private static CityBreakerDecisionEngine26.Candidate candidate(
            long key,
            double targetDistance,
            double blockDistance,
            float speed
    ) {
        return new CityBreakerDecisionEngine26.Candidate(
                key,
                12,
                targetDistance,
                blockDistance,
                true,
                false,
                true,
                true,
                true,
                true,
                2,
                speed
        );
    }

    private static CityBreakerDecisionEngine26.Candidate replaceFlags(
            CityBreakerDecisionEngine26.Candidate candidate,
            boolean obsidian,
            boolean breakable,
            boolean blockLos
    ) {
        return new CityBreakerDecisionEngine26.Candidate(
                candidate.key(),
                candidate.targetId(),
                candidate.targetDistance(),
                candidate.blockDistance(),
                candidate.targetValid(),
                candidate.friend(),
                candidate.targetLineOfSight(),
                blockLos,
                obsidian,
                breakable,
                candidate.toolSlot(),
                candidate.destroySpeed()
        );
    }
}
