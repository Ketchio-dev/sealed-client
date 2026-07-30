package dev.b2tclient.common.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredModuleTest {
    @Test
    void setEnabledRestoresPreviousStateWhenStateChangeFails() {
        RegisteredModule module = module(false);

        assertThrows(IllegalStateException.class, () -> module.setEnabled(true));

        assertFalse(module.enabled());
    }

    @Test
    void toggleRestoresPreviousStateWhenStateChangeFails() {
        RegisteredModule module = module(true);

        assertThrows(IllegalStateException.class, module::toggle);

        assertTrue(module.enabled());
    }

    private static RegisteredModule module(boolean enabledByDefault) {
        return new RegisteredModule(
                new ModuleDescriptor(
                        "failure_test",
                        "Failure Test",
                        "Verifies rollback",
                        ModuleCategory.UTILITY,
                        ModuleRisk.AUTOMATION,
                        enabledByDefault
                ),
                List.of(),
                ignored -> {
                    throw new IllegalStateException("expected state change failure");
                }
        );
    }
}
