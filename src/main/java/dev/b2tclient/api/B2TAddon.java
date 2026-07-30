package dev.b2tclient.api;

/**
 * Local Fabric mods can expose this entrypoint as {@code b2tclient:addon}.
 * B2T Client does not scan directories, download addons, or load remote code.
 */
@FunctionalInterface
public interface B2TAddon {
    void onInitialize();
}
