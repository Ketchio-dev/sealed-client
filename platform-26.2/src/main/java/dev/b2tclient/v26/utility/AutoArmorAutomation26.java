package dev.b2tclient.v26.utility;

import dev.b2tclient.v26.combat.CombatActionArbiter26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Conservative, two-phase Auto Armor service for Minecraft 26.2.
 *
 * <p>{@link #submit} only selects an immutable upgrade and claims the complete
 * inventory/hotbar/use channel bundle. {@link #execute} performs one logical
 * menu transaction only after arbitration, and revalidates the exact player,
 * level, connection, menu, state id, tick, selected hotbar slot, empty cursor,
 * source stack, and equipped stack observed during submission.</p>
 *
 * <p>A logical occupied-slot swap uses the vanilla PICKUP source/armor/source
 * sequence. No inventory object is mutated directly. If a click fails, the
 * carried stack is returned only to the exact source while it is empty; the
 * service never guesses another destination. Only one prepared transaction
 * can execute in a client tick.</p>
 */
public final class AutoArmorAutomation26 {
    public static final String OWNER = "auto_armor";
    public static final int PRIORITY = 25;
    public static final Set<CombatActionArbiter26.Channel>
            INVENTORY_CHANNELS = Set.of(
                    CombatActionArbiter26.Channel.INVENTORY,
                    CombatActionArbiter26.Channel.HOTBAR,
                    CombatActionArbiter26.Channel.USE
            );
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(true, 4, 3, 0.001, 2);

    private static final int MAXIMUM_INVENTORY_SLOTS = 36;

    private final ArmorMetrics armorMetrics = new ArmorMetrics();
    private final AutoArmorDecisionEngine26 engine;
    private Configuration configuration;

    private LocalPlayer observedPlayer;
    private Object observedLevel;
    private ClientPacketListener observedConnection;
    private final ManualChangeTracker manualChanges =
            new ManualChangeTracker();

    private AutoArmorDecisionEngine26.Decision pending;
    private SessionIdentity preparedSession;
    private AbstractContainerMenu preparedMenu;
    private int preparedContainerId = -1;
    private int preparedStateId = -1;
    private int preparedTick = Integer.MIN_VALUE;
    private int preparedSelectedSlot = -1;
    private int preparedSourceSlot = -1;
    private int preparedArmorMenuSlot = -1;
    private StackFingerprint preparedSource;
    private StackFingerprint preparedEquipped;
    private long lastExecutedTick = Long.MIN_VALUE;

    public AutoArmorAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public AutoArmorAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new AutoArmorDecisionEngine26(configuration.timing());
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
     * Selects and submits at most one upgrade without changing live state.
     *
     * @param utilityHotbarOwned true while legacy utility automation owns the
     *                           selected hotbar slot
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            boolean utilityHotbarOwned,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        clearPrepared();

        LocalPlayer player = client == null ? null : client.player;
        Object level = client == null ? null : client.level;
        ClientPacketListener connection =
                client == null ? null : client.getConnection();
        if (player != observedPlayer
                || level != observedLevel
                || connection != observedConnection) {
            observedPlayer = player;
            observedLevel = level;
            observedConnection = connection;
            manualChanges.reset();
            lastExecutedTick = Long.MIN_VALUE;
            engine.reset();
        }

        boolean ready = inventoryReady(client);
        int selectedSlot = player == null
                ? -1
                : player.getInventory().getSelectedSlot();
        int menuStateId = ready
                ? player.inventoryMenu.getStateId()
                : -1;
        boolean manualChange = manualChanges.observe(
                ready,
                selectedSlot,
                menuStateId
        );
        long tick = player == null
                ? 0L
                : Integer.toUnsignedLong(player.tickCount);
        long sessionKey = sessionKey(client);

        ArmorObservation armor = observeArmor(player);
        AutoArmorDecisionEngine26.Observation observation =
                new AutoArmorDecisionEngine26.Observation(
                        sessionKey,
                        tick,
                        enabled,
                        sessionReady(client),
                        ready,
                        utilityHotbarOwned,
                        manualChange,
                        configuration.preserveElytra(),
                        configuration.minimumRemainingDurability(),
                        configuration.minimumImprovement(),
                        armor.equipped(),
                        armor.candidates()
                );
        AutoArmorDecisionEngine26.Decision decision =
                engine.step(observation);
        if (!decision.apply()) {
            return;
        }

        AutoArmorDecisionEngine26.Upgrade upgrade =
                decision.upgrade();
        int sourceSlot = upgrade.candidate().inventorySlot();
        ItemStack source = inventoryItem(player, sourceSlot);
        EquipmentSlot equipmentSlot =
                equipmentSlot(upgrade.armorSlot());
        ItemStack equipped = player.getItemBySlot(equipmentSlot);
        if (!actionStacksValid(upgrade, source, equipped)) {
            engine.commit(
                    decision,
                    AutoArmorDecisionEngine26.Outcome.STALE
            );
            return;
        }

        pending = decision;
        preparedSession = SessionIdentity.capture(client);
        preparedMenu = player.inventoryMenu;
        preparedContainerId = player.inventoryMenu.containerId;
        preparedStateId = player.inventoryMenu.getStateId();
        preparedTick = player.tickCount;
        preparedSelectedSlot = selectedSlot;
        preparedSourceSlot = sourceSlot;
        preparedArmorMenuSlot = armorMenuSlot(upgrade.armorSlot());
        preparedSource = StackFingerprint.of(source);
        preparedEquipped = StackFingerprint.of(equipped);
        arbiter.submit(OWNER, PRIORITY, INVENTORY_CHANNELS);
    }

    /**
     * Executes the previously submitted transaction after shared resolution.
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        AutoArmorDecisionEngine26.Decision decision = pending;
        if (decision == null) {
            return false;
        }
        pending = null;

        if (!arbiter.ownsAll(OWNER, INVENTORY_CHANNELS)) {
            engine.commit(
                    decision,
                    AutoArmorDecisionEngine26.Outcome.DENIED
            );
            clearPreparedMetadata();
            return false;
        }
        if (!preparedStillValid(client, decision)
                || decision.tick() == lastExecutedTick) {
            engine.commit(
                    decision,
                    AutoArmorDecisionEngine26.Outcome.STALE
            );
            clearPreparedMetadata();
            return false;
        }

        boolean applied = pickupSwap(
                client,
                preparedSourceSlot,
                preparedArmorMenuSlot,
                preparedSource,
                preparedEquipped
        );
        engine.commit(
                decision,
                applied
                        ? AutoArmorDecisionEngine26.Outcome.EXECUTED
                        : AutoArmorDecisionEngine26.Outcome.STALE
        );
        if (applied) {
            lastExecutedTick = decision.tick();
            manualChanges.synchronize(
                    true,
                    client.player.getInventory().getSelectedSlot(),
                    client.player.inventoryMenu.getStateId()
            );
        }
        clearPreparedMetadata();
        return applied;
    }

    /**
     * Drops all connection-local state without attempting a blind restore.
     */
    public void release(Minecraft client) {
        clearPrepared();
        observedPlayer = null;
        observedLevel = null;
        observedConnection = null;
        manualChanges.reset();
        lastExecutedTick = Long.MIN_VALUE;
        engine.reset();
    }

    public Status status() {
        AutoArmorDecisionEngine26.Snapshot snapshot =
                engine.snapshot();
        return new Status(
                snapshot.cooldownTicks(),
                snapshot.manualYieldTicks(),
                snapshot.outstandingAction(),
                pending != null
        );
    }

    private ArmorObservation observeArmor(LocalPlayer player) {
        if (player == null) {
            return new ArmorObservation(List.of(), List.of());
        }
        List<AutoArmorDecisionEngine26.EquippedArmor> equipped =
                new ArrayList<>(4);
        for (AutoArmorDecisionEngine26.ArmorSlot armorSlot
                : AutoArmorDecisionEngine26.ArmorSlot.values()) {
            EquipmentSlot slot = equipmentSlot(armorSlot);
            ItemStack stack = player.getItemBySlot(slot);
            equipped.add(
                    new AutoArmorDecisionEngine26.EquippedArmor(
                            armorSlot,
                            armorScore(stack, slot),
                            stack.isEmpty(),
                            stack.is(Items.ELYTRA),
                            bindingCursed(stack)
                    )
            );
        }

        List<AutoArmorDecisionEngine26.Candidate> candidates =
                new ArrayList<>(MAXIMUM_INVENTORY_SLOTS);
        int selected = player.getInventory().getSelectedSlot();
        int size = Math.min(
                MAXIMUM_INVENTORY_SLOTS,
                player.getInventory().getNonEquipmentItems().size()
        );
        for (int inventorySlot = 0;
                inventorySlot < size;
                inventorySlot++) {
            ItemStack stack =
                    player.getInventory().getItem(inventorySlot);
            var equippable = stack.get(DataComponents.EQUIPPABLE);
            AutoArmorDecisionEngine26.ArmorSlot armorSlot =
                    equippable == null
                            ? null
                            : armorSlot(equippable.slot());
            if (armorSlot == null || stack.is(Items.ELYTRA)) {
                continue;
            }
            EquipmentSlot equipmentSlot =
                    equipmentSlot(armorSlot);
            double score = armorScore(stack, equipmentSlot);
            candidates.add(
                    new AutoArmorDecisionEngine26.Candidate(
                            inventorySlot,
                            armorSlot,
                            score,
                            remainingDurability(stack),
                            bindingCursed(stack),
                            inventorySlot < 9
                                    && inventorySlot == selected
                    )
            );
        }
        return new ArmorObservation(
                List.copyOf(equipped),
                List.copyOf(candidates)
        );
    }

    private boolean preparedStillValid(
            Minecraft client,
            AutoArmorDecisionEngine26.Decision decision
    ) {
        if (!inventoryReady(client)
                || preparedSession == null
                || !preparedSession.matches(client)
                || client.player != observedPlayer
                || client.level != observedLevel
                || client.getConnection() != observedConnection
                || client.player.inventoryMenu != preparedMenu
                || client.player.containerMenu != preparedMenu
                || preparedMenu.containerId != preparedContainerId
                || preparedMenu.getStateId() != preparedStateId
                || client.player.tickCount != preparedTick
                || decision.tick()
                != Integer.toUnsignedLong(preparedTick)
                || client.player.getInventory().getSelectedSlot()
                != preparedSelectedSlot
                || preparedSource == null
                || preparedEquipped == null
                || preparedSourceSlot < 0
                || preparedArmorMenuSlot < 0) {
            return false;
        }
        if (preparedSourceSlot < 9
                && preparedSourceSlot == preparedSelectedSlot) {
            return false;
        }

        ItemStack source = inventoryItem(
                client.player,
                preparedSourceSlot
        );
        ItemStack equipped = client.player.inventoryMenu
                .getSlot(preparedArmorMenuSlot)
                .getItem();
        return preparedSource.matches(source)
                && preparedEquipped.matches(equipped)
                && actionStacksValid(
                        decision.upgrade(),
                        source,
                        equipped
                );
    }

    private boolean actionStacksValid(
            AutoArmorDecisionEngine26.Upgrade upgrade,
            ItemStack source,
            ItemStack equipped
    ) {
        if (upgrade == null
                || source == null
                || source.isEmpty()
                || equipped == null) {
            return false;
        }
        var equippable = source.get(DataComponents.EQUIPPABLE);
        AutoArmorDecisionEngine26.ArmorSlot sourceArmorSlot =
                equippable == null
                        ? null
                        : armorSlot(equippable.slot());
        if (sourceArmorSlot != upgrade.armorSlot()
                || bindingCursed(source)
                || remainingDurability(source)
                <= configuration.minimumRemainingDurability()
                || bindingCursed(equipped)
                || (configuration.preserveElytra()
                && upgrade.armorSlot()
                == AutoArmorDecisionEngine26.ArmorSlot.CHEST
                && equipped.is(Items.ELYTRA))) {
            return false;
        }
        double sourceScore = armorScore(
                source,
                equipmentSlot(upgrade.armorSlot())
        );
        double equippedScore = armorScore(
                equipped,
                equipmentSlot(upgrade.armorSlot())
        );
        return Double.isFinite(sourceScore)
                && sourceScore - equippedScore
                > configuration.minimumImprovement();
    }

    /**
     * Performs one logical swap and leaves the cursor empty or visibly yields.
     */
    private boolean pickupSwap(
            Minecraft client,
            int inventorySlot,
            int armorMenuSlot,
            StackFingerprint sourceFingerprint,
            StackFingerprint equippedFingerprint
    ) {
        if (!inventoryReady(client)
                || sourceFingerprint == null
                || equippedFingerprint == null
                || armorMenuSlot < 5
                || armorMenuSlot > 8) {
            return false;
        }
        int sourceMenuSlot = inventoryIndexToMenuSlot(inventorySlot);
        int containerId = client.player.inventoryMenu.containerId;
        try {
            clickPickup(client, containerId, sourceMenuSlot);
            if (!transactionContextStillValid(client)
                    || transactionState(
                    sourceFingerprint,
                    equippedFingerprint,
                    menuStack(client, sourceMenuSlot),
                    menuStack(client, armorMenuSlot),
                    client.player.containerMenu.getCarried()
            ) != TransactionState.SOURCE_ON_CURSOR) {
                recoverTransaction(
                        client,
                        sourceMenuSlot,
                        armorMenuSlot,
                        sourceFingerprint,
                        equippedFingerprint
                );
                return false;
            }

            clickPickup(client, containerId, armorMenuSlot);
            TransactionState secondStage = transactionState(
                    sourceFingerprint,
                    equippedFingerprint,
                    menuStack(client, sourceMenuSlot),
                    menuStack(client, armorMenuSlot),
                    client.player.containerMenu.getCarried()
            );
            if (!transactionContextStillValid(client)
                    || (secondStage
                    != TransactionState.DISPLACED_ON_CURSOR
                    && secondStage != TransactionState.COMPLETED)) {
                recoverTransaction(
                        client,
                        sourceMenuSlot,
                        armorMenuSlot,
                        sourceFingerprint,
                        equippedFingerprint
                );
                return transactionCompleted(
                        client,
                        sourceMenuSlot,
                        armorMenuSlot,
                        sourceFingerprint,
                        equippedFingerprint
                );
            }

            if (equippedFingerprint.empty()) {
                return transactionCompleted(
                        client,
                        sourceMenuSlot,
                        armorMenuSlot,
                        sourceFingerprint,
                        equippedFingerprint
                );
            }

            clickPickup(client, containerId, sourceMenuSlot);
        } catch (RuntimeException exception) {
            recoverTransaction(
                    client,
                    sourceMenuSlot,
                    armorMenuSlot,
                    sourceFingerprint,
                    equippedFingerprint
            );
        }

        if (!transactionCompleted(
                client,
                sourceMenuSlot,
                armorMenuSlot,
                sourceFingerprint,
                equippedFingerprint
        )) {
            recoverTransaction(
                    client,
                    sourceMenuSlot,
                    armorMenuSlot,
                    sourceFingerprint,
                    equippedFingerprint
            );
        }
        return transactionCompleted(
                client,
                sourceMenuSlot,
                armorMenuSlot,
                sourceFingerprint,
                equippedFingerprint
        );
    }

    private static void clickPickup(
            Minecraft client,
            int containerId,
            int menuSlot
    ) {
        client.gameMode.handleContainerInput(
                containerId,
                menuSlot,
                0,
                ContainerInput.PICKUP,
                client.player
        );
    }

    /**
     * Recovers only one of the two exact, recognizable partial states.
     *
     * <p>After the first click the source item can be rolled back. After the
     * second click the displaced armor can be placed into the now-empty exact
     * source to complete the intended swap. Any other cursor or slot state is
     * left visible to the player.</p>
     */
    private boolean recoverTransaction(
            Minecraft client,
            int sourceMenuSlot,
            int armorMenuSlot,
            StackFingerprint sourceFingerprint,
            StackFingerprint equippedFingerprint
    ) {
        if (!transactionContextStillValid(client)
                || !menuSlotEmpty(client, sourceMenuSlot)) {
            return false;
        }
        TransactionState state = transactionState(
                sourceFingerprint,
                equippedFingerprint,
                menuStack(client, sourceMenuSlot),
                menuStack(client, armorMenuSlot),
                client.player.containerMenu.getCarried()
        );
        if (state != TransactionState.SOURCE_ON_CURSOR
                && state != TransactionState.DISPLACED_ON_CURSOR) {
            return false;
        }

        try {
            clickPickup(
                    client,
                    client.player.inventoryMenu.containerId,
                    sourceMenuSlot
            );
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!transactionContextStillValid(client)
                || !client.player.containerMenu.getCarried().isEmpty()) {
            return false;
        }
        if (state == TransactionState.SOURCE_ON_CURSOR) {
            return sourceFingerprint.matches(
                    menuStack(client, sourceMenuSlot)
            ) && equippedFingerprint.matches(
                    menuStack(client, armorMenuSlot)
            );
        }
        return transactionCompleted(
                client,
                sourceMenuSlot,
                armorMenuSlot,
                sourceFingerprint,
                equippedFingerprint
        );
    }

    private boolean transactionContextStillValid(Minecraft client) {
        return client != null
                && client.player != null
                && client.gameMode != null
                && preparedSession != null
                && preparedSession.matches(client)
                && client.player == observedPlayer
                && client.level == observedLevel
                && client.getConnection() == observedConnection
                && client.player.inventoryMenu == preparedMenu
                && client.player.containerMenu == preparedMenu
                && preparedMenu.containerId == preparedContainerId
                && preparedContextMatches(
                preparedStateId,
                preparedMenu.getStateId(),
                preparedTick,
                client.player.tickCount,
                preparedSelectedSlot,
                client.player.getInventory().getSelectedSlot(),
                client.gui.screen() == null
        );
    }

    static boolean preparedContextMatches(
            int preparedStateId,
            int currentStateId,
            int preparedTick,
            int currentTick,
            int preparedSelectedSlot,
            int currentSelectedSlot,
            boolean screenClear
    ) {
        return preparedStateId >= 0
                && currentStateId == preparedStateId
                && currentTick == preparedTick
                && currentSelectedSlot == preparedSelectedSlot
                && screenClear;
    }

    private boolean transactionCompleted(
            Minecraft client,
            int sourceMenuSlot,
            int armorMenuSlot,
            StackFingerprint sourceFingerprint,
            StackFingerprint equippedFingerprint
    ) {
        return transactionContextStillValid(client)
                && transactionState(
                sourceFingerprint,
                equippedFingerprint,
                menuStack(client, sourceMenuSlot),
                menuStack(client, armorMenuSlot),
                client.player.containerMenu.getCarried()
        ) == TransactionState.COMPLETED;
    }

    private static boolean menuSlotEmpty(
            Minecraft client,
            int menuSlot
    ) {
        return menuStack(client, menuSlot).isEmpty();
    }

    private static ItemStack menuStack(
            Minecraft client,
            int menuSlot
    ) {
        if (client == null
                || client.player == null
                || client.player.inventoryMenu == null
                || menuSlot < 0
                || menuSlot
                >= client.player.inventoryMenu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return client.player.inventoryMenu.getSlot(menuSlot).getItem();
    }

    static TransactionState transactionState(
            StackFingerprint sourceFingerprint,
            StackFingerprint equippedFingerprint,
            ItemStack sourceSlot,
            ItemStack armorSlot,
            ItemStack carried
    ) {
        if (sourceFingerprint == null
                || equippedFingerprint == null) {
            return TransactionState.UNKNOWN;
        }
        return transactionState(new TransactionObservation(
                sourceSlot == null || sourceSlot.isEmpty(),
                carried == null || carried.isEmpty(),
                sourceFingerprint.matches(sourceSlot),
                sourceFingerprint.matches(armorSlot),
                sourceFingerprint.matches(carried),
                equippedFingerprint.matches(sourceSlot),
                equippedFingerprint.matches(armorSlot),
                equippedFingerprint.matches(carried),
                equippedFingerprint.empty()
        ));
    }

    static TransactionState transactionState(
            TransactionObservation observation
    ) {
        if (observation == null) {
            return TransactionState.UNKNOWN;
        }
        if (observation.cursorEmpty()
                && observation.sourceInSourceSlot()
                && observation.equippedInArmorSlot()) {
            return TransactionState.ORIGINAL;
        }
        if (observation.sourceSlotEmpty()
                && observation.sourceOnCursor()
                && observation.equippedInArmorSlot()) {
            return TransactionState.SOURCE_ON_CURSOR;
        }
        if (observation.sourceSlotEmpty()
                && observation.sourceInArmorSlot()
                && observation.equippedOnCursor()) {
            return observation.equippedOriginallyEmpty()
                    ? TransactionState.COMPLETED
                    : TransactionState.DISPLACED_ON_CURSOR;
        }
        if (observation.cursorEmpty()
                && observation.sourceInArmorSlot()
                && observation.equippedInSourceSlot()) {
            return TransactionState.COMPLETED;
        }
        return TransactionState.UNKNOWN;
    }

    private double armorScore(
            ItemStack stack,
            EquipmentSlot slot
    ) {
        if (stack == null || stack.isEmpty()) {
            return -1.0;
        }
        armorMetrics.reset();
        stack.forEachModifier(slot, armorMetrics);
        if (armorMetrics.armor <= 0.0
                && armorMetrics.toughness <= 0.0) {
            return -1.0;
        }

        double durability = stack.isDamageableItem()
                ? remainingDurability(stack)
                / (double) Math.max(1, stack.getMaxDamage())
                : 1.0;
        return armorMetrics.armor * 10_000.0
                + armorMetrics.toughness * 1_000.0
                + armorMetrics.knockbackResistance * 1_000.0
                + protectionScore(stack) * 100.0
                + utilityEnchantmentScore(stack) * 5.0
                + durability;
    }

    private static int protectionScore(ItemStack stack) {
        int score = 0;
        for (var enchantment : stack.getEnchantments().entrySet()) {
            int level = enchantment.getIntValue();
            if (enchantment.getKey().is(Enchantments.PROTECTION)) {
                score += level * 4;
            } else if (enchantment.getKey().is(
                    Enchantments.BLAST_PROTECTION
            ) || enchantment.getKey().is(
                    Enchantments.PROJECTILE_PROTECTION
            ) || enchantment.getKey().is(
                    Enchantments.FIRE_PROTECTION
            ) || enchantment.getKey().is(
                    Enchantments.FEATHER_FALLING
            )) {
                score += level * 2;
            }
        }
        return score;
    }

    private static int utilityEnchantmentScore(ItemStack stack) {
        int score = 0;
        for (var enchantment : stack.getEnchantments().entrySet()) {
            int level = enchantment.getIntValue();
            if (enchantment.getKey().is(Enchantments.UNBREAKING)) {
                score += level;
            } else if (enchantment.getKey().is(Enchantments.MENDING)) {
                score += 1;
            } else if (enchantment.getKey().is(Enchantments.RESPIRATION)
                    || enchantment.getKey().is(
                    Enchantments.AQUA_AFFINITY
            ) || enchantment.getKey().is(Enchantments.DEPTH_STRIDER)
                    || enchantment.getKey().is(
                    Enchantments.SWIFT_SNEAK
            ) || enchantment.getKey().is(Enchantments.SOUL_SPEED)) {
                score += level;
            }
        }
        return score;
    }

    private static boolean bindingCursed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (var enchantment : stack.getEnchantments().entrySet()) {
            if (enchantment.getKey().is(Enchantments.BINDING_CURSE)) {
                return true;
            }
        }
        return false;
    }

    static boolean cursed(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && EnchantmentHelper.hasTag(
                        stack,
                        EnchantmentTags.CURSE
                );
    }

    static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0
                || inventoryIndex >= MAXIMUM_INVENTORY_SLOTS) {
            throw new IllegalArgumentException(
                    "Not a main inventory index: " + inventoryIndex
            );
        }
        return inventoryIndex < 9
                ? 36 + inventoryIndex
                : inventoryIndex;
    }

    static int armorMenuSlot(
            AutoArmorDecisionEngine26.ArmorSlot armorSlot
    ) {
        return switch (Objects.requireNonNull(
                armorSlot,
                "armorSlot"
        )) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
        };
    }

    private static EquipmentSlot equipmentSlot(
            AutoArmorDecisionEngine26.ArmorSlot armorSlot
    ) {
        return switch (Objects.requireNonNull(
                armorSlot,
                "armorSlot"
        )) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    private static AutoArmorDecisionEngine26.ArmorSlot armorSlot(
            EquipmentSlot equipmentSlot
    ) {
        return switch (equipmentSlot) {
            case HEAD -> AutoArmorDecisionEngine26.ArmorSlot.HEAD;
            case CHEST -> AutoArmorDecisionEngine26.ArmorSlot.CHEST;
            case LEGS -> AutoArmorDecisionEngine26.ArmorSlot.LEGS;
            case FEET -> AutoArmorDecisionEngine26.ArmorSlot.FEET;
            default -> null;
        };
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
                player.getInventory()
                        .getNonEquipmentItems()
                        .size()
        )) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(inventorySlot);
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

    private static long sessionKey(Minecraft client) {
        SessionIdentity identity = SessionIdentity.capture(client);
        if (identity == null) {
            return 0L;
        }
        long player = Integer.toUnsignedLong(
                System.identityHashCode(identity.player())
        );
        long level = Integer.toUnsignedLong(
                System.identityHashCode(identity.level())
        );
        long connection = Integer.toUnsignedLong(
                System.identityHashCode(identity.connection())
        );
        long key = Long.rotateLeft(player, 11)
                ^ Long.rotateLeft(level, 32)
                ^ connection;
        return key == Long.MIN_VALUE ? 0L : key;
    }

    private void clearPrepared() {
        pending = null;
        clearPreparedMetadata();
    }

    private void clearPreparedMetadata() {
        preparedSession = null;
        preparedMenu = null;
        preparedContainerId = -1;
        preparedStateId = -1;
        preparedTick = Integer.MIN_VALUE;
        preparedSelectedSlot = -1;
        preparedSourceSlot = -1;
        preparedArmorMenuSlot = -1;
        preparedSource = null;
        preparedEquipped = null;
    }

    public record Configuration(
            boolean preserveElytra,
            int actionCooldownTicks,
            int minimumRemainingDurability,
            double minimumImprovement,
            int manualYieldTicks
    ) {
        public Configuration(
                boolean preserveElytra,
                int actionCooldownTicks
        ) {
            this(
                    preserveElytra,
                    actionCooldownTicks,
                    3,
                    0.001,
                    2
            );
        }

        public Configuration {
            if (minimumRemainingDurability < 0
                    || minimumRemainingDurability > 100) {
                throw new IllegalArgumentException(
                        "minimumRemainingDurability must be 0..100"
                );
            }
            if (!Double.isFinite(minimumImprovement)
                    || minimumImprovement < 0.0
                    || minimumImprovement > 10_000.0) {
                throw new IllegalArgumentException(
                        "minimumImprovement must be 0..10000"
                );
            }
            new AutoArmorDecisionEngine26.Timing(
                    actionCooldownTicks,
                    manualYieldTicks
            );
        }

        AutoArmorDecisionEngine26.Timing timing() {
            return new AutoArmorDecisionEngine26.Timing(
                    actionCooldownTicks,
                    manualYieldTicks
            );
        }
    }

    public record Status(
            int cooldownTicks,
            int manualYieldTicks,
            boolean engineOutstanding,
            boolean prepared
    ) {
    }

    enum TransactionState {
        ORIGINAL,
        SOURCE_ON_CURSOR,
        DISPLACED_ON_CURSOR,
        COMPLETED,
        UNKNOWN
    }

    record TransactionObservation(
            boolean sourceSlotEmpty,
            boolean cursorEmpty,
            boolean sourceInSourceSlot,
            boolean sourceInArmorSlot,
            boolean sourceOnCursor,
            boolean equippedInSourceSlot,
            boolean equippedInArmorSlot,
            boolean equippedOnCursor,
            boolean equippedOriginallyEmpty
    ) {
    }

    static final class ManualChangeTracker {
        private boolean initialized;
        private boolean previousInventoryReady;
        private int previousSelectedSlot = -1;
        private int previousMenuStateId = -1;

        boolean observe(
                boolean inventoryReady,
                int selectedSlot,
                int menuStateId
        ) {
            if (!initialized) {
                synchronize(
                        inventoryReady,
                        selectedSlot,
                        menuStateId
                );
                return false;
            }
            boolean selectedChanged =
                    selectedSlot != previousSelectedSlot;
            boolean returnedFromManualMenu =
                    !previousInventoryReady && inventoryReady;
            boolean externalMenuMutation =
                    inventoryReady
                            && previousInventoryReady
                            && menuStateId != previousMenuStateId;
            synchronize(inventoryReady, selectedSlot, menuStateId);
            return selectedChanged
                    || returnedFromManualMenu
                    || externalMenuMutation;
        }

        void synchronize(
                boolean inventoryReady,
                int selectedSlot,
                int menuStateId
        ) {
            initialized = true;
            previousInventoryReady = inventoryReady;
            previousSelectedSlot = selectedSlot;
            previousMenuStateId = menuStateId;
        }

        void reset() {
            initialized = false;
            previousInventoryReady = false;
            previousSelectedSlot = -1;
            previousMenuStateId = -1;
        }
    }

    private record ArmorObservation(
            List<AutoArmorDecisionEngine26.EquippedArmor> equipped,
            List<AutoArmorDecisionEngine26.Candidate> candidates
    ) {
    }

    private record SessionIdentity(
            LocalPlayer player,
            Object level,
            ClientPacketListener connection
    ) {
        private static SessionIdentity capture(Minecraft client) {
            if (client == null
                    || client.player == null
                    || client.level == null
                    || client.getConnection() == null) {
                return null;
            }
            return new SessionIdentity(
                    client.player,
                    client.level,
                    client.getConnection()
            );
        }

        private boolean matches(Minecraft client) {
            return client != null
                    && client.player == player
                    && client.level == level
                    && client.getConnection() == connection;
        }
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

        boolean empty() {
            return expected.isEmpty();
        }
    }

    private static final class ArmorMetrics
            implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        private double armor;
        private double toughness;
        private double knockbackResistance;

        @Override
        public void accept(
                Holder<Attribute> attribute,
                AttributeModifier modifier
        ) {
            if (attribute.equals(Attributes.ARMOR)) {
                armor += modifier.amount();
            } else if (attribute.equals(
                    Attributes.ARMOR_TOUGHNESS
            )) {
                toughness += modifier.amount();
            } else if (attribute.equals(
                    Attributes.KNOCKBACK_RESISTANCE
            )) {
                knockbackResistance += modifier.amount();
            }
        }

        private void reset() {
            armor = 0.0;
            toughness = 0.0;
            knockbackResistance = 0.0;
        }
    }
}
