package dev.b2tclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.b2tclient.config.BuiltInPresetCatalog;
import dev.b2tclient.config.ConfigManager;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.ColorSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.EnumSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.core.setting.Setting;
import dev.b2tclient.core.setting.StringListSetting;
import dev.b2tclient.core.setting.StringSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ClickGuiScreen extends Screen {
    private static final int MAX_WIDTH = 720;
    private static final int MAX_HEIGHT = 440;
    private static final int HEADER_HEIGHT = 30;
    private static final int SECTION_HEADER_HEIGHT = 25;
    private static final int SIDEBAR_WIDTH = 112;
    private static final int CATEGORY_HEIGHT = 25;
    private static final int MODULE_HEIGHT = 24;
    private static final int SETTING_HEIGHT = 19;
    private static final int PRESET_CARD_HEIGHT = 52;
    private static final int PRESET_ACTION_HEIGHT = 22;
    private static final int PADDING = 9;
    private static final long RISK_CONFIRMATION_MILLIS = 5_000L;

    private static final int COLOR_BACKDROP = 0xB0080B0F;
    private static final int COLOR_WINDOW = 0xF0151920;
    private static final int COLOR_HEADER = 0xFF1C222B;
    private static final int COLOR_SIDEBAR = 0xFF12161C;
    private static final int COLOR_CATEGORY = 0xFF1A2028;
    private static final int COLOR_CATEGORY_ACTIVE = 0xFF28564E;
    private static final int COLOR_MODULE = 0xFF202731;
    private static final int COLOR_MODULE_HOVER = 0xFF2B3541;
    private static final int COLOR_SETTING = 0xFF171D24;
    private static final int COLOR_SETTING_HOVER = 0xFF26313C;
    private static final int COLOR_ACCENT = 0xFF55D6BE;
    private static final int COLOR_DANGER = 0xFFFF7B72;
    private static final int COLOR_WARNING = 0xFFFFC857;
    private static final int COLOR_MOVEMENT = 0xFFFF9F43;
    private static final int COLOR_PACKET = 0xFFD980FA;
    private static final int COLOR_TEXT = 0xFFF3F5F7;
    private static final int COLOR_MUTED = 0xFFAAB4BF;
    private static final int MAX_SEARCH_LENGTH = 64;
    private static final int MAX_LIST_EDIT_LENGTH = 512;

    private final ModuleManager moduleManager;
    private final ConfigManager configManager;
    private final Set<String> expandedModules = new HashSet<>();
    private final Map<Category, Integer> scrollOffsets = new EnumMap<>(Category.class);

    private Category selectedCategory = Category.HUD;
    private Module bindingModule;
    private Setting<?> editingSetting;
    private String editBuffer = "";
    private String searchQuery = "";
    private boolean searchFocused;
    private boolean presetView;
    private String selectedPresetId = BuiltInPresetCatalog.LOW_LAG_UTILITY_ID;
    private long riskyPresetArmedUntil;
    private long portableRiskyArmedUntil;
    private String portableRiskyPayload;
    private String presetStatus = "Select a preset to preview its changes.";
    private String hoveredDescription;
    private Layout cachedLayout;

    public ClickGuiScreen(ModuleManager moduleManager, ConfigManager configManager) {
        super(Component.literal("B2T Client"));
        this.moduleManager = moduleManager;
        this.configManager = configManager;
    }

    @Override
    protected void init() {
        super.init();
        cachedLayout = computeLayout();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Layout layout = layout();
        hoveredDescription = null;

        graphics.fill(0, 0, width, height, COLOR_BACKDROP);
        graphics.fill(layout.left, layout.top, layout.right, layout.bottom, COLOR_WINDOW);
        graphics.fill(
                layout.left,
                layout.top,
                layout.right,
                layout.top + HEADER_HEIGHT,
                COLOR_HEADER
        );
        String product = "B2T Client";
        graphics.drawString(font, product, layout.left + PADDING, layout.top + 10,
                COLOR_ACCENT, true);
        String profile = "Profile: " + configManager.activeProfile();
        int profileX = layout.left + PADDING + font.width(product) + 10;
        String hint;
        if (bindingModule != null) {
            hint = "Press a key • Esc clears";
        } else if (editingSetting != null) {
            hint = "Enter saves • Esc cancels";
        } else if (searchFocused) {
            hint = "Type to search • Enter done";
        } else if (presetView) {
            hint = "Preview before applying • P / Esc";
        } else {
            hint = "/ Search • P / Esc";
        }
        int hintX = layout.right - font.width(hint) - PADDING;
        int profileWidth = Math.max(0, hintX - profileX - 8);
        if (profileWidth > font.width("Profile: …")) {
            graphics.drawString(
                    font,
                    trimToWidth(profile, profileWidth),
                    profileX,
                    layout.top + 10,
                    COLOR_MUTED,
                    false
            );
        }
        graphics.drawString(
                font,
                hint,
                hintX,
                layout.top + 10,
                bindingModule == null && editingSetting == null
                        ? (searchFocused ? COLOR_ACCENT : COLOR_MUTED)
                        : COLOR_DANGER,
                false
        );

        renderSidebar(graphics, layout, mouseX, mouseY);
        if (presetView) {
            renderPresetContent(graphics, layout, mouseX, mouseY);
        } else {
            renderContent(graphics, layout, mouseX, mouseY);
        }

        if (hoveredDescription != null) {
            int tooltipY = Math.min(height - 24, layout.bottom + 4);
            int tooltipWidth = Math.min(
                    width - 12,
                    font.width(hoveredDescription) + 14
            );
            graphics.fill(6, tooltipY, 6 + tooltipWidth, tooltipY + 18, 0xF020252D);
            graphics.drawString(
                    font,
                    trimToWidth(hoveredDescription, tooltipWidth - 12),
                    12,
                    tooltipY + 5,
                    COLOR_MUTED,
                    false
            );
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSidebar(
            GuiGraphics graphics,
            Layout layout,
            int mouseX,
            int mouseY
    ) {
        graphics.fill(
                layout.left,
                layout.contentTop,
                layout.mainLeft,
                layout.bottom,
                COLOR_SIDEBAR
        );

        int y = layout.contentTop + PADDING;
        for (Category category : Category.values()) {
            boolean hovered = contains(
                    mouseX,
                    mouseY,
                    layout.left + 5,
                    y,
                    SIDEBAR_WIDTH - 10,
                    CATEGORY_HEIGHT - 2
            );
            int color = category == selectedCategory && !presetView
                    ? COLOR_CATEGORY_ACTIVE
                    : hovered ? COLOR_MODULE_HOVER : COLOR_CATEGORY;
            graphics.fill(
                    layout.left + 5,
                    y,
                    layout.mainLeft - 5,
                    y + CATEGORY_HEIGHT - 2,
                    color
            );
            graphics.drawString(
                    font,
                    category.displayName(),
                    layout.left + 12,
                    y + 8,
                    COLOR_TEXT,
                    category == selectedCategory && !presetView
            );
            y += CATEGORY_HEIGHT;
        }

        boolean hovered = contains(
                mouseX,
                mouseY,
                layout.left + 5,
                y + 5,
                SIDEBAR_WIDTH - 10,
                CATEGORY_HEIGHT - 2
        );
        graphics.fill(
                layout.left + 5,
                y + 5,
                layout.mainLeft - 5,
                y + CATEGORY_HEIGHT + 3,
                presetView
                        ? COLOR_CATEGORY_ACTIVE
                        : hovered ? COLOR_MODULE_HOVER : COLOR_CATEGORY
        );
        graphics.drawString(
                font,
                "Presets",
                layout.left + 12,
                y + 13,
                presetView ? COLOR_ACCENT : COLOR_TEXT,
                presetView
        );
    }

    private void renderPresetContent(
            GuiGraphics graphics,
            Layout layout,
            int mouseX,
            int mouseY
    ) {
        List<ConfigManager.PresetInfo> presets = configManager.builtInPresets();
        ConfigManager.PresetPreview preview = configManager.previewPreset(selectedPresetId);
        graphics.drawString(
                font,
                "Built-in Presets",
                layout.mainLeft + PADDING,
                layout.contentTop + 8,
                COLOR_TEXT,
                true
        );
        String count = presets.size() + " local presets";
        graphics.drawString(
                font,
                count,
                layout.right - font.width(count) - PADDING,
                layout.contentTop + 8,
                COLOR_MUTED,
                false
        );

        int x = layout.mainLeft + PADDING;
        int rowWidth = Math.max(80, layout.right - x - PADDING);
        int y = layout.listTop;
        for (ConfigManager.PresetInfo preset : presets) {
            boolean selected = preset.id().equals(selectedPresetId);
            boolean hovered = contains(
                    mouseX,
                    mouseY,
                    x,
                    y,
                    rowWidth,
                    PRESET_CARD_HEIGHT - 3
            );
            graphics.fill(
                    x,
                    y,
                    x + rowWidth,
                    y + PRESET_CARD_HEIGHT - 3,
                    selected ? COLOR_CATEGORY_ACTIVE
                            : hovered ? COLOR_MODULE_HOVER : COLOR_MODULE
            );
            graphics.fill(
                    x,
                    y,
                    x + 3,
                    y + PRESET_CARD_HEIGHT - 3,
                    selected ? COLOR_ACCENT : 0xFF46515D
            );
            graphics.drawString(font, preset.name(), x + 9, y + 7, COLOR_TEXT, selected);
            String id = preset.id();
            graphics.drawString(
                    font,
                    id,
                    x + rowWidth - font.width(id) - 8,
                    y + 7,
                    COLOR_MUTED,
                    false
            );
            graphics.drawString(
                    font,
                    trimToWidth(preset.description(), rowWidth - 18),
                    x + 9,
                    y + 24,
                    COLOR_MUTED,
                    false
            );
            if (hovered) {
                hoveredDescription = "Click to preview. Applying is always a separate action.";
            }
            y += PRESET_CARD_HEIGHT;
        }

        PresetActionLayout actions = presetActionLayout(layout);
        int actionTop = actions.applyY();
        int previewTop = y + 3;
        int previewBottom = actionTop - 19;
        if (previewBottom > previewTop) {
            String summary = preview.changes().size() + " changes"
                    + "  •  " + preview.riskyEnableCount() + " risky enables"
                    + (preview.missingModuleCount() > 0
                    ? "  •  " + preview.missingModuleCount() + " unavailable"
                    : "");
            graphics.drawString(font, "Preview: " + summary, x, previewTop,
                    COLOR_WARNING, false);
            int changeY = previewTop + 15;
            graphics.enableScissor(x, changeY, x + rowWidth, previewBottom);
            for (ConfigManager.PresetChange change : preview.changes()) {
                if (changeY + 10 > previewBottom) {
                    break;
                }
                String marker = change.requiresRiskConfirmation() ? "! " : "  ";
                String text = marker + change.moduleName() + " • " + change.field()
                        + ": " + change.before() + " → " + change.after();
                graphics.drawString(
                        font,
                        trimToWidth(text, rowWidth),
                        x,
                        changeY,
                        change.requiresRiskConfirmation() ? COLOR_DANGER : COLOR_MUTED,
                        false
                );
                changeY += 12;
            }
            graphics.disableScissor();
        }

        String status = trimToWidth(presetStatus, rowWidth);
        graphics.drawString(
                font,
                status,
                x,
                actionTop - 14,
                presetStatus.startsWith("Failed") ? COLOR_DANGER : COLOR_MUTED,
                false
        );
        renderPresetActions(graphics, actions, preview, mouseX, mouseY);
    }

    private void renderPresetActions(
            GuiGraphics graphics,
            PresetActionLayout actions,
            ConfigManager.PresetPreview preview,
            int mouseX,
            int mouseY
    ) {
        drawButton(
                graphics,
                actions.safeX(),
                actions.applyY(),
                actions.buttonWidth(),
                "Apply safe",
                contains(mouseX, mouseY, actions.safeX(), actions.applyY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                COLOR_ACCENT
        );
        boolean armed = riskyConfirmationArmed();
        String riskyLabel = preview.riskyEnableCount() == 0
                ? "Apply all"
                : armed ? "Confirm risky" : "Enable risky";
        drawButton(
                graphics,
                actions.riskyX(),
                actions.applyY(),
                actions.buttonWidth(),
                riskyLabel,
                contains(mouseX, mouseY, actions.riskyX(), actions.applyY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                preview.riskyEnableCount() == 0 ? COLOR_ACCENT : COLOR_DANGER
        );
        drawButton(
                graphics,
                actions.undoX(),
                actions.applyY(),
                actions.buttonWidth(),
                configManager.canUndoPreset() ? "Undo preset" : "No undo",
                contains(mouseX, mouseY, actions.undoX(), actions.applyY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                configManager.canUndoPreset() ? COLOR_WARNING : COLOR_MUTED
        );
        drawButton(
                graphics,
                actions.safeX(),
                actions.transferY(),
                actions.buttonWidth(),
                "Copy profile",
                contains(mouseX, mouseY, actions.safeX(), actions.transferY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                COLOR_ACCENT
        );
        drawButton(
                graphics,
                actions.riskyX(),
                actions.transferY(),
                actions.buttonWidth(),
                "Import safe",
                contains(mouseX, mouseY, actions.riskyX(), actions.transferY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                COLOR_WARNING
        );
        drawButton(
                graphics,
                actions.undoX(),
                actions.transferY(),
                actions.buttonWidth(),
                portableRiskyConfirmationArmed() ? "Confirm import" : "Import risky",
                contains(mouseX, mouseY, actions.undoX(), actions.transferY(),
                        actions.buttonWidth(), PRESET_ACTION_HEIGHT),
                COLOR_DANGER
        );
    }

    private void drawButton(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            boolean hovered,
            int textColor
    ) {
        graphics.fill(
                x,
                y,
                x + width,
                y + PRESET_ACTION_HEIGHT,
                hovered ? COLOR_MODULE_HOVER : COLOR_SETTING
        );
        graphics.drawString(
                font,
                trimToWidth(label, width - 10),
                x + 5,
                y + 7,
                textColor,
                false
        );
    }

    private void renderContent(
            GuiGraphics graphics,
            Layout layout,
            int mouseX,
            int mouseY
    ) {
        List<Module> allModules = moduleManager.inCategory(selectedCategory);
        List<Module> modules = visibleModules();
        int enabled = 0;
        for (Module module : modules) {
            if (module.isEnabled()) {
                enabled++;
            }
        }
        String title = searchFocused || !searchQuery.isBlank()
                ? "Search: " + (searchQuery.isEmpty() ? "type a module…" : searchQuery)
                        + (searchFocused ? "_" : "")
                : selectedCategory.displayName();
        String count = searchQuery.isBlank()
                ? enabled + " enabled / " + modules.size() + " modules"
                : modules.size() + " shown / " + allModules.size();

        graphics.drawString(
                font,
                trimToWidth(title, Math.max(40, layout.right - layout.mainLeft
                        - font.width(count) - PADDING * 3)),
                layout.mainLeft + PADDING,
                layout.contentTop + 8,
                searchFocused ? COLOR_ACCENT : COLOR_TEXT,
                true
        );
        graphics.drawString(
                font,
                count,
                layout.right - font.width(count) - PADDING,
                layout.contentTop + 8,
                COLOR_MUTED,
                false
        );

        int viewportHeight = Math.max(0, layout.listBottom - layout.listTop);
        int contentHeight = totalContentHeight(modules);
        int maximumScroll = Math.max(0, contentHeight - viewportHeight);
        int scroll = Math.max(0, Math.min(maximumScroll, scroll()));
        scrollOffsets.put(selectedCategory, scroll);

        graphics.enableScissor(
                layout.mainLeft,
                layout.listTop,
                layout.right,
                layout.listBottom
        );
        int x = layout.mainLeft + PADDING;
        int rowWidth = Math.max(80, layout.right - x - PADDING);
        int y = layout.listTop - scroll;

        for (Module module : modules) {
            renderModuleRow(graphics, module, x, y, rowWidth, mouseX, mouseY, layout);
            y += MODULE_HEIGHT;

            if (!expandedModules.contains(module.id())) {
                continue;
            }

            renderBindingRow(graphics, module, x, y, rowWidth, mouseX, mouseY, layout);
            y += SETTING_HEIGHT;
            for (Setting<?> setting : module.settings()) {
                if (!setting.isVisible()) {
                    continue;
                }
                renderSettingRow(graphics, setting, x, y, rowWidth, mouseX, mouseY, layout);
                y += SETTING_HEIGHT;
            }
            y += 3;
        }
        graphics.disableScissor();

        if (maximumScroll > 0 && viewportHeight > 0) {
            int trackTop = layout.listTop;
            int trackHeight = viewportHeight;
            int thumbHeight = Math.max(22, trackHeight * viewportHeight
                    / Math.max(viewportHeight, contentHeight));
            int travel = Math.max(1, trackHeight - thumbHeight);
            int thumbTop = trackTop + scroll * travel / maximumScroll;
            graphics.fill(
                    layout.right - 3,
                    thumbTop,
                    layout.right - 1,
                    thumbTop + thumbHeight,
                    COLOR_ACCENT
            );
        }
    }

    private void renderModuleRow(
            GuiGraphics graphics,
            Module module,
            int x,
            int y,
            int rowWidth,
            int mouseX,
            int mouseY,
            Layout layout
    ) {
        if (!intersects(y, MODULE_HEIGHT, layout.listTop, layout.listBottom)) {
            return;
        }
        boolean hovered = contains(mouseX, mouseY, x, y, rowWidth, MODULE_HEIGHT - 2);
        graphics.fill(
                x,
                y,
                x + rowWidth,
                y + MODULE_HEIGHT - 2,
                hovered ? COLOR_MODULE_HOVER : COLOR_MODULE
        );
        graphics.fill(
                x,
                y,
                x + 3,
                y + MODULE_HEIGHT - 2,
                module.isEnabled() ? COLOR_ACCENT : 0xFF46515D
        );
        String state = module.isEnabled() ? "ON" : "OFF";
        int stateColor = module.isEnabled() ? COLOR_ACCENT : COLOR_MUTED;
        int expandX = x + rowWidth - 14;
        int stateX = expandX - font.width(state) - 10;
        String risk = riskLabel(module.risk());
        int riskX = stateX - font.width(risk) - 10;
        String favorite = module.isFavorite() ? "★" : "";
        int nameX = x + 9;
        int nameWidth = Math.max(12, riskX - nameX - 8);
        String moduleName = favorite.isEmpty() ? module.name() : favorite + " " + module.name();
        graphics.drawString(
                font,
                trimToWidth(moduleName, nameWidth),
                nameX,
                y + 7,
                module.isFavorite() ? COLOR_ACCENT : COLOR_TEXT,
                false
        );
        graphics.drawString(font, risk, riskX, y + 7, riskColor(module.risk()), false);
        graphics.drawString(
                font,
                expandedModules.contains(module.id()) ? "−" : "+",
                expandX,
                y + 7,
                COLOR_MUTED,
                false
        );
        graphics.drawString(
                font,
                state,
                stateX,
                y + 7,
                stateColor,
                true
        );
        if (hovered && inViewport(mouseY, layout)) {
            hoveredDescription = module.description()
                    + "  •  " + riskLabel(module.risk())
                    + " risk  •  Left: toggle, Right: settings, Middle: favorite";
        }
    }

    private void renderBindingRow(
            GuiGraphics graphics,
            Module module,
            int x,
            int y,
            int rowWidth,
            int mouseX,
            int mouseY,
            Layout layout
    ) {
        if (!intersects(y, SETTING_HEIGHT, layout.listTop, layout.listBottom)) {
            return;
        }
        boolean hovered = contains(mouseX, mouseY, x, y, rowWidth, SETTING_HEIGHT);
        graphics.fill(
                x,
                y,
                x + rowWidth,
                y + SETTING_HEIGHT,
                hovered ? COLOR_SETTING_HOVER : COLOR_SETTING
        );
        graphics.drawString(font, "Key bind", x + 12, y + 5, COLOR_MUTED, false);
        String value = bindingModule == module ? "..." : keyName(module.keyCode());
        graphics.drawString(
                font,
                value,
                x + rowWidth - font.width(value) - 9,
                y + 5,
                bindingModule == module ? COLOR_DANGER : COLOR_TEXT,
                false
        );
        if (hovered && inViewport(mouseY, layout)) {
            hoveredDescription = "Click, then press a key. Escape removes the binding.";
        }
    }

    private void renderSettingRow(
            GuiGraphics graphics,
            Setting<?> setting,
            int x,
            int y,
            int rowWidth,
            int mouseX,
            int mouseY,
            Layout layout
    ) {
        if (!intersects(y, SETTING_HEIGHT, layout.listTop, layout.listBottom)) {
            return;
        }
        boolean hovered = contains(mouseX, mouseY, x, y, rowWidth, SETTING_HEIGHT);
        graphics.fill(
                x,
                y,
                x + rowWidth,
                y + SETTING_HEIGHT,
                hovered ? COLOR_SETTING_HOVER : COLOR_SETTING
        );
        graphics.drawString(font, setting.name(), x + 12, y + 5, COLOR_MUTED, false);
        String value = setting == editingSetting
                ? "> " + editBuffer + "_"
                : settingValue(setting);
        int valueWidth = Math.max(36, rowWidth / 2);
        value = trimToWidth(value, valueWidth);
        int valueX = x + rowWidth - font.width(value) - 9;
        if (setting instanceof ColorSetting colorSetting && setting != editingSetting) {
            int swatchRight = valueX - 5;
            graphics.fill(swatchRight - 10, y + 4, swatchRight, y + 15, 0xFF65717E);
            graphics.fill(swatchRight - 9, y + 5, swatchRight - 1, y + 14,
                    colorSetting.get());
        }
        graphics.drawString(
                font,
                value,
                valueX,
                y + 5,
                setting == editingSetting ? COLOR_ACCENT : COLOR_TEXT,
                false
        );
        if (hovered && inViewport(mouseY, layout)) {
            hoveredDescription = settingHelp(setting);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout layout = layout();
        int categoryY = layout.contentTop + PADDING;
        for (Category category : Category.values()) {
            if (contains(
                    mouseX,
                    mouseY,
                    layout.left + 5,
                    categoryY,
                    SIDEBAR_WIDTH - 10,
                    CATEGORY_HEIGHT - 2
            )) {
                selectedCategory = category;
                presetView = false;
                bindingModule = null;
                cancelSettingEdit();
                return true;
            }
            categoryY += CATEGORY_HEIGHT;
        }
        int presetY = categoryY + 5;
        if (contains(
                mouseX,
                mouseY,
                layout.left + 5,
                presetY,
                SIDEBAR_WIDTH - 10,
                CATEGORY_HEIGHT - 2
        )) {
            presetView = true;
            bindingModule = null;
            searchFocused = false;
            cancelSettingEdit();
            return true;
        }

        if (presetView) {
            return presetMouseClicked(layout, mouseX, mouseY, button);
        }

        if (mouseX < layout.mainLeft
                || mouseX >= layout.right
                || mouseY < layout.listTop
                || mouseY >= layout.listBottom) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int x = layout.mainLeft + PADDING;
        int rowWidth = Math.max(80, layout.right - x - PADDING);
        int y = layout.listTop - scroll();
        for (Module module : visibleModules()) {
            if (contains(mouseX, mouseY, x, y, rowWidth, MODULE_HEIGHT - 2)) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    module.toggle(minecraft);
                    configManager.save();
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    if (!expandedModules.add(module.id())) {
                        expandedModules.remove(module.id());
                    }
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                    module.setFavorite(!module.isFavorite());
                    configManager.save();
                }
                return true;
            }
            y += MODULE_HEIGHT;

            if (!expandedModules.contains(module.id())) {
                continue;
            }

            if (contains(mouseX, mouseY, x, y, rowWidth, SETTING_HEIGHT)) {
                bindingModule = module;
                cancelSettingEdit();
                return true;
            }
            y += SETTING_HEIGHT;

            for (Setting<?> setting : module.settings()) {
                if (!setting.isVisible()) {
                    continue;
                }
                if (contains(mouseX, mouseY, x, y, rowWidth, SETTING_HEIGHT)) {
                    if (isTextSetting(setting)) {
                        beginSettingEdit(setting);
                    } else {
                        changeSetting(setting, button, hasShiftDown());
                        configManager.save();
                    }
                    return true;
                }
                y += SETTING_HEIGHT;
            }
            y += 3;
        }
        return true;
    }

    private boolean presetMouseClicked(
            Layout layout,
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || mouseX < layout.mainLeft
                || mouseX >= layout.right
                || mouseY < layout.listTop
                || mouseY >= layout.listBottom) {
            return true;
        }

        int x = layout.mainLeft + PADDING;
        int rowWidth = Math.max(80, layout.right - x - PADDING);
        int y = layout.listTop;
        for (ConfigManager.PresetInfo preset : configManager.builtInPresets()) {
            if (contains(mouseX, mouseY, x, y, rowWidth, PRESET_CARD_HEIGHT - 3)) {
                selectedPresetId = preset.id();
                riskyPresetArmedUntil = 0L;
                clearPortableRiskConfirmation();
                presetStatus = "Preview ready. Choose an explicit apply action below.";
                return true;
            }
            y += PRESET_CARD_HEIGHT;
        }

        PresetActionLayout actions = presetActionLayout(layout);
        if (contains(mouseX, mouseY, actions.safeX(), actions.applyY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            applySelectedPreset(false);
            return true;
        }
        if (contains(mouseX, mouseY, actions.riskyX(), actions.applyY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            ConfigManager.PresetPreview preview = configManager.previewPreset(selectedPresetId);
            if (preview.riskyEnableCount() > 0 && !riskyConfirmationArmed()) {
                riskyPresetArmedUntil = System.currentTimeMillis()
                        + RISK_CONFIRMATION_MILLIS;
                presetStatus = "Risky modules are armed. Click Confirm risky within 5 seconds.";
            } else {
                applySelectedPreset(true);
            }
            return true;
        }
        if (contains(mouseX, mouseY, actions.undoX(), actions.applyY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            riskyPresetArmedUntil = 0L;
            clearPortableRiskConfirmation();
            presetStatus = configManager.undoPreset(minecraft)
                    ? "Restored the profile snapshot from before the last preset."
                    : "No preset snapshot is available for this profile.";
            return true;
        }
        if (contains(mouseX, mouseY, actions.safeX(), actions.transferY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            riskyPresetArmedUntil = 0L;
            clearPortableRiskConfirmation();
            minecraft.keyboardHandler.setClipboard(configManager.exportActiveProfile());
            presetStatus = "Copied this profile only; friends and waypoints were excluded.";
            return true;
        }
        if (contains(mouseX, mouseY, actions.riskyX(), actions.transferY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            importClipboardProfile(false);
            return true;
        }
        if (contains(mouseX, mouseY, actions.undoX(), actions.transferY(),
                actions.buttonWidth(), PRESET_ACTION_HEIGHT)) {
            armOrImportRiskyClipboardProfile();
            return true;
        }
        return true;
    }

    private void applySelectedPreset(boolean confirmRisky) {
        riskyPresetArmedUntil = 0L;
        clearPortableRiskConfirmation();
        ConfigManager.PresetApplyResult result = configManager.applyPreset(
                selectedPresetId,
                minecraft,
                confirmRisky
        );
        if (!result.successful()) {
            presetStatus = "Failed at " + result.failedModuleId()
                    + "; every preset change was rolled back.";
        } else if (!result.changed()) {
            presetStatus = result.skippedRiskyEnables() > 0
                    ? "No safe changes; risky enables were left off."
                    : "This profile already matches the selected preset.";
        } else {
            presetStatus = "Applied " + result.moduleChanges() + " module and "
                    + result.settingChanges() + " setting changes"
                    + (result.skippedRiskyEnables() > 0
                    ? "; " + result.skippedRiskyEnables() + " risky enables stayed off."
                    : ".");
        }
    }

    private boolean riskyConfirmationArmed() {
        return riskyPresetArmedUntil >= System.currentTimeMillis();
    }

    private void importClipboardProfile(boolean confirmRisky) {
        riskyPresetArmedUntil = 0L;
        String payload = minecraft.keyboardHandler.getClipboard();
        try {
            ConfigManager.PortableProfileApplyResult result =
                    configManager.importPortableProfile(payload, minecraft, confirmRisky);
            clearPortableRiskConfirmation();
            if (!result.successful()) {
                presetStatus = "Failed at " + result.failedModuleId()
                        + "; every imported change was rolled back.";
            } else if (!result.changed()) {
                presetStatus = result.skippedRiskyEnables() > 0
                        ? "No safe changes; risky imported modules stayed off."
                        : "The clipboard profile already matches this profile.";
            } else {
                presetStatus = "Imported " + result.moduleChanges() + " module and "
                        + result.settingChanges() + " setting changes"
                        + (result.skippedRiskyEnables() > 0
                        ? "; " + result.skippedRiskyEnables() + " risky enables stayed off."
                        : ".");
            }
        } catch (IllegalArgumentException exception) {
            clearPortableRiskConfirmation();
            presetStatus = "Failed: clipboard does not contain a valid B2T profile.";
        }
    }

    private void armOrImportRiskyClipboardProfile() {
        riskyPresetArmedUntil = 0L;
        String payload = minecraft.keyboardHandler.getClipboard();
        try {
            ConfigManager.PortableProfilePreview preview =
                    configManager.previewPortableProfile(payload);
            if (preview.riskyEnableCount() == 0) {
                importClipboardProfile(true);
                return;
            }
            if (portableRiskyConfirmationArmed()
                    && payload.equals(portableRiskyPayload)) {
                importClipboardProfile(true);
                return;
            }
            portableRiskyPayload = payload;
            portableRiskyArmedUntil = System.currentTimeMillis()
                    + RISK_CONFIRMATION_MILLIS;
            presetStatus = preview.riskyEnableCount()
                    + " risky imported modules armed. Confirm within 5 seconds.";
        } catch (IllegalArgumentException exception) {
            clearPortableRiskConfirmation();
            presetStatus = "Failed: clipboard does not contain a valid B2T profile.";
        }
    }

    private boolean portableRiskyConfirmationArmed() {
        return portableRiskyPayload != null
                && portableRiskyArmedUntil >= System.currentTimeMillis();
    }

    private void clearPortableRiskConfirmation() {
        portableRiskyArmedUntil = 0L;
        portableRiskyPayload = null;
    }

    private PresetActionLayout presetActionLayout(Layout layout) {
        int x = layout.mainLeft + PADDING;
        int width = Math.max(80, layout.right - x - PADDING);
        int gap = 4;
        int buttonWidth = Math.max(30, (width - gap * 2) / 3);
        int riskyX = x + buttonWidth + gap;
        int undoX = riskyX + buttonWidth + gap;
        return new PresetActionLayout(
                x,
                riskyX,
                undoX,
                buttonWidth,
                layout.listBottom - PRESET_ACTION_HEIGHT * 2 - gap,
                layout.listBottom - PRESET_ACTION_HEIGHT
        );
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount
    ) {
        if (presetView) {
            return true;
        }
        Layout layout = layout();
        if (mouseX >= layout.mainLeft
                && mouseX < layout.right
                && mouseY >= layout.listTop
                && mouseY < layout.listBottom) {
            int viewportHeight = Math.max(0, layout.listBottom - layout.listTop);
            int maximum = Math.max(
                    0,
                    totalContentHeight(visibleModules()) - viewportHeight
            );
            int requested = scroll() - (int) Math.round(verticalAmount * 28.0);
            scrollOffsets.put(selectedCategory, Math.max(0, Math.min(maximum, requested)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (bindingModule != null) {
            bindingModule.setKeyCode(
                    keyCode == GLFW.GLFW_KEY_ESCAPE ? GLFW.GLFW_KEY_UNKNOWN : keyCode
            );
            bindingModule = null;
            configManager.save();
            return true;
        }
        if (editingSetting != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelSettingEdit();
            } else if (keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitSettingEdit();
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !editBuffer.isEmpty()) {
                editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
            }
            return true;
        }
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchQuery = "";
                searchFocused = false;
                resetSearchScroll();
            } else if (keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchFocused = false;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                resetSearchScroll();
            } else if (keyCode == GLFW.GLFW_KEY_F && hasControlDown()) {
                searchFocused = false;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SLASH
                || (keyCode == GLFW.GLFW_KEY_F && hasControlDown())) {
            presetView = false;
            searchFocused = true;
            bindingModule = null;
            cancelSettingEdit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_P) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (Character.isISOControl(codePoint)) {
            return super.charTyped(codePoint, modifiers);
        }
        if (editingSetting != null) {
            int maximum = editingSetting instanceof StringSetting stringSetting
                    ? stringSetting.maximumLength()
                    : MAX_LIST_EDIT_LENGTH;
            if (editBuffer.length() < maximum) {
                editBuffer += codePoint;
            }
            return true;
        }
        if (searchFocused) {
            if (searchQuery.length() < MAX_SEARCH_LENGTH) {
                searchQuery += codePoint;
                resetSearchScroll();
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        bindingModule = null;
        cancelSettingEdit();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int totalContentHeight(List<Module> modules) {
        int height = 0;
        for (Module module : modules) {
            height += MODULE_HEIGHT;
            if (!expandedModules.contains(module.id())) {
                continue;
            }
            height += SETTING_HEIGHT + 3;
            for (Setting<?> setting : module.settings()) {
                if (setting.isVisible()) {
                    height += SETTING_HEIGHT;
                }
            }
        }
        return height;
    }

    private int scroll() {
        return scrollOffsets.getOrDefault(selectedCategory, 0);
    }

    private Layout layout() {
        if (cachedLayout == null) {
            cachedLayout = computeLayout();
        }
        return cachedLayout;
    }

    private Layout computeLayout() {
        int windowWidth = Math.max(260, Math.min(MAX_WIDTH, width - 12));
        int windowHeight = Math.max(220, Math.min(MAX_HEIGHT, height - 36));
        windowWidth = Math.min(windowWidth, width);
        windowHeight = Math.min(windowHeight, height);
        int left = Math.max(0, (width - windowWidth) / 2);
        int top = Math.max(0, (height - windowHeight) / 2 - 4);
        int right = Math.min(width, left + windowWidth);
        int bottom = Math.min(height, top + windowHeight);
        int mainLeft = Math.min(right - 120, left + SIDEBAR_WIDTH);
        int contentTop = top + HEADER_HEIGHT;
        int listTop = contentTop + SECTION_HEADER_HEIGHT;
        int listBottom = bottom - PADDING;
        return new Layout(left, top, right, bottom, mainLeft, contentTop, listTop, listBottom);
    }

    private String trimToWidth(String value, int availableWidth) {
        if (font.width(value) <= availableWidth) {
            return value;
        }
        String suffix = "…";
        int textWidth = Math.max(0, availableWidth - font.width(suffix));
        return font.plainSubstrByWidth(value, textWidth) + suffix;
    }

    private void beginSettingEdit(Setting<?> setting) {
        editingSetting = setting;
        bindingModule = null;
        if (setting instanceof StringListSetting listSetting) {
            editBuffer = String.join(", ", listSetting.get());
        } else {
            editBuffer = String.valueOf(setting.get());
        }
    }

    private void commitSettingEdit() {
        if (editingSetting instanceof StringSetting stringSetting) {
            stringSetting.set(editBuffer);
        } else if (editingSetting instanceof StringListSetting listSetting) {
            LinkedHashSet<String> values = Arrays.stream(editBuffer.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            listSetting.set(values);
        }
        editingSetting = null;
        editBuffer = "";
        configManager.save();
    }

    private void cancelSettingEdit() {
        editingSetting = null;
        editBuffer = "";
    }

    private List<Module> visibleModules() {
        List<Module> modules = moduleManager.inCategory(selectedCategory);
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return modules;
        }
        return modules.stream()
                .filter(module -> module.name().toLowerCase(Locale.ROOT).contains(query)
                        || module.id().toLowerCase(Locale.ROOT).contains(query)
                        || module.description().toLowerCase(Locale.ROOT).contains(query)
                        || module.risk().name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    private void resetSearchScroll() {
        scrollOffsets.put(selectedCategory, 0);
    }

    private static boolean isTextSetting(Setting<?> setting) {
        return setting instanceof StringSetting || setting instanceof StringListSetting;
    }

    private static void changeSetting(
            Setting<?> setting,
            int mouseButton,
            boolean shiftDown
    ) {
        int direction = mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
        if (setting instanceof BooleanSetting booleanSetting) {
            booleanSetting.toggle();
        } else if (setting instanceof IntegerSetting integerSetting) {
            integerSetting.increment(direction);
        } else if (setting instanceof DoubleSetting doubleSetting) {
            doubleSetting.increment(direction);
        } else if (setting instanceof EnumSetting<?> enumSetting) {
            enumSetting.cycle(direction);
        } else if (setting instanceof ColorSetting colorSetting) {
            if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                colorSetting.reset();
            } else if (shiftDown) {
                colorSetting.setChannels(
                        Math.max(0, Math.min(255, colorSetting.alpha() + direction * 17)),
                        colorSetting.red(),
                        colorSetting.green(),
                        colorSetting.blue()
                );
            } else {
                float[] hsb = Color.RGBtoHSB(
                        colorSetting.red(),
                        colorSetting.green(),
                        colorSetting.blue(),
                        null
                );
                float hue = (hsb[0] + direction / 24.0f + 1.0f) % 1.0f;
                int rgb = Color.HSBtoRGB(
                        hue,
                        Math.max(0.35f, hsb[1]),
                        Math.max(0.35f, hsb[2])
                );
                colorSetting.setChannels(
                        colorSetting.alpha(),
                        rgb >>> 16 & 0xff,
                        rgb >>> 8 & 0xff,
                        rgb & 0xff
                );
            }
        }
    }

    private static String settingValue(Setting<?> setting) {
        if (setting instanceof BooleanSetting booleanSetting) {
            return booleanSetting.get() ? "On" : "Off";
        }
        if (setting instanceof DoubleSetting doubleSetting) {
            return BigDecimal.valueOf(doubleSetting.get())
                    .stripTrailingZeros()
                    .toPlainString();
        }
        if (setting instanceof EnumSetting<?> enumSetting) {
            return enumSetting.get().name().replace('_', ' ');
        }
        if (setting instanceof ColorSetting colorSetting) {
            return String.format("#%08X", colorSetting.get());
        }
        if (setting instanceof StringSetting stringSetting) {
            return stringSetting.get().isEmpty() ? "(empty)" : stringSetting.get();
        }
        if (setting instanceof StringListSetting listSetting) {
            return listSetting.get().isEmpty()
                    ? "(empty)"
                    : String.join(", ", listSetting.get());
        }
        return String.valueOf(setting.get());
    }

    private static String settingHelp(Setting<?> setting) {
        if (setting instanceof StringSetting) {
            return setting.description() + "  •  Click to edit, Enter saves, Esc cancels";
        }
        if (setting instanceof StringListSetting) {
            return setting.description()
                    + "  •  Click to edit comma-separated values";
        }
        if (setting instanceof ColorSetting) {
            return setting.description()
                    + "  •  Left/Right: hue, Shift: alpha, Middle: reset";
        }
        return setting.description() + "  •  Left: next, Right: previous";
    }

    private static String riskLabel(ModuleRisk risk) {
        return switch (risk) {
            case PASSIVE -> "SAFE";
            case AUTOMATION -> "AUTO";
            case COMBAT -> "COMBAT";
            case MOVEMENT -> "MOVE";
            case PACKET -> "PACKET";
        };
    }

    private static int riskColor(ModuleRisk risk) {
        return switch (risk) {
            case PASSIVE -> COLOR_ACCENT;
            case AUTOMATION -> COLOR_WARNING;
            case COMBAT -> COLOR_DANGER;
            case MOVEMENT -> COLOR_MOVEMENT;
            case PACKET -> COLOR_PACKET;
        };
    }

    private static String keyName(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return "None";
        }
        return InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName().getString();
    }

    private static boolean inViewport(int mouseY, Layout layout) {
        return mouseY >= layout.listTop && mouseY < layout.listBottom;
    }

    private static boolean intersects(int y, int height, int top, int bottom) {
        return y + height > top && y < bottom;
    }

    private static boolean contains(
            double mouseX,
            double mouseY,
            int x,
            int y,
            int width,
            int height
    ) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private record Layout(
            int left,
            int top,
            int right,
            int bottom,
            int mainLeft,
            int contentTop,
            int listTop,
            int listBottom
    ) {
    }

    private record PresetActionLayout(
            int safeX,
            int riskyX,
            int undoX,
            int buttonWidth,
            int applyY,
            int transferY
    ) {
    }
}
