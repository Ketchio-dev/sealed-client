package dev.b2tclient.hud;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Small, in-memory notification queue. It performs no I/O and is safe to call from
 * event handlers that are not on the render thread.
 */
public final class NotificationManager {
    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(4);
    private static final int MAXIMUM_NOTIFICATIONS = 8;
    private static final int MAXIMUM_MESSAGE_LENGTH = 180;

    private final ArrayDeque<Notification> notifications = new ArrayDeque<>();

    public void push(String message) {
        push(message, Type.INFO, DEFAULT_DURATION);
    }

    public void push(String message, Type type) {
        push(message, type, DEFAULT_DURATION);
    }

    public synchronized void push(String message, Type type, Duration duration) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(duration, "duration");
        if (message.isBlank() || duration.isZero() || duration.isNegative()) {
            return;
        }

        String safeMessage = message.strip();
        if (safeMessage.length() > MAXIMUM_MESSAGE_LENGTH) {
            safeMessage = safeMessage.substring(0, MAXIMUM_MESSAGE_LENGTH - 1) + "\u2026";
        }
        long now = System.nanoTime();
        long lifetime;
        try {
            lifetime = duration.toNanos();
        } catch (ArithmeticException exception) {
            lifetime = Long.MAX_VALUE - now;
        }
        long expiresAt = lifetime >= Long.MAX_VALUE - now
                ? Long.MAX_VALUE
                : now + lifetime;
        notifications.addLast(new Notification(safeMessage, type, expiresAt));
        while (notifications.size() > MAXIMUM_NOTIFICATIONS) {
            notifications.removeFirst();
        }
    }

    public synchronized List<Notification> active() {
        long now = System.nanoTime();
        notifications.removeIf(notification -> notification.expiresAtNanos() <= now);
        return List.copyOf(new ArrayList<>(notifications));
    }

    public synchronized void clear() {
        notifications.clear();
    }

    public enum Type {
        INFO(0xFF55D6BE),
        SUCCESS(0xFF72E09A),
        WARNING(0xFFFFC857),
        ERROR(0xFFFF6B6B);

        private final int color;

        Type(int color) {
            this.color = color;
        }

        public int color() {
            return color;
        }
    }

    public record Notification(String message, Type type, long expiresAtNanos) {
        public Notification {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(type, "type");
        }
    }
}
