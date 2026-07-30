package dev.b2tclient.v26.gui;

import dev.b2tclient.v26.ClientRuntime26;
import dev.b2tclient.v26.hud.HudLayout26;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * Drag-and-drop editor for the HUD panel positions.
 *
 * <p>Panels are drawn at the same size the live HUD would use, so what is
 * dragged is what is rendered. Every move goes through
 * {@link HudLayout26#anchorFor} which clamps the result, meaning a panel cannot
 * be dropped off-screen even if the cursor leaves the window.</p>
 */
public final class HudEditorScreen26 extends Screen {
    private static final int GRABBED_OUTLINE = 0xFFFFD166;
    private static final int IDLE_OUTLINE = 0xFF4A6480;
    private final ClientRuntime26 runtime;
    private final Map<HudLayout26.Panel, Rect> lastRects =
            new EnumMap<>(HudLayout26.Panel.class);
    private final Map<HudLayout26.Panel, HudLayout26.Anchor> openingAnchors;
    private HudLayout26.Panel dragging;
    private int grabOffsetX;
    private int grabOffsetY;
    private String status = "Drag a panel  R: reset  Escape: done";

    public HudEditorScreen26(ClientRuntime26 runtime) {
        super(Component.literal("B2T HUD Editor"));
        this.runtime = runtime;
        this.openingAnchors = new EnumMap<>(runtime.hudLayout().snapshot());
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    ) {
        graphics.fill(0, 0, width, height, 0x99080C12);
        graphics.centeredText(font, title, width / 2, 6, 0xFFFFFFFF);
        graphics.centeredText(font, status, width / 2, 18, 0xFFBAC8D6);

        for (HudLayout26.Panel panel : HudLayout26.Panel.values()) {
            Rect rect = rect(panel);
            lastRects.put(panel, rect);
            boolean grabbed = panel == dragging;
            graphics.fill(rect.left(), rect.top(), rect.right(), rect.bottom(),
                    grabbed ? 0x804A6480 : 0x60131B25);
            outline(graphics, rect, grabbed ? GRABBED_OUTLINE : IDLE_OUTLINE);
            graphics.text(font, panel.label(), rect.left() + 3, rect.top() + 3,
                    grabbed ? GRABBED_OUTLINE : 0xFFE8F3FF, true);
        }
    }

    private static void outline(GuiGraphicsExtractor graphics, Rect rect, int color) {
        graphics.fill(rect.left(), rect.top(), rect.right(), rect.top() + 1, color);
        graphics.fill(rect.left(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.left(), rect.top(), rect.left() + 1, rect.bottom(), color);
        graphics.fill(rect.right() - 1, rect.top(), rect.right(), rect.bottom(), color);
    }

    /**
     * The editor shows a fixed representative size rather than the live panel
     * height, which changes every tick as stats appear and disappear. The
     * clamping math is identical either way, so a panel placed here stays fully
     * visible once the real panel renders.
     */
    private Rect rect(HudLayout26.Panel panel) {
        int panelWidth = Math.min(width, 120);
        int panelHeight = Math.min(height, 46);
        HudLayout26.Position position = runtime.hudLayout()
                .resolve(panel, panelWidth, panelHeight, width, height);
        return new Rect(
                position.x(),
                position.y(),
                position.x() + panelWidth,
                position.y() + panelHeight
        );
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(event, doubleClick);
        }
        for (HudLayout26.Panel panel : HudLayout26.Panel.values()) {
            Rect rect = lastRects.getOrDefault(panel, rect(panel));
            if (event.x() >= rect.left() && event.x() < rect.right()
                    && event.y() >= rect.top() && event.y() < rect.bottom()) {
                dragging = panel;
                grabOffsetX = (int) event.x() - rect.left();
                grabOffsetY = (int) event.y() - rect.top();
                status = "Moving " + panel.label();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(
            MouseButtonEvent event,
            double dragX,
            double dragY
    ) {
        if (dragging == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        Rect rect = rect(dragging);
        runtime.hudLayout().setAnchor(dragging, HudLayout26.anchorFor(
                (int) event.x() - grabOffsetX,
                (int) event.y() - grabOffsetY,
                rect.right() - rect.left(),
                rect.bottom() - rect.top(),
                width,
                height
        ));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            status = dragging.label() + " placed";
            dragging = null;
            runtime.requestSave();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_R) {
            runtime.hudLayout().reset();
            dragging = null;
            status = "Layout reset to defaults";
            runtime.requestSave();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        dragging = null;
        lastRects.clear();
        if (!runtime.hudLayout().snapshot().equals(openingAnchors) && !runtime.save()) {
            status = "HUD layout remains in memory; config save failed";
        }
        super.removed();
    }

    private record Rect(int left, int top, int right, int bottom) {
    }
}
