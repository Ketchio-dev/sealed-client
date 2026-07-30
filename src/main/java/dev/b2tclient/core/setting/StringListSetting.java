package dev.b2tclient.core.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class StringListSetting extends Setting<Set<String>> {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 256;
    public static final int DEFAULT_MAXIMUM_ENTRY_LENGTH = 128;

    private final int maximumEntries;
    private final int maximumEntryLength;

    public StringListSetting(
            String id,
            String name,
            String description,
            Collection<String> defaultValue
    ) {
        this(
                id,
                name,
                description,
                defaultValue,
                DEFAULT_MAXIMUM_ENTRIES,
                DEFAULT_MAXIMUM_ENTRY_LENGTH
        );
    }

    public StringListSetting(
            String id,
            String name,
            String description,
            Collection<String> defaultValue,
            int maximumEntries,
            int maximumEntryLength
    ) {
        super(
                id,
                name,
                description,
                normalize(defaultValue, maximumEntries, maximumEntryLength)
        );
        if (maximumEntries < 1 || maximumEntryLength < 1) {
            throw new IllegalArgumentException("String-list limits must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.maximumEntryLength = maximumEntryLength;
    }

    @Override
    public void set(Set<String> value) {
        super.set(normalize(value, maximumEntries, maximumEntryLength));
    }

    public boolean contains(String value) {
        return value != null && get().contains(value.toLowerCase(Locale.ROOT));
    }

    public void add(String value) {
        LinkedHashSet<String> updated = new LinkedHashSet<>(get());
        String normalized = normalizeOne(value, maximumEntryLength);
        if (!normalized.isEmpty()) {
            updated.add(normalized);
            set(updated);
        }
    }

    public void remove(String value) {
        LinkedHashSet<String> updated = new LinkedHashSet<>(get());
        updated.remove(normalizeOne(value, maximumEntryLength));
        set(updated);
    }

    @Override
    public JsonElement toJson() {
        JsonArray result = new JsonArray();
        get().forEach(result::add);
        return result;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (values.size() >= maximumEntries) {
                break;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String normalized = normalizeOne(value.getAsString(), maximumEntryLength);
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
            }
        }
        set(values);
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    public int maximumEntryLength() {
        return maximumEntryLength;
    }

    private static Set<String> normalize(
            Collection<String> values,
            int maximumEntries,
            int maximumEntryLength
    ) {
        if (maximumEntries < 1 || maximumEntryLength < 1) {
            throw new IllegalArgumentException("String-list limits must be positive");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (normalized.size() >= maximumEntries) {
                    break;
                }
                String entry = normalizeOne(value, maximumEntryLength);
                if (!entry.isEmpty()) {
                    normalized.add(entry);
                }
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizeOne(String value, int maximumEntryLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= maximumEntryLength
                ? normalized
                : normalized.substring(0, maximumEntryLength);
    }
}
