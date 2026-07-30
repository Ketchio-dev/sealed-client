package dev.sealedclient.v26.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Conservative combat attack hooks for the Minecraft 26.2 adapter.
 *
 * <p>The service never attacks while a screen is open, the local player is
 * dead, or the play session is incomplete. Trigger Bot has priority over Kill
 * Aura, and both use the vanilla attack-strength cooldown. Criticals only
 * decorates an attack issued by this service; it never generates attacks by
 * itself.</p>
 */
public final class CombatAttackAutomation26 {
    public static final double DEFAULT_ATTACK_RANGE = 3.0;
    public static final float DEFAULT_COOLDOWN_THRESHOLD = 0.92F;
    public static final int DEFAULT_MINIMUM_ATTACK_TICKS = 1;

    private static final double CRITICAL_RISE = 0.0625;
    private static final double MAX_VERTICAL_MOTION_FOR_PACKET_CRITICAL = 1.0E-4;

    private int lastAttackTick = Integer.MIN_VALUE;

    /**
     * Runs one combat tick with safe player-only targeting.
     *
     * @param friendPolicy called for every remote player before that player can
     *                     be selected; failures are treated as "friend"
     */
    public void tick(
            Minecraft client,
            boolean triggerBotEnabled,
            boolean criticalsEnabled,
            boolean killAuraEnabled,
            FriendPolicy friendPolicy
    ) {
        PreparedAttack prepared = prepare(
                client,
                triggerBotEnabled,
                criticalsEnabled,
                killAuraEnabled,
                friendPolicy,
                TargetMode.PLAYERS,
                DEFAULT_ATTACK_RANGE
        );
        execute(client, prepared);
    }

    /**
     * First half of the arbitration-friendly API. This method only reads game
     * state and never attacks, swings, rotates, changes inventory, or sends a
     * packet. Callers may submit {@link RequestedChannel#ATTACK} to their
     * coordinator when the returned intent requests that channel.
     */
    public PreparedAttack prepare(
            Minecraft client,
            boolean triggerBotEnabled,
            boolean criticalsEnabled,
            boolean killAuraEnabled,
            FriendPolicy friendPolicy
    ) {
        return prepare(
                client,
                triggerBotEnabled,
                criticalsEnabled,
                killAuraEnabled,
                friendPolicy,
                TargetMode.PLAYERS,
                DEFAULT_ATTACK_RANGE,
                DEFAULT_COOLDOWN_THRESHOLD,
                DEFAULT_MINIMUM_ATTACK_TICKS
        );
    }

    /**
     * Extended prepare hook for an explicit non-player targeting mode and a
     * server-compatible attack range.
     */
    public PreparedAttack prepare(
            Minecraft client,
            boolean triggerBotEnabled,
            boolean criticalsEnabled,
            boolean killAuraEnabled,
            FriendPolicy friendPolicy,
            TargetMode targetMode,
            double attackRange
    ) {
        return prepare(
                client,
                triggerBotEnabled,
                criticalsEnabled,
                killAuraEnabled,
                friendPolicy,
                targetMode,
                attackRange,
                DEFAULT_COOLDOWN_THRESHOLD,
                DEFAULT_MINIMUM_ATTACK_TICKS
        );
    }

    /**
     * Fully configurable preparation hook used by the 26.2 setting adapter.
     */
    public PreparedAttack prepare(
            Minecraft client,
            boolean triggerBotEnabled,
            boolean criticalsEnabled,
            boolean killAuraEnabled,
            FriendPolicy friendPolicy,
            TargetMode targetMode,
            double attackRange,
            float cooldownThreshold,
            int minimumAttackTicks
    ) {
        AttackSettings sharedSettings = new AttackSettings(
                attackRange,
                cooldownThreshold,
                minimumAttackTicks
        );
        return prepare(
                client,
                triggerBotEnabled,
                criticalsEnabled,
                killAuraEnabled,
                friendPolicy,
                targetMode,
                sharedSettings,
                sharedSettings
        );
    }

