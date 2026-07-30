package dev.b2tclient.e2e;

import dev.b2tclient.B2TClient;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.TickableModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

public final class WorldModulesE2ETest implements FabricClientGameTest {
    private static final List<String> TESTED_MODULES = List.of(
            "clear_weather",
            "full_bright",
            "no_view_bob",
            "auto_walk",
            "auto_sprint",
            "auto_totem",
            "auto_armor",
            "auto_eat",
            "auto_tool",
            "auto_weapon",
            "trigger_bot"
    );

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            context.waitFor(client -> client.player != null && client.gameMode != null);

            try {
                testVisualLifecycle(context);
                testMovementLifecycle(context);
                testAutoTotem(context, world.getServer());
                testAutoArmor(context, world.getServer());
                testAutoEat(context, world.getServer());
                testToolWeaponAndTrigger(context, world.getServer());
            } finally {
                context.runOnClient(client -> {
                    for (String id : TESTED_MODULES) {
                        E2EAssertions.module(id).setEnabled(false, client);
                    }
                    client.options.keyAttack.setDown(false);
                    client.options.keyUse.setDown(false);
                    client.options.keyUp.setDown(false);
                });
            }
        }
    }

    private static void testVisualLifecycle(ClientGameTestContext context) {
        Module clearWeather = E2EAssertions.module("clear_weather");
        Module fullBright = E2EAssertions.module("full_bright");
        Module noViewBob = E2EAssertions.module("no_view_bob");

        context.runOnClient(client -> {
            client.level.setRainLevel(1.0f);
            client.level.setThunderLevel(1.0f);
            client.options.gamma().set(0.35);
            client.options.bobView().set(true);
            clearWeather.setEnabled(true, client);
            fullBright.setEnabled(true, client);
            noViewBob.setEnabled(true, client);
        });
        context.waitTick();

        context.runOnClient(client -> {
            E2EAssertions.assertNear(
                    0.0,
                    client.level.getRainLevel(0.0f),
                    0.001,
                    "Clear Weather must suppress rain"
            );
            E2EAssertions.assertNear(
                    0.0,
                    client.level.getThunderLevel(0.0f),
                    0.001,
                    "Clear Weather must suppress thunder"
            );
            E2EAssertions.assertNear(
                    1.0,
                    client.options.gamma().get(),
                    0.001,
                    "Full Bright must enforce maximum gamma"
            );
            E2EAssertions.assertFalse(
                    client.options.bobView().get(),
                    "No View Bob must disable camera bobbing"
            );

            client.options.gamma().set(0.20);
            client.options.bobView().set(true);
        });
        context.waitTick();
        context.runOnClient(client -> {
            E2EAssertions.assertNear(
                    1.0,
                    client.options.gamma().get(),
                    0.001,
                    "Full Bright must re-apply its invariant each tick"
            );
            E2EAssertions.assertFalse(
                    client.options.bobView().get(),
                    "No View Bob must re-apply its invariant each tick"
            );

            clearWeather.setEnabled(false, client);
            fullBright.setEnabled(false, client);
            noViewBob.setEnabled(false, client);
            E2EAssertions.assertNear(
                    0.35,
                    client.options.gamma().get(),
                    0.001,
                    "Full Bright must restore the previous gamma"
            );
            E2EAssertions.assertTrue(
                    client.options.bobView().get(),
                    "No View Bob must restore the previous option"
            );
        });
    }

    private static void testMovementLifecycle(ClientGameTestContext context) {
        Module autoWalk = E2EAssertions.module("auto_walk");
        Module autoSprint = E2EAssertions.module("auto_sprint");

        context.runOnClient(client -> {
            autoWalk.setEnabled(true, client);
            autoSprint.setEnabled(true, client);
        });
        context.waitFor(client -> client.options.keyUp.isDown(), 20);
        context.waitFor(client -> client.player != null && client.player.isSprinting(), 40);

        context.runOnClient(client -> {
            autoSprint.setEnabled(false, client);
            autoWalk.setEnabled(false, client);
            E2EAssertions.assertFalse(
                    client.options.keyUp.isDown(),
                    "Auto Walk must release forward on disable"
            );
        });
    }

    private static void testAutoTotem(
            ClientGameTestContext context,
            TestServerContext serverContext
    ) {
        resetInventory(serverContext);
        serverContext.runOnServer(server -> {
            ServerPlayer player = serverPlayer(server);
            player.setHealth(10.0f);
            player.getInventory().setItem(9, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            player.inventoryMenu.broadcastFullState();
        });
        context.waitFor(client -> client.player != null
                && client.player.getInventory().getItem(9).is(Items.TOTEM_OF_UNDYING));

        Module autoTotem = E2EAssertions.module("auto_totem");
        context.runOnClient(client -> autoTotem.setEnabled(true, client));
        context.waitFor(client -> client.player != null
                && client.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING), 80);
        waitForServer(
                context,
                serverContext,
                server -> serverPlayer(server).getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                200,
                "Auto Totem inventory click must reach the integrated server"
        );
        context.runOnClient(client -> autoTotem.setEnabled(false, client));
    }

    private static void testAutoArmor(
            ClientGameTestContext context,
            TestServerContext serverContext
    ) {
        resetInventory(serverContext);
        serverContext.runOnServer(server -> {
            ServerPlayer player = serverPlayer(server);
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            player.getInventory().setItem(9, new ItemStack(Items.DIAMOND_HELMET));
            player.inventoryMenu.broadcastFullState();
        });
        context.waitFor(client -> client.player != null
                && client.player.getInventory().getItem(9).is(Items.DIAMOND_HELMET));

        Module autoArmor = E2EAssertions.module("auto_armor");
        context.runOnClient(client -> autoArmor.setEnabled(true, client));
        context.waitFor(client -> client.player != null
                && client.player.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), 100);
        waitForServer(
                context,
                serverContext,
                server -> serverPlayer(server)
                        .getItemBySlot(EquipmentSlot.HEAD)
                        .is(Items.DIAMOND_HELMET),
                200,
                "Auto Armor inventory clicks must reach the integrated server"
        );
        context.runOnClient(client -> autoArmor.setEnabled(false, client));
    }

    private static void testAutoEat(
            ClientGameTestContext context,
            TestServerContext serverContext
    ) {
        resetInventory(serverContext);
        serverContext.runOnServer(server -> {
            ServerPlayer player = serverPlayer(server);
            player.getInventory().setItem(0, new ItemStack(Items.STICK));
            player.getInventory().setItem(1, new ItemStack(Items.BREAD));
            player.getFoodData().setFoodLevel(10);
            player.getFoodData().setSaturation(0.0f);
            player.inventoryMenu.broadcastFullState();
        });
        context.waitFor(client -> client.player != null
                && client.player.getInventory().getItem(1).is(Items.BREAD));

        Module autoEat = E2EAssertions.module("auto_eat");
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedHotbarSlot(0);
            autoEat.setEnabled(true, client);
        });
        context.waitFor(client -> client.options.keyUse.isDown()
                && client.player.getInventory().selected == 1, 40);
        context.runOnClient(client -> {
            autoEat.setEnabled(false, client);
            E2EAssertions.assertFalse(
                    client.options.keyUse.isDown(),
                    "Auto Eat must release use on disable"
            );
            E2EAssertions.assertEquals(
                    0,
                    client.player.getInventory().selected,
                    "Auto Eat must restore the selected hotbar slot"
            );
        });
    }

    private static void testToolWeaponAndTrigger(
            ClientGameTestContext context,
            TestServerContext serverContext
    ) {
        resetInventory(serverContext);
        int zombieId = serverContext.computeOnServer(server -> {
            ServerPlayer player = serverPlayer(server);
            player.teleportTo(0.5, 65.0, 0.5);
            player.getInventory().setItem(0, new ItemStack(Items.STICK));
            player.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            player.getInventory().setItem(2, new ItemStack(Items.DIAMOND_SWORD));
            player.inventoryMenu.broadcastFullState();

            BlockPos blockPosition = new BlockPos(0, 64, 3);
            server.overworld().setBlockAndUpdate(blockPosition, Blocks.OBSIDIAN.defaultBlockState());

            Zombie zombie = EntityType.ZOMBIE.create(
                    server.overworld(),
                    EntitySpawnReason.COMMAND
            );
            if (zombie == null) {
                throw new AssertionError("Could not create E2E zombie");
            }
            zombie.setPos(0.5, 65.0, 2.5);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            if (!server.overworld().addFreshEntity(zombie)) {
                throw new AssertionError("Could not add E2E zombie");
            }
            return zombie.getId();
        });
        context.waitFor(client -> client.player != null
                && client.player.getInventory().getItem(1).is(Items.IRON_PICKAXE)
                && client.player.getInventory().getItem(2).is(Items.DIAMOND_SWORD));
        context.waitFor(client -> client.level != null
                && client.level.getBlockState(new BlockPos(0, 64, 3))
                .is(Blocks.OBSIDIAN));
        context.waitFor(client -> client.level != null && client.level.getEntity(zombieId) != null);

        testAutoTool(context);
        testAutoWeapon(context, zombieId);
        testTriggerBot(context, serverContext, zombieId);
    }

    private static void testAutoTool(ClientGameTestContext context) {
        Module autoTool = E2EAssertions.module("auto_tool");
        BlockPos blockPosition = new BlockPos(0, 64, 3);
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedHotbarSlot(0);
            client.hitResult = new BlockHitResult(
                    Vec3.atCenterOf(blockPosition),
                    Direction.UP,
                    blockPosition,
                    false
            );
            client.options.keyAttack.setDown(true);
            autoTool.setEnabled(true, client);
            ((TickableModule) autoTool).onTick(client);
            E2EAssertions.assertEquals(
                    1,
                    client.player.getInventory().selected,
                    "Auto Tool must select the effective pickaxe"
            );

            client.options.keyAttack.setDown(false);
            ((TickableModule) autoTool).onTick(client);
            E2EAssertions.assertEquals(
                    0,
                    client.player.getInventory().selected,
                    "Auto Tool must restore the previous slot"
            );
            autoTool.setEnabled(false, client);
        });
    }

    private static void testAutoWeapon(ClientGameTestContext context, int zombieId) {
        Module autoWeapon = E2EAssertions.module("auto_weapon");
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedHotbarSlot(0);
            client.hitResult = new EntityHitResult(client.level.getEntity(zombieId));
            client.options.keyAttack.setDown(true);
            autoWeapon.setEnabled(true, client);
            ((TickableModule) autoWeapon).onTick(client);
            E2EAssertions.assertEquals(
                    2,
                    client.player.getInventory().selected,
                    "Auto Weapon must select the strongest safe weapon"
            );

            client.options.keyAttack.setDown(false);
            ((TickableModule) autoWeapon).onTick(client);
            E2EAssertions.assertEquals(
                    0,
                    client.player.getInventory().selected,
                    "Auto Weapon must restore the previous slot"
            );
            autoWeapon.setEnabled(false, client);
        });
    }

    private static void testTriggerBot(
            ClientGameTestContext context,
            TestServerContext serverContext,
            int zombieId
    ) {
        Module triggerBot = E2EAssertions.module("trigger_bot");
        float initialHealth = serverContext.computeOnServer(server ->
                ((Zombie) server.overworld().getEntity(zombieId)).getHealth());

        context.runOnClient(client -> {
            client.hitResult = new EntityHitResult(client.level.getEntity(zombieId));
            triggerBot.setEnabled(true, client);
            ((TickableModule) triggerBot).onTick(client);
        });
        boolean damaged = false;
        for (int tick = 0; tick < 80; tick++) {
            damaged = serverContext.computeOnServer(server -> {
                Zombie zombie = (Zombie) server.overworld().getEntity(zombieId);
                return zombie != null && zombie.getHealth() < initialHealth;
            });
            if (damaged) {
                break;
            }
            context.waitTick();
        }
        E2EAssertions.assertTrue(
                damaged,
                "Trigger Bot attack must damage the target on the integrated server"
        );
        context.runOnClient(client -> triggerBot.setEnabled(false, client));
    }

    private static void resetInventory(TestServerContext serverContext) {
        serverContext.runOnServer(server -> {
            ServerPlayer player = serverPlayer(server);
            player.getInventory().clearContent();
            for (EquipmentSlot slot : List.of(
                    EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS,
                    EquipmentSlot.FEET,
                    EquipmentSlot.OFFHAND
            )) {
                player.setItemSlot(slot, ItemStack.EMPTY);
            }
            player.setHealth(20.0f);
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0f);
            player.inventoryMenu.broadcastFullState();
        });
    }

    private static void waitForServer(
            ClientGameTestContext context,
            TestServerContext serverContext,
            Predicate<net.minecraft.server.MinecraftServer> condition,
            int maximumTicks,
            String failureMessage
    ) {
        for (int tick = 0; tick < maximumTicks; tick++) {
            if (serverContext.computeOnServer(condition::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError(
                failureMessage + " (not observed within " + maximumTicks + " client ticks)"
        );
    }

    private static ServerPlayer serverPlayer(net.minecraft.server.MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() != 1) {
            throw new AssertionError("Expected one E2E player, got " + players.size());
        }
        return players.getFirst();
    }
}
