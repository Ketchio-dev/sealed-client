package dev.b2tclient.module.hud;

import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class ArmorModule extends HudModule {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD
    };

    public ArmorModule() {
        super("armor", "Armor", "Displays equipped armor and remaining durability.", true);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        LocalPlayer player = context.minecraft().player;
        if (player == null) {
            return 0;
        }

        context.text("Armor:", x, y + 4, HudRenderContext.MUTED);
        int itemX = x + 38;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                context.graphics().renderItem(stack, itemX, y);
                context.graphics().renderItemDecorations(context.minecraft().font, stack, itemX, y);
            }
            itemX += 20;
        }
        return 18;
    }
}

