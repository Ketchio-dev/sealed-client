package dev.sealedclient.core;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.setting.Setting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public abstract class Module {
    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final boolean defaultEnabled;
    private final ModuleRisk risk;
    private final List<Setting<?>> settings = new ArrayList<>();
    private volatile boolean enabled;
    private boolean favorite;
    private int keyCode = GLFW.GLFW_KEY_UNKNOWN;

    protected Module(
            String id,
            String name,
            String description,
            Category category,
            boolean defaultEnabled
    ) {
        this(id, name, description, category, defaultEnabled, ModuleRisk.PASSIVE);
    }

    protected Module(
            String id,
            String name,
            String description,
            Category category,
            boolean defaultEnabled,
            ModuleRisk risk
    ) {
        this.id = requireId(id);
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.category = Objects.requireNonNull(category, "category");
        this.defaultEnabled = defaultEnabled;
        this.risk = Objects.requireNonNull(risk, "risk");
        this.enabled = defaultEnabled;
    }

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final Category category() {
        return category;
    }

    public final ModuleRisk risk() {
        return risk;
    }

    public final boolean defaultEnabled() {
        return defaultEnabled;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final void toggle(Minecraft minecraft) {
        setEnabled(!enabled, minecraft);
    }

    public final boolean setEnabled(boolean enabled, Minecraft minecraft) {
        if (this.enabled == enabled) {
            return false;
        }

        boolean previous = this.enabled;
        this.enabled = enabled;
        try {
            if (enabled) {
                onEnable(minecraft);
            } else {
                onDisable(minecraft);
            }
            return true;
        } catch (RuntimeException exception) {
            this.enabled = previous;
            SealedClient.LOGGER.error(
                    "Could not {} module {}; keeping it {}",
                    enabled ? "enable" : "disable",
                    id,
                    previous ? "enabled" : "disabled",
                    exception
            );
            return false;
        }
    }

    public final void reset(Minecraft minecraft) {
        setEnabled(defaultEnabled, minecraft);
        keyCode = GLFW.GLFW_KEY_UNKNOWN;
        favorite = false;
        settings.forEach(Setting::reset);
    }

    public final int keyCode() {
        return keyCode;
    }

    public final void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public final boolean isFavorite() {
        return favorite;
    }

    public final void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public final List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    protected final <S extends Setting<?>> S addSetting(S setting) {
        Objects.requireNonNull(setting, "setting");
        if (settings.stream().anyMatch(existing -> existing.id().equals(setting.id()))) {
            throw new IllegalArgumentException(
                    "Duplicate setting id " + setting.id() + " in module " + id
            );
        }
        settings.add(setting);
        return setting;
    }

    protected void onEnable(Minecraft minecraft) {
    }

    protected void onDisable(Minecraft minecraft) {
    }

    private static String requireId(String value) {
        Objects.requireNonNull(value, "id");
        if (!value.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid module id: " + value);
        }
        return value;
    }
}
