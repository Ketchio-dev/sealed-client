package dev.sealedclient.common.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * Coalesces repeated mutations into a save performed from a caller-owned tick.
 *
 * <p>The controller has no thread, clock, or executor. Callers mark mutations
 * synchronously, invoke {@link #tick()} once per client tick, and invoke
 * {@link #flush()} at lifecycle boundaries. This keeps filesystem work out of
 * GUI click handlers while remaining deterministic.</p>
 */
public final class TickDebouncedSaveController {
    public static final int MAX_TICK_WINDOW = 72_000;

    private final int debounceTicks;
    private final int maximumDelayTicks;
    private final SaveAction saveAction;

    private boolean dirty;
    private int quietAgeTicks;
    private int dirtyAgeTicks;
    private long revision;
    private Exception lastFailure;

    public TickDebouncedSaveController(
            int debounceTicks,
            int maximumDelayTicks,
            SaveAction saveAction
    ) {
        if (debounceTicks < 1 || debounceTicks > MAX_TICK_WINDOW) {
            throw new IllegalArgumentException(
                    "Debounce ticks must be between 1 and " + MAX_TICK_WINDOW
            );
        }
        if (maximumDelayTicks < debounceTicks || maximumDelayTicks > MAX_TICK_WINDOW) {
            throw new IllegalArgumentException(
                    "Maximum delay must be between debounce ticks and "
                            + MAX_TICK_WINDOW
            );
        }
        this.debounceTicks = debounceTicks;
        this.maximumDelayTicks = maximumDelayTicks;
        this.saveAction = Objects.requireNonNull(saveAction, "saveAction");
    }

    public void markDirty() {
        revision++;
        if (!dirty) {
            dirty = true;
            dirtyAgeTicks = 0;
        }
        quietAgeTicks = 0;
        lastFailure = null;
    }

    public TickResult tick() {
        if (!dirty) {
            return TickResult.IDLE;
        }
        quietAgeTicks = boundedIncrement(quietAgeTicks);
        dirtyAgeTicks = boundedIncrement(dirtyAgeTicks);
        if (quietAgeTicks < debounceTicks && dirtyAgeTicks < maximumDelayTicks) {
            return TickResult.PENDING;
        }
        return attemptSave();
    }

    public TickResult flush() {
        return dirty ? attemptSave() : TickResult.IDLE;
    }

    public boolean isDirty() {
        return dirty;
    }

    public Optional<Exception> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public int debounceTicks() {
        return debounceTicks;
    }

    public int maximumDelayTicks() {
        return maximumDelayTicks;
    }

    private TickResult attemptSave() {
        long attemptedRevision = revision;
        try {
            saveAction.save();
            lastFailure = null;
            if (revision == attemptedRevision) {
                dirty = false;
            }
            quietAgeTicks = 0;
            dirtyAgeTicks = 0;
            return TickResult.SAVED;
        } catch (Exception exception) {
            lastFailure = exception;
            // Retain dirty state, but debounce the retry instead of attempting
            // an fsync on every subsequent tick.
            quietAgeTicks = 0;
            dirtyAgeTicks = 0;
            return TickResult.FAILED;
        }
    }

    private static int boundedIncrement(int value) {
        return value >= MAX_TICK_WINDOW ? MAX_TICK_WINDOW : value + 1;
    }

    public enum TickResult {
        IDLE,
        PENDING,
        SAVED,
        FAILED
    }

    @FunctionalInterface
    public interface SaveAction {
        void save() throws Exception;
    }
}
