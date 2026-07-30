package dev.b2tclient.v26.utility;

import dev.b2tclient.v26.combat.CombatActionArbiter26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact, ownership-aware Replenish service for Minecraft 26.2.
 *
 * <p>Planning is read-only. Execution happens only after the shared arbiter
 * grants the complete inventory/hotbar/use bundle and the service revalidates
 * the exact player, world, connection, menu, selected slot, tick, cursor,
 * source stack and target stack captured during planning.</p>
 *
 * <p>One replenish is one logical menu operation made from the vanilla
 * source/target/source PICKUP sequence. That sequence fills the partial
 * hotbar stack and returns only the exact remainder to its original source.
 * At most one such operation may begin per client tick. A failed transaction
 * recovers a carried stack only when it still has the transaction's exact
 * components and its original source is empty; it never guesses another
 * destination.</p>
 */
public final class ReplenishAutomation26 {
    public static final String OWNER = "replenish";
    public static final int PRIORITY = 30;
    public static final Set<CombatActionArbiter26.Channel>
            INVENTORY_CHANNELS = Set.of(
            CombatActionArbiter26.Channel.INVENTORY,
            CombatActionArbiter26.Channel.HOTBAR,
            CombatActionArbiter26.Channel.USE
    );
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(16, 4, 2);

    private final ReplenishDecisionEngine26 engine;
    private Configuration configuration;
    private SessionIdentity observedSession;
    private long sessionEpoch;
    private PreparedTransaction pending;
    private boolean manualBaselineReady;
    private int previousMenuStateId = Integer.MIN_VALUE;
    private int previousSelectedSlot = -1;
    private boolean previousInventoryReady;
    private LocalPlayer lastSubmitPlayer;
    private int lastSubmitTick = Integer.MIN_VALUE;

    public ReplenishAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public ReplenishAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new ReplenishDecisionEngine26(
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
        pending = null;
        lastSubmitPlayer = null;
        lastSubmitTick = Integer.MIN_VALUE;
    }

