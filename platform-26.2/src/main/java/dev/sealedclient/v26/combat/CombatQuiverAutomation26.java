package dev.sealedclient.v26.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative self-buff Quiver service for Minecraft 26.2.
 *
 * <p>Quiver never initiates item use or moves an arrow. The player must
 * already be drawing a main-hand bow with a wholly beneficial, useful tipped
 * arrow in the offhand. It atomically owns ROTATION, USE and HOTBAR so another
 * automation cannot switch the selected bow during aim/release. Release
 * remains blocked by health, effect, durability, inventory, movement and
 * overhead-clearance checks.</p>
 *
 * <p>After a release, the service waits for both inventory/durability
 * acceptance and a server-reflected effect or health change. It does not
 * blindly repeat an unconfirmed shot.</p>
 */
public final class CombatQuiverAutomation26 {
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    20,
                    16.0F,
                    2,
                    100,
                    100,
                    8,
                    100,
                    40
            );

    public static final String OWNER = "quiver";
    public static final int PRIORITY = 64;
    public static final Set<CombatActionArbiter26.Channel> CHANNELS =
            Set.of(
                    CombatActionArbiter26.Channel.ROTATION,
                    CombatActionArbiter26.Channel.USE,
                    CombatActionArbiter26.Channel.HOTBAR
            );

    private static final double OVERHEAD_CLEARANCE = 32.0;
    private static final double MAXIMUM_HORIZONTAL_SPEED_SQUARED = 0.04;

    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private PreparedQuiver pending = PreparedQuiver.none();
    private Confirmation confirmation;
    private int cooldownTicks;
    private int lastAdvancedTick = Integer.MIN_VALUE;
    private ConfirmationOutcome lastOutcome = ConfirmationOutcome.NONE;
    private int lastAimTick = Integer.MIN_VALUE;
    private int lastAimSlot = -1;
    private ItemStack lastAimBow;

    public CombatQuiverAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public CombatQuiverAutomation26(Configuration configuration) {
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
     * Prepares aim or release and submits one atomic three-channel bundle.
     */
    public void submit(
            Minecraft client,
            boolean enabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pending = PreparedQuiver.none();
        LocalPlayer player = client == null ? null : client.player;
        if (player != observedPlayer) {
            resetForPlayer(player);
        }
        advance(client);
        if (confirmation != null) {
            evaluateConfirmation(client);
            if (confirmation != null) {
                return;
            }
        }
        if (!enabled
                || cooldownTicks > 0
                || !sessionAllowsQuiver(client)
                || !safeStationaryUse(client.player)
                || !inventoryReady(client)
                || !overheadClear(client)) {
            return;
        }

        ItemStack bow = client.player.getUseItem();
        ItemStack arrow = client.player.getOffhandItem();
        QuiverDecisionEngine26.ArrowDecision arrowDecision =
                inspectArrow(client.player, arrow);
        if (!bowSafe(bow)
                || !arrowDecision.safeAndUseful()) {
            return;
        }

        boolean charged = client.player.getTicksUsingItem()
                >= configuration.drawTicks();
        boolean serverHadAimTick = charged
                && lastAimTick == client.player.tickCount - 1
                && lastAimSlot
                == client.player.getInventory().getSelectedSlot()
                && lastAimBow == bow;
        QuiverAction action = serverHadAimTick
                ? QuiverAction.RELEASE
                : QuiverAction.AIM;
        pending = new PreparedQuiver(
                true,
                action,
                client.player.tickCount,
                client.player.getInventory().getSelectedSlot(),
                bow,
                arrow,
                arrowDecision
        );
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    /**
     * Aims straight upward or releases the manually drawn bow.
     */
    public boolean execute(
            Minecraft client,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PreparedQuiver prepared = pending;
        pending = PreparedQuiver.none();
        if (!prepared.requested()
                || !arbiter.ownsAll(OWNER, CHANNELS)
                || !preparedStillValid(client, prepared)) {
            return false;
        }

        // Minecraft pitch -90 is straight upward.
        client.player.setXRot(-90.0F);
        if (prepared.action() == QuiverAction.AIM) {
            lastAimTick = client.player.tickCount;
            lastAimSlot = prepared.selectedSlot();
            lastAimBow = prepared.bow();
            return true;
        }

        confirmation = createConfirmation(client, prepared);
        clearAimLease();
        client.gameMode.releaseUsingItem(client.player);
        client.player.swing(InteractionHand.MAIN_HAND);
        lastOutcome = ConfirmationOutcome.WAITING;
        return true;
    }

    /**
     * Clears this service without cancelling a use action the player started.
     */
    public void release(Minecraft client) {
        pending = PreparedQuiver.none();
        confirmation = null;
        cooldownTicks = 0;
        lastAdvancedTick = Integer.MIN_VALUE;
        lastOutcome = ConfirmationOutcome.NONE;
        observedPlayer = null;
        clearAimLease();
    }

    public Status status() {
        return new Status(
                confirmation != null,
                confirmation == null ? 0 : confirmation.elapsedTicks(),
                cooldownTicks,
                lastOutcome
        );
    }

    private void advance(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) {
            return;
        }
        int tick = player.tickCount;
        if (lastAdvancedTick != Integer.MIN_VALUE
                && tick < lastAdvancedTick) {
            confirmation = null;
            cooldownTicks = 0;
        }
        if (tick == lastAdvancedTick) {
            return;
        }
        lastAdvancedTick = tick;
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (confirmation != null) {
            confirmation = confirmation.advance();
        }
    }

    private void evaluateConfirmation(Minecraft client) {
        if (!sessionAllowsConfirmation(client)
                || client.player != observedPlayer) {
            failConfirmation();
            return;
        }
        Confirmation active = confirmation;
        int arrowCount = currentArrowCount(client.player);
        int bowDamage = currentBowDamage(
                client.player,
                active.bowInventorySlot()
        );
        boolean shotAccepted = active.shotAccepted()
                || QuiverDecisionEngine26.shotAccepted(
                        active.arrowCountBefore(),
                        arrowCount,
                        active.bowDamageBefore(),
                        bowDamage
                );
        if (shotAccepted != active.shotAccepted()) {
            active = active.withShotAccepted();
            confirmation = active;
        }

        List<QuiverDecisionEngine26.EffectObservation> observations =
                currentObservations(client.player, active.effects());
        if (shotAccepted
                && QuiverDecisionEngine26.effectConfirmed(
                        observations,
                        active.effectiveHealthBefore(),
                        effectiveHealth(client.player)
                )) {
            confirmation = null;
            cooldownTicks = configuration.successCooldownTicks();
            lastOutcome = ConfirmationOutcome.CONFIRMED;
            return;
        }
        if ((!shotAccepted
                && active.elapsedTicks()
                >= configuration.shotAcceptanceTicks())
                || active.elapsedTicks()
                >= configuration.confirmationTicks()) {
            failConfirmation();
        }
    }

    private void failConfirmation() {
        confirmation = null;
        cooldownTicks = configuration.failureCooldownTicks();
        lastOutcome = ConfirmationOutcome.FAILED;
    }

    private boolean preparedStillValid(
            Minecraft client,
            PreparedQuiver prepared
    ) {
        if (!sessionAllowsQuiver(client)
                || client.player != observedPlayer
                || client.player.tickCount != prepared.preparedTick()
                || client.player.getInventory().getSelectedSlot()
                != prepared.selectedSlot()
                || !safeStationaryUse(client.player)
                || !inventoryReady(client)
                || !overheadClear(client)
                || client.player.getUseItem() != prepared.bow()
                || client.player.getOffhandItem() != prepared.arrow()
                || !bowSafe(client.player.getUseItem())
                || !inspectArrow(
                        client.player,
                        client.player.getOffhandItem()
                ).safeAndUseful()) {
            return false;
        }
        return prepared.action() != QuiverAction.RELEASE
                || client.player.getTicksUsingItem()
                >= configuration.drawTicks();
    }

    private Confirmation createConfirmation(
            Minecraft client,
            PreparedQuiver prepared
    ) {
        List<ExpectedEffect> effects = new ArrayList<>();
        PotionContents potion = prepared.arrow().get(
                DataComponents.POTION_CONTENTS
        );
        if (potion != null) {
            for (MobEffectInstance instance : potion.getAllEffects()) {
                Holder<MobEffect> effect = instance.getEffect();
                MobEffectInstance current =
                        client.player.getEffect(effect);
                effects.add(new ExpectedEffect(
                        effect,
                        effect.getRegisteredName(),
                        effect.value().isInstantaneous(),
                        isInstantHealth(effect),
                        current == null ? -1 : current.getAmplifier(),
                        current == null ? 0 : current.getDuration()
                ));
            }
        }
        return new Confirmation(
                0,
                false,
                prepared.selectedSlot(),
                prepared.arrow().getCount(),
                prepared.bow().getDamageValue(),
                effectiveHealth(client.player),
                List.copyOf(effects)
        );
    }

    private QuiverDecisionEngine26.ArrowDecision inspectArrow(
            LocalPlayer player,
            ItemStack arrow
    ) {
        if (arrow == null
                || arrow.isEmpty()
                || !arrow.is(Items.TIPPED_ARROW)) {
            return QuiverDecisionEngine26.ArrowDecision.blocked(
                    null,
                    QuiverDecisionEngine26.BlockReason.INVALID
            );
        }
        PotionContents potion = arrow.get(DataComponents.POTION_CONTENTS);
        if (potion == null || !potion.hasEffects()) {
            return QuiverDecisionEngine26.ArrowDecision.blocked(
                    null,
                    QuiverDecisionEngine26.BlockReason.INVALID
            );
        }
        List<QuiverDecisionEngine26.EffectCandidate> effects =
                new ArrayList<>();
        for (MobEffectInstance instance : potion.getAllEffects()) {
            Holder<MobEffect> effect = instance.getEffect();
            MobEffectInstance current = player.getEffect(effect);
            int appliedDuration = effect.value().isInstantaneous()
                    ? 0
                    : Math.max(1, instance.getDuration() / 8);
            effects.add(new QuiverDecisionEngine26.EffectCandidate(
                    effect.getRegisteredName(),
                    effect.value().isBeneficial(),
                    effect.value().isInstantaneous(),
                    isInstantHealth(effect),
                    instance.getAmplifier(),
                    appliedDuration,
                    current == null ? -1 : current.getAmplifier(),
                    current == null ? 0 : current.getDuration()
            ));
        }
        QuiverDecisionEngine26.ArrowCandidate candidate =
                new QuiverDecisionEngine26.ArrowCandidate(
                        net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                        arrow.getCount(),
                        effects
                );
        return QuiverDecisionEngine26.select(
                List.of(candidate),
                configuration.minimumEffectRemainingTicks(),
                Math.max(0.0, player.getMaxHealth() - player.getHealth())
        ).orElseGet(() ->
                QuiverDecisionEngine26.ArrowDecision.blocked(
                        candidate,
                        QuiverDecisionEngine26.BlockReason.REDUNDANT_EFFECT
                )
        );
    }

    private boolean bowSafe(ItemStack bow) {
        if (bow == null
                || bow.isEmpty()
                || !(bow.getItem() instanceof BowItem)) {
            return false;
        }
        if (!bow.isDamageableItem()) {
            return true;
        }
        return bow.getMaxDamage() - bow.getDamageValue()
                >= configuration.minimumBowDurability();
    }

    private static boolean safeStationaryUse(LocalPlayer player) {
        if (player == null
                || !player.isUsingItem()
                || player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || !(player.getUseItem().getItem() instanceof BowItem)
                || !player.onGround()
                || player.isPassenger()
                || player.isInWater()
                || player.isInLava()
                || player.isFallFlying()) {
            return false;
        }
        Vec3 movement = player.getDeltaMovement();
        return movement.x * movement.x + movement.z * movement.z
                <= MAXIMUM_HORIZONTAL_SPEED_SQUARED;
    }

    private static boolean overheadClear(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        HitResult hit = client.level.clip(new ClipContext(
                eye,
                eye.add(0.0, OVERHEAD_CLEARANCE, 0.0),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean inventoryReady(Minecraft client) {
        return client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    private boolean sessionAllowsQuiver(Minecraft client) {
        return sessionAllowsConfirmation(client)
                && client.gui.screen() == null
                && effectiveHealth(client.player)
                >= configuration.minimumHealth();
    }

    private static boolean sessionAllowsConfirmation(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.getConnection().getConnection().isConnected()
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static int currentArrowCount(LocalPlayer player) {
        ItemStack arrow = player.getOffhandItem();
        return arrow.is(Items.TIPPED_ARROW) ? arrow.getCount() : 0;
    }

    private static int currentBowDamage(
            LocalPlayer player,
            int inventorySlot
    ) {
        if (inventorySlot < 0 || inventorySlot >= 9) {
            return -1;
        }
        ItemStack bow = player.getInventory().getItem(inventorySlot);
        return bow.getItem() instanceof BowItem
                ? bow.getDamageValue()
                : -1;
    }

    private static List<QuiverDecisionEngine26.EffectObservation>
    currentObservations(
            LocalPlayer player,
            List<ExpectedEffect> effects
    ) {
        List<QuiverDecisionEngine26.EffectObservation> observations =
                new ArrayList<>(effects.size());
        for (ExpectedEffect expected : effects) {
            MobEffectInstance current = player.getEffect(expected.effect());
            observations.add(
                    new QuiverDecisionEngine26.EffectObservation(
                            expected.key(),
                            expected.instantaneous(),
                            expected.healthRestoring(),
                            expected.beforeAmplifier(),
                            expected.beforeRemainingTicks(),
                            current == null ? -1 : current.getAmplifier(),
                            current == null ? 0 : current.getDuration()
                    )
            );
        }
        return observations;
    }

    private static float effectiveHealth(LocalPlayer player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static boolean isInstantHealth(Holder<MobEffect> effect) {
        return effect.value() == MobEffects.INSTANT_HEALTH.value();
    }

    private void resetForPlayer(LocalPlayer player) {
        observedPlayer = player;
        pending = PreparedQuiver.none();
        confirmation = null;
        cooldownTicks = 0;
        lastAdvancedTick = player == null
                ? Integer.MIN_VALUE
                : player.tickCount;
        lastOutcome = ConfirmationOutcome.NONE;
        clearAimLease();
    }

    public enum QuiverAction {
        AIM,
        RELEASE
    }

    public enum ConfirmationOutcome {
        NONE,
        WAITING,
        CONFIRMED,
        FAILED
    }

    public record Configuration(
            int drawTicks,
            float minimumHealth,
            int minimumBowDurability,
            int minimumEffectRemainingTicks,
            int confirmationTicks,
            int shotAcceptanceTicks,
            int successCooldownTicks,
            int failureCooldownTicks
    ) {
        public Configuration {
            if (drawTicks < 5 || drawTicks > 30) {
                throw new IllegalArgumentException(
                        "drawTicks must be within 5..30"
                );
            }
            if (!Float.isFinite(minimumHealth)
                    || minimumHealth <= 0.0F
                    || minimumHealth > 40.0F) {
                throw new IllegalArgumentException(
                        "minimumHealth must be finite and within (0, 40]"
                );
            }
            if (minimumBowDurability < 2
                    || minimumBowDurability > 384) {
                throw new IllegalArgumentException(
                        "minimumBowDurability must be within 2..384"
                );
            }
            if (minimumEffectRemainingTicks < 0
                    || minimumEffectRemainingTicks > 24_000) {
                throw new IllegalArgumentException(
                        "minimumEffectRemainingTicks must be within 0..24000"
                );
            }
            if (confirmationTicks < 20 || confirmationTicks > 200) {
                throw new IllegalArgumentException(
                        "confirmationTicks must be within 20..200"
                );
            }
            if (shotAcceptanceTicks < 1
                    || shotAcceptanceTicks > 20
                    || shotAcceptanceTicks > confirmationTicks) {
                throw new IllegalArgumentException(
                        "shotAcceptanceTicks must be within 1..20 and not exceed confirmationTicks"
                );
            }
            requireCooldown(successCooldownTicks, "successCooldownTicks");
            requireCooldown(failureCooldownTicks, "failureCooldownTicks");
        }
    }

    public record PreparedQuiver(
            boolean requested,
            QuiverAction action,
            int preparedTick,
            int selectedSlot,
            ItemStack bow,
            ItemStack arrow,
            QuiverDecisionEngine26.ArrowDecision arrowDecision
    ) {
        public PreparedQuiver {
            if (requested) {
                Objects.requireNonNull(action, "action");
                Objects.requireNonNull(bow, "bow");
                Objects.requireNonNull(arrow, "arrow");
                Objects.requireNonNull(arrowDecision, "arrowDecision");
            }
        }

        public static PreparedQuiver none() {
            return new PreparedQuiver(
                    false,
                    null,
                    Integer.MIN_VALUE,
                    -1,
                    null,
                    null,
                    null
            );
        }
    }

    public record Status(
            boolean awaitingConfirmation,
            int confirmationElapsedTicks,
            int cooldownTicks,
            ConfirmationOutcome lastOutcome
    ) {
    }

    private record ExpectedEffect(
            Holder<MobEffect> effect,
            String key,
            boolean instantaneous,
            boolean healthRestoring,
            int beforeAmplifier,
            int beforeRemainingTicks
    ) {
    }

    private record Confirmation(
            int elapsedTicks,
            boolean shotAccepted,
            int bowInventorySlot,
            int arrowCountBefore,
            int bowDamageBefore,
            float effectiveHealthBefore,
            List<ExpectedEffect> effects
    ) {
        private Confirmation {
            effects = List.copyOf(effects);
        }

        private Confirmation advance() {
            return new Confirmation(
                    elapsedTicks + 1,
                    shotAccepted,
                    bowInventorySlot,
                    arrowCountBefore,
                    bowDamageBefore,
                    effectiveHealthBefore,
                    effects
            );
        }

        private Confirmation withShotAccepted() {
            return new Confirmation(
                    elapsedTicks,
                    true,
                    bowInventorySlot,
                    arrowCountBefore,
                    bowDamageBefore,
                    effectiveHealthBefore,
                    effects
            );
        }
    }

    private static void requireCooldown(int value, String name) {
        if (value < 0 || value > 1_200) {
            throw new IllegalArgumentException(
                    name + " must be within 0..1200"
            );
        }
    }

    private void clearAimLease() {
        lastAimTick = Integer.MIN_VALUE;
        lastAimSlot = -1;
        lastAimBow = null;
    }
}
