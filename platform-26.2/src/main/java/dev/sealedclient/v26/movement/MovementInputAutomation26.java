package dev.sealedclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

/**
 * Live 26.2 adapter for Ground Speed plus fail-closed mixin activation for
 * No Slow and No Rotate.
 */
public final class MovementInputAutomation26 {
    public static final String GROUND_SPEED_OWNER = "ground_speed";
    public static final int GROUND_SPEED_PRIORITY = 35;
    private static final Set<MovementActionArbiter26.Channel>
            GROUND_SPEED_CHANNELS =
            Set.of(MovementActionArbiter26.Channel.HORIZONTAL);
    private static final NoSlowInputPolicy26 NO_SLOW_POLICY =
            new NoSlowInputPolicy26();
    private static final NoRotatePolicy26 NO_ROTATE_POLICY =
            new NoRotatePolicy26();
    private static volatile HookState hookState = HookState.disabled();

    private final GroundSpeedDecisionEngine26 groundSpeedEngine =
            new GroundSpeedDecisionEngine26();
    private Configuration configuration = Configuration.DEFAULT;
    private GroundSpeedDecisionEngine26.Decision pendingGroundSpeed;
    private Execution lastExecution = Execution.none();

    /**
     * Publishes hook activation and submits Ground Speed's horizontal channel.
     * The runtime must call this during the arbiter collection phase.
     */
    public boolean submit(
            Minecraft client,
            boolean groundSpeedEnabled,
            boolean noSlowEnabled,
            boolean noRotateEnabled,
            MovementSafetyPolicy26.Decision safety,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(arbiter, "arbiter");
        publishHooks(
                client,
                noSlowEnabled,
                noRotateEnabled,
                safety
        );
        pendingGroundSpeed = decideGroundSpeed(
                client,
                groundSpeedEnabled,
                safety
        );
        lastExecution = Execution.none();
        if (pendingGroundSpeed == null || !pendingGroundSpeed.apply()) {
            arbiter.releaseOwner(GROUND_SPEED_OWNER);
            return false;
        }
        return arbiter.submit(
                GROUND_SPEED_OWNER,
                GROUND_SPEED_PRIORITY,
                GROUND_SPEED_CHANNELS
        );
    }

    /**
     * Applies only a previously submitted and fully granted decision.
     *
     * <p>The vertical component is copied exactly and no packet is generated.
     * The returned velocity can be passed to
     * {@link MovementSafetyPolicy26#recordApplied(double, double, double)}.</p>
     */
    public Execution execute(
            Minecraft client,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        GroundSpeedDecisionEngine26.Decision decision = pendingGroundSpeed;
        pendingGroundSpeed = null;
        if (decision == null
                || !decision.apply()
                || !arbiter.ownsAll(
                GROUND_SPEED_OWNER,
                GROUND_SPEED_CHANNELS
        )
                || client == null
                || client.player == null) {
            lastExecution = Execution.none();
            return lastExecution;
        }

        Vec3 current = client.player.getDeltaMovement();
        if (current == null || !Double.isFinite(current.y)) {
            lastExecution = Execution.none();
            return lastExecution;
        }
        Vec3 applied = new Vec3(
                decision.nextVelocityX(),
                current.y,
                decision.nextVelocityZ()
        );
        client.player.setDeltaMovement(applied);
        lastExecution = new Execution(
                true,
                applied.x,
                applied.y,
                applied.z,
                decision.safetyScale()
        );
        return lastExecution;
    }