    /**
     * Selects a transaction and submits its complete channel bundle.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            boolean utilityHotbarOwned,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        LocalPlayer submitPlayer =
                client == null ? null : client.player;
        if (submitPlayer != null
                && submitPlayer == lastSubmitPlayer
                && submitPlayer.tickCount == lastSubmitTick) {
            return;
        }
        lastSubmitPlayer = submitPlayer;
        lastSubmitTick = submitPlayer == null
                ? Integer.MIN_VALUE
                : submitPlayer.tickCount;
        pending = null;

        SessionIdentity current = SessionIdentity.capture(client);
        if (!SessionIdentity.same(current, observedSession)) {
            observedSession = current;
            sessionEpoch = nextEpoch(sessionEpoch);
            engine.reset();
            resetManualBaseline();
        }

        boolean sessionReady = sessionReady(client);
        boolean inventoryReady = inventoryReady(client);
        boolean manualChange = observeManualChange(
                client,
                sessionReady,
                inventoryReady
        );
        List<ReplenishDecisionEngine26.Candidate> candidates =
                inventoryReady && !manualChange
                        ? collectCandidates(
                                client.player,
                                configuration.threshold()
                        )
                        : List.of();
        long tick = client == null || client.player == null
                ? 0L
                : Integer.toUnsignedLong(client.player.tickCount);
        ReplenishDecisionEngine26.Decision decision = engine.step(
                new ReplenishDecisionEngine26.Observation(
                        sessionEpoch,
                        tick,
                        enabled,
                        sessionReady,
                        inventoryReady,
                        manualChange,
                        utilityHotbarOwned,
                        configuration.threshold(),
                        candidates
                )
        );
        if (!decision.apply()) {
            return;
        }

        ItemStack source = inventoryItem(
                client.player,
                decision.sourceInventorySlot()
        );
        ItemStack target = inventoryItem(
                client.player,
                decision.targetHotbarSlot()
        );
        StackFingerprint sourceBefore = StackFingerprint.of(source);
        StackFingerprint targetBefore = StackFingerprint.of(target);
        if (!sourceBefore.present()
                || !targetBefore.present()
                || !sourceBefore.sameComponents(targetBefore)
                || sourceBefore.count()
                        != decision.sourceCountBefore()
                || targetBefore.count()
                        != decision.targetCountBefore()) {
            engine.commit(
                    decision,
                    ReplenishDecisionEngine26.CommitResult.DENIED
            );
            return;
        }

        pending = new PreparedTransaction(
                decision,
                observedSession,
                configuration,
                client.player.getInventory().getSelectedSlot(),
                client.player.inventoryMenu.getStateId(),
                client.player.tickCount,
                sourceBefore,
                targetBefore,
                sourceBefore.withCount(decision.sourceCountAfter()),
                targetBefore.withCount(decision.targetCountAfter())
        );
        if (!arbiter.submit(OWNER, PRIORITY, INVENTORY_CHANNELS)) {
            pending = null;
            engine.commit(
                    decision,
                    ReplenishDecisionEngine26.CommitResult.DENIED
            );
        }
    }

    /**
     * Executes at most one prepared logical menu operation.
     *
     * @return {@code true} only when the exact source/target/cursor
     *         postcondition was observed
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedTransaction prepared = pending;
        pending = null;
        if (prepared == null) {
            return false;
        }
        if (!arbiter.ownsAll(OWNER, INVENTORY_CHANNELS)) {
            engine.commit(
                    prepared.decision(),
                    ReplenishDecisionEngine26.CommitResult.DENIED
            );
            return false;
        }
        if (!preparedStillValid(client, prepared)) {
            updateManualBaseline(client);
            engine.commit(
                    prepared.decision(),
                    ReplenishDecisionEngine26.CommitResult.INVALIDATED
            );
            return false;
        }

        TransactionResult result = applyTransaction(client, prepared);
        engine.commit(
                prepared.decision(),
                result == TransactionResult.APPLIED
                        ? ReplenishDecisionEngine26.CommitResult.APPLIED
                        : ReplenishDecisionEngine26.CommitResult
                        .FAILED_AFTER_OPERATION
        );
        updateManualBaseline(client);
        return result == TransactionResult.APPLIED;
    }

    /**
     * Drops tick-local and session-local state without blind inventory writes.
     */
    public void release() {
        pending = null;
        observedSession = null;
        sessionEpoch = nextEpoch(sessionEpoch);
        engine.reset();
        resetManualBaseline();
        lastSubmitPlayer = null;
        lastSubmitTick = Integer.MIN_VALUE;
    }

    public void release(Minecraft client) {
        release();
    }

    public Status status() {
        ReplenishDecisionEngine26.Snapshot snapshot = engine.snapshot();
        return new Status(
                pending != null,
                snapshot.cooldownTicks(),
                snapshot.lastOperationTick(),
                snapshot.outstanding().blockReason()
        );
    }

    private static List<ReplenishDecisionEngine26.Candidate>
            collectCandidates(
            LocalPlayer player,
            int threshold
    ) {
        if (player == null || threshold < 1 || threshold > 63) {
            return List.of();
        }
        int inventorySize = Math.min(
                36,
                player.getInventory().getNonEquipmentItems().size()
        );
        List<ReplenishDecisionEngine26.Candidate> candidates =
                new ArrayList<>(9 * 27);
        for (int hotbarSlot = 0;
                hotbarSlot < Math.min(9, inventorySize);
                hotbarSlot++) {
            ItemStack target = player.getInventory().getItem(hotbarSlot);
            if (target.isEmpty()
                    || !target.isStackable()
                    || target.getCount() > threshold
                    || target.getCount() >= target.getMaxStackSize()) {
                continue;
            }
            for (int sourceSlot = 9;
                    sourceSlot < inventorySize;
                    sourceSlot++) {
                ItemStack source =
                        player.getInventory().getItem(sourceSlot);
                boolean exact = !source.isEmpty()
                        && ItemStack.isSameItemSameComponents(
                                target,
                                source
                        );
                if (!exact) {
                    continue;
                }
                candidates.add(
                        new ReplenishDecisionEngine26.Candidate(
                                hotbarSlot,
                                sourceSlot,
                                target.getCount(),
                                source.getCount(),
                                target.getMaxStackSize(),
                                true,
                                true
                        )
                );
            }
        }
        return List.copyOf(candidates);
    }

