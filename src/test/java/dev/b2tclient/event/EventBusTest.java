package dev.b2tclient.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EventBusTest {
    @Test
    void postsListenersFromHighestPriorityToLowest() {
        EventBus eventBus = new EventBus();
        List<String> calls = new ArrayList<>();

        eventBus.subscribe(TestEvent.class, -10, event -> calls.add("low"));
        eventBus.subscribe(TestEvent.class, 100, event -> calls.add("high"));
        eventBus.subscribe(TestEvent.class, 0, event -> calls.add("normal"));

        TestEvent event = new TestEvent();

        assertEquals(event, eventBus.post(event));
        assertEquals(List.of("high", "normal", "low"), calls);
        assertEquals(3, eventBus.listenerCount(TestEvent.class));
    }

    @Test
    void listenerFailureDoesNotEscapeOrStopLowerPriorityDelivery() {
        EventBus eventBus = new EventBus();
        List<String> calls = new ArrayList<>();

        eventBus.subscribe(TestEvent.class, 100, event -> calls.add("high"));
        eventBus.subscribe(TestEvent.class, 50, event -> {
            calls.add("failing");
            throw new IllegalStateException("expected listener failure");
        });
        eventBus.subscribe(TestEvent.class, 0, event -> calls.add("normal"));
        eventBus.subscribe(TestEvent.class, -10, event -> calls.add("low"));

        assertDoesNotThrow(() -> eventBus.post(new TestEvent()));
        assertEquals(List.of("high", "failing", "normal", "low"), calls);
    }

    @Test
    void closingSubscriptionStopsFutureDeliveryAndIsIdempotent() {
        EventBus eventBus = new EventBus();
        List<TestEvent> received = new ArrayList<>();
        EventBus.Subscription subscription = eventBus.subscribe(TestEvent.class, received::add);

        TestEvent first = new TestEvent();
        eventBus.post(first);
        subscription.close();
        subscription.close();
        eventBus.post(new TestEvent());

        assertEquals(List.of(first), received);
        assertEquals(0, eventBus.listenerCount(TestEvent.class));
    }

    private static final class TestEvent {
    }
}
