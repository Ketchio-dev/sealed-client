package dev.sealedclient.module.movement;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;

/**
 * Removes the local movement-input reduction applied while using an item.
 *
 * <p>The actual hook lives in the LocalPlayer mixin so this module does not
 * need to mutate velocity or send additional packets.</p>
 */
public final class NoSlowModule extends Module {
    public static final String ID = "no_slow";

    public NoSlowModule() {
        super(
                ID,
                "No Slow",
                "Prevents client-side movement slowdown while using items.",
                Category.MOVEMENT,
                false,
                ModuleRisk.MOVEMENT
        );
    }

    /**
     * Kept as the single activation check used by the mixin. Looking the
     * module up through the runtime avoids static enabled state surviving
     * disconnects, tests, or client shutdown.
     */
    public static boolean shouldBypassUseItemSlowdown() {
        if (!SealedClient.isInitialized()) {
            return false;
        }

        Module module = SealedClient.runtime().modules().find(ID).orElse(null);
        return module != null && module.isEnabled();
    }
}
