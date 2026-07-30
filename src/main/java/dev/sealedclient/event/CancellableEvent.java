package dev.sealedclient.event;

public abstract class CancellableEvent {
    private boolean cancelled;

    public final boolean isCancelled() {
        return cancelled;
    }

    public final void cancel() {
        cancelled = true;
    }
}
