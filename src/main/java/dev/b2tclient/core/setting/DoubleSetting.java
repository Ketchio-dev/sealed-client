package dev.b2tclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class DoubleSetting extends Setting<Double> {
    private final double minimum;
    private final double maximum;
    private final double step;

    public DoubleSetting(
            String id,
            String name,
            String description,
            double defaultValue,
            double minimum,
            double maximum,
            double step
    ) {
        super(id, name, description, defaultValue);
        if (minimum > maximum || step <= 0.0) {
            throw new IllegalArgumentException("Invalid double range");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        set(defaultValue);
    }

    @Override
    public void set(Double value) {
        double clamped = Math.max(minimum, Math.min(maximum, value));
        double snapped = Math.round(clamped / step) * step;
        super.set(Math.max(minimum, Math.min(maximum, snapped)));
    }

    public void increment(int direction) {
        set(get() + step * Integer.signum(direction));
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            set(element.getAsDouble());
        }
    }
}

