package dev.sealedclient.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sealedclient.SealedClient;
import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.setting.IntegerSetting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ConfigRecoveryE2ETest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ConfigManager config = SealedClient.runtime().config();
        Module clock = E2EAssertions.module("clock");
        IntegerSetting maximum = (IntegerSetting) E2EAssertions.setting(
                E2EAssertions.module("effects"),
                "maximum"
        );

        try {
            context.runOnClient(client -> {
                clock.setEnabled(true, client);
                clock.setKeyCode(GLFW.GLFW_KEY_K);
                maximum.set(7);
                config.save();
                config.load(client);
            });

            assertSerializedContract(config.configFile());
            Path backup = config.configFile().resolveSibling("config.json.bak");
            E2EAssertions.assertTrue(Files.isRegularFile(backup), "Config backup must be created");

            context.runOnClient(client -> {
                clock.setEnabled(false, client);
                clock.setKeyCode(GLFW.GLFW_KEY_UNKNOWN);
                maximum.set(1);
                config.load(client);
            });
            assertRestoredValues(clock, maximum);

            long corruptBefore = corruptConfigCount(config.configFile().getParent());
            Files.writeString(
                    config.configFile(),
                    "{ this is deliberately invalid JSON",
                    StandardCharsets.UTF_8
            );
            context.runOnClient(client -> {
                clock.setEnabled(false, client);
                clock.setKeyCode(GLFW.GLFW_KEY_UNKNOWN);
                maximum.set(1);
                config.load(client);
            });
            assertRestoredValues(clock, maximum);
            E2EAssertions.assertEquals(
                    corruptBefore + 1,
                    corruptConfigCount(config.configFile().getParent()),
                    "Unreadable config must be preserved for diagnosis"
            );
            assertSerializedContract(config.configFile());
        } catch (IOException exception) {
            throw new AssertionError("Configuration E2E failed", exception);
        } finally {
            context.runOnClient(client -> {
                clock.setEnabled(false, client);
                clock.setKeyCode(GLFW.GLFW_KEY_UNKNOWN);
                maximum.reset();
                config.save();
            });
        }
    }

    private static void assertRestoredValues(Module clock, IntegerSetting maximum) {
        E2EAssertions.assertTrue(clock.isEnabled(), "Enabled state must load from disk");
        E2EAssertions.assertEquals(GLFW.GLFW_KEY_K, clock.keyCode(), "Key bind must load");
        E2EAssertions.assertEquals(7, maximum.get(), "Setting value must load");
    }

    private static void assertSerializedContract(Path configFile) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(configFile))
                .getAsJsonObject();
        E2EAssertions.assertEquals(
                2,
                root.get("formatVersion").getAsInt(),
                "Config format version"
        );
        String activeProfile = root.get("activeProfile").getAsString();
        JsonObject modules = root.getAsJsonObject("profiles")
                .getAsJsonObject(activeProfile)
                .getAsJsonObject("modules");
        E2EAssertions.assertEquals(
                SealedClient.runtime().modules().all().size(),
                modules.size(),
                "Every module must be serialized"
        );
        JsonObject clock = modules.getAsJsonObject("clock");
        E2EAssertions.assertTrue(clock.get("enabled").getAsBoolean(), "Clock enabled JSON");
        E2EAssertions.assertEquals(GLFW.GLFW_KEY_K, clock.get("key").getAsInt(), "Clock key JSON");
        E2EAssertions.assertEquals(
                7,
                modules
                        .getAsJsonObject("effects")
                        .getAsJsonObject("settings")
                        .get("maximum")
                        .getAsInt(),
                "Effects maximum JSON"
        );
    }

    private static long corruptConfigCount(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("config.corrupt-") && name.endsWith(".json");
            }).count();
        }
    }
}
