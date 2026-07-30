package dev.sealedclient.v26.mixin.movement;

import dev.sealedclient.v26.movement.NoRotatePolicy26;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPacketListenerNoRotateMixin26Test {
    @Test
    void falseAxisUsesVanillaSetterAndTrueAxisSuppressesIt() {
        assertTrue(
                NoRotatePolicy26.shouldApplyServerRotation(false)
        );
        assertFalse(
                NoRotatePolicy26.shouldApplyServerRotation(true)
        );
    }

    @Test
    void unchangedPositionDecisionDoesNotRewriteMixinArguments() {
        NoRotatePolicy26.PositionDecision noOp =
                new NoRotatePolicy26.PositionDecision(
                        new PositionMoveRotation(
                                Vec3.ZERO,
                                Vec3.ZERO,
                                0.0F,
                                0.0F
                        ),
                        Set.of(),
                        false
                );
        NoRotatePolicy26.PositionDecision modified =
                new NoRotatePolicy26.PositionDecision(
                        noOp.correction(),
                        Set.of(),
                        true
                );

        assertFalse(
                NoRotatePolicy26.shouldReplacePositionArguments(noOp)
        );
        assertTrue(
                NoRotatePolicy26.shouldReplacePositionArguments(modified)
        );
    }
}
