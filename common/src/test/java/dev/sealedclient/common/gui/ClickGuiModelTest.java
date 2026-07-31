package dev.sealedclient.common.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClickGuiModelTest {
    private static final ClickGuiModel.Metrics METRICS = new ClickGuiModel.Metrics(
            720, 440, 260, 220, 30, 25, 112, 24, 19, 9
    );

    @Test
    void theWindowIsCentredOnATypicalScreen() {
        ClickGuiModel.Layout layout = ClickGuiModel.layout(1280, 720, METRICS);
        assertEquals(720, layout.right() - layout.left(), "capped at the maximum width");
        assertEquals(440, layout.bottom() - layout.top(), "capped at the maximum height");
        assertEquals(
                layout.left(),
                1280 - layout.right(),
                "equal margin on both sides"
        );
    }

    @Test
    void theWindowNeverSpillsOffASmallScreen() {
        // The minimum size is larger than this screen, so the clamp is what
        // keeps the window on it. Without the clamp the header and the close
        // button land outside the viewport and cannot be clicked.
        ClickGuiModel.Layout layout = ClickGuiModel.layout(200, 150, METRICS);
        assertTrue(layout.left() >= 0, "left edge on screen");
        assertTrue(layout.top() >= 0, "top edge on screen");
        assertTrue(layout.right() <= 200, "right edge on screen");
        assertTrue(layout.bottom() <= 150, "bottom edge on screen");
    }

    @Test
    void theContentBandStaysInsideTheWindow() {
        for (int width : new int[] {320, 800, 1920}) {
            for (int height : new int[] {240, 600, 1080}) {
                ClickGuiModel.Layout layout = ClickGuiModel.layout(width, height, METRICS);
                assertTrue(
                        layout.listTop() >= layout.top(),
                        width + "x" + height + ": list starts below the window top"
                );
                assertTrue(
                        layout.listBottom() <= layout.bottom(),
                        width + "x" + height + ": list ends above the window bottom"
                );
                assertTrue(
                        layout.mainLeft() >= layout.left(),
                        width + "x" + height + ": content starts inside the window"
                );
            }
        }
    }

    @Test
    void aViewportCanCollapseToNothingWithoutGoingNegative() {
        ClickGuiModel.Layout layout = ClickGuiModel.layout(120, 60, METRICS);
        assertTrue(layout.viewportHeight() >= 0, "a negative viewport would invert scrolling");
    }

    @Test
    void collapsedRowsCountOnlyTheirHeader() {
        int height = ClickGuiModel.contentHeight(
                List.of(
                        new ClickGuiModel.Row(false, 5),
                        new ClickGuiModel.Row(false, 3)
                ),
                METRICS
        );
        assertEquals(48, height, "two collapsed rows at 24 each");
    }

    @Test
    void expandedRowsAddTheirVisibleSettings() {
        int height = ClickGuiModel.contentHeight(
                List.of(new ClickGuiModel.Row(true, 2)),
                METRICS
        );
        assertEquals(24 + 19 + 3 + 38, height);
    }

    @Test
    void anExpandedRowWithNoSettingsStillReservesItsDescription() {
        int height = ClickGuiModel.contentHeight(
                List.of(new ClickGuiModel.Row(true, 0)),
                METRICS
        );
        assertEquals(24 + 19 + 3, height);
    }

    @Test
    void anEmptyOrMissingListHasNoHeight() {
        assertEquals(0, ClickGuiModel.contentHeight(List.of(), METRICS));
        assertEquals(0, ClickGuiModel.contentHeight(null, METRICS));
    }

    @Test
    void contentShorterThanTheViewportCannotScroll() {
        assertEquals(0, ClickGuiModel.maximumScroll(100, 400));
        assertEquals(0, ClickGuiModel.maximumScroll(0, 400));
    }

    @Test
    void scrollingStopsAtTheEndOfTheList() {
        int atEnd = ClickGuiModel.scrollBy(300, -99.0, 500, 200, 28.0);
        assertEquals(300, atEnd, "cannot scroll past the last row");
    }

    @Test
    void scrollingStopsAtTheTopOfTheList() {
        int atTop = ClickGuiModel.scrollBy(10, 99.0, 500, 200, 28.0);
        assertEquals(0, atTop, "cannot scroll above the first row");
    }

    @Test
    void anExistingOffsetIsPulledBackWhenTheListShrinks() {
        // Typing into the search box removes rows. An offset left over from the
        // longer list would show empty space below the last result.
        int clamped = ClickGuiModel.scrollBy(400, 0.0, 120, 200, 28.0);
        assertEquals(0, clamped);
    }

    @Test
    void aNonsenseWheelDeltaLeavesTheOffsetValid() {
        int clamped = ClickGuiModel.scrollBy(50, Double.NaN, 500, 200, 28.0);
        assertEquals(50, clamped);
        assertTrue(clamped <= ClickGuiModel.maximumScroll(500, 200));
    }

    @Test
    void collapsingAModulePullsTheViewBackIntoRange() {
        // Collapsing hides a module's settings, so the list can become shorter
        // than the current offset. Leaving the offset where it was scrolls the
        // whole list off the top and shows an empty panel.
        List<ClickGuiModel.Row> expanded = List.of(
                new ClickGuiModel.Row(true, 8),
                new ClickGuiModel.Row(false, 0),
                new ClickGuiModel.Row(false, 0)
        );
        List<ClickGuiModel.Row> collapsed = List.of(
                new ClickGuiModel.Row(false, 0),
                new ClickGuiModel.Row(false, 0),
                new ClickGuiModel.Row(false, 0)
        );
        int viewport = 200;
        int scrolledToEnd = ClickGuiModel.maximumScroll(
                ClickGuiModel.contentHeight(expanded, METRICS), viewport
        );
        assertTrue(scrolledToEnd > 0, "the expanded list must be scrollable");

        int afterCollapse = ClickGuiModel.clampScroll(
                scrolledToEnd,
                ClickGuiModel.contentHeight(collapsed, METRICS),
                viewport
        );
        assertEquals(0, afterCollapse, "the shorter list starts at the top again");
    }

    @Test
    void clampingLeavesAValidOffsetAlone() {
        assertEquals(60, ClickGuiModel.clampScroll(60, 500, 200));
    }

    @Test
    void clampingRejectsNegativeOffsets() {
        assertEquals(0, ClickGuiModel.clampScroll(-40, 500, 200));
    }

    @Test
    void anEmptyQueryMatchesEverything() {
        assertTrue(matches(""));
        assertTrue(matches("   "));
        assertTrue(matches(null));
    }

    @Test
    void searchMatchesEverySearchableField() {
        assertTrue(matches("Crystal"), "by name");
        assertTrue(matches("auto_crystal"), "by id");
        assertTrue(matches("places"), "by description");
        assertTrue(matches("HIGH"), "by risk");
    }

    @Test
    void searchIgnoresCaseAndSurroundingSpace() {
        assertTrue(matches("CRYSTAL"));
        assertTrue(matches("  crystal  "));
    }

    @Test
    void anUnrelatedQueryMatchesNothing() {
        assertFalse(matches("elytra"));
    }

    private static boolean matches(String query) {
        return ClickGuiModel.matchesQuery(
                query,
                "Auto Crystal",
                "auto_crystal",
                "Places and breaks end crystals",
                "HIGH"
        );
    }
}
