package dev.b2tclient.core.setting;

import com.google.gson.JsonElement;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String id;
    private final String name;
    private final String description;
    private final T defaultValue;
    private T value;
    private BooleanSupplier visibleWhen = () -> true;

    protected Setting(String id, String name, String description, T defaultValue) {
        this.id = requireId(id);
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.value = defaultValue;
    }

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final T get() {
        return value;
    }

    public final T defaultValue() {
        return defaultValue;
    }

    public void set(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public final void reset() {
        set(defaultValue);
    }

    public final boolean isVisible() {
        return visibleWhen.getAsBoolean();
    }

    public final Setting<T> visibleWhen(BooleanSupplier condition) {
        this.visibleWhen = Objects.requireNonNull(condition, "condition");
        return this;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);

    private static String requireId(String value) {
        Objects.requireNonNull(value, "id");
        if (!value.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid setting id: " + value);
        }
        return value;
    }
}
