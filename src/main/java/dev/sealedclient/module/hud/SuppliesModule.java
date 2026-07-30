package dev.sealedclient.module.hud;

import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SuppliesModule extends HudModule implements TickableModule {
    private String displayText;
    private int displayedCrystals = -1;
    private int displayedGaps = -1;
    private int displayedExperience = -1;
    private int displayedPearls = -1;

    public SuppliesModule() {
        super("supplies", "Supplies", "Counts common survival and PvP supplies.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            displayText = null;
            return;
        }

        int crystals = 0;
        int gaps = 0;
        int experience = 0;
        int pearls = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.END_CRYSTAL)) {
                crystals += stack.getCount();
            } else if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                    || stack.is(Items.GOLDEN_APPLE)) {
                gaps += stack.getCount();
            } else if (stack.is(Items.EXPERIENCE_BOTTLE)) {
                experience += stack.getCount();
            } else if (stack.is(Items.ENDER_PEARL)) {
                pearls += stack.getCount();
            }
        }
        if (displayText == null
                || displayedCrystals != crystals
                || displayedGaps != gaps
                || displayedExperience != experience
                || displayedPearls != pearls) {
            displayedCrystals = crystals;
            displayedGaps = gaps;
            displayedExperience = experience;
            displayedPearls = pearls;
            displayText = "Crystals " + crystals
                    + "  Gaps " + gaps
                    + "  XP " + experience
                    + "  Pearls " + pearls;
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.TEXT);
        return 10;
    }
}
