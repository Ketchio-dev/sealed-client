package dev.sealedclient.common.gui;

import java.util.List;
import java.util.Locale;

/**
 * Window geometry, scrolling and search for the module browser.
 *
 * <p>Everything here is arithmetic over screen sizes and item counts, so it
 * can be tested without a running game. The screen classes keep the drawing
 * and the input plumbing.</p>
 */
public final class ClickGuiModel {
    /** Metrics the layout is derived from, in scaled screen pixels. */
    public record Metrics(
            int maximumWidth,
            int maximumHeight,
            int minimumWidth,
            int minimumHeight,
            int headerHeight,
            int sectionHeaderHeight,
            int sidebarWidth,
            int moduleRowHeight,
            int settingRowHeight,
            int padding
    ) {
        public Metrics {
            if (maximumWidth <= 0
                    || maximumHeight <= 0
                    || minimumWidth <= 0
                    || minimumHeight <= 0
                    || moduleRowHeight <= 0
                    || settingRowHeight <= 0) {
                throw new IllegalArgumentException("Invalid click GUI metrics");
            }
        }
    }

    /** Resolved window rectangle and the content bands inside it. */
    public record Layout(
            int left,
            int top,
            int right,
            int bottom,
            int mainLeft,
            int contentTop,
            int listTop,
            int listBottom
    ) {
        public int viewportHeight() {
            return Math.max(0, listBottom - listTop);
        }
    }

    private ClickGuiModel() {
    }

    /**
     * Centres the window inside the screen, clamped so it never spills outside
     * even when the screen is smaller than the requested minimum.
     */
    public static Layout layout(int screenWidth, int screenHeight, Metrics metrics) {
        int windowWidth = Math.max(
                metrics.minimumWidth(),
                Math.min(metrics.maximumWidth(), screenWidth - 12)
        );
        int windowHeight = Math.max(
                metrics.minimumHeight(),
                Math.min(metrics.maximumHeight(), screenHeight - 36)
        );
        windowWidth = Math.min(windowWidth, screenWidth);
        windowHeight = Math.min(windowHeight, screenHeight);
        int left = Math.max(0, (screenWidth - windowWidth) / 2);
        int top = Math.max(0, (screenHeight - windowHeight) / 2 - 4);
        int right = Math.min(screenWidth, left + windowWidth);
        int bottom = Math.min(screenHeight, top + windowHeight);
        int mainLeft = Math.min(right - 120, left + metrics.sidebarWidth());
        int contentTop = top + metrics.headerHeight();
        int listTop = contentTop + metrics.sectionHeaderHeight();
        int listBottom = bottom - metrics.padding();
        return new Layout(
                left, top, right, bottom, mainLeft, contentTop, listTop, listBottom
        );
    }

    /**
     * Total pixel height of the module list, counting the settings of expanded
     * rows.
     *
     * @param rows one entry per module, in display order
     */
    public static int contentHeight(List<Row> rows, Metrics metrics) {
        if (rows == null) {
            return 0;
        }
        int height = 0;
        for (Row row : rows) {
            height += metrics.moduleRowHeight();
            if (!row.expanded()) {
                continue;
            }
            height += metrics.settingRowHeight() + 3;
            height += Math.max(0, row.visibleSettingCount()) * metrics.settingRowHeight();
        }
        return height;
    }

    /** Largest scroll offset that still shows content, never negative. */
    public static int maximumScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - Math.max(0, viewportHeight));
    }

    /**
     * Forces an existing offset back into range for the current list.
     *
     * <p>Needed whenever the list gets shorter without the user scrolling:
     * collapsing a module, or filtering it away. An offset left over from the
     * longer list scrolls the remaining rows off the top and shows an empty
     * panel.</p>
     */
    public static int clampScroll(int offset, int contentHeight, int viewportHeight) {
        return Math.max(0, Math.min(maximumScroll(contentHeight, viewportHeight), offset));
    }

    /**
     * Applies a scroll delta and clamps it into range.
     *
     * <p>Clamping here rather than at each call site is what stops the list
     * scrolling past its end or above its start.</p>
     */
    public static int scrollBy(
            int currentOffset,
            double wheelDelta,
            int contentHeight,
            int viewportHeight,
            double pixelsPerNotch
    ) {
        if (!Double.isFinite(wheelDelta)) {
            return clampScroll(currentOffset, contentHeight, viewportHeight);
        }
        int requested = currentOffset - (int) Math.round(wheelDelta * pixelsPerNotch);
        return clampScroll(requested, contentHeight, viewportHeight);
    }

    /**
     * Whether a module matches a search query.
     *
     * <p>Matching is case-insensitive across every field given, so a user who
     * remembers only the risk level or a word from the description can still
     * find the module. The caller decides which fields are searchable, which
     * differs by platform.</p>
     */
    public static boolean matchesQuery(String query, String... fields) {
        if (query == null) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        for (String field : fields) {
            if (contains(field, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** A module row as far as height arithmetic is concerned. */
    public record Row(boolean expanded, int visibleSettingCount) {
    }
}
