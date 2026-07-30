package dev.b2tclient.module.hud;

import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class InventorySpaceModule extends HudModule implements TickableModule {
    private int emptySlots;
    private String displayText;

    public InventorySpaceModule() {
        super("inventory_space", "Inventory Space", "Displays empty main-inventory slots.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            emptySlots = 0;
            displayText = null;
            return;
        }

        int empty = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        if (displayText == null || emptySlots != empty) {
            emptySlots = empty;
            displayText = "Empty slots: " + emptySlots;
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(
                displayText,
                x,
                y,
                emptySlots == 0 ? HudRenderContext.WARNING : HudRenderContext.TEXT
        );
        return 10;
    }
}
