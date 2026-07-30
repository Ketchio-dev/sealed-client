package dev.b2tclient.common.setting;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SettingTest {
    @Test
    void integerValuesAreClampedAndSnapped() {
        IntegerSetting setting = new IntegerSetting("scale", "Scale", "", 100, 50, 200, 25);
        IntegerSetting snappedDefault = new IntegerSetting("offset", "Offset", "", 187, 50, 200, 25);

        assertEquals(175, snappedDefault.defaultValue());
        setting.set(187);
        assertEquals(175, setting.value());
        setting.set(999);
        assertEquals(200, setting.value());
        setting.deserialize("51");
        assertEquals(50, setting.value());
    }

    @Test
    void decimalValuesAreFiniteClampedAndSnapped() {
        DoubleSetting setting = new DoubleSetting(
                "range",
                "Range",
                "",
                3.5,
                2.0,
                6.0,
                0.1
        );

        setting.set(3.56);
        assertEquals(3.6, setting.value(), 1.0E-9);
        setting.deserialize("99");
        assertEquals(6.0, setting.value(), 1.0E-9);
        assertThrows(IllegalArgumentException.class, () -> setting.set(Double.NaN));
        assertThrows(IllegalArgumentException.class, () ->
                new DoubleSetting("bad", "Bad", "", 1.0, 0.0, 2.0, Double.NaN)
        );
    }

    @Test
    void settingsRoundTripAndHonorVisibility() {
        AtomicBoolean visible = new AtomicBoolean(false);
        BooleanSetting setting = new BooleanSetting("active", "Active", "", true, visible::get);

        assertFalse(setting.isVisible());
        setting.deserialize("false");
        assertFalse(setting.value());
        visible.set(true);
        assertTrue(setting.isVisible());
        assertThrows(IllegalArgumentException.class, () -> setting.deserialize("sometimes"));
    }

    @Test
    void stringValidationIsAppliedAfterConstruction() {
        StringSetting setting = new StringSetting(
                "label", "Label", "", "B2T", 8, value -> !value.isBlank(), () -> true
        );

        setting.set("Client");
        assertEquals("Client", setting.value());
        assertThrows(IllegalArgumentException.class, () -> setting.set(""));
        assertThrows(IllegalArgumentException.class, () -> setting.set("too-long!"));
    }
}
