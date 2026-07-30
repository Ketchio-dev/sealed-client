package dev.sealedclient.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.core.setting.StringSetting;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void versionOneConfigurationMigratesToVersionTwo() throws IOException {
        ModuleManager modules = new ModuleManager();
        TestModule module = new TestModule();
        modules.register(module);
        ConfigManager config = new ConfigManager(
                modules,
                new FriendManager(),
                new WaypointManager(),
                temporaryDirectory
        );
        Files.writeString(
                config.configFile(),
                """
                {
                  "formatVersion": 1,
                  "modules": {
                    "test_module": {
                      "enabled": true,
                      "key": 65,
                      "settings": {
                        "label": "legacy"
                      }
                    }
                  }
                }
                """,
                StandardCharsets.UTF_8
        );

        config.load(null);

        assertEquals(ConfigManager.DEFAULT_PROFILE, config.activeProfile());
        assertEquals(Set.of(ConfigManager.DEFAULT_PROFILE), config.profileNames());
        assertTrue(module.isEnabled());
        assertEquals(65, module.keyCode());
        assertEquals("legacy", module.label.get());

        JsonObject migrated = JsonParser.parseString(
                Files.readString(config.configFile(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertEquals(ConfigManager.FORMAT_VERSION, migrated.get("formatVersion").getAsInt());
        assertTrue(migrated.getAsJsonObject("profiles")
                .getAsJsonObject(ConfigManager.DEFAULT_PROFILE)
                .getAsJsonObject("modules")
                .has("test_module"));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.json.bak")));
    }

    @Test
    void switchingProfilesCapturesAndRestoresIndependentModuleState() {
        ModuleManager modules = new ModuleManager();
        TestModule module = new TestModule();
        modules.register(module);
        ConfigManager config = new ConfigManager(
                modules,
                new FriendManager(),
                new WaypointManager(),
                temporaryDirectory
        );

        module.label.set("copied");
        module.setKeyCode(11);
        module.setFavorite(true);
        assertTrue(config.createProfile(" PvP ", true));

        module.label.set("default-state");
        module.setKeyCode(22);
        module.setFavorite(false);

        assertTrue(config.switchProfile("pvp", null));
        assertEquals("pvp", config.activeProfile());
        assertEquals("copied", module.label.get());
        assertEquals(11, module.keyCode());
        assertTrue(module.isFavorite());

        module.label.set("pvp-state");
        module.setKeyCode(33);
        assertTrue(config.switchProfile(ConfigManager.DEFAULT_PROFILE, null));
        assertEquals("default-state", module.label.get());
        assertEquals(22, module.keyCode());
        assertFalse(module.isFavorite());

        assertTrue(config.switchProfile("PVP", null));
        assertEquals("pvp-state", module.label.get());
        assertEquals(33, module.keyCode());
        assertFalse(config.switchProfile("pvp", null));
        assertFalse(config.switchProfile("missing", null));

        config.bindServer(" 2B2T.ORG ", "pvp");
        assertEquals("pvp", config.profileForServer("2b2t.org").orElseThrow());
    }

    @Test
    void oversizedConfigurationIsQuarantinedBeforeParsing() throws IOException {
        ModuleManager modules = new ModuleManager();
        TestModule module = new TestModule();
        modules.register(module);
        ConfigManager config = new ConfigManager(
                modules,
                new FriendManager(),
                new WaypointManager(),
                temporaryDirectory
        );
        Files.writeString(
                config.configFile(),
                " ".repeat(8 * 1024 * 1024 + 1),
                StandardCharsets.UTF_8
        );

        config.load(null);

        assertFalse(module.isEnabled());
        assertTrue(Files.size(config.configFile()) < 8 * 1024 * 1024);
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName()
                    .toString()
                    .startsWith("config.corrupt-")));
        }
    }

    private static final class TestModule extends Module {
        private final StringSetting label;

        private TestModule() {
            super(
                    "test_module",
                    "Test Module",
                    "Configuration fixture",
                    Category.UTILITY,
                    false
            );
            label = addSetting(new StringSetting(
                    "label",
                    "Label",
                    "Configuration fixture",
                    "default",
                    32
            ));
        }
    }
}
