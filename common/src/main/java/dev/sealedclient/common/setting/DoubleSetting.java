package dev.sealedclient.common.setting;

import java.util.function.BooleanSupplier;

/**
 * Finite, bounded decimal setting with deterministic step snapping.
 */
public final class DoubleSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;

    public DoubleSetting(
            String id,
            String name,
            String description,
            double defaultValue,
            double min,
            double max,
            double step
    ) {
        this(id, name, description, defaultValue, min, max, step, () -> true);
    }

    public DoubleSetting(
            String id,
            String name,
            String description,
            double defaultValue,
            double min,
            double max,
            double step,
            BooleanSupplier visible
    ) {
        super(
                id,
                name,
                description,
                checkedAndSnappedDefault(defaultValue, min, max, step),
                visible
        );
        this.min = min;
        this.max = max;
        this.step = step;
    }

    private static double checkedAndSnappedDefault(
            double value,
            double min,
            double max,
            double step
    ) {
        validateRange(value, min, max, step);
        return snap(value, min, max, step);
    }

    private static void validateRange(
            double value,
            double min,
            double max,
            double step
    ) {
        if (!Double.isFinite(value)
                || !Double.isFinite(min)
                || !Double.isFinite(max)
                || !Double.isFinite(step)) {
            throw new IllegalArgumentException("Double setting values must be finite");
        }
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (step <= 0.0) {
            throw new IllegalArgumentException("step must be positive");
        }
    }

    @Override
    protected Double normalize(Double candidate) {
        if (!Double.isFinite(candidate)) {
            throw new IllegalArgumentException("Expected a finite decimal");
        }
        return snap(candidate, min, max, step);
    }

    private static double snap(
            double candidate,
            double min,
            double max,
            double step
    ) {
        double clamped = Math.max(min, Math.min(max, candidate));
        double snapped = min + Math.rint((clamped - min) / step) * step;
        double bounded = Math.max(min, Math.min(max, snapped));
        if (bounded == 0.0) {
            return 0.0;
        }
        return bounded;
    }

    @Override
    public void deserialize(String encoded) {
        set(Double.parseDouble(encoded.trim()));
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double step() {
        return step;
    }
}
