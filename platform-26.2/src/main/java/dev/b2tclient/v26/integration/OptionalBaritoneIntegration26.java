package dev.b2tclient.v26.integration;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Objects;

/**
 * Discovers Baritone without making it a Fabric or JVM dependency of B2T.
 */
public final class OptionalBaritoneIntegration26 {
    private OptionalBaritoneIntegration26() {
    }

    public static BaritoneNavigator26 discover() {
        try {
            var container = FabricLoader.getInstance().getModContainer(
                    "baritone"
            );
            if (container.isEmpty()) {
                return unavailable(
                        "",
                        "Baritone is not installed; no navigation was started"
                );
            }
            String version = container.orElseThrow()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
            return discover(
                    true,
                    version,
                    OptionalBaritoneIntegration26.class.getClassLoader(),
                    BaritoneNavigator26.Limits.DEFAULT
            );
        } catch (LinkageError | RuntimeException exception) {
            return unavailable(
                    "",
                    "Baritone discovery failed ("
                            + exception.getClass().getSimpleName() + ")"
            );
        }
    }

    public static BaritoneNavigator26 discover(
            boolean reportedInstalled,
            String version,
            ClassLoader loader
    ) {
        return discover(
                reportedInstalled,
                version,
                loader,
                BaritoneNavigator26.Limits.DEFAULT
        );
    }

    public static BaritoneNavigator26 discover(
            boolean reportedInstalled,
            String version,
            ClassLoader loader,
            BaritoneNavigator26.Limits limits
    ) {
        return discover(
                reportedInstalled,
                version,
                loader,
                limits,
                ReflectiveBaritoneAccess26.PRODUCTION_NAMES
        );
    }

    static BaritoneNavigator26 discover(
            boolean reportedInstalled,
            String version,
            ClassLoader loader,
            BaritoneNavigator26.Limits limits,
            ReflectiveBaritoneAccess26.ApiNames names
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(names, "names");
        if (!reportedInstalled) {
            return unavailable(
                    version,
                    "Baritone is not installed; no navigation was started"
            );
        }
        try {
            ReflectiveBaritoneAccess26 access =
                    ReflectiveBaritoneAccess26.probe(loader, names);
            return new ReflectiveBaritoneNavigator26(
                    version,
                    access,
                    limits
            );
        } catch (LinkageError | RuntimeException exception) {
            return unavailable(
                    version,
                    "Installed Baritone API is incompatible with Minecraft 26.2 ("
                            + rootType(exception) + ")"
            );
        }
    }

    private static BaritoneNavigator26 unavailable(
            String version,
            String detail
    ) {
        return new UnavailableBaritoneNavigator26(version, detail);
    }

    private static String rootType(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
