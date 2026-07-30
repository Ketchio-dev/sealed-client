package dev.sealedclient.v26.combat;

import dev.sealedclient.common.social.FriendBook;
import dev.sealedclient.common.social.FriendEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
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
 * Two-phase City Breaker and Piston Crystal implementation for Minecraft
 * 26.2.
 *
 * <p>Planning is read-only and bounded. Mutations happen only after the
 * shared arbiter grants every channel in the requested action bundle. City
 * mining retains an exact hotbar lease across ticks and relinquishes it when
 * the user selects another slot. Piston Crystal claims HOTBAR, USE, ATTACK,
 * and ROTATION atomically for every stage, confirms reflected block/entity
 * state, retries a bounded number of times, and removes its power/piston
 * blocks on completion or abort when the server permits it.</p>
 */
public final class CombatSiegeAutomation26 {
    public static final String CITY_OWNER = "city_breaker.action";
    public static final String PISTON_OWNER = "piston_crystal.action";

    private static final int CITY_PRIORITY = 65;
    private static final int PISTON_PRIORITY = 95;
    private static final int MAXIMUM_TARGET_SCANS = 32;
    private static final int MAXIMUM_CITY_SCANS = 128;
    private static final int MAXIMUM_LAYOUT_SCANS = 128;
    private static final int MAXIMUM_FRIEND_SCANS = 32;
    private static final int MAXIMUM_ENTITY_SCANS = 16;
    private static final double CRYSTAL_POWER = 6.0;
    private static final Set<CombatActionArbiter26.Channel> CITY_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.HOTBAR
            );
    private static final Set<CombatActionArbiter26.Channel> PISTON_CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.HOTBAR,
                    CombatActionArbiter26.Channel.USE,
                    CombatActionArbiter26.Channel.ATTACK,
                    CombatActionArbiter26.Channel.ROTATION
            );

    private long logicalTick;
    private long preparedAtTick = -1L;
    private int cityCooldown;
    private int pistonCooldown;
    private PreparedCity preparedCity;
    private PreparedPiston preparedPiston;

    private CitySession citySession;
    private CityBreakerDecisionEngine26.Confirmation cityConfirmation;
    private final CityBreakerDecisionEngine26.StopLatch cityStopLatch =
            new CityBreakerDecisionEngine26.StopLatch();
    private int cityPreviousSlot = -1;
    private int cityAppliedSlot = -1;

    private PistonRun pistonRun;
    private PistonCrystalDecisionEngine26.Sequence pistonSequence;
    private CleanupSession cleanupSession;
    private boolean pistonSucceeded;
    private volatile Configuration configuration = Configuration.defaults();
    private volatile ModeConfiguration modeConfiguration =
            ModeConfiguration.defaults();

    /**
     * Returns the last legacy flat configuration supplied through
     * {@link #setConfiguration(Configuration)}. It is not a projection of an
     * independently configured {@link ModeConfiguration}, because two target
     * ranges cannot be represented by the legacy type.
     */
    @Deprecated(forRemoval = false)
    public Configuration configuration() {
        return configuration;
    }

    static Set<CombatActionArbiter26.Channel> requiredChannels(
            boolean pistonCrystal
    ) {
        return pistonCrystal ? PISTON_CHANNELS : CITY_CHANNELS;
    }

    static boolean acceptsTransactionCrystal(
            Set<Integer> preexistingEntityIds,
            int entityId,
            double expectedDistanceSquared,
            boolean awaitingConfirmation
    ) {
        return PistonCrystalDecisionEngine26.acceptsPlacedCrystal(
                preexistingEntityIds,
                entityId,
                expectedDistanceSquared,
                awaitingConfirmation
        );
    }

    static PistonCrystalDecisionEngine26.CleanupDirective
    planCleanup(
            boolean ownedBlockPresent,
            boolean timedOut,
            boolean manualOverrideOrUnreachable,
            boolean destroyStarted
    ) {
        return PistonCrystalDecisionEngine26.cleanupDirective(
                ownedBlockPresent,
                timedOut,
                manualOverrideOrUnreachable,
                destroyStarted
        );
    }

    static PistonCrystalDecisionEngine26.PlacementOwnership
    observeCleanupOwnership(
            PistonCrystalDecisionEngine26.PlacementOwnership current,
            boolean placementSent,
            boolean exactBlockReflected
    ) {
        return PistonCrystalDecisionEngine26.observeOwnership(
                current,
                placementSent,
                exactBlockReflected
        );
    }

    static boolean reflectedCityOpening(
            boolean air,
            boolean replaceable
    ) {
        return CityBreakerDecisionEngine26.reflectedOpening(
                air,
                replaceable
        );
    }

    static boolean interactionInRange(
            double distanceSquared,
            double maximumRange
    ) {
        return PistonCrystalDecisionEngine26.withinRange(
                distanceSquared,
                maximumRange
        );
    }

    void requestCityStop() {
        cityStopLatch.request();
    }

    boolean cityStopRequested() {
        return cityStopLatch.requested();
    }

    private void completeCityStop() {
        cityStopLatch.complete();
    }

    @Deprecated(forRemoval = false)
    public void setConfiguration(Configuration configuration) {
        Configuration requested = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        this.configuration = requested;
        this.modeConfiguration = new ModeConfiguration(
                CityConfiguration.from(requested),
                PistonConfiguration.from(requested)
        );
    }

    public CityConfiguration cityConfiguration() {
        return modeConfiguration.city();
    }

    public void setCityConfiguration(
            CityConfiguration cityConfiguration
    ) {
        CityConfiguration requested = Objects.requireNonNull(
                cityConfiguration,
                "cityConfiguration"
        );
        ModeConfiguration current = modeConfiguration;
        modeConfiguration = new ModeConfiguration(
                requested,
                current.piston()
        );
    }

    public PistonConfiguration pistonConfiguration() {
        return modeConfiguration.piston();
    }

    public void setPistonConfiguration(
            PistonConfiguration pistonConfiguration
    ) {
        PistonConfiguration requested = Objects.requireNonNull(
                pistonConfiguration,
                "pistonConfiguration"
        );
        ModeConfiguration current = modeConfiguration;
        modeConfiguration = new ModeConfiguration(
                current.city(),
                requested
        );
    }

    public ModeConfiguration modeConfiguration() {
        return modeConfiguration;
    }

    public void setModeConfiguration(
            ModeConfiguration modeConfiguration
    ) {
        this.modeConfiguration = Objects.requireNonNull(
                modeConfiguration,
                "modeConfiguration"
        );
    }

    /**
     * Read-only collection phase. Call once between arbiter begin and resolve.
     */
    public void submit(
            Minecraft client,
            FriendBook friends,
            boolean cityBreakerEnabled,
            boolean pistonCrystalEnabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        logicalTick++;
        cityCooldown = decrement(cityCooldown);
        pistonCooldown = decrement(pistonCooldown);
        preparedCity = null;
        preparedPiston = null;
        preparedAtTick = logicalTick;

        if (!sessionAllowsActions(client)) {
            return;
        }

        preparedPiston = preparePiston(
                client,
                friends,
                pistonCrystalEnabled
        );
        if (preparedPiston != null) {
            arbiter.submit(
                    PISTON_OWNER,
                    PISTON_PRIORITY,
                    PISTON_CHANNELS
            );
            return;
        }

        // Do not let a fresh city session overlap a piston wait phase. The
        // piston transaction owns the layout until confirmation/cleanup ends.
        if (pistonRun != null || cleanupSession != null) {
            return;
        }
        preparedCity = prepareCity(client, friends, cityBreakerEnabled);
        if (preparedCity != null) {
            arbiter.submit(CITY_OWNER, CITY_PRIORITY, CITY_CHANNELS);
        }
    }

    /**
     * Mutation phase. Call once after the arbiter resolves the tick.
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
        if (preparedPiston != null
                && arbiter.ownsAll(PISTON_OWNER, PISTON_CHANNELS)) {
            if (citySession != null) {
                stopCity(client, true);
            }
            executePiston(client, friends, preparedPiston);
            preparedPiston = null;
            preparedCity = null;
            return;
        }
        if (preparedCity != null
                && arbiter.ownsAll(CITY_OWNER, CITY_CHANNELS)) {
            executeCity(client, friends, preparedCity);
        }
        preparedPiston = null;
        preparedCity = null;
    }

    /**
     * Disconnect/shutdown cleanup. No later slot restoration overwrites a
     * user's selection unless this service still owns the applied slot.
     */
    public void release(Minecraft client) {
        stopCity(client, destroyPacketsAllowed(client));
        stopCleanup(client, destroyPacketsAllowed(client));
        pistonRun = null;
        if (pistonSequence != null) {
            pistonSequence.reset();
        }
        pistonSequence = null;
        cleanupSession = null;
        preparedCity = null;
        preparedPiston = null;
        preparedAtTick = -1L;
        cityCooldown = 0;
        pistonCooldown = 0;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                logicalTick,
                citySession == null ? "idle" : "mining",
                citySession == null ? null : citySession.position(),
                cityConfirmation == null
                        ? 0
                        : cityConfirmation.snapshot().retries(),
                cityCooldown,
                pistonSequence == null
                        ? "idle"
                        : pistonSequence.snapshot().stage().name(),
                cleanupSession == null
                        ? "none"
                        : cleanupSession.kind().name(),
                pistonRun == null ? null : pistonRun.base(),
                pistonCooldown
        );
    }

    private PreparedCity prepareCity(
            Minecraft client,
            FriendBook friends,
            boolean enabled
    ) {
        CityConfiguration active = cityConfiguration();
        if (citySession != null) {
            if (cityStopRequested()) {
                return PreparedCity.stop(false, false);
            }
            int selected = client.player.getInventory().getSelectedSlot();
            if (CityBreakerDecisionEngine26.selectionWasReplaced(
                    cityPreviousSlot,
                    cityAppliedSlot,
                    selected
            )) {
                // Preserve the lease until STOP actually wins arbitration.
                // Restoration will observe the user's newer slot and refrain
                // from overwriting it.
                requestCityStop();
                return PreparedCity.stop(false, false);
            }
            BlockState reflected = client.level.getBlockState(
                    citySession.position()
            );
            boolean changed =
                    reflectedCityOpening(
                            reflected.isAir(),
                            reflected.canBeReplaced()
                    );
            CityBreakerDecisionEngine26.Confirmation.Directive directive =
                    cityConfirmation.observe(
                            citySession.key(),
                            changed,
                            logicalTick
                    );
            if (!enabled
                    || effectiveHealth(client.player)
                    < active.minimumHealth()
                    || !validCityTarget(
                    client,
                    friends,
                    citySession.targetEntityId()
            )) {
                return PreparedCity.stop(false, false);
            }
            return switch (directive) {
                case CONFIRMED -> PreparedCity.stop(true, false);
                case FAILED -> PreparedCity.stop(false, true);
                case RETRY -> PreparedCity.retry(citySession);
                case CONTINUE -> PreparedCity.continueMining(citySession);
                case NONE -> null;
            };
        }
        if (!enabled
                || cityCooldown > 0
                || effectiveHealth(client.player)
                < active.minimumHealth()
                || client.player.isUsingItem()) {
            return null;
        }
        CityPlan plan = selectCityPlan(client, friends);
        return plan == null ? null : PreparedCity.start(plan);
    }

    private void executeCity(
            Minecraft client,
            FriendBook friends,
            PreparedCity prepared
    ) {
        if (prepared.action() == CityAction.STOP) {
            stopCity(client, true);
            CityConfiguration active = cityConfiguration();
            cityCooldown = prepared.failed()
                    ? active.failureCooldownTicks()
                    : active.actionCooldownTicks();
            return;
        }
        CityConfiguration active = cityConfiguration();
        CitySession requested = prepared.session();
        if (requested == null
                || !validateCitySession(client, friends, requested)) {
            stopCity(client, true);
            cityCooldown = active.failureCooldownTicks();
            return;
        }
        BlockState state = client.level.getBlockState(requested.position());
        int tool = selectTool(
                client.player,
                state,
                active.minimumToolDurability()
        );
        if (tool < 0
                || !client.player.getInventory()
                .getItem(tool)
                .isCorrectToolForDrops(state)) {
            stopCity(client, true);
            cityCooldown = active.failureCooldownTicks();
            return;
        }
        if (citySession == null) {
            cityPreviousSlot = client.player.getInventory().getSelectedSlot();
            cityAppliedSlot = -1;
        }
        applyCitySlot(client.player, tool);

        if (prepared.action() == CityAction.RETRY) {
            client.gameMode.stopDestroyBlock();
            boolean restarted = client.gameMode.startDestroyBlock(
                    requested.position(),
                    requested.face()
            );
            if (restarted) {
                client.player.swing(InteractionHand.MAIN_HAND);
                cityConfirmation.markRetried(logicalTick);
            } else {
                cityConfirmation.fail();
                // We already own ATTACK/HOTBAR in this execution phase, so
                // terminate now rather than leaving a FAILED lease behind.
                stopCity(client, true);
                cityCooldown = active.failureCooldownTicks();
            }
            return;
        }

        boolean progressed;
        if (prepared.action() == CityAction.START) {
            progressed = client.gameMode.startDestroyBlock(
                    requested.position(),
                    requested.face()
            );
            if (progressed) {
                citySession = requested;
                cityConfirmation =
                        new CityBreakerDecisionEngine26.Confirmation(
                                active.confirmationTicks(),
                                active.maximumRetries()
                        );
                cityConfirmation.begin(requested.key(), logicalTick);
            }
        } else {
            progressed = client.gameMode.continueDestroyBlock(
                    requested.position(),
                    requested.face()
            );
        }
        if (progressed) {
            client.player.swing(InteractionHand.MAIN_HAND);
        } else if (prepared.action() == CityAction.START) {
            stopCity(client, true);
            cityCooldown = active.failureCooldownTicks();
        }
    }

    private CityPlan selectCityPlan(
            Minecraft client,
            FriendBook friends
    ) {
        CityConfiguration active = cityConfiguration();
        List<Player> targets = boundedTargets(
                client,
                friends,
                active.targetRange()
        );
        List<CityBreakerDecisionEngine26.Candidate> candidates =
                new ArrayList<>();
        List<CityPlan> plans = new ArrayList<>();
        long key = 0L;
        int scanned = 0;
        for (Player target : targets) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (scanned++ >= MAXIMUM_CITY_SCANS) {
                    break;
                }
                BlockPos position = target.blockPosition()
                        .relative(direction)
                        .immutable();
                BlockState state = client.level.getBlockState(position);
                BlockHitResult hit = visibleBlockHit(client, position);
                int tool = selectTool(
                        client.player,
                        state,
                        active.minimumToolDurability()
                );
                float destroySpeed = tool < 0
                        ? -1.0F
                        : client.player.getInventory()
                        .getItem(tool)
                        .getDestroySpeed(state);
                double blockDistance = client.player.getEyePosition()
                        .distanceTo(Vec3.atCenterOf(position));
                boolean breakable = !state.isAir()
                        && !state.canBeReplaced()
                        && state.getDestroySpeed(
                        client.level,
                        position
                ) >= 0.0F
                        && tool >= 0
                        && client.player.getInventory()
                        .getItem(tool)
                        .isCorrectToolForDrops(state);
                CityBreakerDecisionEngine26.Candidate candidate =
                        new CityBreakerDecisionEngine26.Candidate(
                                key,
                                target.getId(),
                                client.player.distanceTo(target),
                                blockDistance,
                                validTarget(
                                        client,
                                        friends,
                                        target,
                                        active.targetRange()
                                ),
                                isFriend(friends, target),
                                client.player.hasLineOfSight(target),
                                hit != null,
                                state.is(Blocks.OBSIDIAN),
                                breakable,
                                tool,
                                destroySpeed
                        );
                candidates.add(candidate);
                plans.add(new CityPlan(
                        key,
                        target.getId(),
                        position,
                        hit == null ? Direction.UP : hit.getDirection(),
                        state,
                        tool
                ));
                key++;
            }
        }
        long selected = CityBreakerDecisionEngine26.selectBest(
                candidates,
                new CityBreakerDecisionEngine26.Limits(
                        MAXIMUM_CITY_SCANS,
                        active.targetRange(),
                        active.mineRange()
                )
        );
        for (CityPlan plan : plans) {
            if (plan.key() == selected) {
                return plan;
            }
        }
        return null;
    }

    private boolean validateCitySession(
            Minecraft client,
            FriendBook friends,
            CitySession session
    ) {
        CityConfiguration active = cityConfiguration();
        if (!validCityTarget(
                client,
                friends,
                session.targetEntityId()
        )) {
            return false;
        }
        Player target = (Player) client.level.getEntity(
                session.targetEntityId()
        );
        BlockState state = client.level.getBlockState(session.position());
        return state.equals(session.initialState())
                && state.is(Blocks.OBSIDIAN)
                && horizontalNeighbor(
                target.blockPosition(),
                session.position()
        )
                && state.getDestroySpeed(
                client.level,
                session.position()
        ) >= 0.0F
                && client.player.getEyePosition().distanceToSqr(
                Vec3.atCenterOf(session.position())
        ) <= square(active.mineRange())
                && visibleBlockHit(client, session.position()) != null;
    }

    private boolean validCityTarget(
            Minecraft client,
            FriendBook friends,
            int entityId
    ) {
        CityConfiguration active = cityConfiguration();
        Entity entity = client.level.getEntity(entityId);
        return entity instanceof Player player
                && validTarget(
                client,
                friends,
                player,
                active.targetRange()
        );
    }

    private void applyCitySlot(LocalPlayer player, int slot) {
        if (slot >= 0
                && slot < 9
                && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            cityAppliedSlot = slot;
        }
    }

    private void stopCity(Minecraft client, boolean sendStop) {
        if (citySession != null
                && sendStop
                && client != null
                && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (client != null && client.player != null) {
            int restore = CityBreakerDecisionEngine26.restorationSlot(
                    cityPreviousSlot,
                    cityAppliedSlot,
                    client.player.getInventory().getSelectedSlot()
            );
            if (restore >= 0) {
                client.player.getInventory().setSelectedSlot(restore);
            }
        }
        citySession = null;
        if (cityConfirmation != null) {
            cityConfirmation.reset();
        }
        cityConfirmation = null;
        cityPreviousSlot = -1;
        cityAppliedSlot = -1;
        completeCityStop();
    }

    private PreparedPiston preparePiston(
            Minecraft client,
            FriendBook friends,
            boolean enabled
    ) {
        PistonConfiguration active = pistonConfiguration();
        if (cleanupSession != null) {
            return prepareCleanup(client);
        }
        if (pistonRun == null) {
            if (!enabled
                    || pistonCooldown > 0
                    || effectiveHealth(client.player)
                    < active.minimumHealth()
                    || client.player.isUsingItem()) {
                return null;
            }
            PistonRun selected = selectPistonPlan(client, friends);
            if (selected == null) {
                return null;
            }
            pistonRun = selected;
            pistonSequence = new PistonCrystalDecisionEngine26.Sequence(
                    active.confirmationTicks(),
                    active.maximumRetries()
            );
            pistonSequence.begin();
            pistonSucceeded = false;
        }

        refreshPistonOwnership(client);
        if (!enabled
                || !validatePistonTarget(client, friends)
                || effectiveHealth(client.player)
                < active.minimumHealth()) {
            beginPistonCleanup(client, false);
            return prepareCleanup(client);
        }

        PistonCrystalDecisionEngine26.Sequence.Stage sequenceStage =
                pistonSequence.snapshot().stage();
        boolean allowCrystalDiscovery = sequenceStage
                == PistonCrystalDecisionEngine26.Sequence.Stage.WAIT_CRYSTAL
                && pistonRun.crystalPlacementSent;
        EndCrystal crystal = findRunCrystal(
                client,
                allowCrystalDiscovery
        );
        if (crystal != null) {
            pistonRun.crystalEntityId = crystal.getId();
            if (sequenceStage
                    == PistonCrystalDecisionEngine26.Sequence.Stage.WAIT_CRYSTAL) {
                pistonRun.crystalConfirmed = true;
            }
        }
        refreshPistonOwnership(client);
        PistonCrystalDecisionEngine26.Sequence.Observation observation =
                observePiston(client, crystal);
        PistonCrystalDecisionEngine26.Sequence.Directive directive =
                pistonSequence.directive(logicalTick, observation);
        if (directive
                == PistonCrystalDecisionEngine26.Sequence.Directive.WAIT
                || directive
                == PistonCrystalDecisionEngine26.Sequence.Directive.NONE) {
            return null;
        }
        if (directive
                == PistonCrystalDecisionEngine26.Sequence.Directive.COMPLETE) {
            beginPistonCleanup(client, true);
            return prepareCleanup(client);
        }
        if (directive
                == PistonCrystalDecisionEngine26.Sequence.Directive.ABORT) {
            beginPistonCleanup(client, false);
            return prepareCleanup(client);
        }

        PistonAction action = actionFor(
                pistonSequence.snapshot().stage()
        );
        if (action == null) {
            beginPistonCleanup(client, false);
            return prepareCleanup(client);
        }
        return new PreparedPiston(
                action,
                directive
                        == PistonCrystalDecisionEngine26.Sequence.Directive.RETRY,
                null
        );
    }

    private void executePiston(
            Minecraft client,
            FriendBook friends,
            PreparedPiston prepared
    ) {
        if (prepared.action().cleanup()) {
            executeCleanup(client, prepared);
            return;
        }
        if (pistonRun == null
                || pistonSequence == null
                || !validatePistonAction(
                client,
                friends,
                prepared.action()
        )) {
            beginPistonCleanup(client, false);
            return;
        }
        boolean sent = switch (prepared.action()) {
            case PLACE_PISTON -> placePiston(client);
            case PLACE_CRYSTAL -> placeCrystal(client);
            case PLACE_POWER -> placePower(client);
            case BREAK_CRYSTAL -> breakCrystal(client);
            case CLEANUP_START,
                 CLEANUP_CONTINUE,
                 CLEANUP_ADVANCE,
                 CLEANUP_ABANDON -> false;
        };
        if (!sent) {
            beginPistonCleanup(client, false);
            return;
        }
        switch (prepared.action()) {
            case PLACE_PISTON -> pistonRun.pistonPlacementSent = true;
            case PLACE_CRYSTAL -> pistonRun.crystalPlacementSent = true;
            case PLACE_POWER -> pistonRun.powerPlacementSent = true;
            case BREAK_CRYSTAL,
                 CLEANUP_START,
                 CLEANUP_CONTINUE,
                 CLEANUP_ADVANCE,
                 CLEANUP_ABANDON -> {
            }
        }
        if (prepared.retry()) {
            pistonSequence.markRetried(logicalTick);
        } else {
            pistonSequence.markActed(logicalTick);
        }
    }

    private PistonRun selectPistonPlan(
            Minecraft client,
            FriendBook friends
    ) {
        PistonConfiguration active = pistonConfiguration();
        int pistonSlot = findHotbarSlot(
                client.player,
                Items.PISTON,
                Items.STICKY_PISTON
        );
        int powerSlot = findHotbarSlot(
                client.player,
                Items.REDSTONE_BLOCK
        );
        CrystalHand crystalHand = findCrystalHand(client.player);
        if (pistonSlot < 0 || powerSlot < 0 || crystalHand == null) {
            return null;
        }
        Set<Integer> preexistingCrystalIds =
                boundedExistingCrystalIds(client);
        if (preexistingCrystalIds == null) {
            return null;
        }

        List<PistonCrystalDecisionEngine26.Layout> layouts =
                new ArrayList<>();
        List<PistonRun> runs = new ArrayList<>();
        long key = 0L;
        int scanned = 0;
        for (Player target : boundedTargets(
                client,
                friends,
                active.targetRange()
        )) {
            for (Direction away : Direction.Plane.HORIZONTAL) {
                if (scanned++ >= MAXIMUM_LAYOUT_SCANS) {
                    break;
                }
                BlockPos base = target.blockPosition()
                        .relative(away)
                        .immutable();
                BlockPos piston = base.relative(away).above().immutable();
                BlockPos power = piston.above().immutable();
                Direction facing = away.getOpposite();
                Vec3 explosion = explosionPosition(base, facing);
                boolean explosionSafe = explosionSafe(
                        client,
                        friends,
                        target,
                        explosion
                );
                double interactionDistance = Math.max(
                        client.player.getEyePosition().distanceTo(
                                Vec3.atCenterOf(base)
                        ),
                        Math.max(
                                client.player.getEyePosition().distanceTo(
                                        Vec3.atCenterOf(piston)
                                ),
                                client.player.getEyePosition().distanceTo(
                                        Vec3.atCenterOf(power)
                                )
                        )
                );
                boolean lineOfSight =
                        visibleBlockHit(client, base) != null
                                && visibleBlockHit(
                                client,
                                piston.below()
                        ) != null;
                PistonCrystalDecisionEngine26.Layout layout =
                        new PistonCrystalDecisionEngine26.Layout(
                                key,
                                target.getId(),
                                cell(base),
                                cell(piston),
                                cell(power),
                                horizontal(facing),
                                client.player.distanceTo(target),
                                interactionDistance,
                                validTarget(
                                        client,
                                        friends,
                                        target,
                                        active.targetRange()
                                ),
                                isFriend(friends, target),
                                lineOfSight,
                                validCrystalBase(client, base),
                                crystalSpaceClear(client, base),
                                replaceableAndEmpty(client, piston),
                                !client.level.getBlockState(
                                        piston.below()
                                ).canBeReplaced(),
                                replaceableAndEmpty(client, power),
                                piston.equals(
                                        base.relative(away).above()
                                ) && facing == away.getOpposite(),
                                explosionSafe
                        );
                layouts.add(layout);
                runs.add(new PistonRun(
                        key,
                        target.getId(),
                        base,
                        piston,
                        power,
                        facing,
                        pistonSlot,
                        powerSlot,
                        crystalHand,
                        preexistingCrystalIds
                ));
                key++;
            }
        }
        long selected = PistonCrystalDecisionEngine26.selectBest(
                layouts,
                new PistonCrystalDecisionEngine26.Limits(
                        MAXIMUM_LAYOUT_SCANS,
                        active.targetRange(),
                        active.placeRange()
                )
        );
        for (PistonRun run : runs) {
            if (run.key == selected) {
                return run;
            }
        }
        return null;
    }

    private boolean validatePistonTarget(
            Minecraft client,
            FriendBook friends
    ) {
        PistonConfiguration active = pistonConfiguration();
        if (pistonRun == null) {
            return false;
        }
        Entity entity = client.level.getEntity(pistonRun.targetEntityId);
        if (!(entity instanceof Player target)
                || !validTarget(
                client,
                friends,
                target,
                active.targetRange()
        )
                || !target.blockPosition().equals(
                pistonRun.base.relative(pistonRun.facing)
        )) {
            return false;
        }
        Vec3 explosion = explosionPosition(
                pistonRun.base,
                pistonRun.facing
        );
        return explosionSafe(client, friends, target, explosion);
    }

    private boolean validatePistonAction(
            Minecraft client,
            FriendBook friends,
            PistonAction action
    ) {
        PistonConfiguration active = pistonConfiguration();
        if (!validatePistonTarget(client, friends)) {
            return false;
        }
        return switch (action) {
            case PLACE_PISTON ->
                    withinPlaceRange(
                            client,
                            Vec3.atCenterOf(pistonRun.piston.below())
                                    .add(0.0, 0.5, 0.0)
                    )
                            &&
                    findHotbarSlot(
                            client.player,
                            Items.PISTON,
                            Items.STICKY_PISTON
                    ) >= 0
                            && visibleBlockHit(
                            client,
                            pistonRun.piston.below()
                    ) != null
                            && replaceableAndEmpty(
                            client,
                            pistonRun.piston
                    )
                            && !client.level.getBlockState(
                            pistonRun.piston.below()
                    ).canBeReplaced();
            case PLACE_CRYSTAL ->
                    withinPlaceRange(
                            client,
                            Vec3.atCenterOf(pistonRun.base)
                                    .add(0.0, 0.5, 0.0)
                    )
                            && correctPiston(client)
                            && findCrystalHand(client.player) != null
                            && visibleBlockHit(
                            client,
                            pistonRun.base
                    ) != null
                            && validCrystalBase(client, pistonRun.base)
                            && crystalSpaceClear(client, pistonRun.base);
            case PLACE_POWER ->
                    withinPlaceRange(
                            client,
                            Vec3.atCenterOf(pistonRun.piston)
                                    .add(0.0, 0.5, 0.0)
                    )
                            && correctPiston(client)
                            && findRunCrystal(client, false) != null
                            && visibleBlockHit(
                            client,
                            pistonRun.piston
                    ) != null
                            && findHotbarSlot(
                            client.player,
                            Items.REDSTONE_BLOCK
                    ) >= 0
                            && replaceableAndEmpty(
                            client,
                            pistonRun.power
                    );
            case BREAK_CRYSTAL -> {
                EndCrystal crystal = findRunCrystal(client, false);
                yield crystal != null
                        && crystal.isAlive()
                        && client.player.distanceToSqr(crystal)
                        <= square(active.breakRange())
                        && client.player.hasLineOfSight(crystal);
            }
            case CLEANUP_START,
                 CLEANUP_CONTINUE,
                 CLEANUP_ADVANCE,
                 CLEANUP_ABANDON -> cleanupSession != null;
        };
    }

    private PistonCrystalDecisionEngine26.Sequence.Observation observePiston(
            Minecraft client,
            EndCrystal crystal
    ) {
        BlockState piston = client.level.getBlockState(pistonRun.piston);
        boolean correct = isPiston(piston)
                && piston.hasProperty(PistonBaseBlock.FACING)
                && piston.getValue(PistonBaseBlock.FACING)
                == pistonRun.facing;
        boolean extended = correct
                && piston.hasProperty(PistonBaseBlock.EXTENDED)
                && piston.getValue(PistonBaseBlock.EXTENDED);
        boolean power = client.level.getBlockState(pistonRun.power)
                .is(Blocks.REDSTONE_BLOCK);
        boolean gone = pistonRun.crystalEntityId >= 0
                && (!(client.level.getEntity(
                pistonRun.crystalEntityId
        ) instanceof EndCrystal observed) || !observed.isAlive());
        return new PistonCrystalDecisionEngine26.Sequence.Observation(
                correct,
                crystal != null && crystal.isAlive(),
                power,
                extended,
                gone
        );
    }

    private void refreshPistonOwnership(Minecraft client) {
        if (pistonRun == null || client == null || client.level == null) {
            return;
        }
        boolean pistonReflected = correctPiston(client);
        pistonRun.pistonOwnership = observeCleanupOwnership(
                pistonRun.pistonOwnership,
                pistonRun.pistonPlacementSent,
                pistonReflected
        );
        boolean powerReflected = client.level.getBlockState(
                pistonRun.power
        ).is(Blocks.REDSTONE_BLOCK);
        pistonRun.powerOwnership = observeCleanupOwnership(
                pistonRun.powerOwnership,
                pistonRun.powerPlacementSent,
                powerReflected
        );
    }

    private boolean placePiston(Minecraft client) {
        int slot = findHotbarSlot(
                client.player,
                Items.PISTON,
                Items.STICKY_PISTON
        );
        if (slot < 0) {
            return false;
        }
        int previousSlot = client.player.getInventory().getSelectedSlot();
        float previousYaw = client.player.getYRot();
        float previousPitch = client.player.getXRot();
        float previousHead = client.player.getYHeadRot();
        Direction serverLook = pistonRun.facing.getOpposite();
        try {
            client.player.getInventory().setSelectedSlot(slot);
            sendRotation(client, serverLook.toYRot(), 0.0F);
            BlockPos support = pistonRun.piston.below();
            return useOn(
                    client,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(support)
                                    .add(0.0, 0.5, 0.0),
                            Direction.UP,
                            support,
                            false
                    )
            );
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            sendRotation(client, previousYaw, previousPitch);
            client.player.setYHeadRot(previousHead);
            restoreTemporarySlot(client.player, previousSlot, slot);
        }
    }

    private boolean placeCrystal(Minecraft client) {
        CrystalHand hand = findCrystalHand(client.player);
        if (hand == null) {
            return false;
        }
        int previousSlot = client.player.getInventory().getSelectedSlot();
        try {
            if (hand.slot() >= 0) {
                client.player.getInventory().setSelectedSlot(hand.slot());
            }
            return useOn(
                    client,
                    hand.hand(),
                    new BlockHitResult(
                            Vec3.atCenterOf(pistonRun.base)
                                    .add(0.0, 0.5, 0.0),
                            Direction.UP,
                            pistonRun.base,
                            false
                    )
            );
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (hand.slot() >= 0) {
                restoreTemporarySlot(
                        client.player,
                        previousSlot,
                        hand.slot()
                );
            }
        }
    }

    private boolean placePower(Minecraft client) {
        int slot = findHotbarSlot(
                client.player,
                Items.REDSTONE_BLOCK
        );
        if (slot < 0) {
            return false;
        }
        int previousSlot = client.player.getInventory().getSelectedSlot();
        try {
            client.player.getInventory().setSelectedSlot(slot);
            return useOn(
                    client,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(pistonRun.piston)
                                    .add(0.0, 0.5, 0.0),
                            Direction.UP,
                            pistonRun.piston,
                            false
                    )
            );
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            restoreTemporarySlot(client.player, previousSlot, slot);
        }
    }

    private boolean breakCrystal(Minecraft client) {
        EndCrystal crystal = findRunCrystal(client, false);
        if (crystal == null || !crystal.isAlive()) {
            return false;
        }
        pistonRun.crystalEntityId = crystal.getId();
        client.gameMode.attack(client.player, crystal);
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean useOn(
            Minecraft client,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        InteractionResult result = client.gameMode.useItemOn(
                client.player,
                hand,
                hit
        );
        if (!result.consumesAction()) {
            return false;
        }
        client.player.swing(hand);
        return true;
    }

    private static void sendRotation(
            Minecraft client,
            float yaw,
            float pitch
    ) {
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.setYHeadRot(yaw);
        client.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                yaw,
                pitch,
                client.player.onGround(),
                client.player.horizontalCollision
        ));
    }

    private void beginPistonCleanup(
            Minecraft client,
            boolean succeeded
    ) {
        PistonConfiguration active = pistonConfiguration();
        pistonSucceeded = succeeded;
        if (pistonSequence != null && !succeeded) {
            pistonSequence.abort();
        }
        if (cleanupSession != null) {
            // Cleanup mutations are executed only by a granted prepared
            // action. An already active cleanup owns this transition.
            return;
        }
        if (pistonRun == null) {
            finishPiston();
            return;
        }
        refreshPistonOwnership(client);
        if (pistonRun.powerOwnership.owned()
                && client.level.getBlockState(pistonRun.power)
                .is(Blocks.REDSTONE_BLOCK)) {
            cleanupSession = CleanupSession.pending(
                    CleanupKind.POWER,
                    pistonRun.power,
                    logicalTick,
                    active.cleanupTimeoutTicks()
            );
            return;
        }
        if (pistonRun.pistonOwnership.owned()
                && isPiston(client.level.getBlockState(
                pistonRun.piston
        ))) {
            cleanupSession = CleanupSession.pending(
                    CleanupKind.PISTON,
                    pistonRun.piston,
                    logicalTick,
                    active.cleanupTimeoutTicks()
            );
            return;
        }
        finishPiston();
    }

    private PreparedPiston prepareCleanup(Minecraft client) {
        if (cleanupSession == null) {
            return null;
        }
        BlockState state = client.level.getBlockState(
                cleanupSession.position()
        );
        boolean exactOwnedBlock = cleanupMatches(
                state,
                cleanupSession.kind()
        );
        if (!exactOwnedBlock && pistonRun != null) {
            if (cleanupSession.kind() == CleanupKind.POWER) {
                pistonRun.powerOwnership = observeCleanupOwnership(
                        pistonRun.powerOwnership,
                        pistonRun.powerPlacementSent,
                        false
                );
            } else {
                pistonRun.pistonOwnership = observeCleanupOwnership(
                        pistonRun.pistonOwnership,
                        pistonRun.pistonPlacementSent,
                        false
                );
            }
        }
        boolean stillOwned = pistonRun != null
                && (cleanupSession.kind() == CleanupKind.POWER
                ? pistonRun.powerOwnership.owned()
                : pistonRun.pistonOwnership.owned());
        boolean manualOverride = cleanupSession.started()
                && CityBreakerDecisionEngine26.selectionWasReplaced(
                cleanupSession.previousSlot(),
                cleanupSession.appliedSlot(),
                client.player.getInventory().getSelectedSlot()
        );
        boolean reachable = withinPlaceRange(
                client,
                Vec3.atCenterOf(cleanupSession.position())
        );
        PistonCrystalDecisionEngine26.CleanupDirective directive =
                planCleanup(
                        exactOwnedBlock && stillOwned,
                        logicalTick >= cleanupSession.deadline(),
                        manualOverride || !reachable,
                        cleanupSession.started()
                );
        PistonAction action = switch (directive) {
            case START -> PistonAction.CLEANUP_START;
            case CONTINUE -> PistonAction.CLEANUP_CONTINUE;
            case ADVANCE -> PistonAction.CLEANUP_ADVANCE;
            case ABANDON -> PistonAction.CLEANUP_ABANDON;
        };
        return new PreparedPiston(action, false, cleanupSession);
    }

    private void executeCleanup(
            Minecraft client,
            PreparedPiston prepared
    ) {
        PistonConfiguration active = pistonConfiguration();
        CleanupSession session = cleanupSession;
        if (session == null
                || prepared.cleanup() == null
                || !session.position().equals(
                prepared.cleanup().position()
        )) {
            return;
        }
        if (prepared.action() == PistonAction.CLEANUP_ADVANCE) {
            advanceCleanup(client);
            return;
        }
        if (prepared.action() == PistonAction.CLEANUP_ABANDON) {
            // stopCleanup's ownership-aware restoration will not overwrite
            // the user's newer selection.
            stopCleanup(client, true);
            finishPiston();
            return;
        }
        BlockState state = client.level.getBlockState(session.position());
        if (!cleanupMatches(state, session.kind())) {
            advanceCleanup(client);
            return;
        }
        int tool = selectTool(
                client.player,
                state,
                active.cleanupMinimumToolDurability()
        );
        if (tool < 0) {
            advanceCleanup(client);
            return;
        }
        int previous = session.started()
                ? session.previousSlot()
                : client.player.getInventory().getSelectedSlot();
        int applied = session.appliedSlot();
        if (client.player.getInventory().getSelectedSlot() != tool) {
            client.player.getInventory().setSelectedSlot(tool);
            applied = tool;
        }
        boolean progressed = prepared.action()
                == PistonAction.CLEANUP_START
                ? client.gameMode.startDestroyBlock(
                session.position(),
                Direction.UP
        )
                : client.gameMode.continueDestroyBlock(
                session.position(),
                Direction.UP
        );
        cleanupSession = new CleanupSession(
                session.kind(),
                session.position(),
                true,
                session.deadline(),
                previous,
                applied
        );
        if (progressed) {
            client.player.swing(InteractionHand.MAIN_HAND);
        } else if (prepared.action() == PistonAction.CLEANUP_START) {
            advanceCleanup(client);
        }
    }

    private void advanceCleanup(Minecraft client) {
        PistonConfiguration active = pistonConfiguration();
        CleanupKind finishedKind = cleanupSession == null
                ? null
                : cleanupSession.kind();
        stopCleanup(client, true);
        refreshPistonOwnership(client);
        if (finishedKind == CleanupKind.POWER
                && pistonRun != null
                && pistonRun.pistonOwnership.owned()
                && isPiston(client.level.getBlockState(
                pistonRun.piston
        ))) {
            cleanupSession = CleanupSession.pending(
                    CleanupKind.PISTON,
                    pistonRun.piston,
                    logicalTick,
                    active.cleanupTimeoutTicks()
            );
            return;
        }
        finishPiston();
    }

    private void stopCleanup(Minecraft client, boolean sendStop) {
        if (cleanupSession == null) {
            return;
        }
        if (sendStop && client != null && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (client != null && client.player != null) {
            int restore = CityBreakerDecisionEngine26.restorationSlot(
                    cleanupSession.previousSlot(),
                    cleanupSession.appliedSlot(),
                    client.player.getInventory().getSelectedSlot()
            );
            if (restore >= 0) {
                client.player.getInventory().setSelectedSlot(restore);
            }
        }
        cleanupSession = null;
    }

    private void finishPiston() {
        PistonConfiguration active = pistonConfiguration();
        boolean succeeded = pistonSucceeded;
        pistonRun = null;
        if (pistonSequence != null) {
            pistonSequence.reset();
        }
        pistonSequence = null;
        cleanupSession = null;
        pistonCooldown = succeeded
                ? active.actionCooldownTicks()
                : active.failureCooldownTicks();
        pistonSucceeded = false;
    }

    private EndCrystal findRunCrystal(
            Minecraft client,
            boolean allowDiscovery
    ) {
        if (pistonRun == null) {
            return null;
        }
        if (pistonRun.crystalEntityId >= 0) {
            Entity exact = client.level.getEntity(
                    pistonRun.crystalEntityId
            );
            if (exact instanceof EndCrystal crystal && crystal.isAlive()) {
                return crystal;
            }
        }
        if (!allowDiscovery) {
            return null;
        }
        Vec3 center = crystalPosition(pistonRun.base);
        List<EndCrystal> crystals = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(EndCrystal.class),
                AABB.ofSize(center, 5.0, 4.0, 5.0),
                crystal -> crystal.isAlive()
                        && acceptsTransactionCrystal(
                        pistonRun.preexistingCrystalIds,
                        crystal.getId(),
                        crystal.position().distanceToSqr(center),
                        true
                ),
                crystals,
                MAXIMUM_ENTITY_SCANS
        );
        return crystals.stream()
                .min(Comparator
                        .comparingDouble((EndCrystal crystal) ->
                                crystal.position().distanceToSqr(center))
                        .thenComparingInt(EndCrystal::getId))
                .orElse(null);
    }

    private boolean explosionSafe(
            Minecraft client,
            FriendBook friends,
            Player target,
            Vec3 explosion
    ) {
        PistonConfiguration active = pistonConfiguration();
        if (friends == null) {
            return false;
        }
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
        if (targetDamage < active.minimumTargetDamage()
                || selfDamage > active.maximumSelfDamage()
                || selfDamage >= effectiveHealth(client.player)
                - active.selfSafetyReserve()) {
            return false;
        }
        List<FriendEntry> entries = friends.all();
        if (entries.size() > MAXIMUM_FRIEND_SCANS
                || client.level.players().size() > MAXIMUM_TARGET_SCANS) {
            return false;
        }
        for (FriendEntry entry : entries) {
            Player friend = resolveFriend(client, entry);
            if (friend == null
                    || friend == client.player
                    || !friend.isAlive()) {
                continue;
            }
            double damage = estimateDamage(
                    client,
                    friend,
                    explosion,
                    true
            );
            if (damage > active.maximumFriendDamage()
                    || damage >= effectiveHealth(friend)
                    - active.friendSafetyReserve()) {
                return false;
            }
        }
        return true;
    }

    private static double estimateDamage(
            Minecraft client,
            LivingEntity entity,
            Vec3 explosion,
            boolean failSafeExposure
    ) {
        double exposure = failSafeExposure
                ? 1.0
                : sampleExposure(client, entity, explosion);
        double raw = CrystalDecisionEngine26.rawExplosionDamage(
                entity.position().distanceTo(explosion),
                exposure,
                CRYSTAL_POWER
        );
        double difficulty = switch (client.level.getDifficulty()) {
            case PEACEFUL -> 0.0;
            case EASY -> Math.min(raw, raw * 0.5 + 1.0);
            case NORMAL -> raw;
            case HARD -> raw * 1.5;
        };
        if (difficulty <= 0.0) {
            return 0.0;
        }
        double armor = entity.getArmorValue();
        double toughness = entity.getAttributeValue(
                Attributes.ARMOR_TOUGHNESS
        );
        double armorTerm = Math.max(
                armor / 5.0,
                armor - difficulty / (2.0 + toughness / 4.0)
        );
        return difficulty * (
                1.0 - Math.min(20.0, Math.max(0.0, armorTerm)) / 25.0
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
            double x = sample(box.minX, box.maxX, xi);
            for (int yi = 0; yi < 2; yi++) {
                double y = sample(box.minY, box.maxY, yi);
                for (int zi = 0; zi < 2; zi++) {
                    double z = sample(box.minZ, box.maxZ, zi);
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

    private static double sample(double minimum, double maximum, int step) {
        double padding = Math.min(0.05, (maximum - minimum) * 0.25);
        return minimum + padding
                + (maximum - minimum - padding * 2.0) * step;
    }

    private static List<Player> boundedTargets(
            Minecraft client,
            FriendBook friends,
            double range
    ) {
        List<Player> targets = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Player.class),
                client.player.getBoundingBox().inflate(range),
                player -> validTarget(
                        client,
                        friends,
                        player,
                        range
                ),
                targets,
                MAXIMUM_TARGET_SCANS
        );
        targets.sort(Comparator
                .comparingDouble((Player player) ->
                        client.player.distanceToSqr(player))
                .thenComparingInt(Player::getId));
        return targets;
    }

    private static boolean validTarget(
            Minecraft client,
            FriendBook friends,
            Player target,
            double range
    ) {
        return target != client.player
                && target.isAlive()
                && !target.isDeadOrDying()
                && !target.isSpectator()
                && client.player.distanceToSqr(target) <= square(range)
                && !isFriend(friends, target);
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
        } catch (RuntimeException ignored) {
            return true;
        }
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
            if (player.getName().getString().equalsIgnoreCase(
                    entry.name()
            )) {
                return player;
            }
        }
        return null;
    }

    private static BlockHitResult visibleBlockHit(
            Minecraft client,
            BlockPos position
    ) {
        HitResult hit = client.level.clip(new ClipContext(
                client.player.getEyePosition(),
                Vec3.atCenterOf(position),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hit instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(position)
                ? blockHit
                : null;
    }

    private boolean withinPlaceRange(
            Minecraft client,
            Vec3 interactionPoint
    ) {
        PistonConfiguration active = pistonConfiguration();
        return interactionInRange(
                client.player.getEyePosition().distanceToSqr(
                        interactionPoint
                ),
                active.placeRange()
        );
    }

    private Set<Integer> boundedExistingCrystalIds(
            Minecraft client
    ) {
        PistonConfiguration active = pistonConfiguration();
        List<EndCrystal> crystals = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(EndCrystal.class),
                client.player.getBoundingBox().inflate(
                        active.targetRange()
                                + active.placeRange()
                ),
                Entity::isAlive,
                crystals,
                MAXIMUM_ENTITY_SCANS + 1
        );
        if (crystals.size() > MAXIMUM_ENTITY_SCANS) {
            return null;
        }
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (EndCrystal crystal : crystals) {
            ids.add(crystal.getId());
        }
        return Set.copyOf(ids);
    }

    private static Vec3 crystalPosition(BlockPos base) {
        return new Vec3(
                base.getX() + 0.5,
                base.getY() + 1.0,
                base.getZ() + 0.5
        );
    }

    private static Vec3 explosionPosition(
            BlockPos base,
            Direction facing
    ) {
        PistonCrystalDecisionEngine26.ExplosionPoint point =
                PistonCrystalDecisionEngine26.explosionPoint(
                        cell(base),
                        horizontal(facing)
                );
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static int selectTool(
            LocalPlayer player,
            BlockState state,
            int minimumDurability
    ) {
        int selected = player.getInventory().getSelectedSlot();
        List<MiningDecisionEngine26.ToolCandidate> tools =
                new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue()
                    : Integer.MAX_VALUE;
            tools.add(new MiningDecisionEngine26.ToolCandidate(
                    slot,
                    stack.isDamageableItem(),
                    remaining,
                    stack.isCorrectToolForDrops(state),
                    stack.getDestroySpeed(state)
            ));
        }
        return MiningDecisionEngine26.selectBestTool(
                tools,
                selected,
                minimumDurability
        );
    }

    private static int findHotbarSlot(
            LocalPlayer player,
            Item... items
    ) {
        for (int slot = 0; slot < 9; slot++) {
            Item present = player.getInventory().getItem(slot).getItem();
            for (Item requested : items) {
                if (present == requested) {
                    return slot;
                }
            }
        }
        return -1;
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
        int slot = findHotbarSlot(player, Items.END_CRYSTAL);
        return slot < 0
                ? null
                : new CrystalHand(InteractionHand.MAIN_HAND, slot);
    }

    private static boolean validCrystalBase(
            Minecraft client,
            BlockPos base
    ) {
        BlockState state = client.level.getBlockState(base);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private static boolean crystalSpaceClear(
            Minecraft client,
            BlockPos base
    ) {
        if (!client.level.getBlockState(base.above()).isAir()
                || !client.level.getBlockState(base.above(2)).isAir()) {
            return false;
        }
        List<Entity> entities = new ArrayList<>();
        BlockPos above = base.above();
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
                entities,
                1
        );
        return entities.isEmpty();
    }

    private static boolean replaceableAndEmpty(
            Minecraft client,
            BlockPos position
    ) {
        if (!client.level.getBlockState(position).canBeReplaced()) {
            return false;
        }
        List<Entity> entities = new ArrayList<>();
        client.level.getEntities(
                EntityTypeTest.forClass(Entity.class),
                new AABB(position).inflate(0.001),
                Entity::isAlive,
                entities,
                1
        );
        return entities.isEmpty();
    }

    private boolean correctPiston(Minecraft client) {
        BlockState state = client.level.getBlockState(pistonRun.piston);
        return isPiston(state)
                && state.hasProperty(PistonBaseBlock.FACING)
                && state.getValue(PistonBaseBlock.FACING)
                == pistonRun.facing;
    }

    private static boolean isPiston(BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    private static boolean cleanupMatches(
            BlockState state,
            CleanupKind kind
    ) {
        return kind == CleanupKind.POWER
                ? state.is(Blocks.REDSTONE_BLOCK)
                : isPiston(state);
    }

    private static PistonAction actionFor(
            PistonCrystalDecisionEngine26.Sequence.Stage stage
    ) {
        return switch (stage) {
            case PLACE_PISTON,
                 WAIT_PISTON -> PistonAction.PLACE_PISTON;
            case PLACE_CRYSTAL,
                 WAIT_CRYSTAL -> PistonAction.PLACE_CRYSTAL;
            case PLACE_POWER,
                 WAIT_POWER -> PistonAction.PLACE_POWER;
            case BREAK_CRYSTAL,
                 WAIT_BREAK -> PistonAction.BREAK_CRYSTAL;
            default -> null;
        };
    }

    private static PistonCrystalDecisionEngine26.Cell cell(
            BlockPos position
    ) {
        return new PistonCrystalDecisionEngine26.Cell(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private static PistonCrystalDecisionEngine26.Horizontal horizontal(
            Direction direction
    ) {
        return switch (direction) {
            case NORTH ->
                    PistonCrystalDecisionEngine26.Horizontal.NORTH;
            case EAST ->
                    PistonCrystalDecisionEngine26.Horizontal.EAST;
            case SOUTH ->
                    PistonCrystalDecisionEngine26.Horizontal.SOUTH;
            case WEST ->
                    PistonCrystalDecisionEngine26.Horizontal.WEST;
            default -> throw new IllegalArgumentException(
                    "Horizontal direction required"
            );
        };
    }

    private static void restoreTemporarySlot(
            LocalPlayer player,
            int previous,
            int applied
    ) {
        if (previous >= 0
                && previous < 9
                && applied >= 0
                && applied < 9
                && player.getInventory().getSelectedSlot() == applied
                && previous != applied) {
            player.getInventory().setSelectedSlot(previous);
        }
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

    private static boolean destroyPacketsAllowed(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null;
    }

    private static double effectiveHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    private static double square(double value) {
        return value * value;
    }

    private static boolean horizontalNeighbor(
            BlockPos first,
            BlockPos second
    ) {
        return first.getY() == second.getY()
                && Math.abs(first.getX() - second.getX())
                + Math.abs(first.getZ() - second.getZ()) == 1;
    }

    private static int decrement(int value) {
        return value > 0 ? value - 1 : 0;
    }

    public record ModeConfiguration(
            CityConfiguration city,
            PistonConfiguration piston
    ) {
        public ModeConfiguration {
            Objects.requireNonNull(city, "city");
            Objects.requireNonNull(piston, "piston");
        }

        public static ModeConfiguration defaults() {
            return new ModeConfiguration(
                    CityConfiguration.defaults(),
                    PistonConfiguration.defaults()
            );
        }
    }

    public record CityConfiguration(
            double targetRange,
            double mineRange,
            double minimumHealth,
            int minimumToolDurability,
            int confirmationTicks,
            int maximumRetries,
            int actionCooldownTicks,
            int failureCooldownTicks
    ) {
        public CityConfiguration {
            requireModeRange("targetRange", targetRange, 3.0, 16.0);
            requireModeRange("mineRange", mineRange, 2.0, 6.0);
            requireModeRange(
                    "minimumHealth",
                    minimumHealth,
                    1.0,
                    40.0
            );
            requireModeRange(
                    "minimumToolDurability",
                    minimumToolDurability,
                    0,
                    1000
            );
            requireModeRange(
                    "confirmationTicks",
                    confirmationTicks,
                    20,
                    400
            );
            requireModeRange("maximumRetries", maximumRetries, 0, 3);
            requireModeRange(
                    "actionCooldownTicks",
                    actionCooldownTicks,
                    0,
                    40
            );
            requireModeRange(
                    "failureCooldownTicks",
                    failureCooldownTicks,
                    1,
                    200
            );
        }

        public static CityConfiguration defaults() {
            return from(Configuration.defaults());
        }

        static CityConfiguration from(Configuration source) {
            return new CityConfiguration(
                    source.targetRange(),
                    source.cityMineRange(),
                    source.minimumCityHealth(),
                    source.minimumToolDurability(),
                    source.cityConfirmationTicks(),
                    source.cityMaximumRetries(),
                    source.cityActionCooldownTicks(),
                    source.cityFailureCooldownTicks()
            );
        }
    }

    public record PistonConfiguration(
            double targetRange,
            double placeRange,
            double breakRange,
            double minimumHealth,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            int cleanupMinimumToolDurability,
            int confirmationTicks,
            int maximumRetries,
            int actionCooldownTicks,
            int failureCooldownTicks,
            int cleanupTimeoutTicks
    ) {
        /**
         * Source-compatible constructor for callers written before cleanup
         * durability became independently configurable.
         */
        public PistonConfiguration(
                double targetRange,
                double placeRange,
                double breakRange,
                double minimumHealth,
                double minimumTargetDamage,
                double maximumSelfDamage,
                double maximumFriendDamage,
                double selfSafetyReserve,
                double friendSafetyReserve,
                int confirmationTicks,
                int maximumRetries,
                int actionCooldownTicks,
                int failureCooldownTicks,
                int cleanupTimeoutTicks
        ) {
            this(
                    targetRange,
                    placeRange,
                    breakRange,
                    minimumHealth,
                    minimumTargetDamage,
                    maximumSelfDamage,
                    maximumFriendDamage,
                    selfSafetyReserve,
                    friendSafetyReserve,
                    Configuration.defaults().minimumToolDurability(),
                    confirmationTicks,
                    maximumRetries,
                    actionCooldownTicks,
                    failureCooldownTicks,
                    cleanupTimeoutTicks
            );
        }

        public PistonConfiguration {
            requireModeRange("targetRange", targetRange, 3.0, 16.0);
            requireModeRange("placeRange", placeRange, 2.0, 6.0);
            requireModeRange("breakRange", breakRange, 2.0, 6.0);
            requireModeRange(
                    "minimumHealth",
                    minimumHealth,
                    1.0,
                    40.0
            );
            requireModeRange(
                    "minimumTargetDamage",
                    minimumTargetDamage,
                    0.0,
                    36.0
            );
            requireModeRange(
                    "maximumSelfDamage",
                    maximumSelfDamage,
                    0.0,
                    36.0
            );
            requireModeRange(
                    "maximumFriendDamage",
                    maximumFriendDamage,
                    0.0,
                    36.0
            );
            requireModeRange(
                    "selfSafetyReserve",
                    selfSafetyReserve,
                    0.0,
                    20.0
            );
            requireModeRange(
                    "friendSafetyReserve",
                    friendSafetyReserve,
                    0.0,
                    20.0
            );
            requireModeRange(
                    "cleanupMinimumToolDurability",
                    cleanupMinimumToolDurability,
                    0,
                    1000
            );
            requireModeRange(
                    "confirmationTicks",
                    confirmationTicks,
                    2,
                    40
            );
            requireModeRange("maximumRetries", maximumRetries, 0, 3);
            requireModeRange(
                    "actionCooldownTicks",
                    actionCooldownTicks,
                    0,
                    40
            );
            requireModeRange(
                    "failureCooldownTicks",
                    failureCooldownTicks,
                    1,
                    200
            );
            requireModeRange(
                    "cleanupTimeoutTicks",
                    cleanupTimeoutTicks,
                    10,
                    200
            );
        }

        public static PistonConfiguration defaults() {
            return from(Configuration.defaults());
        }

        static PistonConfiguration from(Configuration source) {
            return new PistonConfiguration(
                    source.targetRange(),
                    source.pistonPlaceRange(),
                    source.pistonBreakRange(),
                    source.minimumPistonHealth(),
                    source.minimumTargetDamage(),
                    source.maximumSelfDamage(),
                    source.maximumFriendDamage(),
                    source.selfSafetyReserve(),
                    source.friendSafetyReserve(),
                    source.minimumToolDurability(),
                    source.pistonConfirmationTicks(),
                    source.pistonMaximumRetries(),
                    source.pistonActionCooldownTicks(),
                    source.pistonFailureCooldownTicks(),
                    source.cleanupTimeoutTicks()
            );
        }
    }

    private static void requireModeRange(
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

    private static void requireModeRange(
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

    /**
     * Legacy flat configuration retained for source compatibility. New
     * integrations should use {@link ModeConfiguration} so each module keeps
     * its own target range and policy.
     */
    public record Configuration(
            double targetRange,
            double cityMineRange,
            double pistonPlaceRange,
            double pistonBreakRange,
            double minimumCityHealth,
            double minimumPistonHealth,
            double minimumTargetDamage,
            double maximumSelfDamage,
            double maximumFriendDamage,
            double selfSafetyReserve,
            double friendSafetyReserve,
            int minimumToolDurability,
            int cityConfirmationTicks,
            int cityMaximumRetries,
            int cityActionCooldownTicks,
            int cityFailureCooldownTicks,
            int pistonConfirmationTicks,
            int pistonMaximumRetries,
            int pistonActionCooldownTicks,
            int pistonFailureCooldownTicks,
            int cleanupTimeoutTicks
    ) {
        public Configuration {
            require("targetRange", targetRange, 3.0, 16.0);
            require("cityMineRange", cityMineRange, 2.0, 6.0);
            require("pistonPlaceRange", pistonPlaceRange, 2.0, 6.0);
            require("pistonBreakRange", pistonBreakRange, 2.0, 6.0);
            require("minimumCityHealth", minimumCityHealth, 1.0, 40.0);
            require(
                    "minimumPistonHealth",
                    minimumPistonHealth,
                    1.0,
                    40.0
            );
            require(
                    "minimumTargetDamage",
                    minimumTargetDamage,
                    0.0,
                    36.0
            );
            require(
                    "maximumSelfDamage",
                    maximumSelfDamage,
                    0.0,
                    36.0
            );
            require(
                    "maximumFriendDamage",
                    maximumFriendDamage,
                    0.0,
                    36.0
            );
            require(
                    "selfSafetyReserve",
                    selfSafetyReserve,
                    0.0,
                    20.0
            );
            require(
                    "friendSafetyReserve",
                    friendSafetyReserve,
                    0.0,
                    20.0
            );
            require(
                    "minimumToolDurability",
                    minimumToolDurability,
                    0,
                    1000
            );
            require(
                    "cityConfirmationTicks",
                    cityConfirmationTicks,
                    20,
                    400
            );
            require("cityMaximumRetries", cityMaximumRetries, 0, 3);
            require(
                    "cityActionCooldownTicks",
                    cityActionCooldownTicks,
                    0,
                    40
            );
            require(
                    "cityFailureCooldownTicks",
                    cityFailureCooldownTicks,
                    1,
                    200
            );
            require(
                    "pistonConfirmationTicks",
                    pistonConfirmationTicks,
                    2,
                    40
            );
            require(
                    "pistonMaximumRetries",
                    pistonMaximumRetries,
                    0,
                    3
            );
            require(
                    "pistonActionCooldownTicks",
                    pistonActionCooldownTicks,
                    0,
                    40
            );
            require(
                    "pistonFailureCooldownTicks",
                    pistonFailureCooldownTicks,
                    1,
                    200
            );
            require(
                    "cleanupTimeoutTicks",
                    cleanupTimeoutTicks,
                    10,
                    200
            );
        }

        public static Configuration defaults() {
            return new Configuration(
                    8.0,
                    4.5,
                    4.5,
                    5.0,
                    8.0,
                    12.0,
                    6.0,
                    12.0,
                    4.0,
                    6.0,
                    6.0,
                    5,
                    240,
                    1,
                    4,
                    40,
                    8,
                    1,
                    6,
                    60,
                    80
            );
        }

        private static void require(
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

        private static void require(
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
            String cityPhase,
            BlockPos cityTarget,
            int cityRetries,
            int cityCooldown,
            String pistonPhase,
            String cleanupPhase,
            BlockPos pistonBase,
            int pistonCooldown
    ) {
    }

    private record CityPlan(
            long key,
            int targetEntityId,
            BlockPos position,
            Direction face,
            BlockState state,
            int toolSlot
    ) {
    }

    private record CitySession(
            long key,
            int targetEntityId,
            BlockPos position,
            Direction face,
            BlockState initialState
    ) {
        static CitySession from(CityPlan plan) {
            return new CitySession(
                    plan.key(),
                    plan.targetEntityId(),
                    plan.position(),
                    plan.face(),
                    plan.state()
            );
        }
    }

    private enum CityAction {
        START,
        CONTINUE,
        RETRY,
        STOP
    }

    private record PreparedCity(
            CityAction action,
            CitySession session,
            boolean confirmed,
            boolean failed
    ) {
        static PreparedCity start(CityPlan plan) {
            return new PreparedCity(
                    CityAction.START,
                    CitySession.from(plan),
                    false,
                    false
            );
        }

        static PreparedCity continueMining(CitySession session) {
            return new PreparedCity(
                    CityAction.CONTINUE,
                    session,
                    false,
                    false
            );
        }

        static PreparedCity retry(CitySession session) {
            return new PreparedCity(
                    CityAction.RETRY,
                    session,
                    false,
                    false
            );
        }

        static PreparedCity stop(boolean confirmed, boolean failed) {
            return new PreparedCity(
                    CityAction.STOP,
                    null,
                    confirmed,
                    failed
            );
        }
    }

    private static final class PistonRun {
        private final long key;
        private final int targetEntityId;
        private final BlockPos base;
        private final BlockPos piston;
        private final BlockPos power;
        private final Direction facing;
        private final int pistonSlot;
        private final int powerSlot;
        private final CrystalHand crystalHand;
        private final Set<Integer> preexistingCrystalIds;
        private int crystalEntityId = -1;
        private boolean pistonPlacementSent;
        private PistonCrystalDecisionEngine26.PlacementOwnership
                pistonOwnership =
                PistonCrystalDecisionEngine26.PlacementOwnership
                        .unconfirmed();
        private boolean crystalPlacementSent;
        private boolean crystalConfirmed;
        private boolean powerPlacementSent;
        private PistonCrystalDecisionEngine26.PlacementOwnership
                powerOwnership =
                PistonCrystalDecisionEngine26.PlacementOwnership
                        .unconfirmed();

        private PistonRun(
                long key,
                int targetEntityId,
                BlockPos base,
                BlockPos piston,
                BlockPos power,
                Direction facing,
                int pistonSlot,
                int powerSlot,
                CrystalHand crystalHand,
                Set<Integer> preexistingCrystalIds
        ) {
            this.key = key;
            this.targetEntityId = targetEntityId;
            this.base = base;
            this.piston = piston;
            this.power = power;
            this.facing = facing;
            this.pistonSlot = pistonSlot;
            this.powerSlot = powerSlot;
            this.crystalHand = crystalHand;
            this.preexistingCrystalIds = Set.copyOf(
                    preexistingCrystalIds
            );
        }

        BlockPos base() {
            return base;
        }
    }

    private enum PistonAction {
        PLACE_PISTON(false),
        PLACE_CRYSTAL(false),
        PLACE_POWER(false),
        BREAK_CRYSTAL(false),
        CLEANUP_START(true),
        CLEANUP_CONTINUE(true),
        CLEANUP_ADVANCE(true),
        CLEANUP_ABANDON(true);

        private final boolean cleanup;

        PistonAction(boolean cleanup) {
            this.cleanup = cleanup;
        }

        boolean cleanup() {
            return cleanup;
        }
    }

    private record PreparedPiston(
            PistonAction action,
            boolean retry,
            CleanupSession cleanup
    ) {
    }

    private enum CleanupKind {
        POWER,
        PISTON
    }

    private record CleanupSession(
            CleanupKind kind,
            BlockPos position,
            boolean started,
            long deadline,
            int previousSlot,
            int appliedSlot
    ) {
        static CleanupSession pending(
                CleanupKind kind,
                BlockPos position,
                long tick,
                int timeout
        ) {
            long deadline = tick > Long.MAX_VALUE - timeout
                    ? Long.MAX_VALUE
                    : tick + timeout;
            return new CleanupSession(
                    kind,
                    position,
                    false,
                    deadline,
                    -1,
                    -1
            );
        }
    }

    private record CrystalHand(InteractionHand hand, int slot) {
    }
}
