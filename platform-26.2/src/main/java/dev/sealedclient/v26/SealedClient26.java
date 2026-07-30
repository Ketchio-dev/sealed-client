package dev.sealedclient.v26;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SealedClient26 implements ClientModInitializer {
    public static final String MOD_ID = "sealedclient";
    public static final Logger LOGGER = LoggerFactory.getLogger("Sealed Client 26.2");
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
