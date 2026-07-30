package dev.sealedclient.v26.movement;

import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoRotatePolicy26Test {
    private final NoRotatePolicy26 policy = new NoRotatePolicy26();

    @Test
    void absoluteCorrectionsUseCurrentCameraComponents() {
        NoRotatePolicy26.Decision decision = policy.decide(
                active(),
                NoRotatePolicy26.Configuration.DEFAULT,
                new NoRotatePolicy26.Rotation(70.0F, -15.0F),
                new NoRotatePolicy26.Rotation(180.0F, 45.0F),
                false,
                false
        );

        assertEquals(70.0F, decision.packetRotation().yaw());
        assertEquals(-15.0F, decision.packetRotation().pitch());
        assertTrue(decision.removeRelativeYaw());
        assertTrue(decision.removeRelativePitch());
    }

    @Test
    void relativeCorrectionsUseZeroSoResolvedRotationStaysCurrent() {
        NoRotatePolicy26.Decision decision = policy.decide(
                active(),
                NoRotatePolicy26.Configuration.DEFAULT,
                new NoRotatePolicy26.Rotation(70.0F, -15.0F),
                new NoRotatePolicy26.Rotation(20.0F, 5.0F),
                true,
                true
        );

        assertEquals(0.0F, decision.packetRotation().yaw());
        assertEquals(0.0F, decision.packetRotation().pitch());
        assertEquals(
                70.0F,
                70.0F + decision.packetRotation().yaw()
        );
        assertEquals(
                -15.0F,
                -15.0F + decision.packetRotation().pitch()
        );
    }

    @Test
    void disabledPolicyLeavesPacketUntouched() {
        NoRotatePolicy26.Rotation incoming =
                new NoRotatePolicy26.Rotation(180.0F, 45.0F);
        NoRotatePolicy26.Decision decision = policy.decide(
                new NoRotatePolicy26.Observation(
                        false, true, true, true
                ),
                NoRotatePolicy26.Configuration.DEFAULT,
                new NoRotatePolicy26.Rotation(70.0F, -15.0F),
                incoming,
                false,
                false
        );

        assertEquals(incoming, decision.packetRotation());
        assertFalse(decision.preserveYaw());
        assertFalse(decision.preservePitch());
    }

    @Test
    void perAxisConfigurationPreservesOnlyRequestedComponent() {
        NoRotatePolicy26.Decision decision = policy.decide(
                active(),
                new NoRotatePolicy26.Configuration(true, false),
                new NoRotatePolicy26.Rotation(70.0F, -15.0F),
                new NoRotatePolicy26.Rotation(20.0F, 5.0F),
                false,
                false
        );

        assertEquals(70.0F, decision.packetRotation().yaw());
        assertEquals(5.0F, decision.packetRotation().pitch());
        assertTrue(decision.removeRelativeYaw());
        assertFalse(decision.removeRelativePitch());
    }

    @Test
    void invalidInputFailsClosedAndEmptyConfigurationIsLegalNoOp() {
        NoRotatePolicy26.Decision invalid = policy.decide(
                active(),
                NoRotatePolicy26.Configuration.DEFAULT,
                new NoRotatePolicy26.Rotation(Float.NaN, 0.0F),
                new NoRotatePolicy26.Rotation(20.0F, 5.0F),
                false,
                false
        );

        assertFalse(invalid.preserveYaw());
        assertFalse(invalid.preservePitch());
        NoRotatePolicy26.Decision noOp = policy.decide(
                active(),
                new NoRotatePolicy26.Configuration(false, false),
                new NoRotatePolicy26.Rotation(70.0F, -15.0F),
                new NoRotatePolicy26.Rotation(20.0F, 5.0F),
                true,
                true
        );
        assertEquals(
                new NoRotatePolicy26.Rotation(20.0F, 5.0F),
                noOp.packetRotation()
        );
        assertFalse(noOp.preserveYaw());
        assertFalse(noOp.preservePitch());
        assertFalse(noOp.removeRelativeYaw());
        assertFalse(noOp.removeRelativePitch());
    }

    @Test
    void activeEmptyConfigurationLeavesPositionCorrectionUnmodified() {
        PositionMoveRotation current = new PositionMoveRotation(
                new Vec3(10.0, 64.0, 10.0),
                new Vec3(0.40, 0.10, -0.20),
                90.0F,
                10.0F
        );
        PositionMoveRotation correction = new PositionMoveRotation(
                new Vec3(2.0, 70.0, -3.0),
                new Vec3(0.20, 0.30, 0.80),
                30.0F,
                5.0F
        );
        Set<Relative> relatives = Set.of(
                Relative.X,
                Relative.Y_ROT,
                Relative.ROTATE_DELTA
        );

        NoRotatePolicy26.PositionDecision decision =
                policy.decidePositionCorrection(
                        active(),
                        new NoRotatePolicy26.Configuration(false, false),
                        current,
                        correction,
                        relatives
                );

        assertFalse(decision.modified());
        assertEquals(correction, decision.correction());
        assertEquals(relatives, decision.relatives());
    }

    @Test
    void positionCorrectionPreservesVanillaPositionVelocityAndRotateDelta() {
        PositionMoveRotation current = new PositionMoveRotation(
                new Vec3(10.0, 64.0, 10.0),
                new Vec3(0.40, 0.10, -0.20),
                90.0F,
                10.0F
        );
        PositionMoveRotation correction = new PositionMoveRotation(
                new Vec3(2.0, 70.0, -3.0),
                new Vec3(0.20, 0.30, 0.80),
                30.0F,
                5.0F
        );
        Set<Relative> relatives = Set.of(
                Relative.X,
                Relative.Z,
                Relative.Y_ROT,
                Relative.DELTA_X,
                Relative.ROTATE_DELTA
        );
        PositionMoveRotation vanillaAbsolute =
                PositionMoveRotation.calculateAbsolute(
                        current,
                        correction,
                        relatives
                );

        NoRotatePolicy26.PositionDecision decision =
                policy.decidePositionCorrection(
                        active(),
                        NoRotatePolicy26.Configuration.DEFAULT,
                        current,
                        correction,
                        relatives
                );

        assertTrue(decision.modified());
        assertEquals(Set.of(Relative.X, Relative.Z), decision.relatives());
        PositionMoveRotation downstreamAbsolute =
                PositionMoveRotation.calculateAbsolute(
                        current,
                        decision.correction(),
                        decision.relatives()
                );
        assertEquals(
                vanillaAbsolute.position(),
                downstreamAbsolute.position()
        );
        assertEquals(
                vanillaAbsolute.deltaMovement(),
                downstreamAbsolute.deltaMovement()
        );
        assertEquals(current.yRot(), downstreamAbsolute.yRot());
        assertEquals(current.xRot(), downstreamAbsolute.xRot());
        assertEquals(
                correction.position(),
                decision.correction().position()
        );
    }

    @Test
    void positionPolicyRetainsRawFlagsForUnpreservedCameraAxis() {
        PositionMoveRotation current = new PositionMoveRotation(
                new Vec3(10.0, 64.0, 10.0),
                new Vec3(0.4, 0.1, -0.2),
                90.0F,
                10.0F
        );
        PositionMoveRotation correction = new PositionMoveRotation(
                new Vec3(2.0, 1.0, -3.0),
                new Vec3(0.2, 0.3, 0.8),
                30.0F,
                5.0F
        );
        Set<Relative> relatives = Set.of(
                Relative.X,
                Relative.Y_ROT,
                Relative.X_ROT,
                Relative.DELTA_Z,
                Relative.ROTATE_DELTA
        );

        NoRotatePolicy26.PositionDecision decision =
                policy.decidePositionCorrection(
                        active(),
                        new NoRotatePolicy26.Configuration(true, false),
                        current,
                        correction,
                        relatives
                );

        assertEquals(
                Set.of(Relative.X, Relative.X_ROT),
                decision.relatives()
        );
        assertEquals(current.yRot(), decision.correction().yRot());
        assertEquals(correction.xRot(), decision.correction().xRot());
        PositionMoveRotation result =
                PositionMoveRotation.calculateAbsolute(
                        current,
                        decision.correction(),
                        decision.relatives()
                );
        PositionMoveRotation vanilla =
                PositionMoveRotation.calculateAbsolute(
                        current,
                        correction,
                        relatives
                );
        assertEquals(vanilla.position(), result.position());
        assertEquals(vanilla.deltaMovement(), result.deltaMovement());
        assertEquals(current.yRot(), result.yRot());
        assertEquals(vanilla.xRot(), result.xRot());
    }

    @Test
    void inactivePositionPolicyLeavesCorrectionAndFlagsUntouched() {
        PositionMoveRotation current = new PositionMoveRotation(
                Vec3.ZERO,
                Vec3.ZERO,
                20.0F,
                5.0F
        );
        PositionMoveRotation correction = new PositionMoveRotation(
                new Vec3(1.0, 2.0, 3.0),
                new Vec3(0.1, 0.2, 0.3),
                40.0F,
                10.0F
        );
        Set<Relative> relatives = Set.of(Relative.X, Relative.Y_ROT);

        NoRotatePolicy26.PositionDecision decision =
                policy.decidePositionCorrection(
                        new NoRotatePolicy26.Observation(
                                false, true, true, true
                        ),
                        NoRotatePolicy26.Configuration.DEFAULT,
                        current,
                        correction,
                        relatives
                );

        assertFalse(decision.modified());
        assertEquals(correction, decision.correction());
        assertEquals(relatives, decision.relatives());
    }

    private static NoRotatePolicy26.Observation active() {
        return new NoRotatePolicy26.Observation(true, true, true, true);
    }
}
