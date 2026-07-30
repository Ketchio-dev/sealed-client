package dev.sealedclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;

public final class InventoryActions {
    public static final int OFFHAND_SWAP_BUTTON = 40;

    private InventoryActions() {
    }

    public static boolean isReady(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.gameMode != null
                && minecraft.screen == null
                && minecraft.player.containerMenu == minecraft.player.inventoryMenu
                && minecraft.player.containerMenu.getCarried().isEmpty();
    }

    public static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) {
            throw new IllegalArgumentException("Not a main inventory index: " + inventoryIndex);
        }
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    public static void swapWithOffhand(Minecraft minecraft, int inventoryIndex) {
        int sourceSlot = inventoryIndexToMenuSlot(inventoryIndex);
        minecraft.gameMode.handleInventoryMouseClick(
                minecraft.player.inventoryMenu.containerId,
                sourceSlot,
                OFFHAND_SWAP_BUTTON,
                ClickType.SWAP,
                minecraft.player
        );
    }

    public static void pickupSwap(Minecraft minecraft, int inventoryIndex, int targetMenuSlot) {
        int sourceSlot = inventoryIndexToMenuSlot(inventoryIndex);
        int containerId = minecraft.player.inventoryMenu.containerId;
        minecraft.gameMode.handleInventoryMouseClick(
                containerId,
                sourceSlot,
                0,
                ClickType.PICKUP,
                minecraft.player
        );
        minecraft.gameMode.handleInventoryMouseClick(
                containerId,
                targetMenuSlot,
                0,
                ClickType.PICKUP,
                minecraft.player
        );
        minecraft.gameMode.handleInventoryMouseClick(
                containerId,
                sourceSlot,
                0,
                ClickType.PICKUP,
                minecraft.player
        );
    }
}
