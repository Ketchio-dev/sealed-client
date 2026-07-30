package dev.sealedclient.v26.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Live Minecraft 26.2 adapter for bounded Elytra Control.
 *
 * <p>It never starts fall flying and submits no packet action. A velocity or
 * pitch change is prepared only while the server-reflected player state says
 * {@link LocalPlayer#isFallFlying()} and the shared movement safety decision
 * permits it. The same live state and exact pre-action motion are revalidated
 * after arbitration.</p>
 */
public final class ElytraControlAutomation26 {
    public static final String OWNER = "elytra_control";
    public static final int PRIORITY = 60;
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    1.25,
                    0.04,
                    0.25,
                    2.0,
                    -12.0,
                    22.0,
                    3.0,
                    8
            );

    private static final double REVALIDATION_EPSILON = 1.0E-9;

    private final ElytraControlDecisionEngine26 engine =
            new ElytraControlDecisionEngine26();
    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private ElytraControlDecisionEngine26.Decision pending;
    private Set<MovementActionArbiter26.Channel> pendingChannels =
            Set.of();
    private int preparedTick = Integer.MIN_VALUE;
    private Vec3 preparedVelocity;
    private float preparedPitch;
    private boolean lastExecutionApplied;
    private boolean lastVelocityApplied;

    public ElytraControlAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public ElytraControlAutomation26(Configuration configuration) {
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

    public void submit(
            Minecraft client,
            boolean enabled,
            MovementSafetyPolicy26.Decision safety,
            MovementActionArbiter26 arbiter
    ) {
        submit(client, enabled, true, safety, arbiter);
    }

    public void submit(
            Minecraft client,
            boolean enabled,
            boolean allowPitch,
            MovementSafetyPolicy26.Decision safety,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(arbiter, "arbiter");
        clearPrepared();
        lastExecutionApplied = false;
        lastVelocityApplied = false;

        LocalPlayer player = client == null ? null : client.player;
        if (player != observedPlayer) {
            observedPlayer = player;
            engine.reset();
        }
        Vec2 input = player == null || player.input == null
                ? Vec2.ZERO
                : player.input.getMoveVector();
        Vec3 velocity = player == null
                ? Vec3.ZERO
                : player.getDeltaMovement();
        boolean jump = player != null
                && player.input != null
                && player.input.keyPresses.jump();
        boolean shift = player != null
                && player.input != null
                && player.input.keyPresses.shift();
        ElytraControlDecisionEngine26.Observation observation =
                new ElytraControlDecisionEngine26.Observation(
                        sessionKey(client),
                        enabled,
                        sessionReady(client),
                        client != null && client.gui.screen() == null,
                        safety.canApply(),
                        safety.scale(),
                        player != null && player.isFallFlying(),
                        player != null && player.isPassenger(),
                        player != null && player.isInWater(),
                        player != null && player.isInLava(),
                        player != null && player.horizontalCollision,
                        input.x,
                        input.y,
                        jump,
                        shift,
                        player == null ? 0.0 : player.getYRot(),
                        player == null ? 0.0F : player.getXRot(),
                        velocity.x,
                        velocity.y,
                        velocity.z
                );
        ElytraControlDecisionEngine26.Decision decision =
                engine.decide(observation, configuration.engine());
        if (!allowPitch && decision.applyPitch()) {
            decision = suppressPitch(decision);
        }
        if (!decision.apply()) {
            return;
        }

        EnumSet<MovementActionArbiter26.Channel> channels =
                EnumSet.noneOf(MovementActionArbiter26.Channel.class);
        if (decision.applyHorizontal()) {
            channels.add(MovementActionArbiter26.Channel.HORIZONTAL);
        }
        if (decision.applyVertical()) {
            channels.add(MovementActionArbiter26.Channel.VERTICAL);
        }
        if (decision.applyPitch()) {
            channels.add(MovementActionArbiter26.Channel.ROTATION);
        }
        if (channels.isEmpty()) {
            engine.commit(decision, false);
            return;
        }
        pending = decision;
        pendingChannels = Set.copyOf(channels);
        preparedTick = player.tickCount;
        preparedVelocity = velocity;
        preparedPitch = player.getXRot();
        arbiter.submit(OWNER, PRIORITY, pendingChannels);
    }

    public boolean execute(
            Minecraft client,
            MovementActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        ElytraControlDecisionEngine26.Decision decision = pending;
        Set<MovementActionArbiter26.Channel> channels = pendingChannels;
        pending = null;
        pendingChannels = Set.of();
        if (decision == null
                || !arbiter.ownsAll(OWNER, channels)
                || !preparedStillValid(client)) {
            engine.commit(decision, false);
            clearPreparedMetadata();
            lastExecutionApplied = false;
            lastVelocityApplied = false;
            return false;
        }

        Vec3 current = client.player.getDeltaMovement();
        boolean velocityApplied =
                decision.applyHorizontal() || decision.applyVertical();
        if (velocityApplied) {
            double nextX = decision.applyHorizontal()
                    ? decision.nextVelocityX()
                    : current.x;
            double nextY = decision.applyVertical()
                    ? decision.nextVelocityY()
                    : current.y;
            double nextZ = decision.applyHorizontal()
                    ? decision.nextVelocityZ()
                    : current.z;
            client.player.setDeltaMovement(nextX, nextY, nextZ);
        }
        if (decision.applyPitch()) {
            client.player.setXRot(decision.nextPitchDegrees());
        }
        engine.commit(decision, true);
        clearPreparedMetadata();
        lastExecutionApplied = true;
        lastVelocityApplied = velocityApplied;
        return true;
    }

    /**
     * Returns whether this service applied motion or pitch in the latest
     * execute phase.
     */
    public boolean lastExecutionApplied() {
        return lastExecutionApplied;
    }

    public boolean lastVelocityApplied() {
        return lastVelocityApplied;
    }

    public void release(Minecraft client) {
        clearPrepared();
        lastExecutionApplied = false;
        lastVelocityApplied = false;
        observedPlayer = null;
        engine.reset();
    }

    public Status status() {
        ElytraControlDecisionEngine26.Snapshot snapshot =
                engine.snapshot();
        return new Status(
                snapshot.pitchOwnedLastTick(),
                snapshot.manualPitchSuppressionTicks(),
                lastExecutionApplied
        );
    }

    private boolean preparedStillValid(Minecraft client) {
        if (!sessionReady(client)
                || client.player != observedPlayer
                || client.gui.screen() != null
                || client.player.tickCount != preparedTick
                || !client.player.isFallFlying()
                || client.player.isPassenger()
                || client.player.isInWater()
                || client.player.isInLava()
                || client.player.horizontalCollision
                || preparedVelocity == null) {
            return false;
        }
        Vec3 current = client.player.getDeltaMovement();
        return close(current.x, preparedVelocity.x)
                && close(current.y, preparedVelocity.y)
                && close(current.z, preparedVelocity.z)
                && Math.abs(client.player.getXRot() - preparedPitch)
                <= 1.0E-5F;
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) <= REVALIDATION_EPSILON;
    }

    static ElytraControlDecisionEngine26.Decision suppressPitch(
            ElytraControlDecisionEngine26.Decision decision
    ) {
        Objects.requireNonNull(decision, "decision");
        boolean apply = decision.applyHorizontal()
                || decision.applyVertical();
        return new ElytraControlDecisionEngine26.Decision(
                decision.sequence(),
                apply,
                decision.applyHorizontal(),
                decision.applyVertical(),
                false,
                decision.nextVelocityX(),
                decision.nextVelocityY(),
                decision.nextVelocityZ(),
                decision.nextPitchDegrees(),
                decision.accelerationBudget(),
                decision.safetyScale(),
                decision.manualPitchSuppressionTicks(),
                decision.blockReason()
        );
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
        pendingChannels = Set.of();
        clearPreparedMetadata();
    }

    private void clearPreparedMetadata() {
        preparedTick = Integer.MIN_VALUE;
        preparedVelocity = null;
        preparedPitch = 0.0F;
    }

    public record Configuration(
            double cruiseSpeed,
            double acceleration,
            double verticalSpeed,
            double maximumPitchChangePerTick,
            double climbPitchDegrees,
            double descentPitchDegrees,
            double manualPitchOverrideDegrees,
            int manualPitchSuppressionTicks
    ) {
        public Configuration(
                double cruiseSpeed,
                double acceleration,
                double verticalSpeed
        ) {
            this(
                    cruiseSpeed,
                    acceleration,
                    verticalSpeed,
                    2.0,
                    -12.0,
                    22.0,
                    3.0,
                    8
            );
        }

        public Configuration {
            new ElytraControlDecisionEngine26.Configuration(
                    cruiseSpeed,
                    acceleration,
                    verticalSpeed,
                    maximumPitchChangePerTick,
                    climbPitchDegrees,
                    descentPitchDegrees,
                    manualPitchOverrideDegrees,
                    manualPitchSuppressionTicks
            );
        }

        ElytraControlDecisionEngine26.Configuration engine() {
            return new ElytraControlDecisionEngine26.Configuration(
                    cruiseSpeed,
                    acceleration,
                    verticalSpeed,
                    maximumPitchChangePerTick,
                    climbPitchDegrees,
                    descentPitchDegrees,
                    manualPitchOverrideDegrees,
                    manualPitchSuppressionTicks
            );
        }
    }

    public record Status(
            boolean ownsPitch,
            int manualPitchSuppressionTicks,
            boolean lastExecutionApplied
    ) {
    }
}
