package dev.sealedclient.e2e;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.gui.ClickGuiScreen;
import dev.sealedclient.hud.HudEditorScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BootstrapAndGuiE2ETest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        assertBootstrapContract();

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null);
            context.waitForScreen(null);
            TestInput input = context.getInput();
            input.resizeWindow(854, 480);
            context.runOnClient(client -> client.options.guiScale().set(1));
            context.waitTick();

            try {
                KeyMapping openGuiKey = readField(
                        SealedClient.runtime(),
                        "openGuiKey",
                        KeyMapping.class
                );
                KeyMapping openHudEditorKey = readField(
                        SealedClient.runtime(),
                        "openHudEditorKey",
                        KeyMapping.class
                );
                E2EAssertions.assertFalse(
                        openGuiKey.isUnbound(),
                        "ClickGUI key binding must be registered"
                );
                E2EAssertions.assertTrue(
                        openGuiKey.matches(GLFW.GLFW_KEY_P, 0),
                        "ClickGUI must default to P"
                );
                E2EAssertions.assertTrue(
                        openHudEditorKey.matches(GLFW.GLFW_KEY_H, 0),
                        "HUD editor must default to H"
                );
                // The game-test API restores options immediately before this
                // entrypoint. Rebuild the vanilla lookup after that restore so
                // synthetic key events see Fabric-added bindings as normal.
                context.runOnClient(client -> KeyMapping.resetMapping());
                input.pressKey(openGuiKey);
                context.waitForScreen(ClickGuiScreen.class);

                ClickGuiScreen screen = currentGui(context);
                E2EAssertions.assertEquals(854, screen.width, "GUI must react to the test width");
                E2EAssertions.assertEquals(480, screen.height, "GUI must react to the test height");
                assertScreenshot(context.takeScreenshot("clickgui-desktop"), 854, 480);

                Module watermark = E2EAssertions.module("watermark");
                boolean initialWatermarkState = watermark.isEnabled();
                click(input, 204, 82, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                E2EAssertions.assertEquals(
                        !initialWatermarkState,
                        watermark.isEnabled(),
                        "Left-click must toggle a module"
                );
                click(input, 204, 82, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                E2EAssertions.assertEquals(
                        initialWatermarkState,
                        watermark.isEnabled(),
                        "Second click must restore the module state"
                );

                click(input, 204, 82, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                E2EAssertions.assertTrue(
                        expandedModules(screen).contains("watermark"),
                        "Right-click must expand module controls"
                );

                input.setCursorPos(300, 220);
                input.scroll(-8.0);
                context.waitTick();
                E2EAssertions.assertTrue(
                        scrollOffsets(screen).getOrDefault(Category.HUD, 0) > 0,
                        "HUD module list must scroll when its content exceeds the viewport"
                );

                click(input, 90, 90, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                E2EAssertions.assertEquals(
                        Category.COMBAT,
                        selectedCategory(screen),
                        "Sidebar click must switch categories"
                );

                input.resizeWindow(480, 320);
                context.waitTick();
                E2EAssertions.assertEquals(480, screen.width, "GUI must relayout at compact width");
                E2EAssertions.assertEquals(320, screen.height, "GUI must relayout at compact height");
                assertScreenshot(context.takeScreenshot("clickgui-compact"), 480, 320);

                input.pressKey(GLFW.GLFW_KEY_P);
                context.waitForScreen(null);

                input.pressKey(openHudEditorKey);
                context.waitForScreen(HudEditorScreen.class);
                assertScreenshot(context.takeScreenshot("hud-editor-compact"), 480, 320);
                input.pressKey(GLFW.GLFW_KEY_H);
                context.waitForScreen(null);
            } finally {
                if (context.computeOnClient(client -> client.screen != null)) {
                    context.setScreen(() -> null);
                    context.waitForScreen(null);
                }
            }
        }
    }

    private static void assertBootstrapContract() {
        var modules = SealedClient.runtime().modules();
        E2EAssertions.assertTrue(
                modules.all().size() >= 89,
                "The 2.0 production module matrix must load"
        );

        Set<String> ids = modules.all().stream()
                .map(Module::id)
                .collect(Collectors.toSet());
        E2EAssertions.assertEquals(
                modules.all().size(),
                ids.size(),
                "Module identifiers must be unique"
        );
        Set<String> required = Set.of(
                "auto_totem",
                "auto_crystal",
                "offhand",
                "kill_aura",
                "surround",
                "hole_fill",
                "auto_mine",
                "elytra_control",
                "safe_walk",
                "player_esp",
                "nametags",
                "storage_esp",
                "freecam",
                "xray",
                "chams",
                "new_chunks",
                "logout_spots",
                "stash_finder",
                "portal_coords",
                "replenish",
                "auto_reconnect",
                "auto_craft",
                "no_slow",
                "no_rotate",
                "array_list",
                "tick_rate"
        );
        E2EAssertions.assertTrue(ids.containsAll(required), "Required 2.0 modules must load");
        E2EAssertions.assertTrue(
                modules.inCategory(Category.HUD).size() >= 24,
                "Expanded HUD count"
        );
        E2EAssertions.assertTrue(
                modules.inCategory(Category.COMBAT).size() >= 20,
                "Expanded combat count"
        );
        E2EAssertions.assertTrue(
                modules.inCategory(Category.VISUAL).size() >= 14,
                "Expanded visual count"
        );
        E2EAssertions.assertTrue(
                modules.inCategory(Category.MOVEMENT).size() >= 14,
                "Expanded movement count"
        );
        E2EAssertions.assertTrue(
                modules.inCategory(Category.UTILITY).size() >= 17,
                "Expanded utility count"
        );
    }

    private static void click(TestInput input, double x, double y, int button) {
        input.setCursorPos(x, y);
        input.pressMouse(button);
    }

    private static ClickGuiScreen currentGui(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            if (client.screen instanceof ClickGuiScreen screen) {
                return screen;
            }
            throw new AssertionError("ClickGUI is not open");
        });
    }

    private static Category selectedCategory(ClickGuiScreen screen) {
        return readField(screen, "selectedCategory", Category.class);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> expandedModules(ClickGuiScreen screen) {
        return (Set<String>) readField(screen, "expandedModules", Set.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<Category, Integer> scrollOffsets(ClickGuiScreen screen) {
        return (Map<Category, Integer>) readField(screen, "scrollOffsets", Map.class);
    }

    private static <T> T readField(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect ClickGUI field " + name, exception);
        }
    }

    private static void assertScreenshot(Path path, int width, int height) {
        try {
            E2EAssertions.assertTrue(Files.isRegularFile(path), "Screenshot was not created");
            E2EAssertions.assertTrue(
                    Files.size(path) > 1_024,
                    "Screenshot is unexpectedly empty: " + path
            );
            byte[] header = Files.readAllBytes(path);
            E2EAssertions.assertTrue(
                    header.length >= 24
                            && header[0] == (byte) 0x89
                            && header[1] == 'P'
                            && header[2] == 'N'
                            && header[3] == 'G',
                    "Screenshot is not a PNG"
            );
            int actualWidth = readBigEndianInt(header, 16);
            int actualHeight = readBigEndianInt(header, 20);
            E2EAssertions.assertEquals(width, actualWidth, "Screenshot width");
            E2EAssertions.assertEquals(height, actualHeight, "Screenshot height");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not validate screenshot " + path, exception);
        }
    }

    private static int readBigEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xff) << 24
                | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8
                | data[offset + 3] & 0xff;
    }
}
