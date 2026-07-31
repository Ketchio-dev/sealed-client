package dev.sealedclient.v26.e2e;

import dev.sealedclient.common.combat.ExplosionDamageFormula;
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
 * Measures real end-crystal damage and checks the prediction against it.
 *
 * <p>A damage estimate is only worth what it predicts, and the only authority
 * on that is the server. Each scenario here places a real crystal at a known
 * offset from a real player, detonates it, reads the actual health drop, and
 * compares that with {@link ExplosionDamageFormula}. The scenarios that pass
 * become the fixed table in {@code ExplosionDamageFormulaTest}, so the fast unit
 * suite is anchored to measurements instead of to another guess.</p>
 *
 * <p>The tolerance is deliberately tight. A loose bound would let a formula
 * drift far enough to matter in a fight while still "passing", which is the
 * failure mode this suite exists to prevent.</p>
 *
 * <p>Scenarios run at well-separated coordinates because an explosion changes
 * the terrain around it, and a previous crater would otherwise alter the next
 * measurement's line of sight.</p>
 */
public final class ExplosionDamageAccuracyE2ETest26 implements FabricClientGameTest {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("Sealed Client 26.2 damage accuracy E2E");
    private static final String PLAYER = "SEALED_E2E_26";
    /** Half a heart. Anything looser could hide a lethal misprediction. */
    private static final double TOLERANCE = 1.0;
    private static final int SETTLE_TICKS = 12;
    private static final int POSITION_TIMEOUT_TICKS = 20 * 20;

    /**
     * One measurement: the player stands at {@code (x, y, z)} and a crystal is
     * detonated {@code offset} blocks away on the X axis.
     */
    private record Scenario(
            String name,
            int x,
            int y,
            int z,
            double offset,
            boolean armored,
            boolean obstructed
    ) {
        Scenario(String name, int x, int y, int z, double offset, boolean armored) {
            this(name, x, y, z, offset, armored, false);
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("point_blank_unarmored", 100, 64, 100, 1.0, false),
            new Scenario("close_unarmored", 200, 64, 100, 2.0, false),
            new Scenario("mid_unarmored", 300, 64, 100, 4.0, false),
            new Scenario("far_unarmored", 400, 64, 100, 7.0, false),
            new Scenario("point_blank_armored", 500, 64, 100, 1.0, true),
            new Scenario("close_armored", 600, 64, 100, 2.0, true),
            new Scenario("mid_armored", 700, 64, 100, 4.0, true),
            new Scenario("far_armored", 800, 64, 100, 7.0, true),
            // Every open scenario above measures full exposure, so the ray
            // sampling itself would go untested without a wall in the way.
            new Scenario("obstructed_unarmored", 900, 64, 100, 3.0, false, true),
            new Scenario("obstructed_armored", 1000, 64, 100, 3.0, true, true)
    );

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!eulaAccepted()) {
            LOGGER.warn(
                    "SKIPPING the 26.2 explosion accuracy E2E: no accepted eula.txt. "
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

                server.runCommand("gamerule doImmediateRespawn true");
                server.runCommand("gamerule mobGriefing false");
                // Regeneration between the two readings would be indistinguishable
                // from the explosion dealing less damage than it did.
                server.runCommand("gamerule naturalRegeneration false");
                server.runCommand("difficulty normal");
                context.waitTicks(SETTLE_TICKS);

                List<String> rows = new ArrayList<>();
                List<String> failures = new ArrayList<>();

                for (Scenario scenario : SCENARIOS) {
                    measure(context, server, scenario, rows, failures);
                }

                LOGGER.info("Measured explosion damage, {} scenarios:", rows.size());
                rows.forEach(row -> LOGGER.info("  {}", row));

                if (!failures.isEmpty()) {
                    throw new AssertionError(
                            "The explosion formula disagreed with real damage in "
                                    + failures.size() + " scenario(s): "
                                    + String.join("; ", failures)
                    );
                }
            }
        }

