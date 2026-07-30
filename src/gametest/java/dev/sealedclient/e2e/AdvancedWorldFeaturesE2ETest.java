package dev.sealedclient.e2e;

import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.module.visual.XRayModule;
import dev.sealedclient.module.world.PortalCoordsModule;
import dev.sealedclient.module.world.StashFinderModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Exercises the 1.21.4-only hooks in a real integrated client. Assertions use
 * state transitions and configured work limits rather than wall-clock timing.
 */
public final class AdvancedWorldFeaturesE2ETest implements FabricClientGameTest {
    private static final List<String> MODULE_IDS = List.of(
            "freecam",
            "no_slow",
            "no_rotate",
            "xray",
            "chams",
            "stash_finder",
            "portal_coords",
            "auto_craft"
    );

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && client.level != null
                    && client.levelRenderer != null);
            prepareWorld(world.getServer());
            context.waitFor(client -> fixturesAreLoaded(client), 80);

            try {
                testRegistrationAndToggleCleanup(context);
                testFreecamLifecycle(context);
                testXRayRefreshLifecycle(context);
                testBoundedWorldFinders(context);
            } finally {
                context.runOnClient(client -> {
                    client.options.keyUp.setDown(false);
                    client.options.keyDown.setDown(false);
                    client.options.keyLeft.setDown(false);
                    client.options.keyRight.setDown(false);
                    client.options.keyJump.setDown(false);
                    client.options.keyShift.setDown(false);
                    for (String id : MODULE_IDS) {
                        E2EAssertions.module(id).reset(client);
                    }
                });
            }
        }
    }

    private static void testRegistrationAndToggleCleanup(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (String id : MODULE_IDS) {
                Module module = E2EAssertions.module(id);
                module.setEnabled(false, client);
                E2EAssertions.assertFalse(
                        module.defaultEnabled(),
                        id + " must remain opt-in"
                );
            }

            assertRisk("freecam", ModuleRisk.MOVEMENT);
            assertRisk("no_slow", ModuleRisk.MOVEMENT);
            assertRisk("no_rotate", ModuleRisk.PACKET);
            assertRisk("xray", ModuleRisk.PASSIVE);
            assertRisk("chams", ModuleRisk.PASSIVE);
            assertRisk("stash_finder", ModuleRisk.PASSIVE);
            assertRisk("portal_coords", ModuleRisk.PASSIVE);
            assertRisk("auto_craft", ModuleRisk.AUTOMATION);

            for (String id : List.of("no_slow", "no_rotate")) {
                Module module = E2EAssertions.module(id);
                module.setEnabled(true, client);
                E2EAssertions.assertTrue(module.isEnabled(), id + " must enable");
                module.setEnabled(false, client);
                E2EAssertions.assertFalse(module.isEnabled(), id + " must disable cleanly");
            }
        });
    }

    private static void testFreecamLifecycle(ClientGameTestContext context) {
        Vec3 playerBefore = context.computeOnClient(client -> client.player.position());
        Vec3 cameraBefore = context.computeOnClient(client -> {
            Module freecam = E2EAssertions.module("freecam");
            freecam.setEnabled(true, client);
            E2EAssertions.assertTrue(freecam.isEnabled(), "Freecam must enable");
            E2EAssertions.assertTrue(
                    client.getCameraEntity() != client.player,
                    "Freecam must detach the camera from the real player"
            );
            client.options.keyUp.setDown(true);
            return client.getCameraEntity().position();
        });

        context.waitTick();
        context.waitTick();
        context.runOnClient(client -> {
            client.options.keyUp.setDown(false);
            E2EAssertions.assertTrue(
                    client.getCameraEntity().position().distanceToSqr(cameraBefore) > 0.01,
                    "Freecam movement input must move the detached camera"
            );
            E2EAssertions.assertTrue(
                    client.player.position().distanceToSqr(playerBefore) < 0.01,
                    "Freecam must not move the real player"
            );

            Module freecam = E2EAssertions.module("freecam");
            freecam.setEnabled(false, client);
            E2EAssertions.assertTrue(
                    client.getCameraEntity() == client.player,
                    "Freecam disable must restore the player camera"
            );
            E2EAssertions.assertFalse(freecam.isEnabled(), "Freecam must remain disabled");
        });
    }

    private static void testXRayRefreshLifecycle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Module xray = E2EAssertions.module("xray");
            xray.setEnabled(true, client);
            E2EAssertions.assertTrue(xray.isEnabled(), "XRay must enable");
            E2EAssertions.assertTrue(
                    XRayModule.active() != null,
                    "XRay render hooks must observe the enabled module"
            );
        });
        context.waitTick();
        context.waitTick();
        context.runOnClient(client -> {
            Module xray = E2EAssertions.module("xray");
            xray.setEnabled(false, client);
            E2EAssertions.assertTrue(
                    XRayModule.active() == null,
                    "XRay render hooks must stop immediately on disable"
            );
        });
        context.waitTick();
        context.waitTick();
    }

    private static void testBoundedWorldFinders(ClientGameTestContext context) {
        StashFinderModule stashFinder =
                (StashFinderModule) E2EAssertions.module("stash_finder");
        PortalCoordsModule portalCoords =
                (PortalCoordsModule) E2EAssertions.module("portal_coords");

        context.runOnClient(client -> {
            integerSetting(stashFinder, "range").set(32);
            integerSetting(stashFinder, "scan_budget").set(2_048);
            integerSetting(stashFinder, "minimum_containers").set(2);
            integerSetting(stashFinder, "maximum_entries").set(8);
            integerSetting(portalCoords, "scan_range").set(16);
            integerSetting(portalCoords, "scan_budget").set(32_768);
            integerSetting(portalCoords, "maximum_entries").set(8);

            stashFinder.setEnabled(true, client);
            portalCoords.setEnabled(true, client);
        });

        context.waitFor(client -> !stashFinder.snapshot().isEmpty(), 40);
        context.waitFor(client -> !portalCoords.snapshot().isEmpty(), 80);
        context.runOnClient(client -> {
            E2EAssertions.assertTrue(
                    stashFinder.snapshot().size() <= stashFinder.maximumEntries(),
                    "Stash Finder snapshots must respect maximum_entries"
            );
            E2EAssertions.assertTrue(
                    stashFinder.snapshot().getFirst().containerCount() >= 2,
                    "Stash Finder must report the isolated barrel cluster"
            );
            StashFinderModule.ScanProgress progress = stashFinder.scanProgress();
            E2EAssertions.assertTrue(
                    progress.scannedChunks() <= progress.totalChunks(),
                    "Stash Finder scan cursor must stay inside its bounded sweep"
            );

            E2EAssertions.assertTrue(
                    portalCoords.snapshot().size() <= portalCoords.maximumEntries(),
                    "Portal Coords snapshots must respect maximum_entries"
            );
            E2EAssertions.assertTrue(
                    portalCoords.snapshot().getFirst().portalBlockCount() >= 2,
                    "Portal Coords must merge connected portal blocks"
            );
            PortalCoordsModule.CoordinateConversionSnapshot conversion =
                    portalCoords.conversionSnapshot().getFirst();
            E2EAssertions.assertEquals(
                    PortalCoordsModule.NETHER,
                    conversion.targetDimension(),
                    "Overworld portal coordinates must target the Nether"
            );

            stashFinder.setEnabled(false, client);
            portalCoords.setEnabled(false, client);
            E2EAssertions.assertTrue(
                    stashFinder.snapshot().isEmpty(),
                    "Stash Finder must clear session results on disable"
            );
            E2EAssertions.assertTrue(
                    portalCoords.snapshot().isEmpty(),
                    "Portal Coords must clear session results on disable"
            );
        });
    }

    private static void prepareWorld(TestServerContext serverContext) {
        serverContext.runOnServer(server -> {
            ServerPlayer player = onlyPlayer(server);
            BlockPos floor = new BlockPos(0, 64, 0);
            server.overworld().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            player.teleportTo(0.5, 65.0, 0.5);
            player.setDeltaMovement(Vec3.ZERO);

            for (int x = -2; x <= 3; x++) {
                server.overworld().setBlockAndUpdate(
                        new BlockPos(x, 65, 4),
                        Blocks.BARREL.defaultBlockState()
                );
            }

            for (int x = 1; x <= 4; x++) {
                server.overworld().setBlockAndUpdate(
                        new BlockPos(x, 64, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                server.overworld().setBlockAndUpdate(
                        new BlockPos(x, 68, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
            }
            for (int y = 65; y <= 67; y++) {
                server.overworld().setBlockAndUpdate(
                        new BlockPos(1, y, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                server.overworld().setBlockAndUpdate(
                        new BlockPos(4, y, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
            }
            for (int x = 2; x <= 3; x++) {
                for (int y = 65; y <= 67; y++) {
                    server.overworld().setBlockAndUpdate(
                            new BlockPos(x, y, 0),
                            Blocks.NETHER_PORTAL.defaultBlockState()
                    );
                }
            }
        });
    }

    private static boolean fixturesAreLoaded(net.minecraft.client.Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        return client.player.position().distanceToSqr(0.5, 65.0, 0.5) < 0.25
                && client.level.getBlockEntity(new BlockPos(-2, 65, 4)) != null
                && client.level.getBlockState(new BlockPos(2, 65, 0))
                .is(Blocks.NETHER_PORTAL);
    }

    private static IntegerSetting integerSetting(Module module, String id) {
        return (IntegerSetting) E2EAssertions.setting(module, id);
    }

    private static void assertRisk(String id, ModuleRisk expected) {
        E2EAssertions.assertEquals(
                expected,
                E2EAssertions.module(id).risk(),
                id + " risk classification"
        );
    }

    private static ServerPlayer onlyPlayer(net.minecraft.server.MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() != 1) {
            throw new AssertionError("Expected one E2E player, got " + players.size());
        }
        return players.getFirst();
    }
}
