package dev.sealedclient.common.item;

/**
 * Remaining durability as a whole percentage.
 *
 * <p>Both platforms compute this and they disagreed: one rounded to the
 * nearest percent, the other truncated. On a diamond chestplate that is a
 * different answer for half of all durability values, which moves the point
 * where auto-repair triggers. Rounding is kept because it is the honest
 * reading of "how full is this" and because it is what the older platform
 * already did, so adopting it changes nothing for existing users.</p>
 */
public final class DurabilityPercent {
    private DurabilityPercent() {
    }

    /**
     * @param remaining durability left, clamped into {@code [0, maximum]}
     * @param maximum   total durability; non-positive means indestructible
     * @return 0 to 100, and 100 for anything that cannot be damaged
     */
    public static int of(int remaining, int maximum) {
        if (maximum <= 0) {
            return 100;
        }
        int bounded = Math.max(0, Math.min(maximum, remaining));
        return Math.round(bounded * 100.0f / maximum);
    }

    /** The same value computed from damage taken rather than durability left. */
    public static int fromDamage(int damage, int maximum) {
        if (maximum <= 0) {
            return 100;
        }
        return of(maximum - damage, maximum);
    }
}
