package dev.b2tclient.config;

import dev.b2tclient.common.module.BuiltinModuleCatalog;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetCatalogBoundedWorkIntegrationTest {
    private static final int EXPECTED_PRESETS = 3;
    private static final int MAX_MODULE_PATCHES_PER_PRESET = 32;
    private static final int MAX_SETTINGS_PER_MODULE_PATCH = 12;

    @Test
    void presetsStayUniqueCatalogBackedAndBounded() {
        var presets = BuiltInPresetCatalog.all();
        Set<String> catalogIds = BuiltinModuleCatalog.entries().stream()
                .map(BuiltinModuleCatalog.CatalogEntry::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> presetIds = new HashSet<>();
        int inspectedPatches = 0;

        assertEquals(EXPECTED_PRESETS, presets.size());
        for (BuiltInPresetCatalog.Preset preset : presets) {
            assertTrue(presetIds.add(preset.id()), preset.id());
            assertFalse(preset.name().isBlank(), preset.id());
            assertFalse(preset.description().isBlank(), preset.id());
            assertTrue(
                    preset.modules().size() <= MAX_MODULE_PATCHES_PER_PRESET,
                    preset.id()
            );

            Set<String> patchedModules = new HashSet<>();
            for (BuiltInPresetCatalog.ModulePatch patch : preset.modules()) {
                inspectedPatches++;
                assertTrue(catalogIds.contains(patch.moduleId()), patch.moduleId());
                assertTrue(patchedModules.add(patch.moduleId()), patch.moduleId());
                assertTrue(
                        patch.settings().size() <= MAX_SETTINGS_PER_MODULE_PATCH,
                        patch.moduleId()
                );
                patch.settings().forEach((settingId, value) -> {
                    assertFalse(settingId.isBlank(), patch.moduleId());
                    assertTrue(value != null && !value.isJsonNull(), patch.moduleId());
                });
            }
        }

        assertEquals(
                presets.stream().mapToInt(preset -> preset.modules().size()).sum(),
                inspectedPatches
        );
    }
}
