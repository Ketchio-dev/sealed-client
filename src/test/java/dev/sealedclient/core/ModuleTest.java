package dev.sealedclient.core;

import dev.sealedclient.core.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleTest {
    @Test
    void failedEnableRollsBackState() {
        Module module = new FailingModule();

        assertFalse(module.setEnabled(true, null));
        assertFalse(module.isEnabled());
    }

    @Test
    void duplicateSettingIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, DuplicateSettingModule::new);
    }

    @Test
    void resetRestoresDefaults() {
        ResettableModule module = new ResettableModule();
        module.option.set(false);
        module.setKeyCode(32);
        module.setEnabled(true, null);

        module.reset(null);

        assertFalse(module.isEnabled());
        assertTrue(module.option.get());
    }

    private static final class FailingModule extends Module {
        private FailingModule() {
            super("failing", "Failing", "test", Category.UTILITY, false);
        }

        @Override
        protected void onEnable(Minecraft minecraft) {
            throw new IllegalStateException("expected test failure");
        }
    }

    private static final class DuplicateSettingModule extends Module {
        private DuplicateSettingModule() {
            super("duplicate", "Duplicate", "test", Category.UTILITY, false);
            addSetting(new BooleanSetting("same", "Same", "test", true));
            addSetting(new BooleanSetting("same", "Same", "test", false));
        }
    }

    private static final class ResettableModule extends Module {
        private final BooleanSetting option;

        private ResettableModule() {
            super("resettable", "Resettable", "test", Category.UTILITY, false);
            option = addSetting(new BooleanSetting("option", "Option", "test", true));
        }
    }
}
