package dev.b2tclient.v26.automation;

import dev.b2tclient.v26.utility.UtilityActionArbiter26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Small, stateful 26.2 hooks for utility modules that only need hotbar input.
 *
 * <p>The adapter owns every key/slot change it makes and restores the previous
 * state when a module stops, a screen opens, or the connection is released.
 * Auto Eat has priority over Auto Tool so the two features cannot fight over
 * the selected hotbar slot.</p>
 */
public final class UtilityAutomation26 {
    public static final String AUTO_EAT_OWNER = "auto_eat";
    public static final String AUTO_TOOL_OWNER = "auto_tool";
    public static final int AUTO_EAT_PRIORITY = 60;
    public static final int AUTO_TOOL_PRIORITY = 40;
    public static final Set<UtilityActionArbiter26.Channel>
            AUTO_EAT_CHANNELS = Set.of(
                    UtilityActionArbiter26.Channel.HOTBAR,
                    UtilityActionArbiter26.Channel.USE
            );
    public static final Set<UtilityActionArbiter26.Channel>
            AUTO_TOOL_CHANNELS = Set.of(
                    UtilityActionArbiter26.Channel.HOTBAR
            );
    static final int AUTO_EAT_HUNGER = 14;
    static final int AUTO_TOOL_MINIMUM_DURABILITY = 5;

    private boolean eating;
    private boolean useKeyWasDown;
    private int eatPreviousSlot = -1;
    private int eatAppliedSlot = -1;

    private boolean toolSwitched;
    private int toolPreviousSlot = -1;
    private int toolAppliedSlot = -1;
    private final ReconnectSchedule reconnectSchedule = new ReconnectSchedule();
    private ServerData lastServer;

    public void tick(
            Minecraft client,
            boolean autoEatEnabled,
            boolean autoToolEnabled,
            boolean autoReconnectEnabled
    ) {
        applyAutoEat(client, autoEatEnabled);
        if (eating) {
            releaseAutoTool(client);
        } else {
            applyAutoTool(client, autoToolEnabled);
        }
        applyAutoReconnect(client, autoReconnectEnabled);
    }

    /**
     * Collects legacy hotbar utility work into the shared utility arbiter.
     * This method is read-only apart from releasing stale leases that no
     * longer have a valid action.
     */
    public void submit(
            Minecraft client,
            boolean autoEatEnabled,
            boolean autoToolEnabled,
            UtilityActionArbiter26 arbiter
    ) {
        boolean wantsEat = wantsAutoEat(client, autoEatEnabled);
        if (wantsEat) {
            arbiter.submit(
                    AUTO_EAT_OWNER,
                    AUTO_EAT_PRIORITY,
                    AUTO_EAT_CHANNELS
            );
            return;
        }
        if (wantsAutoTool(client, autoToolEnabled)) {
            arbiter.submit(
                    AUTO_TOOL_OWNER,
                    AUTO_TOOL_PRIORITY,
                    AUTO_TOOL_CHANNELS
            );
        }
    }

    /**
     * Executes only the legacy action granted by the utility arbiter.
     */
    public void execute(
            Minecraft client,
            boolean autoEatEnabled,
            boolean autoToolEnabled,
            UtilityActionArbiter26 arbiter
    ) {
        if (arbiter.ownsAll(AUTO_EAT_OWNER, AUTO_EAT_CHANNELS)) {
            applyAutoEat(client, autoEatEnabled);
            releaseAutoTool(client);
            return;
        }
        releaseAutoEat(client);
        if (arbiter.ownsAll(AUTO_TOOL_OWNER, AUTO_TOOL_CHANNELS)) {
            applyAutoTool(client, autoToolEnabled);
        } else {
            releaseAutoTool(client);
        }
    }

    /**
     * Reconnection does not own an in-world action channel and therefore runs
     * outside the inventory/use arbitration phase.
     */
    public void tickReconnect(Minecraft client, boolean enabled) {
        applyAutoReconnect(client, enabled);
    }

    public void release(Minecraft client) {
        releaseAutoEat(client);
        releaseAutoTool(client);
    }

    /**
     * Gives a resolved combat or movement action immediate precedence over a
     * lease retained from the previous utility tick.
     */
    public void yieldOwnedActions(Minecraft client) {
        releaseAutoEat(client);
        releaseAutoTool(client);
    }

