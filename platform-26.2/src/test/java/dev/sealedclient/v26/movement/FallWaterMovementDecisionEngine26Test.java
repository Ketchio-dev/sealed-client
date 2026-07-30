package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallWaterMovementDecisionEngine26Test {
    private static final FallWaterMovementDecisionEngine26.NoFallLimits
            NO_FALL_LIMITS =
            new FallWaterMovementDecisionEngine26.NoFallLimits(3.2, 0.08);
    private static final FallWaterMovementDecisionEngine26.FastSwimLimits
            SWIM_LIMITS =
            new FallWaterMovementDecisionEngine26.FastSwimLimits(0.22, 0.24);
    private static final FallWaterMovementDecisionEngine26.JesusLimits
            JESUS_LIMITS =
            new FallWaterMovementDecisionEngine26.JesusLimits(0.08, 0.04);

    @Test
    void noFallRequestsOneVanillaGlideAttemptForARealFall() {
        FallWaterMovementDecisionEngine26.NoFallDecision decision =
                FallWaterMovementDecisionEngine26.decideNoFall(
                        noFallObservation(),
                        NO_FALL_LIMITS
                );

        assertTrue(decision.shouldAttempt());
        assertFalse(decision.shouldResetAttempt());
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.NONE,
                decision.reason()
        );
    }

    @Test
    void noFallResetsLatchOnlyOnAuthoritativeSafeSurface() {
        FallWaterMovementDecisionEngine26.NoFallObservation grounded =
                noFallObservation(
                        true,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        4.0,
                        -0.5
                );

        FallWaterMovementDecisionEngine26.NoFallDecision decision =
                FallWaterMovementDecisionEngine26.decideNoFall(
                        grounded,
                        NO_FALL_LIMITS
                );

        assertTrue(decision.shouldResetAttempt());
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.SAFE_SURFACE,
                decision.reason()
        );
    }

    @Test
    void noFallHonorsManualJumpAndSneakBeforeAutomation() {
        FallWaterMovementDecisionEngine26.NoFallObservation manualJump =
                copyNoFall(noFallObservation(), true, false);
        FallWaterMovementDecisionEngine26.NoFallObservation manualShift =
                copyNoFall(noFallObservation(), false, true);

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.MANUAL_OVERRIDE,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        manualJump,
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.MANUAL_OVERRIDE,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        manualShift,
                        NO_FALL_LIMITS
                ).reason()
        );
    }

    @Test
    void noFallRequiresUsableElytraDescentAndNetworkSafety() {
        FallWaterMovementDecisionEngine26.NoFallObservation base =
                noFallObservation();

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.NO_SAFE_ELYTRA,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        copyNoFall(base, false, true, 4.0, -0.5),
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .NOT_FALLING_FAST_ENOUGH,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        copyNoFall(base, true, true, 3.19, -0.5),
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .NOT_FALLING_FAST_ENOUGH,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        copyNoFall(base, true, true, 4.0, -0.079),
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .NETWORK_SUPPRESSED,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        copySafety(base, false),
                        NO_FALL_LIMITS
                ).reason()
        );
    }

    @Test
    void noFallLatchAndPacketBudgetPreventRepeatedPackets() {
        FallWaterMovementDecisionEngine26.NoFallObservation attempted =
                copyAttemptBudget(noFallObservation(), true, true);
        FallWaterMovementDecisionEngine26.NoFallObservation exhausted =
                copyAttemptBudget(noFallObservation(), false, false);

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .ALREADY_ATTEMPTED,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        attempted,
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.ACTION_BUDGET,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        exhausted,
                        NO_FALL_LIMITS
                ).reason()
        );
    }

    @Test
    void noFallRejectsUnsafePhysicsAndInvalidNumbers() {
        FallWaterMovementDecisionEngine26.NoFallObservation passenger =
                copyUnsafe(noFallObservation(), true, false, false);
        FallWaterMovementDecisionEngine26.NoFallObservation collision =
                copyUnsafe(noFallObservation(), false, true, false);
        FallWaterMovementDecisionEngine26.NoFallObservation flying =
                copyUnsafe(noFallObservation(), false, false, true);
        FallWaterMovementDecisionEngine26.NoFallObservation invalid =
                copyNoFall(
                        noFallObservation(),
                        true,
                        true,
                        Double.NaN,
                        -0.5
                );

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .UNSAFE_MOVEMENT_STATE,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        passenger,
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .UNSAFE_MOVEMENT_STATE,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        collision,
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .UNSAFE_MOVEMENT_STATE,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        flying,
                        NO_FALL_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .INVALID_OBSERVATION,
                FallWaterMovementDecisionEngine26.decideNoFall(
                        invalid,
                        NO_FALL_LIMITS
                ).reason()
        );
    }

    @Test
    void fastSwimUsesManualHeadingAndBoundedAcceleration() {
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        fastSwimObservation(
                                0.0,
                                1.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0
                        ),
                        SWIM_LIMITS
                );

        assertTrue(decision.apply());
        assertEquals(0.0, decision.x(), 1.0E-12);
        assertEquals(0.0528, decision.z(), 1.0E-12);
        assertEquals(0.0, decision.y(), 1.0E-12);
        assertTrue(Math.hypot(decision.x(), decision.z()) < 0.22);
    }

    @Test
    void fastSwimRotatesNormalizedDiagonalInputWithYaw() {
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        fastSwimObservation(
                                1.0,
                                1.0,
                                90.0,
                                0.0,
                                -0.03,
                                0.0
                        ),
                        SWIM_LIMITS
                );

        assertTrue(decision.apply());
        assertTrue(decision.x() < 0.0);
        assertTrue(decision.z() > 0.0);
        assertEquals(-0.03, decision.y(), 1.0E-12);
        assertEquals(0.0528, Math.hypot(decision.x(), decision.z()), 1.0E-12);
    }

    @Test
    void fastSwimScalesDownDuringHighLatencyRecovery() {
        FallWaterMovementDecisionEngine26.FastSwimObservation full =
                fastSwimObservation(0, 1, 0, 0, 0, 0);
        FallWaterMovementDecisionEngine26.FastSwimObservation slowed =
                new FallWaterMovementDecisionEngine26.FastSwimObservation(
                        true,
                        0.45,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                );

        double fullSpeed = FallWaterMovementDecisionEngine26
                .decideFastSwim(full, SWIM_LIMITS).z();
        double slowedSpeed = FallWaterMovementDecisionEngine26
                .decideFastSwim(slowed, SWIM_LIMITS).z();

        assertTrue(slowedSpeed > 0.0);
        assertTrue(slowedSpeed < fullSpeed * 0.5);
    }

    @Test
    void fastSwimRejectsNoInputLavaAndCollisionRisk() {
        FallWaterMovementDecisionEngine26.FastSwimObservation noInput =
                fastSwimObservation(0, 0, 0, 0, 0, 0);
        FallWaterMovementDecisionEngine26.FastSwimObservation lava =
                copySwim(noInput, 0, 1, true, false, true);
        FallWaterMovementDecisionEngine26.FastSwimObservation collision =
                copySwim(noInput, 0, 1, false, true, false);
        FallWaterMovementDecisionEngine26.FastSwimObservation blocked =
                copySwim(noInput, 0, 1, false, false, false);

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.NO_MANUAL_INPUT,
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        noInput,
                        SWIM_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason
                        .NOT_IN_SAFE_WATER,
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        lava,
                        SWIM_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.COLLISION_RISK,
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        collision,
                        SWIM_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.COLLISION_RISK,
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        blocked,
                        SWIM_LIMITS
                ).reason()
        );
    }

    @Test
    void fastSwimReducesOverspeedGraduallyAndPreservesVerticalVelocity() {
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                FallWaterMovementDecisionEngine26.decideFastSwim(
                        fastSwimObservation(0, 1, 0, 0.8, -0.12, 0),
                        SWIM_LIMITS
                );

        assertTrue(decision.apply());
        assertEquals(-0.12, decision.y(), 1.0E-12);
        double speed = Math.hypot(decision.x(), decision.z());
        assertTrue(speed < 0.8);
        assertTrue(speed > 0.22);
        assertEquals(
                Math.hypot(0.8 * (1.0 - 0.24), 0.22 * 0.24),
                speed,
                1.0E-12
        );
    }

    @Test
    void jesusAppliesOnlySmallSurfaceBuoyancyStep() {
        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                FallWaterMovementDecisionEngine26.decideJesus(
                        jesusObservation(-0.02),
                        JESUS_LIMITS
                );

        assertTrue(decision.apply());
        assertEquals(0.02, decision.y(), 1.0E-12);
        assertEquals(0.1, decision.x(), 1.0E-12);
        assertEquals(-0.1, decision.z(), 1.0E-12);
    }

    @Test
    void jesusHighLatencyScaleReducesTargetAndAcceleration() {
        FallWaterMovementDecisionEngine26.JesusObservation slowed =
                new FallWaterMovementDecisionEngine26.JesusObservation(
                        true,
                        0.45,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        0.0,
                        -0.02,
                        0.0
                );

        FallWaterMovementDecisionEngine26.VelocityDecision decision =
                FallWaterMovementDecisionEngine26.decideJesus(
                        slowed,
                        JESUS_LIMITS
                );

        assertTrue(decision.apply());
        assertEquals(-0.002, decision.y(), 1.0E-12);
        assertTrue(decision.y() < 0.08 * 0.45);
    }

    @Test
    void jesusHonorsDescentInputAndRejectsAmbiguousFluidOrCollision() {
        FallWaterMovementDecisionEngine26.JesusObservation base =
                jesusObservation(-0.02);

        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.MANUAL_OVERRIDE,
                FallWaterMovementDecisionEngine26.decideJesus(
                        copyJesus(base, true, true, false, false, false),
                        JESUS_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.NOT_SAFE_SURFACE,
                FallWaterMovementDecisionEngine26.decideJesus(
                        copyJesus(base, false, false, true, false, false),
                        JESUS_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.NOT_SAFE_SURFACE,
                FallWaterMovementDecisionEngine26.decideJesus(
                        copyJesus(base, false, false, false, true, false),
                        JESUS_LIMITS
                ).reason()
        );
        assertEquals(
                FallWaterMovementDecisionEngine26.BlockReason.COLLISION_RISK,
                FallWaterMovementDecisionEngine26.decideJesus(
                        copyJesus(base, false, true, false, false, true),
                        JESUS_LIMITS
                ).reason()
        );
    }

    @Test
    void invalidLimitsAndServiceConfigurationAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FallWaterMovementDecisionEngine26.NoFallLimits(
                        2.49,
                        0.08
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FallWaterMovementDecisionEngine26.FastSwimLimits(
                        0.37,
                        0.24
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FallWaterMovementDecisionEngine26.JesusLimits(
                        0.08,
                        0.09
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FallWaterMovementAutomation26.Configuration(
                        3.2,
                        0.22,
                        Double.NaN
                )
        );
    }

    @Test
    void serviceDeclaresDisjointMinimalActionChannels() {
        assertEquals(
                java.util.Set.of(MovementActionArbiter26.Channel.PACKET),
                FallWaterMovementAutomation26.NO_FALL_CHANNELS
        );
        assertEquals(
                java.util.Set.of(MovementActionArbiter26.Channel.HORIZONTAL),
                FallWaterMovementAutomation26.FAST_SWIM_CHANNELS
        );
        assertEquals(
                java.util.Set.of(MovementActionArbiter26.Channel.VERTICAL),
                FallWaterMovementAutomation26.JESUS_CHANNELS
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            noFallObservation() {
        return noFallObservation(
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                4.0,
                -0.5
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            noFallObservation(
                    boolean enabled,
                    boolean safetyReady,
                    boolean onGround,
                    boolean inWater,
                    boolean inLava,
                    boolean fallFlying,
                    boolean passenger,
                    boolean climbable,
                    boolean noGravity,
                    boolean abilitiesFlying,
                    boolean verticalCollision,
                    boolean manualJump,
                    boolean manualShift,
                    boolean chestGlideUsable,
                    boolean attempted,
                    boolean budget,
                    double fallDistance,
                    double verticalVelocity
            ) {
        return new FallWaterMovementDecisionEngine26.NoFallObservation(
                enabled,
                safetyReady,
                onGround,
                inWater,
                inLava,
                fallFlying,
                passenger,
                climbable,
                noGravity,
                abilitiesFlying,
                verticalCollision,
                manualJump,
                manualShift,
                chestGlideUsable,
                attempted,
                budget,
                fallDistance,
                verticalVelocity
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            copyNoFall(
                    FallWaterMovementDecisionEngine26.NoFallObservation base,
                    boolean manualJump,
                    boolean manualShift
            ) {
        return noFallObservation(
                base.enabled(),
                base.safetyReady(),
                base.onGround(),
                base.inWater(),
                base.inLava(),
                base.fallFlying(),
                base.passenger(),
                base.climbable(),
                base.noGravity(),
                base.abilitiesFlying(),
                base.verticalCollision(),
                manualJump,
                manualShift,
                base.chestGlideUsable(),
                base.attemptedThisFall(),
                base.packetBudgetReady(),
                base.fallDistance(),
                base.verticalVelocity()
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            copyNoFall(
                    FallWaterMovementDecisionEngine26.NoFallObservation base,
                    boolean chestUsable,
                    boolean budget,
                    double fallDistance,
                    double verticalVelocity
            ) {
        return noFallObservation(
                base.enabled(),
                base.safetyReady(),
                base.onGround(),
                base.inWater(),
                base.inLava(),
                base.fallFlying(),
                base.passenger(),
                base.climbable(),
                base.noGravity(),
                base.abilitiesFlying(),
                base.verticalCollision(),
                base.manualJump(),
                base.manualShift(),
                chestUsable,
                base.attemptedThisFall(),
                budget,
                fallDistance,
                verticalVelocity
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            copySafety(
                    FallWaterMovementDecisionEngine26.NoFallObservation base,
                    boolean safety
            ) {
        return noFallObservation(
                base.enabled(),
                safety,
                base.onGround(),
                base.inWater(),
                base.inLava(),
                base.fallFlying(),
                base.passenger(),
                base.climbable(),
                base.noGravity(),
                base.abilitiesFlying(),
                base.verticalCollision(),
                base.manualJump(),
                base.manualShift(),
                base.chestGlideUsable(),
                base.attemptedThisFall(),
                base.packetBudgetReady(),
                base.fallDistance(),
                base.verticalVelocity()
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            copyAttemptBudget(
                    FallWaterMovementDecisionEngine26.NoFallObservation base,
                    boolean attempted,
                    boolean budget
            ) {
        return noFallObservation(
                base.enabled(),
                base.safetyReady(),
                base.onGround(),
                base.inWater(),
                base.inLava(),
                base.fallFlying(),
                base.passenger(),
                base.climbable(),
                base.noGravity(),
                base.abilitiesFlying(),
                base.verticalCollision(),
                base.manualJump(),
                base.manualShift(),
                base.chestGlideUsable(),
                attempted,
                budget,
                base.fallDistance(),
                base.verticalVelocity()
        );
    }

    private static FallWaterMovementDecisionEngine26.NoFallObservation
            copyUnsafe(
                    FallWaterMovementDecisionEngine26.NoFallObservation base,
                    boolean passenger,
                    boolean collision,
                    boolean flying
            ) {
        return noFallObservation(
                base.enabled(),
                base.safetyReady(),
                base.onGround(),
                base.inWater(),
                base.inLava(),
                base.fallFlying(),
                passenger,
                base.climbable(),
                base.noGravity(),
                flying,
                collision,
                base.manualJump(),
                base.manualShift(),
                base.chestGlideUsable(),
                base.attemptedThisFall(),
                base.packetBudgetReady(),
                base.fallDistance(),
                base.verticalVelocity()
        );
    }

    private static FallWaterMovementDecisionEngine26.FastSwimObservation
            fastSwimObservation(
                    double strafe,
                    double forward,
                    double yaw,
                    double x,
                    double y,
                    double z
            ) {
        return new FallWaterMovementDecisionEngine26.FastSwimObservation(
                true,
                1.0,
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                strafe,
                forward,
                yaw,
                x,
                y,
                z
        );
    }

    private static FallWaterMovementDecisionEngine26.FastSwimObservation
            copySwim(
                    FallWaterMovementDecisionEngine26.FastSwimObservation base,
                    double strafe,
                    double forward,
                    boolean lava,
                    boolean collision,
                    boolean pathClear
            ) {
        return new FallWaterMovementDecisionEngine26.FastSwimObservation(
                base.enabled(),
                base.safetyScale(),
                base.inWater(),
                lava,
                base.passenger(),
                base.fallFlying(),
                base.noGravity(),
                collision,
                pathClear,
                strafe,
                forward,
                base.yawDegrees(),
                base.currentX(),
                base.currentY(),
                base.currentZ()
        );
    }

    private static FallWaterMovementDecisionEngine26.JesusObservation
            jesusObservation(double verticalVelocity) {
        return new FallWaterMovementDecisionEngine26.JesusObservation(
                true,
                1.0,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                0.1,
                verticalVelocity,
                -0.1
        );
    }

    private static FallWaterMovementDecisionEngine26.JesusObservation
            copyJesus(
                    FallWaterMovementDecisionEngine26.JesusObservation base,
                    boolean shift,
                    boolean stable,
                    boolean underWater,
                    boolean bubble,
                    boolean collision
            ) {
        return new FallWaterMovementDecisionEngine26.JesusObservation(
                base.enabled(),
                base.safetyScale(),
                base.inWater(),
                underWater,
                base.inLava(),
                stable,
                bubble,
                shift,
                base.passenger(),
                base.fallFlying(),
                base.noGravity(),
                collision,
                !collision,
                base.currentX(),
                base.currentY(),
                base.currentZ()
        );
    }
}
