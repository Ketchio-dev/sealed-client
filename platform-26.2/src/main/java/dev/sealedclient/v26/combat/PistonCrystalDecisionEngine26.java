package dev.sealedclient.v26.combat;

import java.util.List;
import java.util.Set;

/**
 * Pure bounded layout selection and reflected-state sequence for Piston
 * Crystal. Positions are immutable integer cells so tests need no live world.
 */
final class PistonCrystalDecisionEngine26 {
    private PistonCrystalDecisionEngine26() {
    }

    static long selectBest(List<Layout> layouts, Limits limits) {
        if (layouts == null || limits == null) {
            return -1L;
        }
        long selected = -1L;
        double selectedInteractionDistance = Double.POSITIVE_INFINITY;
        double selectedTargetDistance = Double.POSITIVE_INFINITY;
        int examined = 0;
        for (Layout layout : layouts) {
            if (examined++ >= limits.maximumScans()) {
                break;
            }
            if (!valid(layout, limits)) {
                continue;
            }
            if (layout.interactionDistance()
                    < selectedInteractionDistance
                    || (layout.interactionDistance()
                    == selectedInteractionDistance
                    && layout.targetDistance() < selectedTargetDistance)
                    || (layout.interactionDistance()
                    == selectedInteractionDistance
                    && layout.targetDistance() == selectedTargetDistance
                    && (selected < 0L || layout.key() < selected))) {
                selected = layout.key();
                selectedInteractionDistance = layout.interactionDistance();
                selectedTargetDistance = layout.targetDistance();
            }
        }
        return selected;
    }

    static boolean valid(Layout layout, Limits limits) {
        return layout != null
                && layout.key() >= 0L
                && layout.targetId() >= 0
                && layout.base() != null
                && layout.piston() != null
                && layout.power() != null
                && layout.facing() != null
                && finiteNonNegative(layout.targetDistance())
                && finiteNonNegative(layout.interactionDistance())
                && layout.targetDistance() <= limits.targetRange()
                && layout.interactionDistance() <= limits.placeRange()
                && layout.targetValid()
                && !layout.friend()
                && layout.lineOfSight()
                && layout.baseValid()
                && layout.crystalSpaceClear()
                && layout.pistonSpaceClear()
                && layout.pistonSupport()
                && layout.powerSpaceClear()
                && layout.validOrientation()
                && layout.piston().equals(
                layout.base()
                        .offset(layout.facing().opposite(), 1)
                        .above()
        )
                && layout.power().equals(layout.piston().above())
                && layout.explosionSafe();
    }

    /**
     * Accepts only a newly reflected crystal at the exact planned base.
     * Nearby crystals observed before the transaction can never become owned
     * by this sequence.
     */
    static boolean acceptsPlacedCrystal(
            Set<Integer> preexistingEntityIds,
            int entityId,
            double expectedDistanceSquared,
            boolean awaitingPlacementConfirmation
    ) {
        return preexistingEntityIds != null
                && entityId >= 0
                && awaitingPlacementConfirmation
                && !preexistingEntityIds.contains(entityId)
                && Double.isFinite(expectedDistanceSquared)
                && expectedDistanceSquared >= 0.0
                && expectedDistanceSquared <= 0.36;
    }

    /**
     * Environmental cleanup is authorized only for a placement sent by this
     * transaction and subsequently reflected as the exact expected block.
     */
    static boolean ownsPlacedBlock(
            boolean placementSent,
            boolean exactBlockReflected
    ) {
        return placementSent && exactBlockReflected;
    }

