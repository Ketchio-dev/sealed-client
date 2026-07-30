package dev.sealedclient.hud;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.ModuleManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HudRenderer {
    private static final int LEFT_MARGIN = 6;
    private static final int TOP_MARGIN = 6;
    private static final int GAP = 2;
    private static final int MINIMUM_ELEMENT_HEIGHT = 12;
    private static final int NOTIFICATION_GAP = 3;
    private static final int NOTIFICATION_HEIGHT = 17;
    private static final int NOTIFICATION_MAXIMUM_WIDTH = 280;
    private static final Map<String, Integer> WIDTH_HINTS = Map.ofEntries(
            Map.entry("armor", 122),
            Map.entry("array_list", 170),
            Map.entry("coordinates", 210),
            Map.entry("death_position", 280),
            Map.entry("durability_warning", 240),
            Map.entry("effects", 180),
            Map.entry("health", 175),
            Map.entry("player_radar", 185),
            Map.entry("radar", 185),
            Map.entry("server_info", 225),
            Map.entry("session", 230),
            Map.entry("supplies", 215),
            Map.entry("target_hud", 225),
            Map.entry("tick_rate", 280),
            Map.entry("totem_pop_hud", 200)
    );

    private final ModuleManager moduleManager;
    private final NotificationManager notificationManager;
    private List<HudElementBounds> lastBounds = List.of();

    public HudRenderer(ModuleManager moduleManager) {
        this(moduleManager, null);
    }

    public HudRenderer(
            ModuleManager moduleManager,
            NotificationManager notificationManager
    ) {
        this.moduleManager = moduleManager;
        this.notificationManager = notificationManager;
    }

    public void initialize() {
        HudLayerRegistrationCallback.EVENT.register(layeredDrawer -> layeredDrawer.attachLayerAfter(
                IdentifiedLayer.MISC_OVERLAYS,
                ResourceLocation.fromNamespaceAndPath(SealedClient.MOD_ID, "hud"),
                (graphics, tickCounter) -> renderHud(graphics)
        ));
    }

    private void renderHud(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.options.hideGui
                || minecraft.screen instanceof HudEditorScreen) {
            return;
        }
        renderElements(graphics, false);
        renderNotifications(graphics, minecraft);
    }

    /**
     * Renders enabled elements for the editor and returns their current draggable bounds.
     */
    public List<HudElementBounds> renderPreview(GuiGraphics graphics) {
        return renderElements(graphics, true);
    }

    public List<HudElementBounds> lastBounds() {
        return lastBounds;
    }

    private List<HudElementBounds> renderElements(GuiGraphics graphics, boolean editorPreview) {
        Minecraft minecraft = Minecraft.getInstance();
        HudRenderContext context = new HudRenderContext(minecraft, graphics);
        int stackY = TOP_MARGIN;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        List<HudElementBounds> renderedBounds = new ArrayList<>();

        for (HudModule module : moduleManager.hudModules()) {
            if (!module.isEnabled()) {
                continue;
            }

            int width = elementWidth(module, minecraft, screenWidth);
            int heightHint = elementHeightHint(module);
            boolean custom = module.hasCustomPosition();
            int x = custom
                    ? clamp(module.layoutX(), LEFT_MARGIN, screenWidth - width - LEFT_MARGIN)
                    : LEFT_MARGIN;
            int y = custom
                    ? clamp(module.layoutY(), TOP_MARGIN, screenHeight - heightHint - TOP_MARGIN)
                    : stackY;
            int renderedHeight = 0;
            try {
                renderedHeight = Math.max(0, module.render(context, x, y));
            } catch (RuntimeException exception) {
                SealedClient.LOGGER.error("Could not render HUD module {}", module.id(), exception);
            }

            if (editorPreview && renderedHeight == 0) {
                String placeholder = module.name() + " (no data)";
                context.text(placeholder, x, y, HudRenderContext.MUTED);
                width = Math.max(width, minecraft.font.width(placeholder) + 8);
                width = Math.min(width, Math.max(1, screenWidth - LEFT_MARGIN * 2));
                renderedHeight = 10;
            }

            int boundsHeight = Math.max(MINIMUM_ELEMENT_HEIGHT, renderedHeight);
            int finalX = clamp(x, LEFT_MARGIN, screenWidth - width - LEFT_MARGIN);
            int finalY = clamp(y, TOP_MARGIN, screenHeight - boundsHeight - TOP_MARGIN);
            renderedBounds.add(new HudElementBounds(
                    module,
                    finalX,
                    finalY,
                    width,
                    boundsHeight
            ));
            if (!custom) {
                stackY += renderedHeight + GAP;
            }
        }
        lastBounds = List.copyOf(renderedBounds);
        return lastBounds;
    }

    private void renderNotifications(GuiGraphics graphics, Minecraft minecraft) {
        if (notificationManager == null) {
            return;
        }
        List<NotificationManager.Notification> notifications = notificationManager.active();
        int y = TOP_MARGIN;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        for (NotificationManager.Notification notification : notifications.reversed()) {
            String message = notification.message();
            int maximumTextWidth = Math.max(
                    1,
                    Math.min(NOTIFICATION_MAXIMUM_WIDTH, screenWidth - LEFT_MARGIN * 2) - 12
            );
            if (minecraft.font.width(message) > maximumTextWidth) {
                message = minecraft.font.plainSubstrByWidth(
                        message,
                        Math.max(1, maximumTextWidth - minecraft.font.width("\u2026"))
                ) + "\u2026";
            }
            int width = Math.min(
                    screenWidth - LEFT_MARGIN * 2,
                    minecraft.font.width(message) + 12
            );
            int x = Math.max(LEFT_MARGIN, screenWidth - width - LEFT_MARGIN);
            graphics.fill(x, y, x + width, y + NOTIFICATION_HEIGHT, 0xE0181D24);
            graphics.fill(x, y, x + 2, y + NOTIFICATION_HEIGHT, notification.type().color());
            graphics.drawString(
                    minecraft.font,
                    message,
                    x + 7,
                    y + 5,
                    HudRenderContext.TEXT,
                    false
            );
            y += NOTIFICATION_HEIGHT + NOTIFICATION_GAP;
        }
    }

    private static int elementWidth(
            HudModule module,
            Minecraft minecraft,
            int screenWidth
    ) {
        int suggested = WIDTH_HINTS.getOrDefault(
                module.id(),
                Math.max(112, minecraft.font.width(module.name()) + 32)
        );
        return Math.max(1, Math.min(suggested, screenWidth - LEFT_MARGIN * 2));
    }

    private static int elementHeightHint(HudModule module) {
        return switch (module.id()) {
            case "armor", "server_info", "target_hud" -> 20;
            case "effects", "player_radar", "radar", "array_list" -> 100;
            default -> MINIMUM_ELEMENT_HEIGHT;
        };
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) {
            return Math.max(0, maximum);
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
