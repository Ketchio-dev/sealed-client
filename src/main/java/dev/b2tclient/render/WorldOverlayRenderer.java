package dev.b2tclient.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.b2tclient.B2TClient;
import dev.b2tclient.module.visual.BlockESPModule;
import dev.b2tclient.module.visual.HoleESPModule;
import dev.b2tclient.module.visual.NametagsModule;
import dev.b2tclient.module.visual.PlayerESPModule;
import dev.b2tclient.module.visual.StorageESPModule;
import dev.b2tclient.module.visual.TracersModule;
import dev.b2tclient.module.visual.TrajectoriesModule;
import dev.b2tclient.module.visual.WaypointsModule;
import dev.b2tclient.module.world.LogoutSpotsModule;
import dev.b2tclient.module.world.NewChunksModule;
import dev.b2tclient.module.world.PortalCoordsModule;
import dev.b2tclient.module.world.StashFinderModule;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.Waypoint;
import dev.b2tclient.service.WaypointManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Batched world overlay service for the passive situation-awareness modules.
 *
 * <p>All scanning happens on client ticks. Rendering only consumes bounded
 * snapshots and performs distance/frustum culling.</p>
 */
public final class WorldOverlayRenderer {
    private static final int STORAGE_REFRESH_TICKS = 10;
    private static final int MAX_STORAGE_MARKERS = 4_096;
    private static final int MAX_PLAYER_LABELS = 128;
    private static final int MAX_FINDER_MARKERS = 128;
    private static final int STASH_COLOR = 0xFFFFAA00;
    private static final int PORTAL_COLOR = 0xFFCC55FF;

    private final FriendManager friends;
    private final WaypointManager waypoints;
    private final PlayerESPModule playerEsp;
    private final TracersModule tracers;
    private final NametagsModule nametags;
    private final StorageESPModule storageEsp;
    private final HoleESPModule holeEsp;
    private final BlockESPModule blockEsp;
    private final TrajectoriesModule trajectories;
    private final WaypointsModule waypointOverlay;
    private final NewChunksModule newChunks;
    private final LogoutSpotsModule logoutSpots;
    private final StashFinderModule stashFinder;
    private final PortalCoordsModule portalCoords;
    private final BoundedBlockScanner blockScanner = new BoundedBlockScanner();
    private final Set<BlockPos> storageMarkers = new LinkedHashSet<>();

    private ResourceKey<Level> storageDimension;
    private int storageTicker;
    private boolean initialized;

    public WorldOverlayRenderer(
            FriendManager friends,
            WaypointManager waypoints,
            PlayerESPModule playerEsp,
            TracersModule tracers,
            NametagsModule nametags,
            StorageESPModule storageEsp,
            HoleESPModule holeEsp,
            BlockESPModule blockEsp,
            TrajectoriesModule trajectories,
            WaypointsModule waypointOverlay,
            NewChunksModule newChunks,
            LogoutSpotsModule logoutSpots,
            StashFinderModule stashFinder,
            PortalCoordsModule portalCoords
    ) {
        this.friends = friends;
        this.waypoints = waypoints;
        this.playerEsp = playerEsp;
        this.tracers = tracers;
        this.nametags = nametags;
        this.storageEsp = storageEsp;
        this.holeEsp = holeEsp;
        this.blockEsp = blockEsp;
        this.trajectories = trajectories;
        this.waypointOverlay = waypointOverlay;
        this.newChunks = newChunks;
        this.logoutSpots = logoutSpots;
        this.stashFinder = stashFinder;
        this.portalCoords = portalCoords;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.AFTER_ENTITIES.register(this::render);
    }

    public boolean initialized() {
        return initialized;
    }

    private void onTick(Minecraft minecraft) {
        try {
            blockScanner.tick(minecraft, blockEsp, holeEsp);
            updateStorageMarkers(minecraft);
        } catch (RuntimeException exception) {
            B2TClient.LOGGER.error("World overlay scan failed; clearing transient caches", exception);
            blockScanner.clear();
            storageMarkers.clear();
        }
    }

