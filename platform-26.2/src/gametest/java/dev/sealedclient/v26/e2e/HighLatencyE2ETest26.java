package dev.sealedclient.v26.e2e;

import dev.sealedclient.v26.SealedClient26;
import dev.sealedclient.v26.ClientRuntime26;
import dev.sealedclient.v26.hud.HudMetricsBridge26;
import dev.sealedclient.v26.hud.TickRateTracker26;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Exercises the client against a deliberately laggy connection, and against a
 * genuinely slow server.
 *
 * <p>A local dedicated server answers in well under a millisecond, which is the
 * opposite of the conditions this client is built for. {@link LatencyProxy26}
 * sits between the client and that server and holds every byte back by a fixed
 * delay. The suite measures a server-to-client round trip directly — issue a
 * server command, wait until the client observes its effect — first on a direct
 * connection and then through the proxy, and asserts the difference. Measuring
 * the effect rather than reading vanilla's ping field means the assertion is
 * about what the client actually experiences.</p>
 *
 * <p>Low server TPS is reproduced separately with vanilla's {@code /tick rate}
 * command, which is a supported, deterministic way to make the server run slow.
 * That is what validates the tick-rate estimator against a server that really is
 * behind, rather than one that is merely far away — and the suite asserts the
 * client tells those two situations apart.</p>
 *
 * <p>This reproduces latency and slow ticks, not 2b2t. Nothing here models its
 * queue, anticheat, population, or sustained multi-hour behaviour.</p>
 */
