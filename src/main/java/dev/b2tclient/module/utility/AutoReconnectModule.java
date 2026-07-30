package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Objects;

/**
 * Reopens the vanilla connection screen for the last multiplayer server. It
 * never contacts an update service or any host other than that server.
 */
public final class AutoReconnectModule extends Module implements TickableModule {
    private static final String OWNER = "auto_reconnect";

    private final IntegerSetting delaySeconds = addSetting(new IntegerSetting(
            "delay_seconds",
            "Delay",
            "Seconds to wait before reconnecting.",
            10,
            1,
            120,
            1
    ));
    private final IntegerSetting maximumAttempts = addSetting(new IntegerSetting(
            "maximum_attempts",
            "Max attempts",
            "Maximum attempts per disconnected session.",
            5,
            1,
            20,
            1
    ));
    private final ActionCoordinator actions;
    private final ReconnectSchedule schedule = new ReconnectSchedule();
    private ServerData lastServer;

    public AutoReconnectModule(ActionCoordinator actions) {
        super(
                "auto_reconnect",
                "Auto Reconnect",
                "Reopens the vanilla connection screen for the last server.",
                Category.UTILITY,
                false,
                ModuleRisk.PACKET
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        ServerData connectedServer = minecraft.getCurrentServer();
        if (minecraft.player != null && connectedServer != null) {
            remember(connectedServer);
            schedule.connected();
            return;
        }

        if (!(minecraft.screen instanceof DisconnectedScreen disconnected)
                || lastServer == null
                || schedule.attempts() >= maximumAttempts.get()) {
            if (!(minecraft.screen instanceof ConnectScreen)) {
                schedule.clearCountdown();
            }
            return;
        }

        if (!schedule.tick(
                disconnected,
                delaySeconds.get() * 20,
                maximumAttempts.get()
        )) {
            return;
        }
        if (!actions.claim(ActionCoordinator.Channel.NETWORK, OWNER, 20, 20)) {
            schedule.retryAfter(20);
            return;
        }

        Screen parent = minecraft.screen;
        ConnectScreen.startConnecting(
                parent,
                minecraft,
                ServerAddress.parseString(lastServer.ip),
                lastServer,
                false,
                null
        );
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        schedule.connected();
        actions.releaseOwner(minecraft, OWNER);
    }

    private void remember(ServerData server) {
        ServerData copy = new ServerData(server.name, server.ip, server.type());
        copy.copyFrom(server);
        lastServer = copy;
    }

    static final class ReconnectSchedule {
        private Object observedScreen;
        private int remainingTicks = -1;
        private int attempts;

        boolean tick(Object screenIdentity, int delayTicks, int maximumAttempts) {
            Objects.requireNonNull(screenIdentity, "screenIdentity");
            if (attempts >= Math.max(0, maximumAttempts)) {
                return false;
            }
            if (observedScreen != screenIdentity) {
                observedScreen = screenIdentity;
                remainingTicks = Math.max(0, delayTicks);
            }
            if (remainingTicks-- > 0) {
                return false;
            }
            attempts++;
            remainingTicks = -1;
            return true;
        }

        void retryAfter(int delayTicks) {
            attempts = Math.max(0, attempts - 1);
            remainingTicks = Math.max(0, delayTicks);
        }

        void clearCountdown() {
            observedScreen = null;
            remainingTicks = -1;
        }

        void connected() {
            clearCountdown();
            attempts = 0;
        }

        int attempts() {
            return attempts;
        }

        int remainingTicks() {
            return remainingTicks;
        }
    }
}
