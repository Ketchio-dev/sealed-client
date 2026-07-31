package dev.sealedclient.platform;

import net.minecraft.world.entity.player.Player;

/**
 * The one place that knows how the selected hotbar slot is spelled.
 *
 * <p>Minecraft renamed this between the versions this client targets:
 * {@code Inventory.selected} and {@code setSelectedHotbarSlot} on 1.21.4
 * became {@code getSelectedSlot} and {@code setSelectedSlot} later. Sixty-one
 * call sites referenced the old names directly, so a rename that is one line
 * of real change turned into sixty-one compile errors.</p>
 *
 * <p>Routing every access through here means the next rename touches this file
 * and nothing else.</p>
 */
public final class HotbarAccess {
    private HotbarAccess() {
    }

    /** The currently selected hotbar index. */
    public static int selectedSlot(Player player) {
        return player.getInventory().selected;
    }

    /**
     * Selects a hotbar slot, ignoring indices outside the bar.
     *
     * <p>Out-of-range values are dropped rather than clamped: a caller that
     * computed slot -1 found no item, and silently selecting slot 0 would use
     * whatever happened to be there.</p>
     */
    public static void selectSlot(Player player, int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        player.getInventory().setSelectedHotbarSlot(slot);
    }

    /** The stack in the selected hotbar slot. */
    public static net.minecraft.world.item.ItemStack selectedStack(Player player) {
        return player.getInventory().getSelected();
    }

    /** Whether the given slot is already selected. */
    public static boolean isSelected(Player player, int slot) {
        return selectedSlot(player) == slot;
    }
}
