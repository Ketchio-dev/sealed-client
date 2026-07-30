package dev.sealedclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SealedClient implements ClientModInitializer {
    public static final String MOD_ID = "sealedclient";
    public static final String DISPLAY_NAME = "Sealed Client";
    public static final Logger LOGGER = LoggerFactory.getLogger(DISPLAY_NAME);
    public static final String VERSION = resolveVersion();

    private static ClientRuntime runtime;

    @Override
    public void onInitializeClient() {
        if (runtime != null) {
            throw new IllegalStateException("Sealed Client was initialized more than once");
        }
        runtime = new ClientRuntime();
        runtime.initialize();
    }

    public static ClientRuntime runtime() {
        if (runtime == null) {
            throw new IllegalStateException("Sealed Client has not initialized yet");
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
