package dev.sealedclient.v26.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Arbitration-friendly Bow Aim implementation for Minecraft 26.2.
 *
 * <p>It operates only while the local player is actively using a bow or
 * crossbow. Target selection is player-only and fail-closed for friend lookup,
 * visibility, invalid motion and range. Rotation is rate/FOV bounded and is
 * suppressed briefly when a manual rotation is observed while drawing.</p>
 */
public final class CombatBowAimAutomation26 {
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    48.0,
                    3.0,
                    3.15,
                    0.05,
                    40.0,
                    70.0,
                    12.0,
                    5,
                    0.75,
                    5
            );

    public static final String OWNER = "bow_aim";
    public static final int PRIORITY = 58;
    public static final Set<CombatActionArbiter26.Channel> CHANNELS =
            Set.of(CombatActionArbiter26.Channel.ROTATION);

    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private PreparedAim pending = PreparedAim.none();
    private int sampledTick = Integer.MIN_VALUE;
    private float sampledYaw;
    private float sampledPitch;
    private boolean sampledRelevant;
    private int suppressionTicks;

    public CombatBowAimAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public CombatBowAimAutomation26(Configuration configuration) {
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
     * Reads the current tick and submits only a ROTATION claim.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            FriendPolicy friendPolicy,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pending = PreparedAim.none();

        LocalPlayer player = client == null ? null : client.player;
        if (player != observedPlayer) {
            resetObservation(player);
        }
        boolean relevantUse = isSupportedUse(player);
        observeRotation(player, relevantUse);
        if (!enabled
                || suppressionTicks > 0
                || !sessionAllowsAim(client)
                || !relevantUse) {
            return;
        }

        ItemStack used = player.getUseItem();
        double projectileSpeed;
        if (used.getItem() instanceof BowItem) {
            int drawTicks = player.getTicksUsingItem();
            if (drawTicks < configuration.minimumBowDrawTicks()) {
                return;
            }
            projectileSpeed = configuration.fullBowSpeed()
                    * BowItem.getPowerForTime(drawTicks);
        } else if (used.getItem() instanceof CrossbowItem) {
            projectileSpeed = configuration.crossbowSpeed();
        } else {
            return;
        }
        if (!Double.isFinite(projectileSpeed) || projectileSpeed <= 0.05) {
            return;
        }

        FriendPolicy effectiveFriends = friendPolicy == null
                ? FriendPolicy.NONE
                : friendPolicy;
        List<BowAimDecisionEngine26.Candidate> candidates =
                collectCandidates(client, effectiveFriends);
        BowAimDecisionEngine26.Vector3 origin = vector(player.getEyePosition());
        BowAimDecisionEngine26.Solution solution =
                BowAimDecisionEngine26.select(
                        origin,
                        player.getYRot(),
                        player.getXRot(),
                        projectileSpeed,
                        candidates,
                        configuration.limits()
                ).orElse(null);
        if (solution == null) {
            return;
        }

        pending = new PreparedAim(
                true,
                player.tickCount,
                player.getInventory().getSelectedSlot(),
                used.getItem().getClass(),
                solution,
                effectiveFriends
        );
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    /**
     * Applies a prepared rotation after the arbiter has awarded ROTATION.
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedAim prepared = pending;
        pending = PreparedAim.none();
        if (!prepared.requested()
                || !arbiter.ownsAll(OWNER, CHANNELS)
                || !sessionAllowsAim(client)
                || client.player != observedPlayer
                || client.player.tickCount != prepared.preparedTick()
                || client.player.getInventory().getSelectedSlot()
                != prepared.selectedSlot()
                || !isSupportedUse(client.player)
                || client.player.getUseItem().getItem().getClass()
                != prepared.weaponClass()
                || rotationChangedSincePreparation(
                        client.player,
                        sampledYaw,
                        sampledPitch
                )) {
            return false;
        }

        Entity entity = client.level.getEntity(
                prepared.solution().targetEntityId()
        );
        if (!(entity instanceof Player target)
                || !canTarget(
                        client.player,
                        target,
                        prepared.friendPolicy()
                )) {
            return false;
        }

        float yaw = (float) prepared.solution().appliedYaw();
        float pitch = (float) prepared.solution().appliedPitch();
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        sampledTick = client.player.tickCount;
        sampledYaw = yaw;
        sampledPitch = pitch;
        sampledRelevant = true;
        return true;
    }

    public void release() {
        pending = PreparedAim.none();
        observedPlayer = null;
        sampledTick = Integer.MIN_VALUE;
        sampledRelevant = false;
        suppressionTicks = 0;
    }

    int suppressionTicks() {
        return suppressionTicks;
    }

    private List<BowAimDecisionEngine26.Candidate> collectCandidates(
            Minecraft client,
            FriendPolicy friends
    ) {
        List<BowAimDecisionEngine26.Candidate> candidates = new ArrayList<>();
        for (AbstractClientPlayer target : client.level.players()) {
            if (target == client.player) {
                continue;
            }
            if (candidates.size()
                    >= BowAimDecisionEngine26.MAXIMUM_CANDIDATES) {
                break;
            }
            boolean friend = isFriendFailClosed(friends, target);
            candidates.add(new BowAimDecisionEngine26.Candidate(
                    target.getId(),
                    vector(target.getEyePosition()),
                    vector(target.getDeltaMovement()),
                    client.player.getEyePosition()
                            .distanceToSqr(target.getEyePosition()),
                    friend,
                    client.player.hasLineOfSight(target),
                    target.isAlive() && !target.isDeadOrDying(),
                    target.isSpectator()
            ));
        }
        return candidates;
    }

    private boolean canTarget(
            LocalPlayer player,
            Player target,
            FriendPolicy friends
    ) {
        return target != player
                && target.isAlive()
                && !target.isDeadOrDying()
                && !target.isSpectator()
                && !isFriendFailClosed(friends, target)
                && player.hasLineOfSight(target)
                && player.getEyePosition().distanceToSqr(
                        target.getEyePosition()
                ) <= configuration.range() * configuration.range();
    }

    private static boolean isFriendFailClosed(
            FriendPolicy friends,
            Player target
    ) {
        try {
            return friends.isFriend(
                    target.getUUID(),
                    target.getGameProfile().name()
            );
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private void observeRotation(LocalPlayer player, boolean relevant) {
        if (player == null) {
            return;
        }
        int tick = player.tickCount;
        if (sampledTick != Integer.MIN_VALUE && tick < sampledTick) {
            resetObservation(player);
        }
        if (tick == sampledTick) {
            return;
        }
        if (suppressionTicks > 0) {
            suppressionTicks--;
        }
        if (sampledRelevant
                && relevant
                && sampledTick == tick - 1
                && rotationChangedSincePreparation(
                        player,
                        sampledYaw,
                        sampledPitch
                )) {
            suppressionTicks =
                    configuration.manualOverrideSuppressionTicks();
        }
        sampledTick = tick;
        sampledYaw = player.getYRot();
        sampledPitch = player.getXRot();
        sampledRelevant = relevant;
    }

    private boolean rotationChangedSincePreparation(
            LocalPlayer player,
            float expectedYaw,
            float expectedPitch
    ) {
        return manualOverrideDetected(
                player.getYRot(),
                player.getXRot(),
                expectedYaw,
                expectedPitch,
                configuration.manualOverrideThresholdDegrees()
        );
    }

    static boolean manualOverrideDetected(
            double currentYaw,
            double currentPitch,
            double expectedYaw,
            double expectedPitch,
            double thresholdDegrees
    ) {
        if (!Double.isFinite(currentYaw)
                || !Double.isFinite(currentPitch)
                || !Double.isFinite(expectedYaw)
                || !Double.isFinite(expectedPitch)
                || !Double.isFinite(thresholdDegrees)
                || thresholdDegrees < 0.0) {
            return true;
        }
        double yawDelta = BowAimDecisionEngine26.wrapDegrees(
                currentYaw - expectedYaw
        );
        double pitchDelta = currentPitch - expectedPitch;
        return Math.hypot(yawDelta, pitchDelta)
                > thresholdDegrees;
    }

    private void resetObservation(LocalPlayer player) {
        observedPlayer = player;
        pending = PreparedAim.none();
        suppressionTicks = 0;
        sampledTick = player == null
                ? Integer.MIN_VALUE
                : player.tickCount;
        sampledYaw = player == null ? 0.0F : player.getYRot();
        sampledPitch = player == null ? 0.0F : player.getXRot();
        sampledRelevant = isSupportedUse(player);
    }

    private static boolean sessionAllowsAim(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected()
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static boolean isSupportedUse(LocalPlayer player) {
        if (player == null || !player.isUsingItem()) {
            return false;
        }
        ItemStack used = player.getUseItem();
        return !used.isEmpty()
                && (used.getItem() instanceof BowItem
                || used.getItem() instanceof CrossbowItem);
    }

    private static BowAimDecisionEngine26.Vector3 vector(Vec3 vector) {
        return new BowAimDecisionEngine26.Vector3(
                vector.x,
                vector.y,
                vector.z
        );
    }

    public record Configuration(
            double range,
            double fullBowSpeed,
            double crossbowSpeed,
            double gravity,
            double maximumLeadTicks,
            double fovDegrees,
            double maximumRotationDegreesPerTick,
            int minimumBowDrawTicks,
            double manualOverrideThresholdDegrees,
            int manualOverrideSuppressionTicks
    ) {
        public Configuration {
            requirePositive(range, "range");
            requirePositive(fullBowSpeed, "fullBowSpeed");
            requirePositive(crossbowSpeed, "crossbowSpeed");
            requireNonNegative(gravity, "gravity");
            requirePositive(maximumLeadTicks, "maximumLeadTicks");
            if (maximumLeadTicks > 80.0) {
                throw new IllegalArgumentException(
                        "maximumLeadTicks cannot exceed 80"
                );
            }
            requirePositive(fovDegrees, "fovDegrees");
            if (fovDegrees > 180.0) {
                throw new IllegalArgumentException(
                        "fovDegrees cannot exceed 180"
                );
            }
            requirePositive(
                    maximumRotationDegreesPerTick,
                    "maximumRotationDegreesPerTick"
            );
            if (maximumRotationDegreesPerTick > 180.0) {
                throw new IllegalArgumentException(
                        "maximumRotationDegreesPerTick cannot exceed 180"
                );
            }
            if (minimumBowDrawTicks < 1 || minimumBowDrawTicks > 20) {
                throw new IllegalArgumentException(
                        "minimumBowDrawTicks must be within 1..20"
                );
            }
            requirePositive(
                    manualOverrideThresholdDegrees,
                    "manualOverrideThresholdDegrees"
            );
            if (manualOverrideSuppressionTicks < 0
                    || manualOverrideSuppressionTicks > 40) {
                throw new IllegalArgumentException(
                        "manualOverrideSuppressionTicks must be within 0..40"
                );
            }
        }

        BowAimDecisionEngine26.Limits limits() {
            return new BowAimDecisionEngine26.Limits(
                    range,
                    gravity,
                    maximumLeadTicks,
                    fovDegrees,
                    maximumRotationDegreesPerTick
            );
        }
    }

    public record PreparedAim(
            boolean requested,
            int preparedTick,
            int selectedSlot,
            Class<?> weaponClass,
            BowAimDecisionEngine26.Solution solution,
            FriendPolicy friendPolicy
    ) {
        public PreparedAim {
            if (requested) {
                Objects.requireNonNull(weaponClass, "weaponClass");
                Objects.requireNonNull(solution, "solution");
                Objects.requireNonNull(friendPolicy, "friendPolicy");
            }
        }

        public static PreparedAim none() {
            return new PreparedAim(
                    false,
                    Integer.MIN_VALUE,
                    -1,
                    null,
                    null,
                    null
            );
        }
    }

    @FunctionalInterface
    public interface FriendPolicy {
        FriendPolicy NONE = (uuid, name) -> false;

        boolean isFriend(UUID uuid, String name);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and positive"
            );
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative"
            );
        }
    }
}