    /**
     * Reports whether this service currently owns the selected hotbar slot.
     * Combat automation uses this to avoid replacing food or a mining tool in
     * the middle of an owned utility action.
     */
    public boolean ownsHotbar() {
        return eating || toolSwitched;
    }

    private static boolean wantsAutoEat(
            Minecraft client,
            boolean enabled
    ) {
        if (!enabled
                || client == null
                || client.player == null
                || client.gui.screen() != null
                || client.player.getFoodData().getFoodLevel()
                > AUTO_EAT_HUNGER) {
            return false;
        }
        List<FoodCandidate> candidates = new ArrayList<>();
        int hotbarSize = Math.min(
                9,
                client.player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                candidates.add(new FoodCandidate(
                        slot,
                        food.nutrition(),
                        !isUnsafeFood(stack.getItem())
                ));
            }
        }
        return selectBestFood(candidates) >= 0;
    }

    private static boolean wantsAutoTool(
            Minecraft client,
            boolean enabled
    ) {
        if (!enabled
                || client == null
                || client.player == null
                || client.level == null
                || client.gui.screen() != null
                || !client.options.keyAttack.isDown()
                || !(client.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        BlockState state = client.level.getBlockState(hit.getBlockPos());
        int selected = client.player.getInventory().getSelectedSlot();
        List<ToolCandidate> candidates = new ArrayList<>();
        int hotbarSize = Math.min(
                9,
                client.player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue()
                    : Integer.MAX_VALUE;
            candidates.add(new ToolCandidate(
                    slot,
                    remaining > AUTO_TOOL_MINIMUM_DURABILITY,
                    stack.isCorrectToolForDrops(state),
                    stack.getDestroySpeed(state)
            ));
        }
        int bestSlot = selectBestTool(candidates, selected);
        return bestSlot >= 0 && bestSlot != selected;
    }

    private void applyAutoEat(Minecraft client, boolean enabled) {
        if (!enabled
                || client.player == null
                || client.gui.screen() != null
                || client.player.getFoodData().getFoodLevel() > AUTO_EAT_HUNGER) {
            releaseAutoEat(client);
            return;
        }

        List<FoodCandidate> candidates = new ArrayList<>();
        int hotbarSize = Math.min(
                9,
                client.player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                candidates.add(new FoodCandidate(
                        slot,
                        food.nutrition(),
                        !isUnsafeFood(stack.getItem())
                ));
            }
        }

        int slot = selectBestFood(candidates);
        if (slot < 0) {
            releaseAutoEat(client);
            return;
        }
        if (!eating) {
            eatPreviousSlot = client.player.getInventory().getSelectedSlot();
            useKeyWasDown = client.options.keyUse.isDown();
        }
        client.player.getInventory().setSelectedSlot(slot);
        client.options.keyUse.setDown(true);
        eating = true;
        eatAppliedSlot = slot;
    }

    private void applyAutoTool(Minecraft client, boolean enabled) {
        if (!enabled
                || client.player == null
                || client.level == null
                || client.gui.screen() != null
                || !client.options.keyAttack.isDown()
                || !(client.hitResult instanceof BlockHitResult hit)) {
            releaseAutoTool(client);
            return;
        }

        BlockState state = client.level.getBlockState(hit.getBlockPos());
        int selected = client.player.getInventory().getSelectedSlot();
        List<ToolCandidate> candidates = new ArrayList<>();
        int hotbarSize = Math.min(
                9,
                client.player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue()
                    : Integer.MAX_VALUE;
            candidates.add(new ToolCandidate(
                    slot,
                    remaining > AUTO_TOOL_MINIMUM_DURABILITY,
                    stack.isCorrectToolForDrops(state),
                    stack.getDestroySpeed(state)
            ));
        }

        int bestSlot = selectBestTool(candidates, selected);
        if (bestSlot < 0 || bestSlot == selected) {
            return;
        }
        if (!toolSwitched) {
            toolPreviousSlot = selected;
        }
        client.player.getInventory().setSelectedSlot(bestSlot);
        toolSwitched = true;
        toolAppliedSlot = bestSlot;
    }

    private void applyAutoReconnect(Minecraft client, boolean enabled) {
        ServerData connectedServer = client.getCurrentServer();
        if (client.player != null && connectedServer != null) {
            lastServer = copyServer(connectedServer);
            reconnectSchedule.connected();
            return;
        }
        if (!enabled) {
            reconnectSchedule.connected();
            return;
        }
        if (!(client.gui.screen() instanceof DisconnectedScreen disconnected)
                || lastServer == null) {
            if (!(client.gui.screen() instanceof ConnectScreen)) {
                reconnectSchedule.clearCountdown();
            }
            return;
        }
        if (!reconnectSchedule.tick(disconnected, 200, 5)) {
            return;
        }
        try {
            ConnectScreen.startConnecting(
                    disconnected,
                    client,
                    ServerAddress.parseString(lastServer.ip),
                    lastServer,
                    false,
                    null
            );
        } catch (RuntimeException exception) {
            // A malformed or stale address must not create a per-tick retry loop.
            reconnectSchedule.retryAfter(200);
        }
    }

    private void releaseAutoEat(Minecraft client) {
        if (!eating) {
            return;
        }
        client.options.keyUse.setDown(useKeyWasDown);
        if (client.player != null
                && stillOwnsAppliedSlot(
                client.player.getInventory().getSelectedSlot(),
                eatAppliedSlot
        )
                && validHotbarSlot(eatPreviousSlot)) {
            client.player.getInventory().setSelectedSlot(eatPreviousSlot);
        }
        eating = false;
        useKeyWasDown = false;
        eatPreviousSlot = -1;
        eatAppliedSlot = -1;
    }

    private void releaseAutoTool(Minecraft client) {
        if (toolSwitched
                && client.player != null
                && stillOwnsAppliedSlot(
                client.player.getInventory().getSelectedSlot(),
                toolAppliedSlot
        )
                && validHotbarSlot(toolPreviousSlot)) {
            client.player.getInventory().setSelectedSlot(toolPreviousSlot);
        }
        toolSwitched = false;
        toolPreviousSlot = -1;
        toolAppliedSlot = -1;
    }

    static int selectBestFood(List<FoodCandidate> candidates) {
        int bestSlot = -1;
        int bestNutrition = -1;
        for (FoodCandidate candidate : candidates) {
            if (candidate.safe() && candidate.nutrition() > bestNutrition) {
                bestSlot = candidate.slot();
                bestNutrition = candidate.nutrition();
            }
        }
        return bestSlot;
    }

    static int selectBestTool(List<ToolCandidate> candidates, int selectedSlot) {
        int bestSlot = selectedSlot;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ToolCandidate candidate : candidates) {
            if (!candidate.valid()) {
                continue;
            }
            double score = (candidate.correctForDrops() ? 1_000.0 : 0.0)
                    + candidate.destroySpeed();
            if (score > bestScore) {
                bestSlot = candidate.slot();
                bestScore = score;
            }
        }
        return bestSlot;
    }

    private static boolean isUnsafeFood(Item item) {
        return item == Items.ROTTEN_FLESH
                || item == Items.SPIDER_EYE
                || item == Items.POISONOUS_POTATO
                || item == Items.PUFFERFISH
                || item == Items.CHORUS_FRUIT;
    }

    private static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    static boolean stillOwnsAppliedSlot(int selectedSlot, int appliedSlot) {
        return validHotbarSlot(appliedSlot) && selectedSlot == appliedSlot;
    }

    private static ServerData copyServer(ServerData source) {
        ServerData copy = new ServerData(source.name, source.ip, source.type());
        copy.copyFrom(source);
        return copy;
    }

    record FoodCandidate(int slot, int nutrition, boolean safe) {
    }

    record ToolCandidate(
            int slot,
            boolean valid,
            boolean correctForDrops,
            float destroySpeed
    ) {
    }

    static final class ReconnectSchedule {
        private Object observedScreen;
        private int remainingTicks = -1;
        private int attempts;

        boolean tick(Object screenIdentity, int delayTicks, int maximumAttempts) {
            if (screenIdentity == null || attempts >= Math.max(0, maximumAttempts)) {
                return false;
            }
            if (observedScreen != screenIdentity) {
                observedScreen = screenIdentity;
                remainingTicks = Math.max(0, delayTicks);
            }
            if (remainingTicks-- > 0) {
                return false;
            }
            attempts++;
            remainingTicks = -1;
            return true;
        }

        void retryAfter(int delayTicks) {
            attempts = Math.max(0, attempts - 1);
            remainingTicks = Math.max(0, delayTicks);
        }

        void clearCountdown() {
            observedScreen = null;
            remainingTicks = -1;
        }

        void connected() {
            clearCountdown();
            attempts = 0;
        }

        int attempts() {
            return attempts;
        }
    }
}
