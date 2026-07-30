package dev.b2tclient.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EventBus {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);
    private final Map<Class<?>, List<Listener<?>>> listeners = new HashMap<>();

    public synchronized <E> Subscription subscribe(
            Class<E> eventType,
            int priority,
            Consumer<E> consumer
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(consumer, "consumer");
        Listener<E> listener = new Listener<>(priority, consumer);
        listeners.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(listener);
        listeners.get(eventType).sort(Comparator.comparingInt(
                (Listener<?> registered) -> registered.priority()
        ).reversed());
        return () -> unsubscribe(eventType, listener);
    }

    public <E> Subscription subscribe(Class<E> eventType, Consumer<E> consumer) {
        return subscribe(eventType, 0, consumer);
    }

    public <E> E post(E event) {
        Objects.requireNonNull(event, "event");
        List<Listener<?>> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(listeners.getOrDefault(event.getClass(), List.of()));
        }
        for (Listener<?> untyped : snapshot) {
            @SuppressWarnings("unchecked")
            Listener<E> listener = (Listener<E>) untyped;
            try {
                listener.consumer().accept(event);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Event listener failed while handling {}; continuing delivery",
                        event.getClass().getName(),
                        exception
                );
            }
        }
        return event;
    }

    public synchronized void clear() {
        listeners.clear();
    }

    public synchronized int listenerCount(Class<?> eventType) {
        return listeners.getOrDefault(eventType, List.of()).size();
    }

    private synchronized void unsubscribe(Class<?> eventType, Listener<?> listener) {
        List<Listener<?>> registered = listeners.get(eventType);
        if (registered == null) {
            return;
        }
        registered.remove(listener);
        if (registered.isEmpty()) {
            listeners.remove(eventType);
        }
    }

    private record Listener<E>(int priority, Consumer<E> consumer) {
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
