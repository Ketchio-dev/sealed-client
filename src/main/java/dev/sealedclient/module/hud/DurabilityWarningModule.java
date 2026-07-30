package dev.sealedclient.module.hud;

import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class DurabilityWarningModule extends HudModule implements TickableModule {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final IntegerSetting threshold = addSetting(new IntegerSetting(
            "threshold",
            "Threshold",
            "Warn when equipped armor reaches this durability percentage.",
            20,
            1,
            90,
            1
    ));

    private final BooleanSetting overlayMessage = addSetting(new BooleanSetting(
            "overlay_message",
            "Overlay message",
            "Show a periodic warning above the hotbar.",
            true
    ));

    private long lastOverlayTick;
    private final StringBuilder warningBuilder = new StringBuilder(64);
    private String warningText = "";

    public DurabilityWarningModule() {
        super(
                "durability_warning",
                "Durability Warning",
                "Warns before equipped armor breaks.",
                true
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            warningText = "";
            return;
        }

        updateWarning(player);
        if (overlayMessage.get()
                && !warningText.isEmpty()
                && player.tickCount - lastOverlayTick >= 100) {
            minecraft.gui.setOverlayMessage(
                    Component.literal(warningText),
                    false
            );
            lastOverlayTick = player.tickCount;
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (warningText.isEmpty()) {
            return 0;
        }

        context.text(warningText, x, y, HudRenderContext.WARNING);
        return 10;
    }

    private void updateWarning(LocalPlayer player) {
        warningBuilder.setLength(0);
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }

            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            int percent = Math.round(remaining * 100.0f / stack.getMaxDamage());
            if (percent <= threshold.get()) {
                if (warningBuilder.isEmpty()) {
                    warningBuilder.append("Low durability: ");
                } else {
                    warningBuilder.append(", ");
                }
                warningBuilder.append(slot.getName()).append(' ').append(percent).append('%');
            }
        }
        String updated = warningBuilder.isEmpty() ? "" : warningBuilder.toString();
        if (!updated.equals(warningText)) {
            warningText = updated;
        }
    }
}
