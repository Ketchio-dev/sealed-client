package dev.b2tclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class EnumSetting<E extends Enum<E>> extends Setting<E> {
    private final E[] values;

    public EnumSetting(String id, String name, String description, E defaultValue) {
        super(id, name, description, defaultValue);
        values = defaultValue.getDeclaringClass().getEnumConstants();
    }

    public void cycle(int direction) {
        int next = Math.floorMod(get().ordinal() + Integer.signum(direction), values.length);
        set(values[next]);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get().name());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        String requested = element.getAsString();
        for (E value : values) {
            if (value.name().equalsIgnoreCase(requested)) {
                set(value);
                return;
            }
        }
    }
}

