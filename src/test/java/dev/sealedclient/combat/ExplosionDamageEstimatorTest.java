package dev.sealedclient.combat;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ExplosionDamageEstimatorTest {
    @Test
    void rawCrystalDamageMatchesVanillaShapeAtCenter() {
        assertEquals(
                85.0,
                ExplosionDamageEstimator.rawExplosionDamage(0.0, 1.0, 6.0),
                0.0001
        );
    }

    @Test
    void rawCrystalDamageFallsWithDistanceAndExposure() {
        assertEquals(
                32.5,
                ExplosionDamageEstimator.rawExplosionDamage(6.0, 1.0, 6.0),
                0.0001
        );
        assertEquals(
                14.125,
                ExplosionDamageEstimator.rawExplosionDamage(6.0, 0.5, 6.0),
                0.0001
        );
        // A fully covered target still takes the minimum point. Asserting zero
        // here encoded the old behaviour, which a real explosion behind obsidian
        // disproved: it dealt damage where this predicted none.
        assertEquals(
                1.0,
                ExplosionDamageEstimator.rawExplosionDamage(6.0, 0.0, 6.0),
                0.0001
        );
        assertEquals(
                0.0,
                ExplosionDamageEstimator.rawExplosionDamage(12.0, 1.0, 6.0),
                0.0001
        );
    }

    @Test
    void difficultyScalingIsDeterministic() {
        assertEquals(0.0, ExplosionDamageEstimator.scaleForDifficulty(20.0, Difficulty.PEACEFUL));
        assertEquals(11.0, ExplosionDamageEstimator.scaleForDifficulty(20.0, Difficulty.EASY));
        assertEquals(20.0, ExplosionDamageEstimator.scaleForDifficulty(20.0, Difficulty.NORMAL));
        assertEquals(30.0, ExplosionDamageEstimator.scaleForDifficulty(20.0, Difficulty.HARD));
    }

    @Test
    void armorResistanceAndProtectionAreAppliedInOrder() {
        assertEquals(
                3.84,
                ExplosionDamageEstimator.applyReductions(20.0, 20.0, 8.0, 1, 10),
                0.0001
        );
    }

    @Test
    void reductionInputsAreClamped() {
        // Resistance V already removes everything, so any higher level stays at
        // zero rather than leaving the fifth of a point the old curve did.
        assertEquals(
                0.0,
                ExplosionDamageEstimator.applyReductions(20.0, 0.0, 0.0, 9, 99),
                0.0001
        );
        assertEquals(
                20.0,
                ExplosionDamageEstimator.applyReductions(20.0, -5.0, -2.0, -1, -1),
                0.0001
        );
    }
}
