package dev.sealedclient.v26.integration;

/**
 * Narrow provider surface used by the navigator. There is intentionally no
 * command-manager or chat-control operation on this boundary.
 */
interface BaritoneAccess26 {
    Object createGoal(int x, int y, int z);

    Observation observe();

    void setGoalAndPath(Object goal);

    boolean cancelEverything();

    boolean isInGoal(Object goal, Position position);

    record Position(int x, int y, int z) {
    }

    record Observation(
            boolean customProcessActive,
            Object customGoal,
            Object pathingGoal,
            boolean pathing,
            boolean planning,
            Position playerFeet
    ) {
        boolean hasExternalGoal(Object ownedGoal) {
            return hasDistinctExternalGoal(ownedGoal)
                    || hasUnattributedActivity();
        }

        boolean hasDistinctExternalGoal(Object ownedGoal) {
            if (customProcessActive
                    && (ownedGoal == null || customGoal != ownedGoal)) {
                return true;
            }
            return pathingGoal != null && pathingGoal != ownedGoal;
        }

        boolean hasUnattributedActivity() {
            return !customProcessActive
                    && pathingGoal == null
                    && (pathing || planning);
        }

        boolean exactGoalPresent(Object ownedGoal) {
            if (ownedGoal == null) {
                return false;
            }
            if (customProcessActive) {
                return customGoal == ownedGoal;
            }
            return pathingGoal == ownedGoal;
        }

        boolean quiescent() {
            return !customProcessActive && !pathing && !planning;
        }
    }
}
