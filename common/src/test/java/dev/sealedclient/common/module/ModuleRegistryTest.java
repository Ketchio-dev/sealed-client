package dev.sealedclient.common.module;

import dev.sealedclient.common.setting.BooleanSetting;
import dev.sealedclient.common.setting.StringSetting;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {
    @Test
    void registryMaintainsIdentityCategoryAndState() {
        ModuleRegistry registry = new ModuleRegistry();
        RegisteredModule module = registry.register(
                new ModuleDescriptor("watermark", "Watermark", "Draw a label", ModuleCategory.HUD, ModuleRisk.PASSIVE, true),
                new BooleanSetting("shadow", "Shadow", "", true)
        );

        assertTrue(module.enabled());
        assertSame(module, registry.find("WATERMARK").orElseThrow());
        assertEquals(1, registry.byCategory().get(ModuleCategory.HUD).size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(module.descriptor()));
    }

    @Test
    void snapshotsRestoreModuleAndSettingState() {
        ModuleRegistry registry = new ModuleRegistry();
        BooleanSetting shadow = new BooleanSetting("shadow", "Shadow", "", true);
        RegisteredModule module = registry.register(
                new ModuleDescriptor("watermark", "Watermark", "Draw a label", ModuleCategory.HUD, ModuleRisk.PASSIVE, false),
                shadow
        );
        module.setEnabled(true);
        module.setFavorite(true);
        module.setKeyCode(80);
        shadow.set(false);
        var snapshot = registry.snapshot();

        module.setEnabled(false);
        module.setFavorite(false);
        shadow.set(true);
        registry.apply(snapshot);

        assertTrue(module.enabled());
        assertTrue(module.favorite());
        assertEquals(80, module.keyCode());
        assertFalse(shadow.value());
    }

    @Test
    void invalidSnapshotRollsBackEveryEarlierModuleMutation() {
        ModuleRegistry registry = new ModuleRegistry();
        RegisteredModule first = registry.register(
                new ModuleDescriptor(
                        "first",
                        "First",
                        "First module",
                        ModuleCategory.HUD,
                        ModuleRisk.PASSIVE,
                        false
                ),
                new BooleanSetting("flag", "Flag", "", false)
        );
        StringSetting token = new StringSetting(
                "token",
                "Token",
                "",
                "safe",
                16,
                value -> !"invalid".equals(value),
                () -> true
        );
        registry.register(
                new ModuleDescriptor(
                        "second",
                        "Second",
                        "Second module",
                        ModuleCategory.HUD,
                        ModuleRisk.PASSIVE,
                        false
                ),
                token
        );

        Map<String, ModuleSnapshot> candidate = new LinkedHashMap<>();
        candidate.put(
                "first",
                new ModuleSnapshot(
                        true,
                        true,
                        80,
                        Map.of("flag", "true")
                )
        );
        candidate.put(
                "second",
                new ModuleSnapshot(
                        true,
                        false,
                        -1,
                        Map.of("token", "invalid")
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.apply(candidate)
        );
        assertFalse(first.enabled());
        assertFalse(first.favorite());
        assertEquals(-1, first.keyCode());
        assertEquals("false", first.settings().getFirst().serialize());
        assertEquals("safe", token.value());
    }

    @Test
    void validationNeverCommitsEvenAValidCandidate() {
        ModuleRegistry registry = new ModuleRegistry();
        RegisteredModule module = registry.register(
                new ModuleDescriptor(
                        "module",
                        "Module",
                        "Module",
                        ModuleCategory.HUD,
                        ModuleRisk.PASSIVE,
                        false
                )
        );

        registry.validate(Map.of(
                "module",
                new ModuleSnapshot(true, true, 42, Map.of())
        ));

        assertFalse(module.enabled());
        assertFalse(module.favorite());
        assertEquals(-1, module.keyCode());
    }
}
