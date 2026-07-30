package dev.sealedclient.v26.config;

import dev.sealedclient.common.module.ModuleRegistry;
import dev.sealedclient.common.module.ModuleRisk;
import dev.sealedclient.common.module.ModuleSnapshot;
import dev.sealedclient.common.module.RegisteredModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Previews, applies, and undoes a built-in preset.
 *
 * <p>Nothing here mutates the registry until {@link #apply} is called, and even
 * then the change goes through {@link ModuleRegistry#apply} which validates the
 * whole snapshot and rolls back if any part of it is rejected. A preset can
 * therefore never leave the client half-configured.</p>
 *
 * <p>The single-step undo keeps the snapshot taken immediately before the last
 * successful apply, which is what makes trying a preset reversible.</p>
 */
public final class PresetApplication26 {
    private Map<String, ModuleSnapshot> undoSnapshot;
    private String undoLabel = "";

    /**
     * Describes what applying {@code preset} would change, without touching
     * anything.
     */
    public static Preview preview(
            PresetCatalog26.Preset preset,
            ModuleRegistry registry
    ) {
        List<Change> changes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        ModuleRisk highestEnabledRisk = null;

        for (PresetCatalog26.ModulePatch patch : preset.modules()) {
            RegisteredModule module = registry.find(patch.moduleId()).orElse(null);
            if (module == null) {
                skipped.add(patch.moduleId() + " (not in this build)");
                continue;
            }
            if (patch.enabled() && !module.descriptor().available()) {
                skipped.add(module.descriptor().name() + " (unavailable)");
                continue;
            }
            boolean togglesModule = module.enabled() != patch.enabled();
            List<String> settingChanges = changedSettings(module, patch);
            if (!togglesModule && settingChanges.isEmpty()) {
                continue;
            }
            if (patch.enabled()) {
                highestEnabledRisk = higher(highestEnabledRisk, module.descriptor().risk());
            }
            changes.add(new Change(
                    module.descriptor().name(),
                    module.enabled(),
                    patch.enabled(),
                    settingChanges
            ));
        }
        return new Preview(
                preset.id(),
                preset.name(),
                List.copyOf(changes),
                List.copyOf(skipped),
                highestEnabledRisk
        );
    }

    private static List<String> changedSettings(
            RegisteredModule module,
            PresetCatalog26.ModulePatch patch
    ) {
        if (patch.settings().isEmpty()) {
            return List.of();
        }
        Map<String, String> current = module.snapshot().settings();
        List<String> changed = new ArrayList<>();
        patch.settings().forEach((id, value) -> {
            String existing = current.get(id);
            // A setting the module does not declare is reported as changed
            // rather than hidden, so a stale preset entry is visible in the
            // preview instead of silently doing nothing.
            if (existing == null || !existing.equals(value)) {
                changed.add(id + ": " + (existing == null ? "(absent)" : existing) + " -> " + value);
            }
        });
        return List.copyOf(changed);
    }

    /**
     * Applies the preset. On any validation failure the registry is left
     * untouched and the undo slot is not overwritten.
     *
     * @return empty on success, otherwise the reason the preset was rejected
     */
    public Optional<String> apply(
            PresetCatalog26.Preset preset,
            ModuleRegistry registry
    ) {
        Map<String, ModuleSnapshot> before = registry.snapshot();
        Map<String, ModuleSnapshot> candidate = new LinkedHashMap<>(before);

        for (PresetCatalog26.ModulePatch patch : preset.modules()) {
            RegisteredModule module = registry.find(patch.moduleId()).orElse(null);
            if (module == null) {
                continue;
            }
            ModuleSnapshot existing = candidate.get(patch.moduleId());
            if (existing == null) {
                continue;
            }
            boolean enabled = patch.enabled() && module.descriptor().available();
            Map<String, String> settings = new LinkedHashMap<>(existing.settings());
            patch.settings().forEach((id, value) -> {
                if (settings.containsKey(id)) {
                    settings.put(id, value);
                }
            });
            candidate.put(patch.moduleId(), new ModuleSnapshot(
                    enabled,
                    existing.favorite(),
                    existing.keyCode(),
                    Map.copyOf(settings)
            ));
        }

        try {
            registry.apply(candidate);
        } catch (RuntimeException exception) {
            String reason = exception.getMessage();
            return Optional.of(reason == null || reason.isBlank()
                    ? "Preset was rejected and no change was made"
                    : reason);
        }
        undoSnapshot = before;
        undoLabel = preset.name();
        return Optional.empty();
    }

    /** True when a previously applied preset can still be reverted. */
    public boolean canUndo() {
        return undoSnapshot != null;
    }

    /** Name of the preset the pending undo would revert. */
    public String undoLabel() {
        return undoLabel;
    }

    /**
     * Restores the snapshot taken before the last successful apply. The undo
     * slot is consumed whether or not the restore succeeds, so a repeated undo
     * can never walk further back than one step.
     */
    public Optional<String> undo(ModuleRegistry registry) {
        if (undoSnapshot == null) {
            return Optional.of("Nothing to undo");
        }
        Map<String, ModuleSnapshot> restore = undoSnapshot;
        undoSnapshot = null;
        undoLabel = "";
        try {
            registry.apply(restore);
        } catch (RuntimeException exception) {
            return Optional.of("Undo failed; settings were left unchanged");
        }
        return Optional.empty();
    }

    /** Drops the undo slot, e.g. after a profile switch replaced everything. */
    public void clear() {
        undoSnapshot = null;
        undoLabel = "";
    }

    private static ModuleRisk higher(ModuleRisk current, ModuleRisk candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    /** One module the preset would change. */
    public record Change(
            String moduleName,
            boolean from,
            boolean to,
            List<String> settingChanges
    ) {
        public String summary() {
            String state = from == to
                    ? (to ? "ON" : "OFF")
                    : (from ? "ON" : "OFF") + " -> " + (to ? "ON" : "OFF");
            return moduleName + "  " + state
                    + (settingChanges.isEmpty() ? "" : "  +" + settingChanges.size() + " settings");
        }
    }

    /** What applying a preset would do. */
    public record Preview(
            String presetId,
            String presetName,
            List<Change> changes,
            List<String> skipped,
            ModuleRisk highestEnabledRisk
    ) {
        public boolean isNoOp() {
            return changes.isEmpty();
        }

        /**
         * True when the preset switches on a module whose risk warrants an
         * explicit confirmation before anything is changed.
         */
        public boolean requiresConfirmation() {
            return highestEnabledRisk == ModuleRisk.COMBAT
                    || highestEnabledRisk == ModuleRisk.PACKET
                    || highestEnabledRisk == ModuleRisk.MOVEMENT;
        }

        public String riskLabel() {
            return highestEnabledRisk == null ? "NONE" : highestEnabledRisk.name();
        }
    }
}