    private boolean preparedStillValid(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        if (!inventoryReady(client)
                || !SessionIdentity.same(
                        SessionIdentity.capture(client),
                        prepared.session()
                )
                || !SessionIdentity.same(
                        observedSession,
                        prepared.session()
                )
                || !configuration.equals(
                        prepared.configuration()
                )
                || !capturedContextMatches(
                        prepared.menuStateId(),
                        client.player.inventoryMenu.getStateId(),
                        prepared.playerTick(),
                        client.player.tickCount,
                        prepared.selectedHotbarSlot(),
                        client.player.getInventory().getSelectedSlot()
                )) {
            return false;
        }
        ReplenishDecisionEngine26.Decision decision =
                prepared.decision();
        return prepared.sourceBefore().matches(
                inventoryItem(
                        client.player,
                        decision.sourceInventorySlot()
                )
        ) && prepared.targetBefore().matches(
                inventoryItem(
                        client.player,
                        decision.targetHotbarSlot()
                )
        );
    }

    private static TransactionResult applyTransaction(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        ReplenishDecisionEngine26.Decision decision =
                prepared.decision();
        try {
            click(
                    client,
                    decision.sourceMenuSlot()
            );
            if (!stageOneValid(client, prepared)) {
                recoverCursor(client, prepared);
                return TransactionResult.FAILED;
            }

            click(
                    client,
                    decision.targetMenuSlot()
            );
            if (!stageTwoValid(client, prepared)) {
                recoverCursor(client, prepared);
                return TransactionResult.FAILED;
            }

            click(
                    client,
                    decision.sourceMenuSlot()
            );
        } catch (RuntimeException exception) {
            recoverCursor(client, prepared);
            return TransactionResult.FAILED;
        }

        if (finalStateValid(client, prepared)) {
            return TransactionResult.APPLIED;
        }
        recoverCursor(client, prepared);
        return TransactionResult.FAILED;
    }

    private static boolean stageOneValid(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        return transactionContextValid(client, prepared)
                && inventoryItem(
                        client.player,
                        prepared.decision().sourceInventorySlot()
                ).isEmpty()
                && prepared.targetBefore().matches(
                        inventoryItem(
                                client.player,
                                prepared.decision().targetHotbarSlot()
                        )
                )
                && prepared.sourceBefore().matches(
                        client.player.inventoryMenu.getCarried()
                );
    }

    private static boolean stageTwoValid(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        return transactionContextValid(client, prepared)
                && inventoryItem(
                        client.player,
                        prepared.decision().sourceInventorySlot()
                ).isEmpty()
                && prepared.targetAfter().matches(
                        inventoryItem(
                                client.player,
                                prepared.decision().targetHotbarSlot()
                        )
                )
                && prepared.sourceAfter().matches(
                        client.player.inventoryMenu.getCarried()
                );
    }

    private static boolean finalStateValid(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        return transactionContextValid(client, prepared)
                && client.player.inventoryMenu.getCarried().isEmpty()
                && prepared.targetAfter().matches(
                        inventoryItem(
                                client.player,
                                prepared.decision().targetHotbarSlot()
                        )
                )
                && prepared.sourceAfter().matches(
                        inventoryItem(
                                client.player,
                                prepared.decision().sourceInventorySlot()
                        )
                );
    }

