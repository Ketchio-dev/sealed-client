package dev.b2tclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String id, String name, String description, int defaultArgb) {
        super(id, name, description, defaultArgb);
    }

    public int alpha() {
        return get() >>> 24 & 0xff;
    }

    public int red() {
        return get() >>> 16 & 0xff;
    }

    public int green() {
        return get() >>> 8 & 0xff;
    }

    public int blue() {
        return get() & 0xff;
    }

    public void setChannels(int alpha, int red, int green, int blue) {
        set((clamp(alpha) << 24)
                | (clamp(red) << 16)
                | (clamp(green) << 8)
                | clamp(blue));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(String.format("#%08X", get()));
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            set(element.getAsInt());
            return;
        }

        String value = element.getAsString().trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 6) {
            value = "FF" + value;
        }
        if (value.length() == 8) {
            set((int) Long.parseLong(value, 16));
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
