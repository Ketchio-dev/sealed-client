package dev.sealedclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Objects;

public final class StringSetting extends Setting<String> {
    private final int maximumLength;

    public StringSetting(
            String id,
            String name,
            String description,
            String defaultValue,
            int maximumLength
    ) {
        super(id, name, description, defaultValue);
        if (maximumLength < 1) {
            throw new IllegalArgumentException("Maximum length must be positive");
        }
        this.maximumLength = maximumLength;
        set(defaultValue);
    }

    @Override
    public void set(String value) {
        String requested = Objects.requireNonNull(value, "value");
        super.set(requested.length() <= maximumLength
                ? requested
                : requested.substring(0, maximumLength));
    }

    public int maximumLength() {
        return maximumLength;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            set(element.getAsString());
        }
    }
}