    private static boolean transactionContextValid(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        return sessionReady(client)
                && client.gui.screen() == null
                && client.player.containerMenu
                        == client.player.inventoryMenu
                && SessionIdentity.same(
                        SessionIdentity.capture(client),
                        prepared.session()
                )
                && capturedContextMatches(
                        prepared.menuStateId(),
                        client.player.inventoryMenu.getStateId(),
                        prepared.playerTick(),
                        client.player.tickCount,
                        prepared.selectedHotbarSlot(),
                        client.player.getInventory().getSelectedSlot()
                );
    }

    private static void click(Minecraft client, int menuSlot) {
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                menuSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
    }

    /**
     * Returns only an owned cursor remainder to its exact empty source.
     */
    private static void recoverCursor(
            Minecraft client,
            PreparedTransaction prepared
    ) {
        if (!transactionContextValid(client, prepared)) {
            return;
        }
        ItemStack carried =
                client.player.inventoryMenu.getCarried();
        ItemStack source = inventoryItem(
                client.player,
                prepared.decision().sourceInventorySlot()
        );
        if (carried.isEmpty()
                || !source.isEmpty()
                || !ownedRecoveryStack(
                        prepared.sourceBefore(),
                        prepared.sourceAfter(),
                        carried
                )) {
            return;
        }
        try {
            click(
                    client,
                    prepared.decision().sourceMenuSlot()
            );
        } catch (RuntimeException ignored) {
            // Leave a visible cursor state to the player; never guess again.
        }
    }

    static boolean ownedRecoveryStack(
            StackFingerprint sourceBefore,
            StackFingerprint sourceAfter,
            ItemStack carried
    ) {
        return sourceBefore != null
                && sourceAfter != null
                && carried != null
                && !carried.isEmpty()
                && ReplenishDecisionEngine26.ownedRecoveryCandidate(
                        sourceBefore.count(),
                        sourceAfter.count(),
                        carried.getCount(),
                        sourceBefore.sameComponents(carried)
                                && (sourceAfter.count() == 0
                                || sourceAfter.sameComponents(carried))
                );
    }

    static boolean capturedContextMatches(
            int preparedStateId,
            int currentStateId,
            int preparedTick,
            int currentTick,
            int preparedSelectedSlot,
            int currentSelectedSlot
    ) {
        return preparedStateId == currentStateId
                && preparedTick == currentTick
                && preparedSelectedSlot >= 0
                && preparedSelectedSlot < 9
                && preparedSelectedSlot == currentSelectedSlot;
    }

    private boolean observeManualChange(
            Minecraft client,
            boolean sessionReady,
            boolean inventoryReady
    ) {
        if (!sessionReady
                || client == null
                || client.player == null) {
            resetManualBaseline();
            return false;
        }
        int stateId = client.player.inventoryMenu.getStateId();
        int selected =
                client.player.getInventory().getSelectedSlot();
        if (!manualBaselineReady) {
            manualBaselineReady = true;
            previousMenuStateId = stateId;
            previousSelectedSlot = selected;
            previousInventoryReady = inventoryReady;
            return false;
        }
        boolean changed = stateId != previousMenuStateId
                || selected != previousSelectedSlot
                || inventoryReady != previousInventoryReady;
        previousMenuStateId = stateId;
        previousSelectedSlot = selected;
        previousInventoryReady = inventoryReady;
        return changed;
    }

    private void updateManualBaseline(Minecraft client) {
        if (client == null
                || client.player == null
                || !sessionReady(client)) {
            resetManualBaseline();
            return;
        }
        manualBaselineReady = true;
        previousMenuStateId =
                client.player.inventoryMenu.getStateId();
        previousSelectedSlot =
                client.player.getInventory().getSelectedSlot();
        previousInventoryReady = inventoryReady(client);
    }

    private void resetManualBaseline() {
        manualBaselineReady = false;
        previousMenuStateId = Integer.MIN_VALUE;
        previousSelectedSlot = -1;
        previousInventoryReady = false;
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
                && client.player.inventoryMenu.getCarried().isEmpty();
    }

