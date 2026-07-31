package dev.sealedclient.common.hud;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts a queue status line out of a server's sidebar scoreboard.
 *
 * <p>Kept free of Minecraft types so the matching rules can be unit tested
 * directly instead of through a live scoreboard.</p>
 */
public final class QueueTextParser {
    private QueueTextParser() {
    }

    /**
     * @param sidebarTitle the sidebar objective's display name, or {@code null} if absent
     * @param lines        visible sidebar entries, top to bottom
     * @return the line to show in the HUD, already prefixed with {@code "Queue: "}
     */
    public static Optional<String> parse(String sidebarTitle, List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        if (sidebarTitle == null) {
            return Optional.empty();
        }

        String title = sidebarTitle.trim();
        boolean queueSidebar = mentionsQueue(title);
        String fallback = null;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (mentionsQueue(text)) {
                return Optional.of("Queue: " + text);
            }
            if (queueSidebar && fallback == null) {
                fallback = text;
            }
        }

        if (queueSidebar) {
            return Optional.of("Queue: " + (fallback == null ? title : fallback));
        }
        return Optional.empty();
    }

    /** Whether a scoreboard string looks like queue status text. */
    public static boolean mentionsQueue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("queue")
                || normalized.contains("position")
                || normalized.contains("place");
    }
}