    /**
     * Action-specific preparation hook. Trigger Bot and Kill Aura settings are
     * deliberately separate so a missed or invalid crosshair target cannot
     * leak Trigger Bot range/cooldown values into the Kill Aura fallback.
     */
    public PreparedAttack prepare(
            Minecraft client,
            boolean triggerBotEnabled,
            boolean criticalsEnabled,
            boolean killAuraEnabled,
            FriendPolicy friendPolicy,
            TargetMode targetMode,
            AttackSettings triggerBotSettings,
            AttackSettings killAuraSettings
    ) {
        if (!triggerBotEnabled && !killAuraEnabled) {
            return PreparedAttack.none();
        }
        if (!sessionAllowsCombat(client)) {
            return PreparedAttack.none();
        }

        boolean triggerReady = triggerBotEnabled
                && validSettings(triggerBotSettings)
                && cooldownReady(
                        client.player.getAttackStrengthScale(0.0F),
                        client.player.tickCount,
                        lastAttackTick,
                        triggerBotSettings.minimumAttackTicks(),
                        triggerBotSettings.cooldownThreshold()
                );
        boolean auraReady = killAuraEnabled
                && validSettings(killAuraSettings)
                && cooldownReady(
                        client.player.getAttackStrengthScale(0.0F),
                        client.player.tickCount,
                        lastAttackTick,
                        killAuraSettings.minimumAttackTicks(),
                        killAuraSettings.cooldownThreshold()
                );

        FriendPolicy effectiveFriends = friendPolicy == null
                ? FriendPolicy.NONE
                : friendPolicy;
        TargetMode effectiveMode = targetMode == null
                ? TargetMode.PLAYERS
                : targetMode;

        LivingEntity triggerTarget = null;
        if (triggerReady && client.hitResult instanceof EntityHitResult hit) {
            if (hit.getEntity() instanceof LivingEntity living
                    && canTarget(
                            client.player,
                            living,
                            effectiveFriends,
                            effectiveMode,
                            triggerBotSettings.range()
                    )) {
                triggerTarget = living;
            }
        }
        LivingEntity auraTarget = null;
        if (triggerTarget == null && auraReady) {
            auraTarget = selectAuraTarget(
                    client.player,
                    client.level.entitiesForRendering(),
                    effectiveFriends,
                    effectiveMode,
                    killAuraSettings.range()
            );
        }
        AttackSelection selection = selectAttackSettings(
                triggerBotEnabled,
                triggerTarget != null,
                triggerBotSettings,
                killAuraEnabled,
                auraTarget != null,
                killAuraSettings
        );
        if (!selection.selected()) {
            return PreparedAttack.none();
        }
        LivingEntity target = selection.source() == AttackSource.TRIGGER_BOT
                ? triggerTarget
                : auraTarget;

        return new PreparedAttack(
                target,
                effectiveFriends,
                effectiveMode,
                selection.settings(),
                selection.source(),
                criticalsEnabled,
                client.player.tickCount
        );
    }

    /**
     * Second half of the arbitration-friendly API. Call this only after the
     * caller owns every channel returned by
     * {@link PreparedAttack#requestedChannels()}. The target and all safety
     * conditions are revalidated immediately before mutation.
     *
     * @return true when exactly one attack was issued
     */
    public boolean execute(Minecraft client, PreparedAttack prepared) {
        if (prepared == null
                || !prepared.requested()
                || !sessionAllowsCombat(client)
                || client.player.tickCount != prepared.preparedTick
                || !cooldownReady(
                        client.player.getAttackStrengthScale(0.0F),
                        client.player.tickCount,
                        lastAttackTick,
                        prepared.settings.minimumAttackTicks(),
                        prepared.settings.cooldownThreshold()
                )
                || !canTarget(
                        client.player,
                        prepared.target,
                        prepared.friendPolicy,
                        prepared.targetMode,
                        prepared.settings.range()
                )) {
            return false;
        }

        if (prepared.criticalsEnabled && shouldInjectPacketCritical(client.player, client.level)) {
            sendPacketCritical(client.player);
        }
        client.gameMode.attack(client.player, prepared.target);
        client.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTick = client.player.tickCount;
        return true;
    }

    /**
     * Clears tick-local history after a disconnect or client shutdown.
     */
    public void release() {
        lastAttackTick = Integer.MIN_VALUE;
    }

    private static boolean sessionAllowsCombat(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected()
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isUsingItem()
                && !client.player.isSpectator();
    }

    private static LivingEntity selectAuraTarget(
            LocalPlayer player,
            Iterable<Entity> entities,
            FriendPolicy friendPolicy,
            TargetMode targetMode,
            double attackRange
    ) {
        List<EntityCandidate> candidates = new ArrayList<>();
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            boolean valid = canTarget(player, living, friendPolicy, targetMode, attackRange);
            candidates.add(new EntityCandidate(
                    living,
                    new TargetCandidate(
                            living.getId(),
                            player.distanceToSqr(living),
                            living instanceof Player,
                            valid
                    )
            ));
        }

