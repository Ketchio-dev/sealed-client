package dev.sealedclient.performance;

import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.core.setting.StringListSetting;
import dev.sealedclient.core.setting.StringSetting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSettingInvariantTest {
    @Test
    void oversizedStringInputIsCappedBeforeItCanPersist() {
        int maximumLength = 128;
        StringSetting setting = new StringSetting(
                "label",
                "Label",
                "Performance invariant",
                "",
                maximumLength
        );

        setting.set("x".repeat(1_000_000));

        assertEquals(maximumLength, setting.get().length());
        assertEquals(maximumLength, setting.toJson().getAsString().length());
    }

    @Test
    void duplicateHeavyStringListsCollapseToTheirDistinctWorkingSet() {
        int distinctValues = 64;
        List<String> values = new ArrayList<>();
        for (int index = 0; index < 8_192; index++) {
            values.add(" TARGET-" + (index % distinctValues) + " ");
        }

        StringListSetting setting = new StringListSetting(
                "targets",
                "Targets",
                "Performance invariant",
                values
        );

        assertEquals(distinctValues, setting.get().size());
        assertEquals(distinctValues, setting.toJson().getAsJsonArray().size());
        for (int index = 0; index < distinctValues; index++) {
            assertTrue(setting.contains("TARGET-" + index));
        }
    }

    @Test
    void repeatedNumericUpdatesNeverEscapeConfiguredBounds() {
        IntegerSetting integerSetting = new IntegerSetting(
                "integer",
                "Integer",
                "Performance invariant",
                0,
                -100,
                100,
                1
        );
        DoubleSetting doubleSetting = new DoubleSetting(
                "double",
                "Double",
                "Performance invariant",
                0.0,
                -10.0,
                10.0,
                0.25
        );

        for (int index = 0; index < 100_000; index++) {
            integerSetting.set((index & 1) == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            doubleSetting.set((index & 1) == 0 ? Double.MAX_VALUE : -Double.MAX_VALUE);
        }

        assertEquals(-100, integerSetting.get());
        assertEquals(-10.0, doubleSetting.get());
    }
}
