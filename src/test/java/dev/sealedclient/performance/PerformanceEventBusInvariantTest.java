package dev.sealedclient.performance;

import dev.sealedclient.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceEventBusInvariantTest {
    private static final int LISTENER_BUDGET = 128;
    private static final int POST_BUDGET = 2_048;

    @Test
    void dispatchWorkIsExactlyBoundedByPostsAndActiveListeners() {
        EventBus eventBus = new EventBus();
        AtomicInteger deliveries = new AtomicInteger();
        List<EventBus.Subscription> subscriptions = new ArrayList<>();
        for (int index = 0; index < LISTENER_BUDGET; index++) {
            subscriptions.add(eventBus.subscribe(
                    BudgetEvent.class,
                    index % 5,
                    ignored -> deliveries.incrementAndGet()
            ));
        }

        BudgetEvent event = new BudgetEvent();
        for (int index = 0; index < POST_BUDGET; index++) {
            eventBus.post(event);
        }

        assertEquals(LISTENER_BUDGET, eventBus.listenerCount(BudgetEvent.class));
        assertEquals(LISTENER_BUDGET * POST_BUDGET, deliveries.get());

        subscriptions.subList(0, LISTENER_BUDGET / 2)
                .forEach(EventBus.Subscription::close);
        deliveries.set(0);
        for (int index = 0; index < POST_BUDGET; index++) {
            eventBus.post(event);
        }

        assertEquals(LISTENER_BUDGET / 2, eventBus.listenerCount(BudgetEvent.class));
        assertEquals((LISTENER_BUDGET / 2) * POST_BUDGET, deliveries.get());
    }

    @Test
    void repeatedSubscriptionChurnDoesNotRetainListeners() {
        EventBus eventBus = new EventBus();
        for (int index = 0; index < 10_000; index++) {
            EventBus.Subscription subscription = eventBus.subscribe(
                    BudgetEvent.class,
                    ignored -> {
                    }
            );
            subscription.close();
        }

        assertEquals(0, eventBus.listenerCount(BudgetEvent.class));
    }

    private record BudgetEvent() {
    }
}
