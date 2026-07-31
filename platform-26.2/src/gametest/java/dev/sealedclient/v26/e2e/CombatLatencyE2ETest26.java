package dev.sealedclient.v26.e2e;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Measures how quickly the client can react to something the server did.
 *
 * <p>The interesting number is not milliseconds, it is ticks. The client reads
 * incoming packets and then runs one tick; whatever it decides is sent at the
 * end of that tick. So the floor is one tick, and no amount of optimisation
 * moves it: a reaction cannot be sent before the tick that observes its cause.
 * A client claiming to beat that is either measuring something else or acting on
 * a prediction rather than on an observation.</p>
 *
 * <p>This suite establishes that the floor is actually reached rather than
 * merely available. It applies a server-side change, waits for the client to
 * observe it, and counts client ticks in between.</p>
 */
public final class CombatLatencyE2ETest26 implements FabricClientGameTest {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("Sealed Client 26.2 reaction latency E2E");
    private static final String PLAYER = "SEALED_E2E_26";
    private static final int TIMEOUT_TICKS = 30 * 20;
    private static final int SETTLE_TICKS = 10;
    private static final int SAMPLES = 12;

    /**
     * The physical floor: a reaction is sent by the tick that observes its
     * cause, so the observation itself costs one tick and cannot cost less.
     */
    private static final long FLOOR_TICKS = 1L;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!eulaAccepted()) {
            LOGGER.warn(
                    "SKIPPING the 26.2 reaction latency E2E: no accepted eula.txt. "
                            + "Re-run with -Psealed.minecraftEula=true."
            );
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("gamemode", "survival");
        properties.setProperty("spawn-protection", "0");
        properties.setProperty("difficulty", "normal");

        try (TestDedicatedServerContext server =
                     context.worldBuilder().createServer(properties)) {
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksDownload();
                context.waitTicks(SETTLE_TICKS);
                server.runCommand("gamerule naturalRegeneration false");
                server.runCommand("attribute " + PLAYER + " minecraft:max_health base set 1024");
                server.runCommand("effect give " + PLAYER + " minecraft:instant_health 1 200 true");
                context.waitTicks(SETTLE_TICKS);
                server.runCommand("effect clear " + PLAYER);
                context.waitTicks(SETTLE_TICKS);

                List<Long> samples = new ArrayList<>();
                for (int sample = 0; sample < SAMPLES; sample++) {
                    samples.add(measureObservationDelay(context, server));
                }

                long worst = samples.stream().mapToLong(Long::longValue).max().orElseThrow();
                long best = samples.stream().mapToLong(Long::longValue).min().orElseThrow();
                double mean = samples.stream().mapToLong(Long::longValue).average().orElseThrow();

                LOGGER.info(
                        "Reaction latency over {} samples: best {} tick(s), "
                                + "worst {} tick(s), mean {} tick(s). Floor is {} tick.",
                        samples.size(), best, worst,
                        String.format(Locale.ROOT, "%.2f", mean), FLOOR_TICKS
                );

                if (best < FLOOR_TICKS) {
                    throw new AssertionError(
                            "Measured " + best + " ticks, below the one-tick floor. "
                                    + "The measurement is wrong, not the client."
                    );
                }
                if (worst > FLOOR_TICKS) {
                    throw new AssertionError(
                            "Reacting took up to " + worst + " ticks when the floor is "
                                    + FLOOR_TICKS + ". Samples: " + samples
                    );
                }
            }
        }

        LOGGER.info("Reaction latency E2E complete: the client reacts on the observing tick");
    }

    /**
     * Applies a server-side change and counts client ticks until it is visible.
     *
     * <p>Health is used because the server owns it outright: the client cannot
     * predict or interpolate its way to the new value, so seeing it proves a
     * packet was received and processed rather than guessed.</p>
     */
    private static long measureObservationDelay(
            ClientGameTestContext context,
            TestDedicatedServerContext server
    ) {
        double before = context.computeOnClient(client -> (double) client.player.getHealth());
        double target = before - 4.0;

        server.runCommand(String.format(
                Locale.ROOT, "damage %s 4 minecraft:generic", PLAYER
        ));

        long ticks = 0;
        while (ticks < TIMEOUT_TICKS) {
            context.waitTick();
            ticks++;
            double now = context.computeOnClient(client -> (double) client.player.getHealth());
            if (Math.abs(now - target) < 0.5) {
                break;
            }
        }

        if (ticks >= TIMEOUT_TICKS) {
            throw new AssertionError("The client never observed the health change");
        }

        // Restore for the next sample.
        server.runCommand("effect give " + PLAYER + " minecraft:instant_health 1 200 true");
        context.waitTicks(SETTLE_TICKS);
        server.runCommand("effect clear " + PLAYER);
        context.waitTicks(SETTLE_TICKS);
        return ticks;
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
}
