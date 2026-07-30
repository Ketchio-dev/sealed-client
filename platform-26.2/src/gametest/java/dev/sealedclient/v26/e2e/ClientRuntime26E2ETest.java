package dev.sealedclient.v26.e2e;

import dev.sealedclient.common.module.ModuleRisk;
import dev.sealedclient.common.module.RegisteredModule;
import dev.sealedclient.v26.SealedClient26;
import dev.sealedclient.v26.ClientRuntime26;
import dev.sealedclient.v26.config.PresetCatalog26;
import dev.sealedclient.v26.gui.ClientScreen26;
import dev.sealedclient.v26.gui.HudEditorScreen26;
import dev.sealedclient.v26.gui.PresetScreen26;
import dev.sealedclient.v26.gui.ProfileScreen26;
import dev.sealedclient.v26.hud.HudLayout26;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * End-to-end coverage of the 26.2 runtime inside a real client: module
 * registration, the ClickGUI and its sub-screens, keybind dispatch, the HUD
 * layout, presets, profiles, and the disconnect teardown.
 */
public final class ClientRuntime26E2ETest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientRuntime26 runtime = SealedClient26.runtime();
        assertCatalogue(runtime);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null);
            context.waitForScreen(null);

            TestInput input = context.getInput();
            input.resizeWindow(854, 480);
            context.runOnClient(client -> client.options.guiScale().set(1));
            context.waitTick();
            context.runOnClient(client -> KeyMapping.resetMapping());

            openAndCloseEveryScreen(context, input);
            keybindTogglesAModuleOnlyOutsideScreens(context, input, runtime);
            hudLayoutStaysOnScreenAtEveryTestedSize(context, input, runtime);
            presetPreviewIsSideEffectFree(runtime);
            profilesTrackTheActiveEntry(runtime);
            panicDisablesRiskyModulesAndReleasesPlatformState(context, runtime);
        }

        // Leaving the world runs the same disconnect path a server drop takes.
        context.waitTick();
        assertDisconnectCleared(runtime);
    }

    private static void assertCatalogue(ClientRuntime26 runtime) {
        int catalogued = runtime.modules().all().size();
        assertTrue(catalogued == 90, "Expected the 90-entry catalog, saw " + catalogued);
        assertTrue(runtime.profiles().all().size() >= 1,
                "A default profile must exist after initialize()");
    }

    private static void openAndCloseEveryScreen(
            ClientGameTestContext context,
            TestInput input
    ) {
        input.pressKey(GLFW.GLFW_KEY_P);
        context.waitForScreen(ClientScreen26.class);
        context.takeScreenshot("v26-clickgui");

        input.pressKey(GLFW.GLFW_KEY_H);
        context.waitForScreen(HudEditorScreen26.class);
        context.takeScreenshot("v26-hud-editor");
        input.pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitForScreen(null);

        input.pressKey(GLFW.GLFW_KEY_P);
        context.waitForScreen(ClientScreen26.class);
        input.pressKey(GLFW.GLFW_KEY_O);
        context.waitForScreen(ProfileScreen26.class);
        context.takeScreenshot("v26-profiles");
        input.pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitForScreen(null);

        input.pressKey(GLFW.GLFW_KEY_P);
        context.waitForScreen(ClientScreen26.class);
        input.pressKey(GLFW.GLFW_KEY_K);
        context.waitForScreen(PresetScreen26.class);
        context.takeScreenshot("v26-presets");
        input.pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitForScreen(null);
    }

    private static void keybindTogglesAModuleOnlyOutsideScreens(
            ClientGameTestContext context,
            TestInput input,
            ClientRuntime26 runtime
    ) {
        RegisteredModule clock = runtime.modules().find("clock").orElseThrow();
        boolean initial = clock.enabled();
        context.runOnClient(client -> clock.setKeyCode(GLFW.GLFW_KEY_J));
        context.waitTick();

        input.holdKeyFor(GLFW.GLFW_KEY_J, 2);
        context.waitTicks(3);
        assertTrue(clock.enabled() != initial,
                "A bound key must toggle its module while no screen is open");

        input.holdKeyFor(GLFW.GLFW_KEY_J, 2);
        context.waitTicks(3);
        assertTrue(clock.enabled() == initial, "A second press must toggle it back");

        input.pressKey(GLFW.GLFW_KEY_P);
        context.waitForScreen(ClientScreen26.class);
        boolean beforeTypingInScreen = clock.enabled();
        input.holdKeyFor(GLFW.GLFW_KEY_J, 2);
        context.waitTicks(3);
        assertTrue(clock.enabled() == beforeTypingInScreen,
                "Typing inside a screen must never toggle a module");
        input.pressKey(GLFW.GLFW_KEY_P);
        context.waitForScreen(null);

        context.runOnClient(client ->
                clock.setKeyCode(RegisteredModule.UNBOUND_KEY_CODE));
        context.waitTick();
    }

    private static void hudLayoutStaysOnScreenAtEveryTestedSize(
            ClientGameTestContext context,
            TestInput input,
            ClientRuntime26 runtime
    ) {
        HudLayout26 layout = runtime.hudLayout();
        layout.setAnchor(HudLayout26.Panel.INFO, new HudLayout26.Anchor(1.0, 1.0));
        layout.setAnchor(HudLayout26.Panel.ARRAY_LIST, new HudLayout26.Anchor(1.0, 1.0));

        for (int[] size : new int[][] {{854, 480}, {480, 320}, {400, 240}}) {
            input.resizeWindow(size[0], size[1]);
            context.waitTicks(2);
            context.runOnClient(client -> {
                int guiWidth = client.getWindow().getGuiScaledWidth();
                int guiHeight = client.getWindow().getGuiScaledHeight();
                for (HudLayout26.Panel panel : HudLayout26.Panel.values()) {
                    HudLayout26.Position position =
                            layout.resolve(panel, 140, 200, guiWidth, guiHeight);
                    assertTrue(position.x() >= 0 && position.y() >= 0,
                            panel + " must never resolve to a negative origin");
                    assertTrue(position.x() + 140 <= Math.max(140, guiWidth),
                            panel + " overflowed the screen width");
                    assertTrue(position.y() + 200 <= Math.max(200, guiHeight),
                            panel + " overflowed the screen height");
                }
            });
            context.takeScreenshot("v26-hud-" + size[0] + "x" + size[1]);
        }

        layout.reset();
        input.resizeWindow(854, 480);
        context.waitTick();
        assertTrue(layout.isDefault(), "reset() must restore every panel");
    }

    private static void presetPreviewIsSideEffectFree(ClientRuntime26 runtime) {
        var before = runtime.modules().snapshot();
        for (PresetCatalog26.Preset preset : PresetCatalog26.all()) {
            dev.sealedclient.v26.config.PresetApplication26.preview(preset, runtime.modules());
        }
        assertTrue(before.equals(runtime.modules().snapshot()),
                "Previewing presets must not change any module");
    }

    private static void profilesTrackTheActiveEntry(ClientRuntime26 runtime) {
        runtime.profiles().capture("e2e", "*", runtime.modules());
        assertTrue(runtime.profiles().find("e2e").isPresent(), "Captured profile must be findable");
        assertTrue(runtime.profiles().activate("e2e", runtime.modules()),
                "A just-captured profile must activate");
        assertTrue("e2e".equalsIgnoreCase(
                        runtime.profiles().active().orElseThrow().name()),
                "The activated profile must be reported as active");
    }

    private static void panicDisablesRiskyModulesAndReleasesPlatformState(
            ClientGameTestContext context,
            ClientRuntime26 runtime
    ) {
        RegisteredModule autoWalk = runtime.modules().find("auto_walk").orElseThrow();
        RegisteredModule freecam = runtime.modules().find("freecam").orElseThrow();
        RegisteredModule clock = runtime.modules().find("clock").orElseThrow();
        autoWalk.setEnabled(true);
        freecam.setEnabled(true);
        clock.setEnabled(true);

        context.runOnClient(client -> {
            boolean allowed = ClientSendMessageEvents.ALLOW_CHAT.invoker()
                    .allowSendChatMessage(";sealed panic");
            assertTrue(!allowed, "The local panic command must never reach server chat");
            assertTrue(!ClientSendMessageEvents.ALLOW_CHAT.invoker()
                            .allowSendChatMessage(";sealed list"),
                    "The Sealed list command must stay local");
            assertTrue(ClientSendMessageEvents.ALLOW_CHAT.invoker()
                            .allowSendChatMessage(";b2t list"),
                    "The retired prefix must no longer be recognized");
            assertTrue(!autoWalk.enabled() && !freecam.enabled(),
                    "Panic must disable movement modules");
            assertTrue(clock.enabled(), "Panic must preserve passive modules");
            assertTrue(runtime.modules().all().stream()
                            .filter(module -> module.descriptor().risk() != ModuleRisk.PASSIVE)
                            .noneMatch(RegisteredModule::enabled),
                    "Panic must disable every active non-passive module");
            assertTrue(runtime.baritone() == null || !runtime.baritone().movementReserved(),
                    "Panic must release Sealed-owned Baritone navigation");
        });
    }

    private static void assertDisconnectCleared(ClientRuntime26 runtime) {
        assertTrue(runtime.lastDeathLabel().isBlank(),
                "The death label must be cleared on disconnect");
        assertTrue(runtime.combatTarget().entityId(0)
                        == dev.sealedclient.v26.hud.CombatTargetBridge26.NO_TARGET,
                "The combat target must be cleared on disconnect");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
