package dev.sealedclient.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DurabilityPercentTest {
    @Test
    void fullAndEmptyAreTheExtremes() {
        assertEquals(100, DurabilityPercent.of(528, 528));
        assertEquals(0, DurabilityPercent.of(0, 528));
    }

    @Test
    void roundingIsToTheNearestPercent() {
        // Truncating instead would report 30 here, which is the divergence
        // this class exists to settle.
        assertEquals(31, DurabilityPercent.of(163, 528));
        assertEquals(30, DurabilityPercent.of(161, 528));
    }

    @Test
    void indestructibleItemsReadAsFull() {
        assertEquals(100, DurabilityPercent.of(0, 0));
        assertEquals(100, DurabilityPercent.of(5, -1));
    }

    @Test
    void outOfRangeInputsAreClampedRatherThanPropagated() {
        assertEquals(100, DurabilityPercent.of(9999, 528));
        assertEquals(0, DurabilityPercent.of(-50, 528));
    }

    @Test
    void damageAndRemainingAgree() {
        for (int damage = 0; damage <= 528; damage++) {
            assertEquals(
                    DurabilityPercent.of(528 - damage, 528),
                    DurabilityPercent.fromDamage(damage, 528),
                    "damage " + damage
            );
        }
    }

    @Test
    void everyValueStaysWithinZeroToHundred() {
        for (int maximum : new int[] {1, 59, 250, 528, 2031}) {
            for (int remaining = 0; remaining <= maximum; remaining++) {
                int percent = DurabilityPercent.of(remaining, maximum);
                assertTrue(
                        percent >= 0 && percent <= 100,
                        maximum + "/" + remaining + " gave " + percent
                );
            }
        }
    }

    @Test
    void thePercentNeverDecreasesAsDurabilityIncreases() {
        int previous = -1;
        for (int remaining = 0; remaining <= 528; remaining++) {
            int percent = DurabilityPercent.of(remaining, 528);
            assertTrue(percent >= previous, "went backwards at " + remaining);
            previous = percent;
        }
    }
}
