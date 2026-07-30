package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningDecisionEngine26Test {
    @Test
    void toolSelectionPrefersCorrectToolAndRejectsBreakingDurability() {
        int selected = MiningDecisionEngine26.selectBestTool(
                List.of(
                        tool(0, true, 5, true, 30.0F),
                        tool(1, true, 40, false, 20.0F),
                        tool(2, true, 40, true, 4.0F),
                        tool(3, true, 40, true, 4.0F)
                ),
                1,
                5
        );

        assertEquals(2, selected);
    }

    @Test
    void equalToolsKeepTheAlreadySelectedSlot() {
        assertEquals(5, MiningDecisionEngine26.selectBestTool(
                List.of(
                        tool(0, false, Integer.MAX_VALUE, false, 1.0F),
                        tool(5, false, Integer.MAX_VALUE, false, 1.0F),
                        tool(6, false, Integer.MAX_VALUE, false, 1.0F)
                ),
                5,
                5
        ));
    }

    @Test
    void toolScanNeverExaminesBeyondHotbarBound() {
        List<MiningDecisionEngine26.ToolCandidate> candidates =
                new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            candidates.add(tool(slot, false, 100, false, 1.0F));
        }
        candidates.add(tool(0, false, 100, true, 50.0F));

        assertEquals(4, MiningDecisionEngine26.selectBestTool(
                candidates,
                4,
                5
        ));
    }

    @Test
    void slotRestorationOnlyRestoresTheSlotActuallyOwned() {
        assertEquals(2, MiningDecisionEngine26.restorationSlot(2, 6, 6));
        assertEquals(-1, MiningDecisionEngine26.restorationSlot(2, 6, 4));
        assertTrue(MiningDecisionEngine26.selectionWasReplaced(2, 6, 4));
        assertFalse(MiningDecisionEngine26.selectionWasReplaced(2, 6, 6));
        assertTrue(MiningDecisionEngine26.selectionWasReplaced(2, -1, 4));
    }

    @Test
    void miningConfirmationRequiresExactTargetAndHasBoundedTimeout() {
        MiningDecisionEngine26.Confirmation confirmation =
                new MiningDecisionEngine26.Confirmation(4);
        assertTrue(confirmation.begin(9L, 10L));
        assertEquals(
                MiningDecisionEngine26.Confirmation.Result.NONE,
                confirmation.observe(10L, true, 11L)
        );
        assertEquals(
                MiningDecisionEngine26.Confirmation.Result.CONTINUE,
                confirmation.observe(9L, false, 13L)
        );
        assertEquals(
                MiningDecisionEngine26.Confirmation.Result.FAILED,
                confirmation.observe(9L, false, 14L)
        );

        confirmation.reset();
        assertTrue(confirmation.begin(9L, 20L));
        assertEquals(
                MiningDecisionEngine26.Confirmation.Result.CONFIRMED,
                confirmation.observe(9L, true, 21L)
        );
    }

    private static MiningDecisionEngine26.ToolCandidate tool(
            int slot,
            boolean damageable,
            int durability,
            boolean correct,
            float speed
    ) {
        return new MiningDecisionEngine26.ToolCandidate(
                slot,
                damageable,
                durability,
                correct,
                speed
        );
    }
}