        int selectedId = selectTargetId(
                candidates.stream().map(EntityCandidate::candidate).toList(),
                attackRange * attackRange
        );
        if (selectedId < 0) {
            return null;
        }
        for (EntityCandidate candidate : candidates) {
            if (candidate.candidate().entityId() == selectedId) {
                return candidate.entity();
            }
        }
        return null;
    }

    private static boolean canTarget(
            LocalPlayer player,
            LivingEntity target,
            FriendPolicy friendPolicy,
            TargetMode targetMode,
            double attackRange
    ) {
        if (target == player
                || target instanceof ArmorStand
                || target.isRemoved()
                || !target.isAlive()
                || target.isDeadOrDying()
                || !target.isAttackable()
                || !target.isPickable()
                || target.isSpectator()
                || player.isAlliedTo(target)
                || !player.canAttack(target)
                || player.distanceToSqr(target) > attackRange * attackRange
                || !player.hasLineOfSight(target)) {
            return false;
        }
        if (target instanceof Player remotePlayer) {
            return !isFriendFailClosed(friendPolicy, remotePlayer);
        }
        return targetMode == TargetMode.ALL_LIVING;
    }

    private static boolean isFriendFailClosed(FriendPolicy friendPolicy, Player player) {
        try {
            return friendPolicy.isFriend(
                    player.getUUID(),
                    player.getGameProfile().name()
            );
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static boolean shouldInjectPacketCritical(LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level) {
        CriticalContext context = new CriticalContext(
                player.onGround(),
                player.getAbilities().flying,
                player.isPassenger(),
                player.isInWater(),
                player.isInLava(),
                player.onClimbable(),
                player.isFallFlying(),
                player.horizontalCollision,
                player.fallDistance,
                player.getDeltaMovement().y
        );
        return criticalPacketAllowed(context)
                && level.noCollision(
                        player,
                        player.getBoundingBox().move(0.0, CRITICAL_RISE, 0.0)
                );
    }

    private static void sendPacketCritical(LocalPlayer player) {
        boolean horizontalCollision = player.horizontalCollision;
        player.connection.send(new ServerboundMovePlayerPacket.Pos(
                player.getX(),
                player.getY() + CRITICAL_RISE,
                player.getZ(),
                false,
                horizontalCollision
        ));
        player.connection.send(new ServerboundMovePlayerPacket.Pos(
                player.getX(),
                player.getY(),
                player.getZ(),
                false,
                horizontalCollision
        ));
    }

    static int selectTargetId(List<TargetCandidate> candidates, double maximumDistanceSquared) {
        if (candidates == null
                || !Double.isFinite(maximumDistanceSquared)
                || maximumDistanceSquared < 0.0) {
            return -1;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(TargetCandidate::valid)
                .filter(candidate -> Double.isFinite(candidate.distanceSquared()))
                .filter(candidate -> candidate.distanceSquared() >= 0.0)
                .filter(candidate -> candidate.distanceSquared() <= maximumDistanceSquared)
                .min(Comparator
                        .comparingDouble(TargetCandidate::distanceSquared)
                        .thenComparing((TargetCandidate candidate) -> !candidate.player())
                        .thenComparingInt(TargetCandidate::entityId))
                .map(TargetCandidate::entityId)
                .orElse(-1);
    }

    static boolean cooldownReady(
            float attackStrengthScale,
            int currentTick,
            int lastAttackTick,
            int minimumTicks,
            float threshold
    ) {
        if (!Float.isFinite(attackStrengthScale)
                || !Float.isFinite(threshold)
                || threshold < 0.0F
                || threshold > 1.0F
                || attackStrengthScale < threshold) {
            return false;
        }
        int safeMinimumTicks = Math.max(0, minimumTicks);
        if (lastAttackTick == Integer.MIN_VALUE || currentTick < lastAttackTick) {
            return true;
        }
        return (long) currentTick - lastAttackTick >= safeMinimumTicks;
    }

    static boolean criticalPacketAllowed(CriticalContext context) {
        return context != null
                && context.onGround()
                && !context.flying()
                && !context.passenger()
                && !context.inWater()
                && !context.inLava()
                && !context.climbing()
                && !context.fallFlying()
                && !context.horizontalCollision()
                && context.fallDistance() == 0.0F
                && Double.isFinite(context.verticalMotion())
                && Math.abs(context.verticalMotion()) <= MAX_VERTICAL_MOTION_FOR_PACKET_CRITICAL;
    }

    static AttackSelection selectAttackSettings(
            boolean triggerBotEnabled,
            boolean triggerTargetAvailable,
            AttackSettings triggerBotSettings,
            boolean killAuraEnabled,
            boolean killAuraTargetAvailable,
            AttackSettings killAuraSettings
    ) {
        if (triggerBotEnabled
                && triggerTargetAvailable
                && validSettings(triggerBotSettings)) {
            return new AttackSelection(
                    AttackSource.TRIGGER_BOT,
                    triggerBotSettings
            );
        }
        if (killAuraEnabled
                && killAuraTargetAvailable
                && validSettings(killAuraSettings)) {
            return new AttackSelection(
                    AttackSource.KILL_AURA,
                    killAuraSettings
            );
        }
        return AttackSelection.NONE;
    }

    private static boolean validRange(double range) {
        return Double.isFinite(range) && range > 0.0 && range <= DEFAULT_ATTACK_RANGE;
    }

    private static boolean validSettings(AttackSettings settings) {
        return settings != null
                && validRange(settings.range())
                && Float.isFinite(settings.cooldownThreshold())
                && settings.cooldownThreshold() >= 0.0F
                && settings.cooldownThreshold() <= 1.0F
                && settings.minimumAttackTicks() >= 0;
    }

    @FunctionalInterface
    public interface FriendPolicy {
        FriendPolicy NONE = (uuid, name) -> false;

        boolean isFriend(UUID uuid, String name);
    }

    public enum TargetMode {
        PLAYERS,
        ALL_LIVING
    }

    public enum AttackSource {
        TRIGGER_BOT,
        KILL_AURA
    }

    public record AttackSettings(
            double range,
            float cooldownThreshold,
            int minimumAttackTicks
    ) {
        public static AttackSettings defaults() {
            return new AttackSettings(
                    DEFAULT_ATTACK_RANGE,
                    DEFAULT_COOLDOWN_THRESHOLD,
                    DEFAULT_MINIMUM_ATTACK_TICKS
            );
        }
    }

    record AttackSelection(AttackSource source, AttackSettings settings) {
        private static final AttackSelection NONE =
                new AttackSelection(null, null);

        boolean selected() {
            return source != null && settings != null;
        }
    }

    record TargetCandidate(
            int entityId,
            double distanceSquared,
            boolean player,
            boolean valid
    ) {
    }

    record CriticalContext(
            boolean onGround,
            boolean flying,
            boolean passenger,
            boolean inWater,
            boolean inLava,
            boolean climbing,
            boolean fallFlying,
            boolean horizontalCollision,
            double fallDistance,
            double verticalMotion
    ) {
    }

    private record EntityCandidate(LivingEntity entity, TargetCandidate candidate) {
    }

    public enum RequestedChannel {
        ATTACK
    }

    /**
     * Immutable, single-tick attack proposal returned by {@link #prepare}.
     * The target object remains private so callers cannot mutate it while
     * arbitrating.
     */
    public static final class PreparedAttack {
        private static final PreparedAttack NONE = new PreparedAttack(
                null,
                FriendPolicy.NONE,
                TargetMode.PLAYERS,
                AttackSettings.defaults(),
                null,
                false,
                Integer.MIN_VALUE
        );

        private final LivingEntity target;
        private final FriendPolicy friendPolicy;
        private final TargetMode targetMode;
        private final AttackSettings settings;
        private final AttackSource source;
        private final boolean criticalsEnabled;
        private final int preparedTick;

        private PreparedAttack(
                LivingEntity target,
                FriendPolicy friendPolicy,
                TargetMode targetMode,
                AttackSettings settings,
                AttackSource source,
                boolean criticalsEnabled,
                int preparedTick
        ) {
            this.target = target;
            this.friendPolicy = friendPolicy;
            this.targetMode = targetMode;
            this.settings = settings;
            this.source = source;
            this.criticalsEnabled = criticalsEnabled;
            this.preparedTick = preparedTick;
        }

        static PreparedAttack none() {
            return NONE;
        }

        public boolean requested() {
            return target != null;
        }

        public boolean requiresAttackChannel() {
            return requested();
        }

        public Set<RequestedChannel> requestedChannels() {
            return requested()
                    ? Set.of(RequestedChannel.ATTACK)
                    : Set.of();
        }

        public int targetEntityId() {
            return target == null ? -1 : target.getId();
        }

        public boolean requestsCriticalPackets() {
            return requested() && criticalsEnabled;
        }

        public AttackSource source() {
            return source;
        }

        public AttackSettings settings() {
            return settings;
        }
    }
}
