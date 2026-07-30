package dev.sealedclient.v26.gui;

import dev.sealedclient.v26.ClientRuntime26;
import dev.sealedclient.v26.config.PresetApplication26;
import dev.sealedclient.v26.config.PresetCatalog26;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Browses the built-in presets, previews exactly what each one would change,
 * requires a confirmation for risky ones, and offers a single-step undo.
 */
public final class PresetScreen26 extends Screen {
    private static final int HEADER_HEIGHT = 44;
    private static final int FOOTER_HEIGHT = 26;
    private static final int ROW_HEIGHT = 26;
    private static final int LINE_HEIGHT = 10;
    private static final int EDGE = 8;
    private final ClientRuntime26 runtime;
    private PresetCatalog26.Preset selected;
    private PresetApplication26.Preview preview;
    private boolean awaitingConfirmation;
    private int previewScroll;
    private String status = "Click a preset to preview it";

    public PresetScreen26(ClientRuntime26 runtime) {
        super(Component.literal("Sealed Presets"));
        this.runtime = runtime;
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    ) {
        graphics.fill(0, 0, width, height, 0xD010141B);
        graphics.fill(0, 0, width, HEADER_HEIGHT, 0xFF172231);
        graphics.centeredText(font, title, width / 2, 6, 0xFFFFFFFF);
        graphics.centeredText(
                font,
                "Presets only change the modules they list; everything else is untouched",
                width / 2,
                20,
                0xFF93A7BC
        );

        int split = Math.max(90, width / 3);
        renderPresetList(graphics, split, mouseX, mouseY);
        renderPreview(graphics, split);
        renderFooter(graphics);
    }

    private void renderPresetList(
            GuiGraphicsExtractor graphics,
            int split,
            int mouseX,
            int mouseY
    ) {
        List<PresetCatalog26.Preset> presets = PresetCatalog26.all();
        for (int index = 0; index < presets.size(); index++) {
            PresetCatalog26.Preset preset = presets.get(index);
            int y = HEADER_HEIGHT + 2 + index * ROW_HEIGHT;
            if (y + ROW_HEIGHT > height - FOOTER_HEIGHT) {
                break;
            }
            boolean hovered = mouseX >= EDGE && mouseX < split - 4
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            boolean isSelected = preset == selected;
            graphics.fill(EDGE, y, split - 4, y + ROW_HEIGHT - 2,
                    isSelected ? 0xCC3D5269 : hovered ? 0xCC344A62 : 0xAA27394C);
            graphics.text(font, trim(preset.name(), split - EDGE - 12), EDGE + 5, y + 4,
                    0xFFE8F3FF, true);
            graphics.text(font, trim(preset.description(), split - EDGE - 12), EDGE + 5, y + 15,
                    0xFF8FA4B8, false);
            if (hovered) {
                status = preset.description();
            }
        }
    }

    private void renderPreview(GuiGraphicsExtractor graphics, int split) {
        int left = split;
        int top = HEADER_HEIGHT + 2;
        int bottom = height - FOOTER_HEIGHT;
        graphics.fill(left, top, width - EDGE, bottom, 0x80131B25);

        if (preview == null) {
            graphics.centeredText(font, "Select a preset", (left + width - EDGE) / 2,
                    top + 10, 0xFF93A7BC);
            return;
        }

        graphics.text(font, preview.presetName(), left + 6, top + 4, 0xFFFFFFFF, true);
        String risk = "Highest enabled risk: " + preview.riskLabel();
        graphics.text(font, risk, left + 6, top + 15,
                preview.requiresConfirmation() ? 0xFFFF8B7C : 0xFF8FA4B8, false);

        if (preview.isNoOp()) {
            graphics.text(font, "Already applied; nothing would change",
                    left + 6, top + 28, 0xFF9BE8C4, false);
            return;
        }

        List<String> lines = previewLines();
        int rows = Math.max(1, (bottom - top - 30) / LINE_HEIGHT);
        previewScroll = ClientScreen26Model.clampScroll(previewScroll, lines.size(), rows);
        for (int row = 0; row < rows && previewScroll + row < lines.size(); row++) {
            String line = lines.get(previewScroll + row);
            graphics.text(
                    font,
                    trim(line, width - EDGE - left - 12),
                    left + 6,
                    top + 28 + row * LINE_HEIGHT,
                    line.startsWith("Skipped") ? 0xFFDDCF91 : 0xFFCEDCEA,
                    false
            );
        }
    }

