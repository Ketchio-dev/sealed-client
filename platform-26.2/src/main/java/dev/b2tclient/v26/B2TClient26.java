package dev.b2tclient.v26;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class B2TClient26 implements ClientModInitializer {
    public static final String MOD_ID = "b2tclient";
    public static final Logger LOGGER = LoggerFactory.getLogger("B2T Client 26.2");
    private static final ClientRuntime26 RUNTIME = new ClientRuntime26();

    @Override
    public void onInitializeClient() {
        RUNTIME.initialize();
        LOGGER.info(
                "Loaded Minecraft 26.2 adapter: {} catalog entries, {} implemented",
                RUNTIME.modules().all().size(),
                PlatformCapabilities26.SUPPORTED_IDS.size()
        );
    }

    public static ClientRuntime26 runtime() {
        return RUNTIME;
    }
}
