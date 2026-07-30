package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

public final class TotemPopHudModule extends HudModule implements TickableModule {
    private UUID trackedPlayer;
    private int previousTotems = -1;
    private int estimatedUses;
    private String displayText;

    public TotemPopHudModule() {
        super(
                "totem_pop_local",
                "Totem Pop (Local Estimate)",
                "Estimates local totem uses from inventory decreases; moves or drops can count too.",
                false
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            reset();
            return;
        }

        if (!player.getUUID().equals(trackedPlayer)) {
            trackedPlayer = player.getUUID();
            previousTotems = countTotems(player);
            estimatedUses = 0;
        } else {
            int currentTotems = countTotems(player);
            if (previousTotems >= 0 && currentTotems < previousTotems) {
                estimatedUses += previousTotems - currentTotems;
            }
            previousTotems = currentTotems;
        }
        displayText = "Local totem-use estimate: " + estimatedUses;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        reset();
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.MUTED);
        return 10;
    }

    private static int countTotems(LocalPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void reset() {
        trackedPlayer = null;
        previousTotems = -1;
        estimatedUses = 0;
        displayText = null;
    }
}
