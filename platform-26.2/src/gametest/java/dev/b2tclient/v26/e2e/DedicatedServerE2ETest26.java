package dev.b2tclient.v26.e2e;

import dev.b2tclient.common.module.RegisteredModule;
import dev.b2tclient.v26.B2TClient26;
import dev.b2tclient.v26.ClientRuntime26;
import dev.b2tclient.v26.hud.CombatTargetBridge26;
import dev.b2tclient.v26.hud.HudMetricsBridge26;
import dev.b2tclient.v26.hud.TickRateTracker26;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * End-to-end coverage against a real dedicated server over a real socket.
 *
 * <p>The singleplayer suite never exercises the multiplayer paths: an
 * integrated server reports {@code singleplayer} as the server key, so profile
 * matching by server address, the tick-rate estimator that feeds on inbound
 * time-update packets, and the disconnect teardown that a network drop triggers
 * all go untested there. This suite connects over the loopback network so those
 * paths run for real.</p>
 *
 * <p>What this does <em>not</em> reproduce: 2b2t's queue, its anticheat, its
 * population, or its sustained multi-hour conditions. It proves the client's own
 * multiplayer state machines behave against a genuine server, nothing more.</p>
 */
public final class DedicatedServerE2ETest26 implements FabricClientGameTest {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("B2T Client 26.2 dedicated E2E");

    /**
     * Running a dedicated server requires agreeing to Mojang's EULA, which is a
     * decision for whoever runs the build, not something this suite records on
     * their behalf. Without that agreement the suite skips instead of failing,
     * so the rest of the gate stays meaningful.
     *
     * <p>Enable with {@code ./gradlew -Pb2t.minecraftEula=true ...}, which
     * writes {@code eula.txt} into the game-test run directory.</p>
     */
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

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientRuntime26 runtime = B2TClient26.runtime();

        if (!eulaAccepted()) {
            LOGGER.warn(
                    "SKIPPING the 26.2 dedicated-server E2E: no accepted eula.txt in {}. "
                            + "Re-run with -Pb2t.minecraftEula=true to exercise the real "
                            + "network paths (profile matching by server address, the "
                            + "tick-rate estimator, and the disconnect teardown).",
                    Path.of("").toAbsolutePath()
            );
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("gamemode", "creative");
        properties.setProperty("spawn-protection", "0");

        try (TestDedicatedServerContext server =
                     context.worldBuilder().createServer(properties)) {

            String serverKey = firstConnection(context, server, runtime);
            reconnectReappliesTheProfileSnapshot(context, server, runtime, serverKey);
            tickRateIsMeasuredFromRealInboundPackets(context, server, runtime);
            aBoundedSoakKeepsTheRuntimeStable(context, server, runtime);
        }

        context.waitTick();
        assertTrue(runtime.combatTarget().entityId(0) == CombatTargetBridge26.NO_TARGET,
                "The combat target must be cleared after the server is gone");
    }

    /**
     * Connects once and captures the profile the runtime auto-applies for the
     * real server address.
     */
    private static String firstConnection(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            ClientRuntime26 runtime
    ) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();
            context.waitTicks(5);

            String serverKey = context.computeOnClient(runtime::serverKey);
            assertTrue(serverKey != null && !serverKey.isBlank()
                            && !"singleplayer".equals(serverKey),
                    "A dedicated server must report a real address, saw " + serverKey);

            // Save a profile bound to this exact address, then prove the runtime
            // picks it on the next connection rather than leaving state as-is.
            context.runOnClient(client -> {
                runtime.modules().find("clock").orElseThrow().setEnabled(true);
                runtime.modules().find("fps").orElseThrow().setEnabled(false);
                runtime.profiles().capture("dedicated", serverKey, runtime.modules());
            });
            context.waitTick();

