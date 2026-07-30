package dev.sealedclient.api;

/**
 * Local Fabric mods can expose this entrypoint as {@code sealedclient:addon}.
 * Sealed Client does not scan directories, download addons, or load remote code.
 */
@FunctionalInterface
public interface SealedAddon {
    void onInitialize();
}
