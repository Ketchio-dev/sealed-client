package dev.sealedclient.v26.utility;

import dev.sealedclient.v26.combat.CombatActionArbiter26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Confirmed Inventory Manager adapter for Minecraft 26.2.
 *
 * <p>Submit only snapshots a lossless merge and claims the shared inventory
 * channels. Execute revalidates the exact session, player menu, selected slot,
 * cursor, source stack, and target stack before sending one logical
 * source/target PICKUP transaction. Every intermediate state is checked
 * and a failed pickup is returned only to its exact, empty source slot.</p>
 */
public final class InventoryManagerAutomation26 {
    public static final String OWNER = "inventory_manager";
    public static final int PRIORITY = 10;
    public static final Set<CombatActionArbiter26.Channel> INVENTORY_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.INVENTORY,
                    CombatActionArbiter26.Channel.HOTBAR,
                    CombatActionArbiter26.Channel.USE
            );
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(8, 4);

    private final InventoryManagerDecisionEngine26 engine;
    private Configuration configuration;
    private SessionStamp observedSession;
    private long sessionGeneration;
    private PreparedMerge pending;
    private LocalPlayer lastSubmitPlayer;
    private int lastSubmitTick = Integer.MIN_VALUE;
    private int lastTransactionTick = Integer.MIN_VALUE;
    private boolean manualBaselineReady;
    private int previousMenuStateId = Integer.MIN_VALUE;
    private int previousSelectedSlot = -1;
    private InventorySnapshot previousInventorySnapshot;
    private boolean manualContextInterrupted;

    public InventoryManagerAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public InventoryManagerAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new InventoryManagerDecisionEngine26(
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
     * Evaluates and claims at most one complete merge transaction.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            boolean utilityHotbarOwned,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");

        LocalPlayer player = client == null ? null : client.player;
        if (player != null
                && sameIdentityTick(
                player,
                player.tickCount,
                lastSubmitPlayer,
                lastSubmitTick
        )
                && observedSession != null
                && observedSession.matches(client)) {
            return;
        }
        pending = null;

        boolean sessionReady = refreshSession(client);
        lastSubmitPlayer = player;
        lastSubmitTick = player == null
                ? Integer.MIN_VALUE
                : player.tickCount;
        boolean manualChange = observeManualChange(client, sessionReady);
        boolean inventoryReady = sessionReady
                && !utilityHotbarOwned
                && !manualChange
                && inventoryReady(client);
        List<InventoryManagerDecisionEngine26.Candidate> candidates =
                inventoryReady
                        ? collectCandidates(player)
                        : List.of();
        InventoryManagerDecisionEngine26.Decision decision = engine.step(
                new InventoryManagerDecisionEngine26.Observation(
                        currentSessionKey(),
                        enabled,
                        sessionReady,
                        inventoryReady,
                        inventoryReady,
                        manualChange,
                        candidates
                )
        );
        if (!decision.apply()) {
            return;
        }

        PreparedMerge prepared = prepare(client, decision);
        if (prepared == null) {
            engine.yieldToManualChange();
            return;
        }
        pending = prepared;
        if (!arbiter.submit(OWNER, PRIORITY, INVENTORY_CHANNELS)) {
            pending = null;
            engine.commit(decision, false);
        }
    }

    /**
     * Executes the submitted transaction after the shared arbiter resolves.
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedMerge prepared = pending;
        pending = null;
        if (prepared == null) {
            return false;
        }
        if (!arbiter.ownsAll(OWNER, INVENTORY_CHANNELS)) {
            engine.commit(prepared.decision(), false);
            return false;
        }
        if (!preparedStillValid(client, prepared)
                || client.player.tickCount == lastTransactionTick) {
            engine.yieldToManualChange();
            return false;
        }

        lastTransactionTick = client.player.tickCount;
        boolean executed = performMerge(client, prepared);
        engine.commit(prepared.decision(), executed);
        if (!executed) {
            engine.yieldToManualChange();
        }
        updateManualBaseline(client);
        return executed;
    }

    /**
     * Clears all tick- and session-local state. No cursor state survives a
     * successful execute because the transaction is completed synchronously.
     */
    public void release() {
        pending = null;
        observedSession = null;
        sessionGeneration++;
        engine.reset();
        lastSubmitPlayer = null;
        lastSubmitTick = Integer.MIN_VALUE;
        lastTransactionTick = Integer.MIN_VALUE;
        resetManualBaseline();
    }

    public void release(Minecraft client) {
        release();
    }

    public Status status() {
        return new Status(
                pending != null,
                engine.snapshot().cooldownTicks()
        );
    }

    private boolean refreshSession(Minecraft client) {
        SessionStamp current = SessionStamp.capture(client);
        if (current == null) {
            if (observedSession != null) {
                observedSession = null;
                sessionGeneration++;
                engine.reset();
                lastSubmitPlayer = null;
                lastSubmitTick = Integer.MIN_VALUE;
                lastTransactionTick = Integer.MIN_VALUE;
                resetManualBaseline();
            }
            return false;
        }
        if (observedSession == null
                || !observedSession.sameSession(current)) {
            observedSession = current;
            sessionGeneration++;
            engine.reset();
            resetManualBaseline();
            lastSubmitPlayer = null;
            lastSubmitTick = Integer.MIN_VALUE;
            lastTransactionTick = Integer.MIN_VALUE;
        }
        return sessionReady(client);
    }

    static boolean sameIdentityTick(
            Object currentOwner,
            int currentTick,
            Object previousOwner,
            int previousTick
    ) {
        return currentOwner != null
                && currentOwner == previousOwner
                && currentTick == previousTick;
    }

    private long currentSessionKey() {
        long key = sessionGeneration;
        return key == Long.MIN_VALUE ? Long.MIN_VALUE + 1L : key;
    }

    private static List<InventoryManagerDecisionEngine26.Candidate>
    collectCandidates(LocalPlayer player) {
        if (player == null) {
            return List.of();
        }
        List<ItemStack> representatives = new ArrayList<>();
        List<InventoryManagerDecisionEngine26.Candidate> candidates =
                new ArrayList<>(27);
        int inventorySize = Math.min(
                36,
                player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 9; slot < inventorySize; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int group = equivalenceGroup(representatives, stack);
            candidates.add(new InventoryManagerDecisionEngine26.Candidate(
                    slot,
                    stack.getCount(),
                    stack.getMaxStackSize(),
                    stack.isStackable(),
                    "stack-" + group
            ));
        }
        return candidates;
    }

    private static int equivalenceGroup(
            List<ItemStack> representatives,
            ItemStack stack
    ) {
        for (int index = 0; index < representatives.size(); index++) {
            if (sameComponents(representatives.get(index), stack)) {
                return index;
            }
        }
        representatives.add(stack.copy());
        return representatives.size() - 1;
    }

    private PreparedMerge prepare(
            Minecraft client,
            InventoryManagerDecisionEngine26.Decision decision
    ) {
        if (!inventoryReady(client)
                || observedSession == null
                || decision.merge() == null) {
            return null;
        }
        InventoryManagerDecisionEngine26.Merge merge = decision.merge();
        ItemStack source = inventoryItem(client.player, merge.sourceSlot());
        ItemStack target = inventoryItem(client.player, merge.targetSlot());
        if (!validMergeStacks(source, target, merge)) {
            return null;
        }

        ItemStack merged = target.copy();
        merged.grow(source.getCount());
        return new PreparedMerge(
                decision,
                observedSession,
                client.player.tickCount,
                client.player.inventoryMenu.getStateId(),
                client.player.getInventory().getSelectedSlot(),
                inventoryIndexToMenuSlot(merge.sourceSlot()),
                inventoryIndexToMenuSlot(merge.targetSlot()),
                StackFingerprint.of(source),
                StackFingerprint.of(target),
                StackFingerprint.of(merged)
        );
    }

    private static boolean preparedStillValid(
            Minecraft client,
            PreparedMerge prepared
    ) {
        if (!inventoryReady(client)
                || !prepared.session().matches(client)
                || client.player.tickCount != prepared.preparedTick()
                || client.player.inventoryMenu.getStateId()
                != prepared.preparedStateId()
                || client.player.getInventory().getSelectedSlot()
                != prepared.selectedSlot()) {
            return false;
        }
        InventoryManagerDecisionEngine26.Merge merge =
                prepared.decision().merge();
        return merge != null
                && prepared.sourceBefore().matches(
                inventoryItem(client.player, merge.sourceSlot())
        )
                && prepared.targetBefore().matches(
                inventoryItem(client.player, merge.targetSlot())
        )
                && prepared.sourceBefore().matches(
                menuItem(client, prepared.sourceMenuSlot())
        )
                && prepared.targetBefore().matches(
                menuItem(client, prepared.targetMenuSlot())
        );
    }

    private static boolean performMerge(
            Minecraft client,
            PreparedMerge prepared
    ) {
        if (!preparedStillValid(client, prepared)) {
            return false;
        }
        if (!click(client, prepared.sourceMenuSlot())
                || !sourceHeldState(
                client,
                prepared,
                currentStateId(client)
        )) {
            recoverSource(client, prepared);
            return false;
        }
        int heldStateId = currentStateId(client);
        if (!sourceHeldState(client, prepared, heldStateId)
                || !click(client, prepared.targetMenuSlot())
                || !mergedState(
                client,
                prepared,
                currentStateId(client)
        )) {
            recoverSource(client, prepared);
            return false;
        }

        return true;
    }

    private static boolean sourceHeldState(
            Minecraft client,
            PreparedMerge prepared,
            int expectedStateId
    ) {
        return transactionContextMatches(
                client,
                prepared,
                expectedStateId
        )
                && prepared.sourceBefore().matches(
                client.player.containerMenu.getCarried()
        )
                && inventoryItem(
                client.player,
                prepared.decision().merge().sourceSlot()
        ).isEmpty()
                && prepared.targetBefore().matches(inventoryItem(
                client.player,
                prepared.decision().merge().targetSlot()
        ))
                && menuItem(
                client,
                prepared.sourceMenuSlot()
        ).isEmpty()
                && prepared.targetBefore().matches(
                menuItem(client, prepared.targetMenuSlot())
        );
    }

    private static boolean mergedState(
            Minecraft client,
            PreparedMerge prepared,
            int expectedStateId
    ) {
        return transactionContextMatches(
                client,
                prepared,
                expectedStateId
        )
                && client.player.containerMenu.getCarried().isEmpty()
                && inventoryItem(
                client.player,
                prepared.decision().merge().sourceSlot()
        ).isEmpty()
                && prepared.targetAfter().matches(inventoryItem(
                client.player,
                prepared.decision().merge().targetSlot()
        ))
                && menuItem(client, prepared.sourceMenuSlot()).isEmpty()
                && prepared.targetAfter().matches(
                menuItem(client, prepared.targetMenuSlot())
        );
    }

    /**
     * Returns an owned cursor stack only when its original source is still
     * exactly empty. The full source fingerprint proves cursor ownership, so
     * target changes do not prevent returning that stack to its own slot.
     */
    private static boolean recoverSource(
            Minecraft client,
            PreparedMerge prepared
    ) {
        int recoveryStateId = currentStateId(client);
        if (!recoveryContextMatches(
                client,
                prepared,
                recoveryStateId
        )) {
            return false;
        }
        ItemStack carried = client.player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            return true;
        }
        boolean sourceEmpty = inventoryItem(
                client.player,
                prepared.decision().merge().sourceSlot()
        ).isEmpty() && menuItem(
                client,
                prepared.sourceMenuSlot()
        ).isEmpty();
        if (!recoveryEligible(
                prepared.sourceBefore().matches(carried),
                sourceEmpty
        )) {
            return false;
        }
        StackFingerprint recovery = StackFingerprint.of(carried);
        click(client, prepared.sourceMenuSlot());
        return recoveryContextMatches(
                client,
                prepared,
                recoveryStateId
        )
                && client.player.containerMenu.getCarried().isEmpty()
                && recovery.matches(inventoryItem(
                client.player,
                prepared.decision().merge().sourceSlot()
        ))
                && recovery.matches(
                menuItem(client, prepared.sourceMenuSlot())
        );
    }

    static boolean recoveryEligible(
            boolean carriedExactSource,
            boolean sourceEmpty
    ) {
        return carriedExactSource
                && sourceEmpty;
    }

    private static boolean transactionContextMatches(
            Minecraft client,
            PreparedMerge prepared,
            int expectedStateId
    ) {
        return prepared != null
                && menuContextReady(client)
                && prepared.session().matches(client)
                && client.player.tickCount == prepared.preparedTick()
                && client.player.inventoryMenu.getStateId()
                == expectedStateId
                && expectedStateId == prepared.preparedStateId()
                && client.player.getInventory().getSelectedSlot()
                == prepared.selectedSlot();
    }

    private static boolean recoveryContextMatches(
            Minecraft client,
            PreparedMerge prepared,
            int recoveryStateId
    ) {
        return prepared != null
                && menuContextReady(client)
                && prepared.session().matches(client)
                && client.player.tickCount == prepared.preparedTick()
                && client.player.getInventory().getSelectedSlot()
                == prepared.selectedSlot()
                && client.player.inventoryMenu.getStateId()
                == recoveryStateId;
    }

    private static int currentStateId(Minecraft client) {
        return client == null || client.player == null
                ? Integer.MIN_VALUE
                : client.player.inventoryMenu.getStateId();
    }

    private boolean observeManualChange(
            Minecraft client,
            boolean sessionReady
    ) {
        if (!sessionReady
                || client.gui.screen() != null
                || client.player.containerMenu
                != client.player.inventoryMenu) {
            interruptManualBaseline();
            return false;
        }
        int stateId = client.player.inventoryMenu.getStateId();
        int selected = client.player.getInventory().getSelectedSlot();
        InventorySnapshot snapshot = InventorySnapshot.capture(client);
        if (snapshot == null) {
            resetManualBaseline();
            return false;
        }
        if (!manualBaselineReady) {
            manualBaselineReady = true;
            previousMenuStateId = stateId;
            previousSelectedSlot = selected;
            previousInventorySnapshot = snapshot;
            boolean interrupted = manualContextInterrupted;
            manualContextInterrupted = false;
            return interrupted;
        }
        boolean changed = stateId != previousMenuStateId
                || selected != previousSelectedSlot
                || previousInventorySnapshot == null
                || !previousInventorySnapshot.sameContents(snapshot);
        previousMenuStateId = stateId;
        previousSelectedSlot = selected;
        previousInventorySnapshot = snapshot;
        return changed;
    }

    private void updateManualBaseline(Minecraft client) {
        if (client == null
                || client.player == null
                || client.player.containerMenu
                != client.player.inventoryMenu) {
            resetManualBaseline();
            return;
        }
        manualBaselineReady = true;
        previousMenuStateId = client.player.inventoryMenu.getStateId();
        previousSelectedSlot =
                client.player.getInventory().getSelectedSlot();
        previousInventorySnapshot = InventorySnapshot.capture(client);
        manualContextInterrupted = false;
    }

    private void resetManualBaseline() {
        manualBaselineReady = false;
        previousMenuStateId = Integer.MIN_VALUE;
        previousSelectedSlot = -1;
        previousInventorySnapshot = null;
        manualContextInterrupted = false;
    }

    private void interruptManualBaseline() {
        manualBaselineReady = false;
        previousMenuStateId = Integer.MIN_VALUE;
        previousSelectedSlot = -1;
        previousInventorySnapshot = null;
        manualContextInterrupted = true;
    }

    private static boolean click(Minecraft client, int menuSlot) {
        if (!menuContextReady(client)
                || menuSlot < 0
                || menuSlot >= client.player.inventoryMenu.slots.size()) {
            return false;
        }
        try {
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId,
                    menuSlot,
                    0,
                    ContainerInput.PICKUP,
                    client.player
            );
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean validMergeStacks(
            ItemStack source,
            ItemStack target,
            InventoryManagerDecisionEngine26.Merge merge
    ) {
        return source != null
                && target != null
                && !source.isEmpty()
                && !target.isEmpty()
                && source.isStackable()
                && target.isStackable()
                && sameComponents(source, target)
                && source.getCount() == merge.sourceCount()
                && target.getCount() == merge.targetCount()
                && source.getMaxStackSize() == merge.maximumCount()
                && target.getMaxStackSize() == merge.maximumCount()
                && (long) source.getCount() + target.getCount()
                <= source.getMaxStackSize();
    }

    static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) {
            throw new IllegalArgumentException(
                    "Not a main inventory index: " + inventoryIndex
            );
        }
        return inventoryIndex < 9
                ? 36 + inventoryIndex
                : inventoryIndex;
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

    private static ItemStack menuItem(Minecraft client, int menuSlot) {
        if (client == null
                || client.player == null
                || menuSlot < 0
                || menuSlot >= client.player.inventoryMenu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return client.player.inventoryMenu.getSlot(menuSlot).getItem();
    }

    private static boolean sameComponents(ItemStack first, ItemStack second) {
        return first != null
                && second != null
                && !first.isEmpty()
                && !second.isEmpty()
                && ItemStack.isSameItemSameComponents(first, second);
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

    private static boolean menuContextReady(Minecraft client) {
        return sessionReady(client)
                && client.gui.screen() == null
                && client.player.containerMenu
                == client.player.inventoryMenu;
    }

    private static boolean inventoryReady(Minecraft client) {
        return menuContextReady(client)
                && client.player.containerMenu.getCarried().isEmpty();
    }

    public record Configuration(
            int actionCooldownTicks,
            int manualYieldTicks
    ) {
        public Configuration {
            new InventoryManagerDecisionEngine26.Timing(
                    actionCooldownTicks,
                    manualYieldTicks
            );
        }

        private InventoryManagerDecisionEngine26.Timing timing() {
            return new InventoryManagerDecisionEngine26.Timing(
                    actionCooldownTicks,
                    manualYieldTicks
            );
        }
    }

    public record Status(boolean prepared, int cooldownTicks) {
    }

    private record PreparedMerge(
            InventoryManagerDecisionEngine26.Decision decision,
            SessionStamp session,
            int preparedTick,
            int preparedStateId,
            int selectedSlot,
            int sourceMenuSlot,
            int targetMenuSlot,
            StackFingerprint sourceBefore,
            StackFingerprint targetBefore,
            StackFingerprint targetAfter
    ) {
    }

    static final class StackFingerprint {
        private final ItemStack expected;

        private StackFingerprint(ItemStack expected) {
            this.expected = expected == null
                    ? ItemStack.EMPTY
                    : expected.copy();
        }

        static StackFingerprint of(ItemStack stack) {
            return new StackFingerprint(stack);
        }

        boolean matches(ItemStack actual) {
            boolean actualEmpty = actual == null || actual.isEmpty();
            boolean exactComponents = !expected.isEmpty()
                    && !actualEmpty
                    && ItemStack.isSameItemSameComponents(expected, actual);
            return fingerprintMatches(
                    expected.isEmpty(),
                    expected.getCount(),
                    actualEmpty,
                    actualEmpty ? 0 : actual.getCount(),
                    exactComponents
            );
        }

        private boolean sameFingerprint(StackFingerprint other) {
            return other != null && matches(other.expected);
        }
    }

    static boolean fingerprintMatches(
            boolean expectedEmpty,
            int expectedCount,
            boolean actualEmpty,
            int actualCount,
            boolean exactComponents
    ) {
        if (expectedEmpty) {
            return actualEmpty;
        }
        return !actualEmpty
                && expectedCount > 0
                && expectedCount == actualCount
                && exactComponents;
    }

    private record InventorySnapshot(
            List<StackFingerprint> inventory,
            StackFingerprint cursor
    ) {
        private static InventorySnapshot capture(Minecraft client) {
            if (client == null
                    || client.player == null
                    || client.player.containerMenu
                    != client.player.inventoryMenu) {
                return null;
            }
            int size = Math.min(
                    36,
                    client.player.getInventory()
                            .getNonEquipmentItems().size()
            );
            List<StackFingerprint> stacks = new ArrayList<>(size);
            for (int slot = 0; slot < size; slot++) {
                stacks.add(StackFingerprint.of(
                        client.player.getInventory().getItem(slot)
                ));
            }
            return new InventorySnapshot(
                    List.copyOf(stacks),
                    StackFingerprint.of(
                            client.player.containerMenu.getCarried()
                    )
            );
        }

        private boolean sameContents(InventorySnapshot other) {
            if (other == null
                    || inventory.size() != other.inventory.size()
                    || !cursor.sameFingerprint(other.cursor)) {
                return false;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.get(slot).sameFingerprint(
                        other.inventory.get(slot)
                )) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class SessionStamp {
        private final LocalPlayer player;
        private final Object level;
        private final Object connection;
        private final Object menu;
        private final int containerId;

        private SessionStamp(
                LocalPlayer player,
                Object level,
                Object connection,
                Object menu,
                int containerId
        ) {
            this.player = player;
            this.level = level;
            this.connection = connection;
            this.menu = menu;
            this.containerId = containerId;
        }

        private static SessionStamp capture(Minecraft client) {
            if (client == null
                    || client.player == null
                    || client.level == null
                    || client.getConnection() == null) {
                return null;
            }
            return new SessionStamp(
                    client.player,
                    client.level,
                    client.getConnection(),
                    client.player.inventoryMenu,
                    client.player.inventoryMenu.containerId
            );
        }

        private boolean sameSession(SessionStamp other) {
            return other != null
                    && player == other.player
                    && level == other.level
                    && connection == other.connection
                    && menu == other.menu
                    && containerId == other.containerId;
        }

        private boolean matches(Minecraft client) {
            SessionStamp current = capture(client);
            return sameSession(current)
                    && client.player.containerMenu == menu
                    && client.player.containerMenu.containerId == containerId;
        }
    }
}
