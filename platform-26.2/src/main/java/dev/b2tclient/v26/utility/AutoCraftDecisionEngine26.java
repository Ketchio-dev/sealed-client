package dev.b2tclient.v26.utility;

import java.util.Objects;

/**
 * Pure crafting-table lifecycle for place, output pickup, and confirmation.
 */
public final class AutoCraftDecisionEngine26 {
    private Configuration configuration;
    private Object sessionIdentity;
    private Phase phase = Phase.IDLE;
    private Candidate pendingCandidate;
    private int cooldownTicks;
    private int pendingAge;
    private int completedCrafts;
    private Decision lastDecision = Decision.none(BlockReason.INACTIVE);

    public AutoCraftDecisionEngine26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    public Decision step(Observation observation) {
        Observation current = Objects.requireNonNull(
                observation,
                "observation"
        );
        if (current.sessionIdentity() != sessionIdentity) {
            resetSession();
            sessionIdentity = current.sessionIdentity();
        }
        if (!current.enabled()
                && current.sessionIdentity() != null
                && current.sessionReady()
                && current.craftingScreen()
                && current.playerAlive()) {
            if (phase == Phase.AWAITING_PICKUP) {
                if (!current.safetyReady()) {
                    return remember(Decision.none(BlockReason.SAFETY));
                }
                return evaluatePickupConfirmation(current);
            }
            abandonPending();
            cooldownTicks = 0;
            return remember(Decision.none(BlockReason.DISABLED));
        }
        if (!baseLifecycleValid(current)) {
            resetSession();
            sessionIdentity = current.sessionIdentity();
            return remember(Decision.none(blockReason(current)));
        }
        if (!current.safetyReady()) {
            return remember(Decision.none(BlockReason.SAFETY));
        }
        if (!current.cursorEmpty()) {
            if (phase == Phase.AWAITING_PICKUP) {
                if (current.pickupConfirmed()) {
                    return evaluatePickupConfirmation(current);
                }
                return remember(Decision.none(
                        BlockReason.CURSOR_NOT_EMPTY
                ));
            }
            abandonPending();
            return remember(Decision.none(BlockReason.CURSOR_NOT_EMPTY));
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        return switch (phase) {
            case IDLE -> evaluateIdle(current);
            case AWAITING_OUTPUT -> evaluateOutput(current);
            case AWAITING_PICKUP -> evaluatePickupConfirmation(current);
        };
    }

    public void commit(Decision decision, boolean applied) {
        if (decision == null
                || decision != lastDecision
                || !applied) {
            return;
        }
        if (decision.action() == Action.PLACE_RECIPE
                && phase == Phase.IDLE) {
            pendingCandidate = decision.candidate();
            phase = Phase.AWAITING_OUTPUT;
            pendingAge = 0;
            cooldownTicks = configuration.actionDelayTicks();
        } else if (decision.action() == Action.PICKUP_OUTPUT
                && phase == Phase.AWAITING_OUTPUT
                && Objects.equals(
                pendingCandidate,
                decision.candidate()
        )) {
            phase = Phase.AWAITING_PICKUP;
            pendingAge = 0;
            cooldownTicks = configuration.actionDelayTicks();
        }
    }

    public void reset() {
        sessionIdentity = null;
        resetSession();
        lastDecision = Decision.none(BlockReason.INACTIVE);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                phase,
                pendingCandidate,
                cooldownTicks,
                pendingAge,
                completedCrafts,
                sessionIdentity != null
        );
    }

    private Decision evaluateIdle(Observation observation) {
        if (completedCrafts >= configuration.maximumCrafts()) {
            return remember(Decision.none(BlockReason.SESSION_LIMIT));
        }
        if (observation.resultPresent()) {
            return remember(Decision.none(
                    BlockReason.PREEXISTING_OUTPUT
            ));
        }
        if (!observation.gridEmpty()) {
            return remember(Decision.none(BlockReason.GRID_NOT_EMPTY));
        }
        if (cooldownTicks > 0) {
            return remember(Decision.none(BlockReason.COOLDOWN));
        }
        if (observation.candidate() == null) {
            return remember(Decision.none(BlockReason.NO_RECIPE));
        }
        return remember(new Decision(
                Action.PLACE_RECIPE,
                observation.candidate(),
                BlockReason.READY
        ));
    }

    private Decision evaluateOutput(Observation observation) {
        pendingAge++;
        if (pendingAge > configuration.confirmationTimeoutTicks()) {
            abandonPending();
            cooldownTicks = configuration.actionDelayTicks();
            return remember(Decision.none(BlockReason.OUTPUT_TIMEOUT));
        }
        if (observation.resultPresent()
                && !observation.resultMatchesExpected()) {
            abandonPending();
            cooldownTicks = configuration.actionDelayTicks();
            return remember(Decision.none(
                    BlockReason.UNEXPECTED_OUTPUT
            ));
        }
        if (cooldownTicks > 0) {
            return remember(Decision.none(BlockReason.COOLDOWN));
        }
        if (!observation.resultPresent()) {
            return remember(Decision.none(BlockReason.WAITING_FOR_OUTPUT));
        }
        if (!observation.outputTargetAvailable()) {
            return remember(Decision.none(BlockReason.NO_OUTPUT_TARGET));
        }
        return remember(new Decision(
                Action.PICKUP_OUTPUT,
                pendingCandidate,
                BlockReason.READY
        ));
    }

