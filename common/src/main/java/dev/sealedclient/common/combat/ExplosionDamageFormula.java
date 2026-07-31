package dev.sealedclient.common.combat;

import java.util.Objects;

/**
 * The vanilla explosion damage calculation, as a pure function.
 *
 * <p>Every step here mirrors what the server actually runs, so a prediction can
 * be checked against a real explosion rather than against another guess. The
 * accuracy game test measures live damage and compares it with this class; the
 * scenarios it records are pinned as a table test so the formula cannot drift
 * without a fast unit test noticing.</p>
 *
 * <p>The client cannot always see everything the server uses — enchantments on
 * another player's armour, for instance — so a prediction is only ever as good
 * as the inputs available. That limit belongs to the caller, not to this
 * class.</p>
 */
public final class ExplosionDamageFormula {
    /** Blast radius of an end crystal. */
    public static final float END_CRYSTAL_RADIUS = 6.0f;

    private ExplosionDamageFormula() {
    }

    /**
     * Raw explosion damage before any armour or effect reduction.
     *
     * <p>Mirrors {@code ExplosionDamageCalculator.getEntityDamageAmount}.</p>
     *
     * @param distance distance from the explosion centre to the entity position
     * @param exposure fraction of sample rays that reached the centre unblocked
     * @param radius   explosion radius, 6.0 for an end crystal
     */
    public static double rawDamage(double distance, double exposure, double radius) {
        double maxDistance = radius * 2.0;
        if (maxDistance <= 0.0) {
            return 0.0;
        }
        double distanceRatio = distance / maxDistance;
        if (distanceRatio >= 1.0) {
            return 0.0;
        }
        // Anything inside the blast radius takes at least one point, even with
        // every sample ray blocked. Returning zero for fully covered targets was
        // wrong by exactly that one point, which a real explosion behind a wall
        // measured as 0.210 against a prediction of 0.
        double impact = Math.max(0.0, (1.0 - distanceRatio) * exposure);
        return ((impact * impact + impact) / 2.0 * 7.0 * maxDistance) + 1.0;
    }

    /**
     * The number of exposure samples vanilla takes along one axis.
     *
     * <p>Vanilla derives its sample grid from the entity's bounding box rather
     * than using a fixed count, so a player is sampled far more densely than a
     * 2x2x2 approximation would suggest. Mirrors the step calculation in
     * {@code ServerExplosion.getSeenPercent}.</p>
     *
     * @param size the box extent along that axis
     */
    public static int sampleCount(double size) {
        double step = 1.0 / (size * 2.0 + 1.0);
        if (step < 0.0) {
            return 0;
        }
        return (int) Math.floor(1.0 / step) + 1;
    }

    /**
     * Exposure of a box to an explosion centre.
     *
     * <p>Casts vanilla's sample grid and returns the unblocked fraction. The
     * ray test is supplied by the caller so this stays free of world types.</p>
     *
     * @param unblocked returns {@code true} when a ray from the sample point to
     *                  the explosion centre is not interrupted
     */
    public static double exposure(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            RayTest unblocked
    ) {
        Objects.requireNonNull(unblocked, "unblocked");
        double stepX = 1.0 / ((maxX - minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((maxY - minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        if (stepX < 0.0 || stepY < 0.0 || stepZ < 0.0) {
            return 0.0;
        }
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;

        int visible = 0;
        int total = 0;
        for (double fx = 0.0; fx <= 1.0; fx += stepX) {
            for (double fy = 0.0; fy <= 1.0; fy += stepY) {
                for (double fz = 0.0; fz <= 1.0; fz += stepZ) {
                    double x = lerp(fx, minX, maxX) + offsetX;
                    double y = lerp(fy, minY, maxY);
                    double z = lerp(fz, minZ, maxZ) + offsetZ;
                    if (unblocked.test(x, y, z)) {
                        visible++;
                    }
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) visible / total;
    }

    /**
     * Damage remaining after armour points and toughness.
     *
     * <p>Mirrors {@code CombatRules.getDamageAfterAbsorb}.</p>
     */
    public static double afterArmor(double damage, double armor, double toughness) {
        if (damage <= 0.0) {
            return 0.0;
        }
        // Negative armour or toughness cannot exist on a real entity, but a
        // stale or hostile value must not be allowed to amplify the result:
        // unclamped, it reduces the divisor and returns more damage than the
        // blast actually deals.
        double safeArmor = Math.max(0.0, armor);
        double safeToughness = Math.max(0.0, toughness);
        double divisor = 2.0 + safeToughness / 4.0;
        double reduction = clamp(safeArmor - damage / divisor, safeArmor * 0.2, 20.0);
        return damage * (1.0 - reduction / 25.0);
    }

    /**
     * Damage remaining after Protection and Blast Protection.
     *
     * <p>Mirrors {@code CombatRules.getDamageAfterMagicAbsorb}. Blast
     * Protection counts double against explosions.</p>
     */
    public static double afterEnchantments(double damage, double protectionPoints) {
        if (damage <= 0.0) {
            return 0.0;
        }
        return damage * (1.0 - clamp(Math.max(0.0, protectionPoints), 0.0, 20.0) / 25.0);
    }

    /**
     * Damage remaining after the Resistance effect.
     *
     * <p>Mirrors {@code LivingEntity}: each level removes five twenty-fifths,
     * so Resistance V removes all of it.</p>
     *
     * @param resistanceLevel effect level, 0 when absent
     */
    public static double afterResistance(double damage, int resistanceLevel) {
        if (damage <= 0.0 || resistanceLevel <= 0) {
            return Math.max(0.0, damage);
        }
        int remaining = 25 - Math.min(25, resistanceLevel * 5);
        return Math.max(0.0, damage * remaining / 25.0);
    }

    /**
     * Full reduction chain in the order the server applies it.
     */
    public static double afterReductions(
            double rawDamage,
            double armor,
            double toughness,
            double protectionPoints,
            int resistanceLevel
    ) {
        double damage = afterArmor(rawDamage, armor, toughness);
        damage = afterResistance(damage, resistanceLevel);
        return afterEnchantments(damage, protectionPoints);
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    /** Ray test from a sample point on the entity box to the explosion centre. */
    @FunctionalInterface
    public interface RayTest {
        boolean test(double x, double y, double z);
    }
}
