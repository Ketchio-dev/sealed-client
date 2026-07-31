package dev.sealedclient.v26.gui;

import dev.sealedclient.common.gui.ClickGuiModel;

import dev.sealedclient.common.module.ModuleCategory;
import dev.sealedclient.common.module.RegisteredModule;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Pure state helpers for the 26.2 screen. Keeping filtering and bounded input
 * outside Minecraft's rendering class makes the important UI behavior directly
 * testable.
 */
public final class ClientScreen26Model {
    public static final int MAX_SEARCH_LENGTH = 64;

    private ClientScreen26Model() {
    }

    public static List<RegisteredModule> filter(
            List<RegisteredModule> modules,
            CategoryFilter category,
            String search
    ) {
        return modules.stream()
                .filter(module -> category.matches(module.descriptor().category()))
                .filter(module -> ClickGuiModel.matchesQuery(
                        search,
                        module.descriptor().name(),
                        module.descriptor().id(),
                        module.descriptor().description(),
                        module.descriptor().risk().name(),
                        module.descriptor().availability().name()
                ))
                .toList();
    }

    public static int clampScroll(int requested, int itemCount, int visibleRows) {
        int maximum = Math.max(0, itemCount - Math.max(1, visibleRows));
        return Math.max(0, Math.min(maximum, requested));
    }

    public static boolean runtimeAvailable(
            RegisteredModule module,
            boolean baritoneProviderAvailable
    ) {
        if (module == null || !module.descriptor().available()) {
            return false;
        }
        return !"baritone_navigator".equals(module.descriptor().id())
                || baritoneProviderAvailable;
    }

    public static String appendLimited(String current, String addition, int maximumLength) {
        if (maximumLength <= 0) {
            return "";
        }
        String base = current == null ? "" : current;
        String suffix = addition == null ? "" : addition;
        int room = maximumLength - base.length();
        if (room <= 0 || suffix.isEmpty()) {
            return base;
        }
        return base + suffix.substring(0, Math.min(room, suffix.length()));
    }

    public static Columns columns(
            int width,
            int height,
            int edge,
            int gap,
            int headerHeight,
            int footerHeight
    ) {
        int safeWidth = Math.max(2, width);
        int safeGap = Math.max(0, Math.min(gap, safeWidth - 2));
        int safeEdge = Math.max(0, Math.min(edge, (safeWidth - safeGap - 2) / 2));
        int contentWidth = safeWidth - safeEdge * 2 - safeGap;
        int moduleWidth = Math.max(1, contentWidth * 57 / 100);
        moduleWidth = Math.min(contentWidth - 1, moduleWidth);
        int moduleRight = safeEdge + moduleWidth;
        return new Columns(
                safeEdge,
                moduleRight,
                moduleRight + safeGap,
                safeWidth - safeEdge,
                Math.max(headerHeight + 2, height - footerHeight)
        );
    }

    /**
     * Parses through a long so deliberately huge input cannot overflow before it
     * is clamped to the declared setting bounds.
     */
    public static OptionalInt parseBoundedInteger(String input, int minimum, int maximum) {
        if (minimum > maximum || input == null || input.isBlank() || "-".equals(input.trim())) {
            return OptionalInt.empty();
        }
        try {
            long parsed = Long.parseLong(input.trim());
            long clamped = Math.max((long) minimum, Math.min((long) maximum, parsed));
            return OptionalInt.of((int) clamped);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static OptionalDouble parseBoundedDouble(String input, double minimum, double maximum) {
        if (!Double.isFinite(minimum)
                || !Double.isFinite(maximum)
                || minimum > maximum
                || input == null
                || input.isBlank()
                || "-".equals(input.trim())
                || ".".equals(input.trim())
                || "-.".equals(input.trim())) {
            return OptionalDouble.empty();
        }
        try {
            double parsed = Double.parseDouble(input.trim());
            if (!Double.isFinite(parsed)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(Math.max(minimum, Math.min(maximum, parsed)));
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Renders a key code as a stable, translation-independent label.
     *
     * <p>Deliberately not routed through Minecraft's key display names: the
     * label is persisted nowhere but must stay identical between the module
     * list, the keybind row, and the tests that assert them.</p>
     */
    public static String keyLabel(int keyCode) {
        int normalized = RegisteredModule.normalizeKeyCode(keyCode);
        if (normalized == RegisteredModule.UNBOUND_KEY_CODE) {
            return "None";
        }
        String named = NAMED_KEYS.get(normalized);
        if (named != null) {
            return named;
        }
        if (normalized >= GLFW.GLFW_KEY_A && normalized <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + normalized - GLFW.GLFW_KEY_A));
        }
        if (normalized >= GLFW.GLFW_KEY_0 && normalized <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + normalized - GLFW.GLFW_KEY_0));
        }
        if (normalized >= GLFW.GLFW_KEY_F1 && normalized <= GLFW.GLFW_KEY_F25) {
            return "F" + (normalized - GLFW.GLFW_KEY_F1 + 1);
        }
        if (normalized >= GLFW.GLFW_KEY_KP_0 && normalized <= GLFW.GLFW_KEY_KP_9) {
            return "Numpad " + (normalized - GLFW.GLFW_KEY_KP_0);
        }
        return "Key " + normalized;
    }

    /**
     * Decides what a key press means while the keybind row is capturing.
     */
    public static KeybindCapture classifyCapture(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return KeybindCapture.CLEAR;
        }
        if (RegisteredModule.normalizeKeyCode(keyCode)
                == RegisteredModule.UNBOUND_KEY_CODE) {
            return KeybindCapture.IGNORE;
        }
        // Modifier-only presses would make the binding unreachable in practice,
        // because the dispatcher edge-detects a single key code.
        if (MODIFIER_KEYS.contains(keyCode)) {
            return KeybindCapture.IGNORE;
        }
        return KeybindCapture.ASSIGN;
    }

