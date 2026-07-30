package dev.sealedclient.v26.gui;

import dev.sealedclient.common.module.ModuleCategory;
import dev.sealedclient.common.module.ModuleDescriptor;
import dev.sealedclient.common.module.ModuleRisk;
import dev.sealedclient.common.module.RegisteredModule;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientScreen26KeybindTest {
    private static RegisteredModule module(String id, int keyCode) {
        RegisteredModule module = new RegisteredModule(
                new ModuleDescriptor(
                        id,
                        id,
                        "test module",
                        ModuleCategory.UTILITY,
                        ModuleRisk.PASSIVE,
                        false
                ),
                List.of()
        );
        module.setKeyCode(keyCode);
        return module;
    }

    @Test
    void keyLabelsCoverLettersDigitsFunctionAndNumpadKeys() {
        assertEquals("None", ClientScreen26Model.keyLabel(RegisteredModule.UNBOUND_KEY_CODE));
        assertEquals("F", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_F));
        assertEquals("7", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_7));
        assertEquals("F5", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_F5));
        assertEquals("Numpad 3", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_KP_3));
        assertEquals("Space", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_SPACE));
        assertEquals("Page Up", ClientScreen26Model.keyLabel(GLFW.GLFW_KEY_PAGE_UP));
    }

    @Test
    void outOfRangeKeyCodesLabelAsUnbound() {
        assertEquals("None", ClientScreen26Model.keyLabel(-42));
        assertEquals("None", ClientScreen26Model.keyLabel(Integer.MAX_VALUE));
    }

    @Test
    void escapeClearsAndModifiersAreRejected() {
        assertEquals(
                ClientScreen26Model.KeybindCapture.CLEAR,
                ClientScreen26Model.classifyCapture(GLFW.GLFW_KEY_ESCAPE)
        );
        assertEquals(
                ClientScreen26Model.KeybindCapture.IGNORE,
                ClientScreen26Model.classifyCapture(GLFW.GLFW_KEY_LEFT_SHIFT)
        );
        assertEquals(
                ClientScreen26Model.KeybindCapture.IGNORE,
                ClientScreen26Model.classifyCapture(GLFW.GLFW_KEY_RIGHT_CONTROL)
        );
        assertEquals(
                ClientScreen26Model.KeybindCapture.IGNORE,
                ClientScreen26Model.classifyCapture(-1)
        );
        assertEquals(
                ClientScreen26Model.KeybindCapture.ASSIGN,
                ClientScreen26Model.classifyCapture(GLFW.GLFW_KEY_G)
        );
    }

    @Test
    void conflictsListEveryOtherModuleSharingTheKey() {
        RegisteredModule target = module("target", GLFW.GLFW_KEY_G);
        RegisteredModule sharing = module("sharing", GLFW.GLFW_KEY_G);
        RegisteredModule other = module("other", GLFW.GLFW_KEY_H);
        RegisteredModule unbound = module("unbound", RegisteredModule.UNBOUND_KEY_CODE);
        List<RegisteredModule> all = List.of(target, sharing, other, unbound);

        assertEquals(
                List.of("sharing"),
                ClientScreen26Model.conflictingModuleNames(all, target, GLFW.GLFW_KEY_G)
        );
        assertTrue(
                ClientScreen26Model.conflictingModuleNames(all, target, GLFW.GLFW_KEY_J).isEmpty()
        );
    }

    @Test
    void unboundKeyNeverReportsConflictsAgainstOtherUnboundModules() {
        RegisteredModule target = module("target", RegisteredModule.UNBOUND_KEY_CODE);
        RegisteredModule alsoUnbound = module("also", RegisteredModule.UNBOUND_KEY_CODE);
        assertTrue(
                ClientScreen26Model.conflictingModuleNames(
                        List.of(target, alsoUnbound),
                        target,
                        RegisteredModule.UNBOUND_KEY_CODE
                ).isEmpty()
        );
    }
}