    private void render(WorldRenderContext context) {
        PoseStack poses = context.matrixStack();
        MultiBufferSource consumers = context.consumers();
        Minecraft minecraft = Minecraft.getInstance();
        if (poses == null || consumers == null || minecraft.player == null
                || minecraft.level == null) {
            return;
        }

        try {
            Camera camera = context.camera();
            Vec3 cameraPosition = camera.getPosition();
            float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);

            renderPlayers(context, poses, consumers, cameraPosition, partialTick);
            renderStorage(context, poses, consumers, cameraPosition);
            renderBlockSearch(context, poses, consumers, cameraPosition);
            renderHoles(context, poses, consumers, cameraPosition);
            renderWaypoints(context, poses, consumers, cameraPosition);
            renderTrajectory(context, poses, consumers, cameraPosition);
            renderNewChunks(context, poses, consumers, cameraPosition);
            renderLogoutSpots(context, poses, consumers, cameraPosition);
            renderStashes(context, poses, consumers, cameraPosition);
            renderPortals(context, poses, consumers, cameraPosition);
        } catch (RuntimeException exception) {
            B2TClient.LOGGER.error("World overlay render pass failed", exception);
        }
    }

    private void renderPlayers(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition,
            float partialTick
    ) {
        if (!playerEsp.isEnabled() && !tracers.isEnabled() && !nametags.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int renderedLabels = 0;
        List<AbstractClientPlayer> players = new ArrayList<>(context.world().players());
        players.sort(Comparator.comparingDouble(player ->
                player.distanceToSqr(cameraPosition)
        ));
        for (AbstractClientPlayer player : players) {
            boolean self = player == minecraft.player;
            boolean friend = friends.isFriend(player);
            Vec3 position = player.getPosition(partialTick);
            double distanceSquared = position.distanceToSqr(cameraPosition);

            if (playerEsp.isEnabled()
                    && visiblePlayer(playerEsp.showSelf(), playerEsp.showFriends(), self, friend)
                    && distanceSquared <= square(playerEsp.range())) {
                int color = friend ? playerEsp.friendColor() : playerEsp.playerColor();
                AABB worldBox = player.getBoundingBox().move(position.subtract(player.position()));
                if (context.frustum() == null || context.frustum().isVisible(worldBox)) {
                    AABB box = WorldRenderPrimitives.cameraRelative(worldBox.inflate(0.03), cameraPosition);
                    if (playerEsp.fill()) {
                        WorldRenderPrimitives.filledBox(poses, consumers, box, color, 0.30F);
                    }
                    if (playerEsp.outline()) {
                        WorldRenderPrimitives.outlinedBox(poses, consumers, box, color);
                    }
                }
            }

            if (tracers.isEnabled()
                    && visiblePlayer(tracers.showSelf(), tracers.showFriends(), self, friend)
                    && distanceSquared <= square(tracers.range())) {
                int color = friend ? tracers.friendColor() : tracers.playerColor();
                Vec3 start = cameraPosition.add(
                        context.camera().getLookVector().x * 0.5,
                        context.camera().getLookVector().y * 0.5,
                        context.camera().getLookVector().z * 0.5
                );
                WorldRenderPrimitives.line(
                        poses,
                        consumers,
                        cameraPosition,
                        start,
                        position.add(0.0, player.getBbHeight() * 0.5, 0.0),
                        color
                );
            }

            if (nametags.isEnabled() && renderedLabels < MAX_PLAYER_LABELS
                    && visiblePlayer(nametags.showSelf(), nametags.showFriends(), self, friend)
                    && distanceSquared <= square(nametags.range())) {
                int color = friend ? nametags.friendColor() : nametags.playerColor();
                renderFloatingText(
                        poses,
                        consumers,
                        buildNametag(player, Math.sqrt(distanceSquared)),
                        position.add(0.0, player.getBbHeight() + 0.35, 0.0),
                        color,
                        nametags.backgroundColor(),
                        (float) (0.025 * nametags.scale()),
                        cameraPosition,
                        context.camera()
                );
                renderedLabels++;
            }
        }
    }

    private void renderStorage(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!storageEsp.isEnabled()) {
            return;
        }
        double rangeSquared = square(storageEsp.range());
        for (BlockPos position : storageMarkers) {
            if (position.distToCenterSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z)
                    > rangeSquared) {
                continue;
            }
            AABB worldBox = new AABB(position).inflate(0.01);
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            WorldRenderPrimitives.filledBox(
                    poses,
                    consumers,
                    box,
                    storageEsp.color(),
                    0.22F
            );
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, storageEsp.color());
        }
    }

    private void renderBlockSearch(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!blockEsp.isEnabled()) {
            return;
        }
        double rangeSquared = square(blockEsp.range());
        for (BlockPos position : blockScanner.blockMatches().keySet()) {
            if (position.distToCenterSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z)
                    > rangeSquared) {
                continue;
            }
            AABB worldBox = new AABB(position).inflate(0.015);
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            WorldRenderPrimitives.filledBox(poses, consumers, box, blockEsp.color(), 0.18F);
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, blockEsp.color());
        }
    }

    private void renderHoles(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!holeEsp.isEnabled()) {
            return;
        }
        double rangeSquared = square(holeEsp.range());
        for (var entry : blockScanner.holes().entrySet()) {
            BlockPos position = entry.getKey();
            if (position.distToCenterSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z)
                    > rangeSquared
                    || entry.getValue() == BoundedBlockScanner.HoleSafety.UNSAFE
                    && !holeEsp.showUnsafe()) {
                continue;
            }
            int color = switch (entry.getValue()) {
                case SAFE -> holeEsp.safeColor();
                case MIXED -> holeEsp.mixedColor();
                case UNSAFE -> holeEsp.unsafeColor();
            };
            AABB worldBox = new AABB(
                    position.getX() + 0.05,
                    position.getY() + 0.02,
                    position.getZ() + 0.05,
                    position.getX() + 0.95,
                    position.getY() + 0.12,
                    position.getZ() + 0.95
            );
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            WorldRenderPrimitives.filledBox(poses, consumers, box, color, 0.45F);
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, color);
        }
    }

    private void renderWaypoints(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!waypointOverlay.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String server = minecraft.getCurrentServer() == null
                ? "singleplayer"
                : minecraft.getCurrentServer().ip;
        String dimension = context.world().dimension().location().toString();
        for (Waypoint waypoint : waypoints.visibleFor(server, dimension)) {
            Vec3 position = new Vec3(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5);
            double distance = position.distanceTo(cameraPosition);
            if (distance > waypointOverlay.renderDistance()) {
                continue;
            }
            if (waypointOverlay.beams()) {
                WorldRenderPrimitives.line(
                        poses,
                        consumers,
                        cameraPosition,
                        new Vec3(position.x, context.world().getMinY(), position.z),
                        new Vec3(position.x, context.world().getMaxY(), position.z),
                        waypoint.color()
                );
            }
            if (waypointOverlay.labels()) {
                renderFloatingText(
                        poses,
                        consumers,
                        waypoint.name() + " [" + Math.round(distance) + "m]",
                        position,
                        WorldRenderPrimitives.opaque(waypoint.color()),
                        0x99000000,
                        0.025F,
                        cameraPosition,
                        context.camera()
                );
            }
        }
    }

    private void renderTrajectory(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!trajectories.isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ProjectileParameters projectile = projectileParameters(minecraft);
        if (projectile == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        Vec3 position = minecraft.player.getEyePosition();
        Vec3 velocity = minecraft.player.getViewVector(
                context.tickCounter().getGameTimeDeltaPartialTick(true)
        ).normalize().scale(projectile.speed());
        double travelled = 0.0;
        for (int step = 0; step < trajectories.steps()
                && travelled < trajectories.range(); step++) {
            Vec3 next = position.add(velocity);
            HitResult hit = minecraft.level.clip(new ClipContext(
                    position,
                    next,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    minecraft.player
            ));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
            WorldRenderPrimitives.line(
                    poses,
                    consumers,
                    cameraPosition,
                    position,
                    end,
                    trajectories.color()
            );
            travelled += position.distanceTo(end);
            if (hit.getType() != HitResult.Type.MISS) {
                AABB marker = new AABB(
                        end.x - 0.08,
                        end.y - 0.08,
                        end.z - 0.08,
                        end.x + 0.08,
                        end.y + 0.08,
                        end.z + 0.08
                );
                WorldRenderPrimitives.filledBox(
                        poses,
                        consumers,
                        WorldRenderPrimitives.cameraRelative(marker, cameraPosition),
                        trajectories.color(),
                        0.70F
                );
                break;
            }
            position = next;
            velocity = velocity.scale(projectile.drag()).add(0.0, -projectile.gravity(), 0.0);
        }
    }

    private void renderNewChunks(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!newChunks.isEnabled()) {
            return;
        }
        NewChunksModule.RenderSettings settings = newChunks.renderSettings();
        for (NewChunksModule.ChunkSnapshot chunk : newChunks.freshlyObservedSnapshot()) {
            AABB worldBox = new AABB(
                    chunk.minimumBlockX(),
                    context.world().getMinY(),
                    chunk.minimumBlockZ(),
                    chunk.minimumBlockX() + 16,
                    context.world().getMinY() + 0.15,
                    chunk.minimumBlockZ() + 16
            );
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            if (settings.filled()) {
                WorldRenderPrimitives.filledBox(
                        poses,
                        consumers,
                        box,
                        settings.argb(),
                        0.25F
                );
            }
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, settings.argb());
        }
    }

    private void renderLogoutSpots(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!logoutSpots.isEnabled()) {
            return;
        }
        LogoutSpotsModule.RenderSettings settings = logoutSpots.renderSettings();
        for (LogoutSpotsModule.LogoutSpotSnapshot spot : logoutSpots.snapshot()) {
            Vec3 position = new Vec3(spot.x(), spot.y(), spot.z());
            if (position.distanceToSqr(cameraPosition) > square(512.0)) {
                continue;
            }
            AABB worldBox = new AABB(
                    position.x - 0.3,
                    position.y,
                    position.z - 0.3,
                    position.x + 0.3,
                    position.y + 1.8,
                    position.z + 0.3
            );
            if (context.frustum() == null || context.frustum().isVisible(worldBox)) {
                AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
                WorldRenderPrimitives.filledBox(
                        poses,
                        consumers,
                        box,
                        settings.argb(),
                        0.18F
                );
                WorldRenderPrimitives.outlinedBox(poses, consumers, box, settings.argb());
            }
            if (settings.tracer()) {
                WorldRenderPrimitives.line(
                        poses,
                        consumers,
                        cameraPosition,
                        cameraPosition,
                        position.add(0.0, 0.9, 0.0),
                        settings.argb()
                );
            }
            if (settings.showName()) {
                renderFloatingText(
                        poses,
                        consumers,
                        spot.playerName() + " logged out",
                        position.add(0.0, 2.1, 0.0),
                        WorldRenderPrimitives.opaque(settings.argb()),
                        0x99000000,
                        0.025F,
                        cameraPosition,
                        context.camera()
                );
            }
        }
    }

    private void renderStashes(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!stashFinder.isEnabled()) {
            return;
        }
        String dimension = context.world().dimension().location().toString();
        double rangeSquared = square(stashFinder.range());
        int rendered = 0;
        for (StashFinderModule.StashSnapshot stash : stashFinder.snapshot()) {
            if (rendered >= MAX_FINDER_MARKERS || !dimension.equals(stash.dimension())) {
                continue;
            }
            Vec3 center = new Vec3(stash.centerX(), stash.centerY(), stash.centerZ());
            if (center.distanceToSqr(cameraPosition) > rangeSquared) {
                continue;
            }
            AABB worldBox = new AABB(
                    stash.minimumChunkX() * 16.0,
                    stash.centerY() - 0.5,
                    stash.minimumChunkZ() * 16.0,
                    (stash.maximumChunkX() + 1) * 16.0,
                    stash.centerY() + 0.5,
                    (stash.maximumChunkZ() + 1) * 16.0
            );
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            WorldRenderPrimitives.filledBox(poses, consumers, box, STASH_COLOR, 0.12F);
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, STASH_COLOR);
            renderFloatingText(
                    poses,
                    consumers,
                    "Stash · " + stash.containerCount() + " containers",
                    center.add(0.0, 1.2, 0.0),
                    STASH_COLOR,
                    0x99000000,
                    0.025F,
                    cameraPosition,
                    context.camera()
            );
            rendered++;
        }
    }

    private void renderPortals(
            WorldRenderContext context,
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition
    ) {
        if (!portalCoords.isEnabled()) {
            return;
        }
        String dimension = context.world().dimension().location().toString();
        double rangeSquared = square(portalCoords.scanRange());
        int rendered = 0;
        for (PortalCoordsModule.PortalSnapshot portal : portalCoords.snapshot()) {
            if (rendered >= MAX_FINDER_MARKERS || !dimension.equals(portal.dimension())) {
                continue;
            }
            Vec3 center = new Vec3(portal.centerX(), portal.centerY(), portal.centerZ());
            if (center.distanceToSqr(cameraPosition) > rangeSquared) {
                continue;
            }
            AABB worldBox = new AABB(
                    portal.minimumX(),
                    portal.minimumY(),
                    portal.minimumZ(),
                    portal.maximumX() + 1.0,
                    portal.maximumY() + 1.0,
                    portal.maximumZ() + 1.0
            ).inflate(0.02);
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            AABB box = WorldRenderPrimitives.cameraRelative(worldBox, cameraPosition);
            WorldRenderPrimitives.filledBox(poses, consumers, box, PORTAL_COLOR, 0.16F);
            WorldRenderPrimitives.outlinedBox(poses, consumers, box, PORTAL_COLOR);
            renderFloatingText(
                    poses,
                    consumers,
                    portalLabel(portal),
                    center.add(0.0, 1.0, 0.0),
                    PORTAL_COLOR,
                    0x99000000,
                    0.025F,
                    cameraPosition,
                    context.camera()
            );
            rendered++;
        }
    }

    private static String portalLabel(PortalCoordsModule.PortalSnapshot portal) {
        return PortalCoordsModule.convert(
                        portal.dimension(),
                        portal.centerX(),
                        portal.centerY(),
                        portal.centerZ()
                )
                .map(conversion -> String.format(
                        Locale.ROOT,
                        "Portal → %s %.0f, %.0f, %.0f",
                        shortDimension(conversion.targetDimension()),
                        conversion.targetX(),
                        conversion.targetY(),
                        conversion.targetZ()
                ))
                .orElse("Portal · " + portal.portalBlockCount() + " blocks");
    }

    private static String shortDimension(String dimension) {
        if (PortalCoordsModule.OVERWORLD.equals(dimension)) {
            return "Overworld";
        }
        if (PortalCoordsModule.NETHER.equals(dimension)) {
            return "Nether";
        }
        return dimension;
    }

    private void updateStorageMarkers(Minecraft minecraft) {
        if (!storageEsp.isEnabled() || minecraft.level == null || minecraft.player == null) {
            storageMarkers.clear();
            storageDimension = null;
            storageTicker = 0;
            return;
        }
        if (storageDimension != minecraft.level.dimension()) {
            storageDimension = minecraft.level.dimension();
            storageTicker = STORAGE_REFRESH_TICKS;
            storageMarkers.clear();
        }
        if (++storageTicker < STORAGE_REFRESH_TICKS) {
            return;
        }
        storageTicker = 0;

        int range = storageEsp.range();
        int centreX = minecraft.player.chunkPosition().x;
        int centreZ = minecraft.player.chunkPosition().z;
        int chunkRange = (range + 15) / 16;
        double rangeSquared = square(range);
        LinkedHashSet<BlockPos> refreshed = new LinkedHashSet<>();
        for (int chunkX = centreX - chunkRange; chunkX <= centreX + chunkRange; chunkX++) {
            for (int chunkZ = centreZ - chunkRange; chunkZ <= centreZ + chunkRange; chunkZ++) {
                if (!minecraft.level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = minecraft.level.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (isStorage(blockEntity, storageEsp.includeShulkers())
                            && blockEntity.getBlockPos().distToCenterSqr(
                                    minecraft.player.getX(),
                                    minecraft.player.getY(),
                                    minecraft.player.getZ()
                            ) <= rangeSquared) {
                        refreshed.add(blockEntity.getBlockPos().immutable());
                        if (refreshed.size() >= MAX_STORAGE_MARKERS) {
                            storageMarkers.clear();
                            storageMarkers.addAll(refreshed);
                            return;
                        }
                    }
                }
            }
        }
        storageMarkers.clear();
        storageMarkers.addAll(refreshed);
    }

    private String buildNametag(AbstractClientPlayer player, double distance) {
        StringBuilder result = new StringBuilder(player.getGameProfile().getName());
        if (nametags.showHealth()) {
            result.append(String.format(Locale.ROOT, " %.1f❤",
                    player.getHealth() + player.getAbsorptionAmount()));
        }
        if (nametags.showDistance()) {
            result.append(" ").append(Math.round(distance)).append("m");
        }
        if (nametags.showEquipment()) {
            appendEquipment(result, player);
        }
        return result.toString();
    }

    private static void appendEquipment(StringBuilder text, AbstractClientPlayer player) {
        List<ItemStack> equipment = List.of(
                player.getItemBySlot(EquipmentSlot.HEAD),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.FEET),
                player.getMainHandItem(),
                player.getOffhandItem()
        );
        boolean started = false;
        for (ItemStack stack : equipment) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!started) {
                text.append(" [");
                started = true;
            } else {
                text.append(" · ");
            }
            text.append(stack.getHoverName().getString());
            if (stack.isDamageableItem()) {
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                int percent = Math.round(remaining * 100.0F / stack.getMaxDamage());
                text.append(' ').append(percent).append('%');
            }
        }
        if (started) {
            text.append(']');
        }
    }

    private static void renderFloatingText(
            PoseStack poses,
            MultiBufferSource consumers,
            String text,
            Vec3 position,
            int color,
            int backgroundColor,
            float scale,
            Vec3 cameraPosition,
            Camera camera
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        poses.pushPose();
        poses.translate(
                (float) (position.x - cameraPosition.x),
                (float) (position.y - cameraPosition.y),
                (float) (position.z - cameraPosition.z)
        );
        poses.mulPose(camera.rotation());
        poses.scale(scale, -scale, scale);
        Matrix4f matrix = poses.last().pose();
        font.drawInBatch(
                text,
                -font.width(text) / 2.0F,
                0.0F,
                color,
                false,
                matrix,
                consumers,
                Font.DisplayMode.SEE_THROUGH,
                backgroundColor,
                0x00F000F0
        );
        poses.popPose();
    }

    private static ProjectileParameters projectileParameters(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        ItemStack stack = minecraft.player.getUseItem();
        if (stack.isEmpty()) {
            stack = minecraft.player.getMainHandItem();
        }
        if (stack.isEmpty()) {
            stack = minecraft.player.getOffhandItem();
        }
        Item item = stack.getItem();
        if (item == Items.BOW) {
            int useTicks = minecraft.player.isUsingItem()
                    ? minecraft.player.getTicksUsingItem()
                    : BowItem.MAX_DRAW_DURATION;
            return new ProjectileParameters(
                    Math.max(0.1F, BowItem.getPowerForTime(useTicks)) * 3.0,
                    0.99,
                    0.05
            );
        }
        if (item == Items.TRIDENT) {
            return new ProjectileParameters(2.5, 0.99, 0.05);
        }
        if (item == Items.SNOWBALL || item == Items.EGG || item == Items.ENDER_PEARL) {
            return new ProjectileParameters(1.5, 0.99, 0.03);
        }
        if (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION
                || item == Items.EXPERIENCE_BOTTLE) {
            return new ProjectileParameters(0.7, 0.99, 0.05);
        }
        return null;
    }

    private static boolean isStorage(BlockEntity blockEntity, boolean includeShulkers) {
        BlockEntityType<?> type = blockEntity.getType();
        return type == BlockEntityType.CHEST
                || type == BlockEntityType.TRAPPED_CHEST
                || type == BlockEntityType.ENDER_CHEST
                || type == BlockEntityType.BARREL
                || type == BlockEntityType.HOPPER
                || type == BlockEntityType.DISPENSER
                || type == BlockEntityType.DROPPER
                || type == BlockEntityType.FURNACE
                || type == BlockEntityType.BLAST_FURNACE
                || type == BlockEntityType.SMOKER
                || type == BlockEntityType.BREWING_STAND
                || type == BlockEntityType.CRAFTER
                || includeShulkers && type == BlockEntityType.SHULKER_BOX;
    }

    private static boolean visiblePlayer(
            boolean showSelf,
            boolean showFriends,
            boolean self,
            boolean friend
    ) {
        return (!self || showSelf) && (!friend || showFriends);
    }

    private static double square(double value) {
        return value * value;
    }

    private record ProjectileParameters(double speed, double drag, double gravity) {
    }
}
