package dev.sealedclient.api;

import dev.sealedclient.SealedClient;
import dev.sealedclient.common.module.BuiltinModuleCatalog;
import dev.sealedclient.config.ConfigManager;
import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.event.EventBus;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;
import dev.sealedclient.integration.OptionalIntegrationManager;
import dev.sealedclient.hud.NotificationManager;

public final class SealedApi {
    public static final int API_VERSION = 2;

    private SealedApi() {
    }

    public static ModuleManager modules() {
        return SealedClient.runtime().modules();
    }

    public static ConfigManager config() {
        return SealedClient.runtime().config();
    }

    public static EventBus events() {
        return SealedClient.runtime().events();
    }

    public static ActionCoordinator actions() {
        return SealedClient.runtime().actions();
    }

    public static FriendManager friends() {
        return SealedClient.runtime().friends();
    }

    public static WaypointManager waypoints() {
        return SealedClient.runtime().waypoints();
    }

    public static OptionalIntegrationManager integrations() {
        return SealedClient.runtime().integrations();
    }

    public static NotificationManager notifications() {
        return SealedClient.runtime().notifications();
    }

    public static java.util.List<BuiltinModuleCatalog.CatalogEntry> catalog() {
        return BuiltinModuleCatalog.entries();
    }
}