            assertTrue(runtime.profiles().find("dedicated").isPresent(),
                    "The profile must be stored under the real server address");
            return serverKey;
        }
    }

    /**
     * A reconnect must reapply the profile snapshot even when that profile is
     * already the active one, so unsaved live edits from the previous session
     * cannot leak across a disconnect.
     */
    private static void reconnectReappliesTheProfileSnapshot(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            ClientRuntime26 runtime,
            String serverKey
    ) {
        // Drift away from the saved snapshot while disconnected.
        context.runOnClient(client -> {
            runtime.modules().find("clock").orElseThrow().setEnabled(false);
            runtime.modules().find("fps").orElseThrow().setEnabled(true);
        });
        context.waitTick();

        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();
            // applyServerProfile runs on the client tick right after the
            // connection identity changes.
            context.waitTicks(5);

            assertTrue(context.computeOnClient(client ->
                            runtime.profiles().active()
                                    .map(profile -> profile.name())
                                    .orElse("")).equalsIgnoreCase("dedicated"),
                    "Connecting must activate the profile matching the server address");
            assertTrue(runtime.modules().find("clock").orElseThrow().enabled(),
                    "Reconnecting must restore the profile snapshot");
            assertTrue(!runtime.modules().find("fps").orElseThrow().enabled(),
                    "An unsaved live edit must not survive a reconnect");
            assertTrue(context.computeOnClient(runtime::serverKey).equals(serverKey),
                    "The server key must be stable across reconnects");
        }

        assertTrue(runtime.lastDeathLabel().isBlank(),
                "The death label must be cleared by the disconnect teardown");
    }

    /**
     * The tick-rate readout is driven by inbound time-update packets, which only
     * arrive over a real connection.
     */
    private static void tickRateIsMeasuredFromRealInboundPackets(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            ClientRuntime26 runtime
    ) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();

            context.runOnClient(client ->
                    runtime.modules().find("tick_rate").orElseThrow().setEnabled(true));

            // Time updates arrive once per server second; sample well past that.
            context.waitTicks(120);

            TickRateTracker26.Snapshot snapshot =
                    context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot());
            assertTrue(snapshot.status() == TickRateTracker26.Status.LIVE,
                    "Expected a live tick rate over a real connection, saw "
                            + snapshot.status() + " (" + snapshot.displayText() + ")");
            assertTrue(snapshot.sampleCount() > 0, "A live snapshot needs samples");
            assertTrue(snapshot.ticksPerSecond() > 10.0 && snapshot.ticksPerSecond() <= 21.0,
                    "An idle local server should sit near 20 TPS, saw "
                            + snapshot.ticksPerSecond());
        }

        context.waitTicks(2);
        assertTrue(
                context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot().status())
                        == TickRateTracker26.Status.DISCONNECTED,
                "The tick-rate tracker must reset when the connection drops"
        );
    }

    /**
     * Runs the full module set against a live server for a bounded number of
     * ticks. This is a stability check, not a duration soak: it proves the
     * per-tick pipeline survives real packet flow without throwing or wedging
     * an action arbiter.
     */
    private static void aBoundedSoakKeepsTheRuntimeStable(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            ClientRuntime26 runtime
    ) {
        Map<String, Boolean> restore = context.computeOnClient(client -> {
            java.util.Map<String, Boolean> before = new java.util.LinkedHashMap<>();
            for (RegisteredModule module : runtime.modules().all()) {
                before.put(module.descriptor().id(), module.enabled());
            }
            return before;
        });

        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();

            context.runOnClient(client -> {
                for (RegisteredModule module : runtime.modules().all()) {
                    // Baritone needs a separately installed provider; enabling it
                    // here would assert on a dependency this suite does not ship.
                    if (!module.descriptor().available()
                            || "baritone_navigator".equals(module.descriptor().id())) {
                        continue;
                    }
                    module.setEnabled(true);
                }
            });

            context.waitTicks(200);

            assertTrue(context.computeOnClient(client -> client.player != null
                            && client.player.isAlive()
                            && client.getConnection() != null
                            && client.getConnection().getConnection().isConnected()),
                    "The player must survive the soak, still connected, "
                            + "with every module enabled");
            assertTrue(server.computeOnServer(instance ->
                            instance.getPlayerList().getPlayerCount()) == 1,
                    "The server must still see the client after the soak");
        }

        context.runOnClient(client -> restore.forEach((id, enabled) ->
                runtime.modules().find(id).ifPresent(module -> {
                    if (module.descriptor().available() || !enabled) {
                        module.setEnabled(enabled);
                    }
                })));
        context.waitTick();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