    private static ItemStack inventoryItem(
            LocalPlayer player,
            int inventorySlot
    ) {
        if (player == null
                || inventorySlot < 0
                || inventorySlot >= Math.min(
                        36,
                        player.getInventory()
                                .getNonEquipmentItems().size()
                )) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(inventorySlot);
    }

    private static long nextEpoch(long current) {
        long next = current + 1L;
        return next == Long.MIN_VALUE || next == 0L ? 1L : next;
    }

    public record Configuration(
            int threshold,
            int delayTicks,
            int failureCooldownTicks
    ) {
        public Configuration(int threshold, int delayTicks) {
            this(
                    threshold,
                    delayTicks,
                    Math.min(delayTicks, 2)
            );
        }

        public Configuration {
            if (threshold < 1 || threshold > 63) {
                throw new IllegalArgumentException(
                        "threshold must be 1..63"
                );
            }
            new ReplenishDecisionEngine26.Timing(
                    delayTicks,
                    failureCooldownTicks
            );
        }

        ReplenishDecisionEngine26.Timing timing() {
            return new ReplenishDecisionEngine26.Timing(
                    delayTicks,
                    failureCooldownTicks
            );
        }
    }

    public record Status(
            boolean pending,
            int cooldownTicks,
            long lastOperationTick,
            ReplenishDecisionEngine26.BlockReason blockReason
    ) {
    }

    private record PreparedTransaction(
            ReplenishDecisionEngine26.Decision decision,
            SessionIdentity session,
            Configuration configuration,
            int selectedHotbarSlot,
            int menuStateId,
            int playerTick,
            StackFingerprint sourceBefore,
            StackFingerprint targetBefore,
            StackFingerprint sourceAfter,
            StackFingerprint targetAfter
    ) {
    }

    private enum TransactionResult {
        APPLIED,
        FAILED
    }

    private record SessionIdentity(
            Object connection,
            Object level,
            LocalPlayer player,
            Object gameMode,
            Object inventoryMenu,
            int containerId
    ) {
        private static SessionIdentity capture(Minecraft client) {
            if (client == null
                    || client.player == null
                    || client.level == null
                    || client.gameMode == null
                    || client.getConnection() == null) {
                return null;
            }
            return new SessionIdentity(
                    client.getConnection(),
                    client.level,
                    client.player,
                    client.gameMode,
                    client.player.inventoryMenu,
                    client.player.inventoryMenu.containerId
            );
        }

        private static boolean same(
                SessionIdentity first,
                SessionIdentity second
        ) {
            return first == second
                    || first != null
                    && second != null
                    && first.connection == second.connection
                    && first.level == second.level
                    && first.player == second.player
                    && first.gameMode == second.gameMode
                    && first.inventoryMenu == second.inventoryMenu
                    && first.containerId == second.containerId;
        }
    }

    /**
     * Immutable exact ItemStack identity: components plus count.
     */
    static final class StackFingerprint {
        private final ItemStack expected;

        private StackFingerprint(ItemStack stack) {
            expected = stack == null
                    ? ItemStack.EMPTY
                    : stack.copy();
        }

        static StackFingerprint of(ItemStack stack) {
            return new StackFingerprint(stack);
        }

        StackFingerprint withCount(int count) {
            if (count <= 0 || expected.isEmpty()) {
                return new StackFingerprint(ItemStack.EMPTY);
            }
            ItemStack copy = expected.copy();
            copy.setCount(count);
            return new StackFingerprint(copy);
        }

        boolean present() {
            return !expected.isEmpty();
        }

        int count() {
            return expected.isEmpty() ? 0 : expected.getCount();
        }

        boolean matches(ItemStack actual) {
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

        boolean sameComponents(StackFingerprint other) {
            return other != null
                    && sameComponents(other.expected);
        }

        boolean sameComponents(ItemStack actual) {
            return !expected.isEmpty()
                    && actual != null
                    && !actual.isEmpty()
                    && ItemStack.isSameItemSameComponents(
                            expected,
                            actual
                    );
        }
    }
}
