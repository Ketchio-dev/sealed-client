package dev.sealedclient.common.arbitration;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cross-arbiter reservation rules that used to be a hand-written chain
 * of {@code if} statements in the 26.2 runtime.
 *
 * <p>{@link #matchesTheHandWrittenRulesItReplaced()} is the important one: it
 * re-implements the original chain literally and asserts both agree across every
 * combination of combat and movement grants, which is what makes the extraction
 * safe rather than merely tidy.</p>
 */
class CrossArbiterReservationsTest {
    private enum Utility {
        USE,
        HOTBAR,
        INVENTORY,
        ROTATION
    }

    private enum Combat {
        ATTACK,
        USE,
        HOTBAR,
        INVENTORY,
        ROTATION,
        MOVEMENT
    }

    private enum Movement {
        HORIZONTAL,
        VERTICAL,
        KEY_INPUT,
        ROTATION,
        PACKET,
        HOTBAR,
        INVENTORY
    }

    private static final Set<String> SHARED =
            Set.of("USE", "HOTBAR", "INVENTORY", "ROTATION");

    private static Set<Utility> compute(
            Set<Combat> combat,
            Set<Movement> movement,
            boolean reserveEverything
    ) {
        return CrossArbiterReservations.compute(
                Utility.class, combat, movement, SHARED, reserveEverything
        );
    }

    @Test
    void combatGrantsOnSharedHardwareBlockUtility() {
        assertEquals(
                EnumSet.of(Utility.USE, Utility.ROTATION),
                compute(EnumSet.of(Combat.USE, Combat.ROTATION), EnumSet.noneOf(Movement.class), false)
        );
    }

    @Test
    void movementGrantsOnSharedHardwareBlockUtility() {
        assertEquals(
                EnumSet.of(Utility.HOTBAR, Utility.ROTATION),
                compute(EnumSet.noneOf(Combat.class), EnumSet.of(Movement.HOTBAR, Movement.ROTATION), false)
        );
    }

    @Test
    void channelsWithNoUtilityEquivalentAreIgnored() {
        // ATTACK and MOVEMENT exist only in combat; HORIZONTAL only in movement.
        assertTrue(compute(
                EnumSet.of(Combat.ATTACK, Combat.MOVEMENT),
                EnumSet.of(Movement.HORIZONTAL, Movement.VERTICAL, Movement.PACKET, Movement.KEY_INPUT),
                false
        ).isEmpty());
    }

    @Test
    void anExternalControllerReservesEveryUtilityChannel() {
        assertEquals(
                EnumSet.allOf(Utility.class),
                compute(EnumSet.noneOf(Combat.class), EnumSet.noneOf(Movement.class), true)
        );
    }

    @Test
    void reservationsFromBothSubsystemsAreUnioned() {
        assertEquals(
                EnumSet.of(Utility.USE, Utility.HOTBAR, Utility.ROTATION),
                compute(
                        EnumSet.of(Combat.USE, Combat.ROTATION),
                        EnumSet.of(Movement.HOTBAR, Movement.ROTATION),
                        false
                )
        );
    }

    @Test
    void matchesTheHandWrittenRulesItReplaced() {
        for (Set<Combat> combat : powerSet(Combat.class)) {
            for (Set<Movement> movement : powerSet(Movement.class)) {
                for (boolean external : new boolean[] {false, true}) {
                    assertEquals(
                            legacyRules(combat, movement, external),
                            compute(combat, movement, external),
                            "combat=" + combat + " movement=" + movement
                                    + " external=" + external
                    );
                }
            }
        }
    }

    /** The original {@code externalUtilityReservations()} chain, verbatim. */
    private static Set<Utility> legacyRules(
            Set<Combat> combatGrants,
            Set<Movement> movementGrants,
            boolean baritoneOwnsMovement
    ) {
        EnumSet<Utility> reserved = EnumSet.noneOf(Utility.class);
        if (baritoneOwnsMovement) {
            reserved.addAll(EnumSet.allOf(Utility.class));
        }
        if (combatGrants.contains(Combat.USE)) {
            reserved.add(Utility.USE);
        }
        if (combatGrants.contains(Combat.HOTBAR)) {
            reserved.add(Utility.HOTBAR);
        }
        if (combatGrants.contains(Combat.INVENTORY)) {
            reserved.add(Utility.INVENTORY);
        }
        if (combatGrants.contains(Combat.ROTATION)) {
            reserved.add(Utility.ROTATION);
        }
        if (movementGrants.contains(Movement.HOTBAR)) {
            reserved.add(Utility.HOTBAR);
        }
        if (movementGrants.contains(Movement.INVENTORY)) {
            reserved.add(Utility.INVENTORY);
        }
        if (movementGrants.contains(Movement.ROTATION)) {
            reserved.add(Utility.ROTATION);
        }
        return reserved;
    }

    private static <E extends Enum<E>> java.util.List<Set<E>> powerSet(Class<E> type) {
        E[] values = type.getEnumConstants();
        java.util.List<Set<E>> sets = new java.util.ArrayList<>();
        for (int mask = 0; mask < (1 << values.length); mask++) {
            EnumSet<E> set = EnumSet.noneOf(type);
            for (int i = 0; i < values.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    set.add(values[i]);
                }
            }
            sets.add(set);
        }
        return sets;
    }
}
