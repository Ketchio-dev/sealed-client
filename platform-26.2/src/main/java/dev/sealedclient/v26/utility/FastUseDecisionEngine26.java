package dev.sealedclient.v26.utility;

import java.util.Objects;

/**
 * Pure whitelist and cooldown policy for immediate-use items.
 */
public final class FastUseDecisionEngine26 {
    private Configuration configuration;
    private Object sessionIdentity;
    private int cooldownTicks;
    private Decision lastDecision = Decision.blocked(BlockReason.INACTIVE);

    public FastUseDecisionEngine26(Configuration configuration) {
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
            sessionIdentity = current.sessionIdentity();
            cooldownTicks = 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        BlockReason block = blockReason(current);
        if (block != BlockReason.READY) {
            return remember(Decision.blocked(block));
        }
        if (cooldownTicks > 0) {
            return remember(Decision.blocked(BlockReason.OWN_COOLDOWN));
        }
        return remember(new Decision(
                Action.USE,
                current.itemKind(),
                BlockReason.READY
        ));
    }

    public void commit(Decision decision, boolean applied) {
        if (decision == null
                || decision != lastDecision
                || decision.action() != Action.USE
                || !applied) {
            return;
        }
        cooldownTicks = configuration.delayTicks();
    }

    public void reset() {
        sessionIdentity = null;
        cooldownTicks = 0;
        lastDecision = Decision.blocked(BlockReason.INACTIVE);
    }

    public Snapshot snapshot() {
        return new Snapshot(cooldownTicks, sessionIdentity != null);
    }

    private BlockReason blockReason(Observation observation) {
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
        if (!observation.screenClear()) {
            return BlockReason.SCREEN_OPEN;
        }
        if (!observation.useKeyDown()) {
            return BlockReason.KEY_NOT_DOWN;
        }
        if (observation.usingItem()) {
            return BlockReason.LONG_USE_ACTIVE;
        }
        if (observation.vanillaItemCooldown()) {
            return BlockReason.VANILLA_COOLDOWN;
        }
        if (!allowed(observation.itemKind(), observation.fallFlying())) {
            return BlockReason.NOT_WHITELISTED;
        }
        return BlockReason.READY;
    }

    private boolean allowed(ItemKind kind, boolean fallFlying) {
        return switch (kind) {
            case EXPERIENCE_BOTTLE ->
                    configuration.experienceBottles();
            case PROJECTILE -> configuration.projectiles();
            case ENDER_PEARL -> configuration.enderPearls();
            case FIREWORK ->
                    configuration.fireworks() && fallFlying;
            case OTHER -> false;
        };
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    public enum Action {
        NONE,
        USE
    }

    public enum ItemKind {
        EXPERIENCE_BOTTLE,
        PROJECTILE,
        ENDER_PEARL,
        FIREWORK,
        OTHER
    }

    public enum BlockReason {
        READY,
        INACTIVE,
        DISABLED,
        NO_SESSION,
        PLAYER_DEAD,
        SAFETY,
        SCREEN_OPEN,
        KEY_NOT_DOWN,
        LONG_USE_ACTIVE,
        VANILLA_COOLDOWN,
        NOT_WHITELISTED,
        OWN_COOLDOWN,
        ACTION_BUDGET
    }

    public record Configuration(
            int delayTicks,
            boolean experienceBottles,
            boolean projectiles,
            boolean enderPearls,
            boolean fireworks
    ) {
        public Configuration {
            if (delayTicks < 2 || delayTicks > 20) {
                throw new IllegalArgumentException(
                        "Fast Use delay must be in [2, 20]"
                );
            }
        }
    }

    public record Observation(
            Object sessionIdentity,
            boolean enabled,
            boolean sessionReady,
            boolean safetyReady,
            boolean screenClear,
            boolean playerAlive,
            boolean useKeyDown,
            boolean usingItem,
            boolean vanillaItemCooldown,
            boolean fallFlying,
            ItemKind itemKind
    ) {
        public Observation {
            itemKind = Objects.requireNonNull(itemKind, "itemKind");
        }
    }

    public record Decision(
            Action action,
            ItemKind itemKind,
            BlockReason blockReason
    ) {
        public Decision {
            action = Objects.requireNonNull(action, "action");
            itemKind = Objects.requireNonNull(itemKind, "itemKind");
            blockReason = Objects.requireNonNull(
                    blockReason,
                    "blockReason"
            );
        }

        public boolean use() {
            return action == Action.USE;
        }

        static Decision blocked(BlockReason reason) {
            return new Decision(Action.NONE, ItemKind.OTHER, reason);
        }
    }

    public record Snapshot(
            int cooldownTicks,
            boolean sessionActive
    ) {
    }
}
