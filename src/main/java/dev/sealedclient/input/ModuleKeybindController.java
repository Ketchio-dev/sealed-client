package dev.sealedclient.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.hud.NotificationManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ModuleKeybindController {
    private final ModuleManager moduleManager;
    private final ConfigManager configManager;
    private final NotificationManager notifications;
    private Module[] watchedModules = new Module[0];
    private boolean[] previousState = new boolean[0];

    public ModuleKeybindController(ModuleManager moduleManager, ConfigManager configManager) {
        this(moduleManager, configManager, null);
    }

    public ModuleKeybindController(
            ModuleManager moduleManager,
            ConfigManager configManager,
            NotificationManager notifications
    ) {
        this.moduleManager = moduleManager;
        this.configManager = configManager;
        this.notifications = notifications;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.getWindow() == null) {
            return;
        }

        boolean acceptInput = minecraft.screen == null;
        long window = minecraft.getWindow().getWindow();
        refreshModules();
        for (int index = 0; index < watchedModules.length; index++) {
            Module module = watchedModules[index];
            int keyCode = module.keyCode();
            if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
                previousState[index] = false;
                continue;
            }

            boolean physicallyPressed = InputConstants.isKeyDown(window, keyCode);
            boolean wasPressed = previousState[index];
            if (acceptInput && physicallyPressed && !wasPressed) {
                module.toggle(minecraft);
                configManager.save();
                if (notifications != null) {
                    notifications.push(
                            module.name() + " "
                                    + (module.isEnabled() ? "enabled" : "disabled"),
                            module.isEnabled()
                                    ? NotificationManager.Type.SUCCESS
                                    : NotificationManager.Type.INFO
                    );
                }
            }
            previousState[index] = physicallyPressed;
        }
    }

    private void refreshModules() {
        if (watchedModules.length == moduleManager.all().size()) {
            return;
        }
        watchedModules = moduleManager.all().toArray(Module[]::new);
        previousState = new boolean[watchedModules.length];
    }
}