    static PlacementOwnership observeOwnership(
            PlacementOwnership current,
            boolean placementSent,
            boolean exactBlockReflected
    ) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "Current ownership is required"
            );
        }
        if (current.revoked()) {
            return current;
        }
        if (current.confirmed() && !exactBlockReflected) {
            return new PlacementOwnership(false, true);
        }
        if (placementSent && exactBlockReflected) {
            return new PlacementOwnership(true, false);
        }
        return current;
    }

    static boolean withinRange(
            double distanceSquared,
            double maximumRange
    ) {
        return Double.isFinite(distanceSquared)
                && distanceSquared >= 0.0
                && Double.isFinite(maximumRange)
                && maximumRange > 0.0
                && distanceSquared <= maximumRange * maximumRange;
    }

    static CleanupDirective cleanupDirective(
            boolean ownedBlockPresent,
            boolean timedOut,
            boolean manualSlotOverride,
            boolean destroyStarted
    ) {
        if (!ownedBlockPresent || timedOut) {
            return CleanupDirective.ADVANCE;
        }
        if (manualSlotOverride) {
            return CleanupDirective.ABANDON;
        }
        return destroyStarted
                ? CleanupDirective.CONTINUE
                : CleanupDirective.START;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    record Cell(int x, int y, int z) {
        Cell offset(Horizontal direction, int amount) {
            return new Cell(
                    x + direction.dx() * amount,
                    y,
                    z + direction.dz() * amount
            );
        }

        Cell above() {
            return new Cell(x, y + 1, z);
        }
    }

    static ExplosionPoint explosionPoint(Cell base, Horizontal facing) {
        if (base == null || facing == null) {
            throw new IllegalArgumentException(
                    "Explosion base and facing are required"
            );
        }
        return new ExplosionPoint(
                base.x() + 0.5 + facing.dx(),
                base.y() + 1.0,
                base.z() + 0.5 + facing.dz()
        );
    }

    record ExplosionPoint(double x, double y, double z) {
    }

    enum CleanupDirective {
        START,
        CONTINUE,
        ADVANCE,
        ABANDON
    }

    record PlacementOwnership(boolean confirmed, boolean revoked) {
        PlacementOwnership {
            if (confirmed && revoked) {
                throw new IllegalArgumentException(
                        "Revoked placement cannot remain confirmed"
                );
            }
        }

        static PlacementOwnership unconfirmed() {
            return new PlacementOwnership(false, false);
        }

        boolean owned() {
            return confirmed && !revoked;
        }
    }

    enum Horizontal {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int dx;
        private final int dz;

        Horizontal(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        int dx() {
            return dx;
        }

        int dz() {
            return dz;
        }

        Horizontal opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case EAST -> WEST;
                case SOUTH -> NORTH;
                case WEST -> EAST;
            };
        }
    }

    record Layout(
            long key,
            int targetId,
            Cell base,
            Cell piston,
            Cell power,
            Horizontal facing,
            double targetDistance,
            double interactionDistance,
            boolean targetValid,
            boolean friend,
            boolean lineOfSight,
            boolean baseValid,
            boolean crystalSpaceClear,
            boolean pistonSpaceClear,
            boolean pistonSupport,
            boolean powerSpaceClear,
            boolean validOrientation,
            boolean explosionSafe
    ) {
    }

    record Limits(
            int maximumScans,
            double targetRange,
            double placeRange
    ) {
        Limits {
            if (maximumScans <= 0
                    || !Double.isFinite(targetRange)
                    || targetRange <= 0.0
                    || !Double.isFinite(placeRange)
                    || placeRange <= 0.0) {
                throw new IllegalArgumentException("Invalid piston limits");
            }
        }
    }

    /**
     * A mutation is followed by an exact reflected-state wait. Timeout exposes
     * a stable RETRY directive until the adapter actually resends the action.
     */
    static final class Sequence {
        private final int timeoutTicks;
        private final int maximumRetries;
        private Stage stage = Stage.IDLE;
        private long deadline;
        private int retries;

        Sequence(int timeoutTicks, int maximumRetries) {
            if (timeoutTicks <= 0 || maximumRetries < 0) {
                throw new IllegalArgumentException("Invalid piston sequence");
            }
            this.timeoutTicks = timeoutTicks;
            this.maximumRetries = maximumRetries;
        }

        boolean begin() {
            if (stage != Stage.IDLE) {
                return false;
            }
            stage = Stage.PLACE_PISTON;
            retries = 0;
            deadline = 0L;
            return true;
        }

        Directive directive(long tick, Observation observation) {
            if (tick < 0L || observation == null) {
                return Directive.NONE;
            }
            if (stage.mutation()) {
                return Directive.ACT;
            }
            if (!stage.waiting()) {
                return stage == Stage.COMPLETE
                        ? Directive.COMPLETE
                        : stage == Stage.ABORTED
                        ? Directive.ABORT
                        : Directive.NONE;
            }
            if (confirmed(stage, observation)) {
                advanceConfirmed();
                return stage == Stage.COMPLETE
                        ? Directive.COMPLETE
                        : Directive.ACT;
            }
            if (tick < deadline) {
                return Directive.WAIT;
            }
            if (retries >= maximumRetries) {
                stage = Stage.ABORTED;
                return Directive.ABORT;
            }
            return Directive.RETRY;
        }

        boolean markActed(long tick) {
            if (!stage.mutation() || tick < 0L) {
                return false;
            }
            stage = stage.waitStage();
            deadline = add(tick, timeoutTicks);
            return true;
        }

        boolean markRetried(long tick) {
            if (!stage.waiting() || tick < 0L) {
                return false;
            }
            retries++;
            deadline = add(tick, timeoutTicks);
            return true;
        }

        void abort() {
            if (stage != Stage.IDLE && stage != Stage.COMPLETE) {
                stage = Stage.ABORTED;
            }
        }

        void reset() {
            stage = Stage.IDLE;
            deadline = 0L;
            retries = 0;
        }

        Snapshot snapshot() {
            return new Snapshot(stage, deadline, retries);
        }

        private void advanceConfirmed() {
            retries = 0;
            deadline = 0L;
            stage = switch (stage) {
                case WAIT_PISTON -> Stage.PLACE_CRYSTAL;
                case WAIT_CRYSTAL -> Stage.PLACE_POWER;
                case WAIT_POWER -> Stage.BREAK_CRYSTAL;
                case WAIT_BREAK -> Stage.COMPLETE;
                default -> stage;
            };
        }

        private static boolean confirmed(
                Stage stage,
                Observation observation
        ) {
            return switch (stage) {
                case WAIT_PISTON -> observation.pistonCorrect();
                case WAIT_CRYSTAL -> observation.crystalPresent();
                case WAIT_POWER -> observation.powerPresent()
                        && observation.pistonExtended();
                case WAIT_BREAK -> observation.crystalGone();
                default -> false;
            };
        }

        private static long add(long tick, int amount) {
            return tick > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE
                    : tick + amount;
        }

        enum Stage {
            IDLE(false, false),
            PLACE_PISTON(true, false),
            WAIT_PISTON(false, true),
            PLACE_CRYSTAL(true, false),
            WAIT_CRYSTAL(false, true),
            PLACE_POWER(true, false),
            WAIT_POWER(false, true),
            BREAK_CRYSTAL(true, false),
            WAIT_BREAK(false, true),
            COMPLETE(false, false),
            ABORTED(false, false);

            private final boolean mutation;
            private final boolean waiting;

            Stage(boolean mutation, boolean waiting) {
                this.mutation = mutation;
                this.waiting = waiting;
            }

            boolean mutation() {
                return mutation;
            }

            boolean waiting() {
                return waiting;
            }

            Stage waitStage() {
                return switch (this) {
                    case PLACE_PISTON -> WAIT_PISTON;
                    case PLACE_CRYSTAL -> WAIT_CRYSTAL;
                    case PLACE_POWER -> WAIT_POWER;
                    case BREAK_CRYSTAL -> WAIT_BREAK;
                    default -> this;
                };
            }
        }

        enum Directive {
            NONE,
            ACT,
            WAIT,
            RETRY,
            COMPLETE,
            ABORT
        }

        record Observation(
                boolean pistonCorrect,
                boolean crystalPresent,
                boolean powerPresent,
                boolean pistonExtended,
                boolean crystalGone
        ) {
            static Observation none() {
                return new Observation(false, false, false, false, false);
            }
        }

        record Snapshot(Stage stage, long deadline, int retries) {
        }
    }
}