    /**
     * Names the other modules already bound to the same key.
     *
     * <p>Sharing a key is allowed — it toggles every bound module at once — but
     * the screen surfaces it so it is never a silent surprise.</p>
     */
    public static List<String> conflictingModuleNames(
            List<RegisteredModule> modules,
            RegisteredModule target,
            int keyCode
    ) {
        int normalized = RegisteredModule.normalizeKeyCode(keyCode);
        if (modules == null || normalized == RegisteredModule.UNBOUND_KEY_CODE) {
            return List.of();
        }
        return modules.stream()
                .filter(module -> module != target)
                .filter(module -> module.keyCode() == normalized)
                .map(module -> module.descriptor().name())
                .toList();
    }

    public enum KeybindCapture {
        /** Escape clears the binding. */
        CLEAR,
        /** Not a usable single-key binding; capture stays armed. */
        IGNORE,
        /** Assign this key code to the module. */
        ASSIGN
    }

    private static final java.util.Set<Integer> MODIFIER_KEYS = java.util.Set.of(
            GLFW.GLFW_KEY_LEFT_SHIFT,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            GLFW.GLFW_KEY_RIGHT_CONTROL,
            GLFW.GLFW_KEY_LEFT_ALT,
            GLFW.GLFW_KEY_RIGHT_ALT,
            GLFW.GLFW_KEY_LEFT_SUPER,
            GLFW.GLFW_KEY_RIGHT_SUPER
    );

    private static final java.util.Map<Integer, String> NAMED_KEYS = java.util.Map.ofEntries(
            java.util.Map.entry(GLFW.GLFW_KEY_SPACE, "Space"),
            java.util.Map.entry(GLFW.GLFW_KEY_ENTER, "Enter"),
            java.util.Map.entry(GLFW.GLFW_KEY_KP_ENTER, "Numpad Enter"),
            java.util.Map.entry(GLFW.GLFW_KEY_TAB, "Tab"),
            java.util.Map.entry(GLFW.GLFW_KEY_BACKSPACE, "Backspace"),
            java.util.Map.entry(GLFW.GLFW_KEY_INSERT, "Insert"),
            java.util.Map.entry(GLFW.GLFW_KEY_DELETE, "Delete"),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT, "Right"),
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT, "Left"),
            java.util.Map.entry(GLFW.GLFW_KEY_DOWN, "Down"),
            java.util.Map.entry(GLFW.GLFW_KEY_UP, "Up"),
            java.util.Map.entry(GLFW.GLFW_KEY_PAGE_UP, "Page Up"),
            java.util.Map.entry(GLFW.GLFW_KEY_PAGE_DOWN, "Page Down"),
            java.util.Map.entry(GLFW.GLFW_KEY_HOME, "Home"),
            java.util.Map.entry(GLFW.GLFW_KEY_END, "End"),
            java.util.Map.entry(GLFW.GLFW_KEY_MINUS, "-"),
            java.util.Map.entry(GLFW.GLFW_KEY_EQUAL, "="),
            java.util.Map.entry(GLFW.GLFW_KEY_LEFT_BRACKET, "["),
            java.util.Map.entry(GLFW.GLFW_KEY_RIGHT_BRACKET, "]"),
            java.util.Map.entry(GLFW.GLFW_KEY_BACKSLASH, "\\"),
            java.util.Map.entry(GLFW.GLFW_KEY_SEMICOLON, ";"),
            java.util.Map.entry(GLFW.GLFW_KEY_APOSTROPHE, "'"),
            java.util.Map.entry(GLFW.GLFW_KEY_COMMA, ","),
            java.util.Map.entry(GLFW.GLFW_KEY_PERIOD, "."),
            java.util.Map.entry(GLFW.GLFW_KEY_SLASH, "/"),
            java.util.Map.entry(GLFW.GLFW_KEY_GRAVE_ACCENT, "`")
    );

    public enum CategoryFilter {
        ALL(null),
        HUD(ModuleCategory.HUD),
        COMBAT(ModuleCategory.COMBAT),
        VISUAL(ModuleCategory.VISUAL),
        MOVEMENT(ModuleCategory.MOVEMENT),
        UTILITY(ModuleCategory.UTILITY);

        private final ModuleCategory category;

        CategoryFilter(ModuleCategory category) {
            this.category = category;
        }

        public boolean matches(ModuleCategory candidate) {
            return category == null || category == candidate;
        }
    }

    public record Columns(
            int moduleLeft,
            int moduleRight,
            int settingLeft,
            int settingRight,
            int bottom
    ) {
        public Columns {
            if (moduleLeft >= moduleRight
                    || moduleRight > settingLeft
                    || settingLeft >= settingRight) {
                throw new IllegalArgumentException("Columns must be ordered and non-empty");
            }
        }
    }
}