    private List<String> previewLines() {
        List<String> lines = new java.util.ArrayList<>();
        preview.changes().forEach(change -> lines.add(change.summary()));
        preview.skipped().forEach(skip -> lines.add("Skipped " + skip));
        return List.copyOf(lines);
    }

    private void renderFooter(GuiGraphicsExtractor graphics) {
        graphics.fill(0, height - FOOTER_HEIGHT, width, height, 0xEE111821);
        String action = preview == null
                ? ""
                : preview.isNoOp()
                ? ""
                : awaitingConfirmation
                ? "Enter: confirm apply   Escape: cancel"
                : "Enter: apply";
        String undo = runtime.presets().canUndo()
                ? "   U: undo " + runtime.presets().undoLabel()
                : "";
        graphics.text(font, trim(action + undo, width - 10), 5, height - 22, 0xFF9BE8C4, false);
        graphics.text(font, trim(status, width - 10), 5, height - 11, 0xFFBAC8D6, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int split = Math.max(90, width / 3);
        if (event.x() >= EDGE && event.x() < split - 4 && event.y() >= HEADER_HEIGHT + 2) {
            int index = ((int) event.y() - HEADER_HEIGHT - 2) / ROW_HEIGHT;
            List<PresetCatalog26.Preset> presets = PresetCatalog26.all();
            if (index >= 0 && index < presets.size()) {
                select(presets.get(index));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void select(PresetCatalog26.Preset preset) {
        selected = preset;
        preview = PresetApplication26.preview(preset, runtime.modules());
        awaitingConfirmation = false;
        previewScroll = 0;
        status = preview.isNoOp()
                ? preset.name() + " would change nothing"
                : preview.changes().size() + " module change(s) — Enter to apply";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (preview != null) {
            previewScroll -= (int) Math.signum(vertical) * 2;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_U) {
            undo();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            applyOrConfirm();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (awaitingConfirmation) {
                awaitingConfirmation = false;
                status = "Apply cancelled; nothing was changed";
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void applyOrConfirm() {
        if (selected == null || preview == null) {
            status = "Select a preset first";
            return;
        }
        if (preview.isNoOp()) {
            status = selected.name() + " is already applied";
            return;
        }
        if (preview.requiresConfirmation() && !awaitingConfirmation) {
            awaitingConfirmation = true;
            status = selected.name() + " enables " + preview.riskLabel()
                    + "-risk modules — press Enter again to confirm";
            return;
        }
        awaitingConfirmation = false;
        runtime.presets()
                .apply(selected, runtime.modules())
                .ifPresentOrElse(
                        failure -> status = "Not applied: " + failure,
                        () -> {
                            runtime.clearBaritoneConfirmation();
                            status = saved("Applied " + selected.name() + " — press U to undo");
                        }
                );
        preview = PresetApplication26.preview(selected, runtime.modules());
    }

    private void undo() {
        if (!runtime.presets().canUndo()) {
            status = "Nothing to undo";
            return;
        }
        String label = runtime.presets().undoLabel();
        runtime.presets()
                .undo(runtime.modules())
                .ifPresentOrElse(
                        failure -> status = failure,
                        () -> {
                            runtime.clearBaritoneConfirmation();
                            status = saved("Reverted " + label);
                        }
                );
        if (selected != null) {
            preview = PresetApplication26.preview(selected, runtime.modules());
        }
        awaitingConfirmation = false;
    }

    private String saved(String message) {
        return runtime.save() ? message : message + " (memory only; config save failed)";
    }

    private String trim(String value, int availableWidth) {
        if (availableWidth <= 0) {
            return "";
        }
        if (font.width(value) <= availableWidth) {
            return value;
        }
        String suffix = "...";
        return font.plainSubstrByWidth(value, Math.max(0, availableWidth - font.width(suffix)))
                + suffix;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        awaitingConfirmation = false;
        super.removed();
    }
}
