package dev.sealedclient.common.render;

/**
 * Packed ARGB colour arithmetic.
 *
 * <p>Extracted from the render primitives so it can be tested without a
 * graphics context. Every version of the render pipeline this client has
 * targeted needs the same four floats; only the call that consumes them
 * changes.</p>
 */
public final class ArgbColor {
    private ArgbColor() {
    }

    /** Red channel, 0 to 1. */
    public static float red(int argb) {
        return (argb >>> 16 & 0xFF) / 255.0f;
    }

    /** Green channel, 0 to 1. */
    public static float green(int argb) {
        return (argb >>> 8 & 0xFF) / 255.0f;
    }

    /** Blue channel, 0 to 1. */
    public static float blue(int argb) {
        return (argb & 0xFF) / 255.0f;
    }

    /**
     * Alpha channel scaled by a factor, clamped to the visible range.
     *
     * <p>A scale above one would otherwise wrap a nearly opaque colour into a
     * transparent one, and a negative scale would produce a colour the
     * pipeline rejects.</p>
     */
    public static float alpha(int argb, float scale) {
        if (!Float.isFinite(scale)) {
            return 0.0f;
        }
        float scaled = (argb >>> 24 & 0xFF) / 255.0f * scale;
        return Math.max(0.0f, Math.min(1.0f, scaled));
    }

    /** The same colour forced to full opacity. */
    public static int opaque(int argb) {
        return argb | 0xFF000000;
    }
}
