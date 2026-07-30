package dev.b2tclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

/**
 * Conservative Minecraft 26.2 implementation of No Fall, Fast Swim and Jesus.
 *
 * <p>No Fall uses the same verified sequence as vanilla: first
 * {@link LocalPlayer#tryToStartFallFlying()}, then exactly one
 * {@code START_FALL_FLYING} packet when that method succeeds. It never sends
 * ground-spoof packets. Fast Swim and Jesus only adjust bounded local velocity;
 * they do not spoof collision or fluid state.</p>
 *
 * <p>The runtime must call {@link #submit} during the movement arbiter's
 * collection phase and {@link #execute} after its single resolve call. A
 * shared {@link MovementSafetyPolicy26.Decision} is consumed so every movement
 * service reacts to the same latency and correction observation.</p>
 */
public final class FallWaterMovementAutomation26 {
    public static final String NO_FALL_OWNER = "no_fall";
    public static final String FAST_SWIM_OWNER = "fast_swim";
    public static final String JESUS_OWNER = "jesus";

    public static final int NO_FALL_PRIORITY = 90;
    public static final int FAST_SWIM_PRIORITY = 40;
    public static final int JESUS_PRIORITY = 65;

    public static final Set<MovementActionArbiter26.Channel>
            NO_FALL_CHANNELS =
            Set.of(MovementActionArbiter26.Channel.PACKET);
    public static final Set<MovementActionArbiter26.Channel>
            FAST_SWIM_CHANNELS =
            Set.of(MovementActionArbiter26.Channel.HORIZONTAL);
    public static final Set<MovementActionArbiter26.Channel>
            JESUS_CHANNELS =
            Set.of(MovementActionArbiter26.Channel.VERTICAL);

    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(3.2, 0.22, 0.08);

    private static final double MINIMUM_DESCENT_SPEED = 0.08;
    private static final double SWIM_ACCELERATION_BLEND = 0.24;
    private static final double JESUS_UPWARD_ACCELERATION = 0.04;
    private static final int MAXIMUM_NO_FALL_ACTIONS = 2;
    private static final int NO_FALL_ACTION_WINDOW_TICKS = 200;
    private static final int NO_FALL_ACTION_SPACING_TICKS = 20;

    private Configuration configuration;
    private FallWaterMovementDecisionEngine26.NoFallLimits noFallLimits;
    private FallWaterMovementDecisionEngine26.FastSwimLimits fastSwimLimits;
    private FallWaterMovementDecisionEngine26.JesusLimits jesusLimits;
    private MovementPacketBudget26 noFallBudget = newNoFallBudget();
    private LocalPlayer observedPlayer;
    private ClientLevel observedLevel;
    private boolean noFallEnabledLastTick;
    private boolean attemptedThisFall;
    private Pending pendingNoFall = Pending.none();
    private Pending pendingFastSwim = Pending.none();
    private Pending pendingJesus = Pending.none();
    private long lastArbiterTick;
    private long glideAttempts;
    private long noFallPacketsSent;
    private long fastSwimApplications;
    private long jesusApplications;
    private long executionFailures;
    private FallWaterMovementDecisionEngine26.BlockReason lastNoFallReason =
            FallWaterMovementDecisionEngine26.BlockReason.DISABLED;
    private FallWaterMovementDecisionEngine26.BlockReason lastFastSwimReason =
            FallWaterMovementDecisionEngine26.BlockReason.DISABLED;
    private FallWaterMovementDecisionEngine26.BlockReason lastJesusReason =
            FallWaterMovementDecisionEngine26.BlockReason.DISABLED;

