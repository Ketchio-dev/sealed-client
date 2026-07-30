package dev.sealedclient.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInPresetConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void catalogIdsAreStableAndUnknownIdsAreRejected() {
        ConfigManager config = configWith(new ModuleManager());

        assertEquals(
                List.of(
                        BuiltInPresetCatalog.LOW_LAG_UTILITY_ID,
                        BuiltInPresetCatalog.TRAVEL_SAFE_ID,
                        BuiltInPresetCatalog.CRYSTAL_PRACTICE_ID
                ),
                config.builtInPresets().stream().map(ConfigManager.PresetInfo::id).toList()
        );
        assertThrows(IllegalArgumentException.class, () -> config.previewPreset("missing"));
    }

    @Test
    void safeApplySkipsRiskyEnableAndUndoRestoresWholeProfile() throws IOException {
        ModuleManager modules = new ModuleManager();
        FixtureModule coordinates = new FixtureModule(
                "coordinates",
                ModuleRisk.PASSIVE,
                false
        );
        FixtureModule autoSprint = new FixtureModule(
                "auto_sprint",
                ModuleRisk.MOVEMENT,
                false
        );
        modules.register(coordinates);
        modules.register(autoSprint);
        ConfigManager config = configWith(modules);
        Files.writeString(
                config.configFile(),
                """
                {
                  "formatVersion": 2,
                  "activeProfile": "default",
                  "futureRoot": {"keep": true},
                  "profiles": {
                    "default": {
                      "profileExtension": "keep",
                      "modules": {
                        "auto_sprint": {
                          "enabled": false,
                          "key": 71,
                          "favorite": true,
                          "settings": {
                            "require_forward": false,
                            "future_setting": "keep"
                          }
                        },
                        "future_module": {
                          "enabled": true,
                          "settings": {"future": 42}
                        }
                      }
                    }
                  },
                  "serverBindings": {},
                  "friends": [],
                  "waypoints": []
                }
                """,
                StandardCharsets.UTF_8
        );
        config.load(null);

        ConfigManager.PresetPreview preview = config.previewPreset(
                BuiltInPresetCatalog.TRAVEL_SAFE_ID
        );
        assertTrue(preview.changes().stream().anyMatch(change ->
                change.moduleId().equals("auto_sprint")
                        && change.requiresRiskConfirmation()));
        assertTrue(preview.riskyEnableCount() >= 1);

        ConfigManager.PresetApplyResult safeResult = config.applyPreset(
                BuiltInPresetCatalog.TRAVEL_SAFE_ID,
                null,
                false
        );

        assertTrue(coordinates.isEnabled());
        assertFalse(autoSprint.isEnabled());
        assertTrue(autoSprint.option.get());
        assertEquals(71, autoSprint.keyCode());
        assertTrue(autoSprint.isFavorite());
        assertTrue(safeResult.skippedRiskyEnables() >= 1);
        assertTrue(config.canUndoPreset());
        assertUnknownFieldsPreserved(config.configFile());

        assertTrue(config.undoPreset(null));
        assertFalse(coordinates.isEnabled());
        assertFalse(autoSprint.isEnabled());
        assertFalse(autoSprint.option.get());
        assertEquals(71, autoSprint.keyCode());
        assertTrue(autoSprint.isFavorite());
        assertFalse(config.canUndoPreset());
        assertFalse(config.undoPreset(null));
        assertUnknownFieldsPreserved(config.configFile());
    }

    @Test
    void confirmedApplyEnablesRiskyModulesAndCanBeUndone() {
        ModuleManager modules = new ModuleManager();
        FixtureModule autoSprint = new FixtureModule(
                "auto_sprint",
                ModuleRisk.MOVEMENT,
                false
        );
        modules.register(autoSprint);
        ConfigManager config = configWith(modules);

        ConfigManager.PresetApplyResult result = config.applyPreset(
                BuiltInPresetCatalog.TRAVEL_SAFE_ID,
                null,
                true
        );

        assertTrue(result.changed());
        assertTrue(autoSprint.isEnabled());
        assertEquals(0, result.skippedRiskyEnables());
        assertTrue(config.undoPreset(null));
        assertFalse(autoSprint.isEnabled());
        assertFalse(autoSprint.option.get());
    }

    private ConfigManager configWith(ModuleManager modules) {
        return new ConfigManager(
                modules,
                new FriendManager(),
                new WaypointManager(),
                temporaryDirectory
        );
    }

    private static void assertUnknownFieldsPreserved(Path configFile) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(configFile, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertTrue(root.getAsJsonObject("futureRoot").get("keep").getAsBoolean());
        JsonObject profile = root.getAsJsonObject("profiles").getAsJsonObject("default");
        assertEquals("keep", profile.get("profileExtension").getAsString());
        JsonObject modules = profile.getAsJsonObject("modules");
        assertEquals(
                "keep",
                modules.getAsJsonObject("auto_sprint")
                        .getAsJsonObject("settings")
                        .get("future_setting")
                        .getAsString()
        );
        assertEquals(
                42,
                modules.getAsJsonObject("future_module")
                        .getAsJsonObject("settings")
                        .get("future")
                        .getAsInt()
        );
    }

    private static final class FixtureModule extends Module {
        private final BooleanSetting option;

        private FixtureModule(String id, ModuleRisk risk, boolean defaultValue) {
            super(id, id, "Preset fixture", Category.UTILITY, false, risk);
            option = addSetting(new BooleanSetting(
                    "require_forward",
                    "Require forward",
                    "Preset fixture",
                    defaultValue
            ));
        }
    }
}
