package dev.b2tclient.v26.gui;

import dev.b2tclient.common.module.ModuleAvailability;
import dev.b2tclient.common.module.ModuleCategory;
import dev.b2tclient.common.module.ModuleDescriptor;
import dev.b2tclient.common.module.ModuleRisk;
import dev.b2tclient.common.module.RegisteredModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientScreen26ModelTest {
    @Test
    void filtersByCategoryAndSearchesMetadataCaseInsensitively() {
        RegisteredModule hud = module(
                "clock",
                "Clock",
                ModuleCategory.HUD,
                ModuleRisk.PASSIVE,
                ModuleAvailability.AVAILABLE
        );
        RegisteredModule combat = module(
                "auto_crystal",
                "Auto Crystal",
                ModuleCategory.COMBAT,
                ModuleRisk.COMBAT,
                ModuleAvailability.UNAVAILABLE
        );

        assertEquals(
                List.of(hud),
                ClientScreen26Model.filter(
                        List.of(hud, combat),
                        ClientScreen26Model.CategoryFilter.HUD,
                        ""
                )
        );
        assertEquals(
                List.of(combat),
                ClientScreen26Model.filter(
                        List.of(hud, combat),
                        ClientScreen26Model.CategoryFilter.ALL,
                        "CRYSTAL"
                )
        );
        assertEquals(
                List.of(combat),
                ClientScreen26Model.filter(
                        List.of(hud, combat),
                        ClientScreen26Model.CategoryFilter.ALL,
                        "unavailable"
                )
        );
    }

    @Test
    void clampsScrollAgainstFilteredContent() {
        assertEquals(0, ClientScreen26Model.clampScroll(-5, 20, 7));
        assertEquals(13, ClientScreen26Model.clampScroll(99, 20, 7));
        assertEquals(0, ClientScreen26Model.clampScroll(2, 2, 7));
    }

    @Test
    void boundedTextAppendNeverExceedsLimit() {
        assertEquals("abcdef", ClientScreen26Model.appendLimited("abc", "defghi", 6));
        assertEquals("abc", ClientScreen26Model.appendLimited("abc", "z", 3));
        assertEquals("", ClientScreen26Model.appendLimited("abc", "z", 0));
    }

    @Test
    void layoutKeepsSeparateNonEmptyColumnsAndFooterBoundary() {
        ClientScreen26Model.Columns columns = ClientScreen26Model.columns(
                854,
                480,
                8,
                6,
                66,
                14
        );

        assertTrue(columns.moduleLeft() < columns.moduleRight());
        assertTrue(columns.moduleRight() < columns.settingLeft());
        assertTrue(columns.settingLeft() < columns.settingRight());
        assertEquals(466, columns.bottom());
    }

    @Test
    void integerParsingRejectsMalformedAndClampsWithoutOverflow() {
        assertEquals(7, ClientScreen26Model.parseBoundedInteger("7", 0, 10).orElseThrow());
        assertEquals(10, ClientScreen26Model.parseBoundedInteger("9999999999", 0, 10).orElseThrow());
        assertEquals(-10, ClientScreen26Model.parseBoundedInteger("-9999999999", -10, 10).orElseThrow());
        assertTrue(ClientScreen26Model.parseBoundedInteger("-", -10, 10).isEmpty());
        assertTrue(ClientScreen26Model.parseBoundedInteger("5x", -10, 10).isEmpty());
    }

    @Test
    void decimalParsingRejectsNonFiniteAndClamps() {
        assertEquals(
                0.75,
                ClientScreen26Model.parseBoundedDouble("0.75", 0.0, 1.0).orElseThrow()
        );
        assertEquals(
                1.0,
                ClientScreen26Model.parseBoundedDouble("9e99", 0.0, 1.0).orElseThrow()
        );
        assertTrue(ClientScreen26Model.parseBoundedDouble("NaN", 0.0, 1.0).isEmpty());
        assertTrue(ClientScreen26Model.parseBoundedDouble("Infinity", 0.0, 1.0).isEmpty());
        assertTrue(ClientScreen26Model.parseBoundedDouble("-.", -1.0, 1.0).isEmpty());
    }

    @Test
    void baritoneRequiresAProviderEvenWhenCodeIsImplemented() {
        RegisteredModule baritone = module(
                "baritone_navigator",
                "Baritone Navigator",
                ModuleCategory.UTILITY,
                ModuleRisk.AUTOMATION,
                ModuleAvailability.AVAILABLE
        );
        RegisteredModule clock = module(
                "clock",
                "Clock",
                ModuleCategory.HUD,
                ModuleRisk.PASSIVE,
                ModuleAvailability.AVAILABLE
        );

        assertFalse(ClientScreen26Model.runtimeAvailable(baritone, false));
        assertTrue(ClientScreen26Model.runtimeAvailable(baritone, true));
        assertTrue(ClientScreen26Model.runtimeAvailable(clock, false));
    }

    private static RegisteredModule module(
            String id,
            String name,
            ModuleCategory category,
            ModuleRisk risk,
            ModuleAvailability availability
    ) {
        return new RegisteredModule(new ModuleDescriptor(
                id,
                name,
                "Description for " + name,
                category,
                risk,
                false,
                availability,
                availability == ModuleAvailability.AVAILABLE ? "Implemented" : "Unavailable"
        ), List.of());
    }
}