    public FallWaterMovementAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public FallWaterMovementAutomation26(Configuration configuration) {
        setConfiguration(configuration);
    }

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        noFallLimits = configuration.noFallLimits();
        fastSwimLimits = configuration.fastSwimLimits();
        jesusLimits = configuration.jesusLimits();
    }

    /**
     * Prepares and submits up to three disjoint movement actions.
     */
    public void submit(
            Minecraft client,
            boolean noFallEnabled,
            boolean fastSwimEnabled,
            boolean jesusEnabled,
            MovementSafetyPolicy26.Decision safety,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        clearPending();
        lastArbiterTick = arbiter.tick();

        LocalPlayer player = client == null ? null : client.player;
        ClientLevel level = client == null ? null : client.level;
        if (player != observedPlayer || level != observedLevel) {
            resetForContext(player, level);
        }
        if (!noFallEnabled && noFallEnabledLastTick) {
            attemptedThisFall = false;
        }
        noFallEnabledLastTick = noFallEnabled;

        if (!connectedSession(client)) {
            resetForContext(null, null);
            releaseOwners(arbiter);
            return;
        }
        if (!controllableSession(client)) {
            releaseOwners(arbiter);
            return;
        }

        boolean safetyReady = safety != null && safety.canApply();
        boolean packetSafetyReady = safetyReady
                && safety.state() == MovementSafetyPolicy26.State.ACTIVE;
        double safetyScale = safetyReady ? safety.scale() : 0.0;
        Input input = player.input == null
                ? Input.EMPTY
                : player.input.keyPresses;
        Vec3 velocity = player.getDeltaMovement();

        FallWaterMovementDecisionEngine26.NoFallDecision noFall =
                decideNoFall(
                        player,
                        input,
                        velocity,
                        noFallEnabled,
                        packetSafetyReady,
                        noFallBudget.canAcquire(lastArbiterTick)
                );
        lastNoFallReason = noFall.reason();
        if (noFall.shouldResetAttempt()) {
            attemptedThisFall = false;
        } else if (noFall.shouldAttempt()) {
            pendingNoFall = Pending.requested(
                    player,
                    level,
                    player.tickCount,
                    lastArbiterTick,
                    safetyScale
            );
            arbiter.submit(
                    NO_FALL_OWNER,
                    NO_FALL_PRIORITY,
                    NO_FALL_CHANNELS
            );
        } else {
            arbiter.releaseOwner(NO_FALL_OWNER);
        }

        FallWaterMovementDecisionEngine26.VelocityDecision fastSwim =
                decideFastSwim(
                        player,
                        input,
                        velocity,
                        fastSwimEnabled,
                        safetyScale,
                        true
                );
        if (fastSwim.apply()) {
            boolean pathClear = horizontalPathClear(
                    level,
                    player,
                    fastSwim
            );
            fastSwim = decideFastSwim(
                    player,
                    input,
                    velocity,
                    fastSwimEnabled,
                    safetyScale,
                    pathClear
            );
        }
        lastFastSwimReason = fastSwim.reason();
        if (fastSwim.apply()) {
            pendingFastSwim = Pending.requested(
                    player,
                    level,
                    player.tickCount,
                    lastArbiterTick,
                    safetyScale
            );
            arbiter.submit(
                    FAST_SWIM_OWNER,
                    FAST_SWIM_PRIORITY,
                    FAST_SWIM_CHANNELS
            );
        } else {
            arbiter.releaseOwner(FAST_SWIM_OWNER);
        }

        SurfaceInfo surface = observeSurface(level, player);
        FallWaterMovementDecisionEngine26.VelocityDecision jesus =
                decideJesus(
                        player,
                        input,
                        velocity,
                        surface,
                        jesusEnabled,
                        safetyScale,
                        true
                );
        if (jesus.apply()) {
            boolean pathClear = upwardPathClear(
                    level,
                    player,
                    jesus.y()
            );
            jesus = decideJesus(
                    player,
                    input,
                    velocity,
                    surface,
                    jesusEnabled,
                    safetyScale,
                    pathClear
            );
        }
        lastJesusReason = jesus.reason();
        if (jesus.apply()) {
            pendingJesus = Pending.requested(
                    player,
                    level,
                    player.tickCount,
                    lastArbiterTick,
                    safetyScale
            );
            arbiter.submit(
                    JESUS_OWNER,
                    JESUS_PRIORITY,
                    JESUS_CHANNELS
            );
        } else {
            arbiter.releaseOwner(JESUS_OWNER);
        }
    }

    /**
     * Executes only the action bundles awarded by the movement arbiter.
     *
     * @return the final applied velocity, when either water assist changed it;
     *         the central runtime should pass that vector to
     *         {@link MovementSafetyPolicy26#recordApplied(double, double, double)}
     */
    public Execution execute(
            Minecraft client,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        Pending noFall = pendingNoFall;
        Pending fastSwim = pendingFastSwim;
        Pending jesus = pendingJesus;
        clearPending();

        boolean noFallSent = executeNoFall(client, arbiter, noFall);
        boolean fastSwimApplied =
                executeFastSwim(client, arbiter, fastSwim);
        boolean jesusApplied = executeJesus(client, arbiter, jesus);
        LocalPlayer player = client == null ? null : client.player;
        Vec3 finalVelocity = player == null
                ? Vec3.ZERO
                : player.getDeltaMovement();
        return new Execution(
                noFallSent,
                fastSwimApplied,
                jesusApplied,
                fastSwimApplied || jesusApplied,
                finalVelocity.x,
                finalVelocity.y,
                finalVelocity.z
        );
    }

    /**
     * Clears all session state and current movement claims.
     */
    public void release(MovementActionArbiter26 arbiter) {
        if (arbiter != null) {
            releaseOwners(arbiter);
        }
        resetForContext(null, null);
    }

    public void release() {
        resetForContext(null, null);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                attemptedThisFall,
                pendingNoFall.requested(),
                pendingFastSwim.requested(),
                pendingJesus.requested(),
                glideAttempts,
                noFallPacketsSent,
                fastSwimApplications,
                jesusApplications,
                executionFailures,
                lastNoFallReason,
                lastFastSwimReason,
                lastJesusReason,
                noFallBudget.snapshot(Math.max(0L, lastArbiterTick))
        );
    }

    private boolean executeNoFall(
            Minecraft client,
            MovementActionArbiter26 arbiter,
            Pending pending
    ) {
        if (!pendingMatches(client, pending, arbiter.tick())
                || !arbiter.ownsAll(NO_FALL_OWNER, NO_FALL_CHANNELS)) {
            return false;
        }
        LocalPlayer player = client.player;
        Input input = player.input == null
                ? Input.EMPTY
                : player.input.keyPresses;
        FallWaterMovementDecisionEngine26.NoFallDecision decision =
                decideNoFall(
                        player,
                        input,
                        player.getDeltaMovement(),
                        true,
                        pending.safetyScale() > 0.0,
                        noFallBudget.canAcquire(lastArbiterTick)
                );
        lastNoFallReason = decision.reason();
        if (!decision.shouldAttempt()
                || !noFallBudget.acquire(lastArbiterTick)) {
            return false;
        }

        attemptedThisFall = true;
        glideAttempts++;
        try {
            if (!player.tryToStartFallFlying()) {
                return false;
            }
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
            noFallPacketsSent++;
            return true;
        } catch (RuntimeException failure) {
            executionFailures++;
            return false;
        }
    }

    private boolean executeFastSwim(
            Minecraft client,
            MovementActionArbiter26 arbiter,
            Pending pending
    ) {
        if (!pendingMatches(client, pending, arbiter.tick())
                || !arbiter.ownsAll(
                        FAST_SWIM_OWNER,
                        FAST_SWIM_CHANNELS
                )) {
            return false;
        }
        LocalPlayer player = client.player;
        Input input = player.input == null
                ? Input.EMPTY
                : player.input.keyPresses;
        Vec3 velocity = player.getDeltaMovement();
        FallWaterMovementDecisionEngine26.VelocityDecision tentative =
                decideFastSwim(
                        player,
                        input,
                        velocity,
                        true,
                        pending.safetyScale(),
                        true
                );
        if (!tentative.apply()) {
            lastFastSwimReason = tentative.reason();
            return false;
        }
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                decideFastSwim(
                        player,
                        input,
                        velocity,
                        true,
                        pending.safetyScale(),
                        horizontalPathClear(
                                client.level,
                                player,
                                tentative
                        )
                );
        lastFastSwimReason = decision.reason();
        if (!decision.apply()) {
            return false;
        }
        try {
            player.setDeltaMovement(
                    decision.x(),
                    decision.y(),
                    decision.z()
            );
            fastSwimApplications++;
            return true;
        } catch (RuntimeException failure) {
            executionFailures++;
            return false;
        }
    }

    private boolean executeJesus(
            Minecraft client,
            MovementActionArbiter26 arbiter,
            Pending pending
    ) {
        if (!pendingMatches(client, pending, arbiter.tick())
                || !arbiter.ownsAll(JESUS_OWNER, JESUS_CHANNELS)) {
            return false;
        }
        LocalPlayer player = client.player;
        Input input = player.input == null
                ? Input.EMPTY
                : player.input.keyPresses;
        Vec3 velocity = player.getDeltaMovement();
        SurfaceInfo surface = observeSurface(client.level, player);
        FallWaterMovementDecisionEngine26.VelocityDecision tentative =
                decideJesus(
                        player,
                        input,
                        velocity,
                        surface,
                        true,
                        pending.safetyScale(),
                        true
                );
        if (!tentative.apply()) {
            lastJesusReason = tentative.reason();
            return false;
        }
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                decideJesus(
                        player,
                        input,
                        velocity,
                        surface,
                        true,
                        pending.safetyScale(),
                        upwardPathClear(
                                client.level,
                                player,
                                tentative.y()
                        )
                );
        lastJesusReason = decision.reason();
        if (!decision.apply()) {
            return false;
        }
        try {
            player.setDeltaMovement(
                    decision.x(),
                    decision.y(),
                    decision.z()
            );
            jesusApplications++;
            return true;
        } catch (RuntimeException failure) {
            executionFailures++;
            return false;
        }
    }

    private FallWaterMovementDecisionEngine26.NoFallDecision decideNoFall(
            LocalPlayer player,
            Input input,
            Vec3 velocity,
            boolean enabled,
            boolean safetyReady,
            boolean packetBudgetReady
    ) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        boolean usableElytra = chest.getItem() == Items.ELYTRA
                && LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST)
                && (!chest.isDamageableItem()
                || chest.getMaxDamage() - chest.getDamageValue() > 1);
        return FallWaterMovementDecisionEngine26.decideNoFall(
                new FallWaterMovementDecisionEngine26.NoFallObservation(
                        enabled,
                        safetyReady,
                        player.onGround(),
                        player.isInWater(),
                        player.isInLava(),
                        player.isFallFlying(),
                        player.isPassenger(),
                        player.onClimbable(),
                        player.isNoGravity(),
                        player.getAbilities().flying,
                        player.verticalCollision,
                        input.jump(),
                        input.shift(),
                        usableElytra,
                        attemptedThisFall,
                        packetBudgetReady,
                        player.fallDistance,
                        velocity.y
                ),
                noFallLimits
        );
    }

    private FallWaterMovementDecisionEngine26.VelocityDecision
            decideFastSwim(
                    LocalPlayer player,
                    Input input,
                    Vec3 velocity,
                    boolean enabled,
                    double safetyScale,
                    boolean pathClear
            ) {
        Vec2 movement = player.input == null
                ? Vec2.ZERO
                : player.input.getMoveVector();
        return FallWaterMovementDecisionEngine26.decideFastSwim(
                new FallWaterMovementDecisionEngine26.FastSwimObservation(
                        enabled,
                        safetyScale,
                        player.isInWater(),
                        player.isInLava(),
                        player.isPassenger(),
                        player.isFallFlying(),
                        player.isNoGravity()
                                || player.getAbilities().flying,
                        player.horizontalCollision,
                        pathClear,
                        movement.x,
                        movement.y,
                        player.getYRot(),
                        velocity.x,
                        velocity.y,
                        velocity.z
                ),
                fastSwimLimits
        );
    }

    private FallWaterMovementDecisionEngine26.VelocityDecision decideJesus(
            LocalPlayer player,
            Input input,
            Vec3 velocity,
            SurfaceInfo surface,
            boolean enabled,
            double safetyScale,
            boolean pathClear
    ) {
        return FallWaterMovementDecisionEngine26.decideJesus(
                new FallWaterMovementDecisionEngine26.JesusObservation(
                        enabled,
                        safetyScale,
                        player.isInWater(),
                        player.isUnderWater(),
                        player.isInLava() || surface.lava(),
                        surface.stableWater(),
                        surface.bubbleColumn(),
                        input.shift(),
                        player.isPassenger(),
                        player.isFallFlying(),
                        player.isNoGravity()
                                || player.getAbilities().flying,
                        player.verticalCollision
                                && !player.verticalCollisionBelow,
                        pathClear,
                        velocity.x,
                        velocity.y,
                        velocity.z
                ),
                jesusLimits
        );
    }

    private static boolean connectedSession(Minecraft client) {
        return client != null
                && client.level != null
                && client.player != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
    }

    private static boolean controllableSession(Minecraft client) {
        return connectedSession(client)
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static boolean pendingMatches(
            Minecraft client,
            Pending pending,
            long arbiterTick
    ) {
        return pending.requested()
                && controllableSession(client)
                && client.player == pending.player()
                && client.level == pending.level()
                && client.player.tickCount == pending.playerTick()
                && arbiterTick == pending.arbiterTick();
    }

    private static boolean horizontalPathClear(
            ClientLevel level,
            LocalPlayer player,
            FallWaterMovementDecisionEngine26.VelocityDecision decision
    ) {
        if (level == null || player == null || !decision.apply()) {
            return false;
        }
        AABB moved = player.getBoundingBox().move(
                decision.x(),
                0.0,
                decision.z()
        );
        return !moved.hasNaN()
                && boundsLoaded(level, moved)
                && level.noCollision(player, moved);
    }

    private static boolean upwardPathClear(
            ClientLevel level,
            LocalPlayer player,
            double targetY
    ) {
        if (level == null || player == null || !Double.isFinite(targetY)) {
            return false;
        }
        double upwardProbe = Math.max(
                0.05,
                Math.max(0.0, targetY)
        );
        AABB moved = player.getBoundingBox().move(0.0, upwardProbe, 0.0);
        return !moved.hasNaN()
                && boundsLoaded(level, moved)
                && level.noCollision(player, moved);
    }

    private static boolean boundsLoaded(ClientLevel level, AABB bounds) {
        if (!Double.isFinite(bounds.minX)
                || !Double.isFinite(bounds.minY)
                || !Double.isFinite(bounds.minZ)
                || !Double.isFinite(bounds.maxX)
                || !Double.isFinite(bounds.maxY)
                || !Double.isFinite(bounds.maxZ)) {
            return false;
        }
        double maxX = Math.nextDown(bounds.maxX);
        double maxY = Math.nextDown(bounds.maxY);
        double maxZ = Math.nextDown(bounds.maxZ);
        return level.isLoaded(BlockPos.containing(
                bounds.minX,
                bounds.minY,
                bounds.minZ
        )) && level.isLoaded(BlockPos.containing(
                maxX,
                bounds.minY,
                bounds.minZ
        )) && level.isLoaded(BlockPos.containing(
                bounds.minX,
                bounds.minY,
                maxZ
        )) && level.isLoaded(BlockPos.containing(
                maxX,
                bounds.minY,
                maxZ
        )) && level.isLoaded(BlockPos.containing(
                bounds.minX,
                maxY,
                bounds.minZ
        )) && level.isLoaded(BlockPos.containing(
                maxX,
                maxY,
                bounds.minZ
        )) && level.isLoaded(BlockPos.containing(
                bounds.minX,
                maxY,
                maxZ
        )) && level.isLoaded(BlockPos.containing(
                maxX,
                maxY,
                maxZ
        ));
    }

    private static SurfaceInfo observeSurface(
            ClientLevel level,
            LocalPlayer player
    ) {
        if (level == null || player == null) {
            return SurfaceInfo.none();
        }
        AABB bounds = player.getBoundingBox();
        BlockPos feet = BlockPos.containing(
                player.getX(),
                bounds.minY + 0.02,
                player.getZ()
        );
        SurfaceInfo atFeet = observeSurfaceAt(level, feet, bounds.minY);
        if (atFeet.water() || atFeet.lava()) {
            return atFeet;
        }
        return observeSurfaceAt(level, feet.below(), bounds.minY);
    }

    private static SurfaceInfo observeSurfaceAt(
            ClientLevel level,
            BlockPos position,
            double feetY
    ) {
        if (!level.isLoaded(position)) {
            return SurfaceInfo.none();
        }
        FluidState fluid = level.getFluidState(position);
        boolean water = fluid.is(FluidTags.WATER);
        boolean lava = fluid.is(FluidTags.LAVA);
        boolean bubble = level.getBlockState(position)
                .is(Blocks.BUBBLE_COLUMN);
        double fluidTop = position.getY()
                + (fluid.isEmpty() ? 0.0 : fluid.getHeight(level, position));
        double depthAtFeet = fluidTop - feetY;
        boolean stableWater = water
                && fluid.isSource()
                && depthAtFeet > 0.01
                && depthAtFeet <= 1.05;
        return new SurfaceInfo(
                water,
                lava,
                stableWater,
                bubble
        );
    }

    private void clearPending() {
        pendingNoFall = Pending.none();
        pendingFastSwim = Pending.none();
        pendingJesus = Pending.none();
    }

    private void resetForContext(
            LocalPlayer player,
            ClientLevel level
    ) {
        observedPlayer = player;
        observedLevel = level;
        clearPending();
        noFallEnabledLastTick = false;
        attemptedThisFall = false;
        noFallBudget = newNoFallBudget();
    }

    private static MovementPacketBudget26 newNoFallBudget() {
        return new MovementPacketBudget26(
                MAXIMUM_NO_FALL_ACTIONS,
                NO_FALL_ACTION_WINDOW_TICKS,
                NO_FALL_ACTION_SPACING_TICKS
        );
    }

    private static void releaseOwners(MovementActionArbiter26 arbiter) {
        arbiter.releaseOwner(NO_FALL_OWNER);
        arbiter.releaseOwner(FAST_SWIM_OWNER);
        arbiter.releaseOwner(JESUS_OWNER);
    }

    private record Pending(
            boolean requested,
            LocalPlayer player,
            ClientLevel level,
            int playerTick,
            long arbiterTick,
            double safetyScale
    ) {
        private static Pending none() {
            return new Pending(false, null, null, 0, 0L, 0.0);
        }

        private static Pending requested(
                LocalPlayer player,
                ClientLevel level,
                int playerTick,
                long arbiterTick,
                double safetyScale
        ) {
            return new Pending(
                    true,
                    Objects.requireNonNull(player, "player"),
                    Objects.requireNonNull(level, "level"),
                    playerTick,
                    arbiterTick,
                    safetyScale
            );
        }
    }

    private record SurfaceInfo(
            boolean water,
            boolean lava,
            boolean stableWater,
            boolean bubbleColumn
    ) {
        private static SurfaceInfo none() {
            return new SurfaceInfo(false, false, false, false);
        }
    }

    public record Configuration(
            double noFallTriggerDistance,
            double fastSwimSpeed,
            double jesusBuoyancy
    ) {
        public Configuration {
            // Reuse the pure-policy validation as the authoritative ranges.
            new FallWaterMovementDecisionEngine26.NoFallLimits(
                    noFallTriggerDistance,
                    MINIMUM_DESCENT_SPEED
            );
            new FallWaterMovementDecisionEngine26.FastSwimLimits(
                    fastSwimSpeed,
                    SWIM_ACCELERATION_BLEND
            );
            new FallWaterMovementDecisionEngine26.JesusLimits(
                    jesusBuoyancy,
                    JESUS_UPWARD_ACCELERATION
            );
        }

        public FallWaterMovementDecisionEngine26.NoFallLimits
                noFallLimits() {
            return new FallWaterMovementDecisionEngine26.NoFallLimits(
                    noFallTriggerDistance,
                    MINIMUM_DESCENT_SPEED
            );
        }

        public FallWaterMovementDecisionEngine26.FastSwimLimits
                fastSwimLimits() {
            return new FallWaterMovementDecisionEngine26.FastSwimLimits(
                    fastSwimSpeed,
                    SWIM_ACCELERATION_BLEND
            );
        }

        public FallWaterMovementDecisionEngine26.JesusLimits
                jesusLimits() {
            return new FallWaterMovementDecisionEngine26.JesusLimits(
                    jesusBuoyancy,
                    JESUS_UPWARD_ACCELERATION
            );
        }
    }

    public record Execution(
            boolean noFallPacketSent,
            boolean fastSwimApplied,
            boolean jesusApplied,
            boolean velocityApplied,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        public Execution {
            if (velocityApplied
                    && (!Double.isFinite(velocityX)
                    || !Double.isFinite(velocityY)
                    || !Double.isFinite(velocityZ))) {
                throw new IllegalArgumentException(
                        "Applied execution velocity must be finite"
                );
            }
        }
    }

    public record Snapshot(
            boolean attemptedThisFall,
            boolean noFallPending,
            boolean fastSwimPending,
            boolean jesusPending,
            long glideAttempts,
            long noFallPacketsSent,
            long fastSwimApplications,
            long jesusApplications,
            long executionFailures,
            FallWaterMovementDecisionEngine26.BlockReason lastNoFallReason,
            FallWaterMovementDecisionEngine26.BlockReason
                    lastFastSwimReason,
            FallWaterMovementDecisionEngine26.BlockReason lastJesusReason,
            MovementPacketBudget26.Snapshot packetBudget
    ) {
        public Snapshot {
            if (glideAttempts < 0
                    || noFallPacketsSent < 0
                    || fastSwimApplications < 0
                    || jesusApplications < 0
                    || executionFailures < 0) {
                throw new IllegalArgumentException(
                        "Movement counters cannot be negative"
                );
            }
            Objects.requireNonNull(lastNoFallReason, "lastNoFallReason");
            Objects.requireNonNull(
                    lastFastSwimReason,
                    "lastFastSwimReason"
            );
            Objects.requireNonNull(lastJesusReason, "lastJesusReason");
            Objects.requireNonNull(packetBudget, "packetBudget");
        }
    }
}
