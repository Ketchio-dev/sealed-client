package dev.sealedclient.v26.automation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityAutomation26Test {
    @Test
    void foodSelectionPrefersNutritionAndRejectsUnsafeCandidates() {
        int selected = UtilityAutomation26.selectBestFood(List.of(
                new UtilityAutomation26.FoodCandidate(0, 5, true),
                new UtilityAutomation26.FoodCandidate(1, 20, false),
                new UtilityAutomation26.FoodCandidate(2, 8, true),
                new UtilityAutomation26.FoodCandidate(3, 8, true)
        ));

        assertEquals(2, selected);
        assertEquals(-1, UtilityAutomation26.selectBestFood(List.of(
                new UtilityAutomation26.FoodCandidate(1, 20, false)
        )));
    }

    @Test
    void toolSelectionPrefersCorrectSafeToolAndIsDeterministicOnTies() {
        int selected = UtilityAutomation26.selectBestTool(List.of(
                new UtilityAutomation26.ToolCandidate(0, true, false, 9.0F),
                new UtilityAutomation26.ToolCandidate(1, false, true, 30.0F),
                new UtilityAutomation26.ToolCandidate(2, true, true, 4.0F),
                new UtilityAutomation26.ToolCandidate(3, true, true, 4.0F)
        ), 0);

        assertEquals(2, selected);
        assertEquals(4, UtilityAutomation26.selectBestTool(List.of(
                new UtilityAutomation26.ToolCandidate(1, false, true, 30.0F),
                new UtilityAutomation26.ToolCandidate(2, false, true, 40.0F)
        ), 4));
    }

    @Test
    void reconnectScheduleIsDelayedBoundedAndResetsAfterConnection() {
        UtilityAutomation26.ReconnectSchedule schedule =
                new UtilityAutomation26.ReconnectSchedule();
        Object firstScreen = new Object();

        assertEquals(false, schedule.tick(firstScreen, 2, 2));
        assertEquals(false, schedule.tick(firstScreen, 2, 2));
        assertEquals(true, schedule.tick(firstScreen, 2, 2));
        assertEquals(1, schedule.attempts());

        Object secondScreen = new Object();
        assertEquals(true, schedule.tick(secondScreen, 0, 2));
        assertEquals(2, schedule.attempts());
        assertEquals(false, schedule.tick(new Object(), 0, 2));

        schedule.connected();
        assertEquals(0, schedule.attempts());
        assertEquals(true, schedule.tick(new Object(), 0, 2));
    }

    @Test
    void retainedSlotIsRestoredOnlyWhileTheUtilityStillOwnsIt() {
        assertTrue(UtilityAutomation26.stillOwnsAppliedSlot(4, 4));
        assertFalse(UtilityAutomation26.stillOwnsAppliedSlot(3, 4));
        assertFalse(UtilityAutomation26.stillOwnsAppliedSlot(4, -1));
        assertFalse(UtilityAutomation26.stillOwnsAppliedSlot(9, 9));
    }
}
