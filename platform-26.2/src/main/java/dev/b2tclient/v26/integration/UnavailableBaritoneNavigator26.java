package dev.b2tclient.v26.integration;

import java.util.Objects;

final class UnavailableBaritoneNavigator26 implements BaritoneNavigator26 {
    private final String version;
    private final String detail;

    UnavailableBaritoneNavigator26(String version, String detail) {
        this.version = Objects.requireNonNullElse(version, "");
        this.detail = Objects.requireNonNullElse(
                detail,
                "Install a Baritone Fabric build matching Minecraft 26.2"
        );
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public NavigationResult goTo(int x, int y, int z) {
        return NavigationResult.failure(detail);
    }

    @Override
    public NavigationResult pause() {
        return NavigationResult.failure(detail);
    }

    @Override
    public NavigationResult resume() {
        return NavigationResult.failure(detail);
    }

    @Override
    public NavigationResult stop() {
        return NavigationResult.failure(detail);
    }

    @Override
    public NavigationStatus status() {
        return new NavigationStatus(NavigationState.UNAVAILABLE, detail, false);
    }

    @Override
    public void releaseOwnedNavigation() {
        // No command or goal was submitted.
    }

    @Override
    public void resetSession() {
        // No transient provider state exists.
    }
}
