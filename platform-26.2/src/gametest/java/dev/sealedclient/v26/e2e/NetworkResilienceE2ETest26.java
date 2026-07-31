package dev.sealedclient.v26.e2e;

import dev.sealedclient.v26.ClientRuntime26;
import dev.sealedclient.v26.SealedClient26;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Checks that an unannounced network drop leaves no stale session state behind.
 *
 * <p>A clean {@code /disconnect} is the easy case and is already covered
 * elsewhere. What actually happens on a distant server is that the connection
 * simply stops: no disconnect packet, no goodbye. {@link LatencyProxy26} can
 * reproduce that exactly, by severing the sockets it is forwarding while leaving
 * its listener open, so the client can reconnect to the same address afterwards.</p>
 *
 * <p>The assertions are about teardown and recovery, not about timing: the
 * client must notice the drop, run its platform-state release, and then complete
 * a fresh session through the same proxy. Everything waits on a condition with a
 * generous timeout rather than sleeping a fixed amount, because this runs on
 * whatever machine happens to be building.</p>
 *
 * <p>This models a dropped connection, not 2b2t. Nothing here reproduces its
 * queue, its anticheat, or its population.</p>
 */
public final class NetworkResilienceE2ETest26 implements FabricClientGameTest {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("Sealed Client 26.2 resilience E2E");
    private static final int TIMEOUT_TICKS = 30 * 20;
    private static final long ONE_WAY_DELAY_MILLIS = 25L;
    private static final String PLAYER = "SEALED_E2E_26";

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            runResilienceTest(context);
        } catch (IOException exception) {
            throw new AssertionError("Could not start the resilience proxy", exception);
        }
    }

    private void runResilienceTest(ClientGameTestContext context) throws IOException {
        if (!eulaAccepted()) {
            LOGGER.warn(
                    "SKIPPING the 26.2 network resilience E2E: no accepted eula.txt. "
                            + "Re-run with -Psealed.minecraftEula=true."
            );
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("gamemode", "creative");
        properties.setProperty("spawn-protection", "0");

        try (TestDedicatedServerContext server =
                     context.worldBuilder().createServer(properties)) {
            int port = server.computeOnServer(instance -> instance.getPort());
            assertTrue(port > 0, "The dedicated server must report its port");

            try (LatencyProxy26 proxy = LatencyProxy26.start(
                    "127.0.0.1", port, ONE_WAY_DELAY_MILLIS)) {
                String address = proxy.address();

                LOGGER.info("Session 1: connecting through {}", address);
                connectThrough(context, address);
                assertTrue(proxy.forwardedBytes() > 0,
                        "The proxy must have carried the first session's traffic");

                // Give the client a little state worth losing.
                server.runCommand("tp " + PLAYER + " 0.5 120.0 0.5");
                context.waitTicks(20);

                severTheConnection(context, proxy);
                assertTeardownIsClean(context);

                LOGGER.info("Session 2: reconnecting through {}", address);
                connectThrough(context, address);
                assertTrue(proxy.droppedSessions() == 1,
                        "Exactly one session must have been severed, saw "
                                + proxy.droppedSessions());

                assertNoStateLeakedFromTheDroppedSession(context);

                disconnect(context);
            }
        }

        context.waitTick();
        LOGGER.info("Network resilience E2E complete: drop, teardown, and reconnect all held");
    }

    /**
     * Cuts the sockets underneath the client without any protocol-level
     * goodbye, then waits until the client actually notices.
     */
    private static void severTheConnection(
            ClientGameTestContext context,
            LatencyProxy26 proxy
    ) {
        int closed = proxy.dropConnections();
        assertTrue(closed > 0, "There must have been a live connection to sever");
        LOGGER.info("Severed {} socket(s) with no disconnect packet", closed);

        context.waitFor(client -> client.getConnection() == null
                || !client.getConnection().getConnection().isConnected(), TIMEOUT_TICKS);
        context.waitTicks(20);
    }

    /**
     * After the drop the client must have released the state it was holding on
     * behalf of the session, exactly as a clean disconnect would.
     */
    private static void assertTeardownIsClean(ClientGameTestContext context) {
        ClientRuntime26 runtime = SealedClient26.runtime();

        assertTrue(context.computeOnClient(client ->
                        client.getConnection() == null
                                || !client.getConnection().getConnection().isConnected()),
                "The client must have registered the dropped connection");
        assertTrue(runtime.lastDeathLabel().isBlank(),
                "The death label must be cleared when the session ends unexpectedly");
        assertTrue(context.computeOnClient(client -> !client.options.keyUp.isDown()
                        && !client.options.keySprint.isDown()),
                "Movement keys held by automation must be released on an unexpected drop");
    }

    /** A fresh session must not inherit anything from the severed one. */
    private static void assertNoStateLeakedFromTheDroppedSession(ClientGameTestContext context) {
        ClientRuntime26 runtime = SealedClient26.runtime();

        assertTrue(context.computeOnClient(client -> client.player != null
                        && client.player.isAlive()),
                "The reconnected session must have a live player");
        assertTrue(runtime.lastDeathLabel().isBlank(),
                "The reconnected session must not inherit a death label");
        assertTrue(context.computeOnClient(client -> !client.options.keyUp.isDown()
                        && !client.options.keySprint.isDown()),
                "The reconnected session must not inherit held movement keys");
    }

    private static void connectThrough(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(
                new TitleScreen(),
                client,
                ServerAddress.parseString(address),
                new ServerData("sealed-resilience-proxy", address, ServerData.Type.OTHER),
                false,
                null
        ));
        context.waitFor(client -> client.player != null, TIMEOUT_TICKS);
        context.waitTicks(20);
    }

    private static void disconnect(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(
                        net.minecraft.network.chat.Component.literal("resilience e2e done")
                );
            }
        });
        context.waitFor(client -> client.getConnection() == null, TIMEOUT_TICKS);
        returnToTitleScreen(context);
    }

    /**
     * Connecting through the proxy bypasses the harness' own connection handle,
     * so the disconnect screen has to be dismissed by hand. The harness requires
     * every client game test to finish on the title screen.
     */
    private static void returnToTitleScreen(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.setScreen(new TitleScreen()));
        context.waitFor(client -> client.gui.screen() instanceof TitleScreen, TIMEOUT_TICKS);
        context.waitTicks(5);
    }

    private static boolean eulaAccepted() {
        Path eula = Path.of("eula.txt");
        if (!Files.isRegularFile(eula)) {
            return false;
        }
        try {
            return Files.readAllLines(eula).stream()
                    .map(line -> line.replace(" ", "").toLowerCase(Locale.ROOT))
                    .anyMatch("eula=true"::equals);
        } catch (IOException exception) {
            return false;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
