package dev.b2tclient.v26.gui;

import dev.b2tclient.common.module.RegisteredModule;
import dev.b2tclient.common.setting.BooleanSetting;
import dev.b2tclient.common.setting.DoubleSetting;
import dev.b2tclient.common.setting.IntegerSetting;
import dev.b2tclient.common.setting.Setting;
import dev.b2tclient.common.setting.StringSetting;
import dev.b2tclient.v26.ClientRuntime26;
import dev.b2tclient.v26.visual.BlockIdDiagnostics26;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class ClientScreen26 extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int SETTING_ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 66;
    /** Top of the per-module keybind row inside the settings column. */
    private static final int KEYBIND_ROW_TOP = HEADER_HEIGHT + 27;
    private static final int KEYBIND_ROW_HEIGHT = 15;
    /** First settings row sits below the keybind row. */
    private static final int SETTING_LIST_TOP =
            KEYBIND_ROW_TOP + KEYBIND_ROW_HEIGHT + 3;
    private static final int FOOTER_HEIGHT = 14;
    private static final int EDGE = 8;
    /** Setting ids whose value is resolved against the block registry. */
    private static final java.util.Set<String> BLOCK_LIST_SETTINGS =
            java.util.Set.of("targets", "visible_blocks");
    private final ClientRuntime26 runtime;
    private ClientScreen26Model.CategoryFilter category = ClientScreen26Model.CategoryFilter.ALL;
    private int moduleScroll;
    private int settingScroll;
    private String search = "";
    private boolean searchFocused;
    private RegisteredModule selectedModule;
    private Setting<?> editingSetting;
    private String editBuffer = "";
    private boolean capturingKeybind;
    private String status = "Left: toggle  Right: settings  Middle: favorite";

    public ClientScreen26(ClientRuntime26 runtime) {
        super(Component.literal("B2T Client 26.2"));
        this.runtime = runtime;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD010141B);
        graphics.fill(0, 0, width, HEADER_HEIGHT, 0xFF172231);
        graphics.centeredText(font, title, width / 2, 5, 0xFFFFFFFF);

        long available = runtime.modules().all().stream()
                .filter(module -> module.descriptor().available())
                .count();
        graphics.centeredText(
                font,
                "P: close  H: HUD  O: profiles  K: presets | " + runtime.modules().all().size() + " catalogued / "
                        + available + " implemented",
                width / 2,
                17,
                0xFF93A7BC
        );

        renderSearch(graphics);
        renderCategoryTabs(graphics);

        Layout layout = layout();
        graphics.fill(layout.moduleLeft(), HEADER_HEIGHT, layout.moduleRight(), layout.bottom(), 0x80131B25);
        graphics.fill(layout.settingLeft(), HEADER_HEIGHT, layout.settingRight(), layout.bottom(), 0x80131B25);

        List<RegisteredModule> modules = visibleModules();
        int visibleRows = visibleModuleRows(layout);
        moduleScroll = ClientScreen26Model.clampScroll(moduleScroll, modules.size(), visibleRows);
        RegisteredModule hovered = null;

        for (int row = 0; row < visibleRows && moduleScroll + row < modules.size(); row++) {
            RegisteredModule module = modules.get(moduleScroll + row);
            int y = HEADER_HEIGHT + 2 + row * ROW_HEIGHT;
            boolean isHovered = mouseX >= layout.moduleLeft()
                    && mouseX < layout.moduleRight()
                    && mouseY >= y
                    && mouseY < y + ROW_HEIGHT - 1;
            if (isHovered) {
                hovered = module;
            }
            int background = rowColor(module, isHovered);
            if (module == selectedModule) {
                background = isHovered ? 0xE04A6480 : 0xC03D5269;
            }
            graphics.fill(layout.moduleLeft(), y, layout.moduleRight(), y + ROW_HEIGHT - 1, background);

            boolean runtimeAvailable = runtimeAvailable(module);
            String state = runtimeAvailable
                    ? (module.enabled() ? "ON" : "OFF")
                    : module.descriptor().available()
                    ? "PROVIDER MISSING"
                    : "UNAVAILABLE";
            int textColor = runtimeAvailable ? 0xFFE8F3FF : 0xFF9AA1AA;
            String favorite = module.favorite() ? "* " : "";
            graphics.text(
                    font,
                    trim(favorite + module.descriptor().name(), layout.moduleWidth() - 122),
                    layout.moduleLeft() + 5,
                    y + 5,
                    module.favorite() ? 0xFFFFD166 : textColor,
                    true
            );
            String bind = module.keyCode() == RegisteredModule.UNBOUND_KEY_CODE
                    ? ""
                    : "[" + ClientScreen26Model.keyLabel(module.keyCode()) + "] ";
            String tags = bind + module.descriptor().risk().name() + " | " + state;
            graphics.text(
                    font,
                    tags,
                    layout.moduleRight() - 5 - font.width(tags),
                    y + 5,
                    runtimeAvailable ? riskColor(module) : 0xFF8E7378,
                    false
            );
        }

        renderSettings(graphics, layout, mouseX, mouseY);

        if (hovered != null) {
            status = runtimeAvailable(hovered)
                    ? hovered.descriptor().description()
                    : hovered.descriptor().capabilityDetail();
        }
        graphics.fill(0, height - FOOTER_HEIGHT, width, height, 0xEE111821);
        graphics.text(font, trim(status, width - 10), 5, height - 11, 0xFFBAC8D6, false);
    }

    private void renderSearch(GuiGraphicsExtractor graphics) {
        int color = searchFocused ? 0xFF385A78 : 0xFF27394C;
        graphics.fill(EDGE, 29, width - EDGE, 45, color);
        String shown = search.isEmpty() && !searchFocused ? "Search (/ or Ctrl+F)" : search;
        graphics.text(
                font,
                trim(shown + (searchFocused ? "_" : ""), width - EDGE * 2 - 8),
                EDGE + 4,
                33,
                search.isEmpty() ? 0xFF8EA0B2 : 0xFFE8F3FF,
                false
        );
    }

    private void renderCategoryTabs(GuiGraphicsExtractor graphics) {
        ClientScreen26Model.CategoryFilter[] categories = ClientScreen26Model.CategoryFilter.values();
        int available = width - EDGE * 2;
        for (int index = 0; index < categories.length; index++) {
            int left = EDGE + index * available / categories.length;
            int right = EDGE + (index + 1) * available / categories.length - 1;
            boolean selected = category == categories[index];
            graphics.fill(left, 48, right, 63, selected ? 0xFF2D705B : 0xFF243547);
            String name = categories[index].name();
            graphics.centeredText(font, name, (left + right) / 2, 52, selected ? 0xFFFFFFFF : 0xFFAAB8C6);
        }
    }

    private void renderSettings(
            GuiGraphicsExtractor graphics,
            Layout layout,
            int mouseX,
            int mouseY
    ) {
        int left = layout.settingLeft();
        int right = layout.settingRight();
        if (selectedModule == null) {
            graphics.centeredText(font, "Right-click a module", (left + right) / 2, HEADER_HEIGHT + 12, 0xFF93A7BC);
            return;
        }

        graphics.text(
                font,
                trim(selectedModule.descriptor().name(), layout.settingWidth() - 10),
                left + 5,
                HEADER_HEIGHT + 5,
                0xFFFFFFFF,
                true
        );
        String summary = selectedModule.settings().isEmpty()
                ? "No configurable settings"
                : selectedModule.settings().size() + " settings";
        graphics.text(font, summary, left + 5, HEADER_HEIGHT + 17, 0xFF8FA4B8, false);

        renderKeybindRow(graphics, layout, mouseX, mouseY);

        List<Setting<?>> settings = editableSettings(selectedModule);
        int rows = visibleSettingRows(layout);
        settingScroll = ClientScreen26Model.clampScroll(settingScroll, settings.size(), rows);
        for (int row = 0; row < rows && settingScroll + row < settings.size(); row++) {
            Setting<?> setting = settings.get(settingScroll + row);
            int y = SETTING_LIST_TOP + row * SETTING_ROW_HEIGHT;
            boolean hovered = mouseX >= left + 3
                    && mouseX < right - 3
                    && mouseY >= y
                    && mouseY < y + SETTING_ROW_HEIGHT - 2;
            graphics.fill(left + 3, y, right - 3, y + SETTING_ROW_HEIGHT - 2,
                    hovered ? 0xCC344A62 : 0xAA27394C);
            graphics.text(font, trim(setting.name(), layout.settingWidth() / 2 - 9), left + 7, y + 5,
                    0xFFE8F3FF, false);
            renderSettingValue(graphics, setting, left, right, y);
            String problem = blockListProblem(setting);
            if (problem != null) {
                graphics.text(font, trim(problem, layout.settingWidth() - 14),
                        left + 7, y + 12, 0xFFFF8B7C, false);
            }
            if (hovered) {
                status = problem != null ? problem : setting.description();
            }
        }
        if (settings.isEmpty() && !selectedModule.settings().isEmpty()) {
            graphics.text(font, "No Boolean/Integer/String settings", left + 5,
                    SETTING_LIST_TOP + 5, 0xFF93A7BC, false);
        }
    }

    /**
     * Names unusable entries in a block-id list, or null when there are none.
     *
     * <p>Reported for the XRay and Block ESP lists only: those are the two
     * settings whose values are resolved against the block registry and
     * silently dropped when they do not match.</p>
     */
    private String blockListProblem(Setting<?> setting) {
        if (!(setting instanceof StringSetting typed)
                || !BLOCK_LIST_SETTINGS.contains(setting.id())) {
            return null;
        }
        if (setting == editingSetting) {
            return BlockIdDiagnostics26.inspect(editBuffer).message();
        }
        return BlockIdDiagnostics26.inspect(typed.value()).message();
    }

    private void renderKeybindRow(
            GuiGraphicsExtractor graphics,
            Layout layout,
            int mouseX,
            int mouseY
    ) {
        int left = layout.settingLeft();
        int right = layout.settingRight();
        if (KEYBIND_ROW_TOP + KEYBIND_ROW_HEIGHT > layout.bottom()) {
            return;
        }
        boolean hovered = mouseX >= left + 3
                && mouseX < right - 3
                && mouseY >= KEYBIND_ROW_TOP
                && mouseY < KEYBIND_ROW_TOP + KEYBIND_ROW_HEIGHT;
        int background = capturingKeybind
                ? 0xCC6A4B18
                : hovered ? 0xCC344A62 : 0xAA1F2E3D;
        graphics.fill(left + 3, KEYBIND_ROW_TOP, right - 3,
                KEYBIND_ROW_TOP + KEYBIND_ROW_HEIGHT, background);
        graphics.text(font, "Keybind", left + 7, KEYBIND_ROW_TOP + 4, 0xFFE8F3FF, false);

        String value = capturingKeybind
                ? "Press a key..."
                : ClientScreen26Model.keyLabel(selectedModule.keyCode());
        int valueColor = capturingKeybind
                ? 0xFFFFD166
                : selectedModule.keyCode() == RegisteredModule.UNBOUND_KEY_CODE
                ? 0xFF8FA4B8
                : 0xFF72D6A9;
        String shown = trim(value, Math.max(10, layout.settingWidth() / 2 - 10));
        graphics.text(font, shown, right - 7 - font.width(shown),
                KEYBIND_ROW_TOP + 4, valueColor, false);

        if (hovered && !capturingKeybind) {
            status = "Click to bind  Middle-click to clear";
        }
    }

    private void renderSettingValue(
            GuiGraphicsExtractor graphics,
            Setting<?> setting,
            int left,
            int right,
            int y
    ) {
        if (setting instanceof BooleanSetting typed) {
            String value = typed.value() ? "ON" : "OFF";
            graphics.text(font, value, right - 7 - font.width(value), y + 5,
                    typed.value() ? 0xFF67D6A3 : 0xFFB2BAC3, false);
            return;
        }
        if (setting instanceof IntegerSetting typed) {
            graphics.text(font, "-", right - 47, y + 5, 0xFFDDCF91, false);
            graphics.text(font, "+", right - 13, y + 5, 0xFF72D6A9, false);
            String value = setting == editingSetting ? editBuffer + "_" : String.valueOf(typed.value());
            int available = 27;
            graphics.text(font, trim(value, available), right - 21 - Math.min(available, font.width(value)),
                    y + 5, setting == editingSetting ? 0xFFFFD166 : 0xFFE8F3FF, false);
            return;
        }
        if (setting instanceof DoubleSetting typed) {
            graphics.text(font, "-", right - 47, y + 5, 0xFFDDCF91, false);
            graphics.text(font, "+", right - 13, y + 5, 0xFF72D6A9, false);
            String value = setting == editingSetting ? editBuffer + "_" : setting.serialize();
            int available = 27;
            graphics.text(font, trim(value, available), right - 21 - Math.min(available, font.width(value)),
                    y + 5, setting == editingSetting ? 0xFFFFD166 : 0xFFE8F3FF, false);
            return;
        }
        if (setting instanceof StringSetting typed) {
            String value = setting == editingSetting ? editBuffer + "_" : typed.value();
            graphics.text(font, trim(value, Math.max(20, (right - left) / 2 - 10)),
                    left + (right - left) / 2, y + 5,
                    setting == editingSetting ? 0xFFFFD166 : 0xFFB8CEE3, false);
        }
    }

    private int rowColor(RegisteredModule module, boolean hovered) {
        if (!runtimeAvailable(module)) {
            return hovered ? 0xCC533239 : 0xAA33272C;
        }
        if (module.enabled()) {
            return hovered ? 0xCC26765B : 0xAA1E5D49;
        }
        return hovered ? 0xCC344A62 : 0xAA27394C;
    }

    private static int riskColor(RegisteredModule module) {
        return switch (module.descriptor().risk()) {
            case PASSIVE -> 0xFF79C8EF;
            case AUTOMATION -> 0xFFE0C56E;
            case COMBAT -> 0xFFFF8B7C;
            case MOVEMENT -> 0xFFC9A2FF;
            case PACKET -> 0xFFFF6F91;
        };
    }

    private boolean runtimeAvailable(RegisteredModule module) {
        return ClientScreen26Model.runtimeAvailable(
                module,
                runtime.baritone() != null
                        && runtime.baritone().available()
        );
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (inside(event, EDGE, 29, width - EDGE, 45)) {
            searchFocused = true;
            editingSetting = null;
            return true;
        }
        if (event.x() >= EDGE && event.x() < width - EDGE && event.y() >= 48 && event.y() < 63) {
            int available = width - EDGE * 2;
            int index = Math.min(
                    ClientScreen26Model.CategoryFilter.values().length - 1,
                    Math.max(0, ((int) event.x() - EDGE)
                            * ClientScreen26Model.CategoryFilter.values().length / Math.max(1, available))
            );
            category = ClientScreen26Model.CategoryFilter.values()[index];
            moduleScroll = 0;
            searchFocused = false;
            return true;
        }

        Layout layout = layout();
        if (event.x() >= layout.moduleLeft()
                && event.x() < layout.moduleRight()
                && event.y() >= HEADER_HEIGHT + 2
                && event.y() < layout.bottom()) {
            int row = ((int) event.y() - HEADER_HEIGHT - 2) / ROW_HEIGHT;
            int index = moduleScroll + row;
            List<RegisteredModule> modules = visibleModules();
            if (index >= 0 && index < modules.size()) {
                RegisteredModule module = modules.get(index);
                if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                    module.setFavorite(!module.favorite());
                    saveChanged(module.favorite() ? "Added favorite" : "Removed favorite");
                } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    selectModule(module);
                    status = module.settings().isEmpty()
                            ? "No configurable settings for " + module.descriptor().name()
                            : "Editing " + module.descriptor().name();
                } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                        && runtimeAvailable(module)) {
                    module.toggle();
                    saveChanged(module.descriptor().name() + (module.enabled() ? " enabled" : " disabled"));
                } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    selectModule(module);
                    status = module.descriptor().capabilityDetail();
                }
                return true;
            }
        }

        if (selectedModule != null
                && event.x() >= layout.settingLeft() + 3
                && event.x() < layout.settingRight() - 3
                && event.y() >= KEYBIND_ROW_TOP
                && event.y() < KEYBIND_ROW_TOP + KEYBIND_ROW_HEIGHT) {
            cancelEdit();
            searchFocused = false;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                capturingKeybind = false;
                selectedModule.setKeyCode(RegisteredModule.UNBOUND_KEY_CODE);
                saveChanged(selectedModule.descriptor().name() + " keybind cleared");
            } else {
                capturingKeybind = true;
                status = "Press a key to bind  Escape clears";
            }
            return true;
        }

        if (event.x() >= layout.settingLeft()
                && event.x() < layout.settingRight()
                && event.y() >= SETTING_LIST_TOP
                && event.y() < layout.bottom()
                && selectedModule != null) {
            List<Setting<?>> settings = editableSettings(selectedModule);
            int row = ((int) event.y() - SETTING_LIST_TOP) / SETTING_ROW_HEIGHT;
            int index = settingScroll + row;
            if (index >= 0 && index < settings.size()) {
                changeSettingFromClick(settings.get(index), event, layout);
                return true;
            }
        }
        searchFocused = false;
        return super.mouseClicked(event, doubleClick);
    }

    private void changeSettingFromClick(Setting<?> setting, MouseButtonEvent event, Layout layout) {
        if (setting instanceof BooleanSetting typed) {
            typed.set(!typed.value());
            cancelEdit();
            saveChanged(setting.name() + " = " + typed.value());
            return;
        }
        if (setting instanceof IntegerSetting typed) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                typed.reset();
                cancelEdit();
                saveChanged(setting.name() + " reset");
                return;
            }
            int direction = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 0;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (event.x() >= layout.settingRight() - 25) {
                    direction = 1;
                } else if (event.x() >= layout.settingRight() - 52) {
                    direction = -1;
                }
            }
            if (direction != 0) {
                long candidate = (long) typed.value() + (long) direction * typed.step();
                typed.set((int) Math.max(typed.min(), Math.min(typed.max(), candidate)));
                cancelEdit();
                saveChanged(setting.name() + " = " + typed.value());
            } else {
                beginEdit(setting);
            }
            return;
        }
        if (setting instanceof DoubleSetting typed) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                typed.reset();
                cancelEdit();
                saveChanged(setting.name() + " reset");
                return;
            }
            int direction = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 0;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (event.x() >= layout.settingRight() - 25) {
                    direction = 1;
                } else if (event.x() >= layout.settingRight() - 52) {
                    direction = -1;
                }
            }
            if (direction != 0) {
                double candidate = typed.value() + direction * typed.step();
                typed.set(Math.max(typed.min(), Math.min(typed.max(), candidate)));
                cancelEdit();
                saveChanged(setting.name() + " = " + typed.serialize());
            } else {
                beginEdit(setting);
            }
            return;
        }
        if (setting instanceof StringSetting) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                setting.reset();
                cancelEdit();
                saveChanged(setting.name() + " reset");
            } else {
                beginEdit(setting);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        Layout layout = layout();
        int delta = -(int) Math.signum(vertical) * 3;
        if (mouseX >= layout.settingLeft() && selectedModule != null) {
            int size = editableSettings(selectedModule).size();
            settingScroll = ClientScreen26Model.clampScroll(
                    settingScroll + delta,
                    size,
                    visibleSettingRows(layout)
            );
        } else {
            moduleScroll = ClientScreen26Model.clampScroll(
                    moduleScroll + delta,
                    visibleModules().size(),
                    visibleModuleRows(layout)
            );
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (capturingKeybind) {
            applyCapturedKey(key);
            return true;
        }
        if (editingSetting != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelEdit();
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !editBuffer.isEmpty()) {
                editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
            }
            return true;
        }
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER
                    || key == GLFW.GLFW_KEY_KP_ENTER) {
                searchFocused = false;
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                moduleScroll = 0;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_SLASH || (key == GLFW.GLFW_KEY_F && event.hasControlDown())) {
            searchFocused = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_K && minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(new PresetScreen26(runtime));
            return true;
        }
        if (key == GLFW.GLFW_KEY_O && minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(new ProfileScreen26(runtime));
            return true;
        }
        if (key == GLFW.GLFW_KEY_H && minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(new HudEditorScreen26(runtime));
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String value = event.codepointAsString();
        if (editingSetting instanceof IntegerSetting) {
            if ((value.length() == 1 && Character.isDigit(value.charAt(0)))
                    || ("-".equals(value) && editBuffer.isEmpty())) {
                editBuffer = ClientScreen26Model.appendLimited(editBuffer, value, 12);
            }
            return true;
        }
        if (editingSetting instanceof DoubleSetting) {
            if ((value.length() == 1 && Character.isDigit(value.charAt(0)))
                    || ("-".equals(value) && editBuffer.isEmpty())
                    || (".".equals(value) && !editBuffer.contains("."))) {
                editBuffer = ClientScreen26Model.appendLimited(editBuffer, value, 24);
            }
            return true;
        }
        if (editingSetting instanceof StringSetting typed) {
            if (event.isAllowedChatCharacter()) {
                editBuffer = ClientScreen26Model.appendLimited(editBuffer, value, typed.maxLength());
            }
            return true;
        }
        if (searchFocused) {
            if (event.isAllowedChatCharacter()) {
                search = ClientScreen26Model.appendLimited(
                        search,
                        value,
                        ClientScreen26Model.MAX_SEARCH_LENGTH
                );
                moduleScroll = 0;
            }
            return true;
        }
        return super.charTyped(event);
    }

    private void applyCapturedKey(int key) {
        if (selectedModule == null) {
            capturingKeybind = false;
            return;
        }
        switch (ClientScreen26Model.classifyCapture(key)) {
            case IGNORE -> status = "Modifier-only keys cannot be bound; press another key";
            case CLEAR -> {
                capturingKeybind = false;
                selectedModule.setKeyCode(RegisteredModule.UNBOUND_KEY_CODE);
                saveChanged(selectedModule.descriptor().name() + " keybind cleared");
            }
            case ASSIGN -> {
                capturingKeybind = false;
                selectedModule.setKeyCode(key);
                List<String> conflicts = ClientScreen26Model.conflictingModuleNames(
                        runtime.modules().all(),
                        selectedModule,
                        key
                );
                String label = ClientScreen26Model.keyLabel(key);
                saveChanged(conflicts.isEmpty()
                        ? selectedModule.descriptor().name() + " bound to " + label
                        : selectedModule.descriptor().name() + " bound to " + label
                                + " (also toggles " + String.join(", ", conflicts) + ")");
            }
            default -> capturingKeybind = false;
        }
    }

    private void beginEdit(Setting<?> setting) {
        editingSetting = setting;
        editBuffer = setting.serialize();
        searchFocused = false;
        status = "Enter: save  Escape: cancel  Middle-click: reset";
    }

    private void commitEdit() {
        try {
            if (editingSetting instanceof IntegerSetting typed) {
                OptionalInt value = ClientScreen26Model.parseBoundedInteger(
                        editBuffer,
                        typed.min(),
                        typed.max()
                );
                if (value.isEmpty()) {
                    status = "Invalid integer; expected " + typed.min() + ".." + typed.max();
                    return;
                }
                typed.set(value.getAsInt());
            } else if (editingSetting instanceof DoubleSetting typed) {
                OptionalDouble value = ClientScreen26Model.parseBoundedDouble(
                        editBuffer,
                        typed.min(),
                        typed.max()
                );
                if (value.isEmpty()) {
                    status = "Invalid decimal; expected " + typed.min() + ".." + typed.max();
                    return;
                }
                typed.set(value.getAsDouble());
            } else if (editingSetting instanceof StringSetting typed) {
                typed.set(editBuffer);
            }
            String changed = editingSetting.name() + " saved";
            cancelEdit();
            saveChanged(changed);
        } catch (IllegalArgumentException exception) {
            status = "Invalid value: " + exception.getMessage();
        }
    }

    private void cancelEdit() {
        editingSetting = null;
        editBuffer = "";
    }

    private void selectModule(RegisteredModule module) {
        selectedModule = module;
        settingScroll = 0;
        capturingKeybind = false;
        cancelEdit();
    }

    private void saveChanged(String message) {
        status = message;
        runtime.requestSave();
    }

    private List<RegisteredModule> visibleModules() {
        return ClientScreen26Model.filter(runtime.modules().all(), category, search);
    }

    private static List<Setting<?>> editableSettings(RegisteredModule module) {
        return module.settings().stream()
                .filter(Setting::isVisible)
                .filter(setting -> setting instanceof BooleanSetting
                        || setting instanceof IntegerSetting
                        || setting instanceof DoubleSetting
                        || setting instanceof StringSetting)
                .toList();
    }

    private int visibleModuleRows(Layout layout) {
        return Math.max(1, (layout.bottom() - HEADER_HEIGHT - 4) / ROW_HEIGHT);
    }

    private int visibleSettingRows(Layout layout) {
        return Math.max(1, (layout.bottom() - SETTING_LIST_TOP - 2) / SETTING_ROW_HEIGHT);
    }

    private Layout layout() {
        ClientScreen26Model.Columns columns = ClientScreen26Model.columns(
                width,
                height,
                EDGE,
                6,
                HEADER_HEIGHT,
                FOOTER_HEIGHT
        );
        return new Layout(
                columns.moduleLeft(),
                columns.moduleRight(),
                columns.settingLeft(),
                columns.settingRight(),
                columns.bottom()
        );
    }

    private String trim(String value, int availableWidth) {
        if (availableWidth <= 0) {
            return "";
        }
        if (font.width(value) <= availableWidth) {
            return value;
        }
        String suffix = "...";
        return font.plainSubstrByWidth(value, Math.max(0, availableWidth - font.width(suffix))) + suffix;
    }

    private static boolean inside(MouseButtonEvent event, int left, int top, int right, int bottom) {
        return event.x() >= left && event.x() < right && event.y() >= top && event.y() < bottom;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        capturingKeybind = false;
        if (editingSetting != null) {
            commitEdit();
        }
        if (!runtime.save()) {
            status = "Changes remain in memory; config save failed";
        }
        super.removed();
    }

    private record Layout(
            int moduleLeft,
            int moduleRight,
            int settingLeft,
            int settingRight,
            int bottom
    ) {
        int moduleWidth() {
            return moduleRight - moduleLeft;
        }

        int settingWidth() {
            return settingRight - settingLeft;
        }
    }
}
