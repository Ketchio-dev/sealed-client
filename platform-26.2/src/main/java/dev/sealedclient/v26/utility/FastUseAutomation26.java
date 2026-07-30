package dev.sealedclient.v26.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Set;

/**
 * Packet-budgeted Fast Use service for Minecraft 26.2.
 *
 * <p>The service reduces only the client's generic right-click delay and lets
 * vanilla perform the eventual use. It therefore cannot emit a second packet
 * in the same tick as normal key handling. It never removes an item's own
 * vanilla cooldown and never accepts long-use food, bows, shields, blocks, or
 * an item outside the explicit whitelist.</p>
 */
public final class FastUseAutomation26 {
    private static volatile FastUseAutomation26 activeLeaseOwner;
    public static final String OWNER = "fast_use";
    public static final int PRIORITY = 20;
    public static final Set<UtilityActionArbiter26.Channel> CHANNELS =
            Set.of(UtilityActionArbiter26.Channel.USE);
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    new FastUseDecisionEngine26.Configuration(
                            2,
                            true,
                            false,
                            false,
                            false
                    ),
                    8,
                    20,
                    2
            );

    private final FastUseDecisionEngine26 engine;
    private Configuration configuration;
    private UtilityActionBudget26 budget;
    private LocalPlayer observedPlayer;
    private Object observedConnection;
    private Object observedLevel;
    private Object sessionIdentity;
    private PendingUse pending;
    private CooldownLease cooldownLease;
    private boolean lastEnabled;
    private boolean lastSafetyReady;

    public FastUseAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public FastUseAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new FastUseDecisionEngine26(configuration.policy());
        budget = configuration.newBudget();
    }

    public void setConfiguration(Configuration configuration) {
        Configuration requested = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        boolean budgetChanged =
                requested.maximumActionsPerWindow()
                        != this.configuration.maximumActionsPerWindow()
                        || requested.windowTicks()
                        != this.configuration.windowTicks()
                        || requested.minimumSpacingTicks()
                        != this.configuration.minimumSpacingTicks();
        this.configuration = requested;
        engine.setConfiguration(requested.policy());
        if (budgetChanged) {
            budget = requested.newBudget();
        }
    }

    public Configuration configuration() {
        return configuration;
    }

    public void submit(
            Minecraft client,
            boolean enabled,
            boolean safetyReady,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pending = null;
        lastEnabled = enabled;
        lastSafetyReady = safetyReady;
        LocalPlayer player = client == null ? null : client.player;
        reconcileAfterVanilla(client, enabled, safetyReady);
        if (observeSession(client, player)) {
            restoreCooldownLease();
            engine.reset();
            budget.reset();
        }
        ItemStack mainHand = player == null
                ? ItemStack.EMPTY
                : player.getMainHandItem();
        long tick = player == null ? 0L : Math.max(0, player.tickCount);
        FastUseDecisionEngine26.Decision decision = engine.step(
                new FastUseDecisionEngine26.Observation(
                        sessionIdentity,
                        enabled,
                        sessionReady(client, player),
                        safetyReady,
                        client != null && client.gui.screen() == null,
                        player != null
                                && player.isAlive()
                                && !player.isSpectator(),
                        client != null && client.options.keyUse.isDown(),
                        player != null && player.isUsingItem(),
                        player != null
                                && player.getCooldowns()
                                .isOnCooldown(mainHand),
                        player != null && player.isFallFlying(),
                        classify(mainHand)
                )
        );
        if (!decision.use()
                || !itemUseOnlyTarget(client)
                || !budget.canAcquire(tick)) {
            return;
        }
        pending = new PendingUse(
                decision,
                mainHand.copy(),
                tick,
                player.getInventory().getSelectedSlot()
        );
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    public boolean execute(
            Minecraft client,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PendingUse plan = pending;
        pending = null;
        if (plan == null
                || !arbiter.ownsAll(OWNER, CHANNELS)
                || !stillValid(client, plan)) {
            if (plan != null) {
                engine.commit(plan.decision(), false);
            }
            return false;
        }
        int currentDelay = FastUseCooldownAccess26.current(client);
        if (currentDelay <= configuration.policy().delayTicks()
                || !budget.acquire(plan.tick())) {
            engine.commit(plan.decision(), false);
            return false;
        }
        boolean applied = FastUseCooldownAccess26.limit(
                client,
                configuration.policy().delayTicks()
        );
        if (applied) {
            cooldownLease = new CooldownLease(
                    client,
                    client.player,
                    observedConnection,
                    observedLevel,
                    plan.selectedSlot(),
                    plan.expectedStack().copy(),
                    plan.decision().itemKind(),
                    currentDelay,
                    configuration.policy().delayTicks(),
                    0
            );
            activeLeaseOwner = this;
        }
        engine.commit(plan.decision(), applied);
        return applied;
    }

    /**
     * Must run from the Fabric START_CLIENT_TICK callback, before vanilla
     * evaluates right-click input. It restores the unmodified cooldown when
     * the exact whitelisted slot/stack/key/safety context has diverged.
     */
    public void prepareVanillaTick(
            Minecraft client,
            boolean enabled,
            boolean safetyReady
    ) {
        CooldownLease current = cooldownLease;
        lastEnabled = enabled;
        lastSafetyReady = safetyReady;
        if (current == null) {
            return;
        }
        int delay = FastUseCooldownAccess26.current(current.client());
        if (delay < 0) {
            clearCooldownLease();
            return;
        }
        if (delay > current.limitedDelay()) {
            // Vanilla reset the field while consuming the intended item.
            clearCooldownLease();
            return;
        }
        if (!leaseContextStillValid(
                client,
                enabled,
                safetyReady,
                current
        )) {
            restoreCooldownLease();
            return;
        }
        cooldownLease = current.withCompletedVanillaTicks(
                current.completedVanillaTicks() + 1
        );
    }

    public void release() {
        pending = null;
        restoreCooldownLease();
        observedPlayer = null;
        observedConnection = null;
        observedLevel = null;
        sessionIdentity = null;
        engine.reset();
        budget.reset();
    }

    /**
     * Final check used only by vanilla's held-use repeat invocation. Explicit
     * new clicks remain vanilla-owned even when a lease has become invalid.
     * Hotbar keys and the repeat gate execute in the same vanilla method after
     * START_CLIENT_TICK, so this closes the otherwise unobservable gap.
     */
    public static boolean blockUnsafeHeldRepeat(Minecraft client) {
        FastUseAutomation26 owner = activeLeaseOwner;
        if (owner == null || owner.cooldownLease == null) {
            return false;
        }
        boolean valid = owner.leaseContextStillValid(
                client,
                owner.lastEnabled,
                owner.lastSafetyReady,
                owner.cooldownLease
        );
        if (valid) {
            return false;
        }
        owner.restoreCooldownLease();
        return true;
    }

    static boolean shouldBlockLeasedUse(
            boolean leaseActive,
            boolean exactSafeContext,
            boolean heldRepeat
    ) {
        return heldRepeat && leaseActive && !exactSafeContext;
    }

    public Status status(long tick) {
        return new Status(
                engine.snapshot(),
                budget.snapshot(Math.max(0L, tick))
        );
    }

    static FastUseDecisionEngine26.ItemKind classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return FastUseDecisionEngine26.ItemKind.OTHER;
        }
        if (stack.is(Items.EXPERIENCE_BOTTLE)) {
            return FastUseDecisionEngine26.ItemKind.EXPERIENCE_BOTTLE;
        }
        if (stack.is(Items.EGG)
                || stack.is(Items.BLUE_EGG)
                || stack.is(Items.BROWN_EGG)
                || stack.is(Items.SNOWBALL)) {
            return FastUseDecisionEngine26.ItemKind.PROJECTILE;
        }
        if (stack.is(Items.ENDER_PEARL)) {
            return FastUseDecisionEngine26.ItemKind.ENDER_PEARL;
        }
        if (stack.is(Items.FIREWORK_ROCKET)) {
            return FastUseDecisionEngine26.ItemKind.FIREWORK;
        }
        return FastUseDecisionEngine26.ItemKind.OTHER;
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

    private static boolean stillValid(
            Minecraft client,
            PendingUse plan
    ) {
        LocalPlayer player = client == null ? null : client.player;
        if (!sessionReady(client, player)
                || !player.isAlive()
                || player.isSpectator()
                || client.gui.screen() != null
                || !client.options.keyUse.isDown()
                || player.isUsingItem()
                || player.getInventory().getSelectedSlot()
                != plan.selectedSlot()) {
            return false;
        }
        ItemStack current = player.getMainHandItem();
        return current.getCount() == plan.expectedStack().getCount()
                && ItemStack.isSameItemSameComponents(
                current,
                plan.expectedStack()
        )
                && !player.getCooldowns().isOnCooldown(current)
                && classify(current) == plan.decision().itemKind()
                && (plan.decision().itemKind()
                != FastUseDecisionEngine26.ItemKind.FIREWORK
                || player.isFallFlying());
    }

    private void reconcileAfterVanilla(
            Minecraft client,
            boolean enabled,
            boolean safetyReady
    ) {
        CooldownLease current = cooldownLease;
        if (current == null) {
            return;
        }
        int delay = FastUseCooldownAccess26.current(current.client());
        if (delay < 0 || delay > current.limitedDelay()) {
            clearCooldownLease();
            return;
        }
        if (!leaseContextStillValid(
                client,
                enabled,
                safetyReady,
                current
        )) {
            restoreCooldownLease();
        }
    }

    private boolean leaseContextStillValid(
            Minecraft client,
            boolean enabled,
            boolean safetyReady,
            CooldownLease lease
    ) {
        LocalPlayer player = client == null ? null : client.player;
        return configuration.policy().delayTicks()
                == lease.limitedDelay()
                && leaseContextValid(
                enabled,
                safetyReady,
                client == lease.client()
                        && player == lease.player()
                        && client.getConnection() == lease.connection()
                        && client.level == lease.level(),
                client != null && client.gui.screen() == null,
                itemUseOnlyTarget(client),
                player != null
                        && player.isAlive()
                        && !player.isSpectator(),
                client != null && client.options.keyUse.isDown(),
                player != null && !player.isUsingItem(),
                player != null
                        && player.getInventory().getSelectedSlot()
                        == lease.selectedSlot(),
                player != null && exactStack(
                        player.getMainHandItem(),
                        lease.expectedStack()
                ),
                player != null
                        && !player.getCooldowns().isOnCooldown(
                        player.getMainHandItem()
                ),
                player != null
                        && classify(player.getMainHandItem())
                        == lease.itemKind()
                        && configurationAllows(
                        lease.itemKind(),
                        player.isFallFlying()
                )
        );
    }

    private void restoreCooldownLease() {
        CooldownLease current = cooldownLease;
        clearCooldownLease();
        if (current == null) {
            return;
        }
        int delay = FastUseCooldownAccess26.current(current.client());
        if (delay < 0 || delay > current.limitedDelay()) {
            return;
        }
        int restored = FastUseCooldownAccess26.restoredValue(
                delay,
                current.originalDelay(),
                current.completedVanillaTicks()
        );
        FastUseCooldownAccess26.restore(current.client(), restored);
    }

    private void clearCooldownLease() {
        cooldownLease = null;
        if (activeLeaseOwner == this) {
            activeLeaseOwner = null;
        }
    }

    private boolean configurationAllows(
            FastUseDecisionEngine26.ItemKind kind,
            boolean fallFlying
    ) {
        FastUseDecisionEngine26.Configuration policy =
                configuration.policy();
        return switch (kind) {
            case EXPERIENCE_BOTTLE -> policy.experienceBottles();
            case PROJECTILE -> policy.projectiles();
            case ENDER_PEARL -> policy.enderPearls();
            case FIREWORK -> policy.fireworks() && fallFlying;
            case OTHER -> false;
        };
    }

    private static boolean exactStack(
            ItemStack actual,
            ItemStack expected
    ) {
        return actual != null
                && expected != null
                && !actual.isEmpty()
                && actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameComponents(actual, expected);
    }

    static boolean leaseContextValid(
            boolean enabled,
            boolean safetyReady,
            boolean sameSession,
            boolean screenClear,
            boolean itemUseOnlyTarget,
            boolean playerAlive,
            boolean useKeyDown,
            boolean notUsingItem,
            boolean sameSlot,
            boolean sameStack,
            boolean noItemCooldown,
            boolean allowedItem
    ) {
        return enabled
                && safetyReady
                && sameSession
                && screenClear
                && itemUseOnlyTarget
                && playerAlive
                && useKeyDown
                && notUsingItem
                && sameSlot
                && sameStack
                && noItemCooldown
                && allowedItem;
    }

    static boolean itemUseOnlyTarget(Minecraft client) {
        return client != null
                && client.hitResult != null
                && client.hitResult.getType() == HitResult.Type.MISS;
    }

    public record Configuration(
            FastUseDecisionEngine26.Configuration policy,
            int maximumActionsPerWindow,
            int windowTicks,
            int minimumSpacingTicks
    ) {
        public Configuration {
            policy = Objects.requireNonNull(policy, "policy");
            // Delegate validation and keep one authoritative budget contract.
            new UtilityActionBudget26(
                    maximumActionsPerWindow,
                    windowTicks,
                    minimumSpacingTicks
            );
        }

        UtilityActionBudget26 newBudget() {
            return new UtilityActionBudget26(
                    maximumActionsPerWindow,
                    windowTicks,
                    minimumSpacingTicks
            );
        }
    }

    private record PendingUse(
            FastUseDecisionEngine26.Decision decision,
            ItemStack expectedStack,
            long tick,
            int selectedSlot
    ) {
    }

    private record CooldownLease(
            Minecraft client,
            LocalPlayer player,
            Object connection,
            Object level,
            int selectedSlot,
            ItemStack expectedStack,
            FastUseDecisionEngine26.ItemKind itemKind,
            int originalDelay,
            int limitedDelay,
            int completedVanillaTicks
    ) {
        private CooldownLease {
            client = Objects.requireNonNull(client, "client");
            player = Objects.requireNonNull(player, "player");
            expectedStack = expectedStack.copy();
            itemKind = Objects.requireNonNull(itemKind, "itemKind");
            if (selectedSlot < 0 || selectedSlot > 8
                    || originalDelay < 0
                    || limitedDelay < 0
                    || completedVanillaTicks < 0) {
                throw new IllegalArgumentException(
                        "Invalid Fast Use cooldown lease"
                );
            }
        }

        CooldownLease withCompletedVanillaTicks(int ticks) {
            return new CooldownLease(
                    client,
                    player,
                    connection,
                    level,
                    selectedSlot,
                    expectedStack,
                    itemKind,
                    originalDelay,
                    limitedDelay,
                    ticks
            );
        }
    }

    public record Status(
            FastUseDecisionEngine26.Snapshot engine,
            UtilityActionBudget26.Snapshot budget
    ) {
    }
}
