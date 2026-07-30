package dev.sealedclient.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.StringListSetting;
import dev.sealedclient.core.setting.StringSetting;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoundationSettingTest {
    @Test
    void stringSettingTruncatesValuesAndRoundTripsJson() {
        StringSetting setting = new StringSetting(
                "label", "Label", "test", "default", 8
        );

        setting.set("123456789");
        assertEquals("12345678", setting.get());
        assertEquals("\"12345678\"", setting.toJson().toString());

        setting.fromJson(new JsonPrimitive("loaded value"));
        assertEquals("loaded v", setting.get());
        setting.fromJson(new JsonPrimitive(42));
        assertEquals("loaded v", setting.get());
        assertThrows(NullPointerException.class, () -> setting.set(null));
    }

    @Test
    void stringSettingRejectsAnInvalidMaximumLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StringSetting("label", "Label", "test", "", 0)
        );
    }

    @Test
    void colorSettingClampsChannelsAndAcceptsArgbAndRgbJson() {
        ColorSetting setting = new ColorSetting(
                "color", "Color", "test", 0x10203040
        );

        setting.setChannels(300, -1, 128, 256);
        assertEquals(255, setting.alpha());
        assertEquals(0, setting.red());
        assertEquals(128, setting.green());
        assertEquals(255, setting.blue());
        assertEquals("\"#FF0080FF\"", setting.toJson().toString());

        setting.fromJson(new JsonPrimitive("#112233"));
        assertEquals(0xff112233, setting.get());
        setting.fromJson(new JsonPrimitive("80123456"));
        assertEquals(0x80123456, setting.get());
        setting.fromJson(new JsonPrimitive(0x10203040));
        assertEquals(0x10203040, setting.get());
    }

    @Test
    void stringListNormalizesDeduplicatesAndRoundTripsJson() {
        StringListSetting setting = new StringListSetting(
                "targets",
                "Targets",
                "test",
                List.of(" ZOMBIE ", "skeleton", "zombie")
        );

        assertEquals(Set.of("zombie", "skeleton"), setting.get());
        assertTrue(setting.contains("ZOMBIE"));
        assertFalse(setting.contains(null));

        setting.add(" Creeper ");
        setting.add(" ");
        setting.remove("SKELETON");
        assertEquals(Set.of("zombie", "creeper"), setting.get());
        assertThrows(UnsupportedOperationException.class, () -> setting.get().add("spider"));

        JsonArray json = new JsonArray();
        json.add(" PLAYER ");
        json.add("player");
        json.add("");
        json.add(7);
        json.add(JsonNull.INSTANCE);
        setting.fromJson(json);

        assertEquals(Set.of("player"), setting.get());
        assertEquals(Set.of("player"), Set.copyOf(
                setting.toJson().getAsJsonArray().asList().stream()
                        .map(element -> element.getAsString())
                        .toList()
        ));
    }

    @Test
    void stringListEnforcesEntryCountAndLengthBudgets() {
        StringListSetting setting = new StringListSetting(
                "targets",
                "Targets",
                "test",
                List.of("first", "second", "third"),
                2,
                4
        );

        assertEquals(List.of("firs", "seco"), setting.get().stream().toList());
        setting.add("another");
        assertEquals(2, setting.get().size());
        assertEquals(4, setting.get().stream().mapToInt(String::length).max().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new StringListSetting("bad", "Bad", "test", List.of(), 0, 8)
        );
    }
}
