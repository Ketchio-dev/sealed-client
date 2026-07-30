package dev.sealedclient.v26.utility;

import dev.sealedclient.v26.mixin.utility.MinecraftRightClickDelayAccessor26;
import net.minecraft.client.Minecraft;

/**
 * Narrow fail-closed access to Minecraft's vanilla right-click cooldown.
 *
 * <p>26.2 is unobfuscated and names the field {@code rightClickDelay}. The
 * access goes through a Mixin accessor instead of reflection, so it is
 * compile-time checked and fails closed whenever the Mixin was not applied
 * (for example in plain unit tests). Fast Use only reduces the delay and lets
 * vanilla generate the eventual single use packet, which avoids a second use
 * call in the same tick.</p>
 */
final class FastUseCooldownAccess26 {
    private FastUseCooldownAccess26() {
    }

    static boolean available(Minecraft client) {
        return accessor(client) != null;
    }

    static int current(Minecraft client) {
        MinecraftRightClickDelayAccessor26 accessor = accessor(client);
        if (accessor == null) {
            return -1;
        }
        try {
            return accessor.sealed$rightClickDelay();
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    static boolean limit(Minecraft client, int maximumDelayTicks) {
        if (maximumDelayTicks < 1 || maximumDelayTicks > 20) {
            throw new IllegalArgumentException(
                    "Fast Use cooldown limit must be in [1, 20]"
            );
        }
        int current = current(client);
        if (current <= maximumDelayTicks) {
            return false;
        }
        return write(client, maximumDelayTicks);
    }

    static boolean restore(Minecraft client, int delayTicks) {
        if (delayTicks < 0 || delayTicks > 20) {
            throw new IllegalArgumentException(
                    "Restored cooldown must be in [0, 20]"
            );
        }
        int current = current(client);
        if (current < 0 || current >= delayTicks) {
            return false;
        }
        return write(client, delayTicks);
    }

    static int limitedValue(int current, int maximumDelayTicks) {
        if (current < 0) {
            return current;
        }
        return Math.min(current, maximumDelayTicks);
    }

    static int restoredValue(
            int current,
            int original,
            int completedVanillaTicks
    ) {
        if (current < 0 || original < 0 || completedVanillaTicks < 0) {
            throw new IllegalArgumentException(
                    "Cooldown values cannot be negative"
            );
        }
        return Math.max(
                current,
                Math.max(0, original - completedVanillaTicks)
        );
    }

    private static boolean write(Minecraft client, int delayTicks) {
        MinecraftRightClickDelayAccessor26 accessor = accessor(client);
        if (accessor == null) {
            return false;
        }
        try {
            accessor.sealed$setRightClickDelay(delayTicks);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static MinecraftRightClickDelayAccessor26 accessor(
            Minecraft client
    ) {
        if (client instanceof MinecraftRightClickDelayAccessor26 accessor) {
            return accessor;
        }
        return null;
    }
}
