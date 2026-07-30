package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMendAutomation26Test {
    @Test
    void leaseRequiresExactOwnedSlotAndRotation() {
        assertTrue(AutoMendAutomation26.leaseStillOwned(3, 90.0F, 3));
        assertFalse(AutoMendAutomation26.leaseStillOwned(4, 90.0F, 3));
        assertFalse(AutoMendAutomation26.leaseStillOwned(3, 89.9F, 3));
    }

    @Test
    void manualYieldRecoversWithoutSneakAndTriggerReleaseClearsIt() {
        int yielded = AutoMendAutomation26.nextManualYieldTicks(
                0,
                true,
                false,
                false,
                true
        );
        assertTrue(yielded > 0);
        for (int tick = 0; tick < 20; tick++) {
            yielded = AutoMendAutomation26.nextManualYieldTicks(
                    yielded,
                    true,
                    false,
                    false,
                    false
            );
        }
        assertEquals(0, yielded);
        assertEquals(0, AutoMendAutomation26.nextManualYieldTicks(
                20,
                true,
                true,
                false,
                false
        ));
    }

    @Test
    void sameTickLeaseYieldResumesWarmOnlyWithUntouchedBaseline() {
        assertTrue(AutoMendAutomation26.canResumeWarmLease(
                120,
                2,
                15.0F,
                5,
                120,
                2,
                15.0F,
                5
        ));
        assertFalse(AutoMendAutomation26.canResumeWarmLease(
                121,
                2,
                15.0F,
                5,
                120,
                2,
                15.0F,
                5
        ));
        assertFalse(AutoMendAutomation26.canResumeWarmLease(
                120,
                3,
                15.0F,
                5,
                120,
                2,
                15.0F,
                5
        ));
        assertFalse(AutoMendAutomation26.canResumeWarmLease(
                120,
                2,
                15.0F,
                4,
                120,
                2,
                15.0F,
                5
        ));
    }

    @Test
    void centralYieldOrderCarriesManualInterferenceIntoSubmit() {
        boolean interference =
                AutoMendAutomation26.detectsYieldedManualInterference(
                        true,
                        true,
                        4,
                        35.0F,
                        2
                );
        assertTrue(interference);
        assertEquals(
                20,
                AutoMendAutomation26.nextManualYieldTicks(
                        0,
                        true,
                        false,
                        false,
                        interference
                )
        );
        assertFalse(
                AutoMendAutomation26.detectsYieldedManualInterference(
                        true,
                        true,
                        2,
                        90.0F,
                        2
                )
        );
    }
}
