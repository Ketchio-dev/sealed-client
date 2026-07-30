package dev.b2tclient.v26.combat;

import dev.b2tclient.common.social.FriendBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Two-phase defensive construction combat service for Minecraft 26.2.
 *
 * <p>The service implements Surround, Hole Fill, Self Trap, Auto Trap, and a
 * conservative vanilla Burrow. Preparation is read-only and all mutations are
 * guarded by complete {@link CombatActionArbiter26} channel bundles. Every
 * placement must later be reflected by the server world as an exact obsidian
 * block before it is counted as successful.</p>
 *
 * <p>Candidate scans, entity checks, confirmation timeouts, retries, and
 * cooldowns are bounded. Targeted actions fail closed without a usable friend
 * book. Hotbar cleanup restores only the slot still owned by this service, so
 * a manual or foreign selection is never overwritten.</p>
 */
public final class CombatDefensiveConstructionAutomation26 {
    public static final String SURROUND_OWNER = "surround.action";
    public static final String HOLE_FILL_OWNER = "hole_fill.action";
    public static final String SELF_TRAP_OWNER = "self_trap.action";
    public static final String AUTO_TRAP_OWNER = "auto_trap.action";
    public static final String BURROW_OWNER = "burrow.action";

    private static final int SURROUND_PRIORITY = 78;
    private static final int HOLE_FILL_PRIORITY = 70;
    private static final int SELF_TRAP_PRIORITY = 76;
    private static final int AUTO_TRAP_PRIORITY = 72;
    private static final int BURROW_PRIORITY = 88;
    private static final int MAXIMUM_SIMPLE_CANDIDATES = 16;
    private static final int MAXIMUM_ENTITY_COLLISION_RESULTS = 1;
    private static final float BLAST_RESISTANCE_THRESHOLD = 600.0F;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP
    };
    private static final Set<CombatActionArbiter26.Channel>
            PLACEMENT_CHANNELS = Set.of(
            CombatActionArbiter26.Channel.USE,
            CombatActionArbiter26.Channel.HOTBAR
    );
    private static final Set<CombatActionArbiter26.Channel>
            BURROW_CHANNELS = Set.of(
            CombatActionArbiter26.Channel.USE,
            CombatActionArbiter26.Channel.HOTBAR,
            CombatActionArbiter26.Channel.MOVEMENT
    );

    private final EnumMap<ModuleId, Integer> cooldowns =
            new EnumMap<>(ModuleId.class);
    private final List<PreparedAction> preparedActions = new ArrayList<>(5);
    private final DefensiveConstructionDecisionEngine26.BurrowStateMachine
            burrowState =
            new DefensiveConstructionDecisionEngine26.BurrowStateMachine();

    private Configuration configuration = Configuration.defaults();
    private ConstructionConfirmation26 confirmation;
    private PendingPlacement pendingPlacement;
    private Object observedLevel;
    private Object observedDimension;
    private LocalPlayer observedPlayer;
    private BlockPos burrowStart;
    private long logicalTick;
    private long nextActionKey;
    private long preparedAtTick = -1L;
    private long confirmedPlacements;
    private long failedPlacements;
    private Outcome lastOutcome = Outcome.IDLE;
    private ModuleId lastModule;

    public CombatDefensiveConstructionAutomation26() {
        for (ModuleId module : ModuleId.values()) {
            cooldowns.put(module, 0);
        }
    }

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    /**
     * Read-only preparation phase. Call once after arbiter begin and before
     * resolution.
     */
    public void submit(
            Minecraft client,
            FriendBook friends,
            boolean surroundEnabled,
            boolean holeFillEnabled,
            boolean selfTrapEnabled,
            boolean autoTrapEnabled,
            boolean burrowEnabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        logicalTick++;
        preparedActions.clear();
        preparedAtTick = logicalTick;
        cooldowns.replaceAll((module, ticks) -> decrement(ticks));

        if (!sessionAllowsConstruction(client)) {
            if (client == null || client.player == null || client.level == null) {
                resetLifecycleState();
            }
            return;
        }
        if (!acceptWorld(client)) {
            return;
        }

        EnabledModules enabled = new EnabledModules(
                surroundEnabled,
                holeFillEnabled,
                selfTrapEnabled,
                autoTrapEnabled,
                burrowEnabled
        );
        if (pendingPlacement != null) {
            preparePending(client, friends, enabled, arbiter);
            return;
        }

        if (!burrowEnabled) {
            resetBurrow();
        } else if (prepareBurrow(client, arbiter)) {
            return;
        }
        if (burrowState.snapshot().phase()
                != DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Phase.IDLE) {
            // A Burrow transaction owns the player's vertical state until it
            // finishes or times out. Do not start unrelated construction.
            return;
        }

        int obsidianSlot = findObsidianSlot(client.player);
        if (obsidianSlot < 0) {
            return;
        }
        int selectedSlot = client.player.getInventory().getSelectedSlot();

        if (surroundEnabled
                && ready(ModuleId.SURROUND)
                && healthAllows(client.player, ModuleId.SURROUND)) {
            submitPrepared(
                    prepareSurround(
                            client,
                            selectedSlot,
                            obsidianSlot
                    ),
                    arbiter
            );
        }
        if (selfTrapEnabled
                && ready(ModuleId.SELF_TRAP)
                && healthAllows(client.player, ModuleId.SELF_TRAP)) {
            submitPrepared(
                    prepareSelfTrap(
                            client,
                            selectedSlot,
                            obsidianSlot
                    ),
                    arbiter
            );
        }
        if (autoTrapEnabled
                && ready(ModuleId.AUTO_TRAP)
                && healthAllows(client.player, ModuleId.AUTO_TRAP)
                && usableFriendBook(friends, ModuleId.AUTO_TRAP)) {
            Player target = selectTarget(
                    client,
                    friends,
                    ModuleId.AUTO_TRAP
            );
            submitPrepared(
                    prepareAutoTrap(
                            client,
                            friends,
                            target,
                            selectedSlot,
                            obsidianSlot
                    ),
                    arbiter
            );
        }
        if (holeFillEnabled
                && ready(ModuleId.HOLE_FILL)
                && healthAllows(client.player, ModuleId.HOLE_FILL)
                && usableFriendBook(friends, ModuleId.HOLE_FILL)) {
            Player target = selectTarget(
                    client,
                    friends,
                    ModuleId.HOLE_FILL
            );
            submitPrepared(
                    prepareHoleFill(
                            client,
                            friends,
                            target,
                            selectedSlot,
                            obsidianSlot
                    ),
                    arbiter
            );
        }
    }

    /**
     * Mutation phase. Executes at most one fully granted action.
     */
    public void execute(
            Minecraft client,
            FriendBook friends,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        if (preparedAtTick != logicalTick
                || !sessionAllowsConstruction(client)
                || !sameObservedWorld(client)) {
            preparedActions.clear();
            return;
        }
        preparedActions.sort(
                Comparator.comparingInt(PreparedAction::priority).reversed()
                        .thenComparing(PreparedAction::owner)
        );
        for (PreparedAction prepared : preparedActions) {
            if (!arbiter.ownsAll(prepared.owner(), prepared.channels())) {
                continue;
            }
            if (prepared.kind() == ActionKind.JUMP) {
                executeBurrowJump(client, prepared);
            } else {
                executePlacement(client, friends, prepared);
            }
            break;
        }
        preparedActions.clear();
    }

    /**
     * Disconnect/shutdown cleanup. No hotbar selection remains leased between
     * ticks, so cleanup cannot overwrite a later manual selection.
     */
    public void release(Minecraft client) {
        preparedActions.clear();
        resetLifecycleState();
        observedLevel = null;
        observedDimension = null;
        observedPlayer = null;
        preparedAtTick = -1L;
    }

    public Snapshot snapshot() {
        ConstructionConfirmation26.Snapshot confirmationSnapshot =
                confirmation == null ? null : confirmation.snapshot();
        DefensiveConstructionDecisionEngine26.BurrowStateMachine.Snapshot
                burrowSnapshot = burrowState.snapshot();
        return new Snapshot(
                logicalTick,
                lastOutcome,
                lastModule == null ? "none" : lastModule.id(),
                pendingPlacement == null ? null : pendingPlacement.position(),
                confirmationSnapshot == null
                        ? "IDLE"
                        : confirmationSnapshot.phase().name(),
                confirmationSnapshot == null
                        ? 0
                        : confirmationSnapshot.retries(),
                burrowSnapshot.phase().name(),
                burrowStart,
                confirmedPlacements,
                failedPlacements,
                Map.copyOf(cooldowns)
        );
    }

    private void preparePending(
            Minecraft client,
            FriendBook friends,
            EnabledModules enabled,
            CombatActionArbiter26 arbiter
    ) {
        PendingPlacement pending = pendingPlacement;
        if (pending.level() != observedLevel
                || !Objects.equals(
                pending.dimension(),
                observedDimension
        )) {
            abortPending(Outcome.WORLD_CHANGED);
            return;
        }
        if (!enabled.enabled(pending.module())) {
            abortPending(Outcome.DISABLED);
            return;
        }
        boolean confirmed = client.level.getBlockState(pending.position())
                .getBlock() == Blocks.OBSIDIAN;
        ConstructionConfirmation26.Result result = confirmation.observe(
                pending.key(),
                confirmed,
                logicalTick
        );
        if (result == ConstructionConfirmation26.Result.CONFIRMED) {
            completePending();
            return;
        }
        if (result == ConstructionConfirmation26.Result.FAILED) {
            failPending(Outcome.SERVER_TIMEOUT);
            return;
        }
        if (result != ConstructionConfirmation26.Result.RETRY) {
            return;
        }

        PreparedAction retry = prepareRetry(client, friends, pending);
        if (retry == null) {
            confirmation.fail();
            failPending(Outcome.REVALIDATION_FAILED);
            return;
        }
        submitPrepared(retry, arbiter);
    }

    private PreparedAction prepareRetry(
            Minecraft client,
            FriendBook friends,
            PendingPlacement pending
    ) {
        int obsidianSlot = findObsidianSlot(client.player);
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        if (obsidianSlot < 0
                || !validateModuleContext(
                client,
                friends,
                pending.module(),
                pending.position(),
                pending.targetUuid()
        )) {
            return null;
        }
        Support support = findSupport(client, pending.position());
        if (support == null) {
            return null;
        }
        return PreparedAction.placement(
                pending.module(),
                pending.key(),
                pending.position(),
                support,
                pending.targetUuid(),
                selectedSlot,
                obsidianSlot,
                true
        );
    }

    private boolean prepareBurrow(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        DefensiveConstructionDecisionEngine26.BurrowStateMachine.Snapshot
                state = burrowState.snapshot();
        if (state.phase()
                == DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Phase.FAILED) {
            failBurrow(Outcome.BURROW_ABORTED);
            return true;
        }
        if (!ready(ModuleId.BURROW)
                || !healthAllows(client.player, ModuleId.BURROW)) {
            return state.phase()
                    != DefensiveConstructionDecisionEngine26
                    .BurrowStateMachine.Phase.IDLE;
        }

        if (state.phase()
                == DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Phase.IDLE) {
            BlockPos start = client.player.blockPosition().immutable();
            if (!client.player.onGround()
                    || !validBurrowStart(client, start)
                    || findObsidianSlot(client.player) < 0) {
                return false;
            }
            long key = nextKey();
            if (!burrowState.begin(
                    key,
                    client.player.getY(),
                    logicalTick,
                    configuration.burrow().timeoutTicks()
            )) {
                return false;
            }
            burrowStart = start;
            state = burrowState.snapshot();
        }

        if (burrowStart == null
                || !client.level.getBlockState(burrowStart).canBeReplaced()) {
            failBurrow(Outcome.REVALIDATION_FAILED);
            return true;
        }
        DefensiveConstructionDecisionEngine26.BurrowStateMachine.Directive
                directive = burrowState.evaluate(
                client.player.getY(),
                client.player.onGround(),
                true,
                logicalTick,
                configuration.burrow().autoJump(),
                configuration.burrow().minimumRise()
        );
        if (directive
                == DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Directive.FAILED) {
            failBurrow(Outcome.BURROW_ABORTED);
            return true;
        }
        if (directive
                == DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Directive.JUMP) {
            PreparedAction jump = PreparedAction.jump(
                    state.targetKey(),
                    burrowStart
            );
            submitPrepared(jump, arbiter);
            return true;
        }
        if (directive
                != DefensiveConstructionDecisionEngine26
                .BurrowStateMachine.Directive.PLACE) {
            return true;
        }

        int obsidianSlot = findObsidianSlot(client.player);
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        Support support = findSupport(client, burrowStart);
        if (obsidianSlot < 0
                || support == null
                || !collisionFree(client, burrowStart, false)
                || distanceSquared(client.player, burrowStart)
                > square(policy(ModuleId.BURROW).placementRange())) {
            return true;
        }
        PreparedAction placement = PreparedAction.placement(
                ModuleId.BURROW,
                state.targetKey(),
                burrowStart,
                support,
                null,
                selectedSlot,
                obsidianSlot,
                false
        );
        submitPrepared(placement, arbiter);
        return true;
    }

    private PreparedAction prepareSurround(
            Minecraft client,
            int selectedSlot,
            int obsidianSlot
    ) {
        if (!client.player.onGround()) {
            return null;
        }
        BlockPos feet = client.player.blockPosition();
        List<BlockPos> positions = new ArrayList<>(5);
        if (configuration.surround().floor()) {
            positions.add(feet.below());
        }
        positions.add(feet.relative(Direction.NORTH));
        positions.add(feet.relative(Direction.SOUTH));
        positions.add(feet.relative(Direction.WEST));
        positions.add(feet.relative(Direction.EAST));
        return prepareFromPositions(
                client,
                ModuleId.SURROUND,
                positions,
                null,
                selectedSlot,
                obsidianSlot,
                true,
                false
        );
    }

    private PreparedAction prepareSelfTrap(
            Minecraft client,
            int selectedSlot,
            int obsidianSlot
    ) {
        if (!client.player.onGround()) {
            return null;
        }
        BlockPos feet = client.player.blockPosition();
        List<BlockPos> positions = new ArrayList<>(5);
        if (configuration.selfTrap().headSides()) {
            positions.add(feet.above().relative(Direction.NORTH));
            positions.add(feet.above().relative(Direction.SOUTH));
            positions.add(feet.above().relative(Direction.WEST));
            positions.add(feet.above().relative(Direction.EAST));
        }
        positions.add(feet.above(2));
        return prepareFromPositions(
                client,
                ModuleId.SELF_TRAP,
                positions,
                null,
                selectedSlot,
                obsidianSlot,
                true,
                false
        );
    }

    private PreparedAction prepareAutoTrap(
            Minecraft client,
            FriendBook friends,
            Player target,
            int selectedSlot,
            int obsidianSlot
    ) {
        if (!validTarget(
                client,
                friends,
                target,
                ModuleId.AUTO_TRAP
        )) {
            return null;
        }
        BlockPos feet = target.blockPosition();
        List<BlockPos> positions = new ArrayList<>(9);
        positions.add(feet.relative(Direction.NORTH));
        positions.add(feet.relative(Direction.SOUTH));
        positions.add(feet.relative(Direction.WEST));
        positions.add(feet.relative(Direction.EAST));
        if (configuration.autoTrap().headSides()) {
            positions.add(feet.above().relative(Direction.NORTH));
            positions.add(feet.above().relative(Direction.SOUTH));
            positions.add(feet.above().relative(Direction.WEST));
            positions.add(feet.above().relative(Direction.EAST));
        }
        positions.add(feet.above(2));
        return prepareFromPositions(
                client,
                ModuleId.AUTO_TRAP,
                positions,
                target,
                selectedSlot,
                obsidianSlot,
                true,
                false
        );
    }

    private PreparedAction prepareHoleFill(
            Minecraft client,
            FriendBook friends,
            Player target,
            int selectedSlot,
            int obsidianSlot
    ) {
        if (!validTarget(
                client,
                friends,
                target,
                ModuleId.HOLE_FILL
        )) {
            return null;
        }
        int radius = configuration.holeFill().scanRadius();
        BlockPos origin = client.player.blockPosition();
        List<PositionObservation> observations = new ArrayList<>(
                configuration.holeFill().maximumHoleScans()
        );
        int scanned = 0;
        for (int y = -1; y <= 1
                && scanned < configuration.holeFill().maximumHoleScans(); y++) {
            for (int x = -radius; x <= radius
                    && scanned < configuration.holeFill().maximumHoleScans(); x++) {
                for (int z = -radius; z <= radius
                        && scanned
                        < configuration.holeFill().maximumHoleScans(); z++) {
                    BlockPos position = origin.offset(x, y, z).immutable();
                    scanned++;
                    double targetDistance = Vec3.atCenterOf(position)
                            .distanceToSqr(target.position());
                    if (targetDistance
                            > square(configuration.holeFill().enemyRadius())) {
                        continue;
                    }
                    observations.add(observePosition(
                            client,
                            ModuleId.HOLE_FILL,
                            position,
                            0,
                            targetDistance,
                            true,
                            safeHole(client, position)
                    ));
                }
            }
        }
        PositionObservation selected = selectObservation(
                ModuleId.HOLE_FILL,
                observations,
                configuration.holeFill().maximumHoleScans()
        );
        if (selected == null) {
            return null;
        }
        return PreparedAction.placement(
                ModuleId.HOLE_FILL,
                nextKey(),
                selected.position(),
                selected.support(),
                target.getUUID(),
                selectedSlot,
                obsidianSlot,
                false
        );
    }

    private PreparedAction prepareFromPositions(
            Minecraft client,
            ModuleId module,
            List<BlockPos> positions,
            Player target,
            int selectedSlot,
            int obsidianSlot,
            boolean targetEligible,
            boolean holeSafe
    ) {
        List<PositionObservation> observations = new ArrayList<>(
                Math.min(positions.size(), MAXIMUM_SIMPLE_CANDIDATES)
        );
        int order = 0;
        for (BlockPos position : positions) {
            if (order >= MAXIMUM_SIMPLE_CANDIDATES) {
                break;
            }
            observations.add(observePosition(
                    client,
                    module,
                    position.immutable(),
                    order,
                    target == null
                            ? 0.0
                            : Vec3.atCenterOf(position)
                            .distanceToSqr(target.position()),
                    targetEligible,
                    holeSafe
            ));
            order++;
        }
        PositionObservation selected = selectObservation(
                module,
                observations,
                MAXIMUM_SIMPLE_CANDIDATES
        );
        if (selected == null) {
            return null;
        }
        return PreparedAction.placement(
                module,
                nextKey(),
                selected.position(),
                selected.support(),
                target == null ? null : target.getUUID(),
                selectedSlot,
                obsidianSlot,
                false
        );
    }

    private PositionObservation observePosition(
            Minecraft client,
            ModuleId module,
            BlockPos position,
            int order,
            double targetDistanceSquared,
            boolean targetEligible,
            boolean holeSafe
    ) {
        boolean replaceable = validWorldPosition(client, position)
                && client.level.getBlockState(position).canBeReplaced();
        Support support = replaceable ? findSupport(client, position) : null;
        boolean collisionFree = replaceable
                && collisionFree(client, position, false);
        DefensiveConstructionDecisionEngine26.Candidate candidate =
                new DefensiveConstructionDecisionEngine26.Candidate(
                        Integer.toUnsignedLong(order),
                        module.engineModule(),
                        order,
                        distanceSquared(client.player, position),
                        targetDistanceSquared,
                        replaceable,
                        support != null,
                        collisionFree,
                        targetEligible,
                        holeSafe
                );
        return new PositionObservation(position, support, candidate);
    }

    private PositionObservation selectObservation(
            ModuleId module,
            List<PositionObservation> observations,
            int maximumScans
    ) {
        List<DefensiveConstructionDecisionEngine26.Candidate> candidates =
                observations.stream()
                        .map(PositionObservation::candidate)
                        .toList();
        DefensiveConstructionDecisionEngine26.Candidate selected =
                DefensiveConstructionDecisionEngine26.selectBest(
                        candidates,
                        new DefensiveConstructionDecisionEngine26.ModeLimits(
                                module.engineModule(),
                                maximumScans,
                                square(policy(module).placementRange())
                        )
                );
        if (selected == null) {
            return null;
        }
        for (PositionObservation observation : observations) {
            if (observation.candidate() == selected) {
                return observation;
            }
        }
        return null;
    }

    private void submitPrepared(
            PreparedAction prepared,
            CombatActionArbiter26 arbiter
    ) {
        if (prepared == null) {
            return;
        }
        preparedActions.add(prepared);
        arbiter.submit(
                prepared.owner(),
                prepared.priority(),
                prepared.channels()
        );
    }

    private void executeBurrowJump(
            Minecraft client,
            PreparedAction prepared
    ) {
        if (prepared.module() != ModuleId.BURROW
                || burrowStart == null
                || !burrowStart.equals(prepared.position())
                || !client.player.onGround()
                || !client.player.blockPosition().equals(burrowStart)
                || !client.level.getBlockState(burrowStart)
                .canBeReplaced()) {
            failBurrow(Outcome.REVALIDATION_FAILED);
            return;
        }
        client.player.jumpFromGround();
        if (!burrowState.markJumpSent()) {
            failBurrow(Outcome.REVALIDATION_FAILED);
            return;
        }
        lastModule = ModuleId.BURROW;
        lastOutcome = Outcome.BURROW_JUMP_SENT;
    }

    private void executePlacement(
            Minecraft client,
            FriendBook friends,
            PreparedAction prepared
    ) {
        if (client.player.getInventory().getSelectedSlot()
                != prepared.expectedSelectedSlot()
                || !validHotbarItem(
                client.player,
                prepared.obsidianSlot()
        )
                || !validateModuleContext(
                client,
                friends,
                prepared.module(),
                prepared.position(),
                prepared.targetUuid()
        )) {
            onInteractionFailed(prepared, Outcome.REVALIDATION_FAILED);
            return;
        }
        Support support = findSupport(client, prepared.position());
        if (support == null) {
            onInteractionFailed(prepared, Outcome.REVALIDATION_FAILED);
            return;
        }

        int originalSlot =
                client.player.getInventory().getSelectedSlot();
        int appliedSlot = prepared.obsidianSlot();
        boolean sent = false;
        try {
            if (originalSlot != appliedSlot) {
                client.player.getInventory().setSelectedSlot(appliedSlot);
            }
            if (validHotbarItem(client.player, appliedSlot)
                    && client.player.getInventory().getSelectedSlot()
                    == appliedSlot) {
                sent = placeOnSupport(
                        client,
                        prepared.position(),
                        support
                );
            }
        } catch (RuntimeException ignored) {
            sent = false;
        } finally {
            int current = client.player.getInventory().getSelectedSlot();
            int restore =
                    DefensiveConstructionDecisionEngine26.restorationSlot(
                            originalSlot,
                            appliedSlot,
                            current
                    );
            if (restore >= 0 && restore != current) {
                client.player.getInventory().setSelectedSlot(restore);
            }
        }
        if (!sent) {
            onInteractionFailed(prepared, Outcome.INTERACTION_REJECTED);
            return;
        }

        if (prepared.retry()) {
            if (confirmation == null
                    || !confirmation.markRetried(logicalTick)) {
                onInteractionFailed(
                        prepared,
                        Outcome.REVALIDATION_FAILED
                );
                return;
            }
        } else {
            confirmation = new ConstructionConfirmation26(
                    policy(prepared.module()).confirmationTimeoutTicks(),
                    policy(prepared.module()).maximumRetries()
            );
            if (!confirmation.begin(prepared.key(), logicalTick)) {
                onInteractionFailed(
                        prepared,
                        Outcome.REVALIDATION_FAILED
                );
                return;
            }
            pendingPlacement = new PendingPlacement(
                    prepared.module(),
                    prepared.key(),
                    prepared.position().immutable(),
                    prepared.targetUuid(),
                    observedLevel,
                    observedDimension
            );
            if (prepared.module() == ModuleId.BURROW
                    && !burrowState.markPlacementSent()) {
                confirmation.fail();
                failPending(Outcome.REVALIDATION_FAILED);
                return;
            }
        }
        lastModule = prepared.module();
        lastOutcome = prepared.retry()
                ? Outcome.RETRY_SENT
                : Outcome.SENT_AWAITING_SERVER;
    }

    private boolean validateModuleContext(
            Minecraft client,
            FriendBook friends,
            ModuleId module,
            BlockPos position,
            UUID targetUuid
    ) {
        if (!sessionAllowsConstruction(client)
                || !sameObservedWorld(client)
                || !healthAllows(client.player, module)
                || distanceSquared(client.player, position)
                > square(policy(module).placementRange())
                || !validPlacementTarget(client, position)) {
            return false;
        }
        if (module == ModuleId.BURROW) {
            return burrowStart != null
                    && burrowStart.equals(position)
                    && client.player.getY()
                    >= burrowState.snapshot().startY()
                    + configuration.burrow().minimumRise();
        }
        if (module == ModuleId.SURROUND) {
            BlockPos feet = client.player.blockPosition();
            return client.player.onGround()
                    && surroundPosition(feet, position);
        }
        if (module == ModuleId.SELF_TRAP) {
            return client.player.onGround()
                    && selfTrapPosition(
                    client.player.blockPosition(),
                    position
            );
        }
        Player target = resolveTarget(client, targetUuid);
        if (!validTarget(client, friends, target, module)) {
            return false;
        }
        if (module == ModuleId.AUTO_TRAP) {
            return autoTrapPosition(target.blockPosition(), position);
        }
        return module == ModuleId.HOLE_FILL
                && safeHole(client, position)
                && Vec3.atCenterOf(position).distanceToSqr(
                target.position()
        ) <= square(configuration.holeFill().enemyRadius());
    }

    private boolean validPlacementTarget(
            Minecraft client,
            BlockPos position
    ) {
        return validWorldPosition(client, position)
                && client.level.getBlockState(position).canBeReplaced()
                && collisionFree(client, position, false)
                && findSupport(client, position) != null;
    }

    private void onInteractionFailed(
            PreparedAction prepared,
            Outcome outcome
    ) {
        if (prepared.retry() && confirmation != null) {
            confirmation.fail();
            failPending(outcome);
            return;
        }
        failedPlacements++;
        lastModule = prepared.module();
        lastOutcome = outcome;
        cooldowns.put(
                prepared.module(),
                policy(prepared.module()).failureCooldownTicks()
        );
        if (prepared.module() == ModuleId.BURROW) {
            resetBurrow();
        }
    }

    private void completePending() {
        PendingPlacement completed = pendingPlacement;
        if (completed.module() == ModuleId.BURROW
                && !burrowState.confirm(completed.key())) {
            failPending(Outcome.REVALIDATION_FAILED);
            return;
        }
        confirmedPlacements++;
        lastModule = completed.module();
        lastOutcome = Outcome.CONFIRMED;
        cooldowns.put(
                completed.module(),
                policy(completed.module()).actionCooldownTicks()
        );
        if (completed.module() == ModuleId.BURROW) {
            resetBurrow();
        }
        confirmation.reset();
        confirmation = null;
        pendingPlacement = null;
    }

    private void failPending(Outcome outcome) {
        PendingPlacement failed = pendingPlacement;
        failedPlacements++;
        lastModule = failed == null ? lastModule : failed.module();
        lastOutcome = outcome;
        if (failed != null) {
            cooldowns.put(
                    failed.module(),
                    policy(failed.module()).failureCooldownTicks()
            );
            if (failed.module() == ModuleId.BURROW) {
                burrowState.fail();
                resetBurrow();
            }
        }
        if (confirmation != null) {
            confirmation.reset();
        }
        confirmation = null;
        pendingPlacement = null;
    }

    private void abortPending(Outcome outcome) {
        lastModule = pendingPlacement == null
                ? lastModule
                : pendingPlacement.module();
        lastOutcome = outcome;
        if (pendingPlacement != null
                && pendingPlacement.module() == ModuleId.BURROW) {
            resetBurrow();
        }
        if (confirmation != null) {
            confirmation.reset();
        }
        confirmation = null;
        pendingPlacement = null;
    }

    private void failBurrow(Outcome outcome) {
        failedPlacements++;
        lastModule = ModuleId.BURROW;
        lastOutcome = outcome;
        cooldowns.put(
                ModuleId.BURROW,
                policy(ModuleId.BURROW).failureCooldownTicks()
        );
        resetBurrow();
    }

    private void resetBurrow() {
        burrowState.reset();
        burrowStart = null;
    }

    private boolean acceptWorld(Minecraft client) {
        Object level = client.level;
        Object dimension = client.level.dimension();
        LocalPlayer player = client.player;
        if (observedLevel == null) {
            observedLevel = level;
            observedDimension = dimension;
            observedPlayer = player;
            return true;
        }
        if (observedLevel != level
                || observedPlayer != player
                || !Objects.equals(observedDimension, dimension)) {
            abortPending(Outcome.WORLD_CHANGED);
            resetBurrow();
            preparedActions.clear();
            observedLevel = level;
            observedDimension = dimension;
            observedPlayer = player;
            cooldowns.replaceAll((module, ticks) -> 0);
            return false;
        }
        return true;
    }

    private boolean sameObservedWorld(Minecraft client) {
        return client != null
                && client.level == observedLevel
                && client.player == observedPlayer
                && Objects.equals(
                client.level.dimension(),
                observedDimension
        );
    }

    private void resetLifecycleState() {
        if (confirmation != null) {
            confirmation.reset();
        }
        confirmation = null;
        pendingPlacement = null;
        resetBurrow();
        preparedActions.clear();
        cooldowns.replaceAll((module, ticks) -> 0);
        lastOutcome = Outcome.IDLE;
        lastModule = null;
    }

    private Player selectTarget(
            Minecraft client,
            FriendBook friends,
            ModuleId module
    ) {
        ModePolicy policy = policy(module);
        List<Player> candidates = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Player.class),
                client.player.getBoundingBox().inflate(
                        policy.targetRange()
                ),
                player -> validTarget(
                        client,
                        friends,
                        player,
                        module
                ),
                candidates,
                policy.maximumPlayerScans()
        );
        return candidates.stream()
                .min(Comparator
                        .comparingDouble((Player player) ->
                                client.player.distanceToSqr(player))
                        .thenComparingInt(Player::getId))
                .orElse(null);
    }

    private boolean validTarget(
            Minecraft client,
            FriendBook friends,
            Player player,
            ModuleId module
    ) {
        ModePolicy policy = policy(module);
        return player != null
                && player != client.player
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator()
                && !player.isCreative()
                && client.player.distanceToSqr(player)
                <= square(policy.targetRange())
                && !isFriend(friends, player, module);
    }

    private boolean usableFriendBook(
            FriendBook friends,
            ModuleId module
    ) {
        if (friends == null) {
            return false;
        }
        try {
            return friends.all().size()
                    <= policy(module).maximumFriendEntries();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isFriend(
            FriendBook friends,
            Player player,
            ModuleId module
    ) {
        if (!usableFriendBook(friends, module) || player == null) {
            return true;
        }
        try {
            return friends.findByUuid(player.getUUID()).isPresent()
                    || friends.findByName(
                    player.getName().getString()
            ).isPresent();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static Player resolveTarget(
            Minecraft client,
            UUID targetUuid
    ) {
        if (targetUuid == null) {
            return null;
        }
        Entity entity = client.level.getEntity(targetUuid);
        return entity instanceof Player player ? player : null;
    }

    private boolean surroundPosition(
            BlockPos feet,
            BlockPos candidate
    ) {
        return (configuration.surround().floor()
                && candidate.equals(feet.below()))
                || candidate.equals(feet.relative(Direction.NORTH))
                || candidate.equals(feet.relative(Direction.SOUTH))
                || candidate.equals(feet.relative(Direction.WEST))
                || candidate.equals(feet.relative(Direction.EAST));
    }

    private boolean selfTrapPosition(
            BlockPos feet,
            BlockPos candidate
    ) {
        if (candidate.equals(feet.above(2))) {
            return true;
        }
        return configuration.selfTrap().headSides()
                && (candidate.equals(
                feet.above().relative(Direction.NORTH)
        )
                || candidate.equals(
                feet.above().relative(Direction.SOUTH)
        )
                || candidate.equals(
                feet.above().relative(Direction.WEST)
        )
                || candidate.equals(
                feet.above().relative(Direction.EAST)
        ));
    }

    private boolean autoTrapPosition(
            BlockPos feet,
            BlockPos candidate
    ) {
        if (candidate.equals(feet.relative(Direction.NORTH))
                || candidate.equals(feet.relative(Direction.SOUTH))
                || candidate.equals(feet.relative(Direction.WEST))
                || candidate.equals(feet.relative(Direction.EAST))
                || candidate.equals(feet.above(2))) {
            return true;
        }
        return configuration.autoTrap().headSides()
                && (candidate.equals(
                feet.above().relative(Direction.NORTH)
        )
                || candidate.equals(
                feet.above().relative(Direction.SOUTH)
        )
                || candidate.equals(
                feet.above().relative(Direction.WEST)
        )
                || candidate.equals(
                feet.above().relative(Direction.EAST)
        ));
    }

    private static boolean validBurrowStart(
            Minecraft client,
            BlockPos position
    ) {
        return validWorldPosition(client, position)
                && client.level.getBlockState(position).canBeReplaced()
                && findSupport(client, position) != null
                && collisionFree(client, position, true);
    }

    private static boolean safeHole(
            Minecraft client,
            BlockPos position
    ) {
        if (!validWorldPosition(client, position)
                || !client.level.getBlockState(position).canBeReplaced()
                || !client.level.getBlockState(position.above())
                .canBeReplaced()
                || !blastResistant(
                client.level.getBlockState(position.below())
        )) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!blastResistant(client.level.getBlockState(
                    position.relative(direction)
            ))) {
                return false;
            }
        }
        return true;
    }

    private static boolean blastResistant(BlockState state) {
        return state != null
                && !state.isAir()
                && state.getBlock().getExplosionResistance()
                >= BLAST_RESISTANCE_THRESHOLD;
    }

    private static Support findSupport(
            Minecraft client,
            BlockPos target
    ) {
        if (!validWorldPosition(client, target)) {
            return null;
        }
        for (Direction direction : SUPPORT_ORDER) {
            BlockPos neighbor = target.relative(direction);
            if (!validWorldPosition(client, neighbor)) {
                continue;
            }
            BlockState state = client.level.getBlockState(neighbor);
            if (state.canBeReplaced()) {
                continue;
            }
            return new Support(
                    neighbor.immutable(),
                    direction.getOpposite()
            );
        }
        return null;
    }

    private static boolean placeOnSupport(
            Minecraft client,
            BlockPos target,
            Support support
    ) {
        if (!validPlacementTargetStatic(client, target)
                || support == null
                || client.level.getBlockState(support.neighbor())
                .canBeReplaced()) {
            return false;
        }
        Vec3 hitLocation = Vec3.atCenterOf(support.neighbor()).add(
                support.clickedFace().getStepX() * 0.5,
                support.clickedFace().getStepY() * 0.5,
                support.clickedFace().getStepZ() * 0.5
        );
        BlockHitResult hit = new BlockHitResult(
                hitLocation,
                support.clickedFace(),
                support.neighbor(),
                false
        );
        InteractionResult result = client.gameMode.useItemOn(
                client.player,
                InteractionHand.MAIN_HAND,
                hit
        );
        if (!result.consumesAction()) {
            return false;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean validPlacementTargetStatic(
            Minecraft client,
            BlockPos position
    ) {
        return validWorldPosition(client, position)
                && client.level.getBlockState(position).canBeReplaced()
                && collisionFree(client, position, false);
    }

    private static boolean validWorldPosition(
            Minecraft client,
            BlockPos position
    ) {
        return client != null
                && client.level != null
                && position != null
                && client.level.isInWorldBounds(position)
                && client.level.getWorldBorder().isWithinBounds(position);
    }

    private static boolean collisionFree(
            Minecraft client,
            BlockPos position,
            boolean ignoreSelf
    ) {
        List<Entity> collisions = new ArrayList<>(1);
        client.level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                new AABB(position).deflate(0.001),
                entity -> entity.isAlive()
                        && !entity.isRemoved()
                        && (!ignoreSelf || entity != client.player),
                collisions,
                MAXIMUM_ENTITY_COLLISION_RESULTS
        );
        return collisions.isEmpty();
    }

    private static boolean sessionAllowsConstruction(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.player.level() == client.level
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator()
                && !client.player.isUsingItem()
                && client.player.containerMenu
                == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    private static int findObsidianSlot(LocalPlayer player) {
        for (int slot = 0;
                slot < DefensiveConstructionDecisionEngine26.HOTBAR_SIZE;
                slot++) {
            if (validHotbarItem(player, slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean validHotbarItem(
            LocalPlayer player,
            int slot
    ) {
        if (player == null
                || !DefensiveConstructionDecisionEngine26
                .validHotbarSlot(slot)) {
            return false;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        return !stack.isEmpty() && stack.getItem() == Items.OBSIDIAN;
    }

    private static double effectiveHealth(Player player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static double distanceSquared(
            LocalPlayer player,
            BlockPos position
    ) {
        return player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(position)
        );
    }

    private boolean ready(ModuleId module) {
        return cooldowns.getOrDefault(module, 0) <= 0;
    }

    private boolean healthAllows(Player player, ModuleId module) {
        return effectiveHealth(player) >= policy(module).minimumHealth();
    }

    ModePolicy policy(ModuleId module) {
        return configuration.policy(module);
    }

    private long nextKey() {
        long key = nextActionKey;
        nextActionKey = nextActionKey == Long.MAX_VALUE
                ? 0L
                : nextActionKey + 1L;
        return key;
    }

    private static int decrement(int ticks) {
        return ticks > 0 ? ticks - 1 : 0;
    }

    private static double square(double value) {
        return value * value;
    }

    static Set<CombatActionArbiter26.Channel> requiredChannels(
            ModuleId module
    ) {
        Objects.requireNonNull(module, "module");
        return module == ModuleId.BURROW
                ? BURROW_CHANNELS
                : PLACEMENT_CHANNELS;
    }

    public enum ModuleId {
        SURROUND("surround", SURROUND_OWNER, SURROUND_PRIORITY),
        HOLE_FILL("hole_fill", HOLE_FILL_OWNER, HOLE_FILL_PRIORITY),
        SELF_TRAP("self_trap", SELF_TRAP_OWNER, SELF_TRAP_PRIORITY),
        AUTO_TRAP("auto_trap", AUTO_TRAP_OWNER, AUTO_TRAP_PRIORITY),
        BURROW("burrow", BURROW_OWNER, BURROW_PRIORITY);

        private final String id;
        private final String owner;
        private final int priority;

        ModuleId(String id, String owner, int priority) {
            this.id = id;
            this.owner = owner;
            this.priority = priority;
        }

        public String id() {
            return id;
        }

        String owner() {
            return owner;
        }

        int priority() {
            return priority;
        }

        DefensiveConstructionDecisionEngine26.Module engineModule() {
            return DefensiveConstructionDecisionEngine26.Module.valueOf(
                    name()
            );
        }
    }

    public enum Outcome {
        IDLE,
        BURROW_JUMP_SENT,
        SENT_AWAITING_SERVER,
        RETRY_SENT,
        CONFIRMED,
        INTERACTION_REJECTED,
        REVALIDATION_FAILED,
        SERVER_TIMEOUT,
        BURROW_ABORTED,
        WORLD_CHANGED,
        DISABLED
    }

    /**
     * Immutable settings split by owning module. No runtime decision derives a
     * minimum, maximum, or fallback from another enabled module.
     */
    public record Configuration(
            SurroundConfiguration surround,
            HoleFillConfiguration holeFill,
            SelfTrapConfiguration selfTrap,
            AutoTrapConfiguration autoTrap,
            BurrowConfiguration burrow
    ) {
        public Configuration {
            surround = Objects.requireNonNull(surround, "surround");
            holeFill = Objects.requireNonNull(holeFill, "holeFill");
            selfTrap = Objects.requireNonNull(selfTrap, "selfTrap");
            autoTrap = Objects.requireNonNull(autoTrap, "autoTrap");
            burrow = Objects.requireNonNull(burrow, "burrow");
        }

        /**
         * Temporary source-compatible bridge for the original aggregated
         * configuration. New integrations must construct the five mode
         * records directly.
         */
        @Deprecated
        public Configuration(
                double targetRange,
                double placementRange,
                int holeScanRadius,
                double holeEnemyRadius,
                boolean surroundFloor,
                boolean selfTrapSides,
                boolean autoTrapSides,
                double minimumHealth,
                int actionCooldownTicks,
                int failureCooldownTicks,
                int confirmationTimeoutTicks,
                int maximumRetries,
                boolean burrowAutoJump,
                int burrowTimeoutTicks,
                double burrowMinimumRise,
                int maximumPlayerScans,
                int maximumHoleScans,
                int maximumFriendEntries
        ) {
            this(
                    new SurroundConfiguration(
                            placementRange,
                            surroundFloor,
                            minimumHealth,
                            actionCooldownTicks,
                            failureCooldownTicks,
                            confirmationTimeoutTicks,
                            maximumRetries
                    ),
                    new HoleFillConfiguration(
                            targetRange,
                            placementRange,
                            holeScanRadius,
                            holeEnemyRadius,
                            minimumHealth,
                            actionCooldownTicks,
                            failureCooldownTicks,
                            confirmationTimeoutTicks,
                            maximumRetries,
                            maximumPlayerScans,
                            maximumHoleScans,
                            maximumFriendEntries
                    ),
                    new SelfTrapConfiguration(
                            placementRange,
                            selfTrapSides,
                            minimumHealth,
                            actionCooldownTicks,
                            failureCooldownTicks,
                            confirmationTimeoutTicks,
                            maximumRetries
                    ),
                    new AutoTrapConfiguration(
                            targetRange,
                            placementRange,
                            autoTrapSides,
                            minimumHealth,
                            actionCooldownTicks,
                            failureCooldownTicks,
                            confirmationTimeoutTicks,
                            maximumRetries,
                            maximumPlayerScans,
                            maximumFriendEntries
                    ),
                    new BurrowConfiguration(
                            placementRange,
                            minimumHealth,
                            actionCooldownTicks,
                            failureCooldownTicks,
                            confirmationTimeoutTicks,
                            maximumRetries,
                            burrowAutoJump,
                            burrowTimeoutTicks,
                            burrowMinimumRise
                    )
            );
        }

        public static Configuration defaults() {
            return new Configuration(
                    SurroundConfiguration.defaults(),
                    HoleFillConfiguration.defaults(),
                    SelfTrapConfiguration.defaults(),
                    AutoTrapConfiguration.defaults(),
                    BurrowConfiguration.defaults()
            );
        }

        ModePolicy policy(ModuleId module) {
            Objects.requireNonNull(module, "module");
            return switch (module) {
                case SURROUND -> new ModePolicy(
                        surround.placementRange(),
                        surround.minimumHealth(),
                        surround.actionCooldownTicks(),
                        surround.failureCooldownTicks(),
                        surround.confirmationTimeoutTicks(),
                        surround.maximumRetries(),
                        0.0,
                        0,
                        0
                );
                case HOLE_FILL -> new ModePolicy(
                        holeFill.placementRange(),
                        holeFill.minimumHealth(),
                        holeFill.actionCooldownTicks(),
                        holeFill.failureCooldownTicks(),
                        holeFill.confirmationTimeoutTicks(),
                        holeFill.maximumRetries(),
                        holeFill.targetRange(),
                        holeFill.maximumPlayerScans(),
                        holeFill.maximumFriendEntries()
                );
                case SELF_TRAP -> new ModePolicy(
                        selfTrap.placementRange(),
                        selfTrap.minimumHealth(),
                        selfTrap.actionCooldownTicks(),
                        selfTrap.failureCooldownTicks(),
                        selfTrap.confirmationTimeoutTicks(),
                        selfTrap.maximumRetries(),
                        0.0,
                        0,
                        0
                );
                case AUTO_TRAP -> new ModePolicy(
                        autoTrap.placementRange(),
                        autoTrap.minimumHealth(),
                        autoTrap.actionCooldownTicks(),
                        autoTrap.failureCooldownTicks(),
                        autoTrap.confirmationTimeoutTicks(),
                        autoTrap.maximumRetries(),
                        autoTrap.targetRange(),
                        autoTrap.maximumPlayerScans(),
                        autoTrap.maximumFriendEntries()
                );
                case BURROW -> new ModePolicy(
                        burrow.placementRange(),
                        burrow.minimumHealth(),
                        burrow.actionCooldownTicks(),
                        burrow.failureCooldownTicks(),
                        burrow.confirmationTimeoutTicks(),
                        burrow.maximumRetries(),
                        0.0,
                        0,
                        0
                );
            };
        }

        // Legacy accessors remain only while the central settings adapter
        // transitions from aggregated values to the five mode records.
        @Deprecated
        public double targetRange() {
            return holeFill.targetRange();
        }

        @Deprecated
        public double placementRange() {
            return surround.placementRange();
        }

        @Deprecated
        public int holeScanRadius() {
            return holeFill.scanRadius();
        }

        @Deprecated
        public double holeEnemyRadius() {
            return holeFill.enemyRadius();
        }

        @Deprecated
        public boolean surroundFloor() {
            return surround.floor();
        }

        @Deprecated
        public boolean selfTrapSides() {
            return selfTrap.headSides();
        }

        @Deprecated
        public boolean autoTrapSides() {
            return autoTrap.headSides();
        }

        @Deprecated
        public double minimumHealth() {
            return surround.minimumHealth();
        }

        @Deprecated
        public int actionCooldownTicks() {
            return surround.actionCooldownTicks();
        }

        @Deprecated
        public int failureCooldownTicks() {
            return surround.failureCooldownTicks();
        }

        @Deprecated
        public int confirmationTimeoutTicks() {
            return surround.confirmationTimeoutTicks();
        }

        @Deprecated
        public int maximumRetries() {
            return surround.maximumRetries();
        }

        @Deprecated
        public boolean burrowAutoJump() {
            return burrow.autoJump();
        }

        @Deprecated
        public int burrowTimeoutTicks() {
            return burrow.timeoutTicks();
        }

        @Deprecated
        public double burrowMinimumRise() {
            return burrow.minimumRise();
        }

        @Deprecated
        public int maximumPlayerScans() {
            return holeFill.maximumPlayerScans();
        }

        @Deprecated
        public int maximumHoleScans() {
            return holeFill.maximumHoleScans();
        }

        @Deprecated
        public int maximumFriendEntries() {
            return holeFill.maximumFriendEntries();
        }
    }

    public record SurroundConfiguration(
            double placementRange,
            boolean floor,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries
    ) {
        public SurroundConfiguration {
            validatePlacementPolicy(
                    "surround",
                    placementRange,
                    minimumHealth,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    confirmationTimeoutTicks,
                    maximumRetries
            );
        }

        public static SurroundConfiguration defaults() {
            return new SurroundConfiguration(
                    4.5,
                    true,
                    8.0,
                    2,
                    40,
                    8,
                    1
            );
        }
    }

    public record HoleFillConfiguration(
            double targetRange,
            double placementRange,
            int scanRadius,
            double enemyRadius,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries,
            int maximumPlayerScans,
            int maximumHoleScans,
            int maximumFriendEntries
    ) {
        public HoleFillConfiguration {
            requireRange("holeFill.targetRange", targetRange, 2.0, 16.0);
            validatePlacementPolicy(
                    "holeFill",
                    placementRange,
                    minimumHealth,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    confirmationTimeoutTicks,
                    maximumRetries
            );
            requireRange("holeFill.scanRadius", scanRadius, 1, 6);
            requireRange("holeFill.enemyRadius", enemyRadius, 1.0, 6.0);
            validateTargetBudgets(
                    "holeFill",
                    maximumPlayerScans,
                    maximumFriendEntries
            );
            requireRange(
                    "holeFill.maximumHoleScans",
                    maximumHoleScans,
                    16,
                    1024
            );
        }

        public static HoleFillConfiguration defaults() {
            return new HoleFillConfiguration(
                    8.0,
                    4.5,
                    4,
                    3.0,
                    8.0,
                    2,
                    40,
                    8,
                    1,
                    48,
                    512,
                    64
            );
        }
    }

    public record SelfTrapConfiguration(
            double placementRange,
            boolean headSides,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries
    ) {
        public SelfTrapConfiguration {
            validatePlacementPolicy(
                    "selfTrap",
                    placementRange,
                    minimumHealth,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    confirmationTimeoutTicks,
                    maximumRetries
            );
        }

        public static SelfTrapConfiguration defaults() {
            return new SelfTrapConfiguration(
                    4.5,
                    false,
                    8.0,
                    2,
                    40,
                    8,
                    1
            );
        }
    }

    public record AutoTrapConfiguration(
            double targetRange,
            double placementRange,
            boolean headSides,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries,
            int maximumPlayerScans,
            int maximumFriendEntries
    ) {
        public AutoTrapConfiguration {
            requireRange("autoTrap.targetRange", targetRange, 2.0, 16.0);
            validatePlacementPolicy(
                    "autoTrap",
                    placementRange,
                    minimumHealth,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    confirmationTimeoutTicks,
                    maximumRetries
            );
            validateTargetBudgets(
                    "autoTrap",
                    maximumPlayerScans,
                    maximumFriendEntries
            );
        }

        public static AutoTrapConfiguration defaults() {
            return new AutoTrapConfiguration(
                    4.5,
                    4.5,
                    false,
                    8.0,
                    2,
                    40,
                    8,
                    1,
                    48,
                    64
            );
        }
    }

    public record BurrowConfiguration(
            double placementRange,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries,
            boolean autoJump,
            int timeoutTicks,
            double minimumRise
    ) {
        public BurrowConfiguration {
            validatePlacementPolicy(
                    "burrow",
                    placementRange,
                    minimumHealth,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    confirmationTimeoutTicks,
                    maximumRetries
            );
            requireRange("burrow.timeoutTicks", timeoutTicks, 4, 40);
            requireRange("burrow.minimumRise", minimumRise, 0.6, 1.4);
        }

        public static BurrowConfiguration defaults() {
            return new BurrowConfiguration(
                    4.5,
                    12.0,
                    2,
                    40,
                    8,
                    1,
                    true,
                    16,
                    1.0
            );
        }
    }

    record ModePolicy(
            double placementRange,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries,
            double targetRange,
            int maximumPlayerScans,
            int maximumFriendEntries
    ) {
    }

    private static void validatePlacementPolicy(
            String prefix,
            double placementRange,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int confirmationTimeoutTicks,
            int maximumRetries
    ) {
        requireRange(
                prefix + ".placementRange",
                placementRange,
                2.0,
                6.0
        );
        requireRange(
                prefix + ".minimumHealth",
                minimumHealth,
                1.0,
                40.0
        );
        requireRange(
                prefix + ".actionCooldownTicks",
                actionCooldownTicks,
                0,
                20
        );
        requireRange(
                prefix + ".failureCooldownTicks",
                failureCooldownTicks,
                1,
                200
        );
        requireRange(
                prefix + ".confirmationTimeoutTicks",
                confirmationTimeoutTicks,
                2,
                40
        );
        requireRange(
                prefix + ".maximumRetries",
                maximumRetries,
                0,
                3
        );
    }

    private static void validateTargetBudgets(
            String prefix,
            int maximumPlayerScans,
            int maximumFriendEntries
    ) {
        requireRange(
                prefix + ".maximumPlayerScans",
                maximumPlayerScans,
                1,
                64
        );
        requireRange(
                prefix + ".maximumFriendEntries",
                maximumFriendEntries,
                1,
                128
        );
    }

    private static void requireRange(
            String name,
            double value,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be within ["
                            + minimum + ", " + maximum + "]"
            );
        }
    }

    private static void requireRange(
            String name,
            int value,
            int minimum,
            int maximum
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be within ["
                            + minimum + ", " + maximum + "]"
            );
        }
    }

    public record Snapshot(
            long tick,
            Outcome outcome,
            String module,
            BlockPos pendingPosition,
            String confirmationPhase,
            int retries,
            String burrowPhase,
            BlockPos burrowStart,
            long confirmedPlacements,
            long failedPlacements,
            Map<ModuleId, Integer> cooldowns
    ) {
        public Snapshot {
            outcome = Objects.requireNonNull(outcome, "outcome");
            module = Objects.requireNonNull(module, "module");
            confirmationPhase = Objects.requireNonNull(
                    confirmationPhase,
                    "confirmationPhase"
            );
            burrowPhase = Objects.requireNonNull(
                    burrowPhase,
                    "burrowPhase"
            );
            cooldowns = Map.copyOf(cooldowns);
        }
    }

    private record EnabledModules(
            boolean surround,
            boolean holeFill,
            boolean selfTrap,
            boolean autoTrap,
            boolean burrow
    ) {
        boolean enabled(ModuleId module) {
            return switch (module) {
                case SURROUND -> surround;
                case HOLE_FILL -> holeFill;
                case SELF_TRAP -> selfTrap;
                case AUTO_TRAP -> autoTrap;
                case BURROW -> burrow;
            };
        }
    }

    private enum ActionKind {
        JUMP,
        PLACE
    }

    private record Support(
            BlockPos neighbor,
            Direction clickedFace
    ) {
    }

    private record PositionObservation(
            BlockPos position,
            Support support,
            DefensiveConstructionDecisionEngine26.Candidate candidate
    ) {
    }

    private record PendingPlacement(
            ModuleId module,
            long key,
            BlockPos position,
            UUID targetUuid,
            Object level,
            Object dimension
    ) {
    }

    private record PreparedAction(
            ActionKind kind,
            ModuleId module,
            long key,
            BlockPos position,
            Support support,
            UUID targetUuid,
            int expectedSelectedSlot,
            int obsidianSlot,
            boolean retry,
            Set<CombatActionArbiter26.Channel> channels
    ) {
        static PreparedAction jump(long key, BlockPos position) {
            return new PreparedAction(
                    ActionKind.JUMP,
                    ModuleId.BURROW,
                    key,
                    position,
                    null,
                    null,
                    -1,
                    -1,
                    false,
                    BURROW_CHANNELS
            );
        }

        static PreparedAction placement(
                ModuleId module,
                long key,
                BlockPos position,
                Support support,
                UUID targetUuid,
                int expectedSelectedSlot,
                int obsidianSlot,
                boolean retry
        ) {
            return new PreparedAction(
                    ActionKind.PLACE,
                    module,
                    key,
                    position,
                    support,
                    targetUuid,
                    expectedSelectedSlot,
                    obsidianSlot,
                    retry,
                    requiredChannels(module)
            );
        }

        String owner() {
            return module.owner();
        }

        int priority() {
            return module.priority();
        }
    }
}
