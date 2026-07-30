package dev.sealedclient.module.visual;

import dev.sealedclient.core.ModuleManager;
import dev.sealedclient.module.world.LogoutSpotsModule;
import dev.sealedclient.module.world.NewChunksModule;
import dev.sealedclient.module.world.PortalCoordsModule;
import dev.sealedclient.module.world.StashFinderModule;
import dev.sealedclient.render.WorldOverlayRenderer;
import dev.sealedclient.service.ActionCoordinator;
import dev.sealedclient.service.FriendManager;
import dev.sealedclient.service.WaypointManager;

import java.util.Objects;

/**
 * Registers the visual/world expansion as one cohesive feature set.
 */
public final class VisualWorldExpansionRegistrar {
    private VisualWorldExpansionRegistrar() {
    }

    /**
     * Registers all modules and returns the renderer service. The caller should
     * invoke {@link WorldOverlayRenderer#initialize()} after core client services
     * have been initialized.
     */
    public static WorldOverlayRenderer register(
            ModuleManager modules,
            FriendManager friends,
            WaypointManager waypoints,
            ActionCoordinator actions
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(actions, "actions");

        PlayerESPModule playerEsp = new PlayerESPModule();
        TracersModule tracers = new TracersModule();
        NametagsModule nametags = new NametagsModule();
        StorageESPModule storageEsp = new StorageESPModule();
        HoleESPModule holeEsp = new HoleESPModule();
        BlockESPModule blockEsp = new BlockESPModule();
        TrajectoriesModule trajectories = new TrajectoriesModule();
        WaypointsModule waypointOverlay = new WaypointsModule();
        NewChunksModule newChunks = new NewChunksModule();
        LogoutSpotsModule logoutSpots = new LogoutSpotsModule();
        FreecamModule freecam = new FreecamModule(actions);
        XRayModule xray = new XRayModule();
        ChamsModule chams = new ChamsModule();
        StashFinderModule stashFinder = new StashFinderModule();
        PortalCoordsModule portalCoords = new PortalCoordsModule();

        modules.register(playerEsp);
        modules.register(tracers);
        modules.register(nametags);
        modules.register(storageEsp);
        modules.register(holeEsp);
        modules.register(blockEsp);
        modules.register(trajectories);
        modules.register(waypointOverlay);
        modules.register(newChunks);
        modules.register(logoutSpots);
        modules.register(freecam);
        modules.register(xray);
        modules.register(chams);
        modules.register(stashFinder);
        modules.register(portalCoords);

        return new WorldOverlayRenderer(
                friends,
                waypoints,
                playerEsp,
                tracers,
                nametags,
                storageEsp,
                holeEsp,
                blockEsp,
                trajectories,
                waypointOverlay,
                newChunks,
                logoutSpots,
                stashFinder,
                portalCoords
        );
    }
}
