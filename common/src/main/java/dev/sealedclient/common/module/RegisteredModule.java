package dev.sealedclient.common.module;

import dev.sealedclient.common.setting.Setting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class RegisteredModule {
    /** Sentinel for "this module has no keybind". */
    public static final int UNBOUND_KEY_CODE = -1;
    /** Highest key code GLFW reports (GLFW_KEY_LAST). */
    public static final int MAX_KEY_CODE = 348;

    private final ModuleDescriptor descriptor;
    private final List<Setting<?>> settings;
    private final Consumer<Boolean> stateChange;
    private boolean enabled;
    private boolean favorite;
    private int keyCode = UNBOUND_KEY_CODE;

    public RegisteredModule(ModuleDescriptor descriptor, List<Setting<?>> settings) {
        this(descriptor, settings, ignored -> { });
    }

    RegisteredModule(
            ModuleDescriptor descriptor,
            List<Setting<?>> settings,
            Consumer<Boolean> stateChange
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.settings = List.copyOf(settings);
        this.stateChange = Objects.requireNonNull(stateChange, "stateChange");
        ensureUniqueSettings(this.settings);
        enabled = descriptor.enabledByDefault();
    }

    private static void ensureUniqueSettings(List<Setting<?>> settings) {
        Map<String, Setting<?>> ids = new LinkedHashMap<>();
        for (Setting<?> setting : settings) {
            Setting<?> previous = ids.put(setting.id(), setting);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate setting id: " + setting.id());
            }
        }
    }

    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    public List<Setting<?>> settings() {
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled && !descriptor.available()) {
            throw new IllegalStateException(descriptor.name() + " is unavailable: " + descriptor.capabilityDetail());
        }
        if (this.enabled == enabled) {
            return;
        }
        boolean previous = this.enabled;
        this.enabled = enabled;
        try {
            stateChange.accept(enabled);
        } catch (RuntimeException exception) {
            this.enabled = previous;
            throw exception;
        }
    }

    public boolean toggle() {
        if (!descriptor.available()) {
            setEnabled(false);
            return false;
        }
        setEnabled(!enabled);
        return enabled;
    }

    public boolean favorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public int keyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = normalizeKeyCode(keyCode);
    }

    /**
     * Collapses every out-of-range or negative binding to the single unbound
     * value, so persisted garbage can never resolve to a real GLFW key.
     */
    public static int normalizeKeyCode(int keyCode) {
        if (keyCode < 0 || keyCode > MAX_KEY_CODE) {
            return UNBOUND_KEY_CODE;
        }
        return keyCode;
    }

    public ModuleSnapshot snapshot() {
        Map<String, String> encoded = new LinkedHashMap<>();
        for (Setting<?> setting : settings) {
            encoded.put(setting.id(), setting.serialize());
        }
        return new ModuleSnapshot(enabled, favorite, keyCode, encoded);
    }

    public void apply(ModuleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Setting<?>> byId = new LinkedHashMap<>();
        settings.forEach(setting -> byId.put(setting.id(), setting));
        snapshot.settings().forEach((id, value) -> {
            Setting<?> setting = byId.get(id);
            if (setting != null) {
                setting.deserialize(value);
            }
        });
        enabled = descriptor.available() && snapshot.enabled();
        favorite = snapshot.favorite();
        keyCode = normalizeKeyCode(snapshot.keyCode());
    }
}