    private Decision evaluatePickupConfirmation(
            Observation observation
    ) {
        pendingAge++;
        if (observation.pickupInvalidated()) {
            abandonPending();
            cooldownTicks = configuration.actionDelayTicks();
            return remember(Decision.none(BlockReason.PICKUP_ROLLED_BACK));
        }
        if (observation.pickupConfirmed()) {
            completedCrafts++;
            abandonPending();
            cooldownTicks = configuration.actionDelayTicks();
            return remember(Decision.none(BlockReason.PICKUP_CONFIRMED));
        }
        if (pendingAge > configuration.confirmationTimeoutTicks()) {
            abandonPending();
            cooldownTicks = configuration.actionDelayTicks();
            return remember(Decision.none(BlockReason.PICKUP_TIMEOUT));
        }
        return remember(Decision.none(
                BlockReason.WAITING_FOR_PICKUP_CONFIRMATION
        ));
    }

    private boolean baseLifecycleValid(Observation observation) {
        return observation.sessionIdentity() != null
                && observation.sessionReady()
                && observation.craftingScreen()
                && observation.playerAlive();
    }

    private static BlockReason blockReason(Observation observation) {
        if (!observation.enabled()) {
            return BlockReason.DISABLED;
        }
        if (observation.sessionIdentity() == null
                || !observation.sessionReady()) {
            return BlockReason.NO_SESSION;
        }
        if (!observation.playerAlive()) {
            return BlockReason.PLAYER_DEAD;
        }
        if (!observation.safetyReady()) {
            return BlockReason.SAFETY;
        }
        if (!observation.craftingScreen()) {
            return BlockReason.NOT_CRAFTING_SCREEN;
        }
        return BlockReason.INACTIVE;
    }

    private void abandonPending() {
        phase = Phase.IDLE;
        pendingCandidate = null;
        pendingAge = 0;
    }

    private void resetSession() {
        phase = Phase.IDLE;
        pendingCandidate = null;
        cooldownTicks = 0;
        pendingAge = 0;
        completedCrafts = 0;
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    public enum Phase {
        IDLE,
        AWAITING_OUTPUT,
        AWAITING_PICKUP
    }

    public enum Action {
        NONE,
        PLACE_RECIPE,
        PICKUP_OUTPUT
    }

    public enum BlockReason {
        READY,
        INACTIVE,
        DISABLED,
        NO_SESSION,
        PLAYER_DEAD,
        SAFETY,
        NOT_CRAFTING_SCREEN,
        CURSOR_NOT_EMPTY,
        GRID_NOT_EMPTY,
        PREEXISTING_OUTPUT,
        SESSION_LIMIT,
        COOLDOWN,
        NO_RECIPE,
        WAITING_FOR_OUTPUT,
        UNEXPECTED_OUTPUT,
        OUTPUT_TIMEOUT,
        NO_OUTPUT_TARGET,
        WAITING_FOR_PICKUP_CONFIRMATION,
        PICKUP_CONFIRMED,
        PICKUP_ROLLED_BACK,
        PICKUP_TIMEOUT,
        ACTION_BUDGET
    }

    public record Configuration(
            int actionDelayTicks,
            int maximumCrafts,
            int confirmationTimeoutTicks
    ) {
        public Configuration {
            if (actionDelayTicks < 2 || actionDelayTicks > 100) {
                throw new IllegalArgumentException(
                        "Craft action delay must be in [2, 100]"
                );
            }
            if (maximumCrafts < 1 || maximumCrafts > 64) {
                throw new IllegalArgumentException(
                        "Maximum crafts must be in [1, 64]"
                );
            }
            if (confirmationTimeoutTicks < 4
                    || confirmationTimeoutTicks > 400) {
                throw new IllegalArgumentException(
                        "Craft confirmation timeout must be in [4, 400]"
                );
            }
            confirmationTimeoutTicks = Math.max(
                    confirmationTimeoutTicks,
                    Math.max(40, actionDelayTicks * 4)
            );
        }
    }

    public record Candidate(
            String recipeSelector,
            String outputId,
            String resultToken,
            int resultCount
    ) {
        public Candidate {
            if (recipeSelector == null || recipeSelector.isBlank()) {
                throw new IllegalArgumentException(
                        "Recipe selector cannot be blank"
                );
            }
            if (outputId == null || outputId.isBlank()) {
                throw new IllegalArgumentException(
                        "Output ID cannot be blank"
                );
            }
            if (resultToken == null || resultToken.isBlank()) {
                throw new IllegalArgumentException(
                        "Result token cannot be blank"
                );
            }
            if (resultCount < 1) {
                throw new IllegalArgumentException(
                        "Result count must be positive"
                );
            }
        }
    }

    public record Observation(
            Object sessionIdentity,
            boolean enabled,
            boolean sessionReady,
            boolean safetyReady,
            boolean craftingScreen,
            boolean playerAlive,
            boolean cursorEmpty,
            boolean gridEmpty,
            boolean resultPresent,
            boolean resultMatchesExpected,
            boolean outputTargetAvailable,
            boolean pickupConfirmed,
            boolean pickupInvalidated,
            Candidate candidate
    ) {
    }

    public record Decision(
            Action action,
            Candidate candidate,
            BlockReason blockReason
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
            if (action != Action.NONE && candidate == null) {
                throw new IllegalArgumentException(
                        "Craft action requires a candidate"
                );
            }
        }

        public boolean apply() {
            return action != Action.NONE;
        }

        static Decision none(BlockReason reason) {
            return new Decision(Action.NONE, null, reason);
        }
    }

    public record Snapshot(
            Phase phase,
            Candidate pendingCandidate,
            int cooldownTicks,
            int pendingAge,
            int completedCrafts,
            boolean sessionActive
    ) {
    }
}
