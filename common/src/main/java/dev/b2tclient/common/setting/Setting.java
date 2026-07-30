package dev.b2tclient.common.setting;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String id;
    private final String name;
    private final String description;
    private final T defaultValue;
    private final BooleanSupplier visible;
    private T value;

    protected Setting(
            String id,
            String name,
            String description,
            T defaultValue,
            BooleanSupplier visible
    ) {
        this.id = requireId(id);
        this.name = requireText(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.visible = Objects.requireNonNull(visible, "visible");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.value = this.defaultValue;
    }

    protected abstract T normalize(T candidate);

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final T defaultValue() {
        return defaultValue;
    }

    public final T value() {
        return value;
    }

    public final void set(T candidate) {
        value = normalize(Objects.requireNonNull(candidate, "candidate"));
    }

    public final void reset() {
        value = defaultValue;
    }

    public final boolean isVisible() {
        return visible.getAsBoolean();
    }

    public final String serialize() {
        return String.valueOf(value);
    }

    public abstract void deserialize(String encoded);

    protected static String requireId(String id) {
        String normalized = Objects.requireNonNull(id, "id").trim();
        if (!normalized.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid setting id: " + id);
        }
        return normalized;
    }

    protected static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
