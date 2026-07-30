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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
 * Two-phase Auto Crystal and Auto Mine service for Minecraft 26.2.
 *
 * <p>{@link #submit} performs bounded read-only candidate discovery and asks
 * the shared combat arbiter for one complete action bundle. After every combat
 * service has submitted and the runtime resolves the arbiter,
 * {@link #execute} performs only the granted action. This prevents partial
 * hotbar/use/attack mutations.</p>
 *
 * <p>Crystal actions use client-observed server world state as confirmation:
 * a placed crystal must appear at the exact base and a broken entity id must
 * disappear. Auto Mine likewise waits for the target block state to change.
 * All retries and timeouts are bounded.</p>
 */
public final class CombatCrystalMineAutomation26 {
    public static final String CRYSTAL_OWNER = "auto_crystal.action";
    public static final String MINE_OWNER = "auto_mine.action";

    private static final int CRYSTAL_PRIORITY = 90;
    private static final int MINE_PRIORITY = 60;
    private static final int MAXIMUM_PLAYER_SCANS = 48;
    private static final int MAXIMUM_CRYSTAL_SCANS = 16;
    private static final int MAXIMUM_PLACE_BLOCK_SCANS = 256;
    private static final int MAXIMUM_PLACE_EVALUATIONS = 24;
    private static final int MAXIMUM_FRIEND_SCANS = 32;
    private static final int CRYSTAL_CONFIRMATION_TICKS = 8;
    private static final int CRYSTAL_MAXIMUM_RETRIES = 1;
    private static final int MINE_CONFIRMATION_TICKS = 240;
    private static final int MINIMUM_TOOL_DURABILITY = 5;
    private static final double END_CRYSTAL_POWER = 6.0;
    private static final double MINIMUM_CRYSTAL_SELF_DISTANCE = 2.5;
    private static final Set<CombatActionArbiter26.Channel> BREAK_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.HOTBAR
            );
    private static final Set<CombatActionArbiter26.Channel> PLACE_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.USE,
                    CombatActionArbiter26.Channel.HOTBAR
            );
    private static final Set<CombatActionArbiter26.Channel> MINE_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.HOTBAR
            );
    private final ConfirmationState26 crystalConfirmation =
            new ConfirmationState26(
                    CRYSTAL_CONFIRMATION_TICKS,
                    CRYSTAL_MAXIMUM_RETRIES
            );
    private final MiningDecisionEngine26.Confirmation mineConfirmation =
            new MiningDecisionEngine26.Confirmation(
                    MINE_CONFIRMATION_TICKS
            );

    private long logicalTick;
    private int crystalCooldown;
    private int mineCooldown;
    private PendingCrystal pendingCrystal;
    private PreparedCrystal preparedCrystal;
    private PreparedMine preparedMine;
    private long preparedAtTick = -1L;

    private BlockPos miningPosition;
    private Direction miningFace = Direction.UP;
    private BlockState miningInitialState;
    private int miningPreviousSlot = -1;
    private int miningAppliedSlot = -1;
    private boolean mineStopRequested;
    private volatile Configuration configuration = Configuration.defaults();

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
     * Read-only preparation phase. Call exactly once after
     * {@link CombatActionArbiter26#beginTick} and before
     * {@link CombatActionArbiter26#resolve()}.
     */
    public void submit(
            Minecraft client,
            FriendBook friends,
            boolean autoCrystalEnabled,
            boolean autoMineEnabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        logicalTick++;
        crystalCooldown = decrement(crystalCooldown);
        mineCooldown = decrement(mineCooldown);
        preparedCrystal = null;
        preparedMine = null;
        preparedAtTick = logicalTick;

        if (!autoCrystalEnabled) {
            resetCrystal();
        }
        if (!sessionAllowsActions(client)) {
            return;
        }

        if (autoCrystalEnabled) {
            preparedCrystal = prepareCrystal(client, friends);
            if (preparedCrystal != null) {
                arbiter.submit(
                        CRYSTAL_OWNER,
                        CRYSTAL_PRIORITY,
                        preparedCrystal.channels()
                );
                return;
            }
        }

        preparedMine = prepareMine(client, autoMineEnabled);
        if (preparedMine != null) {
            arbiter.submit(MINE_OWNER, MINE_PRIORITY, MINE_CHANNELS);
        }
    }

    /**
     * Mutation phase. Call once after {@link CombatActionArbiter26#resolve()}.
     */
    public void execute(
            Minecraft client,
            FriendBook friends,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        if (preparedAtTick != logicalTick || !sessionAllowsActions(client)) {
            return;
        }
        if (preparedCrystal != null
                && arbiter.ownsAll(
                CRYSTAL_OWNER,
                preparedCrystal.channels()
        )) {
            executeCrystal(client, friends, preparedCrystal);
            preparedCrystal = null;
            preparedMine = null;
            return;
        }
        if (preparedMine != null
                && arbiter.ownsAll(MINE_OWNER, MINE_CHANNELS)) {
            executeMine(client, preparedMine);
        }
        preparedCrystal = null;
        preparedMine = null;
    }

    /**
     * Lifecycle cleanup for disconnect, shutdown, or runtime replacement.
     */
    /**
     * The player AutoCrystal selected on its last prepare pass, or -1. Exposed
     * as an id rather than an entity so the HUD cannot retain a removed player.
     */
    private int lastTargetEntityId = -1;

    public int lastTargetEntityId() {
        return lastTargetEntityId;
    }

    public void release(Minecraft client) {
        lastTargetEntityId = -1;
        resetCrystal();
        stopMine(client, sessionAllowsDestroyPacket(client));
        crystalCooldown = 0;
        mineCooldown = 0;
        preparedCrystal = null;
        preparedMine = null;
        preparedAtTick = -1L;
    }

    public Snapshot snapshot() {
        ConfirmationState26.Snapshot crystal = crystalConfirmation.snapshot();
        return new Snapshot(
                logicalTick,
                crystal.phase().name(),
                crystal.action() == null ? "none" : crystal.action().name(),
                crystal.retries(),
                crystalCooldown,
                miningPosition == null ? "idle" : "mining",
                miningPosition,
                mineCooldown
        );
    }

    private PreparedCrystal prepareCrystal(
            Minecraft client,
            FriendBook friends
    ) {
        Configuration active = configuration;
        if (effectiveHealth(client.player) < active.minimumCrystalHealth()) {
            return null;
        }

        ConfirmationState26.Snapshot state = crystalConfirmation.snapshot();
        if (state.phase() == ConfirmationState26.Phase.CONFIRMED) {
            resetCrystal();
            crystalCooldown = active.crystalActionCooldownTicks();
            return null;
        }
        if (state.phase() == ConfirmationState26.Phase.FAILED) {
            resetCrystal();
            crystalCooldown = active.crystalFailureCooldownTicks();
            return null;
        }
        if (pendingCrystal != null && crystalWorldConfirmed(client, pendingCrystal)) {
            crystalConfirmation.confirm(
                    pendingCrystal.action(),
                    pendingCrystal.key()
            );
            resetCrystal();
            crystalCooldown = active.crystalActionCooldownTicks();
            return null;
        }

        ConfirmationState26.Directive directive =
                crystalConfirmation.advance(logicalTick);
        if (directive == ConfirmationState26.Directive.FAILED) {
            resetCrystal();
            crystalCooldown = active.crystalFailureCooldownTicks();
            return null;
        }
        if (directive == ConfirmationState26.Directive.RETRY) {
            if (pendingCrystal == null
                    || !validatePendingCrystal(client, friends, pendingCrystal)) {
                crystalConfirmation.fail();
                return null;
            }
            return PreparedCrystal.retry(pendingCrystal);
        }
        if (state.phase() == ConfirmationState26.Phase.AWAITING_CONFIRMATION
                || state.phase() == ConfirmationState26.Phase.RETRY_READY
                || crystalCooldown > 0
                || client.player.isUsingItem()) {
            return null;
        }

        Player target = selectTarget(
                client,
                friends,
                active.targetRange()
        );
        lastTargetEntityId = target == null ? -1 : target.getId();
        if (target == null) {
            return null;
        }
        PreparedCrystal breaking = selectBreak(client, friends, target);
        return breaking != null
                ? breaking
                : selectPlace(client, friends, target);
    }

    private PreparedCrystal selectBreak(
            Minecraft client,
            FriendBook friends,
            Player target
    ) {
        List<EndCrystal> crystals = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(EndCrystal.class),
                client.player.getBoundingBox().inflate(
                        configuration.breakRange()
                ),
                crystal -> crystal.isAlive()
                        && client.player.distanceToSqr(crystal)
                        <= configuration.breakRange()
                        * configuration.breakRange()
                        && client.player.distanceTo(crystal)
                        >= MINIMUM_CRYSTAL_SELF_DISTANCE
                        && client.player.hasLineOfSight(crystal),
                crystals,
                MAXIMUM_CRYSTAL_SCANS
        );
        crystals.sort(Comparator
                .comparingDouble((EndCrystal crystal) ->
                        target.distanceToSqr(crystal))
                .thenComparingInt(EndCrystal::getId));

        List<CrystalDecisionEngine26.Candidate> scored = new ArrayList<>();
        List<PendingCrystal> pending = new ArrayList<>();
        for (EndCrystal crystal : crystals) {
            long key = Integer.toUnsignedLong(crystal.getId());
            CrystalDecisionEngine26.Candidate evaluation = evaluateCrystal(
                    client,
                    friends,
                    target,
                    crystal.position(),
                    key,
                    client.player.distanceTo(crystal)
            );
            scored.add(evaluation);
            pending.add(PendingCrystal.breaking(
                    key,
                    crystal.getId(),
                    target.getId(),
                    crystal.position()
            ));
        }
        long selected = CrystalDecisionEngine26.selectBest(
                scored,
                crystalLimits(MAXIMUM_CRYSTAL_SCANS),
                effectiveHealth(client.player)
        );
        return findPending(pending, selected);
    }

    private PreparedCrystal selectPlace(
            Minecraft client,
            FriendBook friends,
            Player target
    ) {
        if (findCrystalHand(client.player) == null) {
            return null;
        }
        List<BlockPos> positions = boundedPlacePositions(client.player);
        List<CrystalDecisionEngine26.Candidate> scored = new ArrayList<>();
        List<PendingCrystal> pending = new ArrayList<>();
        int examined = 0;
        int evaluated = 0;
        for (BlockPos base : positions) {
            if (examined++ >= MAXIMUM_PLACE_BLOCK_SCANS
                    || evaluated >= MAXIMUM_PLACE_EVALUATIONS) {
                break;
            }
            if (!validCrystalBase(client, base)) {
                continue;
            }
            Vec3 explosion = Vec3.atCenterOf(base).add(0.0, 1.0, 0.0);
            double distance = client.player.getEyePosition().distanceTo(explosion);
            if (distance > configuration.placeRange()
                    || client.player.position().distanceTo(explosion)
                    < MINIMUM_CRYSTAL_SELF_DISTANCE) {
                continue;
            }
            // The bounded, deterministically sorted evaluation index is
            // collision-free inside this transaction; the exact BlockPos is
            // retained separately for world confirmation and retries.
            long key = evaluated;
            scored.add(evaluateCrystal(
                    client,
                    friends,
                    target,
                    explosion,
                    key,
                    distance
            ));
            pending.add(PendingCrystal.placing(
                    key,
                    base.immutable(),
                    target.getId(),
                    explosion
            ));
            evaluated++;
        }
        long selected = CrystalDecisionEngine26.selectBest(
                scored,
                crystalLimits(MAXIMUM_PLACE_EVALUATIONS),
                effectiveHealth(client.player)
        );
        return findPending(pending, selected);
    }

    private static PreparedCrystal findPending(
            List<PendingCrystal> candidates,
            long selected
    ) {
        if (selected < 0L) {
            return null;
        }
        for (PendingCrystal candidate : candidates) {
            if (candidate.key() == selected) {
                return PreparedCrystal.initial(candidate);
            }
        }
        return null;
    }

    private CrystalDecisionEngine26.Candidate evaluateCrystal(
            Minecraft client,
            FriendBook friends,
            Player target,
            Vec3 explosion,
            long key,
            double distance
    ) {
        double targetDamage = estimateDamage(
                client,
                target,
                explosion,
                false
        );
        double selfDamage = estimateDamage(
                client,
                client.player,
                explosion,
                true
        );
        FriendRisk friendRisk = evaluateFriends(client, friends, explosion);
        return new CrystalDecisionEngine26.Candidate(
                key,
                targetDamage,
                selfDamage,
                friendRisk.maximumDamage(),
                friendRisk.present(),
                friendRisk.lowestHealth(),
                distance,
                true
        );
    }

    private FriendRisk evaluateFriends(
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
        // Never truncate a social safety list. Oversized books fail closed
        // instead of silently ignoring the entries beyond the CPU budget.
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
                    >= END_CRYSTAL_POWER * END_CRYSTAL_POWER * 4.0) {
                continue;
            }
            present = true;
            maximumDamage = Math.max(
                    maximumDamage,
                    estimateDamage(client, player, explosion, true)
            );
            lowestHealth = Math.min(lowestHealth, effectiveHealth(player));
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

    private void executeCrystal(
            Minecraft client,
            FriendBook friends,
            PreparedCrystal prepared
    ) {
        PendingCrystal action = prepared.action();
        if (!validatePendingCrystal(client, friends, action)) {
            if (prepared.retry()) {
                crystalConfirmation.fail();
            }
            return;
        }

        // A crystal action owns ATTACK/HOTBAR as needed, so it can atomically
        // stop and restore a mine session before interacting.
        stopMine(client, true);
        boolean sent = action.action() == ConfirmationState26.Action.BREAK
                ? attackCrystal(client, action.entityId())
                : placeCrystal(client, action.base());
        if (!sent) {
            if (prepared.retry()) {
                crystalConfirmation.fail();
            }
            return;
        }

        if (prepared.retry()) {
            crystalConfirmation.markRetried(logicalTick);
        } else if (crystalConfirmation.begin(
                action.action(),
                action.key(),
                logicalTick
        )) {
            pendingCrystal = action;
        }
        crystalCooldown = configuration.crystalActionCooldownTicks();
    }

    private boolean validatePendingCrystal(
            Minecraft client,
            FriendBook friends,
            PendingCrystal pending
    ) {
        if (!sessionAllowsActions(client)
                || effectiveHealth(client.player)
                < configuration.minimumCrystalHealth()) {
            return false;
        }
        Entity targetEntity = client.level.getEntity(pending.targetEntityId());
        if (!(targetEntity instanceof Player target)
                || !validTarget(
                client,
                friends,
                target,
                configuration.targetRange()
        )) {
            return false;
        }
        Vec3 explosion;
        double distance;
        if (pending.action() == ConfirmationState26.Action.BREAK) {
            Entity entity = client.level.getEntity(pending.entityId());
            if (!(entity instanceof EndCrystal crystal)
                    || !crystal.isAlive()
                    || client.player.distanceToSqr(crystal)
                    > configuration.breakRange()
                    * configuration.breakRange()
                    || !client.player.hasLineOfSight(crystal)) {
                return false;
            }
            explosion = crystal.position();
            distance = client.player.distanceTo(crystal);
        } else {
            if (pending.base() == null
                    || findCrystalHand(client.player) == null
                    || !validCrystalBase(client, pending.base())) {
                return false;
            }
            explosion = Vec3.atCenterOf(pending.base()).add(0.0, 1.0, 0.0);
            distance = client.player.getEyePosition().distanceTo(explosion);
            if (distance > configuration.placeRange()) {
                return false;
            }
        }
        CrystalDecisionEngine26.Candidate current = evaluateCrystal(
                client,
                friends,
                target,
                explosion,
                pending.key(),
                distance
        );
        return CrystalDecisionEngine26.safe(
                current,
                crystalLimits(MAXIMUM_PLACE_EVALUATIONS),
                effectiveHealth(client.player)
        );
    }

    private static boolean attackCrystal(Minecraft client, int entityId) {
        Entity entity = client.level.getEntity(entityId);
        if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) {
            return false;
        }
        client.gameMode.attack(client.player, crystal);
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean placeCrystal(Minecraft client, BlockPos base) {
        CrystalHand crystal = findCrystalHand(client.player);
        if (crystal == null) {
            return false;
        }
        int previousSlot = client.player.getInventory().getSelectedSlot();
        try {
            if (crystal.slot() >= 0 && crystal.slot() != previousSlot) {
                client.player.getInventory().setSelectedSlot(crystal.slot());
            }
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(base).add(0.0, 0.5, 0.0),
                    Direction.UP,
                    base,
                    false
            );
            InteractionResult result = client.gameMode.useItemOn(
                    client.player,
                    crystal.hand(),
                    hit
            );
            if (!result.consumesAction()) {
                return false;
            }
            client.player.swing(crystal.hand());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (client.player != null
                    && client.player.getInventory().getSelectedSlot()
                    != previousSlot) {
                client.player.getInventory().setSelectedSlot(previousSlot);
            }
        }
    }

    private boolean crystalWorldConfirmed(
            Minecraft client,
            PendingCrystal action
    ) {
        if (action.action() == ConfirmationState26.Action.BREAK) {
            Entity observed = client.level.getEntity(action.entityId());
            return !(observed instanceof EndCrystal) || !observed.isAlive();
        }
        Vec3 expected = Vec3.atCenterOf(action.base()).add(0.0, 1.0, 0.0);
        List<EndCrystal> observed = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(EndCrystal.class),
                AABB.ofSize(expected, 2.0, 3.0, 2.0),
                crystal -> crystal.isAlive()
                        && crystal.position().distanceToSqr(expected) <= 2.25,
                observed,
                1
        );
        return !observed.isEmpty();
    }

    private PreparedMine prepareMine(
            Minecraft client,
            boolean enabled
    ) {
        if (miningPosition != null) {
            if (mineStopRequested) {
                return PreparedMine.stop();
            }
            int selectedSlot =
                    client.player.getInventory().getSelectedSlot();
            if (MiningDecisionEngine26.selectionWasReplaced(
                    miningPreviousSlot,
                    miningAppliedSlot,
                    selectedSlot
            )) {
                // The user or another higher-level system selected a slot.
                // Relinquish without restoring over that newer choice.
                miningAppliedSlot = -1;
                miningPreviousSlot = -1;
                mineStopRequested = true;
                return PreparedMine.stop();
            }
            BlockState current = client.level.getBlockState(miningPosition);
            boolean changed = !current.equals(miningInitialState)
                    || current.isAir();
            MiningDecisionEngine26.Confirmation.Result observed =
                    mineConfirmation.observe(
                            Integer.toUnsignedLong(miningPosition.hashCode()),
                            changed,
                            logicalTick
                    );
            if (!enabled
                    || effectiveHealth(client.player)
                    < configuration.minimumMineHealth()
                    || observed
                    == MiningDecisionEngine26.Confirmation.Result.CONFIRMED
                    || observed
                    == MiningDecisionEngine26.Confirmation.Result.FAILED
                    || client.player.getEyePosition().distanceToSqr(
                    Vec3.atCenterOf(miningPosition)
            ) > configuration.mineRange()
                    * configuration.mineRange()) {
                mineStopRequested = true;
                return PreparedMine.stop();
            }
            int tool = selectTool(client, current);
            return PreparedMine.continueMining(
                    miningPosition,
                    miningFace,
                    tool
            );
        }
        if (!enabled
                || mineCooldown > 0
                || effectiveHealth(client.player)
                < configuration.minimumMineHealth()
                || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos position = hit.getBlockPos();
        BlockState state = client.level.getBlockState(position);
        if (!validMineState(client, position, state)
                || client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(position)
        ) > configuration.mineRange() * configuration.mineRange()) {
            return null;
        }
        return PreparedMine.start(
                position.immutable(),
                hit.getDirection(),
                state,
                selectTool(client, state)
        );
    }

    private void executeMine(Minecraft client, PreparedMine prepared) {
        if (prepared.kind() == MineAction.STOP) {
            stopMine(client, true);
            mineCooldown = configuration.mineActionCooldownTicks();
            return;
        }
        BlockState current = client.level.getBlockState(prepared.position());
        if (!validMineState(client, prepared.position(), current)
                || client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(prepared.position())
        ) > configuration.mineRange() * configuration.mineRange()) {
            stopMine(client, true);
            return;
        }
        if (miningPosition == null) {
            miningPreviousSlot =
                    client.player.getInventory().getSelectedSlot();
            miningAppliedSlot = -1;
        }
        if (prepared.toolSlot() >= 0
                && prepared.toolSlot() < 9
                && client.player.getInventory().getSelectedSlot()
                != prepared.toolSlot()) {
            client.player.getInventory().setSelectedSlot(prepared.toolSlot());
            miningAppliedSlot = prepared.toolSlot();
        }

        boolean progressed;
        if (prepared.kind() == MineAction.START) {
            progressed = client.gameMode.startDestroyBlock(
                    prepared.position(),
                    prepared.face()
            );
            if (progressed) {
                miningPosition = prepared.position().immutable();
                miningFace = prepared.face();
                miningInitialState = prepared.initialState();
                mineStopRequested = false;
                mineConfirmation.begin(
                        Integer.toUnsignedLong(miningPosition.hashCode()),
                        logicalTick
                );
            }
        } else {
            progressed = client.gameMode.continueDestroyBlock(
                    prepared.position(),
                    prepared.face()
            );
        }
        if (progressed) {
            client.player.swing(InteractionHand.MAIN_HAND);
        } else if (prepared.kind() == MineAction.START) {
            stopMine(client, true);
        }
    }

    private void stopMine(Minecraft client, boolean sendDestroyStop) {
        if (miningPosition != null
                && sendDestroyStop
                && client != null
                && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (client != null && client.player != null) {
            int restore = MiningDecisionEngine26.restorationSlot(
                    miningPreviousSlot,
                    miningAppliedSlot,
                    client.player.getInventory().getSelectedSlot()
            );
            if (restore >= 0) {
                client.player.getInventory().setSelectedSlot(restore);
            }
        }
        miningPosition = null;
        miningInitialState = null;
        miningFace = Direction.UP;
        miningPreviousSlot = -1;
        miningAppliedSlot = -1;
        mineStopRequested = false;
        mineConfirmation.reset();
    }

    private static int selectTool(Minecraft client, BlockState state) {
        int selected = client.player.getInventory().getSelectedSlot();
        List<MiningDecisionEngine26.ToolCandidate> candidates =
                new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue()
                    : Integer.MAX_VALUE;
            candidates.add(new MiningDecisionEngine26.ToolCandidate(
                    slot,
                    stack.isDamageableItem(),
                    remaining,
                    stack.isCorrectToolForDrops(state),
                    stack.getDestroySpeed(state)
            ));
        }
        return MiningDecisionEngine26.selectBestTool(
                candidates,
                selected,
                MINIMUM_TOOL_DURABILITY
        );
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
            if (friends.findByUuid(player.getUUID()).isPresent()) {
                return true;
            }
            return friends.findByName(player.getName().getString()).isPresent();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static boolean validCrystalBase(
            Minecraft client,
            BlockPos base
    ) {
        BlockState state = client.level.getBlockState(base);
        if (state.getBlock() != Blocks.OBSIDIAN
                && state.getBlock() != Blocks.BEDROCK) {
            return false;
        }
        BlockPos above = base.above();
        if (!client.level.getBlockState(above).isAir()
                || !client.level.getBlockState(above.above()).isAir()) {
            return false;
        }
        List<Entity> occupying = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                new AABB(
                        above.getX(),
                        above.getY(),
                        above.getZ(),
                        above.getX() + 1.0,
                        above.getY() + 2.0,
                        above.getZ() + 1.0
                ).inflate(0.001),
                Entity::isAlive,
                occupying,
                1
        );
        return occupying.isEmpty();
    }

    private static boolean validMineState(
            Minecraft client,
            BlockPos position,
            BlockState state
    ) {
        return state != null
                && !state.isAir()
                && !state.canBeReplaced()
                && state.getDestroySpeed(client.level, position) >= 0.0F;
    }

    private static List<BlockPos> boundedPlacePositions(LocalPlayer player) {
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
        return positions;
    }

    private static CrystalHand findCrystalHand(LocalPlayer player) {
        if (player.getOffhandItem().getItem() == Items.END_CRYSTAL) {
            return new CrystalHand(InteractionHand.OFF_HAND, -1);
        }
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).getItem()
                == Items.END_CRYSTAL) {
            return new CrystalHand(InteractionHand.MAIN_HAND, selected);
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).getItem()
                    == Items.END_CRYSTAL) {
                return new CrystalHand(InteractionHand.MAIN_HAND, slot);
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
        double raw = CrystalDecisionEngine26.rawExplosionDamage(
                distance,
                exposure,
                END_CRYSTAL_POWER
        );
        double scaled = scaleDifficulty(raw, client.level.getDifficulty());
        return applyArmor(
                scaled,
                entity.getArmorValue(),
                entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS)
        );
    }

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
                    HitResult hit = client.level.clip(new ClipContext(
                            new Vec3(x, y, z),
                            explosion,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            entity
                    ));
                    if (hit.getType() == HitResult.Type.MISS) {
                        visible++;
                    }
                    samples++;
                }
            }
        }
        return samples == 0 ? 0.0 : (double) visible / samples;
    }

    private static double interpolate(double minimum, double maximum, int step) {
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

    private static boolean sessionAllowsDestroyPacket(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.gui.screen() == null;
    }

    private CrystalDecisionEngine26.Limits crystalLimits(int scans) {
        Configuration active = configuration;
        return new CrystalDecisionEngine26.Limits(
                scans,
                active.minimumTargetDamage(),
                active.maximumSelfDamage(),
                active.maximumFriendDamage(),
                active.selfSafetyReserve(),
                active.friendSafetyReserve(),
                1.35,
                0.03
        );
    }

    private void resetCrystal() {
        crystalConfirmation.reset();
        pendingCrystal = null;
    }

    private static int decrement(int value) {
        return value > 0 ? value - 1 : 0;
    }

    /**
     * Bounded live configuration suitable for wiring to the 26.2 settings
     * model. Construction rejects values outside server-compatible ranges.
     */
    public record Configuration(
            double targetRange,
            double breakRange,
            double placeRange,
            double mineRange,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            double minimumCrystalHealth,
            double minimumMineHealth,
            int crystalActionCooldownTicks,
            int crystalFailureCooldownTicks,
            int mineActionCooldownTicks
    ) {
        public Configuration {
            requireRange("targetRange", targetRange, 3.0, 16.0);
            requireRange("breakRange", breakRange, 2.0, 6.0);
            requireRange("placeRange", placeRange, 2.0, 6.0);
            requireRange("mineRange", mineRange, 2.0, 6.0);
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
            requireRange(
                    "minimumCrystalHealth",
                    minimumCrystalHealth,
                    1.0,
                    40.0
            );
            requireRange(
                    "minimumMineHealth",
                    minimumMineHealth,
                    1.0,
                    40.0
            );
            requireRange(
                    "crystalActionCooldownTicks",
                    crystalActionCooldownTicks,
                    0,
                    20
            );
            requireRange(
                    "crystalFailureCooldownTicks",
                    crystalFailureCooldownTicks,
                    1,
                    200
            );
            requireRange(
                    "mineActionCooldownTicks",
                    mineActionCooldownTicks,
                    0,
                    20
            );
        }

        public static Configuration defaults() {
            return new Configuration(
                    10.0,
                    4.5,
                    4.5,
                    4.5,
                    5.5,
                    12.0,
                    4.0,
                    6.0,
                    6.0,
                    12.0,
                    8.0,
                    2,
                    40,
                    3
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
            String crystalPhase,
            String crystalAction,
            int crystalRetries,
            int crystalCooldown,
            String minePhase,
            BlockPos mineTarget,
            int mineCooldown
    ) {
    }

    private record CrystalHand(InteractionHand hand, int slot) {
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

    private record PendingCrystal(
            ConfirmationState26.Action action,
            long key,
            int entityId,
            BlockPos base,
            int targetEntityId,
            Vec3 explosion
    ) {
        static PendingCrystal breaking(
                long key,
                int entityId,
                int targetEntityId,
                Vec3 explosion
        ) {
            return new PendingCrystal(
                    ConfirmationState26.Action.BREAK,
                    key,
                    entityId,
                    null,
                    targetEntityId,
                    explosion
            );
        }

        static PendingCrystal placing(
                long key,
                BlockPos base,
                int targetEntityId,
                Vec3 explosion
        ) {
            return new PendingCrystal(
                    ConfirmationState26.Action.PLACE,
                    key,
                    -1,
                    base,
                    targetEntityId,
                    explosion
            );
        }
    }

    private record PreparedCrystal(
            PendingCrystal action,
            boolean retry,
            Set<CombatActionArbiter26.Channel> channels
    ) {
        static PreparedCrystal initial(PendingCrystal action) {
            return new PreparedCrystal(
                    action,
                    false,
                    action.action() == ConfirmationState26.Action.BREAK
                            ? BREAK_CHANNELS
                            : PLACE_CHANNELS
            );
        }

        static PreparedCrystal retry(PendingCrystal action) {
            return new PreparedCrystal(
                    action,
                    true,
                    action.action() == ConfirmationState26.Action.BREAK
                            ? BREAK_CHANNELS
                            : PLACE_CHANNELS
            );
        }
    }

    private enum MineAction {
        START,
        CONTINUE,
        STOP
    }

    private record PreparedMine(
            MineAction kind,
            BlockPos position,
            Direction face,
            BlockState initialState,
            int toolSlot
    ) {
        static PreparedMine start(
                BlockPos position,
                Direction face,
                BlockState initialState,
                int toolSlot
        ) {
            return new PreparedMine(
                    MineAction.START,
                    position,
                    face,
                    initialState,
                    toolSlot
            );
        }

        static PreparedMine continueMining(
                BlockPos position,
                Direction face,
                int toolSlot
        ) {
            return new PreparedMine(
                    MineAction.CONTINUE,
                    position,
                    face,
                    null,
                    toolSlot
            );
        }

        static PreparedMine stop() {
            return new PreparedMine(
                    MineAction.STOP,
                    null,
                    Direction.UP,
                    null,
                    -1
            );
        }
    }
}
