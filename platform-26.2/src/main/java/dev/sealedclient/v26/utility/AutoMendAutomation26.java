package dev.sealedclient.v26.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ownership-aware Minecraft 26.2 Auto Mend service.
 */
public final class AutoMendAutomation26 {
    public static final String OWNER = "auto_mend";
    public static final int PRIORITY = 60;
    public static final Set<UtilityActionArbiter26.Channel> CHANNELS =
            Set.of(
                    UtilityActionArbiter26.Channel.HOTBAR,
                    UtilityActionArbiter26.Channel.ROTATION,
                    UtilityActionArbiter26.Channel.USE
            );
    public static final AutoMendDecisionEngine26.Configuration
            DEFAULT_CONFIGURATION =
            new AutoMendDecisionEngine26.Configuration(
                    65,
                    90,
                    2,
                    true,
                    20
            );

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };
    private static final float MENDING_PITCH = 90.0F;

    private final AutoMendDecisionEngine26 engine;
    private AutoMendDecisionEngine26.Configuration configuration;
    private LocalPlayer observedPlayer;
    private Object observedConnection;
    private Object observedLevel;
    private Object sessionIdentity;
    private AutoMendDecisionEngine26.Decision pending;
    private Lease lease;
    private YieldedLease yieldedLease;
    private boolean yieldedManualInterference;
    private int manualYieldTicks;

    public AutoMendAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public AutoMendAutomation26(
            AutoMendDecisionEngine26.Configuration configuration
    ) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new AutoMendDecisionEngine26(configuration);
    }

    public void setConfiguration(
            AutoMendDecisionEngine26.Configuration configuration
    ) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine.setConfiguration(configuration);
    }

    public AutoMendDecisionEngine26.Configuration configuration() {
        return configuration;
    }

    /**
     * Evaluates live armor and submits a complete hotbar/rotation/use bundle.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            boolean safetyReady,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pending = null;

        LocalPlayer player = client == null ? null : client.player;
        boolean sessionChanged = observeSession(client, player);
        boolean sneaking = client != null
                && client.options.keyShift.isDown();
        if (sessionChanged) {
            lease = null;
            yieldedLease = null;
            yieldedManualInterference = false;
            manualYieldTicks = 0;
            engine.reset();
        }
        boolean manualInterference = lease != null
                && player != null
                && !leaseStillOwned(
                        player.getInventory().getSelectedSlot(),
                        player.getXRot(),
                        lease.appliedSlot()
                );
        manualInterference |= yieldedManualInterference;
        yieldedManualInterference = false;
        if (manualInterference) {
            restoreLease(player);
            yieldedLease = null;
            engine.reset();
        }
        manualYieldTicks = nextManualYieldTicks(
                manualYieldTicks,
                enabled,
                configuration.requireSneak(),
                sneaking,
                manualInterference
        );
        if (manualYieldTicks > 0) {
            restoreLease(player);
            yieldedLease = null;
            return;
        }

        Bottle bottle = findBottle(player);
        List<AutoMendDecisionEngine26.ArmorPiece> armor =
                armorSnapshot(player);
        AutoMendDecisionEngine26.Observation observation =
                new AutoMendDecisionEngine26.Observation(
                        sessionIdentity,
                        enabled,
                        sessionReady(client, player),
                        safetyReady,
                        client != null && client.gui.screen() == null,
                        player != null
                                && player.isAlive()
                                && !player.isSpectator(),
                        sneaking,
                        player == null
                                ? -1
                                : player.getInventory().getSelectedSlot(),
                        player == null ? 0.0F : player.getXRot(),
                        bottle.slot(),
                        bottle.count(),
                        armor
                );
        AutoMendDecisionEngine26.Decision decision =
                engine.step(observation);
        if (!decision.requiresOwnership()) {
            restoreLease(player);
            yieldedLease = null;
            return;
        }
        pending = decision;
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    /**
     * Revalidates actual durability and performs at most one vanilla use.
     */
    public boolean execute(
            Minecraft client,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        AutoMendDecisionEngine26.Decision decision = pending;
        pending = null;
        if (decision == null) {
            return false;
        }
        if (!arbiter.ownsAll(OWNER, CHANNELS)
                || !stillValid(client, decision)) {
            engine.commit(decision, false);
            restoreLease(client == null ? null : client.player);
            yieldedLease = null;
            return false;
        }

        LocalPlayer player = client.player;
        boolean resumedSameTick = canResumeWarmLease(
                yieldedLease,
                player,
                decision.bottleSlot()
        );
        boolean leaseWarmup = lease == null
                ? !resumedSameTick
                : lease.appliedSlot() != decision.bottleSlot();
        if (lease == null) {
            lease = new Lease(
                    player.getInventory().getSelectedSlot(),
                    player.getXRot(),
                    decision.bottleSlot()
            );
        } else if (lease.appliedSlot() != decision.bottleSlot()) {
            lease = new Lease(
                    lease.previousSlot(),
                    lease.previousPitch(),
                    decision.bottleSlot()
            );
        }
        player.getInventory().setSelectedSlot(decision.bottleSlot());
        player.setXRot(MENDING_PITCH);
        yieldedLease = null;
        if (decision.action()
                == AutoMendDecisionEngine26.Action.HOLD
                || leaseWarmup) {
            return false;
        }

        InteractionResult result = client.gameMode.useItem(
                player,
                InteractionHand.MAIN_HAND
        );
        boolean applied = result.consumesAction();
        // The normal game-mode route may already have emitted a use packet
        // even when the local result is PASS. Quarantine the attempt until
        // actual durability changes or confirmation times out.
        engine.commit(decision, true);
        restoreLease(player);
        return applied;
    }

    /**
     * Restores the player's baseline before combat preparation without
     * discarding mend confirmation/cooldown state. A lease resumed later in
     * the same player tick remains warm only when the baseline stayed exactly
     * owned in between.
     */
    public void yieldOwnedLease(Minecraft client) {
        pending = null;
        LocalPlayer player = client == null ? null : client.player;
        Lease current = lease;
        yieldedManualInterference =
                detectsYieldedManualInterference(
                        current != null,
                        player != null && player == observedPlayer,
                        player == null
                                ? -1
                                : player.getInventory().getSelectedSlot(),
                        player == null ? 0.0F : player.getXRot(),
                        current == null ? -1 : current.appliedSlot()
                );
        yieldedLease = current != null
                && player != null
                && player == observedPlayer
                && leaseStillOwned(
                player.getInventory().getSelectedSlot(),
                player.getXRot(),
                current.appliedSlot()
        )
                ? new YieldedLease(
                player.tickCount,
                current.previousSlot(),
                current.previousPitch(),
                current.appliedSlot()
        )
                : null;
        restoreLease(player);
    }

    public void release(Minecraft client) {
        pending = null;
        restoreLease(client == null ? null : client.player);
        observedPlayer = null;
        observedConnection = null;
        observedLevel = null;
        sessionIdentity = null;
        yieldedLease = null;
        yieldedManualInterference = false;
        manualYieldTicks = 0;
        engine.reset();
    }

    public AutoMendDecisionEngine26.Snapshot status() {
        return engine.snapshot();
    }

    private boolean observeSession(
            Minecraft client,
            LocalPlayer player
    ) {
        Object connection = client == null
                ? null
                : client.getConnection();
        Object level = client == null ? null : client.level;
        if (player == observedPlayer
                && connection == observedConnection
                && level == observedLevel) {
            return false;
        }
        observedPlayer = player;
        observedConnection = connection;
        observedLevel = level;
        sessionIdentity = player == null
                || connection == null
                || level == null
                ? null
                : new Object();
        return true;
    }

    private static boolean sessionReady(
            Minecraft client,
            LocalPlayer player
    ) {
        return client != null
                && player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null;
    }

    private static Bottle findBottle(LocalPlayer player) {
        if (player == null) {
            return Bottle.NONE;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.EXPERIENCE_BOTTLE)
                    && stack.getCount() > 0) {
                return new Bottle(slot, stack.getCount());
            }
        }
        return Bottle.NONE;
    }

    private static List<AutoMendDecisionEngine26.ArmorPiece>
            armorSnapshot(LocalPlayer player) {
        if (player == null) {
            return List.of();
        }
        List<AutoMendDecisionEngine26.ArmorPiece> pieces =
                new ArrayList<>(ARMOR_SLOTS.length);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isDamageableItem()
                    || !EnchantmentHelper.has(
                    stack,
                    EnchantmentEffectComponents.REPAIR_WITH_XP
            )) {
                continue;
            }
            pieces.add(new AutoMendDecisionEngine26.ArmorPiece(
                    slot.getName(),
                    itemToken(stack),
                    stack.getDamageValue(),
                    stack.getMaxDamage()
            ));
        }
        return List.copyOf(pieces);
    }

    private static String itemToken(ItemStack stack) {
        ItemStack normalized = stack.copy();
        normalized.setDamageValue(0);
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                + ":"
                + Integer.toUnsignedString(
                ItemStack.hashItemAndComponents(normalized),
                16
        );
    }

    static boolean leaseStillOwned(
            int selectedSlot,
            float pitch,
            int appliedSlot
    ) {
        return selectedSlot == appliedSlot
                && Float.compare(pitch, MENDING_PITCH) == 0;
    }

    static int nextManualYieldTicks(
            int currentTicks,
            boolean enabled,
            boolean requireSneak,
            boolean sneaking,
            boolean manualInterference
    ) {
        if (!enabled || requireSneak && !sneaking) {
            return 0;
        }
        if (manualInterference) {
            return 20;
        }
        return Math.max(0, currentTicks - 1);
    }

    static boolean canResumeWarmLease(
            YieldedLease yielded,
            LocalPlayer player,
            int requestedBottleSlot
    ) {
        return yielded != null
                && player != null
                && yielded.playerTick() == player.tickCount
                && yielded.appliedSlot() == requestedBottleSlot
                && player.getInventory().getSelectedSlot()
                == yielded.baselineSlot()
                && Float.compare(
                player.getXRot(),
                yielded.baselinePitch()
        ) == 0;
    }

    static boolean canResumeWarmLease(
            int currentTick,
            int selectedSlot,
            float pitch,
            int requestedBottleSlot,
            int yieldedTick,
            int baselineSlot,
            float baselinePitch,
            int yieldedBottleSlot
    ) {
        return currentTick == yieldedTick
                && selectedSlot == baselineSlot
                && Float.compare(pitch, baselinePitch) == 0
                && requestedBottleSlot == yieldedBottleSlot;
    }

    static boolean detectsYieldedManualInterference(
            boolean hasLease,
            boolean samePlayer,
            int selectedSlot,
            float pitch,
            int appliedSlot
    ) {
        return hasLease
                && samePlayer
                && !leaseStillOwned(selectedSlot, pitch, appliedSlot);
    }

    private static boolean stillValid(
            Minecraft client,
            AutoMendDecisionEngine26.Decision decision
    ) {
        LocalPlayer player = client == null ? null : client.player;
        if (!sessionReady(client, player)
                || client.gui.screen() != null
                || !player.isAlive()
                || player.isSpectator()
                || decision.bottleSlot() < 0
                || decision.bottleSlot() > 8) {
            return false;
        }
        ItemStack bottle = player.getInventory()
                .getItem(decision.bottleSlot());
        return bottle.is(Items.EXPERIENCE_BOTTLE)
                && bottle.getCount() == decision.bottleCount()
                && armorSnapshot(player).equals(decision.armor());
    }

    private void restoreLease(LocalPlayer player) {
        Lease current = lease;
        lease = null;
        if (current == null
                || player == null
                || player != observedPlayer) {
            return;
        }
        if (player.getInventory().getSelectedSlot()
                == current.appliedSlot()) {
            player.getInventory().setSelectedSlot(
                    current.previousSlot()
            );
        }
        if (Float.compare(player.getXRot(), MENDING_PITCH) == 0) {
            player.setXRot(current.previousPitch());
        }
    }

    private record Bottle(int slot, int count) {
        private static final Bottle NONE = new Bottle(-1, 0);
    }

    private record Lease(
            int previousSlot,
            float previousPitch,
            int appliedSlot
    ) {
    }

    record YieldedLease(
            int playerTick,
            int baselineSlot,
            float baselinePitch,
            int appliedSlot
    ) {
    }
}
