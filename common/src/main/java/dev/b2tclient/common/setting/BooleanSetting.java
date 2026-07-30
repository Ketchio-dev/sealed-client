package dev.b2tclient.common.setting;

import java.util.function.BooleanSupplier;

public final class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String id, String name, String description, boolean defaultValue) {
        this(id, name, description, defaultValue, () -> true);
    }

    public BooleanSetting(
            String id,
            String name,
            String description,
            boolean defaultValue,
            BooleanSupplier visible
    ) {
        super(id, name, description, defaultValue, visible);
    }

    @Override
    protected Boolean normalize(Boolean candidate) {
        return candidate;
    }

    @Override
    public void deserialize(String encoded) {
        if (!"true".equalsIgnoreCase(encoded) && !"false".equalsIgnoreCase(encoded)) {
            throw new IllegalArgumentException("Expected true or false");
        }
        set(Boolean.parseBoolean(encoded));
    }
}
