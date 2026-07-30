package dev.b2tclient.v26;

import dev.b2tclient.common.module.ModuleCategory;
import dev.b2tclient.common.module.ModuleDescriptor;
import dev.b2tclient.common.module.ModuleRegistry;
import dev.b2tclient.common.module.ModuleRisk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientRuntime26ContainmentTest {
    @Test
    void aFailingSubsystemIsDisabledLoggedAndDoesNotStopLaterSubsystems() {
        ModuleRegistry modules = new ModuleRegistry();
        var failing = modules.register(new ModuleDescriptor(
                "failing_module",
                "Failing Module",
                "Fails during its tick",
                ModuleCategory.UTILITY,
                ModuleRisk.AUTOMATION,
                true
        ));
        List<String> calls = new ArrayList<>();
        List<ClientRuntime26.TickFailure> failures = new ArrayList<>();
        RuntimeException expected = new IllegalStateException("expected failure");

        ClientRuntime26.runContainedTickStep(
                modules,
                "failing-subsystem",
                List.of("failing_module"),
                () -> {
                    calls.add("failing");
                    throw expected;
                },
                failures::add
        );
        ClientRuntime26.runContainedTickStep(
                modules,
                "later-subsystem",
                List.of(),
                () -> calls.add("later"),
                failures::add
        );

        assertEquals(List.of("failing", "later"), calls);
        assertFalse(failing.enabled(), "the source module must be disabled");
        assertEquals(1, failures.size());
        assertEquals("failing-subsystem", failures.getFirst().subsystem());
        assertEquals("failing_module", failures.getFirst().moduleId());
        assertSame(expected, failures.getFirst().exception());
    }
}
