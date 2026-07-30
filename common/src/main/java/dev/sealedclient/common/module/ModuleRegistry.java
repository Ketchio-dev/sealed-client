package dev.sealedclient.common.module;

import dev.sealedclient.common.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ModuleRegistry {
    private final Map<String, RegisteredModule> modules = new LinkedHashMap<>();

    public RegisteredModule register(ModuleDescriptor descriptor, Setting<?>... settings) {
        RegisteredModule module = new RegisteredModule(descriptor, List.of(settings));
        if (modules.putIfAbsent(descriptor.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate module id: " + descriptor.id());
        }
        return module;
    }

    public Optional<RegisteredModule> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(modules.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public List<RegisteredModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    public Map<ModuleCategory, List<RegisteredModule>> byCategory() {
        Map<ModuleCategory, List<RegisteredModule>> grouped = new EnumMap<>(ModuleCategory.class);
        for (RegisteredModule module : modules.values()) {
            grouped.computeIfAbsent(module.descriptor().category(), ignored -> new ArrayList<>()).add(module);
        }
        grouped.replaceAll((ignored, value) -> List.copyOf(value));
        return Collections.unmodifiableMap(grouped);
    }

    public Map<String, ModuleSnapshot> snapshot() {
        Map<String, ModuleSnapshot> result = new LinkedHashMap<>();
        modules.forEach((id, module) -> result.put(id, module.snapshot()));
        return Collections.unmodifiableMap(result);
    }

    public void apply(Map<String, ModuleSnapshot> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, ModuleSnapshot> before = snapshot();
        try {
            applyUnchecked(snapshot);
        } catch (RuntimeException exception) {
            rollback(before, exception);
            throw exception;
        }
    }

    /**
     * Validates a snapshot against the live setting definitions without
     * leaving any module, setting, favorite, or keybind change behind.
     */
    public void validate(Map<String, ModuleSnapshot> candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Map<String, ModuleSnapshot> before = snapshot();
        try {
            applyUnchecked(candidate);
        } finally {
            applyUnchecked(before);
        }
    }

    private void applyUnchecked(Map<String, ModuleSnapshot> snapshot) {
        snapshot.forEach((id, state) ->
                find(id).ifPresent(module -> module.apply(state))
        );
    }

    private void rollback(
            Map<String, ModuleSnapshot> before,
            RuntimeException original
    ) {
        try {
            applyUnchecked(before);
        } catch (RuntimeException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
