package dev.b2tclient.common.setting;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class StringSetting extends Setting<String> {
    private final int maxLength;
    private final Predicate<String> validator;

    public StringSetting(
            String id,
            String name,
            String description,
            String defaultValue,
            int maxLength
    ) {
        this(id, name, description, defaultValue, maxLength, value -> true, () -> true);
    }

    public StringSetting(
            String id,
            String name,
            String description,
            String defaultValue,
            int maxLength,
            Predicate<String> validator,
            BooleanSupplier visible
    ) {
        super(id, name, description, checkedDefault(defaultValue, maxLength, validator), visible);
        this.maxLength = maxLength;
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    private static String checkedDefault(String value, int maxLength, Predicate<String> validator) {
        Objects.requireNonNull(validator, "validator");
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        if (value.length() > maxLength || !validator.test(value)) {
            throw new IllegalArgumentException("Invalid default value");
        }
        return value;
    }

    @Override
    protected String normalize(String candidate) {
        if (candidate.length() > maxLength || !validator.test(candidate)) {
            throw new IllegalArgumentException("Invalid value for " + id());
        }
        return candidate;
    }

    @Override
    public void deserialize(String encoded) {
        set(encoded);
    }

    public int maxLength() {
        return maxLength;
    }
}
