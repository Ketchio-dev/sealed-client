package dev.sealedclient.v26.config;

import dev.sealedclient.common.module.ModuleRegistry;
import dev.sealedclient.common.module.ModuleRisk;
import dev.sealedclient.common.module.ModuleSnapshot;
import dev.sealedclient.v26.PlatformCapabilities26;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetApplication26Test {
    private final ModuleRegistry registry = PlatformCapabilities26.createRegistry();
    private final PresetApplication26 presets = new PresetApplication26();

    private static PresetCatalog26.Preset preset(String id) {
        return PresetCatalog26.find(id).orElseThrow();
    }

    @Test
    void everyCatalogueEntryHasAUniqueIdAndOnlyKnownModules() {
        Set<String> ids = new HashSet<>();
        assertEquals(3, PresetCatalog26.all().size());
        for (PresetCatalog26.Preset preset : PresetCatalog26.all()) {
            assertTrue(ids.add(preset.id()), preset.id());
            assertFalse(preset.modules().isEmpty(), preset.id());
            for (PresetCatalog26.ModulePatch patch : preset.modules()) {
                assertTrue(
                        registry.find(patch.moduleId()).isPresent(),
                        preset.id() + " references unknown module " + patch.moduleId()
                );
            }
        }
    }

    @Test
    void previewDoesNotMutateAnything() {
        Map<String, ModuleSnapshot> before = registry.snapshot();
        PresetApplication26.Preview preview =
                PresetApplication26.preview(preset(PresetCatalog26.CRYSTAL_PRACTICE_ID), registry);

        assertNotNull(preview);
        assertFalse(preview.isNoOp());
        assertEquals(before, registry.snapshot(), "previewing must be side-effect free");
    }

    @Test
    void aPresetThatEnablesCombatModulesRequiresConfirmation() {
        PresetApplication26.Preview crystal =
                PresetApplication26.preview(preset(PresetCatalog26.CRYSTAL_PRACTICE_ID), registry);
        assertTrue(crystal.requiresConfirmation());
        assertTrue(Set.of(ModuleRisk.COMBAT, ModuleRisk.MOVEMENT, ModuleRisk.PACKET)
                .contains(crystal.highestEnabledRisk()));
    }

    @Test
    void aHudOnlyPresetDoesNotRequireConfirmation() {
        PresetApplication26.Preview lowLag =
                PresetApplication26.preview(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        assertFalse(lowLag.requiresConfirmation(),
                "turning HUD readouts on must not demand a risk confirmation");
    }

    @Test
    void applyingThenUndoingRestoresTheExactPreviousState() {
        Map<String, ModuleSnapshot> before = registry.snapshot();

        assertEquals(Optional.empty(),
                presets.apply(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry));
        assertTrue(presets.canUndo());
        assertFalse(before.equals(registry.snapshot()), "apply must actually change state");

        assertEquals(Optional.empty(), presets.undo(registry));
        assertEquals(before, registry.snapshot(), "undo must restore byte-for-byte");
    }

    @Test
    void undoIsSingleStepAndIsConsumed() {
        presets.apply(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        Map<String, ModuleSnapshot> afterFirst = registry.snapshot();
        presets.apply(preset(PresetCatalog26.TRAVEL_SAFE_ID), registry);

        assertEquals(Optional.empty(), presets.undo(registry));
        assertEquals(afterFirst, registry.snapshot(),
                "undo walks back one preset, not to the very beginning");

        assertFalse(presets.canUndo());
        assertEquals(Optional.of("Nothing to undo"), presets.undo(registry));
        assertEquals(afterFirst, registry.snapshot(),
                "a second undo must not change anything further");
    }

    @Test
    void applyingIsIdempotentAndTheSecondPreviewIsANoOp() {
        presets.apply(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        Map<String, ModuleSnapshot> applied = registry.snapshot();

        PresetApplication26.Preview repeat =
                PresetApplication26.preview(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        assertTrue(repeat.isNoOp());

        presets.apply(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        assertEquals(applied, registry.snapshot());
    }

    @Test
    void presetSettingValuesSurviveTheRegistryValidation() {
        assertEquals(Optional.empty(),
                presets.apply(preset(PresetCatalog26.TRAVEL_SAFE_ID), registry));
        // A rejected value would have thrown and rolled back, leaving the undo
        // slot empty; a populated slot proves the whole snapshot validated.
        assertTrue(presets.canUndo());
    }

    @Test
    void clearDropsThePendingUndoForLifecycleTeardown() {
        presets.apply(preset(PresetCatalog26.LOW_LAG_UTILITY_ID), registry);
        assertTrue(presets.canUndo());

        presets.clear();
        assertFalse(presets.canUndo());
        assertEquals("", presets.undoLabel());
    }

    @Test
    void unavailableModulesAreListedAsSkippedRatherThanSilentlyDropped() {
        for (PresetCatalog26.Preset preset : PresetCatalog26.all()) {
            PresetApplication26.Preview preview =
                    PresetApplication26.preview(preset, registry);
            long unavailableEnabled = preset.enabledModuleIds().stream()
                    .map(registry::find)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .filter(module -> !module.descriptor().available())
                    .count();
            assertEquals(
                    unavailableEnabled,
                    preview.skipped().stream().filter(s -> s.endsWith("(unavailable)")).count(),
                    preset.id() + " must report every unavailable module it wanted to enable"
            );
        }
    }
}
