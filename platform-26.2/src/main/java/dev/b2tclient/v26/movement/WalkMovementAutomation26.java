package dev.b2tclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Arbitration-friendly Minecraft 26.2 implementation of Safe Walk, Auto
 * Center, Hole Snap, and Step.
 *
 * <h2>Tick contract</h2>
 * <ol>
 *     <li>The runtime observes {@link MovementSafetyPolicy26} once.</li>
 *     <li>It calls {@link #submit} along with every other movement service.</li>
 *     <li>It calls {@link MovementActionArbiter26#resolve()} exactly once.</li>
 *     <li>It calls {@link #execute}; the returned horizontal motion, if any,
 *     is passed to {@link MovementSafetyPolicy26#recordApplied}.</li>
 * </ol>
 *
 * <p>{@code submit} performs no forward movement. It may revoke the one-tick
 * Safe Walk authorization or remove this service's own Step modifier when a
 * module becomes unsafe or disabled. That cleanup is deliberately immediate
 * and ownership checked. {@link #release} must be called on disconnect,
 * stopping, and whenever the runtime skips the normal execute phase.</p>
 */
public final class WalkMovementAutomation26 {
    public static final String SAFE_WALK_OWNER = "safe_walk";
    public static final String AUTO_CENTER_OWNER = "auto_center";
    public static final String HOLE_SNAP_OWNER = "hole_snap";
    public static final String STEP_OWNER = "step";

    public static final int SAFE_WALK_PRIORITY = 80;
    public static final int HOLE_SNAP_PRIORITY = 75;
    public static final int AUTO_CENTER_PRIORITY = 55;
    public static final int STEP_PRIORITY = 45;

    public static final Set<MovementActionArbiter26.Channel>
            SAFE_WALK_CHANNELS = Set.of(
                    MovementActionArbiter26.Channel.HORIZONTAL,
                    MovementActionArbiter26.Channel.KEY_INPUT
            );
    public static final Set<MovementActionArbiter26.Channel>
            HORIZONTAL_CHANNELS = Set.of(
                    MovementActionArbiter26.Channel.HORIZONTAL
            );
    public static final Set<MovementActionArbiter26.Channel>
            STEP_CHANNELS = Set.of(
                    MovementActionArbiter26.Channel.VERTICAL
            );

    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    0.45,
                    0.12,
                    0.04,
                    3,
                    0.20,
                    0.035,
                    384,
                    1.0,
                    0.20
            );

    private static final Identifier STEP_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(
                    "b2tclient",
                    "walk_movement_step_height"
            );
    private static final double BLAST_RESISTANCE_THRESHOLD = 600.0;
    private static final double POSITION_REVALIDATION_EPSILON_SQUARED = 0.04;
    private static final double MODIFIER_EPSILON = 1.0E-4;

    private final Object safeWalkLeaseOwner = new Object();
    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private ClientLevel observedLevel;
    private LocalPlayer modifiedStepPlayer;
    private double appliedStepTarget =
            WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT;
    private PreparedSafeWalk pendingSafeWalk = PreparedSafeWalk.none();
    private PreparedHorizontal pendingHoleSnap = PreparedHorizontal.none();
    private PreparedHorizontal pendingAutoCenter = PreparedHorizontal.none();
    private PreparedStep pendingStep = PreparedStep.none();
    private int lastHoleInspections;
    private long successfulExecutions;

    public WalkMovementAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public WalkMovementAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
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
    }

    /**
     * Prepares all enabled assists and submits their complete channel bundles.
     *
     * <p>A null or paused safety decision is treated as unsafe. Autonomous
     * centering and hole snapping submit only while directional, jump, and
     * crouch input are all idle. Step reacts to directional input but never
     * alters it. Safe Walk is the sole exception: it guards a manually
     * requested move at an unsupported edge using vanilla edge trimming.</p>
     */
    public void submit(
            Minecraft client,
            boolean safeWalkEnabled,
            boolean autoCenterEnabled,
            boolean holeSnapEnabled,
            boolean stepEnabled,
            MovementSafetyPolicy26.Decision safetyDecision,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        clearPending();
        lastHoleInspections = 0;
        synchronizeContext(client);

        if (!sessionReady(client)
                || safetyDecision == null
                || !safetyDecision.canApply()) {
            clearOwnedState(client);
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        WalkMovementDecisionEngine26.ControlState control =
                controlState(client, true);

        if (safeWalkEnabled) {
            PreparedSafeWalk safeWalk = prepareSafeWalk(
                    player,
                    level,
                    control
            );
            if (safeWalk.requested()) {
                pendingSafeWalk = safeWalk;
                arbiter.submit(
                        SAFE_WALK_OWNER,
                        SAFE_WALK_PRIORITY,
                        SAFE_WALK_CHANNELS
                );
            } else {
                SafeWalkGuard26.release(safeWalkLeaseOwner);
            }
        } else {
            SafeWalkGuard26.release(safeWalkLeaseOwner);
        }

        if (holeSnapEnabled) {
            PreparedHorizontal holeSnap = prepareHoleSnap(
                    player,
                    level,
                    control,
                    safetyDecision.scale()
            );
            if (holeSnap.requested()) {
                pendingHoleSnap = holeSnap;
                arbiter.submit(
                        HOLE_SNAP_OWNER,
                        HOLE_SNAP_PRIORITY,
                        HORIZONTAL_CHANNELS
                );
            }
        }

        if (autoCenterEnabled) {
            PreparedHorizontal center = prepareAutoCenter(
                    player,
                    level,
                    control,
                    safetyDecision.scale()
            );
            if (center.requested()) {
                pendingAutoCenter = center;
                arbiter.submit(
                        AUTO_CENTER_OWNER,
                        AUTO_CENTER_PRIORITY,
                        HORIZONTAL_CHANNELS
                );
            }
        }

        if (stepEnabled) {
            PreparedStep step = prepareStep(
                    player,
                    control,
                    safetyDecision.scale()
            );
            if (step.requested()) {
                pendingStep = step;
                arbiter.submit(
                        STEP_OWNER,
                        STEP_PRIORITY,
                        STEP_CHANNELS
                );
            } else {
                removeOwnedStepModifier();
            }
        } else {
            removeOwnedStepModifier();
        }
    }

    /**
     * Executes only current-tick grants and revalidates every mutable premise.
     */
    public Execution execute(
            Minecraft client,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        EnumSet<Assist> applied = EnumSet.noneOf(Assist.class);
        AppliedHorizontal horizontal = null;

        if (!sessionReady(client)
                || client.player != observedPlayer
                || client.level != observedLevel) {
            clearOwnedState(client);
            clearPending();
            return Execution.none(lastHoleInspections);
        }

        if (pendingSafeWalk.requested()
                && arbiter.ownsAll(
                        SAFE_WALK_OWNER,
                        SAFE_WALK_CHANNELS
                )
                && executeSafeWalk(client, pendingSafeWalk)) {
            applied.add(Assist.SAFE_WALK);
        } else {
            SafeWalkGuard26.release(safeWalkLeaseOwner);
        }

        if (pendingHoleSnap.requested()
                && arbiter.ownsAll(
                        HOLE_SNAP_OWNER,
                        HORIZONTAL_CHANNELS
                )) {
            horizontal = executeHorizontal(
                    client,
                    pendingHoleSnap,
                    Assist.HOLE_SNAP
            );
        } else if (pendingAutoCenter.requested()
                && arbiter.ownsAll(
                        AUTO_CENTER_OWNER,
                        HORIZONTAL_CHANNELS
                )) {
            horizontal = executeHorizontal(
                    client,
                    pendingAutoCenter,
                    Assist.AUTO_CENTER
            );
        }
        if (horizontal != null) {
            applied.add(horizontal.assist());
        }

        if (pendingStep.requested()
                && arbiter.ownsAll(STEP_OWNER, STEP_CHANNELS)
                && executeStep(client, pendingStep)) {
            applied.add(Assist.STEP);
        } else {
            removeOwnedStepModifier();
        }

        successfulExecutions += applied.size();
        clearPending();
        return new Execution(
                applied,
                Optional.ofNullable(horizontal),
                lastHoleInspections
        );
    }

    /**
     * Revokes all delayed behavior and removes only state still owned by this
     * service.
     */
    public void release(Minecraft client) {
        SafeWalkGuard26.release(safeWalkLeaseOwner);
        removeOwnedStepModifier();
        if (client != null
                && client.player != null
                && client.player != modifiedStepPlayer) {
            removeStepModifier(client.player);
        }
        observedPlayer = null;
        observedLevel = null;
        modifiedStepPlayer = null;
        appliedStepTarget =
                WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT;
        lastHoleInspections = 0;
        clearPending();
    }

    public Snapshot snapshot() {
        Assist pendingHorizontal = pendingHoleSnap.requested()
                ? Assist.HOLE_SNAP
                : pendingAutoCenter.requested()
                ? Assist.AUTO_CENTER
                : Assist.NONE;
        return new Snapshot(
                pendingSafeWalk.requested(),
                pendingHorizontal,
                pendingStep.requested(),
                lastHoleInspections,
                modifiedStepPlayer != null,
                appliedStepTarget,
                observedPlayer != null,
                successfulExecutions
        );
    }

    private PreparedSafeWalk prepareSafeWalk(
            LocalPlayer player,
            ClientLevel level,
            WalkMovementDecisionEngine26.ControlState control
    ) {
        DirectionVector direction = inputDirection(player);
        boolean unsupported = unsupportedAhead(
                player,
                level,
                direction,
                configuration.safeWalkLookAhead()
        );
        boolean requested =
                WalkMovementDecisionEngine26.shouldStopAtEdge(
                        new WalkMovementDecisionEngine26.EdgeObservation(
                                control,
                                direction.x(),
                                direction.z(),
                                configuration.safeWalkLookAhead(),
                                unsupported
                        )
                );
        return requested
                ? new PreparedSafeWalk(
                        true,
                        player,
                        level,
                        player.tickCount,
                        player.getX(),
                        player.getY(),
                        player.getZ()
                )
                : PreparedSafeWalk.none();
    }

    private PreparedHorizontal prepareAutoCenter(
            LocalPlayer player,
            ClientLevel level,
            WalkMovementDecisionEngine26.ControlState control,
            double safetyScale
    ) {
        BlockPos feet = feetPosition(player);
        BlockPos support = feet.below();
        if (!level.isLoaded(support)
                || level.getBlockState(support)
                        .getCollisionShape(level, support)
                        .isEmpty()) {
            return PreparedHorizontal.none();
        }
        return prepareHorizontal(
                Assist.AUTO_CENTER,
                player,
                level,
                control,
                feet.asLong(),
                feet.getX() + 0.5,
                feet.getZ() + 0.5,
                configuration.autoCenterSpeed(),
                configuration.autoCenterTolerance(),
                safetyScale
        );
    }

    private PreparedHorizontal prepareHoleSnap(
            LocalPlayer player,
            ClientLevel level,
            WalkMovementDecisionEngine26.ControlState control,
            double safetyScale
    ) {
        List<WalkMovementDecisionEngine26.HoleCandidate> candidates =
                collectHoleCandidates(player, level);
        WalkMovementDecisionEngine26.HoleSelection selection =
                WalkMovementDecisionEngine26.selectHole(
                        candidates,
                        configuration.maximumHoleScans(),
                        configuration.holeRadius() + 0.75,
                        1.25
                );
        lastHoleInspections = selection.inspected();
        WalkMovementDecisionEngine26.HoleCandidate candidate =
                selection.candidate().orElse(null);
        if (candidate == null) {
            return PreparedHorizontal.none();
        }
        return prepareHorizontal(
                Assist.HOLE_SNAP,
                player,
                level,
                control,
                candidate.key(),
                candidate.centerX(),
                candidate.centerZ(),
                configuration.holeSnapSpeed(),
                configuration.holeSnapTolerance(),
                safetyScale
        );
    }

    private PreparedHorizontal prepareHorizontal(
            Assist assist,
            LocalPlayer player,
            ClientLevel level,
            WalkMovementDecisionEngine26.ControlState control,
            long targetKey,
            double targetX,
            double targetZ,
            double speed,
            double tolerance,
            double safetyScale
    ) {
        WalkMovementDecisionEngine26.HorizontalPlan preliminary =
                horizontalPlan(
                        control,
                        player,
                        targetX,
                        targetZ,
                        speed,
                        tolerance,
                        safetyScale,
                        true
                ).orElse(null);
        if (preliminary == null) {
            return PreparedHorizontal.none();
        }
        boolean pathClear = preliminary.stop()
                || level.noCollision(
                        player,
                        player.getBoundingBox().move(
                                preliminary.deltaX(),
                                0.0,
                                preliminary.deltaZ()
                        )
                );
        WalkMovementDecisionEngine26.HorizontalPlan checked =
                horizontalPlan(
                        control,
                        player,
                        targetX,
                        targetZ,
                        speed,
                        tolerance,
                        safetyScale,
                        pathClear
                ).orElse(null);
        if (checked == null) {
            return PreparedHorizontal.none();
        }
        return new PreparedHorizontal(
                true,
                assist,
                player,
                level,
                player.tickCount,
                player.getX(),
                player.getY(),
                player.getZ(),
                targetKey,
                targetX,
                targetZ,
                speed,
                tolerance,
                safetyScale
        );
    }

    private PreparedStep prepareStep(
            LocalPlayer player,
            WalkMovementDecisionEngine26.ControlState control,
            double safetyScale
    ) {
        WalkMovementDecisionEngine26.StepPlan plan =
                WalkMovementDecisionEngine26.step(
                        new WalkMovementDecisionEngine26.StepObservation(
                                control,
                                appliedStepTarget,
                                configuration.stepHeight(),
                                configuration.maximumStepIncreasePerTick(),
                                safetyScale
                        )
                ).orElse(null);
        return plan == null
                ? PreparedStep.none()
                : new PreparedStep(
                        true,
                        player,
                        player.tickCount,
                        safetyScale
                );
    }

    private boolean executeSafeWalk(
            Minecraft client,
            PreparedSafeWalk prepared
    ) {
        LocalPlayer player = client.player;
        if (!samePreparation(player, prepared)
                || !WalkMovementDecisionEngine26.shouldStopAtEdge(
                        new WalkMovementDecisionEngine26.EdgeObservation(
                                controlState(client, true),
                                inputDirection(player).x(),
                                inputDirection(player).z(),
                                configuration.safeWalkLookAhead(),
                                unsupportedAhead(
                                        player,
                                        client.level,
                                        inputDirection(player),
                                        configuration.safeWalkLookAhead()
                                )
                        )
                )) {
            return false;
        }
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(0.0, velocity.y, 0.0);
        SafeWalkGuard26.authorize(
                safeWalkLeaseOwner,
                player,
                client.level,
                player.tickCount
        );
        return true;
    }

    private AppliedHorizontal executeHorizontal(
            Minecraft client,
            PreparedHorizontal prepared,
            Assist expectedAssist
    ) {
        LocalPlayer player = client.player;
        if (prepared.assist() != expectedAssist
                || !samePreparation(player, prepared)
                || (expectedAssist == Assist.HOLE_SNAP
                && !revalidateHole(client.level, prepared.targetKey()))) {
            return null;
        }
        if (expectedAssist == Assist.AUTO_CENTER
                && feetPosition(player).asLong() != prepared.targetKey()) {
            return null;
        }

        WalkMovementDecisionEngine26.ControlState control =
                controlState(client, true);
        WalkMovementDecisionEngine26.HorizontalPlan preliminary =
                horizontalPlan(
                        control,
                        player,
                        prepared.targetX(),
                        prepared.targetZ(),
                        prepared.maximumSpeed(),
                        prepared.tolerance(),
                        prepared.safetyScale(),
                        true
                ).orElse(null);
        if (preliminary == null) {
            return null;
        }
        boolean pathClear = preliminary.stop()
                || client.level.noCollision(
                        player,
                        player.getBoundingBox().move(
                                preliminary.deltaX(),
                                0.0,
                                preliminary.deltaZ()
                        )
                );
        WalkMovementDecisionEngine26.HorizontalPlan plan =
                horizontalPlan(
                        control,
                        player,
                        prepared.targetX(),
                        prepared.targetZ(),
                        prepared.maximumSpeed(),
                        prepared.tolerance(),
                        prepared.safetyScale(),
                        pathClear
                ).orElse(null);
        if (plan == null) {
            return null;
        }

        Vec3 movement = player.getDeltaMovement();
        player.setDeltaMovement(
                plan.deltaX(),
                movement.y,
                plan.deltaZ()
        );
        return new AppliedHorizontal(
                expectedAssist,
                plan.deltaX(),
                plan.deltaZ(),
                plan.stop()
        );
    }

    private boolean executeStep(
            Minecraft client,
            PreparedStep prepared
    ) {
        LocalPlayer player = client.player;
        if (prepared.player() != player
                || prepared.preparedTick() != player.tickCount) {
            return false;
        }
        WalkMovementDecisionEngine26.StepPlan plan =
                WalkMovementDecisionEngine26.step(
                        new WalkMovementDecisionEngine26.StepObservation(
                                controlState(client, true),
                                appliedStepTarget,
                                configuration.stepHeight(),
                                configuration.maximumStepIncreasePerTick(),
                                prepared.safetyScale()
                        )
                ).orElse(null);
        if (plan == null) {
            return false;
        }

        AttributeInstance attribute =
                player.getAttribute(Attributes.STEP_HEIGHT);
        if (attribute == null) {
            return false;
        }
        if (modifiedStepPlayer != null
                && modifiedStepPlayer != player) {
            removeStepModifier(modifiedStepPlayer);
        }
        attribute.removeModifier(STEP_MODIFIER_ID);
        double unmodified = attribute.getValue();
        double amount = plan.targetHeight() - unmodified;
        if (!Double.isFinite(amount) || amount <= MODIFIER_EPSILON) {
            modifiedStepPlayer = null;
            appliedStepTarget =
                    WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT;
            return false;
        }
        attribute.addOrUpdateTransientModifier(new AttributeModifier(
                STEP_MODIFIER_ID,
                amount,
                AttributeModifier.Operation.ADD_VALUE
        ));
        modifiedStepPlayer = player;
        appliedStepTarget = plan.targetHeight();
        return true;
    }

    private List<WalkMovementDecisionEngine26.HoleCandidate>
            collectHoleCandidates(
                    LocalPlayer player,
                    ClientLevel level
            ) {
        int budget = configuration.maximumHoleScans();
        List<WalkMovementDecisionEngine26.HoleCandidate> candidates =
                new ArrayList<>(Math.min(budget, 512));
        BlockPos origin = feetPosition(player);
        int radius = configuration.holeRadius();

        outer:
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (candidates.size() >= budget) {
                        break outer;
                    }
                    BlockPos position = origin.offset(x, yOffset, z);
                    boolean loaded = holeNeighborhoodLoaded(level, position);
                    boolean safe = loaded && isSafeHole(level, position);
                    double centerX = position.getX() + 0.5;
                    double centerY = position.getY();
                    double centerZ = position.getZ() + 0.5;
                    double deltaX = centerX - player.getX();
                    double deltaZ = centerZ - player.getZ();
                    double distanceSquared =
                            deltaX * deltaX + deltaZ * deltaZ;
                    double verticalDistance = Math.abs(
                            centerY - player.getY()
                    );
                    boolean pathClear = safe
                            && pathTowardCandidateIsClear(
                                    player,
                                    level,
                                    centerX,
                                    centerZ
                            );
                    candidates.add(
                            new WalkMovementDecisionEngine26.HoleCandidate(
                                    position.asLong(),
                                    centerX,
                                    centerY,
                                    centerZ,
                                    distanceSquared,
                                    verticalDistance,
                                    safe,
                                    loaded,
                                    pathClear
                            )
                    );
                }
            }
        }
        return candidates;
    }

    private boolean pathTowardCandidateIsClear(
            LocalPlayer player,
            ClientLevel level,
            double targetX,
            double targetZ
    ) {
        double deltaX = targetX - player.getX();
        double deltaZ = targetZ - player.getZ();
        double distance = Math.hypot(deltaX, deltaZ);
        if (!Double.isFinite(distance)) {
            return false;
        }
        if (distance < MODIFIER_EPSILON) {
            return true;
        }
        double step = Math.min(
                configuration.holeSnapSpeed(),
                distance
        );
        return level.noCollision(
                player,
                player.getBoundingBox().move(
                        deltaX / distance * step,
                        0.0,
                        deltaZ / distance * step
                )
        );
    }

    private static Optional<WalkMovementDecisionEngine26.HorizontalPlan>
            horizontalPlan(
                    WalkMovementDecisionEngine26.ControlState control,
                    LocalPlayer player,
                    double targetX,
                    double targetZ,
                    double maximumSpeed,
                    double tolerance,
                    double safetyScale,
                    boolean pathClear
            ) {
        return WalkMovementDecisionEngine26.steer(
                new WalkMovementDecisionEngine26.SteeringObservation(
                        control,
                        player.getX(),
                        player.getZ(),
                        targetX,
                        targetZ,
                        maximumSpeed,
                        tolerance,
                        safetyScale,
                        true,
                        pathClear
                )
        );
    }

    private static boolean unsupportedAhead(
            LocalPlayer player,
            ClientLevel level,
            DirectionVector direction,
            double lookAhead
    ) {
        if (!direction.finite()
                || direction.lengthSquared()
                < WalkMovementDecisionEngine26.INPUT_EPSILON_SQUARED) {
            return false;
        }
        BlockPos projectedSupport = BlockPos.containing(
                player.getX() + direction.x() * lookAhead,
                player.getY() - 0.20,
                player.getZ() + direction.z() * lookAhead
        );
        if (!level.isLoaded(projectedSupport)) {
            return true;
        }
        AABB supportProbe = player.getBoundingBox().move(
                direction.x() * lookAhead,
                -0.16,
                direction.z() * lookAhead
        );
        return level.noCollision(player, supportProbe);
    }

    private static boolean revalidateHole(
            ClientLevel level,
            long targetKey
    ) {
        BlockPos position = BlockPos.of(targetKey);
        return holeNeighborhoodLoaded(level, position)
                && isSafeHole(level, position);
    }

    private static boolean holeNeighborhoodLoaded(
            ClientLevel level,
            BlockPos feet
    ) {
        if (!level.isLoaded(feet)
                || !level.isLoaded(feet.above())
                || !level.isLoaded(feet.below())) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!level.isLoaded(feet.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeHole(
            ClientLevel level,
            BlockPos feet
    ) {
        if (!emptyDrySpace(level, feet)
                || !emptyDrySpace(level, feet.above())
                || !blastResistantFullBlock(level, feet.below())) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!blastResistantFullBlock(
                    level,
                    feet.relative(direction)
            )) {
                return false;
            }
        }
        return true;
    }

    private static boolean emptyDrySpace(
            ClientLevel level,
            BlockPos position
    ) {
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty()
                && state.getFluidState().isEmpty();
    }

    private static boolean blastResistantFullBlock(
            ClientLevel level,
            BlockPos position
    ) {
        BlockState state = level.getBlockState(position);
        return state.isCollisionShapeFullBlock(level, position)
                && state.getBlock().getExplosionResistance()
                >= BLAST_RESISTANCE_THRESHOLD;
    }

    private static WalkMovementDecisionEngine26.ControlState controlState(
            Minecraft client,
            boolean sessionReady
    ) {
        LocalPlayer player = client.player;
        boolean directional = manualDirectional(client, player);
        boolean jump = client.options.keyJump.isDown()
                || player.input.keyPresses.jump();
        boolean crouch = client.options.keyShift.isDown()
                || player.input.keyPresses.shift();
        return new WalkMovementDecisionEngine26.ControlState(
                sessionReady,
                player.isAlive() && !player.isDeadOrDying(),
                player.isSpectator(),
                player.isPassenger(),
                player.isInLiquid(),
                player.isFallFlying(),
                player.onGround(),
                directional,
                jump,
                crouch
        );
    }

    private static boolean manualDirectional(
            Minecraft client,
            LocalPlayer player
    ) {
        Vec2 movement = player.input.getMoveVector();
        return movement.lengthSquared()
                >= WalkMovementDecisionEngine26.INPUT_EPSILON_SQUARED
                || client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown();
    }

    private static DirectionVector inputDirection(LocalPlayer player) {
        Vec2 input = player.input.getMoveVector();
        double strafe = input.x;
        double forward = input.y;
        double length = Math.hypot(strafe, forward);
        if (!Double.isFinite(length) || length < 1.0E-4) {
            return DirectionVector.zero();
        }
        strafe /= Math.max(1.0, length);
        forward /= Math.max(1.0, length);
        double radians = Math.toRadians(player.getYRot());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new DirectionVector(
                strafe * cos - forward * sin,
                forward * cos + strafe * sin
        );
    }

    private static BlockPos feetPosition(LocalPlayer player) {
        return BlockPos.containing(
                player.getX(),
                player.getY() + 0.05,
                player.getZ()
        );
    }

    private void synchronizeContext(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        ClientLevel level = client == null ? null : client.level;
        if (player == observedPlayer && level == observedLevel) {
            return;
        }
        SafeWalkGuard26.release(safeWalkLeaseOwner);
        removeOwnedStepModifier();
        observedPlayer = player;
        observedLevel = level;
        appliedStepTarget =
                WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT;
    }

    private void clearOwnedState(Minecraft client) {
        SafeWalkGuard26.release(safeWalkLeaseOwner);
        removeOwnedStepModifier();
        if (client == null
                || client.player == null
                || client.player != observedPlayer
                || client.level != observedLevel) {
            observedPlayer = client == null ? null : client.player;
            observedLevel = client == null ? null : client.level;
        }
    }

    private void removeOwnedStepModifier() {
        removeStepModifier(modifiedStepPlayer);
        modifiedStepPlayer = null;
        appliedStepTarget =
                WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT;
    }

    private static void removeStepModifier(LocalPlayer player) {
        if (player == null) {
            return;
        }
        AttributeInstance attribute =
                player.getAttribute(Attributes.STEP_HEIGHT);
        if (attribute != null) {
            attribute.removeModifier(STEP_MODIFIER_ID);
        }
    }

    private void clearPending() {
        pendingSafeWalk = PreparedSafeWalk.none();
        pendingHoleSnap = PreparedHorizontal.none();
        pendingAutoCenter = PreparedHorizontal.none();
        pendingStep = PreparedStep.none();
    }

    private static boolean sessionReady(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected()
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator()
                && !client.player.isPassenger();
    }

    private static boolean samePreparation(
            LocalPlayer player,
            PreparedSafeWalk prepared
    ) {
        return prepared.player() == player
                && prepared.level() == player.level()
                && prepared.preparedTick() == player.tickCount
                && positionClose(
                        player,
                        prepared.x(),
                        prepared.y(),
                        prepared.z()
                );
    }

    private static boolean samePreparation(
            LocalPlayer player,
            PreparedHorizontal prepared
    ) {
        return prepared.player() == player
                && prepared.level() == player.level()
                && prepared.preparedTick() == player.tickCount
                && positionClose(
                        player,
                        prepared.x(),
                        prepared.y(),
                        prepared.z()
                );
    }

    private static boolean positionClose(
            LocalPlayer player,
            double x,
            double y,
            double z
    ) {
        double deltaX = player.getX() - x;
        double deltaY = player.getY() - y;
        double deltaZ = player.getZ() - z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                <= POSITION_REVALIDATION_EPSILON_SQUARED;
    }

    public enum Assist {
        NONE,
        SAFE_WALK,
        AUTO_CENTER,
        HOLE_SNAP,
        STEP
    }

    public record Configuration(
            double safeWalkLookAhead,
            double autoCenterSpeed,
            double autoCenterTolerance,
            int holeRadius,
            double holeSnapSpeed,
            double holeSnapTolerance,
            int maximumHoleScans,
            double stepHeight,
            double maximumStepIncreasePerTick
    ) {
        public Configuration {
            requireRange(
                    safeWalkLookAhead,
                    0.20,
                    0.80,
                    "safeWalkLookAhead"
            );
            requireRange(
                    autoCenterSpeed,
                    0.01,
                    0.25,
                    "autoCenterSpeed"
            );
            requireRange(
                    autoCenterTolerance,
                    0.005,
                    0.15,
                    "autoCenterTolerance"
            );
            if (holeRadius < 1 || holeRadius > 5) {
                throw new IllegalArgumentException(
                        "holeRadius must be within 1..5"
                );
            }
            requireRange(
                    holeSnapSpeed,
                    0.01,
                    0.35,
                    "holeSnapSpeed"
            );
            requireRange(
                    holeSnapTolerance,
                    0.005,
                    0.15,
                    "holeSnapTolerance"
            );
            if (maximumHoleScans < 1 || maximumHoleScans > 512) {
                throw new IllegalArgumentException(
                        "maximumHoleScans must be within 1..512"
                );
            }
            requireRange(stepHeight, 0.6, 1.5, "stepHeight");
            requireRange(
                    maximumStepIncreasePerTick,
                    0.05,
                    0.50,
                    "maximumStepIncreasePerTick"
            );
        }
    }

    public record AppliedHorizontal(
            Assist assist,
            double x,
            double z,
            boolean stop
    ) {
        public AppliedHorizontal {
            assist = Objects.requireNonNull(assist, "assist");
            if (assist != Assist.AUTO_CENTER
                    && assist != Assist.HOLE_SNAP) {
                throw new IllegalArgumentException(
                        "Applied horizontal assist must steer autonomously"
                );
            }
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException(
                        "Applied horizontal motion must be finite"
                );
            }
        }
    }

    public record Execution(
            Set<Assist> applied,
            Optional<AppliedHorizontal> horizontal,
            int holeInspections
    ) {
        public Execution {
            applied = Objects.requireNonNull(applied, "applied");
            applied = applied.isEmpty()
                    ? Set.of()
                    : Set.copyOf(applied);
            horizontal = Objects.requireNonNull(
                    horizontal,
                    "horizontal"
            );
            if (holeInspections < 0) {
                throw new IllegalArgumentException(
                        "holeInspections cannot be negative"
                );
            }
        }

        static Execution none(int holeInspections) {
            return new Execution(
                    Set.of(),
                    Optional.empty(),
                    holeInspections
            );
        }
    }

    public record Snapshot(
            boolean safeWalkPending,
            Assist pendingHorizontal,
            boolean stepPending,
            int lastHoleInspections,
            boolean stepModifierOwned,
            double appliedStepTarget,
            boolean contextPresent,
            long successfulExecutions
    ) {
        public Snapshot {
            pendingHorizontal = Objects.requireNonNull(
                    pendingHorizontal,
                    "pendingHorizontal"
            );
            if (lastHoleInspections < 0
                    || !Double.isFinite(appliedStepTarget)
                    || appliedStepTarget
                    < WalkMovementDecisionEngine26.VANILLA_STEP_HEIGHT
                    || successfulExecutions < 0L) {
                throw new IllegalArgumentException(
                        "Invalid movement automation snapshot"
                );
            }
        }
    }

    private record DirectionVector(double x, double z) {
        static DirectionVector zero() {
            return new DirectionVector(0.0, 0.0);
        }

        boolean finite() {
            return Double.isFinite(x) && Double.isFinite(z);
        }

        double lengthSquared() {
            return x * x + z * z;
        }
    }

    private record PreparedSafeWalk(
            boolean requested,
            LocalPlayer player,
            ClientLevel level,
            int preparedTick,
            double x,
            double y,
            double z
    ) {
        static PreparedSafeWalk none() {
            return new PreparedSafeWalk(
                    false,
                    null,
                    null,
                    Integer.MIN_VALUE,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }

    private record PreparedHorizontal(
            boolean requested,
            Assist assist,
            LocalPlayer player,
            ClientLevel level,
            int preparedTick,
            double x,
            double y,
            double z,
            long targetKey,
            double targetX,
            double targetZ,
            double maximumSpeed,
            double tolerance,
            double safetyScale
    ) {
        static PreparedHorizontal none() {
            return new PreparedHorizontal(
                    false,
                    Assist.NONE,
                    null,
                    null,
                    Integer.MIN_VALUE,
                    0.0,
                    0.0,
                    0.0,
                    0L,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }

    private record PreparedStep(
            boolean requested,
            LocalPlayer player,
            int preparedTick,
            double safetyScale
    ) {
        static PreparedStep none() {
            return new PreparedStep(
                    false,
                    null,
                    Integer.MIN_VALUE,
                    0.0
            );
        }
    }

    private static void requireRange(
            double value,
            double minimum,
            double maximum,
            String name
    ) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be finite and within "
                            + minimum + ".." + maximum
            );
        }
    }
}
