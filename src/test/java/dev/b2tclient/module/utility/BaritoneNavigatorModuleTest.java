package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.integration.BaritoneNavigator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BaritoneNavigatorModuleTest {
    @Test
    void defaultsAreDisabledAutomationAndUnavailableIntegrationFailsClosed() {
        BaritoneNavigatorModule module = new BaritoneNavigatorModule(
                BaritoneNavigator.unavailable("", "not installed")
        );

        assertEquals(Category.UTILITY, module.category());
        assertEquals(ModuleRisk.AUTOMATION, module.risk());
        assertFalse(module.defaultEnabled());
        assertFalse(module.isEnabled());

        BooleanSetting confirmation = (BooleanSetting) module.settings().stream()
                .filter(setting -> setting.id().equals("confirm_target"))
                .findFirst()
                .orElseThrow();
        assertFalse(confirmation.get());

        assertFalse(module.setEnabled(true, null));
        assertFalse(module.isEnabled());
        assertFalse(confirmation.get());
    }
}
