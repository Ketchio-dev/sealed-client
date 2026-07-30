package dev.b2tclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class IntegerSetting extends Setting<Integer> {
    private final int minimum;
    private final int maximum;
    private final int step;

    public IntegerSetting(
            String id,
            String name,
            String description,
            int defaultValue,
            int minimum,
            int maximum,
            int step
    ) {
        super(id, name, description, defaultValue);
        if (minimum > maximum || step <= 0) {
            throw new IllegalArgumentException("Invalid integer range");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        set(defaultValue);
    }

    @Override
    public void set(Integer value) {
        super.set(Math.max(minimum, Math.min(maximum, value)));
    }

    public void increment(int direction) {
        set(get() + step * Integer.signum(direction));
    }

    public int minimum() {
        return minimum;
    }

    public int maximum() {
        return maximum;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            set(element.getAsInt());
        }
    }
}

