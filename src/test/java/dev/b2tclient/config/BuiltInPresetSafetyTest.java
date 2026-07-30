package dev.b2tclient.config;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.WaypointManager;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInPresetSafetyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unconfirmedPresetCannotEnableRiskyModule() {
        FixtureModule safeWalk = new FixtureModule(
                "safe_walk",
                ModuleRisk.MOVEMENT,
                false
        );
        ConfigManager config = configWith(safeWalk);

        ConfigManager.PresetPreview preview =
                config.previewPreset(BuiltInPresetCatalog.TRAVEL_SAFE_ID);
        ConfigManager.PresetApplyResult result = config.applyPreset(
                BuiltInPresetCatalog.TRAVEL_SAFE_ID,
                null,
                false
        );

        assertEquals(1, preview.riskyEnableCount());
        assertTrue(result.successful());
        assertEquals(1, result.skippedRiskyEnables());
        assertFalse(result.changed());
        assertNull(result.failedModuleId());
        assertFalse(safeWalk.isEnabled());
        assertFalse(config.canUndoPreset());
    }

    @Test
    void failedEnableRollsBackEarlierModuleAndSettingChanges() {
        ConfiguredFixtureModule autoSprint = new ConfiguredFixtureModule(
                "auto_sprint",
                ModuleRisk.MOVEMENT,
                false
        );
        FailingFixtureModule safeWalk = new FailingFixtureModule(
                "safe_walk",
                ModuleRisk.MOVEMENT
        );
        ConfigManager config = configWith(autoSprint, safeWalk);

        ConfigManager.PresetApplyResult result = config.applyPreset(
                BuiltInPresetCatalog.TRAVEL_SAFE_ID,
                null,
                true
        );

        assertFalse(result.successful());
        assertFalse(result.changed());
        assertEquals("safe_walk", result.failedModuleId());
        assertFalse(autoSprint.isEnabled());
        assertFalse(autoSprint.requireForward.get());
        assertFalse(safeWalk.isEnabled());
        assertFalse(config.canUndoPreset());
    }

    @Test
    void successfulPresetCanBeUndoneExactlyOnce() {
        FixtureModule fps = new FixtureModule("fps", ModuleRisk.PASSIVE, false);
        FixtureModule playerEsp = new FixtureModule(
                "player_esp",
                ModuleRisk.PASSIVE,
                false
        );
        assertTrue(playerEsp.setEnabled(true, null));
        ConfigManager config = configWith(fps, playerEsp);

        ConfigManager.PresetApplyResult result = config.applyPreset(
                BuiltInPresetCatalog.LOW_LAG_UTILITY_ID,
                null,
                false
        );

        assertTrue(result.successful());
        assertTrue(result.changed());
        assertTrue(fps.isEnabled());
        assertFalse(playerEsp.isEnabled());
        assertTrue(config.canUndoPreset());

        assertTrue(config.undoPreset(null));
        assertFalse(fps.isEnabled());
        assertTrue(playerEsp.isEnabled());
        assertFalse(config.canUndoPreset());
        assertFalse(config.undoPreset(null));
    }

    private ConfigManager configWith(Module... modules) {
        ModuleManager moduleManager = new ModuleManager();
        for (Module module : modules) {
            moduleManager.register(module);
        }
        return new ConfigManager(
                moduleManager,
                new FriendManager(),
                new WaypointManager(),
                temporaryDirectory
        );
    }

    private static class FixtureModule extends Module {
        private FixtureModule(String id, ModuleRisk risk, boolean defaultEnabled) {
            super(id, id, "Preset test fixture", Category.UTILITY, defaultEnabled, risk);
        }
    }

    private static final class ConfiguredFixtureModule extends FixtureModule {
        private final BooleanSetting requireForward;

        private ConfiguredFixtureModule(
                String id,
                ModuleRisk risk,
                boolean defaultEnabled
        ) {
            super(id, risk, defaultEnabled);
            requireForward = addSetting(new BooleanSetting(
                    "require_forward",
                    "Require Forward",
                    "Preset test fixture",
                    false
            ));
        }
    }

    private static final class FailingFixtureModule extends FixtureModule {
        private FailingFixtureModule(String id, ModuleRisk risk) {
            super(id, risk, false);
        }

        @Override
        protected void onEnable(Minecraft minecraft) {
            throw new IllegalStateException("Expected preset activation failure");
        }
    }
}
