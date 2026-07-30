package dev.b2tclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class B2TClient implements ClientModInitializer {
    public static final String MOD_ID = "b2tclient";
    public static final String DISPLAY_NAME = "B2T Client";
    public static final Logger LOGGER = LoggerFactory.getLogger(DISPLAY_NAME);
    public static final String VERSION = resolveVersion();

    private static ClientRuntime runtime;

    @Override
    public void onInitializeClient() {
        if (runtime != null) {
            throw new IllegalStateException("B2T Client was initialized more than once");
        }
        runtime = new ClientRuntime();
        runtime.initialize();
    }

    public static ClientRuntime runtime() {
        if (runtime == null) {
            throw new IllegalStateException("B2T Client has not initialized yet");
        }
        return runtime;
    }

    public static boolean isInitialized() {
        return runtime != null;
    }

    private static String resolveVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("development");
        } catch (RuntimeException ignored) {
            return "development";
        }
    }
}
