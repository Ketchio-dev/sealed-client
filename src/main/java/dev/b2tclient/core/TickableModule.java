package dev.b2tclient.core;

import net.minecraft.client.Minecraft;

/**
 * Marks modules that need client-tick dispatch.
 *
 * <p>Most HUD-only modules do not need a tick callback. Keeping them out of the
 * tick loop avoids invoking an inherited no-op method 20 times per second.</p>
 */
public interface TickableModule {
    void onTick(Minecraft minecraft);
}
