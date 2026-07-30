package dev.sealedclient.integration;

import java.util.Objects;

final class UnavailableBaritoneNavigator implements BaritoneNavigator {
    private final String version;
    private final String detail;

    UnavailableBaritoneNavigator(String version, String detail) {
        this.version = Objects.requireNonNullElse(version, "");
        this.detail = Objects.requireNonNullElse(
                detail,
                "Install the matching Baritone Fabric mod to use navigation"
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
    public NavigationResult stop() {
        return NavigationResult.failure(detail);
    }

    @Override
    public NavigationStatus status() {
        return new NavigationStatus(NavigationState.UNAVAILABLE, detail, false);
    }

    @Override
    public void releaseOwnedNavigation() {
        // Nothing was started through Sealed Client.
    }
}
