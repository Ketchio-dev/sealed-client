package dev.b2tclient.combat;

public final class CrystalScoring {
    private CrystalScoring() {
    }

    public static boolean acceptable(
            double targetDamage,
            double selfDamage,
            double selfHealth,
            double minDamage,
            double maxSelfDamage,
            boolean facePlace
    ) {
        return targetDamage > 0.0
                && selfDamage <= maxSelfDamage
                && selfDamage + 0.5 < selfHealth
                && (targetDamage >= minDamage || facePlace);
    }

    public static double score(
            double targetDamage,
            double selfDamage,
            double targetDistance,
            double actionDistance,
            double selfWeight
    ) {
        return targetDamage
                - selfDamage * selfWeight
                - Math.max(0.0, targetDistance) * 0.08
                - Math.max(0.0, actionDistance) * 0.03;
    }

    public static double targetPriority(
            double distance,
            double health,
            double armor,
            boolean facePlace
    ) {
        return Math.max(0.0, distance)
                + Math.max(0.0, health) * 0.08
                + Math.max(0.0, armor) * 0.02
                - (facePlace ? 2.0 : 0.0);
    }
}
