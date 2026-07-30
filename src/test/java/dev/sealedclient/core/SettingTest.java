package dev.sealedclient.core;

import com.google.gson.JsonPrimitive;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.EnumSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingTest {
    @Test
    void integerValuesAreClampedAndStepped() {
        IntegerSetting setting = new IntegerSetting(
                "range", "Range", "test", 5, 0, 10, 2
        );

        setting.increment(1);
        assertEquals(7, setting.get());
        setting.set(50);
        assertEquals(10, setting.get());
        setting.fromJson(new JsonPrimitive(-20));
        assertEquals(0, setting.get());
    }

    @Test
    void doublesAreClampedAndSnapped() {
        DoubleSetting setting = new DoubleSetting(
                "threshold", "Threshold", "test", 0.5, 0.0, 1.0, 0.05
        );

        setting.set(0.876);
        assertEquals(0.9, setting.get(), 0.000_001);
        setting.set(-5.0);
        assertEquals(0.0, setting.get(), 0.000_001);
    }

    @Test
    void enumJsonLoadingIsCaseInsensitive() {
        EnumSetting<Mode> setting = new EnumSetting<>(
                "mode", "Mode", "test", Mode.FIRST
        );

        setting.fromJson(new JsonPrimitive("second"));
        assertEquals(Mode.SECOND, setting.get());
        setting.cycle(1);
        assertEquals(Mode.FIRST, setting.get());
    }

    private enum Mode {
        FIRST,
        SECOND
    }
}
