package dev.b2tclient.module.hud;

import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class TotemCountModule extends HudModule implements TickableModule {
    private int count;
    private String displayText;

    public TotemCountModule() {
        super("totem_count", "Totem Count", "Counts totems in the player inventory.", true);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            count = 0;
            displayText = null;
            return;
        }

        int updatedCount = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                updatedCount += stack.getCount();
            }
        }
        if (displayText == null || count != updatedCount) {
            count = updatedCount;
            displayText = "Totems: " + count;
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
                count > 0 ? HudRenderContext.TEXT : HudRenderContext.WARNING
        );
        return 10;
    }
}
