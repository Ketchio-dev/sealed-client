package dev.b2tclient.v26.visual;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable settings boundary for the seven 26.2 world overlays.
 *
 * <p>The platform settings adapter can rebuild this value when persisted
 * settings change. Runtime code consumes one coherent snapshot, avoiding
 * partially updated render state while the extraction and submission phases
 * are separated by Minecraft 26.2's renderer.</p>
 */
public record VisualOverlayConfiguration26(
        PlayerEsp playerEsp,
        Tracers tracers,
        Nametags nametags,
        StorageEsp storageEsp,
        HoleEsp holeEsp,
        BlockEsp blockEsp,
        Trajectories trajectories
) {
    public static final int MAX_ENTITY_RENDER_CAP = 256;
    /**
     * Per-overlay marker limit. Three world overlays plus the entity budget
     * fit below the renderer's global 8,192-box frame ceiling, so enabling one
     * feature cannot starve every overlay registered after it.
     */
    public static final int MAX_WORLD_RENDER_CAP = 2_560;
    public static final int MAX_SCAN_BUDGET = 16_384;

    public static final VisualOverlayConfiguration26 DISABLED =
            new VisualOverlayConfiguration26(
                    new PlayerEsp(
                            false,
                            128.0,
                            0xCC55AAFF,
                            0xCC55FF88,
                            true,
                            false,
                            true,
                            true,
                            128
                    ),
                    new Tracers(
                            false,
                            192.0,
                            0xDDFF6666,
                            0xDD55FF88,
                            true,
                            false,
                            1.5F,
                            128
                    ),
                    new Nametags(
                            false,
                            128.0,
                            0xFFFFFFFF,
                            0xFF55FF88,
                            0x99000000,
                            true,
                            false,
                            true,
                            true,
                            true,
                            1.0F,
                            96
                    ),
                    new StorageEsp(
                            false,
                            96,
                            0xCCFFB52E,
                            true,
                            2_048
                    ),
                    new HoleEsp(
                            false,
                            24,
                            0xCC32D26E,
                            0xCCE4B640,
                            0xCCD94A4A,
                            false,
                            1_024,
                            1_024
                    ),
                    new BlockEsp(
                            false,
                            Set.of(
                                    "minecraft:ancient_debris",
                                    "minecraft:nether_portal",
                                    "minecraft:end_portal_frame"
                            ),
                            64,
                            2_048,
                            0xCC9B59FF,
                            2_560
                    ),
                    new Trajectories(
                            false,
                            96.0,
                            120,
                            0xE65AE6FF
                    )
            );

    public VisualOverlayConfiguration26 {
        Objects.requireNonNull(playerEsp, "playerEsp");
        Objects.requireNonNull(tracers, "tracers");
        Objects.requireNonNull(nametags, "nametags");
        Objects.requireNonNull(storageEsp, "storageEsp");
        Objects.requireNonNull(holeEsp, "holeEsp");
        Objects.requireNonNull(blockEsp, "blockEsp");
        Objects.requireNonNull(trajectories, "trajectories");
    }

    public boolean anyEnabled() {
        return playerEsp.enabled()
                || tracers.enabled()
                || nametags.enabled()
                || storageEsp.enabled()
                || holeEsp.enabled()
                || blockEsp.enabled()
                || trajectories.enabled();
    }

    public boolean anyEntityOverlayEnabled() {
        return playerEsp.enabled() || tracers.enabled() || nametags.enabled();
    }

    public boolean anyScanOverlayEnabled() {
        return storageEsp.enabled() || holeEsp.enabled() || blockEsp.enabled();
    }

    public record PlayerEsp(
            boolean enabled,
            double range,
            int playerColor,
            int friendColor,
            boolean showFriends,
            boolean showSelf,
            boolean fill,
            boolean outline,
            int renderCap
    ) {
        public PlayerEsp {
            range = validateDistance(range, 16.0, 512.0, "playerEsp.range");
            renderCap = validateCap(
                    renderCap,
                    MAX_ENTITY_RENDER_CAP,
                    "playerEsp.renderCap"
            );
        }
    }

    public record Tracers(
            boolean enabled,
            double range,
            int playerColor,
            int friendColor,
            boolean showFriends,
            boolean showSelf,
            float lineWidth,
            int renderCap
    ) {
        public Tracers {
            range = validateDistance(range, 16.0, 512.0, "tracers.range");
            if (!Float.isFinite(lineWidth)
                    || lineWidth < 0.5F
                    || lineWidth > 4.0F) {
                throw new IllegalArgumentException(
                        "tracers.lineWidth must be in [0.5, 4.0]"
                );
            }
            renderCap = validateCap(
                    renderCap,
                    MAX_ENTITY_RENDER_CAP,
                    "tracers.renderCap"
            );
        }
    }

    public record Nametags(
            boolean enabled,
            double range,
            int playerColor,
            int friendColor,
            int backgroundColor,
            boolean showFriends,
            boolean showSelf,
            boolean showHealth,
            boolean showDistance,
            boolean showEquipment,
            float scale,
            int renderCap
    ) {
        public Nametags {
            range = validateDistance(range, 16.0, 512.0, "nametags.range");
            if (!Float.isFinite(scale) || scale < 0.5F || scale > 2.5F) {
                throw new IllegalArgumentException(
                        "nametags.scale must be in [0.5, 2.5]"
                );
            }
            renderCap = validateCap(
                    renderCap,
                    MAX_ENTITY_RENDER_CAP,
                    "nametags.renderCap"
            );
        }
    }

    public record StorageEsp(
            boolean enabled,
            int range,
            int color,
            boolean includeShulkers,
            int renderCap
    ) {
        public StorageEsp {
            range = validateInt(range, 16, 256, "storageEsp.range");
            renderCap = validateCap(
                    renderCap,
                    MAX_WORLD_RENDER_CAP,
                    "storageEsp.renderCap"
            );
        }
    }

    public record HoleEsp(
            boolean enabled,
            int range,
            int safeColor,
            int mixedColor,
            int unsafeColor,
            boolean showUnsafe,
            int scanBudget,
            int renderCap
    ) {
        public HoleEsp {
            range = validateInt(range, 4, 64, "holeEsp.range");
            scanBudget = validateInt(
                    scanBudget,
                    128,
                    MAX_SCAN_BUDGET,
                    "holeEsp.scanBudget"
            );
            renderCap = validateCap(
                    renderCap,
                    MAX_WORLD_RENDER_CAP,
                    "holeEsp.renderCap"
            );
        }
    }

    public record BlockEsp(
            boolean enabled,
            Set<String> targets,
            int range,
            int scanBudget,
            int color,
            int renderCap
    ) {
        public BlockEsp {
            targets = Set.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.size() > 128
                    || targets.stream().anyMatch(
                            target -> target == null
                                    || target.isBlank()
                                    || target.length() > 128
                    )) {
                throw new IllegalArgumentException(
                        "blockEsp.targets must contain at most 128 valid identifiers"
                );
            }
            range = validateInt(range, 8, 192, "blockEsp.range");
            scanBudget = validateInt(
                    scanBudget,
                    128,
                    MAX_SCAN_BUDGET,
                    "blockEsp.scanBudget"
            );
            renderCap = validateCap(
                    renderCap,
                    MAX_WORLD_RENDER_CAP,
                    "blockEsp.renderCap"
            );
        }
    }

    public record Trajectories(
            boolean enabled,
            double range,
            int steps,
            int color
    ) {
        public Trajectories {
            range = validateDistance(
                    range,
                    16.0,
                    256.0,
                    "trajectories.range"
            );
            steps = validateInt(steps, 20, 320, "trajectories.steps");
        }
    }

    private static int validateCap(int value, int maximum, String label) {
        return validateInt(value, 0, maximum, label);
    }

    private static int validateInt(
            int value,
            int minimum,
            int maximum,
            String label
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " must be in [" + minimum + ", " + maximum + "]"
            );
        }
        return value;
    }

    private static double validateDistance(
            double value,
            double minimum,
            double maximum,
            String label
    ) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " must be in [" + minimum + ", " + maximum + "]"
            );
        }
        return value;
    }
}