    /**
     * Clears pending actions and disables both mixin hooks immediately.
     */
    public void release(MovementActionArbiter26 arbiter) {
        if (arbiter != null) {
            arbiter.releaseOwner(GROUND_SPEED_OWNER);
        }
        pendingGroundSpeed = null;
        lastExecution = Execution.none();
        hookState = HookState.disabled();
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

    public Execution lastExecution() {
        return lastExecution;
    }

    /**
     * Called exclusively from the LocalPlayer input-scaling redirect.
     */
    public static boolean shouldBypassItemSlowdown(LocalPlayer player) {
        HookState hooks = hookState;
        return NO_SLOW_POLICY.shouldBypass(new NoSlowInputPolicy26.Observation(
                hooks.noSlowEnabled(),
                hooks.sessionActive(),
                player != null && hooks.playerPresent(),
                player != null && player.isAlive() && !player.isDeadOrDying(),
                player != null && player.isUsingItem(),
                player != null && player.isPassenger()
        ));
    }

    /**
     * Called exclusively from server-rotation hooks.
     */
    public static boolean shouldPreserveServerYaw(LocalPlayer player) {
        return noRotateDecision(player).preserveYaw();
    }

    /**
     * Called exclusively from server-rotation hooks.
     */
    public static boolean shouldPreserveServerPitch(LocalPlayer player) {
        return noRotateDecision(player).preservePitch();
    }

    public static NoRotatePolicy26.PositionDecision preservePositionCorrection(
            LocalPlayer player,
            PositionMoveRotation correction,
            Set<Relative> relatives
    ) {
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(relatives, "relatives");
        if (player == null) {
            return new NoRotatePolicy26.PositionDecision(
                    correction,
                    relatives,
                    false
            );
        }
        HookState hooks = hookState;
        return NO_ROTATE_POLICY.decidePositionCorrection(
                noRotateObservation(hooks, player),
                hooks.noRotateConfiguration(),
                PositionMoveRotation.of(player),
                correction,
                relatives
        );
    }

    public static HookSnapshot hookSnapshot() {
        HookState state = hookState;
        return new HookSnapshot(
                state.noSlowEnabled(),
                state.noRotateEnabled(),
                state.sessionActive(),
                state.playerPresent(),
                state.playerAlive(),
                state.noRotateConfiguration()
        );
    }

    private GroundSpeedDecisionEngine26.Decision decideGroundSpeed(
            Minecraft client,
            boolean enabled,
            MovementSafetyPolicy26.Decision safety
    ) {
        LocalPlayer player = client == null ? null : client.player;
        boolean sessionActive = sessionActive(client);
        boolean playerPresent = player != null;
        boolean playerAlive = playerPresent
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator();
        boolean screenClear = client != null && client.gui.screen() == null;
        boolean networkReady = safety.networkReady();
        Vec2 input = playerPresent && player.input != null
                ? player.input.getMoveVector()
                : Vec2.ZERO;
        Vec3 velocity = playerPresent
                ? player.getDeltaMovement()
                : Vec3.ZERO;
        GroundSpeedDecisionEngine26.Observation observation =
                new GroundSpeedDecisionEngine26.Observation(
                        enabled,
                        sessionActive,
                        playerPresent,
                        playerAlive,
                        screenClear,
                        networkReady,
                        safety.canApply(),
                        safety.scale(),
                        playerPresent && player.onGround(),
                        playerPresent && player.isPassenger(),
                        playerPresent && player.isInWater(),
                        playerPresent && player.isInLava(),
                        playerPresent && player.isSwimming(),
                        playerPresent && player.isFallFlying(),
                        playerPresent && player.getAbilities().flying,
                        playerPresent && player.horizontalCollision,
                        input.x,
                        input.y,
                        playerPresent ? player.getYRot() : 0.0,
                        velocity.x,
                        velocity.z
                );
        return groundSpeedEngine.decide(
                observation,
                configuration.groundSpeed()
        );
    }

    private void publishHooks(
            Minecraft client,
            boolean noSlowEnabled,
            boolean noRotateEnabled,
            MovementSafetyPolicy26.Decision safety
    ) {
        LocalPlayer player = client == null ? null : client.player;
        boolean playerAlive = player != null
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator();
        boolean hookSafe = safety.canApply()
                && safety.networkReady()
                && client != null
                && client.gui.screen() == null
                && playerAlive;
        hookState = new HookState(
                noSlowEnabled && hookSafe,
                noRotateEnabled && hookSafe,
                sessionActive(client),
                player != null,
                playerAlive,
                configuration.noRotate()
        );
    }

    private static NoRotatePolicy26.Decision noRotateDecision(
            LocalPlayer player
    ) {
        HookState hooks = hookState;
        NoRotatePolicy26.Rotation current =
                player == null
                        ? new NoRotatePolicy26.Rotation(0.0F, 0.0F)
                        : new NoRotatePolicy26.Rotation(
                                player.getYRot(),
                                player.getXRot()
                        );
        return NO_ROTATE_POLICY.decide(
                noRotateObservation(hooks, player),
                hooks.noRotateConfiguration(),
                current,
                current,
                false,
                false
        );
    }

    private static NoRotatePolicy26.Observation noRotateObservation(
            HookState hooks,
            LocalPlayer player
    ) {
        return new NoRotatePolicy26.Observation(
                hooks.noRotateEnabled(),
                hooks.sessionActive(),
                player != null && hooks.playerPresent(),
                player != null
                        && hooks.playerAlive()
                        && player.isAlive()
                        && !player.isDeadOrDying()
        );
    }

    private static boolean sessionActive(Minecraft client) {
        return client != null
                && client.level != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected();
    }

    public record Configuration(
            GroundSpeedDecisionEngine26.Configuration groundSpeed,
            NoRotatePolicy26.Configuration noRotate
    ) {
        public static final Configuration DEFAULT = new Configuration(
                GroundSpeedDecisionEngine26.Configuration.DEFAULT,
                NoRotatePolicy26.Configuration.DEFAULT
        );

        public Configuration {
            groundSpeed = Objects.requireNonNull(groundSpeed, "groundSpeed");
            noRotate = Objects.requireNonNull(noRotate, "noRotate");
        }

        public Configuration(
                double targetSpeed,
                double accelerationPerTick,
                boolean preserveYaw,
                boolean preservePitch
        ) {
            this(
                    new GroundSpeedDecisionEngine26.Configuration(
                            targetSpeed,
                            accelerationPerTick
                    ),
                    new NoRotatePolicy26.Configuration(
                            preserveYaw,
                            preservePitch
                    )
            );
        }
    }

    public record Execution(
            boolean applied,
            double velocityX,
            double velocityY,
            double velocityZ,
            double safetyScale
    ) {
        public Execution {
            if (!Double.isFinite(velocityX)
                    || !Double.isFinite(velocityY)
                    || !Double.isFinite(velocityZ)
                    || !Double.isFinite(safetyScale)
                    || safetyScale < 0.0
                    || safetyScale > 1.0) {
                throw new IllegalArgumentException(
                        "Execution values must be finite and bounded"
                );
            }
        }

        private static Execution none() {
            return new Execution(false, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public record HookSnapshot(
            boolean noSlowEnabled,
            boolean noRotateEnabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            NoRotatePolicy26.Configuration noRotateConfiguration
    ) {
    }

    private record HookState(
            boolean noSlowEnabled,
            boolean noRotateEnabled,
            boolean sessionActive,
            boolean playerPresent,
            boolean playerAlive,
            NoRotatePolicy26.Configuration noRotateConfiguration
    ) {
        private HookState {
            noRotateConfiguration = Objects.requireNonNull(
                    noRotateConfiguration,
                    "noRotateConfiguration"
            );
        }

        private static HookState disabled() {
            return new HookState(
                    false,
                    false,
                    false,
                    false,
                    false,
                    NoRotatePolicy26.Configuration.DEFAULT
            );
        }
    }
}
