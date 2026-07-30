package dev.sealedclient.hud;

import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.core.ModuleManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;

/**
 * Visual editor for the positions stored on each enabled HUD module.
 */
public final class HudEditorScreen extends Screen {
    private static final int COLOR_BACKDROP = 0xB00B0E13;
    private static final int COLOR_PANEL = 0xE0181D24;
    private static final int COLOR_TEXT = 0xFFF3F5F7;
    private static final int COLOR_MUTED = 0xFFAAB4BF;
    private static final int COLOR_ACCENT = 0xFF55D6BE;
    private static final int COLOR_STACKED = 0xFF718096;
    private static final int COLOR_SELECTED = 0xFFFFC857;
    private static final int BOTTOM_BAR_HEIGHT = 23;
    private static final int RESET_BUTTON_WIDTH = 74;

    private final ModuleManager moduleManager;
    private final ConfigManager configManager;
    private final HudRenderer renderer;

    private List<HudElementBounds> bounds = List.of();
    private HudModule selected;
    private HudModule dragging;
    private int dragOffsetX;
    private int dragOffsetY;
    private boolean dirty;

    public HudEditorScreen(ModuleManager moduleManager, ConfigManager configManager) {
        super(Component.literal("Sealed HUD Editor"));
        this.moduleManager = Objects.requireNonNull(moduleManager, "moduleManager");
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        renderer = new HudRenderer(moduleManager);
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        graphics.fill(0, 0, width, height, COLOR_BACKDROP);
        drawGrid(graphics);

        bounds = renderer.renderPreview(graphics);
        for (HudElementBounds element : bounds) {
            int color = element.module() == selected
                    ? COLOR_SELECTED
                    : element.module().hasCustomPosition() ? COLOR_ACCENT : COLOR_STACKED;
            drawOutline(graphics, element, color);
        }

        renderBottomBar(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (insideResetButton(mouseX, mouseY)) {
            resetAllPositions();
            return true;
        }

        for (int index = bounds.size() - 1; index >= 0; index--) {
            HudElementBounds element = bounds.get(index);
            if (!element.contains(mouseX, mouseY)) {
                continue;
            }
            selected = element.module();
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                selected.resetLayoutPosition();
                dirty = true;
                saveChanges();
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                dragging = selected;
                dragOffsetX = (int) Math.round(mouseX) - element.x();
                dragOffsetY = (int) Math.round(mouseY) - element.y();
                dragging.setLayoutPosition(element.x(), element.y());
                dirty = true;
                return true;
            }
        }
        selected = null;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY
    ) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragging == null) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        HudElementBounds element = bounds.stream()
                .filter(candidate -> candidate.module() == dragging)
                .findFirst()
                .orElse(new HudElementBounds(dragging, 2, 2, 112, 12));
        int requestedX = (int) Math.round(mouseX) - dragOffsetX;
        int requestedY = (int) Math.round(mouseY) - dragOffsetY;
        dragging.setLayoutPosition(
                element.clampX(requestedX, width),
                element.clampY(requestedY, height - BOTTOM_BAR_HEIGHT)
        );
        dirty = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging != null) {
            dragging = null;
            saveChanges();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            resetAllPositions();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)
                && selected != null) {
            selected.resetLayoutPosition();
            dirty = true;
            saveChanges();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_H) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        dragging = null;
        saveChanges();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void resetAllPositions() {
        for (HudModule module : moduleManager.hudModules()) {
            module.resetLayoutPosition();
        }
        selected = null;
        dragging = null;
        dirty = true;
        saveChanges();
    }

    private void saveChanges() {
        if (!dirty) {
            return;
        }
        configManager.save();
        dirty = false;
    }

    private void renderBottomBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = Math.max(0, height - BOTTOM_BAR_HEIGHT);
        graphics.fill(0, top, width, height, COLOR_PANEL);

        String hint = width >= 310
                ? "Drag to move  \u2022  Right-click/Delete to reset  \u2022  Esc to close"
                : "Drag \u2022 Right-click reset";
        int maximumHintWidth = Math.max(0, width - RESET_BUTTON_WIDTH - 18);
        if (font.width(hint) > maximumHintWidth) {
            hint = maximumHintWidth > font.width("\u2026")
                    ? font.plainSubstrByWidth(
                            hint,
                            maximumHintWidth - font.width("\u2026")
                    ) + "\u2026"
                    : "";
        }
        graphics.drawString(font, hint, 6, top + 8, COLOR_MUTED, false);

        int resetX = resetButtonX();
        int resetColor = insideResetButton(mouseX, mouseY) ? COLOR_ACCENT : COLOR_STACKED;
        graphics.fill(resetX, top + 3, width - 4, height - 3, resetColor);
        String reset = "Reset stack";
        int resetTextX = resetX + Math.max(
                3,
                (width - 4 - resetX - font.width(reset)) / 2
        );
        graphics.drawString(font, reset, resetTextX, top + 8, COLOR_TEXT, true);
    }

    private void drawGrid(GuiGraphics graphics) {
        for (int x = 16; x < width; x += 16) {
            graphics.fill(x, 0, x + 1, Math.max(0, height - BOTTOM_BAR_HEIGHT), 0x181F2933);
        }
        for (int y = 16; y < height - BOTTOM_BAR_HEIGHT; y += 16) {
            graphics.fill(0, y, width, y + 1, 0x181F2933);
        }
    }

    private static void drawOutline(
            GuiGraphics graphics,
            HudElementBounds element,
            int color
    ) {
        int left = element.x() - 2;
        int top = element.y() - 2;
        int right = element.x() + element.width() + 2;
        int bottom = element.y() + element.height() + 2;
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private int resetButtonX() {
        return Math.max(4, width - RESET_BUTTON_WIDTH - 4);
    }

    private boolean insideResetButton(double mouseX, double mouseY) {
        return mouseX >= resetButtonX()
                && mouseX < width - 4
                && mouseY >= Math.max(0, height - BOTTOM_BAR_HEIGHT) + 3
                && mouseY < height - 3;
    }
}
