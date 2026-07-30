package dev.b2tclient.v26.combat;

import dev.b2tclient.common.social.FriendBook;
import dev.b2tclient.common.social.FriendEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Two-phase Anchor Aura and Bed Aura service for Minecraft 26.2.
 *
 * <p>Candidate discovery is read-only and bounded. One complete
 * ATTACK/USE/HOTBAR/ROTATION bundle is submitted to the shared arbiter, and
 * no slot or world interaction is mutated unless the whole bundle wins.
 * Every place, charge, or explosive use then waits for a matching server
 * world-state transition before continuing. One retry and both confirmation
 * and failure windows are bounded.</p>
 *
 * <p>Dimension handling is fail-closed and position-aware. An anchor is
 * eligible only when {@link EnvironmentAttributes#RESPAWN_ANCHOR_WORKS} is
 * explicitly false, so normal Nether respawn-anchor use is never treated as
 * an explosion. A bed is eligible only when the exact local
 * {@link EnvironmentAttributes#BED_RULE} explicitly explodes.</p>
 */
public final class CombatBedAnchorAutomation26 {
    public static final String ANCHOR_OWNER = "anchor_aura.action";
    public static final String BED_OWNER = "bed_aura.action";

    private static final int ANCHOR_PRIORITY = 88;
    private static final int BED_PRIORITY = 87;
    private static final int MAXIMUM_PLAYER_SCANS = 48;
    private static final int MAXIMUM_FRIEND_SCANS = 32;
    private static final int MAXIMUM_BLOCK_SCANS = 256;
    private static final int MAXIMUM_ACTION_EVALUATIONS = 32;
    private static final int CONFIRMATION_TICKS = 10;
    private static final int MAXIMUM_RETRIES = 1;
    private static final double BAD_RESPAWN_EXPLOSION_POWER = 5.0;
    private static final Set<CombatActionArbiter26.Channel> ACTION_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.USE,
                    CombatActionArbiter26.Channel.HOTBAR,
                    CombatActionArbiter26.Channel.ROTATION
            );

    private final BlockActionConfirmation26 confirmation =
            new BlockActionConfirmation26(
                    CONFIRMATION_TICKS,
                    MAXIMUM_RETRIES
            );

    private volatile Configuration anchorConfiguration =
            Configuration.defaults();
    private volatile Configuration bedConfiguration =
            Configuration.defaults();
    private long logicalTick;
    private ActionCooldowns cooldowns = ActionCooldowns.zero();
    private PendingAction pending;
    private PreparedAction prepared;
    private long preparedAtTick = -1L;

    public Configuration anchorConfiguration() {
        return anchorConfiguration;
    }

    public void setAnchorConfiguration(Configuration configuration) {
        this.anchorConfiguration = Objects.requireNonNull(
                configuration,
                "anchorConfiguration"
        );
    }

    public Configuration bedConfiguration() {
        return bedConfiguration;
    }

    public void setBedConfiguration(Configuration configuration) {
        this.bedConfiguration = Objects.requireNonNull(
                configuration,
                "bedConfiguration"
        );
    }

    /**
     * Bounded read-only preparation. Call once in the arbiter collection
     * phase.
     */
    public void submit(
            Minecraft client,
            FriendBook friends,
            boolean anchorEnabled,
            boolean bedEnabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        logicalTick++;
        cooldowns = cooldowns.tick();
        prepared = null;
        preparedAtTick = logicalTick;

        if (pending != null
                && (!anchorEnabled
                && pending.kind()
                == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                || !bedEnabled
                && pending.kind()
                == BedAnchorDecisionEngine26.ExplosiveKind.BED)) {
            resetTransaction();
        }
        if (!anchorEnabled && !bedEnabled) {
            resetTransaction();
            return;
        }
        if (!sessionAllowsActions(client)) {
            return;
        }

        prepared = prepare(
                client,
                friends,
                anchorEnabled,
                bedEnabled
        );
        if (prepared != null) {
            arbiter.submit(
                    owner(prepared.action().kind()),
                    priority(prepared.action().kind()),
                    ACTION_CHANNELS
            );
        }
    }

    /**
     * Executes only the fully granted action and immediately revalidates
     * dimension, target, resources, reach, and explosion safety.
     */
    public void execute(
            Minecraft client,
            FriendBook friends,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedAction requested = prepared;
        prepared = null;
        if (requested == null
                || preparedAtTick != logicalTick
                || !sessionAllowsActions(client)
                || !arbiter.ownsAll(
                owner(requested.action().kind()),
                ACTION_CHANNELS
        )) {
            return;
        }
        PendingAction action = requested.action();
        if (!validatePending(client, friends, action)) {
            if (requested.retry()) {
                confirmation.fail();
            }
            return;
        }
        if (!perform(client, action)) {
            if (requested.retry()) {
                confirmation.fail();
            } else {
                setCooldown(
                        action.kind(),
                        configuration(action.kind()).failureCooldownTicks()
                );
            }
            return;
        }

        if (requested.retry()) {
            confirmation.markRetried(logicalTick);
        } else if (confirmation.begin(
                confirmationAction(action.action()),
                action.key(),
                logicalTick
        )) {
            pending = action;
        }
        setCooldown(
                action.kind(),
                configuration(action.kind()).actionCooldownTicks()
        );
    }

    /**
     * Clears pending transactions without sending an unsafe late packet.
     */
    public void release(Minecraft client) {
        resetTransaction();
        cooldowns = ActionCooldowns.zero();
        prepared = null;
        preparedAtTick = -1L;
    }

    public Snapshot snapshot() {
        BlockActionConfirmation26.Snapshot state = confirmation.snapshot();
        return new Snapshot(
                logicalTick,
                state.phase().name(),
                pending == null ? "none" : pending.kind().name(),
                pending == null ? "none" : pending.action().name(),
                pending == null ? null : pending.primary(),
                state.retries(),
                cooldowns.forKind(
                        BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                ),
                cooldowns.forKind(
                        BedAnchorDecisionEngine26.ExplosiveKind.BED
                )
        );
    }

    private PreparedAction prepare(
            Minecraft client,
            FriendBook friends,
            boolean anchorEnabled,
            boolean bedEnabled
    ) {
        BlockActionConfirmation26.Snapshot state = confirmation.snapshot();
        if (state.phase() == BlockActionConfirmation26.Phase.CONFIRMED) {
            BedAnchorDecisionEngine26.ExplosiveKind kind =
                    pending == null ? null : pending.kind();
            resetTransaction();
            if (kind != null) {
                setCooldown(
                        kind,
                        configuration(kind).actionCooldownTicks()
                );
            }
            return null;
        }
        if (state.phase() == BlockActionConfirmation26.Phase.FAILED) {
            BedAnchorDecisionEngine26.ExplosiveKind kind =
                    pending == null ? null : pending.kind();
            resetTransaction();
            if (kind != null) {
                setCooldown(
                        kind,
                        configuration(kind).failureCooldownTicks()
                );
            }
            return null;
        }
        if (pending != null && worldConfirmed(client, pending)) {
            BedAnchorDecisionEngine26.ExplosiveKind kind = pending.kind();
            confirmation.confirm(
                    confirmationAction(pending.action()),
                    pending.key()
            );
            resetTransaction();
            setCooldown(
                    kind,
                    configuration(kind).actionCooldownTicks()
            );
            return null;
        }

        BlockActionConfirmation26.Directive directive =
                confirmation.advance(logicalTick);
        if (directive == BlockActionConfirmation26.Directive.FAILED) {
            BedAnchorDecisionEngine26.ExplosiveKind kind =
                    pending == null ? null : pending.kind();
            resetTransaction();
            if (kind != null) {
                setCooldown(
                        kind,
                        configuration(kind).failureCooldownTicks()
                );
            }
            return null;
        }
        if (directive == BlockActionConfirmation26.Directive.RETRY) {
            if (pending == null
                    || !validatePending(client, friends, pending)) {
                confirmation.fail();
                return null;
            }
            return PreparedAction.retry(pending);
        }
        if (state.phase()
                == BlockActionConfirmation26.Phase.AWAITING_CONFIRMATION
                || state.phase()
                == BlockActionConfirmation26.Phase.RETRY_READY
                || client.player.isUsingItem()) {
            return null;
        }

        double localHealth = effectiveHealth(client.player);
        boolean anchorReady = anchorEnabled
                && cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
        ) == 0
                && localHealth >= anchorConfiguration.minimumHealth();
        boolean bedReady = bedEnabled
                && cooldowns.forKind(
                BedAnchorDecisionEngine26.ExplosiveKind.BED
        ) == 0
                && localHealth >= bedConfiguration.minimumHealth();
        if (!anchorReady && !bedReady) {
            return null;
        }
        Player anchorTarget = anchorReady
                ? selectTarget(
                client,
                friends,
                anchorConfiguration.targetRange()
        )
                : null;
        Player bedTarget = bedReady
                ? selectTarget(
                client,
                friends,
                bedConfiguration.targetRange()
        )
                : null;
        if (anchorTarget == null && bedTarget == null) {
            return null;
        }
        return selectAction(
                client,
                friends,
                anchorTarget,
                bedTarget
        );
    }

    private PreparedAction selectAction(
            Minecraft client,
            FriendBook friends,
            Player anchorTarget,
            Player bedTarget
    ) {
        CandidateBatch batch = new CandidateBatch();

        for (BlockPos position : boundedExistingPositions(client.player)) {
            if (batch.full(
                    BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
            ) && batch.full(
                    BedAnchorDecisionEngine26.ExplosiveKind.BED
            )) {
                break;
            }
            BlockState state = client.level.getBlockState(position);
            if (anchorTarget != null
                    && !batch.full(
                    BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
            )
                    && state.is(Blocks.RESPAWN_ANCHOR)) {
                PendingAction action = existingAnchorAction(
                        client,
                        position,
                        state,
                        anchorTarget,
                        batch.nextKey()
                );
                if (action != null) {
                    addCandidate(
                            client,
                            friends,
                            anchorTarget,
                            action,
                            batch
                    );
                }
            } else if (bedTarget != null
                    && !batch.full(
                    BedAnchorDecisionEngine26.ExplosiveKind.BED
            )
                    && state.getBlock() instanceof BedBlock
                    && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                PendingAction action = existingBedAction(
                        client,
                        position,
                        state,
                        bedTarget,
                        batch.nextKey()
                );
                if (action != null) {
                    addCandidate(
                            client,
                            friends,
                            bedTarget,
                            action,
                            batch
                    );
                }
            }
        }

        if (anchorTarget != null
                && !batch.full(
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
        )) {
            for (BlockPos position
                    : boundedPlacementPositions(anchorTarget)) {
                if (batch.full(
                        BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                )) {
                    break;
                }
                if (completeAnchorResources(client.player)
                        && validAnchorPlacement(
                        client,
                        position,
                        anchorConfiguration
                )) {
                    PendingAction action = PendingAction.anchorPlace(
                            batch.nextKey(),
                            position,
                            anchorTarget.getId(),
                            Vec3.atCenterOf(position)
                    );
                    addCandidate(
                            client,
                            friends,
                            anchorTarget,
                            action,
                            batch
                    );
                }
            }
        }
        if (bedTarget != null
                && !batch.full(
                BedAnchorDecisionEngine26.ExplosiveKind.BED
        )) {
            for (BlockPos position : boundedPlacementPositions(bedTarget)) {
                if (batch.full(
                        BedAnchorDecisionEngine26.ExplosiveKind.BED
                )) {
                    break;
                }
                Direction facing = client.player.getDirection();
                BlockPos head = position.relative(facing);
                if (completeBedResources(client.player)
                        && validBedPlacement(
                        client,
                        position,
                        head,
                        bedConfiguration
                )) {
                    PendingAction action = PendingAction.bedPlace(
                            batch.nextKey(),
                            position,
                            head,
                            facing,
                            bedTarget.getId(),
                            Vec3.atCenterOf(head)
                    );
                    addCandidate(
                            client,
                            friends,
                            bedTarget,
                            action,
                            batch
                    );
                }
            }
        }

        long selected = BedAnchorDecisionEngine26.selectBest(
                batch.scored(),
                policies(),
                effectiveHealth(client.player)
        );
        for (PendingAction action : batch.actions()) {
            if (action.key() == selected) {
                return PreparedAction.initial(action);
            }
        }
        return null;
    }

    private PendingAction existingAnchorAction(
            Minecraft client,
            BlockPos position,
            BlockState state,
            Player target,
            long key
    ) {
        Configuration active = anchorConfiguration;
        if (!client.level.isLoaded(position)
                || !dimensionAllows(
                client,
                BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                position
        )
                || !withinUseRange(client, position, active)
                || !blockReachable(client, position, null)) {
            return null;
        }
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        Vec3 explosion = Vec3.atCenterOf(position);
        if (charge > 0 && findEmptyHand(client.player) != null) {
            return PendingAction.anchorUse(
                    key,
                    position,
                    target.getId(),
                    explosion,
                    charge
            );
        }
        if (charge == 0 && findGlowstone(client.player) != null) {
            return PendingAction.anchorCharge(
                    key,
                    position,
                    target.getId(),
                    explosion,
                    charge
            );
        }
        return null;
    }

    private PendingAction existingBedAction(
            Minecraft client,
            BlockPos head,
            BlockState state,
            Player target,
            long key
    ) {
        Configuration active = bedConfiguration;
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos foot = head.relative(facing.getOpposite());
        if (!client.level.isLoaded(head)
                || !client.level.isLoaded(foot)
                || !bedPairMatches(client, foot, head, facing)
                || !dimensionAllows(
                client,
                BedAnchorDecisionEngine26.ExplosiveKind.BED,
                head
        )
                || !withinUseRange(client, head, active)
                || !blockReachable(client, head, foot)
                || findEmptyHand(client.player) == null) {
            return null;
        }
        return PendingAction.bedUse(
                key,
                foot,
                head,
                facing,
                target.getId(),
                Vec3.atCenterOf(head)
        );
    }

    private void addCandidate(
            Minecraft client,
            FriendBook friends,
            Player target,
            PendingAction action,
            CandidateBatch batch
    ) {
        Configuration active = configuration(action.kind());
        if (batch.full(action.kind())
                || !dimensionAllows(
                client,
                action.kind(),
                action.explosionPosition()
                )
                || !resourcesAvailable(client.player, action)
                || !actionWorldValid(client, action, active)) {
            return;
        }
        batch.add(action, evaluate(
                client,
                friends,
                target,
                action,
                active
        ));
    }

    private BedAnchorDecisionEngine26.Candidate evaluate(
            Minecraft client,
            FriendBook friends,
            Player target,
            PendingAction action,
            Configuration active
    ) {
        FriendRisk friendRisk = evaluateFriends(
                client,
                friends,
                action.explosion()
        );
        return new BedAnchorDecisionEngine26.Candidate(
                action.key(),
                action.kind(),
                action.action(),
                estimateDamage(
                        client,
                        target,
                        action.explosion(),
                        false
                ),
                estimateDamage(
                        client,
                        client.player,
                        action.explosion(),
                        true
                ),
                friendRisk.maximumDamage(),
                friendRisk.present(),
                friendRisk.lowestHealth(),
                client.player.getEyePosition().distanceTo(
                        action.explosion()
                ),
                dimensionAllows(
                        client,
                        action.kind(),
                        action.explosionPosition()
                ),
                resourcesAvailable(client.player, action),
                actionWorldValid(client, action, active)
        );
    }

    private boolean validatePending(
            Minecraft client,
            FriendBook friends,
            PendingAction action
    ) {
        Configuration active = action == null
                ? null
                : configuration(action.kind());
        if (!sessionAllowsActions(client)
                || action == null
                || active == null
                || effectiveHealth(client.player)
                < active.minimumHealth()
                || !dimensionAllows(
                client,
                action.kind(),
                action.explosionPosition()
                )
                || !resourcesAvailable(client.player, action)
                || !actionWorldValid(client, action, active)) {
            return false;
        }
        Entity entity = client.level.getEntity(action.targetEntityId());
        if (!(entity instanceof Player target)
                || !validTarget(
                client,
                friends,
                target,
                active.targetRange()
        )) {
            return false;
        }
        return BedAnchorDecisionEngine26.safe(
                evaluate(client, friends, target, action, active),
                limits(active),
                effectiveHealth(client.player)
        );
    }

    private boolean actionWorldValid(
            Minecraft client,
            PendingAction action,
            Configuration active
    ) {
        if (!client.level.isLoaded(action.primary())
                || action.secondary() != null
                && !client.level.isLoaded(action.secondary())) {
            return false;
        }
        return switch (action.kind()) {
            case ANCHOR -> switch (action.action()) {
                case PLACE -> validAnchorPlacement(
                        client,
                        action.primary(),
                        active
                );
                case CHARGE -> {
                    BlockState state =
                            client.level.getBlockState(action.primary());
                    yield state.is(Blocks.RESPAWN_ANCHOR)
                            && state.getValue(RespawnAnchorBlock.CHARGE)
                            == action.initialCharge()
                            && withinUseRange(
                            client,
                            action.primary(),
                            active
                    )
                            && blockReachable(
                            client,
                            action.primary(),
                            null
                    );
                }
                case USE -> {
                    BlockState state =
                            client.level.getBlockState(action.primary());
                    yield state.is(Blocks.RESPAWN_ANCHOR)
                            && state.getValue(RespawnAnchorBlock.CHARGE) > 0
                            && withinUseRange(
                            client,
                            action.primary(),
                            active
                    )
                            && blockReachable(
                            client,
                            action.primary(),
                            null
                    );
                }
            };
            case BED -> switch (action.action()) {
                case PLACE -> validBedPlacement(
                        client,
                        action.primary(),
                        action.secondary(),
                        active
                ) && client.player.getDirection() == action.direction();
                case USE -> bedPairMatches(
                        client,
                        action.primary(),
                        action.secondary(),
                        action.direction()
                )
                        && withinUseRange(
                        client,
                        action.secondary(),
                        active
                )
                        && blockReachable(
                        client,
                        action.secondary(),
                        action.primary()
                );
                case CHARGE -> false;
            };
        };
    }

    private static boolean resourcesAvailable(
            LocalPlayer player,
            PendingAction action
    ) {
        return switch (action.kind()) {
            case ANCHOR -> switch (action.action()) {
                case PLACE -> completeAnchorResources(player);
                case CHARGE -> findGlowstone(player) != null;
                case USE -> findEmptyHand(player) != null;
            };
            case BED -> switch (action.action()) {
                case PLACE -> completeBedResources(player);
                case USE -> findEmptyHand(player) != null;
                case CHARGE -> false;
            };
        };
    }

    private static boolean completeAnchorResources(LocalPlayer player) {
        return findAnchor(player) != null
                && findGlowstone(player) != null
                && findEmptyHand(player) != null;
    }

    private static boolean completeBedResources(LocalPlayer player) {
        return findBed(player) != null && findEmptyHand(player) != null;
    }

    private boolean perform(Minecraft client, PendingAction action) {
        return switch (action.kind()) {
            case ANCHOR -> switch (action.action()) {
                case PLACE -> interact(
                        client,
                        findAnchor(client.player),
                        topOf(action.primary().below())
                );
                case CHARGE -> interact(
                        client,
                        findGlowstone(client.player),
                        centerOf(action.primary())
                );
                case USE -> interact(
                        client,
                        findEmptyHand(client.player),
                        centerOf(action.primary())
                );
            };
            case BED -> switch (action.action()) {
                case PLACE -> interact(
                        client,
                        findBed(client.player),
                        topOf(action.primary().below())
                );
                case USE -> interact(
                        client,
                        findEmptyHand(client.player),
                        centerOf(action.secondary())
                );
                case CHARGE -> false;
            };
        };
    }

    private static boolean interact(
            Minecraft client,
            HandSelection selection,
            BlockHitResult hit
    ) {
        if (selection == null || hit == null) {
            return false;
        }
        int previousSlot = client.player.getInventory().getSelectedSlot();
        int appliedSlot = -1;
        try {
            if (selection.slot() >= 0
                    && selection.slot() != previousSlot) {
                client.player.getInventory().setSelectedSlot(
                        selection.slot()
                );
                appliedSlot = selection.slot();
            }
            InteractionResult result = client.gameMode.useItemOn(
                    client.player,
                    selection.hand(),
                    hit
            );
            if (!result.consumesAction()) {
                return false;
            }
            client.player.swing(selection.hand());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (client.player != null && appliedSlot >= 0) {
                int restore = BedAnchorDecisionEngine26.restorationSlot(
                        previousSlot,
                        appliedSlot,
                        client.player.getInventory().getSelectedSlot()
                );
                if (restore >= 0) {
                    client.player.getInventory().setSelectedSlot(restore);
                }
            }
        }
    }

    private boolean worldConfirmed(
            Minecraft client,
            PendingAction action
    ) {
        if (!client.level.isLoaded(action.primary())
                || action.secondary() != null
                && !client.level.isLoaded(action.secondary())) {
            return false;
        }
        return switch (action.kind()) {
            case ANCHOR -> switch (action.action()) {
                case PLACE -> client.level.getBlockState(action.primary())
                        .is(Blocks.RESPAWN_ANCHOR);
                case CHARGE -> {
                    BlockState state =
                            client.level.getBlockState(action.primary());
                    yield state.is(Blocks.RESPAWN_ANCHOR)
                            && state.getValue(RespawnAnchorBlock.CHARGE)
                            > action.initialCharge();
                }
                // Explosive use is confirmed only by the server-reflected
                // break/removal outcome, not by the optimistic interaction
                // result returned when the packet is queued.
                case USE -> !client.level.getBlockState(action.primary())
                        .is(Blocks.RESPAWN_ANCHOR);
            };
            case BED -> switch (action.action()) {
                case PLACE -> bedPairMatches(
                        client,
                        action.primary(),
                        action.secondary(),
                        action.direction()
                );
                // Both halves must be removed before an explosive bed use is
                // accepted as a server-confirmed break.
                case USE -> !(client.level
                        .getBlockState(action.primary())
                        .getBlock() instanceof BedBlock)
                        && !(client.level
                        .getBlockState(action.secondary())
                        .getBlock() instanceof BedBlock);
                case CHARGE -> false;
            };
        };
    }

    private static boolean dimensionAllows(
            Minecraft client,
            BedAnchorDecisionEngine26.ExplosiveKind kind,
            BlockPos position
    ) {
        if (client == null
                || client.level == null
                || position == null
                || !client.level.isLoaded(position)) {
            return false;
        }
        try {
            Boolean anchorWorks = null;
            Boolean bedExplodes = null;
            if (kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR) {
                anchorWorks = client.level.environmentAttributes().getValue(
                        EnvironmentAttributes.RESPAWN_ANCHOR_WORKS,
                        position
                );
            } else if (kind
                    == BedAnchorDecisionEngine26.ExplosiveKind.BED) {
                BedRule rule = client.level.environmentAttributes().getValue(
                        EnvironmentAttributes.BED_RULE,
                        position
                );
                bedExplodes = rule == null ? null : rule.explodes();
            }
            return BedAnchorDecisionEngine26.dimensionAllowsExplosion(
                    kind,
                    anchorWorks,
                    bedExplodes
            );
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean withinUseRange(
            Minecraft client,
            BlockPos position,
            Configuration active
    ) {
        return client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(position)
        ) <= active.useRange() * active.useRange();
    }

    private static boolean blockReachable(
            Minecraft client,
            BlockPos primary,
            BlockPos companion
    ) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(primary);
        HitResult result = client.level.clip(new ClipContext(
                eye,
                center,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (!(result instanceof BlockHitResult block)) {
            return false;
        }
        return block.getBlockPos().equals(primary)
                || companion != null
                && block.getBlockPos().equals(companion);
    }

    private boolean validAnchorPlacement(
            Minecraft client,
            BlockPos position,
            Configuration active
    ) {
        if (!client.level.isLoaded(position)
                || !client.level.isLoaded(position.below())
                || !client.level.getWorldBorder().isWithinBounds(position)
                || !client.level.getBlockState(position).canBeReplaced()
                || !client.level.getBlockState(position.below()).isFaceSturdy(
                client.level,
                position.below(),
                Direction.UP
        )
                || client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(position)
        ) > active.placeRange() * active.placeRange()
                || !supportReachable(client, position.below())) {
            return false;
        }
        return unoccupied(client, new AABB(position));
    }

    private boolean validBedPlacement(
            Minecraft client,
            BlockPos foot,
            BlockPos head,
            Configuration active
    ) {
        if (foot == null
                || head == null
                || !client.level.isLoaded(foot)
                || !client.level.isLoaded(head)
                || !client.level.isLoaded(foot.below())
                || !client.level.isLoaded(head.below())
                || !client.level.getWorldBorder().isWithinBounds(foot)
                || !client.level.getWorldBorder().isWithinBounds(head)
                || !client.level.getBlockState(foot).canBeReplaced()
                || !client.level.getBlockState(head).canBeReplaced()
                || !client.level.getBlockState(foot.below()).isFaceSturdy(
                client.level,
                foot.below(),
                Direction.UP
        )
                || !client.level.getBlockState(head.below()).isFaceSturdy(
                client.level,
                head.below(),
                Direction.UP
        )
                || client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(foot)
        ) > active.placeRange() * active.placeRange()
                || !supportReachable(client, foot.below())) {
            return false;
        }
        return unoccupied(client, new AABB(foot))
                && unoccupied(client, new AABB(head));
    }

    private static boolean supportReachable(
            Minecraft client,
            BlockPos support
    ) {
        HitResult result = client.level.clip(new ClipContext(
                client.player.getEyePosition(),
                Vec3.atCenterOf(support).add(0.0, 0.49, 0.0),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return result instanceof BlockHitResult block
                && block.getBlockPos().equals(support);
    }

    private static boolean unoccupied(Minecraft client, AABB box) {
        List<Entity> occupying = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                box.deflate(0.001),
                Entity::isAlive,
                occupying,
                1
        );
        return occupying.isEmpty();
    }

    private static boolean bedPairMatches(
            Minecraft client,
            BlockPos foot,
            BlockPos head,
            Direction facing
    ) {
        if (foot == null || head == null || facing == null) {
            return false;
        }
        BlockState footState = client.level.getBlockState(foot);
        BlockState headState = client.level.getBlockState(head);
        return footState.getBlock() instanceof BedBlock
                && headState.getBlock() instanceof BedBlock
                && footState.getValue(BedBlock.PART) == BedPart.FOOT
                && headState.getValue(BedBlock.PART) == BedPart.HEAD
                && footState.getValue(BedBlock.FACING) == facing
                && headState.getValue(BedBlock.FACING) == facing
                && foot.relative(facing).equals(head);
    }

    private static BlockHitResult topOf(BlockPos support) {
        return new BlockHitResult(
                Vec3.atCenterOf(support).add(0.0, 0.5, 0.0),
                Direction.UP,
                support,
                false
        );
    }

    private static BlockHitResult centerOf(BlockPos position) {
        return new BlockHitResult(
                Vec3.atCenterOf(position),
                Direction.UP,
                position,
                false
        );
    }

    private static HandSelection findAnchor(LocalPlayer player) {
        return findItem(player, stack ->
                stack.getItem() == Items.RESPAWN_ANCHOR);
    }

    private static HandSelection findGlowstone(LocalPlayer player) {
        return findItem(player, stack ->
                stack.getItem() == Items.GLOWSTONE);
    }

    private static HandSelection findBed(LocalPlayer player) {
        return findItem(player, stack ->
                stack.getItem() instanceof BedItem);
    }

    private static HandSelection findEmptyHand(LocalPlayer player) {
        if (player.getOffhandItem().isEmpty()) {
            return new HandSelection(InteractionHand.OFF_HAND, -1);
        }
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).isEmpty()) {
            return new HandSelection(InteractionHand.MAIN_HAND, selected);
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return new HandSelection(InteractionHand.MAIN_HAND, slot);
            }
        }
        return null;
    }

    private static HandSelection findItem(
            LocalPlayer player,
            StackPredicate predicate
    ) {
        if (predicate.test(player.getOffhandItem())) {
            return new HandSelection(InteractionHand.OFF_HAND, -1);
        }
        int selected = player.getInventory().getSelectedSlot();
        if (predicate.test(player.getInventory().getItem(selected))) {
            return new HandSelection(InteractionHand.MAIN_HAND, selected);
        }
        for (int slot = 0; slot < 9; slot++) {
            if (predicate.test(player.getInventory().getItem(slot))) {
                return new HandSelection(InteractionHand.MAIN_HAND, slot);
            }
        }
        return null;
    }

    private static Player selectTarget(
            Minecraft client,
            FriendBook friends,
            double targetRange
    ) {
        List<Player> players = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Player.class),
                client.player.getBoundingBox().inflate(targetRange),
                player -> validTarget(
                        client,
                        friends,
                        player,
                        targetRange
                ),
                players,
                MAXIMUM_PLAYER_SCANS
        );
        return players.stream()
                .min(Comparator
                        .comparingDouble((Player player) ->
                                client.player.distanceToSqr(player))
                        .thenComparingInt(Player::getId))
                .orElse(null);
    }

    private static boolean validTarget(
            Minecraft client,
            FriendBook friends,
            Player player,
            double targetRange
    ) {
        return player != client.player
                && player.isAlive()
                && !player.isDeadOrDying()
                && !player.isSpectator()
                && client.player.distanceToSqr(player)
                <= targetRange * targetRange
                && !isFriend(friends, player);
    }

    private static boolean isFriend(FriendBook friends, Player player) {
        if (friends == null || player == null) {
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

    private static FriendRisk evaluateFriends(
            Minecraft client,
            FriendBook friends,
            Vec3 explosion
    ) {
        if (friends == null) {
            return FriendRisk.blocked();
        }
        List<FriendEntry> entries = friends.all();
        if (entries.isEmpty()) {
            return FriendRisk.none();
        }
        if (entries.size() > MAXIMUM_FRIEND_SCANS
                || client.level.players().size() > MAXIMUM_PLAYER_SCANS) {
            return FriendRisk.blocked();
        }
        double maximumDamage = 0.0;
        double lowestHealth = Double.POSITIVE_INFINITY;
        boolean present = false;
        for (FriendEntry entry : entries) {
            Player player = resolveFriend(client, entry);
            if (player == null
                    || player == client.player
                    || !player.isAlive()
                    || player.position().distanceToSqr(explosion)
                    >= BAD_RESPAWN_EXPLOSION_POWER
                    * BAD_RESPAWN_EXPLOSION_POWER * 4.0) {
                continue;
            }
            present = true;
            maximumDamage = Math.max(
                    maximumDamage,
                    estimateDamage(client, player, explosion, true)
            );
            lowestHealth = Math.min(
                    lowestHealth,
                    effectiveHealth(player)
            );
        }
        return present
                ? new FriendRisk(true, maximumDamage, lowestHealth)
                : FriendRisk.none();
    }

    private static Player resolveFriend(
            Minecraft client,
            FriendEntry entry
    ) {
        if (entry.uuid() != null) {
            Entity byUuid = client.level.getEntity(entry.uuid());
            if (byUuid instanceof Player player) {
                return player;
            }
        }
        for (AbstractClientPlayer player : client.level.players()) {
            if (player.getName().getString().equalsIgnoreCase(entry.name())) {
                return player;
            }
        }
        return null;
    }

    private static double estimateDamage(
            Minecraft client,
            LivingEntity entity,
            Vec3 explosion,
            boolean failSafeExposure
    ) {
        double distance = entity.position().distanceTo(explosion);
        double exposure = failSafeExposure
                ? 1.0
                : sampleExposure(client, entity, explosion);
        double raw = BedAnchorDecisionEngine26.rawExplosionDamage(
                distance,
                exposure,
                BAD_RESPAWN_EXPLOSION_POWER
        );
        double scaled = scaleDifficulty(
                raw,
                client.level.getDifficulty()
        );
        return applyArmor(
                scaled,
                entity.getArmorValue(),
                entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS)
        );
    }

    /**
     * Exactly eight ray samples; no adaptive or unbounded exposure loop.
     */
    private static double sampleExposure(
            Minecraft client,
            LivingEntity entity,
            Vec3 explosion
    ) {
        AABB box = entity.getBoundingBox();
        int visible = 0;
        int samples = 0;
        for (int xi = 0; xi < 2; xi++) {
            double x = interpolate(box.minX, box.maxX, xi);
            for (int yi = 0; yi < 2; yi++) {
                double y = interpolate(box.minY, box.maxY, yi);
                for (int zi = 0; zi < 2; zi++) {
                    double z = interpolate(box.minZ, box.maxZ, zi);
                    HitResult result = client.level.clip(new ClipContext(
                            new Vec3(x, y, z),
                            explosion,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            entity
                    ));
                    if (result.getType() == HitResult.Type.MISS) {
                        visible++;
                    }
                    samples++;
                }
            }
        }
        return samples == 0 ? 0.0 : (double) visible / samples;
    }

    private static double interpolate(
            double minimum,
            double maximum,
            int step
    ) {
        double padding = Math.min(0.05, (maximum - minimum) * 0.25);
        return minimum + padding
                + (maximum - minimum - padding * 2.0) * step;
    }

    private static double scaleDifficulty(
            double damage,
            Difficulty difficulty
    ) {
        if (damage <= 0.0 || difficulty == Difficulty.PEACEFUL) {
            return 0.0;
        }
        return switch (difficulty) {
            case EASY -> Math.min(damage, damage * 0.5 + 1.0);
            case NORMAL -> damage;
            case HARD -> damage * 1.5;
            case PEACEFUL -> 0.0;
        };
    }

    private static double applyArmor(
            double damage,
            double armor,
            double toughness
    ) {
        if (damage <= 0.0) {
            return 0.0;
        }
        double armorTerm = Math.max(
                armor / 5.0,
                armor - damage / (2.0 + toughness / 4.0)
        );
        return damage * (1.0
                - Math.min(20.0, Math.max(0.0, armorTerm)) / 25.0);
    }

    private static double effectiveHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    private static List<BlockPos> boundedExistingPositions(
            LocalPlayer player
    ) {
        BlockPos center = player.blockPosition();
        List<BlockPos> positions = new ArrayList<>(567);
        for (int y = -3; y <= 3; y++) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    positions.add(center.offset(x, y, z));
                }
            }
        }
        Vec3 eye = player.getEyePosition();
        positions.sort(Comparator
                .comparingDouble((BlockPos position) ->
                        eye.distanceToSqr(Vec3.atCenterOf(position)))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(
                positions.subList(
                        0,
                        Math.min(MAXIMUM_BLOCK_SCANS, positions.size())
                )
        );
    }

    private static List<BlockPos> boundedPlacementPositions(Player target) {
        BlockPos center = target.blockPosition();
        List<BlockPos> positions = new ArrayList<>(245);
        for (int y = -2; y <= 2; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    positions.add(center.offset(x, y, z));
                }
            }
        }
        Vec3 targetCenter = target.position();
        positions.sort(Comparator
                .comparingDouble((BlockPos position) ->
                        targetCenter.distanceToSqr(
                                Vec3.atCenterOf(position)
                        ))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(
                positions.subList(
                        0,
                        Math.min(MAXIMUM_BLOCK_SCANS, positions.size())
                )
        );
    }

    private static BedAnchorDecisionEngine26.Limits limits(
            Configuration active
    ) {
        return new BedAnchorDecisionEngine26.Limits(
                MAXIMUM_ACTION_EVALUATIONS,
                active.minimumTargetDamage(),
                active.maximumSelfDamage(),
                active.maximumFriendDamage(),
                active.selfSafetyReserve(),
                active.friendSafetyReserve(),
                1.35,
                0.03,
                0.25
        );
    }

    private BedAnchorDecisionEngine26.Policies policies() {
        return new BedAnchorDecisionEngine26.Policies(
                limits(anchorConfiguration),
                limits(bedConfiguration)
        );
    }

    private Configuration configuration(
            BedAnchorDecisionEngine26.ExplosiveKind kind
    ) {
        return configurationFor(
                kind,
                anchorConfiguration,
                bedConfiguration
        );
    }

    static Configuration configurationFor(
            BedAnchorDecisionEngine26.ExplosiveKind kind,
            Configuration anchor,
            Configuration bed
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(bed, "bed");
        return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                ? anchor
                : bed;
    }

    private void setCooldown(
            BedAnchorDecisionEngine26.ExplosiveKind kind,
            int ticks
    ) {
        cooldowns = cooldowns.with(kind, ticks);
    }

    private static boolean sessionAllowsActions(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static String owner(
            BedAnchorDecisionEngine26.ExplosiveKind kind
    ) {
        return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                ? ANCHOR_OWNER
                : BED_OWNER;
    }

    private static int priority(
            BedAnchorDecisionEngine26.ExplosiveKind kind
    ) {
        return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                ? ANCHOR_PRIORITY
                : BED_PRIORITY;
    }

    private static BlockActionConfirmation26.Action confirmationAction(
            BedAnchorDecisionEngine26.Action action
    ) {
        return switch (action) {
            case PLACE -> BlockActionConfirmation26.Action.PLACE;
            case CHARGE -> BlockActionConfirmation26.Action.CHARGE;
            case USE -> BlockActionConfirmation26.Action.USE;
        };
    }

    private void resetTransaction() {
        confirmation.reset();
        pending = null;
    }

    /**
     * Validated live settings. Ranges are intentionally capped at vanilla
     * interaction reach and bounded target discovery.
     */
    public record Configuration(
            double targetRange,
            double useRange,
            double placeRange,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            double minimumHealth,
            int actionCooldownTicks,
            int failureCooldownTicks
    ) {
        public Configuration {
            requireRange("targetRange", targetRange, 3.0, 16.0);
            requireRange("useRange", useRange, 2.0, 6.0);
            requireRange("placeRange", placeRange, 2.0, 6.0);
            requireRange(
                    "minimumTargetDamage",
                    minimumTargetDamage,
                    0.0,
                    36.0
            );
            requireRange(
                    "maximumSelfDamage",
                    maximumSelfDamage,
                    0.0,
                    36.0
            );
            requireRange(
                    "maximumFriendDamage",
                    maximumFriendDamage,
                    0.0,
                    36.0
            );
            requireRange(
                    "selfSafetyReserve",
                    selfSafetyReserve,
                    0.0,
                    20.0
            );
            requireRange(
                    "friendSafetyReserve",
                    friendSafetyReserve,
                    0.0,
                    20.0
            );
            requireRange("minimumHealth", minimumHealth, 1.0, 40.0);
            requireRange(
                    "actionCooldownTicks",
                    actionCooldownTicks,
                    0,
                    20
            );
            requireRange(
                    "failureCooldownTicks",
                    failureCooldownTicks,
                    1,
                    200
            );
        }

        public static Configuration defaults() {
            return new Configuration(
                    10.0,
                    4.5,
                    4.5,
                    5.0,
                    12.0,
                    4.0,
                    6.0,
                    6.0,
                    12.0,
                    2,
                    40
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
    }

    public record Snapshot(
            long tick,
            String phase,
            String kind,
            String action,
            BlockPos target,
            int retries,
            int anchorCooldown,
            int bedCooldown
    ) {
        public int cooldown() {
            return Math.max(anchorCooldown, bedCooldown);
        }
    }

    record ActionCooldowns(int anchor, int bed) {
        ActionCooldowns {
            if (anchor < 0 || bed < 0) {
                throw new IllegalArgumentException(
                        "Action cooldowns cannot be negative"
                );
            }
        }

        static ActionCooldowns zero() {
            return new ActionCooldowns(0, 0);
        }

        ActionCooldowns tick() {
            return new ActionCooldowns(
                    anchor > 0 ? anchor - 1 : 0,
                    bed > 0 ? bed - 1 : 0
            );
        }

        ActionCooldowns with(
                BedAnchorDecisionEngine26.ExplosiveKind kind,
                int ticks
        ) {
            Objects.requireNonNull(kind, "kind");
            if (ticks < 0) {
                throw new IllegalArgumentException(
                        "Action cooldown cannot be negative"
                );
            }
            return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                    ? new ActionCooldowns(ticks, bed)
                    : new ActionCooldowns(anchor, ticks);
        }

        int forKind(BedAnchorDecisionEngine26.ExplosiveKind kind) {
            Objects.requireNonNull(kind, "kind");
            return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                    ? anchor
                    : bed;
        }
    }

    /**
     * Keeps independent live-evaluation budgets for the two modules while
     * retaining one deterministic key space for shared arbitration.
     */
    private static final class CandidateBatch {
        private final List<PendingAction> actions = new ArrayList<>();
        private final List<BedAnchorDecisionEngine26.Candidate> scored =
                new ArrayList<>();
        private long nextKey;
        private int anchorEvaluations;
        private int bedEvaluations;

        long nextKey() {
            return nextKey++;
        }

        boolean full(BedAnchorDecisionEngine26.ExplosiveKind kind) {
            return kind == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR
                    ? anchorEvaluations >= MAXIMUM_ACTION_EVALUATIONS
                    : bedEvaluations >= MAXIMUM_ACTION_EVALUATIONS;
        }

        void add(
                PendingAction action,
                BedAnchorDecisionEngine26.Candidate candidate
        ) {
            if (action == null
                    || candidate == null
                    || full(action.kind())) {
                return;
            }
            actions.add(action);
            scored.add(candidate);
            if (action.kind()
                    == BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR) {
                anchorEvaluations++;
            } else {
                bedEvaluations++;
            }
        }

        List<PendingAction> actions() {
            return actions;
        }

        List<BedAnchorDecisionEngine26.Candidate> scored() {
            return scored;
        }
    }

    private record HandSelection(InteractionHand hand, int slot) {
    }

    @FunctionalInterface
    private interface StackPredicate {
        boolean test(ItemStack stack);
    }

    private record FriendRisk(
            boolean present,
            double maximumDamage,
            double lowestHealth
    ) {
        static FriendRisk none() {
            return new FriendRisk(false, 0.0, 0.0);
        }

        static FriendRisk blocked() {
            return new FriendRisk(true, Double.MAX_VALUE, 0.0);
        }
    }

    private record PendingAction(
            BedAnchorDecisionEngine26.ExplosiveKind kind,
            BedAnchorDecisionEngine26.Action action,
            long key,
            BlockPos primary,
            BlockPos secondary,
            Direction direction,
            int targetEntityId,
            Vec3 explosion,
            int initialCharge
    ) {
        BlockPos explosionPosition() {
            return kind == BedAnchorDecisionEngine26.ExplosiveKind.BED
                    ? secondary
                    : primary;
        }

        static PendingAction anchorPlace(
                long key,
                BlockPos position,
                int targetEntityId,
                Vec3 explosion
        ) {
            return new PendingAction(
                    BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                    BedAnchorDecisionEngine26.Action.PLACE,
                    key,
                    position.immutable(),
                    null,
                    Direction.NORTH,
                    targetEntityId,
                    explosion,
                    -1
            );
        }

        static PendingAction anchorCharge(
                long key,
                BlockPos position,
                int targetEntityId,
                Vec3 explosion,
                int initialCharge
        ) {
            return new PendingAction(
                    BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                    BedAnchorDecisionEngine26.Action.CHARGE,
                    key,
                    position.immutable(),
                    null,
                    Direction.NORTH,
                    targetEntityId,
                    explosion,
                    initialCharge
            );
        }

        static PendingAction anchorUse(
                long key,
                BlockPos position,
                int targetEntityId,
                Vec3 explosion,
                int initialCharge
        ) {
            return new PendingAction(
                    BedAnchorDecisionEngine26.ExplosiveKind.ANCHOR,
                    BedAnchorDecisionEngine26.Action.USE,
                    key,
                    position.immutable(),
                    null,
                    Direction.NORTH,
                    targetEntityId,
                    explosion,
                    initialCharge
            );
        }

        static PendingAction bedPlace(
                long key,
                BlockPos foot,
                BlockPos head,
                Direction direction,
                int targetEntityId,
                Vec3 explosion
        ) {
            return new PendingAction(
                    BedAnchorDecisionEngine26.ExplosiveKind.BED,
                    BedAnchorDecisionEngine26.Action.PLACE,
                    key,
                    foot.immutable(),
                    head.immutable(),
                    direction,
                    targetEntityId,
                    explosion,
                    -1
            );
        }

        static PendingAction bedUse(
                long key,
                BlockPos foot,
                BlockPos head,
                Direction direction,
                int targetEntityId,
                Vec3 explosion
        ) {
            return new PendingAction(
                    BedAnchorDecisionEngine26.ExplosiveKind.BED,
                    BedAnchorDecisionEngine26.Action.USE,
                    key,
                    foot.immutable(),
                    head.immutable(),
                    direction,
                    targetEntityId,
                    explosion,
                    -1
            );
        }
    }

    private record PreparedAction(PendingAction action, boolean retry) {
        static PreparedAction initial(PendingAction action) {
            return new PreparedAction(action, false);
        }

        static PreparedAction retry(PendingAction action) {
            return new PreparedAction(action, true);
        }
    }
}