public final class HighLatencyE2ETest26 implements FabricClientGameTest {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("Sealed Client 26.2 latency E2E");
    /** One-way hold; a server-to-client effect is delayed by roughly this. */
    private static final long ONE_WAY_DELAY_MILLIS = 150L;
    private static final int CONNECT_TIMEOUT_TICKS = 30 * 20;
    private static final int ROUND_TRIP_SAMPLES = 5;
    private static final String PLAYER = "SEALED_E2E_26";

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            runLatencyTest(context);
        } catch (IOException exception) {
            throw new AssertionError("Could not start the latency proxy", exception);
        }
    }

    private void runLatencyTest(ClientGameTestContext context) throws IOException {
        if (!eulaAccepted()) {
            LOGGER.warn(
                    "SKIPPING the 26.2 high-latency E2E: no accepted eula.txt. "
                            + "Re-run with -Psealed.minecraftEula=true."
            );
            return;
        }

        ClientRuntime26 runtime = SealedClient26.runtime();
        Properties properties = new Properties();
        properties.setProperty("gamemode", "creative");
        properties.setProperty("spawn-protection", "0");

        try (TestDedicatedServerContext server =
                     context.worldBuilder().createServer(properties)) {

            long baseline = measureDirectBaseline(context, server);
            long delayed = measureThroughProxy(context, server, runtime);

            long injected = delayed - baseline;
            LOGGER.info(
                    "Round trip: {} ms direct, {} ms through a +{} ms proxy (delta {} ms)",
                    baseline, delayed, ONE_WAY_DELAY_MILLIS, injected
            );
            assertTrue(
                    injected >= ONE_WAY_DELAY_MILLIS / 2,
                    "The proxy delay must show up in the observed round trip: "
                            + "direct " + baseline + " ms, delayed " + delayed + " ms"
            );

            lowServerTickRateIsReportedAsLowTps(context, server);
        }

        context.waitTick();
        assertTrue(runtime.lastDeathLabel().isBlank(),
                "The death label must be cleared by the disconnect teardown");
    }

    /** Round trip on a direct connection, for comparison. */
    private static long measureDirectBaseline(
            ClientGameTestContext context,
            TestDedicatedServerContext server
    ) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();
            context.waitTicks(10);
            return medianRoundTripMillis(context, server);
        }
    }

    /**
     * Connects through the proxy and measures the same round trip, plus the
     * latency-sensitive readouts.
     */
    private static long measureThroughProxy(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            ClientRuntime26 runtime
    ) throws IOException {
        int serverPort = server.computeOnServer(instance -> instance.getPort());
        assertTrue(serverPort > 0, "The dedicated server must report its port");

        try (LatencyProxy26 proxy = LatencyProxy26.start(
                "127.0.0.1", serverPort, ONE_WAY_DELAY_MILLIS)) {

            String address = proxy.address();
            LOGGER.info("Connecting through the latency proxy at {} (+{} ms each way)",
                    address, ONE_WAY_DELAY_MILLIS);
            connectThrough(context, address);

            assertTrue(proxy.forwardedBytes() > 0,
                    "The proxy must have carried the session's traffic");
            assertTrue(context.computeOnClient(client ->
                            client.getCurrentServer() != null
                                    && address.equals(client.getCurrentServer().ip)),
                    "The client must be connected through the proxy address");

            long delayed = medianRoundTripMillis(context, server);
            tickRateStaysLiveDespiteTheDelay(context);
            theClientSurvivesSustainedPlayUnderLatency(context);
            disconnect(context);
            return delayed;
        }
    }

    /**
     * Measures how long a server-side change takes to become visible on the
     * client. The command is issued on the server, so the measured time is the
     * server-to-client direction plus one client tick of quantisation.
     */
    private static long medianRoundTripMillis(
            ClientGameTestContext context,
            TestDedicatedServerContext server
    ) {
        List<Long> samples = new ArrayList<>();
        for (int sample = 0; sample < ROUND_TRIP_SAMPLES; sample++) {
            int target = 100 + sample * 10;
            long start = System.nanoTime();
            server.runCommand("tp " + PLAYER + " 0.5 " + target + ".0 0.5");
            context.waitFor(
                    client -> client.player != null
                            && Math.abs(client.player.getY() - target) < 1.5,
                    CONNECT_TIMEOUT_TICKS
            );
            samples.add((System.nanoTime() - start) / 1_000_000L);
            context.waitTicks(4);
        }
        Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }

    /**
     * Latency must not be mistaken for a stalled server: while time updates keep
     * arriving, just later, the estimator has to stay LIVE and near 20 TPS.
     */
    private static void tickRateStaysLiveDespiteTheDelay(ClientGameTestContext context) {
        context.runOnClient(client ->
                SealedClient26.runtime().modules().find("tick_rate")
                        .orElseThrow().setEnabled(true));
        context.waitTicks(140);
        TickRateTracker26.Snapshot snapshot =
                context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot());

        LOGGER.info("Tick rate under {} ms one-way delay: {}",
                ONE_WAY_DELAY_MILLIS, snapshot.displayText());
        assertTrue(snapshot.status() == TickRateTracker26.Status.LIVE,
                "A delayed but healthy server must still read LIVE, saw "
                        + snapshot.status());
        assertTrue(snapshot.ticksPerSecond() > 12.0,
                "Latency must not be misreported as low TPS, saw "
                        + snapshot.ticksPerSecond());
    }

    /**
     * A genuinely slow server must be reported as slow. This is the case the
     * latency test above is deliberately contrasted with.
     */
    private static void lowServerTickRateIsReportedAsLowTps(
            ClientGameTestContext context,
            TestDedicatedServerContext server
    ) {
        try (TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();
            context.runOnClient(client ->
                    SealedClient26.runtime().modules().find("tick_rate")
                            .orElseThrow().setEnabled(true));
            context.waitTicks(120);

            TickRateTracker26.Snapshot healthy =
                    context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot());
            assertTrue(healthy.ticksPerSecond() > 12.0,
                    "Baseline must be a healthy tick rate, saw " + healthy.displayText());

            server.runCommand("tick rate 5");
            // The estimator averages over a window, so give it enough slow
            // samples to displace the healthy ones.
            context.waitTicks(400);

            TickRateTracker26.Snapshot slow =
                    context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot());
            LOGGER.info("Tick rate with the server forced to 5 TPS: {}", slow.displayText());
            assertTrue(slow.status() == TickRateTracker26.Status.LIVE
                            || slow.status() == TickRateTracker26.Status.STALE,
                    "A slow server must still produce a reading, saw " + slow.status());
            assertTrue(slow.ticksPerSecond() < 12.0,
                    "A server forced to 5 TPS must read below 12 TPS, saw "
                            + slow.displayText());

            server.runCommand("tick rate 20");
            context.waitTicks(400);
            TickRateTracker26.Snapshot recovered =
                    context.computeOnClient(client -> HudMetricsBridge26.tickRateSnapshot());
            LOGGER.info("Tick rate after recovery: {}", recovered.displayText());
            assertTrue(recovered.ticksPerSecond() > 12.0,
                    "The estimator must recover once the server speeds up, saw "
                            + recovered.displayText());
        }
    }

    /**
     * Drives the client through the normal multiplayer connect flow to an
     * arbitrary address, which is what lets the proxy sit in the middle.
     */
    private static void connectThrough(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(
                new TitleScreen(),
                client,
                ServerAddress.parseString(address),
                new ServerData("b2t-latency-proxy", address, ServerData.Type.OTHER),
                false,
                null
        ));
        context.waitFor(client -> client.player != null, CONNECT_TIMEOUT_TICKS);
        context.waitTicks(20);
    }

    private static void theClientSurvivesSustainedPlayUnderLatency(
            ClientGameTestContext context
    ) {
        context.waitTicks(200);
        assertTrue(context.computeOnClient(client ->
                        client.player != null && client.player.isAlive()
                                && client.getConnection() != null
                                && client.getConnection().getConnection().isConnected()),
                "The connection must survive sustained play under latency");
    }

    private static void disconnect(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(
                        net.minecraft.network.chat.Component.literal("latency e2e done")
                );
            }
        });
        context.waitFor(client -> client.getConnection() == null, CONNECT_TIMEOUT_TICKS);
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
