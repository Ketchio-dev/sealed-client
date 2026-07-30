package dev.sealedclient.v26.utility;

import dev.sealedclient.v26.combat.CombatActionArbiter26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One-shot, ownership-safe Chest Swap implementation for Minecraft 26.2.
 *
 * <p>{@link #submit} performs only immutable planning and submits one atomic
 * inventory/hotbar/use claim. {@link #execute} revalidates the exact player,
 * level, menu, selected slot, cursor, source stack, and chest stack before
 * issuing the vanilla source/chest/source PICKUP transaction. The complete
 * transaction is the service's single logical menu operation for the tick.</p>
 *
 * <p>Every individual PICKUP is followed by exact stack-state validation. On
 * failure, cursor recovery is attempted only when the captured transaction
 * source is still empty and the cursor contains one of the two captured
 * stacks. Unknown or manually changed state is never guessed at.</p>
 */
public final class ChestSwapAutomation26 {
    public static final String OWNER = "chest_swap";
    public static final int PRIORITY = 68;
    public static final Set<CombatActionArbiter26.Channel>
            INVENTORY_CHANNELS = Set.of(
            CombatActionArbiter26.Channel.INVENTORY,
            CombatActionArbiter26.Channel.HOTBAR,
            CombatActionArbiter26.Channel.USE
    );
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(10, 4, 8, 4);

    private static final int CHEST_MENU_SLOT = 6;
    private static final int MAXIMUM_INVENTORY_SLOTS = 36;

    private final ChestSwapDecisionEngine26 engine;
    private final ArmorScoreAccumulator scoreAccumulator =
            new ArmorScoreAccumulator();
    private Configuration configuration;
    private PreparedSwap pending;
    private SessionIdentity observedSession;
    private long sessionEpoch;
    private int lastLogicalTransactionTick = Integer.MIN_VALUE;
    private int lastPhysicalClicks;
    private TransactionResult lastTransactionResult =
            TransactionResult.NONE;

    public ChestSwapAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public ChestSwapAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new ChestSwapDecisionEngine26(configuration.timing());
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
     * Plans one rising-edge swap and submits its complete channel bundle.
     *
     * <p>When another utility service already owns a hotbar operation, this
     * method neither scans candidates nor submits a claim.</p>
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            boolean utilityHotbarOwned,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pending = null;
        lastPhysicalClicks = 0;
        lastTransactionResult = TransactionResult.NONE;

        boolean sessionReady = sessionReady(client);
        boolean inventoryReady = !utilityHotbarOwned
                && inventoryReady(client);
        CandidateSelection selection = null;
        int candidateSlot = 0;
        if (enabled && inventoryReady) {
            selection = selectReplacement(client.player);
            candidateSlot = selection == null
                    ? -1
                    : selection.inventorySlot();
        }

        ChestSwapDecisionEngine26.Decision decision = engine.step(
                new ChestSwapDecisionEngine26.Observation(
                        observeSession(client),
                        enabled,
                        sessionReady,
                        inventoryReady,
                        utilityHotbarOwned,
                        candidateSlot
                )
        );
        if (!decision.apply()
                || utilityHotbarOwned
                || selection == null
                || decision.inventorySlot()
                != selection.inventorySlot()) {
            return;
        }

        PreparedSwap prepared = prepare(client, decision, selection);
        if (prepared == null) {
            engine.commit(
                    decision,
                    ChestSwapDecisionEngine26.Outcome.INVALIDATED
            );
            return;
        }
        pending = prepared;
        arbiter.submit(OWNER, PRIORITY, INVENTORY_CHANNELS);
    }

    /**
     * Executes at most one captured source/chest/source transaction.
     *
     * @return {@code true} only when the final exact inventory state proves
     *         that the swap completed and the cursor is empty
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedSwap prepared = pending;
        pending = null;
        if (prepared == null) {
            return false;
        }
        if (!arbiter.ownsAll(OWNER, INVENTORY_CHANNELS)) {
            engine.commit(
                    prepared.decision(),
                    ChestSwapDecisionEngine26.Outcome.DENIED
            );
            lastTransactionResult = TransactionResult.DENIED;
            return false;
        }
        if (!preparedStillValid(client, prepared)) {
            engine.commit(
                    prepared.decision(),
                    ChestSwapDecisionEngine26.Outcome.INVALIDATED
            );
            lastTransactionResult = TransactionResult.INVALIDATED;
            return false;
        }
        if (client.player.tickCount == lastLogicalTransactionTick) {
            engine.commit(
                    prepared.decision(),
                    ChestSwapDecisionEngine26.Outcome.FAILED
            );
            lastTransactionResult = TransactionResult.FAILED;
            return false;
        }

        lastLogicalTransactionTick = client.player.tickCount;
        TransactionResult result = pickupSwap(client, prepared);
        lastTransactionResult = result;
        engine.commit(
                prepared.decision(),
                switch (result) {
                    case APPLIED ->
                            ChestSwapDecisionEngine26.Outcome.APPLIED;
                    case INVALIDATED ->
                            ChestSwapDecisionEngine26.Outcome.INVALIDATED;
                    case DENIED, NONE, FAILED ->
                            ChestSwapDecisionEngine26.Outcome.FAILED;
                }
        );
        return result == TransactionResult.APPLIED;
    }

    /**
     * Clears all connection-local plans and latches. The service never keeps a
     * cursor lease across calls, so lifecycle cleanup does not perform a blind
     * inventory mutation.
     */
    public void release(Minecraft client) {
        pending = null;
        observedSession = null;
        lastLogicalTransactionTick = Integer.MIN_VALUE;
        lastPhysicalClicks = 0;
        lastTransactionResult = TransactionResult.NONE;
        engine.reset();
    }

    public Status status() {
        ChestSwapDecisionEngine26.Snapshot snapshot = engine.snapshot();
        return new Status(
                snapshot.armed(),
                snapshot.cooldownTicks(),
                snapshot.waitTicks(),
                snapshot.terminal(),
                pending != null,
                lastLogicalTransactionTick,
                lastPhysicalClicks,
                lastTransactionResult
        );
    }

    private CandidateSelection selectReplacement(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!safeDisplacedChest(chest)) {
            return null;
        }
        boolean wearingElytra = isElytra(chest);
        int selected = player.getInventory().getSelectedSlot();
        int size = Math.min(
                MAXIMUM_INVENTORY_SLOTS,
                player.getInventory().getNonEquipmentItems().size()
        );
        List<ChestSwapDecisionEngine26.Candidate> candidates =
                new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            boolean elytra = isElytra(stack);
            boolean chestplate = !elytra
                    && equippable != null
                    && equippable.slot() == EquipmentSlot.CHEST;
            ChestSwapDecisionEngine26.CandidateKind kind = elytra
                    ? ChestSwapDecisionEngine26.CandidateKind.ELYTRA
                    : chestplate
                    ? ChestSwapDecisionEngine26.CandidateKind.CHESTPLATE
                    : ChestSwapDecisionEngine26.CandidateKind.OTHER;
            candidates.add(new ChestSwapDecisionEngine26.Candidate(
                    slot,
                    kind,
                    elytra
                            ? LivingEntity.canGlideUsing(
                            stack,
                            EquipmentSlot.CHEST
                    )
                            : chestplate,
                    cursed(stack),
                    remainingDurability(stack),
                    chestplate
                            ? armorScore(stack)
                            : 0.0,
                    slot < 9,
                    slot < 9 && slot == selected
            ));
        }

        ChestSwapDecisionEngine26.Candidate candidate =
                ChestSwapDecisionEngine26.selectCandidate(
                        wearingElytra,
                        candidates,
                        configuration.minimumDurability()
                ).orElse(null);
        if (candidate == null) {
            return null;
        }
        ItemStack source = inventoryItem(player, candidate.inventorySlot());
        return new CandidateSelection(
                candidate.inventorySlot(),
                StackFingerprint.of(source),
                StackFingerprint.of(chest)
        );
    }

    private PreparedSwap prepare(
            Minecraft client,
            ChestSwapDecisionEngine26.Decision decision,
            CandidateSelection selection
    ) {
        if (!inventoryReady(client)
                || client.player.getInventory().getSelectedSlot()
                == selection.inventorySlot()
                && selection.inventorySlot() < 9) {
            return null;
        }
        ItemStack source = inventoryItem(
                client.player,
                selection.inventorySlot()
        );
        ItemStack chest =
                client.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!selection.source().matches(source)
                || !selection.chest().matches(chest)) {
            return null;
        }
        return new PreparedSwap(
                decision,
                selection.inventorySlot(),
                inventoryIndexToMenuSlot(selection.inventorySlot()),
                client.player.inventoryMenu.containerId,
                client.player.inventoryMenu.getStateId(),
                client.player.tickCount,
                client.player.getInventory().getSelectedSlot(),
                client.player,
                client.level,
                client.getConnection(),
                client.player.inventoryMenu,
                selection.source(),
                selection.chest()
        );
    }

    private static boolean preparedStillValid(
            Minecraft client,
            PreparedSwap prepared
    ) {
        return sameContext(client, prepared)
                && client.player.containerMenu.getCarried().isEmpty()
                && prepared.source().matches(
                inventoryItem(client.player, prepared.inventorySlot())
        )
                && prepared.chest().matches(
                client.player.getItemBySlot(EquipmentSlot.CHEST)
        );
    }

    private TransactionResult pickupSwap(
            Minecraft client,
            PreparedSwap prepared
    ) {
        if (!preparedStillValid(client, prepared)) {
            return TransactionResult.INVALIDATED;
        }
        try {
            click(client, prepared.sourceMenuSlot());
            if (!afterSourcePickup(client, prepared)) {
                return recoverCapturedCursor(client, prepared);
            }

            click(client, CHEST_MENU_SLOT);
            if (!afterChestPickup(client, prepared)) {
                return recoverCapturedCursor(client, prepared);
            }

            if (!prepared.chest().empty()) {
                click(client, prepared.sourceMenuSlot());
            }
            return finalTransactionState(client, prepared)
                    ? TransactionResult.APPLIED
                    : recoverCapturedCursor(client, prepared);
        } catch (RuntimeException exception) {
            return recoverCapturedCursor(client, prepared);
        }
    }

    private void click(Minecraft client, int menuSlot) {
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                menuSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
        lastPhysicalClicks++;
    }

    private static boolean afterSourcePickup(
            Minecraft client,
            PreparedSwap prepared
    ) {
        return sameContext(client, prepared)
                && prepared.source().matches(
                client.player.containerMenu.getCarried()
        )
                && inventoryItem(
                client.player,
                prepared.inventorySlot()
        ).isEmpty()
                && prepared.chest().matches(
                client.player.getItemBySlot(EquipmentSlot.CHEST)
        );
    }

    private static boolean afterChestPickup(
            Minecraft client,
            PreparedSwap prepared
    ) {
        return sameContext(client, prepared)
                && prepared.chest().matches(
                client.player.containerMenu.getCarried()
        )
                && inventoryItem(
                client.player,
                prepared.inventorySlot()
        ).isEmpty()
                && prepared.source().matches(
                client.player.getItemBySlot(EquipmentSlot.CHEST)
        );
    }

    private static boolean finalTransactionState(
            Minecraft client,
            PreparedSwap prepared
    ) {
        return sameContext(client, prepared)
                && client.player.containerMenu.getCarried().isEmpty()
                && prepared.chest().matches(
                inventoryItem(client.player, prepared.inventorySlot())
        )
                && prepared.source().matches(
                client.player.getItemBySlot(EquipmentSlot.CHEST)
        );
    }

    /**
     * Recovers only the two exact transaction states whose source slot is
     * known empty. The first state rolls back; the second completes the swap.
     */
    private TransactionResult recoverCapturedCursor(
            Minecraft client,
            PreparedSwap prepared
    ) {
        boolean contextValid = sameContext(client, prepared);
        if (!contextValid) {
            return TransactionResult.INVALIDATED;
        }
        ItemStack sourceSlot = inventoryItem(
                client.player,
                prepared.inventorySlot()
        );
        ItemStack cursor = client.player.containerMenu.getCarried();
        ItemStack chest =
                client.player.getItemBySlot(EquipmentSlot.CHEST);

        RecoveryAction recovery = classifyRecovery(
                new RecoveryObservation(
                        contextValid,
                        sourceSlot.isEmpty(),
                        cursor.isEmpty(),
                        prepared.source().matches(cursor),
                        prepared.chest().matches(cursor),
                        prepared.source().matches(chest),
                        prepared.chest().matches(chest),
                        finalTransactionState(client, prepared),
                        cursor.isEmpty()
                                && prepared.source().matches(sourceSlot)
                                && prepared.chest().matches(chest)
                )
        );
        if (recovery == RecoveryAction.ALREADY_APPLIED) {
            return TransactionResult.APPLIED;
        }
        if (recovery == RecoveryAction.ALREADY_ROLLED_BACK) {
            return TransactionResult.FAILED;
        }
        if (recovery == RecoveryAction.ABANDON) {
            return TransactionResult.INVALIDATED;
        }
        try {
            click(client, prepared.sourceMenuSlot());
        } catch (RuntimeException ignored) {
            return TransactionResult.FAILED;
        }
        if (recovery == RecoveryAction.COMPLETE_SWAP
                && finalTransactionState(client, prepared)) {
            return TransactionResult.APPLIED;
        }
        return client.player.containerMenu.getCarried().isEmpty()
                && prepared.source().matches(
                inventoryItem(client.player, prepared.inventorySlot())
        )
                && prepared.chest().matches(
                client.player.getItemBySlot(EquipmentSlot.CHEST)
        )
                ? TransactionResult.FAILED
                : TransactionResult.INVALIDATED;
    }

    static RecoveryAction classifyRecovery(
            RecoveryObservation observation
    ) {
        if (observation == null || !observation.contextValid()) {
            return RecoveryAction.ABANDON;
        }
        if (observation.finalState()) {
            return RecoveryAction.ALREADY_APPLIED;
        }
        if (observation.initialState()) {
            return RecoveryAction.ALREADY_ROLLED_BACK;
        }
        if (!observation.sourceEmpty()
                || observation.cursorEmpty()) {
            return RecoveryAction.ABANDON;
        }
        if (observation.cursorMatchesSource()
                && observation.chestMatchesOriginal()) {
            return RecoveryAction.ROLL_BACK;
        }
        if (observation.cursorMatchesOriginal()
                && observation.chestMatchesSource()) {
            return RecoveryAction.COMPLETE_SWAP;
        }
        return RecoveryAction.ABANDON;
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

    static boolean exactStackMatch(
            ItemStack expected,
            ItemStack actual
    ) {
        boolean expectedEmpty = expected == null || expected.isEmpty();
        if (expectedEmpty) {
            return actual == null || actual.isEmpty();
        }
        return actual != null
                && !actual.isEmpty()
                && expected.getCount() == actual.getCount()
                && ItemStack.isSameItemSameComponents(expected, actual);
    }

    private double armorScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        scoreAccumulator.reset();
        stack.forEachModifier(EquipmentSlot.CHEST, scoreAccumulator);
        if (scoreAccumulator.armor <= 0.0
                && scoreAccumulator.toughness <= 0.0) {
            return 0.0;
        }
        double durability = stack.isDamageableItem()
                && stack.getMaxDamage() > 0
                ? remainingDurability(stack)
                / (double) stack.getMaxDamage()
                : 1.0;
        return scoreAccumulator.armor * 1_000.0
                + scoreAccumulator.toughness * 100.0
                + durability;
    }

    private static boolean sameContext(
            Minecraft client,
            PreparedSwap prepared
    ) {
        return transactionContextReady(client)
                && PreparedContextIdentity.same(
                PreparedContextIdentity.capture(client),
                new PreparedContextIdentity(
                        prepared.player(),
                        prepared.level(),
                        prepared.connection(),
                        prepared.inventoryMenu(),
                        prepared.containerId(),
                        prepared.menuStateId(),
                        prepared.tick(),
                        prepared.selectedSlot()
                )
        );
    }

    private static boolean transactionContextReady(Minecraft client) {
        return sessionReady(client)
                && client.gui.screen() == null
                && client.player.containerMenu
                == client.player.inventoryMenu;
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
        return transactionContextReady(client)
                && client.player.containerMenu.getCarried().isEmpty();
    }

    private static boolean safeDisplacedChest(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null
                && equippable.slot() == EquipmentSlot.CHEST
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
                MAXIMUM_INVENTORY_SLOTS,
                player.getInventory().getNonEquipmentItems().size()
        )) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(inventorySlot);
    }

    private long observeSession(Minecraft client) {
        SessionIdentity current = SessionIdentity.capture(client);
        SessionTransition transition = transitionSession(
                observedSession,
                current,
                sessionEpoch,
                lastLogicalTransactionTick,
                lastPhysicalClicks,
                lastTransactionResult
        );
        observedSession = transition.identity();
        sessionEpoch = transition.epoch();
        lastLogicalTransactionTick =
                transition.lastLogicalTransactionTick();
        lastPhysicalClicks = transition.lastPhysicalClicks();
        lastTransactionResult = transition.lastTransactionResult();
        if (transition.changed()) {
            pending = null;
        }
        return sessionEpoch;
    }

    static SessionTransition transitionSession(
            SessionIdentity previous,
            SessionIdentity current,
            long epoch,
            int lastLogicalTransactionTick,
            int lastPhysicalClicks,
            TransactionResult lastTransactionResult
    ) {
        Objects.requireNonNull(
                lastTransactionResult,
                "lastTransactionResult"
        );
        if (SessionIdentity.same(previous, current)) {
            return new SessionTransition(
                    current,
                    epoch,
                    false,
                    lastLogicalTransactionTick,
                    lastPhysicalClicks,
                    lastTransactionResult
            );
        }
        return new SessionTransition(
                current,
                nextSessionEpoch(epoch),
                true,
                Integer.MIN_VALUE,
                0,
                TransactionResult.NONE
        );
    }

    static long nextSessionEpoch(long current) {
        long next = current + 1L;
        return next == Long.MIN_VALUE ? Long.MIN_VALUE + 1L : next;
    }

    public record Configuration(
            int minimumDurability,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int maximumWaitTicks
    ) {
        public Configuration(int minimumDurability) {
            this(minimumDurability, 4, 8, 4);
        }

        public Configuration {
            if (minimumDurability < 0
                    || minimumDurability > 100) {
                throw new IllegalArgumentException(
                        "minimumDurability must be 0..100"
                );
            }
            new ChestSwapDecisionEngine26.Timing(
                    actionCooldownTicks,
                    failureCooldownTicks,
                    maximumWaitTicks
            );
        }

        ChestSwapDecisionEngine26.Timing timing() {
            return new ChestSwapDecisionEngine26.Timing(
                    actionCooldownTicks,
                    failureCooldownTicks,
                    maximumWaitTicks
            );
        }
    }

    public record Status(
            boolean armed,
            int cooldownTicks,
            int waitTicks,
            ChestSwapDecisionEngine26.Terminal terminal,
            boolean pending,
            int lastLogicalTransactionTick,
            int lastPhysicalClicks,
            TransactionResult lastTransactionResult
    ) {
    }

    public enum TransactionResult {
        NONE,
        APPLIED,
        DENIED,
        INVALIDATED,
        FAILED
    }

    enum RecoveryAction {
        ALREADY_APPLIED,
        ALREADY_ROLLED_BACK,
        ROLL_BACK,
        COMPLETE_SWAP,
        ABANDON
    }

    record RecoveryObservation(
            boolean contextValid,
            boolean sourceEmpty,
            boolean cursorEmpty,
            boolean cursorMatchesSource,
            boolean cursorMatchesOriginal,
            boolean chestMatchesSource,
            boolean chestMatchesOriginal,
            boolean finalState,
            boolean initialState
    ) {
    }

    private record CandidateSelection(
            int inventorySlot,
            StackFingerprint source,
            StackFingerprint chest
    ) {
    }

    private record PreparedSwap(
            ChestSwapDecisionEngine26.Decision decision,
            int inventorySlot,
            int sourceMenuSlot,
            int containerId,
            int menuStateId,
            int tick,
            int selectedSlot,
            LocalPlayer player,
            Object level,
            Object connection,
            Object inventoryMenu,
            StackFingerprint source,
            StackFingerprint chest
    ) {
    }

    record SessionIdentity(
            Object player,
            Object level,
            Object connection,
            Object inventoryMenu
    ) {
        private static SessionIdentity capture(Minecraft client) {
            LocalPlayer player = client == null ? null : client.player;
            return new SessionIdentity(
                    player,
                    client == null ? null : client.level,
                    client == null ? null : client.getConnection(),
                    player == null ? null : player.inventoryMenu
            );
        }

        static boolean same(
                SessionIdentity first,
                SessionIdentity second
        ) {
            return first != null
                    && second != null
                    && first.player() == second.player()
                    && first.level() == second.level()
                    && first.connection() == second.connection()
                    && first.inventoryMenu() == second.inventoryMenu();
        }
    }

    record SessionTransition(
            SessionIdentity identity,
            long epoch,
            boolean changed,
            int lastLogicalTransactionTick,
            int lastPhysicalClicks,
            TransactionResult lastTransactionResult
    ) {
    }

    record PreparedContextIdentity(
            Object player,
            Object level,
            Object connection,
            Object inventoryMenu,
            int containerId,
            int menuStateId,
            int tick,
            int selectedSlot
    ) {
        private static PreparedContextIdentity capture(Minecraft client) {
            LocalPlayer player = client == null ? null : client.player;
            return new PreparedContextIdentity(
                    player,
                    client == null ? null : client.level,
                    client == null ? null : client.getConnection(),
                    player == null ? null : player.inventoryMenu,
                    player == null
                            ? Integer.MIN_VALUE
                            : player.inventoryMenu.containerId,
                    player == null
                            ? Integer.MIN_VALUE
                            : player.inventoryMenu.getStateId(),
                    player == null ? Integer.MIN_VALUE : player.tickCount,
                    player == null
                            ? -1
                            : player.getInventory().getSelectedSlot()
            );
        }

        static boolean same(
                PreparedContextIdentity current,
                PreparedContextIdentity prepared
        ) {
            return current != null
                    && prepared != null
                    && current.player() == prepared.player()
                    && current.level() == prepared.level()
                    && current.connection() == prepared.connection()
                    && current.inventoryMenu() == prepared.inventoryMenu()
                    && current.containerId() == prepared.containerId()
                    && current.menuStateId() == prepared.menuStateId()
                    && current.tick() == prepared.tick()
                    && current.selectedSlot() == prepared.selectedSlot();
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

        private boolean empty() {
            return expected.isEmpty();
        }

        private boolean matches(ItemStack actual) {
            return exactStackMatch(expected, actual);
        }
    }

    private static final class ArmorScoreAccumulator
            implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        private double armor;
        private double toughness;

        @Override
        public void accept(
                Holder<Attribute> attribute,
                AttributeModifier modifier
        ) {
            if (attribute.equals(Attributes.ARMOR)) {
                armor += modifier.amount();
            } else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) {
                toughness += modifier.amount();
            }
        }

        private void reset() {
            armor = 0.0;
            toughness = 0.0;
        }
    }
}
