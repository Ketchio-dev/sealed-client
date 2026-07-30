package dev.b2tclient.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.StringSetting;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.WaypointManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableProfileConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportContainsOnlyRegisteredModuleProfileData() {
        Fixture fixture = fixture();
        fixture.friends.add("local-friend", null);
        fixture.passive.label.set("portable");
        fixture.passive.setKeyCode(80);
        fixture.passive.setFavorite(true);

        JsonObject exported = JsonParser.parseString(
                fixture.config.exportActiveProfile()
        ).getAsJsonObject();

        assertEquals("b2t-profile", exported.get("format").getAsString());
        assertEquals(ConfigManager.PORTABLE_PROFILE_VERSION, exported.get("version").getAsInt());
        assertTrue(exported.getAsJsonObject("modules").has("passive"));
        assertFalse(exported.has("friends"));
        assertFalse(exported.has("waypoints"));
        assertFalse(exported.has("serverBindings"));
    }

    @Test
    void safeImportAppliesPassiveFieldsButRequiresConfirmationForCombat() {
        Fixture fixture = fixture();
        String payload = """
                {
                  "format": "b2t-profile",
                  "version": 1,
                  "modules": {
                    "passive": {
                      "enabled": true,
                      "key": 71,
                      "favorite": true,
                      "settings": {
                        "label": "shared",
                        "future_setting": 1
                      }
                    },
                    "risky": {
                      "enabled": true,
                      "settings": {}
                    },
                    "future_module": {
                      "enabled": true,
                      "settings": {}
                    }
                  }
                }
                """;

        ConfigManager.PortableProfilePreview preview =
                fixture.config.previewPortableProfile(payload);
        assertEquals(1, preview.riskyEnableCount());
        assertEquals(1, preview.unknownModuleCount());
        assertEquals(1, preview.unknownSettingCount());

        ConfigManager.PortableProfileApplyResult result =
                fixture.config.importPortableProfile(payload, null, false);

        assertTrue(result.successful());
        assertTrue(result.changed());
        assertEquals(1, result.skippedRiskyEnables());
        assertEquals(1, result.unknownModuleCount());
        assertEquals(1, result.unknownSettingCount());
        assertTrue(fixture.passive.isEnabled());
        assertEquals("shared", fixture.passive.label.get());
        assertEquals(71, fixture.passive.keyCode());
        assertTrue(fixture.passive.isFavorite());
        assertFalse(fixture.risky.isEnabled());
    }

    @Test
    void confirmedImportCanBeUndoneAsOneTransaction() {
        Fixture fixture = fixture();
        String payload = """
                {
                  "format": "b2t-profile",
                  "version": 1,
                  "modules": {
                    "risky": {
                      "enabled": true,
                      "settings": {"label": "armed"}
                    }
                  }
                }
                """;

        assertTrue(fixture.config.importPortableProfile(payload, null, true).successful());
        assertTrue(fixture.risky.isEnabled());
        assertEquals("armed", fixture.risky.label.get());
        assertTrue(fixture.config.canUndoPreset());

        assertTrue(fixture.config.undoPreset(null));
        assertFalse(fixture.risky.isEnabled());
        assertEquals("default", fixture.risky.label.get());
    }

    @Test
    void invalidSettingRollsBackEveryEarlierChange() {
        Fixture fixture = fixture();
        String payload = """
                {
                  "format": "b2t-profile",
                  "version": 1,
                  "modules": {
                    "passive": {
                      "enabled": true,
                      "key": 70,
                      "settings": {"label": "changed"}
                    },
                    "risky": {
                      "enabled": false,
                      "settings": {
                        "label": "this value exceeds the fixture setting maximum"
                      }
                    }
                  }
                }
                """;

        ConfigManager.PortableProfileApplyResult result =
                fixture.config.importPortableProfile(payload, null, true);

        assertFalse(result.successful());
        assertEquals("risky", result.failedModuleId());
        assertFalse(fixture.passive.isEnabled());
        assertEquals(-1, fixture.passive.keyCode());
        assertEquals("default", fixture.passive.label.get());
        assertFalse(fixture.config.canUndoPreset());
    }

    @Test
    void malformedOversizedAndDeepPayloadsFailBeforeMutation() {
        Fixture fixture = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.config.previewPortableProfile("{")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.config.previewPortableProfile(" ".repeat(256 * 1024 + 1))
        );
        String deeplyNested = "{\"format\":\"b2t-profile\",\"version\":1,\"modules\":"
                + "[".repeat(33) + "0" + "]".repeat(33) + "}";
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.config.previewPortableProfile(deeplyNested)
        );
        assertFalse(fixture.passive.isEnabled());
    }

    private Fixture fixture() {
        ModuleManager modules = new ModuleManager();
        TestModule passive = new TestModule("passive", ModuleRisk.PASSIVE);
        TestModule risky = new TestModule("risky", ModuleRisk.COMBAT);
        modules.register(passive);
        modules.register(risky);
        FriendManager friends = new FriendManager();
        ConfigManager config = new ConfigManager(
                modules,
                friends,
                new WaypointManager(),
                temporaryDirectory
        );
        return new Fixture(config, friends, passive, risky);
    }

    private record Fixture(
            ConfigManager config,
            FriendManager friends,
            TestModule passive,
            TestModule risky
    ) {
    }

    private static final class TestModule extends Module {
        private final StringSetting label;

        private TestModule(String id, ModuleRisk risk) {
            super(
                    id,
                    id,
                    "Portable profile fixture",
                    Category.UTILITY,
                    false,
                    risk
            );
            label = addSetting(new StringSetting(
                    "label",
                    "Label",
                    "Portable profile fixture",
                    "default",
                    16
            ));
        }
    }
}
