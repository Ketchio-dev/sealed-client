package dev.sealedclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Confirmed, ownership-aware Elytra Swap service for Minecraft 26.2.
 *
 * <p>The service uses the vanilla inventory-menu PICKUP transaction
 * (source/chest/source) and never mutates an inventory object directly. It
 * starts only from the normal player inventory with an empty carried stack,
 * rejects cursed or near-broken elytras, and never uses the selected hotbar
 * slot as a source.</p>
 *
 * <p>The displaced chest stack is restored only while the service still owns
 * both exact stack locations. Manual scrolling onto the source hotbar slot,
 * changing chest equipment, moving the displaced item, opening a screen, or a
 * server rollback makes the service wait or abandon rather than overwrite
 * player intent. Connection/player changes clear all leases.</p>
 */
public final class ElytraSwapAutomation26 {
    public static final String OWNER = "elytra_swap";
    public static final int PRIORITY = 70;
    public static final Set<MovementActionArbiter26.Channel> CHANNELS =
            Set.of(MovementActionArbiter26.Channel.INVENTORY);
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    1.5,
                    8,
                    true,
                    40,
                    2,
                    8,
                    40
            );

    private static final int CHEST_MENU_SLOT = 6;
    private static final int MAXIMUM_INVENTORY_SLOTS = 36;

    private final ElytraSwapDecisionEngine26 engine;
    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private ElytraSwapDecisionEngine26.Decision pending;
    private int preparedTick = Integer.MIN_VALUE;
    private int preparedSelectedSlot = -1;
    private StackFingerprint preparedElytra;
    private StackFingerprint preparedChest;
    private Lease lease;

    public ElytraSwapAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public ElytraSwapAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        this.engine = new ElytraSwapDecisionEngine26(
                configuration.timing()
        );
    }

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine.setTiming(configuration.timing());
    }

    /**
     * Advances confirmations and submits at most one inventory transaction.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            MovementSafetyPolicy26.Decision safety,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(arbiter, "arbiter");
        clearPrepared();

        LocalPlayer player = client == null ? null : client.player;
        if (player != observedPlayer) {
            observedPlayer = player;
            lease = null;
            engine.reset();
        }

        boolean sessionReady = sessionReady(client);
        long sessionKey = sessionKey(client);
        boolean inventoryReady = inventoryReady(client)
                && safety.canApply()
                && sourceSlotNotSelected(player);
        ItemStack chest = player == null
                ? ItemStack.EMPTY
                : player.getItemBySlot(EquipmentSlot.CHEST);
        ElytraSwapDecisionEngine26.Candidate candidate =
                findCandidate(player);

        Ownership ownership = observeOwnership(player, chest);
        ElytraSwapDecisionEngine26.Observation observation =
                new ElytraSwapDecisionEngine26.Observation(
                        sessionKey,
                        enabled,
                        sessionReady,
                        inventoryReady,
                        player != null && player.onGround(),
                        unsafeEnvironment(player),
                        player == null
                                ? 0.0
                                : Math.max(0.0, player.fallDistance),
                        configuration.fallDistance(),
                        candidate == null
                                ? -1
                                : candidate.inventorySlot(),
                        !chest.isEmpty(),
                        configuration.restoreArmor(),
                        isElytra(chest),
                        ownership.wearingOwnedElytra(),
                        ownership.sourceOwnershipIntact(),
                        ownership.restoreConfirmed(),
                        ownership.contradicted()
                );
        ElytraSwapDecisionEngine26.Decision decision =
                engine.step(observation);
        if (!decision.apply()) {
            return;
        }

        ItemStack source = inventoryItem(player, decision.inventorySlot());
        if (!actionStacksValid(decision, chest, source)) {
            engine.commit(decision, false);
            return;
        }
        pending = decision;
        preparedTick = player.tickCount;
        preparedSelectedSlot = player.getInventory().getSelectedSlot();
        preparedElytra = decision.action()
                == ElytraSwapDecisionEngine26.Action.EQUIP
                ? StackFingerprint.of(source)
                : lease == null ? null : lease.elytra();
        preparedChest = decision.action()
                == ElytraSwapDecisionEngine26.Action.EQUIP
                ? StackFingerprint.of(chest)
                : lease == null ? null : lease.displacedChest();
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    /**
     * Executes the prepared vanilla PICKUP swap after arbitration.
     */
    public boolean execute(
            Minecraft client,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        ElytraSwapDecisionEngine26.Decision decision = pending;
        if (decision == null) {
            return false;
        }
        pending = null;
        if (!arbiter.ownsAll(OWNER, CHANNELS)
                || !preparedStillValid(client, decision)) {
            engine.commit(decision, false);
            clearPreparedMetadata();
            return false;
        }

        boolean applied = pickupSwap(
                client,
                decision.inventorySlot(),
                preparedElytra,
                preparedChest,
                decision.action()
        );
        if (applied
                && decision.action()
                == ElytraSwapDecisionEngine26.Action.EQUIP) {
            lease = new Lease(
                    decision.inventorySlot(),
                    preparedElytra,
                    preparedChest,
                    decision.restoreRequired()
            );
        }
        engine.commit(decision, applied);
        clearPreparedMetadata();
        return applied;
    }

    /**
     * Clears connection-local ownership without attempting a blind mutation.
     */
    public void release(Minecraft client) {
        clearPrepared();
        lease = null;
        observedPlayer = null;
        engine.reset();
    }

    public Status status() {
        ElytraSwapDecisionEngine26.Snapshot snapshot = engine.snapshot();
        return new Status(
                snapshot.phase(),
                lease != null,
                lease == null ? -1 : lease.sourceSlot(),
                snapshot.confirmationTicks(),
                snapshot.cooldownTicks(),
                snapshot.suppressedUntilGround()
        );
    }

    private ElytraSwapDecisionEngine26.Candidate findCandidate(
            LocalPlayer player
    ) {
        if (player == null) {
            return null;
        }
        int selected = player.getInventory().getSelectedSlot();
        int size = Math.min(
                MAXIMUM_INVENTORY_SLOTS,
                player.getInventory().getNonEquipmentItems().size()
        );
        List<ElytraSwapDecisionEngine26.Candidate> candidates =
                new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            candidates.add(new ElytraSwapDecisionEngine26.Candidate(
                    slot,
                    isElytra(stack),
                    LivingEntity.canGlideUsing(
                            stack,
                            EquipmentSlot.CHEST
                    ),
                    remainingDurability(stack),
                    cursed(stack),
                    slot < 9,
                    slot < 9 && slot == selected
            ));
        }
        return ElytraSwapDecisionEngine26.selectBestElytra(
                candidates,
                configuration.minimumDurability()
        ).orElse(null);
    }

    private Ownership observeOwnership(
            LocalPlayer player,
            ItemStack chest
    ) {
        if (lease == null || player == null) {
            return Ownership.none();
        }
        ItemStack source = inventoryItem(player, lease.sourceSlot());
        boolean chestElytra = lease.elytra().matches(chest);
        boolean sourceChest = lease.displacedChest().matches(source);
        boolean sourceElytra = lease.elytra().matches(source);
        boolean chestRestored = lease.displacedChest().matches(chest);
        boolean sourceIntact = lease.restoreRequired()
                ? sourceChest
                : source.isEmpty();
        boolean restored = chestRestored && sourceElytra;

        ElytraSwapDecisionEngine26.Phase phase =
                engine.snapshot().phase();
        boolean contradicted;
        if (phase == ElytraSwapDecisionEngine26.Phase.AWAITING_RESTORE) {
            contradicted = !restored
                    && (!source.isEmpty() && !sourceElytra)
                    && (!chest.isEmpty() && !chestRestored);
        } else {
            contradicted = !chestElytra
                    && !chest.isEmpty()
                    && !sourceIntact;
        }
        return new Ownership(
                chestElytra,
                sourceIntact,
                restored,
                contradicted
        );
    }

    private boolean actionStacksValid(
            ElytraSwapDecisionEngine26.Decision decision,
            ItemStack chest,
            ItemStack source
    ) {
        if (decision.action()
                == ElytraSwapDecisionEngine26.Action.EQUIP) {
            return isSafeElytra(source)
                    && remainingDurability(source)
                    > configuration.minimumDurability()
                    && safeDisplacedChest(chest);
        }
        return lease != null
                && decision.inventorySlot() == lease.sourceSlot()
                && lease.elytra().matches(chest)
                && lease.displacedChest().matches(source);
    }

    private boolean preparedStillValid(
            Minecraft client,
            ElytraSwapDecisionEngine26.Decision decision
    ) {
        if (!inventoryReady(client)
                || client.player != observedPlayer
                || client.player.tickCount != preparedTick
                || client.player.getInventory().getSelectedSlot()
                != preparedSelectedSlot
                || (decision.inventorySlot() < 9
                && decision.inventorySlot()
                == preparedSelectedSlot)) {
            return false;
        }
        ItemStack chest =
                client.player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack source = inventoryItem(
                client.player,
                decision.inventorySlot()
        );
        return actionStacksValid(decision, chest, source)
                && preparedElytra != null
                && preparedChest != null
                && (decision.action()
                == ElytraSwapDecisionEngine26.Action.EQUIP
                ? preparedElytra.matches(source)
                && preparedChest.matches(chest)
                : preparedElytra.matches(chest)
                && preparedChest.matches(source));
    }

    private static boolean pickupSwap(
            Minecraft client,
            int inventorySlot,
            StackFingerprint elytra,
            StackFingerprint chest,
            ElytraSwapDecisionEngine26.Action action
    ) {
        if (!inventoryReady(client)
                || elytra == null
                || chest == null) {
            return false;
        }
        int sourceMenuSlot = inventoryIndexToMenuSlot(inventorySlot);
        int containerId = client.player.inventoryMenu.containerId;
        try {
            client.gameMode.handleContainerInput(
                    containerId,
                    sourceMenuSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            client.gameMode.handleContainerInput(
                    containerId,
                    CHEST_MENU_SLOT,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            client.gameMode.handleContainerInput(
                    containerId,
                    sourceMenuSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        } catch (RuntimeException exception) {
            recoverCarriedStack(client, sourceMenuSlot);
        }

        if (!client.player.containerMenu.getCarried().isEmpty()) {
            recoverCarriedStack(client, sourceMenuSlot);
        }
        if (!client.player.containerMenu.getCarried().isEmpty()) {
            return false;
        }
        ItemStack equipped =
                client.player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack source = inventoryItem(client.player, inventorySlot);
        return action == ElytraSwapDecisionEngine26.Action.EQUIP
                ? elytra.matches(equipped) && chest.matches(source)
                : chest.matches(equipped) && elytra.matches(source);
    }

    /**
     * A failed PICKUP sequence may leave the transaction's carried stack on
     * the cursor. It is safe to return it only to the exact transaction source
     * while that source remains empty; any other state is left untouched for
     * the player rather than guessing a destination.
     */
    private static void recoverCarriedStack(
            Minecraft client,
            int sourceMenuSlot
    ) {
        if (client == null
                || client.player == null
                || client.gameMode == null
                || client.player.containerMenu
                != client.player.inventoryMenu
                || client.player.containerMenu.getCarried().isEmpty()
                || sourceMenuSlot < 0
                || sourceMenuSlot
                >= client.player.inventoryMenu.slots.size()
                || client.player.inventoryMenu
                .getSlot(sourceMenuSlot).hasItem()) {
            return;
        }
        try {
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId,
                    sourceMenuSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
        } catch (RuntimeException ignored) {
            // Leave the visible cursor state to the player; never guess again.
        }
    }

    static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) {
            throw new IllegalArgumentException(
                    "Not a main inventory index: " + inventoryIndex
            );
        }
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    private static boolean sessionReady(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static boolean inventoryReady(Minecraft client) {
        return sessionReady(client)
                && client.gui.screen() == null
                && client.player.containerMenu
                == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    private boolean sourceSlotNotSelected(LocalPlayer player) {
        return player == null
                || lease == null
                || lease.sourceSlot() >= 9
                || lease.sourceSlot()
                != player.getInventory().getSelectedSlot();
    }

    private static boolean unsafeEnvironment(LocalPlayer player) {
        return player == null
                || player.isPassenger()
                || player.isInWater()
                || player.isInLava();
    }

    private static boolean safeDisplacedChest(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null
                && equippable.slot() == EquipmentSlot.CHEST
                && !cursed(stack);
    }

    private static boolean isSafeElytra(ItemStack stack) {
        return isElytra(stack)
                && LivingEntity.canGlideUsing(
                        stack,
                        EquipmentSlot.CHEST
                )
                && !cursed(stack);
    }

    private static boolean isElytra(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() == Items.ELYTRA;
    }

    private static boolean cursed(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && EnchantmentHelper.hasTag(
                        stack,
                        EnchantmentTags.CURSE
                );
    }

    private static int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return stack.isDamageableItem()
                ? Math.max(
                        0,
                        stack.getMaxDamage() - stack.getDamageValue()
                )
                : Integer.MAX_VALUE;
    }

    private static ItemStack inventoryItem(
            LocalPlayer player,
            int inventorySlot
    ) {
        if (player == null
                || inventorySlot < 0
                || inventorySlot >= Math.min(
                36,
                player.getInventory().getNonEquipmentItems().size()
        )) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(inventorySlot);
    }

    private static long sessionKey(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return 0L;
        }
        long level = Integer.toUnsignedLong(
                System.identityHashCode(client.level)
        );
        long player = Integer.toUnsignedLong(
                System.identityHashCode(client.player)
        );
        long key = (level << 32) ^ player;
        return key == Long.MIN_VALUE ? 0L : key;
    }

    private void clearPrepared() {
        pending = null;
        clearPreparedMetadata();
    }

    private void clearPreparedMetadata() {
        preparedTick = Integer.MIN_VALUE;
        preparedSelectedSlot = -1;
        preparedElytra = null;
        preparedChest = null;
    }

    public record Configuration(
            double fallDistance,
            int minimumDurability,
            boolean restoreArmor,
            int confirmationTimeoutTicks,
            int stableConfirmationTicks,
            int actionCooldownTicks,
            int failureCooldownTicks
    ) {
        public Configuration(
                double fallDistance,
                int minimumDurability,
                boolean restoreArmor
        ) {
            this(
                    fallDistance,
                    minimumDurability,
                    restoreArmor,
                    40,
                    2,
                    8,
                    40
            );
        }

        public Configuration {
            if (!Double.isFinite(fallDistance)
                    || fallDistance < 0.5
                    || fallDistance > 8.0) {
                throw new IllegalArgumentException(
                        "fallDistance must be 0.5..8.0"
                );
            }
            if (minimumDurability < 2
                    || minimumDurability > 100) {
                throw new IllegalArgumentException(
                        "minimumDurability must be 2..100"
                );
            }
            new ElytraSwapDecisionEngine26.Timing(
                    confirmationTimeoutTicks,
                    stableConfirmationTicks,
                    actionCooldownTicks,
                    failureCooldownTicks
            );
        }

        ElytraSwapDecisionEngine26.Timing timing() {
            return new ElytraSwapDecisionEngine26.Timing(
                    confirmationTimeoutTicks,
                    stableConfirmationTicks,
                    actionCooldownTicks,
                    failureCooldownTicks
            );
        }
    }

    public record Status(
            ElytraSwapDecisionEngine26.Phase phase,
            boolean ownsEquipment,
            int sourceSlot,
            int confirmationTicks,
            int cooldownTicks,
            boolean manualSuppressed
    ) {
    }

    private record Lease(
            int sourceSlot,
            StackFingerprint elytra,
            StackFingerprint displacedChest,
            boolean restoreRequired
    ) {
    }

    private record Ownership(
            boolean wearingOwnedElytra,
            boolean sourceOwnershipIntact,
            boolean restoreConfirmed,
            boolean contradicted
    ) {
        private static Ownership none() {
            return new Ownership(false, false, false, false);
        }
    }

    private record StackFingerprint(ItemStack expected) {
        private StackFingerprint {
            expected = expected == null
                    ? ItemStack.EMPTY
                    : expected.copy();
        }

        private static StackFingerprint of(ItemStack stack) {
            return new StackFingerprint(stack);
        }

        private boolean matches(ItemStack actual) {
            if (expected.isEmpty()) {
                return actual == null || actual.isEmpty();
            }
            return actual != null
                    && !actual.isEmpty()
                    && expected.getCount() == actual.getCount()
                    && ItemStack.isSameItemSameComponents(
                            expected,
                            actual
                    );
        }
    }
}
