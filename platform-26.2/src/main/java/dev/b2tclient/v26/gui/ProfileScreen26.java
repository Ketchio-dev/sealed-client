package dev.b2tclient.v26.gui;

import dev.b2tclient.common.profile.ClientProfile;
import dev.b2tclient.common.profile.ProfileBook;
import dev.b2tclient.v26.ClientRuntime26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Manages saved profiles: which one is active, what server pattern each one
 * matches, and save / use / delete.
 *
 * <p>Deleting is a two-step confirm because a profile carries the full module
 * snapshot and there is no undo for it once the config is rewritten.</p>
 */
public final class ProfileScreen26 extends Screen {
    private static final int HEADER_HEIGHT = 62;
    private static final int FOOTER_HEIGHT = 14;
    private static final int ROW_HEIGHT = 24;
    private static final int EDGE = 8;
    private final ClientRuntime26 runtime;
    private int scroll;
    private String nameBuffer = "";
    private boolean nameFocused;
    private String pendingDelete;
    private String status = "Left: use  Middle: delete  Type a name then Enter to save";

    public ProfileScreen26(ClientRuntime26 runtime) {
        super(Component.literal("B2T Profiles"));
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
        graphics.centeredText(font, title, width / 2, 5, 0xFFFFFFFF);
        graphics.centeredText(
                font,
                "Current server: " + serverLabel(),
                width / 2,
                17,
                0xFF93A7BC
        );

        graphics.fill(EDGE, 30, width - EDGE, 46, nameFocused ? 0xFF385A78 : 0xFF27394C);
        String shown = nameBuffer.isEmpty() && !nameFocused
                ? "New profile name (Enter saves the current settings)"
                : nameBuffer + (nameFocused ? "_" : "");
        graphics.text(font, trim(shown, width - EDGE * 2 - 8), EDGE + 4, 34,
                nameBuffer.isEmpty() ? 0xFF8EA0B2 : 0xFFE8F3FF, false);

        List<ClientProfile> profiles = runtime.profiles().all();
        int rows = visibleRows();
        scroll = ClientScreen26Model.clampScroll(scroll, profiles.size(), rows);
        String activeName = runtime.profiles().active()
                .map(ClientProfile::name)
                .orElse(null);

        for (int row = 0; row < rows && scroll + row < profiles.size(); row++) {
            ClientProfile profile = profiles.get(scroll + row);
            int y = HEADER_HEIGHT + 2 + row * ROW_HEIGHT;
            boolean hovered = mouseX >= EDGE && mouseX < width - EDGE
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            boolean active = profile.name().equalsIgnoreCase(activeName);
            boolean confirming = profile.name().equalsIgnoreCase(pendingDelete);

            int background = confirming
                    ? (hovered ? 0xCC7A3038 : 0xAA5A2028)
                    : active
                    ? (hovered ? 0xCC26765B : 0xAA1E5D49)
                    : hovered ? 0xCC344A62 : 0xAA27394C;
            graphics.fill(EDGE, y, width - EDGE, y + ROW_HEIGHT - 2, background);

            String label = (active ? "> " : "  ") + profile.name();
            graphics.text(font, trim(label, width - EDGE * 2 - 130), EDGE + 5, y + 4,
                    active ? 0xFF9BE8C4 : 0xFFE8F3FF, true);
            String pattern = "server: " + profile.serverPattern();
            graphics.text(font, trim(pattern, width - EDGE * 2 - 130), EDGE + 5, y + 14,
                    0xFF8FA4B8, false);

            String right = confirming
                    ? "Middle-click again to delete"
                    : active ? "ACTIVE" : profile.modules().size() + " modules";
            graphics.text(font, right, width - EDGE - 5 - font.width(right), y + 9,
                    confirming ? 0xFFFF8B7C : active ? 0xFF9BE8C4 : 0xFFAAB8C6, false);

            if (hovered && !confirming) {
                status = "Matches " + profile.serverPattern()
                        + "  |  Left: activate  Middle: delete";
            }
        }

        if (profiles.isEmpty()) {
            graphics.centeredText(font, "No profiles saved", width / 2,
                    HEADER_HEIGHT + 12, 0xFF93A7BC);
        }

        graphics.fill(0, height - FOOTER_HEIGHT, width, height, 0xEE111821);
        graphics.text(font, trim(status, width - 10), 5, height - 11, 0xFFBAC8D6, false);
    }

    private String serverLabel() {
        String server = runtime.serverKey(Minecraft.getInstance());
        return server == null || server.isBlank() ? "(not connected)" : server;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() >= EDGE && event.x() < width - EDGE
                && event.y() >= 30 && event.y() < 46) {
            nameFocused = true;
            return true;
        }
        nameFocused = false;

        List<ClientProfile> profiles = runtime.profiles().all();
        int row = ((int) event.y() - HEADER_HEIGHT - 2) / ROW_HEIGHT;
        int index = scroll + row;
        if (event.y() < HEADER_HEIGHT + 2 || index < 0 || index >= profiles.size()) {
            return super.mouseClicked(event, doubleClick);
        }
        ClientProfile profile = profiles.get(index);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            deleteProfile(profile);
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pendingDelete = null;
            if (runtime.profiles().activate(profile.name(), runtime.modules())) {
                runtime.clearBaritoneConfirmation();
                status = saved("Activated " + profile.name());
            } else {
                status = "Profile " + profile.name() + " is not valid for this build";
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void deleteProfile(ClientProfile profile) {
        if (!profile.name().equalsIgnoreCase(pendingDelete)) {
            pendingDelete = profile.name();
            status = "Middle-click " + profile.name() + " again to delete it";
            return;
        }
        pendingDelete = null;
        ProfileBook.DeleteResult result = runtime.profiles().delete(profile.name());
        status = switch (result) {
            case DELETED -> saved("Deleted " + profile.name());
            case LAST_PROFILE ->
                    "Refusing to delete the last profile; save another one first";
            case NOT_FOUND -> "Profile " + profile.name() + " no longer exists";
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = ClientScreen26Model.clampScroll(
                scroll - (int) Math.signum(vertical) * 2,
                runtime.profiles().all().size(),
                visibleRows()
        );
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (nameFocused) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                saveCurrent();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !nameBuffer.isEmpty()) {
                nameBuffer = nameBuffer.substring(0, nameBuffer.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                nameFocused = false;
                return true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (nameFocused && event.isAllowedChatCharacter()) {
            nameBuffer = ClientScreen26Model.appendLimited(
                    nameBuffer,
                    event.codepointAsString(),
                    48
            );
            return true;
        }
        return super.charTyped(event);
    }

    private void saveCurrent() {
        String name = nameBuffer.trim();
        if (name.isEmpty()) {
            status = "Enter a profile name first";
            return;
        }
        // A one-session navigation confirmation must not be baked into a
        // profile that may be reapplied automatically on a later connection.
        runtime.clearBaritoneConfirmation();
        String pattern = serverLabel().startsWith("(")
                ? "*"
                : runtime.serverKey(Minecraft.getInstance());
        runtime.profiles().capture(name, pattern, runtime.modules());
        nameBuffer = "";
        nameFocused = false;
        pendingDelete = null;
        status = saved("Saved " + name + " for " + pattern);
    }

    private String saved(String message) {
        return runtime.save() ? message : message + " (memory only; config save failed)";
    }

    private int visibleRows() {
        return Math.max(1, (height - FOOTER_HEIGHT - HEADER_HEIGHT - 4) / ROW_HEIGHT);
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
        pendingDelete = null;
        nameFocused = false;
        super.removed();
    }
}
