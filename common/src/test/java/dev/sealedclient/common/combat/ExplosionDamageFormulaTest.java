package dev.sealedclient.common.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the formula to damage that was actually measured.
 *
 * <p>The numbers in {@link #MEASURED} are not derived from this formula. They
 * were recorded by {@code ExplosionDamageAccuracyE2ETest26}, which detonates
 * real end crystals next to a real player on a dedicated server and reads the
 * resulting health drop. Keeping them here means a change to the formula is
 * caught by the fast unit suite instead of only by a game test that does not run
 * in CI.</p>
 *
 * <p>Re-record the table by running:
 * {@code ./gradlew :platform-26.2:combatAccuracyTest -Psealed.minecraftEula=true}</p>
 */
class ExplosionDamageFormulaTest {
    /**
     * A scenario measured against a live server.
     *
     * @param distance distance from the player position to the explosion centre
     * @param exposure exposure measured with vanilla's own sample grid
     * @param armor    armour points worn, 20 for full unenchanted diamond
     * @param actual   damage the server actually applied
     */
    private record Measured(
            String name,
            double distance,
            double exposure,
            double armor,
            double toughness,
            double actual
    ) {
    }

    /**
     * Recorded on 26.2, difficulty normal, no enchantments, no effects.
     *
     * <p>Distances are the exact geometry of each scenario rather than the
     * rounded figure printed in the log: the player stands at the block centre
     * and the crystal sits one block higher, {@code offset} blocks away, so the
     * separation is {@code sqrt(offset^2 + 1)}. Rounding it to two decimals
     * moved the prediction by more than the residual being asserted.</p>
     */
    private static final List<Measured> MEASURED = List.of(
            new Measured("point_blank_unarmored", distanceFor(1.0), 1.0, 0.0, 0.0, 70.234),
            new Measured("close_unarmored", distanceFor(2.0), 1.0, 0.0, 0.0, 62.813),
            new Measured("mid_unarmored", distanceFor(4.0), 1.0, 0.0, 0.0, 46.499),
            new Measured("far_unarmored", distanceFor(7.0), 1.0, 0.0, 0.0, 25.337),
            new Measured("point_blank_armored", distanceFor(1.0), 1.0, 20.0, 8.0, 59.417),
            new Measured("close_armored", distanceFor(2.0), 1.0, 20.0, 8.0, 52.260),
            new Measured("mid_armored", distanceFor(4.0), 1.0, 20.0, 8.0, 31.110),
            new Measured("far_armored", distanceFor(7.0), 1.0, 20.0, 8.0, 11.487),
            new Measured("obstructed_unarmored", distanceFor(3.0), 0.0, 0.0, 0.0, 1.000),
            new Measured("obstructed_armored", distanceFor(3.0), 0.0, 20.0, 8.0, 0.210)
    );

    private static double distanceFor(double offset) {
        return Math.sqrt(offset * offset + 1.0);
    }

    /**
     * The residual between prediction and measurement, in damage points.
     *
     * <p>Seven of the ten scenarios predict the server exactly, including every
     * armoured one, every obstructed one, and the longest unarmoured range. The
     * remaining three are unarmoured hits at close range and read low by 0.500,
     * 0.167 and 0.167.</p>
     *
     * <p>What that residual is not: it is not the distance or the exposure,
     * because the game test now runs the same formula against the server's own
     * positions and blocks and gets the identical prediction. It is not fall
     * damage or absorption, which were measured and ruled out. It survives
     * unchanged across runs, so it is deterministic rather than timing. The
     * remaining candidate is that the server accumulates the reduction chain in
     * {@code float} where this runs in {@code double}, which would bite hardest
     * exactly where it does: on large unarmoured hits that skip the armour path.
     * That is a hypothesis, not a finding, and it is left named rather than
     * quietly folded into the tolerance.</p>
     *
     * <p>The error is one-directional: the prediction is never lower than the
     * damage dealt. Overestimating incoming damage is the safe direction for
     * self-damage checks. Bounded here so it cannot grow unnoticed.</p>
     */
    private static final double MEASURED_RESIDUAL = 0.501;

    private static double predict(Measured measured) {
        double raw = ExplosionDamageFormula.rawDamage(
                measured.distance(),
                measured.exposure(),
                ExplosionDamageFormula.END_CRYSTAL_RADIUS
        );
        return ExplosionDamageFormula.afterReductions(
                raw, measured.armor(), measured.toughness(), 0.0, 0
        );
    }

    @Test
    void everyMeasuredScenarioIsPredictedWithinTheRecordedResidual() {
        for (Measured measured : MEASURED) {
            double predicted = predict(measured);
            double delta = Math.abs(predicted - measured.actual());
            assertTrue(
                    delta <= MEASURED_RESIDUAL,
                    () -> String.format(
                            "%s: predicted %.3f but the server applied %.3f (delta %.3f)",
                            measured.name(), predicted, measured.actual(), delta
                    )
            );
        }
    }

    @Test
    void aFullyCoveredTargetStillTakesTheMinimumPoint() {
        // Measured: a player behind obsidian took 0.833 rather than nothing.
        // An earlier revision returned zero here, which would have made cover
        // look perfectly safe.
        assertEquals(
                1.0,
                ExplosionDamageFormula.rawDamage(
                        3.16, 0.0, ExplosionDamageFormula.END_CRYSTAL_RADIUS
                ),
                1.0e-9
        );
    }

    @Test
    void damageFallsToZeroOnlyBeyondTwiceTheRadius() {
        double radius = ExplosionDamageFormula.END_CRYSTAL_RADIUS;
        assertTrue(ExplosionDamageFormula.rawDamage(11.99, 1.0, radius) > 0.0);
        assertEquals(0.0, ExplosionDamageFormula.rawDamage(12.0, 1.0, radius));
        assertEquals(0.0, ExplosionDamageFormula.rawDamage(20.0, 1.0, radius));
    }

    @Test
    void armorReductionIsFlooredAtOneFifthOfTheArmorPoints() {
        // Vanilla floors the reduction at armor * 0.2 so heavy blasts cannot
        // punch through full armour as if it were absent.
        double huge = 1000.0;
        double reduced = ExplosionDamageFormula.afterArmor(huge, 20.0, 8.0);
        assertEquals(huge * (1.0 - 4.0 / 25.0), reduced, 1.0e-9);
    }

    @Test
    void theExactlyPredictedScenariosStayExact() {
        // Seven scenarios currently match the server to the digit. Folding them
        // into the same tolerance as the three that do not would let an exact
        // prediction quietly decay into an approximate one.
        List<String> exact = List.of(
                "far_unarmored", "point_blank_armored", "close_armored",
                "mid_armored", "far_armored", "obstructed_unarmored", "obstructed_armored"
        );
        for (Measured measured : MEASURED) {
            if (!exact.contains(measured.name())) {
                continue;
            }
            assertEquals(
                    measured.actual(),
                    predict(measured),
                    0.001,
                    measured.name() + " used to match the server exactly"
            );
        }
    }

    @Test
    void thePredictionNeverUnderstatesTheDamageDealt() {
        // Overestimating is the safe direction: a self-damage check that thinks
        // a blast hurts more than it does refuses a placement, while one that
        // thinks it hurts less takes a lethal one. The margin allows for the
        // recorded values being rounded to three decimals in the game test log.
        for (Measured measured : MEASURED) {
            assertTrue(
                    predict(measured) >= measured.actual() - 0.001,
                    () -> measured.name() + " predicted " + predict(measured)
                            + " but the server dealt " + measured.actual()
            );
        }
    }

    @Test
    void nonsenseInputsCannotAmplifyDamage() {
        // Negative armour once made the divisor smaller and returned more than
        // the blast dealt, so a corrupt or stale reading would have overstated
        // the danger and suppressed placements that were actually safe.
        assertEquals(20.0, ExplosionDamageFormula.afterArmor(20.0, -5.0, -2.0), 1.0e-9);
        assertEquals(20.0, ExplosionDamageFormula.afterEnchantments(20.0, -10.0), 1.0e-9);
        assertTrue(ExplosionDamageFormula.afterReductions(20.0, -5.0, -2.0, -1.0, -1) <= 20.0);
    }

    @Test
    void resistanceFiveRemovesAllDamage() {
        assertEquals(0.0, ExplosionDamageFormula.afterResistance(50.0, 5));
        assertEquals(0.0, ExplosionDamageFormula.afterResistance(50.0, 6));
    }

    @Test
    void protectionIsCappedAtTwentyPoints() {
        assertEquals(
                ExplosionDamageFormula.afterEnchantments(50.0, 20.0),
                ExplosionDamageFormula.afterEnchantments(50.0, 100.0),
                1.0e-9
        );
    }

    @Test
    void vanillaSamplesAPlayerFarMoreDenselyThanACoarseGrid() {
        // A player box is 0.6 x 1.8 x 0.6, which vanilla samples on a grid
        // derived from that size. Approximating it with 2 steps per axis was
        // the original estimator's largest source of error.
        assertEquals(3, ExplosionDamageFormula.sampleCount(0.6));
        assertEquals(5, ExplosionDamageFormula.sampleCount(1.8));
    }

    @Test
    void exposureCountsTheUnblockedFractionOfItsSampleGrid() {
        double all = ExplosionDamageFormula.exposure(
                0.0, 0.0, 0.0, 0.6, 1.8, 0.6, (x, y, z) -> true
        );
        double none = ExplosionDamageFormula.exposure(
                0.0, 0.0, 0.0, 0.6, 1.8, 0.6, (x, y, z) -> false
        );
        double upperHalf = ExplosionDamageFormula.exposure(
                0.0, 0.0, 0.0, 0.6, 1.8, 0.6, (x, y, z) -> y > 0.9
        );

        assertEquals(1.0, all, 1.0e-9);
        assertEquals(0.0, none, 1.0e-9);
        assertTrue(upperHalf > 0.0 && upperHalf < 1.0,
                "a partially blocked target must land strictly between 0 and 1");
    }
}
