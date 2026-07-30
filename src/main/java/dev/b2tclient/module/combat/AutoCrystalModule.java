package dev.b2tclient.module.combat;

import dev.b2tclient.B2TClient;
import dev.b2tclient.api.B2TApi;
import dev.b2tclient.combat.CombatTransactionEngine;
import dev.b2tclient.combat.CombatUtil;
import dev.b2tclient.combat.CrystalScoring;
import dev.b2tclient.combat.ExplosionDamageEstimator;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.event.EventBus;
import dev.b2tclient.event.PacketEvent;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class AutoCrystalModule extends Module implements TickableModule {
    private static final String OWNER = "auto_crystal";
    private static final int PRIORITY = 80;
    private static final int MAX_BREAK_CANDIDATES = 12;
    private static final int CONFIRMATION_TIMEOUT_TICKS = 8;
    private static final int MAXIMUM_RETRIES = 2;
    private static final int RETRY_BACKOFF_TICKS = 2;
    private static final int FAILURE_COOLDOWN_TICKS = 40;

    private final FriendManager friends;
    private final ActionCoordinator actions;
    private final CombatTransactionEngine<Long> transaction =
            new CombatTransactionEngine<>(
                    CONFIRMATION_TIMEOUT_TICKS,
                    MAXIMUM_RETRIES,
                    RETRY_BACKOFF_TICKS
            );
    private final BooleanSetting breakCrystals = addSetting(new BooleanSetting(
            "break",
            "Break",
            "Attack safe crystals near the selected target.",
            true
    ));
    private final BooleanSetting placeCrystals = addSetting(new BooleanSetting(
            "place",
            "Place",
            "Place crystals on valid obsidian or bedrock bases.",
            true
    ));
    private final DoubleSetting targetRange = addSetting(new DoubleSetting(
            "target_range",
            "Target range",
            "Maximum distance to select an enemy player.",
            10.0,
            3.0,
            16.0,
            0.5
    ));
    private final DoubleSetting breakRange = addSetting(new DoubleSetting(
            "break_range",
            "Break range",
            "Maximum distance to attack a crystal.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting breakWallRange = addSetting(new DoubleSetting(
            "break_wall_range",
            "Break wall range",
            "Maximum distance to attack a crystal without line of sight.",
            3.0,
            0.0,
            6.0,
            0.1
    ));
    private final DoubleSetting placeRange = addSetting(new DoubleSetting(
            "place_range",
            "Place range",
            "Maximum distance to a crystal base.",
            4.5,
            2.0,
            6.0,
            0.1
    ));
    private final DoubleSetting minSelfDistance = addSetting(new DoubleSetting(
            "min_self_distance",
            "Minimum self distance",
            "Reject crystal positions closer than this simple distance guard.",
            2.0,
            0.0,
            6.0,
            0.1
    ));
    private final DoubleSetting minDamage = addSetting(new DoubleSetting(
            "min_damage",
            "Minimum damage",
            "Required estimated target damage unless face-place conditions apply.",
            6.0,
            0.0,
            36.0,
            0.5
    ));
    private final DoubleSetting maxSelfDamage = addSetting(new DoubleSetting(
            "max_self_damage",
            "Maximum self damage",
            "Maximum estimated damage allowed to the local player.",
            8.0,
            0.0,
            36.0,
            0.5
    ));
    private final DoubleSetting facePlaceHealth = addSetting(new DoubleSetting(
            "face_place_health",
            "Face-place health",
            "Allow damage below minimum when target health plus absorption is this low.",
            8.0,
            0.0,
            36.0,
            0.5
    ));
    private final DoubleSetting facePlaceArmor = addSetting(new DoubleSetting(
            "face_place_armor",
            "Face-place armor",
            "Allow damage below minimum when any equipped target armor is below this durability percent.",
            15.0,
            0.0,
            100.0,
            1.0
    ));
    private final DoubleSetting selfDamageWeight = addSetting(new DoubleSetting(
            "self_damage_weight",
            "Self-damage weight",
            "Penalty applied to estimated self damage while scoring candidates.",
            1.25,
            0.0,
            3.0,
            0.05
    ));
    private final DoubleSetting friendSafety = addSetting(new DoubleSetting(
            "friend_safety",
            "Friend safety",
            "Avoid crystals within this distance of a friend.",
            5.0,
            0.0,
            10.0,
            0.5
    ));
    private final IntegerSetting predictionTicks = addSetting(new IntegerSetting(
            "prediction_ticks",
            "Prediction ticks",
            "Bounded target movement prediction used for damage scoring.",
            1,
            0,
            3,
            1
    ));
    private final IntegerSetting maxCandidates = addSetting(new IntegerSetting(
            "max_candidates",
            "Maximum candidates",
            "Maximum valid bases that receive bounded exposure sampling.",
            12,
            4,
            24,
            1
    ));
    private final IntegerSetting minimumCrystalAge = addSetting(new IntegerSetting(
            "minimum_crystal_age",
            "Minimum crystal age",
            "Minimum client-observed ticks before a crystal may be attacked.",
            1,
            0,
            10,
            1
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Global delay",
            "Minimum ticks between any two crystal actions.",
            1,
            0,
            20,
            1
    ));
    private final IntegerSetting breakDelay = addSetting(new IntegerSetting(
            "break_delay",
            "Break delay",
            "Ticks before another crystal attack.",
            2,
            0,
            20,
            1
    ));
    private final IntegerSetting placeDelay = addSetting(new IntegerSetting(
            "place_delay",
            "Place delay",
            "Ticks before another crystal placement.",
            2,
            0,
            20,
            1
    ));
    private final BooleanSetting rotate = addSetting(new BooleanSetting(
            "rotate",
            "Rotate",
            "Face the crystal or base before interacting.",
            true
    ));

    private int globalCooldown;
    private int breakCooldown;
    private int placeCooldown;
    private volatile PendingCrystalAction pendingAction;
    private volatile long logicalTick;
    private long failureReleaseTick;
    private EventBus.Subscription packetSubscription;

    public AutoCrystalModule(FriendManager friends, ActionCoordinator actions) {
        super(
                "auto_crystal",
                "Auto Crystal",
                "Scores crystal damage and performs bounded, conservative break and place actions.",
                Category.COMBAT,
                false,
                ModuleRisk.COMBAT
        );
        this.friends = Objects.requireNonNull(friends, "friends");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        logicalTick++;
        globalCooldown = decrement(globalCooldown);
        breakCooldown = decrement(breakCooldown);
        placeCooldown = decrement(placeCooldown);
        if (!CombatUtil.isReady(minecraft)) {
            if (transaction.snapshot().phase() != CombatTransactionEngine.Phase.IDLE) {
                resetTransaction("session_unavailable");
            }
            return;
        }

        CombatTransactionEngine.Snapshot<Long> status = transaction.snapshot();
        if (status.phase() == CombatTransactionEngine.Phase.CONFIRMED) {
            resetTransaction("confirmation_consumed");
        } else if (status.phase() == CombatTransactionEngine.Phase.FAILED) {
            if (logicalTick < failureReleaseTick) {
                return;
            }
            resetTransaction("failure_cooldown_elapsed");
        } else {
            CombatTransactionEngine.Directive directive = transaction.advance(logicalTick);
            if (directive == CombatTransactionEngine.Directive.FAILED) {
                failureReleaseTick = logicalTick + FAILURE_COOLDOWN_TICKS;
                return;
            }
            if (directive == CombatTransactionEngine.Directive.RETRY) {
                if (!retryPendingAction(minecraft)) {
                    transaction.fail("retry_target_unavailable", logicalTick);
                    failureReleaseTick = logicalTick + FAILURE_COOLDOWN_TICKS;
                }
                return;
            }
            status = transaction.snapshot();
            if (status.phase() == CombatTransactionEngine.Phase.AWAITING_CONFIRMATION
                    || status.phase() == CombatTransactionEngine.Phase.RETRY_BACKOFF) {
                return;
            }
        }

        if (minecraft.player.isDeadOrDying()
                || minecraft.player.isUsingItem()
                || globalCooldown > 0) {
            return;
        }
        Player target = selectTarget(minecraft);
        if (target == null) {
            return;
        }
        Vec3 predictedTarget = predictedPosition(target);
        boolean facePlace = shouldFacePlace(target);

        if (breakCrystals.get()
                && breakCooldown == 0
                && breakBestCrystal(minecraft, target, predictedTarget, facePlace)) {
            globalCooldown = delay.get();
            breakCooldown = breakDelay.get();
            return;
        }
        if (placeCrystals.get()
                && placeCooldown == 0
                && placeBestCrystal(minecraft, target, predictedTarget, facePlace)) {
            globalCooldown = delay.get();
            placeCooldown = placeDelay.get();
        }
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        logicalTick = 0;
        failureReleaseTick = 0;
        resetTransaction("enabled");
        if (B2TClient.isInitialized()) {
            packetSubscription = B2TApi.events().subscribe(
                    PacketEvent.class,
                    50,
                    this::onPacket
            );
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        globalCooldown = 0;
        breakCooldown = 0;
        placeCooldown = 0;
        if (packetSubscription != null) {
            packetSubscription.close();
            packetSubscription = null;
        }
        resetTransaction("disabled");
        actions.releaseOwner(minecraft, OWNER);
    }

    /**
     * Concise packet transaction diagnostics for commands, tests, and future
     * HUD integrations without exposing mutable engine state.
     */
    public String transactionStatus() {
        return transaction.snapshot().concise();
    }

    private boolean breakBestCrystal(
            Minecraft minecraft,
            Player target,
            Vec3 predictedTarget,
            boolean facePlace
    ) {
        double range = breakRange.get();
        List<EndCrystal> candidates = minecraft.level.getEntitiesOfClass(
                EndCrystal.class,
                minecraft.player.getBoundingBox().inflate(range),
                crystal -> crystal.isAlive()
                        && crystal.time >= minimumCrystalAge.get()
                        && minecraft.player.distanceToSqr(crystal) <= range * range
                        && minecraft.player.distanceTo(crystal) >= minSelfDistance.get()
                        && withinBreakVisibility(minecraft, crystal)
                        && safeForFriends(minecraft, crystal.position())
        );
        candidates.sort(Comparator
                .comparingDouble((EndCrystal crystal) -> target.distanceToSqr(crystal))
                .thenComparingInt(EndCrystal::getId));

        CrystalEvaluation best = null;
        int evaluated = 0;
        for (EndCrystal crystal : candidates) {
            if (evaluated++ >= MAX_BREAK_CANDIDATES) {
                break;
            }
            CrystalEvaluation evaluation = evaluate(
                    minecraft,
                    target,
                    predictedTarget,
                    crystal.position(),
                    minecraft.player.distanceTo(crystal),
                    facePlace
            );
            if (evaluation != null
                    && (best == null || evaluation.score() > best.score())) {
                best = evaluation.withCrystal(crystal);
            }
        }
        if (best == null
                || best.crystal() == null
                || !best.crystal().isAlive()
                || !attackCrystal(minecraft, best.crystal())) {
            return false;
        }
        pendingAction = PendingCrystalAction.forBreak(best.crystal());
        if (!transaction.begin(
                CombatTransactionEngine.Action.BREAK,
                Integer.toUnsignedLong(best.crystal().getId()),
                logicalTick
        )) {
            pendingAction = null;
            return false;
        }
        return true;
    }

    private boolean placeBestCrystal(
            Minecraft minecraft,
            Player target,
            Vec3 predictedTarget,
            boolean facePlace
    ) {
        HandSelection selection = findCrystalHand(minecraft);
        if (selection == null) {
            return false;
        }
        CrystalEvaluation best = findBestBase(
                minecraft,
                target,
                predictedTarget,
                facePlace
        );
        if (best == null
                || best.base() == null
                || !validCrystalSpace(minecraft, best.base())) {
            return false;
        }

        if (!placeCrystal(minecraft, best.base(), selection)) {
            return false;
        }
        pendingAction = PendingCrystalAction.forPlace(best.base());
        if (!transaction.begin(
                CombatTransactionEngine.Action.PLACE,
                best.base().asLong(),
                logicalTick
        )) {
            pendingAction = null;
            return false;
        }
        return true;
    }

    private CrystalEvaluation findBestBase(
            Minecraft minecraft,
            Player target,
            Vec3 predictedTarget,
            boolean facePlace
    ) {
        BlockPos center = target.blockPosition();
        int radius = (int) Math.ceil(placeRange.get());
        double placeLimit = placeRange.get() * placeRange.get();
        double selfLimit = minSelfDistance.get() * minSelfDistance.get();
        List<BlockPos> validBases = new ArrayList<>();

        for (BlockPos mutable : BlockPos.betweenClosed(
                center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius)
        )) {
            BlockPos base = mutable.immutable();
            var block = minecraft.level.getBlockState(base).getBlock();
            if ((block != Blocks.OBSIDIAN && block != Blocks.BEDROCK)
                    || minecraft.player.getEyePosition()
                    .distanceToSqr(base.getCenter()) > placeLimit
                    || minecraft.player.position()
                    .distanceToSqr(base.above().getCenter()) < selfLimit
                    || !validCrystalSpace(minecraft, base)
                    || !safeForFriends(minecraft, base.above().getCenter())) {
                continue;
            }
            validBases.add(base);
        }
        validBases.sort(Comparator
                .comparingDouble((BlockPos base) ->
                        base.above().getCenter().distanceToSqr(predictedTarget))
                .thenComparingLong(BlockPos::asLong));

        CrystalEvaluation best = null;
        int limit = Math.min(maxCandidates.get(), validBases.size());
        for (int index = 0; index < limit; index++) {
            BlockPos base = validBases.get(index);
            Vec3 explosion = base.above().getCenter();
            CrystalEvaluation evaluation = evaluate(
                    minecraft,
                    target,
                    predictedTarget,
                    explosion,
                    minecraft.player.getEyePosition().distanceTo(base.getCenter()),
                    facePlace
            );
            if (evaluation != null
                    && (best == null || evaluation.score() > best.score())) {
                best = evaluation.withBase(base);
            }
        }
        return best;
    }

    private CrystalEvaluation evaluate(
            Minecraft minecraft,
            Player target,
            Vec3 predictedTarget,
            Vec3 explosion,
            double actionDistance,
            boolean facePlace
    ) {
        double selfDamage = ExplosionDamageEstimator.estimateEndCrystal(
                minecraft.level,
                minecraft.player,
                minecraft.player.position(),
                explosion
        );
        double selfHealth = minecraft.player.getHealth()
                + minecraft.player.getAbsorptionAmount();
        if (selfDamage > maxSelfDamage.get() || selfDamage + 0.5 >= selfHealth) {
            return null;
        }
        double targetDamage = ExplosionDamageEstimator.estimateEndCrystal(
                minecraft.level,
                target,
                predictedTarget,
                explosion
        );
        if (!CrystalScoring.acceptable(
                targetDamage,
                selfDamage,
                selfHealth,
                minDamage.get(),
                maxSelfDamage.get(),
                facePlace
        )) {
            return null;
        }
        double score = CrystalScoring.score(
                targetDamage,
                selfDamage,
                explosion.distanceTo(predictedTarget),
                actionDistance,
                selfDamageWeight.get()
        );
        return new CrystalEvaluation(
                null,
                null,
                targetDamage,
                selfDamage,
                score
        );
    }

    private HandSelection findCrystalHand(Minecraft minecraft) {
        if (minecraft.player.getItemInHand(InteractionHand.OFF_HAND).is(Items.END_CRYSTAL)) {
            return new HandSelection(InteractionHand.OFF_HAND, -1);
        }
        int slot = CombatUtil.findHotbarItem(minecraft.player, Items.END_CRYSTAL);
        return slot < 0 ? null : new HandSelection(InteractionHand.MAIN_HAND, slot);
    }

    private boolean withinBreakVisibility(Minecraft minecraft, EndCrystal crystal) {
        if (minecraft.player.hasLineOfSight(crystal)) {
            return true;
        }
        return minecraft.player.distanceToSqr(crystal)
                <= breakWallRange.get() * breakWallRange.get();
    }

    private boolean shouldFacePlace(Player target) {
        if (target.getHealth() + target.getAbsorptionAmount() <= facePlaceHealth.get()) {
            return true;
        }
        if (facePlaceArmor.get() <= 0.0) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack armor = target.getItemBySlot(slot);
            if (!armor.isEmpty()
                    && armor.isDamageableItem()
                    && 100.0 * (armor.getMaxDamage() - armor.getDamageValue())
                    / armor.getMaxDamage() <= facePlaceArmor.get()) {
                return true;
            }
        }
        return false;
    }

    private Player selectTarget(Minecraft minecraft) {
        double rangeSquared = targetRange.get() * targetRange.get();
        return minecraft.level.players().stream()
                .map(Player.class::cast)
                .filter(player -> CombatUtil.isAttackablePlayer(
                        minecraft.player,
                        player,
                        friends
                ))
                .filter(player -> minecraft.player.distanceToSqr(player) <= rangeSquared)
                .min(Comparator
                        .comparingDouble((Player player) -> CrystalScoring.targetPriority(
                                minecraft.player.distanceTo(player),
                                player.getHealth() + player.getAbsorptionAmount(),
                                player.getArmorValue(),
                                shouldFacePlace(player)
                        ))
                        .thenComparing(Player::getUUID))
                .orElse(null);
    }

    private Vec3 predictedPosition(Player target) {
        int ticks = predictionTicks.get();
        if (ticks <= 0 || target.onGround() && target.getDeltaMovement().horizontalDistanceSqr() < 0.0001) {
            return target.position();
        }
        Vec3 movement = target.getDeltaMovement().scale(ticks);
        double horizontalLimit = 1.5;
        double horizontalLength = movement.horizontalDistance();
        if (horizontalLength > horizontalLimit) {
            movement = new Vec3(
                    movement.x * horizontalLimit / horizontalLength,
                    Math.max(-1.0, Math.min(1.0, movement.y)),
                    movement.z * horizontalLimit / horizontalLength
            );
        } else {
            movement = new Vec3(
                    movement.x,
                    Math.max(-1.0, Math.min(1.0, movement.y)),
                    movement.z
            );
        }
        return target.position().add(movement);
    }

    private static boolean validCrystalSpace(Minecraft minecraft, BlockPos base) {
        if (minecraft.level == null
                || minecraft.player == null
                || !minecraft.level.getBlockState(base.above()).isAir()) {
            return false;
        }
        AABB space = new AABB(base.above()).expandTowards(0.0, 1.0, 0.0);
        return minecraft.level.getEntities(
                minecraft.player,
                space,
                entity -> entity.isAlive() && !entity.isRemoved()
        ).isEmpty();
    }

    private boolean safeForFriends(Minecraft minecraft, Vec3 position) {
        double safetySquared = friendSafety.get() * friendSafety.get();
        if (safetySquared <= 0.0) {
            return true;
        }
        return minecraft.level.players().stream()
                .filter(friends::isFriend)
                .noneMatch(friend -> friend.position().distanceToSqr(position) < safetySquared);
    }

    private boolean retryPendingAction(Minecraft minecraft) {
        PendingCrystalAction pending = pendingAction;
        if (pending == null || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        if (pending.action() == CombatTransactionEngine.Action.BREAK) {
            Entity entity = minecraft.level.getEntity(pending.entityId());
            if (!(entity instanceof EndCrystal crystal)
                    || !crystal.isAlive()
                    || minecraft.player.distanceToSqr(crystal)
                    > breakRange.get() * breakRange.get()
                    || !withinBreakVisibility(minecraft, crystal)
                    || !safeForFriends(minecraft, crystal.position())
                    || !attackCrystal(minecraft, crystal)) {
                return false;
            }
            globalCooldown = delay.get();
            breakCooldown = breakDelay.get();
            return true;
        }
        BlockPos base = pending.base();
        HandSelection selection = findCrystalHand(minecraft);
        if (base == null
                || selection == null
                || !validCrystalSpace(minecraft, base)
                || !safeForFriends(minecraft, base.above().getCenter())
                || !placeCrystal(minecraft, base, selection)) {
            return false;
        }
        globalCooldown = delay.get();
        placeCooldown = placeDelay.get();
        return true;
    }

    private boolean attackCrystal(Minecraft minecraft, EndCrystal crystal) {
        if (!actions.claim(ActionCoordinator.Channel.ATTACK, OWNER, PRIORITY, 1)) {
            return false;
        }
        if (rotate.get()
                && actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
            CombatUtil.rotateToward(minecraft.player, crystal.position());
        }
        minecraft.gameMode.attack(minecraft.player, crystal);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean placeCrystal(
            Minecraft minecraft,
            BlockPos base,
            HandSelection selection
    ) {
        if (!actions.claim(ActionCoordinator.Channel.USE, OWNER, PRIORITY, 1)
                || (selection.slot() >= 0
                && !actions.claim(ActionCoordinator.Channel.HOTBAR, OWNER, PRIORITY, 1))) {
            return false;
        }
        int previous = minecraft.player.getInventory().selected;
        try {
            if (selection.slot() >= 0) {
                minecraft.player.getInventory().setSelectedHotbarSlot(selection.slot());
                if (!minecraft.player.getInventory().getSelected().is(Items.END_CRYSTAL)) {
                    return false;
                }
            } else if (!minecraft.player.getItemInHand(selection.hand())
                    .is(Items.END_CRYSTAL)) {
                return false;
            }
            if (rotate.get()
                    && actions.claim(ActionCoordinator.Channel.ROTATION, OWNER, PRIORITY, 1)) {
                CombatUtil.rotateToward(minecraft.player, base.getCenter());
            }
            BlockHitResult hit = new BlockHitResult(
                    base.getCenter().add(0.0, 0.5, 0.0),
                    Direction.UP,
                    base,
                    false
            );
            boolean placed = minecraft.gameMode.useItemOn(
                    minecraft.player,
                    selection.hand(),
                    hit
            ).consumesAction();
            if (placed) {
                minecraft.player.swing(selection.hand());
            }
            return placed;
        } finally {
            if (selection.slot() >= 0
                    && previous >= 0
                    && previous < 9
                    && minecraft.player != null) {
                minecraft.player.getInventory().setSelectedHotbarSlot(previous);
            }
        }
    }

    private void onPacket(PacketEvent event) {
        if (event.direction() != PacketEvent.Direction.INBOUND) {
            return;
        }
        if (event.packet() instanceof ClientboundDisconnectPacket
                || event.packet() instanceof ClientboundLoginPacket
                || event.packet() instanceof ClientboundRespawnPacket) {
            resetTransaction("session_packet");
            return;
        }

        PendingCrystalAction pending = pendingAction;
        if (pending == null) {
            return;
        }
        if (pending.action() == CombatTransactionEngine.Action.PLACE
                && event.packet() instanceof ClientboundAddEntityPacket added
                && added.getType() == EntityType.END_CRYSTAL) {
            Vec3 spawn = new Vec3(added.getX(), added.getY(), added.getZ());
            if (spawn.distanceToSqr(pending.confirmationPosition()) <= 2.25) {
                transaction.confirm(
                        CombatTransactionEngine.Action.PLACE,
                        pending.base().asLong(),
                        logicalTick
                );
            }
            return;
        }
        if (pending.action() != CombatTransactionEngine.Action.BREAK) {
            return;
        }
        if (event.packet() instanceof ClientboundRemoveEntitiesPacket removed
                && removed.getEntityIds().contains(pending.entityId())) {
            transaction.confirm(
                    CombatTransactionEngine.Action.BREAK,
                    Integer.toUnsignedLong(pending.entityId()),
                    logicalTick
            );
        } else if (event.packet() instanceof ClientboundExplodePacket exploded
                && exploded.center().distanceToSqr(pending.confirmationPosition()) <= 2.25) {
            transaction.confirm(
                    CombatTransactionEngine.Action.BREAK,
                    Integer.toUnsignedLong(pending.entityId()),
                    logicalTick
            );
        }
    }

    private void resetTransaction(String reason) {
        pendingAction = null;
        transaction.reset(reason, logicalTick);
    }

    private static int decrement(int value) {
        return value > 0 ? value - 1 : 0;
    }

    private record HandSelection(InteractionHand hand, int slot) {
    }

    private record PendingCrystalAction(
            CombatTransactionEngine.Action action,
            BlockPos base,
            int entityId,
            Vec3 confirmationPosition
    ) {
        private PendingCrystalAction {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(confirmationPosition, "confirmationPosition");
        }

        private static PendingCrystalAction forPlace(BlockPos base) {
            BlockPos immutable = Objects.requireNonNull(base, "base").immutable();
            return new PendingCrystalAction(
                    CombatTransactionEngine.Action.PLACE,
                    immutable,
                    -1,
                    immutable.above().getCenter()
            );
        }

        private static PendingCrystalAction forBreak(EndCrystal crystal) {
            Objects.requireNonNull(crystal, "crystal");
            return new PendingCrystalAction(
                    CombatTransactionEngine.Action.BREAK,
                    null,
                    crystal.getId(),
                    crystal.position()
            );
        }
    }

    private record CrystalEvaluation(
            BlockPos base,
            EndCrystal crystal,
            double targetDamage,
            double selfDamage,
            double score
    ) {
        private CrystalEvaluation withBase(BlockPos requestedBase) {
            return new CrystalEvaluation(
                    requestedBase,
                    crystal,
                    targetDamage,
                    selfDamage,
                    score
            );
        }

        private CrystalEvaluation withCrystal(EndCrystal requestedCrystal) {
            return new CrystalEvaluation(
                    base,
                    requestedCrystal,
                    targetDamage,
                    selfDamage,
                    score
            );
        }
    }
}