        LOGGER.info("Explosion damage accuracy E2E complete: prediction matches the server");
    }

    private void measure(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            Scenario scenario,
            List<String> rows,
            List<String> failures
    ) {
        prepare(context, server, scenario);

        double healthBefore = serverHealth(server);
        double armor = context.computeOnClient(client ->
                (double) client.player.getArmorValue());

        // Without this an "armored" scenario whose loadout never reached the
        // client would quietly measure the unarmored case and still pass.
        if (scenario.armored() && armor <= 0.0) {
            failures.add(scenario.name() + " expected armor but the client saw none");
            return;
        }
        double toughness = context.computeOnClient(client -> client.player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS));

        // The crystal sits on the ground next to the player; its explosion
        // centre is the entity position, one block up from the block it rests on.
        double centreX = scenario.x() + 0.5 + scenario.offset();
        double centreY = scenario.y() + 1.0;
        double centreZ = scenario.z() + 0.5;

        server.runCommand(String.format(
                Locale.ROOT,
                "summon end_crystal %.1f %.1f %.1f {ShowBottom:0b}",
                centreX, centreY, centreZ
        ));
        context.waitTicks(SETTLE_TICKS);

        double predicted = predict(context, centreX, centreY, centreZ, armor, toughness);
        double exposure = exposure(context, centreX, centreY, centreZ);
        double distance = context.computeOnClient(client -> client.player.position()
                .distanceTo(new net.minecraft.world.phys.Vec3(centreX, centreY, centreZ)));
        long crystals = context.computeOnClient(client -> {
            int found = 0;
            for (var entity : client.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
                    found++;
                }
            }
            return (long) found;
        });

        // Damaging the crystal detonates it exactly as a player hit would.
        server.runCommand(String.format(
                Locale.ROOT,
                "damage @e[type=end_crystal,limit=1,sort=nearest,x=%.1f,y=%.1f,z=%.1f,distance=..3]"
                        + " 1000 minecraft:generic",
                centreX, centreY, centreZ
        ));
        context.waitTicks(SETTLE_TICKS);

        double healthAfter = serverHealth(server);
        double actual = Math.max(0.0, healthBefore - healthAfter);
        double delta = Math.abs(actual - predicted);

        rows.add(String.format(
                Locale.ROOT,
                "%-24s offset=%.1f armor=%.0f dist=%.2f exposure=%.3f crystals=%d "
                        + "predicted=%.3f actual=%.3f delta=%.3f",
                scenario.name(), scenario.offset(), armor, distance, exposure, crystals,
                predicted, actual, delta
        ));

        // An obstructed scenario that still reads full exposure never tested
        // the ray sampling, so the wall failed to go up.
        if (scenario.obstructed() && exposure >= 1.0) {
            failures.add(scenario.name() + " was meant to be obstructed but saw full exposure");
            return;
        }

        // A scenario that measured nothing at all proves nothing about the
        // formula. Anything this close must produce damage, so zero means the
        // setup failed rather than that the prediction was right.
        if (scenario.offset() <= 8.0 && actual <= 0.0) {
            failures.add(String.format(
                    Locale.ROOT,
                    "%s took no damage at all (dist=%.2f exposure=%.3f crystals=%d)",
                    scenario.name(), distance, exposure, crystals
            ));
            return;
        }

        // A scenario where the player survived at full health proves nothing
        // about the formula, and one that killed them clips the measurement.
        if (healthBefore <= 0.0) {
            failures.add(scenario.name() + " started with no health");
            return;
        }
        if (healthAfter <= 0.0 && predicted < healthBefore) {
            failures.add(scenario.name() + " died, so actual damage was clipped");
            return;
        }
        if (delta > TOLERANCE) {
            failures.add(String.format(
                    Locale.ROOT,
                    "%s predicted %.3f but took %.3f (delta %.3f)",
                    scenario.name(), predicted, actual, delta
            ));
        }
    }

    /**
     * Reads health from the server, which is the authority on damage.
     *
     * <p>The client's copy arrives a packet later and is the value the player
     * sees, not the value the server applied. Measuring it made the readings
     * disagree with the formula by a consistent fraction of a heart.</p>
     */
    private static double serverHealth(TestDedicatedServerContext server) {
        return server.computeOnServer(instance -> {
            var players = instance.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return 0.0;
            }
            var player = players.get(0);
            return (double) (player.getHealth() + player.getAbsorptionAmount());
        });
    }

    /** The exposure term alone, reported so a zero reading can be diagnosed. */
    private static double exposure(
            ClientGameTestContext context,
            double centreX,
            double centreY,
            double centreZ
    ) {
        return context.computeOnClient(client -> {
            var player = client.player;
            var box = player.getBoundingBox();
            var centre = new net.minecraft.world.phys.Vec3(centreX, centreY, centreZ);
            return ExplosionDamageFormula.exposure(
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    (x, y, z) -> client.level.clip(new net.minecraft.world.level.ClipContext(
                            new net.minecraft.world.phys.Vec3(x, y, z),
                            centre,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            player
                    )).getType() == net.minecraft.world.phys.HitResult.Type.MISS
            );
        });
    }

    /**
     * Runs the shared formula against live client state, exactly as a module
     * would when deciding whether a placement is worth taking.
     */
    private static double predict(
            ClientGameTestContext context,
            double centreX,
            double centreY,
            double centreZ,
            double armor,
            double toughness
    ) {
        return context.computeOnClient(client -> {
            var player = client.player;
            var box = player.getBoundingBox();
            var centre = new net.minecraft.world.phys.Vec3(centreX, centreY, centreZ);

            double exposure = ExplosionDamageFormula.exposure(
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    (x, y, z) -> client.level.clip(new net.minecraft.world.level.ClipContext(
                            new net.minecraft.world.phys.Vec3(x, y, z),
                            centre,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            player
                    )).getType() == net.minecraft.world.phys.HitResult.Type.MISS
            );

            double distance = player.position().distanceTo(centre);
            double raw = ExplosionDamageFormula.rawDamage(
                    distance, exposure, ExplosionDamageFormula.END_CRYSTAL_RADIUS
            );
            return ExplosionDamageFormula.afterReductions(raw, armor, toughness, 0.0, 0);
        });
    }

    /**
     * Puts the player on clean ground at full health with a known loadout.
     *
     * <p>A crystal at point-blank range deals far more than twenty points, so a
     * vanilla health pool would cap the measurement at whatever the player had
     * left and report a death instead of a damage figure. The pool is raised
     * well above any possible blast so the full amount stays observable;
     * nothing in the damage calculation depends on maximum health.</p>
     */
    private static void prepare(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            Scenario scenario
    ) {
        server.runCommand("attribute " + PLAYER + " minecraft:max_health base set 1024");
        server.runCommand(String.format(
                Locale.ROOT,
                "fill %d %d %d %d %d %d minecraft:obsidian",
                scenario.x() - 6, scenario.y() - 1, scenario.z() - 6,
                scenario.x() + 14, scenario.y() - 1, scenario.z() + 6
        ));
        server.runCommand(String.format(
                Locale.ROOT,
                "fill %d %d %d %d %d %d minecraft:air",
                scenario.x() - 6, scenario.y(), scenario.z() - 6,
                scenario.x() + 14, scenario.y() + 4, scenario.z() + 6
        ));
        // fill silently does nothing in an unloaded chunk, and these scenarios
        // sit hundreds of blocks apart. Without this the platform is sometimes
        // never built and the player falls to the bottom of the world instead
        // of standing where the measurement expects.
        server.runCommand(String.format(
                Locale.ROOT,
                "forceload add %d %d %d %d",
                scenario.x() - 16, scenario.z() - 16,
                scenario.x() + 24, scenario.z() + 16
        ));
        context.waitTicks(SETTLE_TICKS);

        if (scenario.obstructed()) {
            // A pillar between player and blast, tall and wide enough that some
            // sample rays are stopped and others are not.
            server.runCommand(String.format(
                    Locale.ROOT,
                    "fill %d %d %d %d %d %d minecraft:obsidian",
                    scenario.x() + 1, scenario.y(), scenario.z() - 1,
                    scenario.x() + 1, scenario.y() + 2, scenario.z() + 1
            ));
        }
        server.runCommand("clear " + PLAYER);
        if (scenario.armored()) {
            // Unenchanted diamond: known armour points with nothing the client
            // could fail to see, so a mismatch means the formula, not the input.
            server.runCommand("item replace entity " + PLAYER + " armor.head with minecraft:diamond_helmet");
            server.runCommand("item replace entity " + PLAYER + " armor.chest with minecraft:diamond_chestplate");
            server.runCommand("item replace entity " + PLAYER + " armor.legs with minecraft:diamond_leggings");
            server.runCommand("item replace entity " + PLAYER + " armor.feet with minecraft:diamond_boots");
        }
        server.runCommand("effect clear " + PLAYER);
        server.runCommand(String.format(
                Locale.ROOT,
                "tp %s %d.5 %d.0 %d.5",
                PLAYER, scenario.x(), scenario.y(), scenario.z()
        ));
        server.runCommand("effect give " + PLAYER + " minecraft:instant_health 1 200 true");
        context.waitTicks(SETTLE_TICKS);
        server.runCommand("effect clear " + PLAYER);

        // A teleport is a round trip. Waiting a fixed number of ticks let an
        // earlier revision measure from the previous scenario's coordinates,
        // which read as "no damage" rather than as the setup fault it was.
        try {
            context.waitFor(client -> client.player != null
                    && client.player.position().distanceTo(new net.minecraft.world.phys.Vec3(
                            scenario.x() + 0.5, scenario.y(), scenario.z() + 0.5)) < 1.0,
                    POSITION_TIMEOUT_TICKS);
        } catch (AssertionError error) {
            throw new AssertionError(
                    scenario.name() + " never reached its start position. "
                            + describe(context, server, scenario),
                    error
            );
        }
        context.waitTicks(SETTLE_TICKS);
        server.runCommand(String.format(
                Locale.ROOT,
                "forceload remove %d %d %d %d",
                scenario.x() - 16, scenario.z() - 16,
                scenario.x() + 24, scenario.z() + 16
        ));
    }

    /** State from both sides, so a stuck setup says why instead of timing out. */
    private static String describe(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            Scenario scenario
    ) {
        String serverSide = server.computeOnServer(instance -> {
            var players = instance.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return "server has no player";
            }
            var player = players.get(0);
            return String.format(
                    Locale.ROOT,
                    "server pos=(%.2f, %.2f, %.2f) health=%.2f alive=%s dim=%s",
                    player.getX(), player.getY(), player.getZ(),
                    player.getHealth(), player.isAlive(),
                    player.level().dimension().identifier()
            );
        });
        String clientSide = context.computeOnClient(client -> {
            if (client.player == null) {
                return "client has no player";
            }
            return String.format(
                    Locale.ROOT,
                    "client pos=(%.2f, %.2f, %.2f) health=%.2f alive=%s",
                    client.player.getX(), client.player.getY(), client.player.getZ(),
                    client.player.getHealth(), client.player.isAlive()
            );
        });
        return String.format(
                Locale.ROOT,
                "Wanted (%d.50, %d.00, %d.50). %s. %s",
                scenario.x(), scenario.y(), scenario.z(), serverSide, clientSide
        );
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
