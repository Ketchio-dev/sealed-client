package dev.b2tclient.common.setting;

import java.util.function.BooleanSupplier;

public final class IntegerSetting extends Setting<Integer> {
    private final int min;
    private final int max;
    private final int step;

    public IntegerSetting(
            String id,
            String name,
            String description,
            int defaultValue,
            int min,
            int max,
            int step
    ) {
        this(id, name, description, defaultValue, min, max, step, () -> true);
    }

    public IntegerSetting(
            String id,
            String name,
            String description,
            int defaultValue,
            int min,
            int max,
            int step,
            BooleanSupplier visible
    ) {
        super(id, name, description, checkedAndSnappedDefault(defaultValue, min, max, step), visible);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    private static int checkedAndSnappedDefault(int value, int min, int max, int step) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step must be positive");
        }
        return snap(value, min, max, step);
    }

    @Override
    protected Integer normalize(Integer candidate) {
        return snap(candidate, min, max, step);
    }

    private static int snap(int candidate, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, candidate));
        int offset = clamped - min;
        int snapped = min + Math.round((float) offset / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    @Override
    public void deserialize(String encoded) {
        set(Integer.parseInt(encoded.trim()));
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public int step() {
        return step;
    }
}
