package dev.sealedclient.e2e;

import dev.sealedclient.core.Module;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;

public final class AutoDisconnectE2ETest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        TestSingleplayerContext world = context.worldBuilder().create();
        context.waitForScreen(null);
        Module autoDisconnect = E2EAssertions.module("auto_disconnect");

        world.getServer().runOnServer(server -> {
            var players = server.getPlayerList().getPlayers();
            if (players.size() != 1) {
                throw new AssertionError("Expected one E2E player");
            }
            players.getFirst().setHealth(2.0f);
        });
        context.waitFor(client -> client.player != null && client.player.getHealth() <= 2.0f);

        context.runOnClient(client -> autoDisconnect.setEnabled(true, client));
        context.waitForScreen(DisconnectedScreen.class);
        context.waitFor(
                client -> client.level == null
                        && client.getConnection() == null
                        && !client.hasSingleplayerServer(),
                400
        );

        context.runOnClient(client -> {
            autoDisconnect.setEnabled(false, client);
            client.setScreen(new TitleScreen());
        });
        context.waitForScreen(TitleScreen.class);
    }
}
