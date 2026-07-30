package dev.b2tclient.api;

import dev.b2tclient.B2TClient;
import dev.b2tclient.common.module.BuiltinModuleCatalog;
import dev.b2tclient.config.ConfigManager;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.event.EventBus;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.WaypointManager;
import dev.b2tclient.integration.OptionalIntegrationManager;
import dev.b2tclient.hud.NotificationManager;

public final class B2TApi {
    public static final int API_VERSION = 2;

    private B2TApi() {
    }

    public static ModuleManager modules() {
        return B2TClient.runtime().modules();
    }

    public static ConfigManager config() {
        return B2TClient.runtime().config();
    }

    public static EventBus events() {
        return B2TClient.runtime().events();
    }

    public static ActionCoordinator actions() {
        return B2TClient.runtime().actions();
    }

    public static FriendManager friends() {
        return B2TClient.runtime().friends();
    }

    public static WaypointManager waypoints() {
        return B2TClient.runtime().waypoints();
    }

    public static OptionalIntegrationManager integrations() {
        return B2TClient.runtime().integrations();
    }

    public static NotificationManager notifications() {
        return B2TClient.runtime().notifications();
    }

    public static java.util.List<BuiltinModuleCatalog.CatalogEntry> catalog() {
        return BuiltinModuleCatalog.entries();
    }
}
