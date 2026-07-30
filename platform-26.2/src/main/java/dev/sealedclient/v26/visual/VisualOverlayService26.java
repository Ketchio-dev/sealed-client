package dev.sealedclient.v26.visual;

import dev.sealedclient.v26.visual.EntityOverlayDecisionEngine26.Candidate;
import dev.sealedclient.v26.visual.EntityOverlayDecisionEngine26.Configuration;
import dev.sealedclient.v26.visual.EntityOverlayDecisionEngine26.OverlayPlan;
import dev.sealedclient.v26.visual.EntityOverlayDecisionEngine26.OverlayPolicy;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.Collision;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.CollisionKind;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.Limits;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.ProjectileParameters;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.ProjectileType;
import dev.sealedclient.v26.visual.TrajectoryDecisionEngine26.Vector3;
import dev.sealedclient.v26.visual.VisualOverlayRenderer26.Box;
import dev.sealedclient.v26.visual.VisualOverlayRenderer26.FrameSnapshot;
import dev.sealedclient.v26.visual.VisualOverlayRenderer26.Label;
import dev.sealedclient.v26.visual.VisualOverlayRenderer26.Line;
import dev.sealedclient.v26.visual.VisualOverlayRenderer26.Point;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One registration boundary for all seven Minecraft 26.2 visual overlays.
 *
 * <p>Work is split according to the 26.2 render architecture:</p>
 *
 * <ol>
 *   <li>client ticks perform bounded storage/block/hole scans;</li>
 *   <li>end-extraction prepares immutable entity, text, box, and trajectory
 *       data;</li>
 *   <li>collect-submits consumes only that immutable frame.</li>
 * </ol>
 *
 * <p>The submit callback never reads the level. Session, level identity, or
 * dimension changes clear every scan cache before it can be rendered.</p>
 */
public final class VisualOverlayService26 {
    public static final int MAX_COMBINED_BLOCK_PROBES_PER_TICK = 16_384;
    public static final int MAX_STORAGE_DISCOVERIES_PER_TICK = 256;
    public static final int MAX_STORAGE_CHUNKS_PER_TICK = 8;
    private static final int HOLE_VERTICAL_SCAN_RANGE = 16;
    private static final int MAX_SCAN_PENDING = 32_768;
    private static final int MAX_LABEL_CHARACTERS = 512;
    private static final int MAX_OUTLINED_BOXES_PER_WORLD_OVERLAY = 256;
    private static final int[][] HOLE_WALL_OFFSETS = {
            {0, -1, 0},
            {0, 0, -1},
            {0, 0, 1},
            {1, 0, 0},
            {-1, 0, 0}
    };
    private static final RenderStateDataKey<FrameSnapshot> FRAME_DATA_KEY =
            RenderStateDataKey.create(
                    () -> "sealedclient:visual_overlay_frame"
            );
    private static volatile VisualOverlayService26 activeNametagService;

    private final PlayerRelationResolver relationResolver;
    private final VisualOverlayRenderer26 renderer =
            new VisualOverlayRenderer26();

    private volatile VisualOverlayConfiguration26 configuration =
            VisualOverlayConfiguration26.DISABLED;
    private volatile FrameSnapshot extractedFrame = FrameSnapshot.EMPTY;
    private volatile Set<Integer> selectedNametagEntityIds = Set.of();

    private WorldOverlayScanEngine26<BlockKey, Boolean> blockScanner;
    private WorldOverlayScanEngine26<BlockKey, HoleSafety> holeScanner;
    private WorldOverlayScanEngine26<BlockKey, Boolean> storageScanner;
    private Set<Identifier> compiledBlockTargets = Set.of();
    private ScanAllocation scanAllocation;

    private BlockPos blockOrigin;
    private BlockPos holeOrigin;
    private int storageOriginChunkX;
    private int storageOriginChunkZ;
    private boolean storageOriginPresent;
    private long blockCursor;
    private long holeCursor;
    private long storageChunkCursor;
    private Object lastScannedSession;
    private ClientLevel lastScannedLevel;
    private boolean initialized;

    public VisualOverlayService26(PlayerRelationResolver relationResolver) {
        this.relationResolver = Objects.requireNonNull(
                relationResolver,
                "relationResolver"
        );
        compiledBlockTargets = compileBlockTargets(
                configuration.blockEsp().targets()
        );
        rebuildScanners(configuration);
    }

    /**
     * Registers tick, extraction, and submit hooks exactly once.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        activeNametagService = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(
                context -> renderer.submit(
                        context,
                        fabricRenderState(context.levelState())
                                .getDataOrDefault(
                                        FRAME_DATA_KEY,
                                        FrameSnapshot.EMPTY
                                )
                )
        );
    }

    public boolean initialized() {
        return initialized;
    }

    public VisualOverlayConfiguration26 configuration() {
        return configuration;
    }

    public void setConfiguration(
            VisualOverlayConfiguration26 nextConfiguration
    ) {
        VisualOverlayConfiguration26 required = Objects.requireNonNull(
                nextConfiguration,
                "nextConfiguration"
        );
        VisualOverlayConfiguration26 previous = configuration;
        ScanAllocation previousAllocation = scanAllocation;
        configuration = required;
        if (!previous.nametags().equals(required.nametags())) {
            selectedNametagEntityIds = Set.of();
        }
        boolean targetsChanged = !previous.blockEsp().targets().equals(
                required.blockEsp().targets()
        );
        if (targetsChanged) {
            compiledBlockTargets = compileBlockTargets(
                    required.blockEsp().targets()
            );
        }
        ScanAllocation nextAllocation = allocateScanBudgets(required);
        scanAllocation = nextAllocation;
        if (targetsChanged
                || !sameBlockScanSettings(
                        previous.blockEsp(),
                        required.blockEsp()
                )
                || previousAllocation.blockProbeBudget()
                        != nextAllocation.blockProbeBudget()
                || previousAllocation.blockAdmissionBudget()
                        != nextAllocation.blockAdmissionBudget()) {
            rebuildBlockScanner(required.blockEsp());
        }
        if (!sameHoleScanSettings(
                previous.holeEsp(),
                required.holeEsp()
        )
                || previousAllocation.holeProbeBudget()
                        != nextAllocation.holeProbeBudget()
                || previousAllocation.holeAdmissionBudget()
                        != nextAllocation.holeAdmissionBudget()) {
            rebuildHoleScanner(required.holeEsp());
        }
        if (!sameStorageScanSettings(
                previous.storageEsp(),
                required.storageEsp()
        )) {
            rebuildStorageScanner(required.storageEsp());
        }
        if (!required.anyEnabled()) {
            extractedFrame = FrameSnapshot.EMPTY;
        }
    }

    /**
     * Explicit lifecycle reset for disconnect, shutdown, and test harnesses.
     */
    public void reset() {
        resetWorldState();
    }

    public FrameSnapshot extractedFrame() {
        return extractedFrame;
    }

    public static boolean suppressVanillaNametag(
            AbstractClientPlayer player,
            double distanceSquared
    ) {
        VisualOverlayService26 service = activeNametagService;
        if (service == null
                || player == null
                || !Double.isFinite(distanceSquared)
                || !service.selectedNametagEntityIds.contains(
                        player.getId()
                )) {
            return false;
        }
        VisualOverlayConfiguration26.Nametags settings =
                service.configuration.nametags();
        if (!settings.enabled()
                || !player.isAlive()
                || player.isSpectator()
                || distanceSquared > square(settings.range())) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        /*
         * END_EXTRACTION publishes the selected set after vanilla asks this
         * question. Suppress only when every discovered player fits in the
         * configured label cap; otherwise rank changes could make the
         * previous-frame set hide a label that is not selected this frame.
         */
        if (client.level == null
                || client.level.players().size() > settings.renderCap()) {
            return false;
        }
        if (player == client.player) {
            return settings.showSelf()
                    && !client.options.getCameraType().isFirstPerson();
        }
        try {
            boolean friend = service.relationResolver.isFriend(
                    player.getUUID(),
                    player.getGameProfile().name()
            );
            return !friend || settings.showFriends();
        } catch (RuntimeException ignored) {
            // Fail open: never hide vanilla text if relation data is unsafe.
            return false;
        }
    }

    /**
     * Effective hard budgets after the shared block-probe allocator is
     * applied. This is suitable for diagnostics and performance tests.
     */
    public BudgetSnapshot budgets() {
        ScanAllocation allocation = scanAllocation;
        return new BudgetSnapshot(
                allocation.blockProbeBudget(),
                allocation.blockAdmissionBudget(),
                allocation.holeProbeBudget(),
                allocation.holeAdmissionBudget(),
                MAX_STORAGE_DISCOVERIES_PER_TICK,
                EntityOverlayDecisionEngine26.MAXIMUM_CANDIDATES,
                VisualOverlayRenderer26.MAX_BOXES_PER_FRAME,
                VisualOverlayRenderer26.MAX_LINES_PER_FRAME,
                VisualOverlayRenderer26.MAX_LABELS_PER_FRAME
        );
    }

    public ScanDiagnostics scanDiagnostics() {
        var blocks = blockScanner.snapshot();
        var holes = holeScanner.snapshot();
        var storage = storageScanner.snapshot();
        return new ScanDiagnostics(
                blocks.pendingCandidates(),
                blocks.entries().size(),
                holes.pendingCandidates(),
                holes.entries().size(),
                storage.pendingCandidates(),
                storage.entries().size()
        );
    }

    /**
     * The position scans are centred on: the detached Freecam camera when it is
     * attached, otherwise the player. Falls back to the player if Freecam
     * reports no camera, so a half-torn-down camera cannot blank the overlays.
     */
    static Vec3 scanViewpoint(LocalPlayer player, Vec3 cameraPosition) {
        if (cameraPosition != null) {
            return cameraPosition;
        }
        return player.position();
    }

    private static Vec3 scanViewpoint(LocalPlayer player) {
        return scanViewpoint(
                player,
                FreecamController26.active() ? FreecamController26.activeCameraPosition() : null
        );
    }

    private void tick(Minecraft client) {
        VisualOverlayConfiguration26 settings = configuration;
        if (client == null
                || client.level == null
                || client.player == null
                || client.getConnection() == null
                || !settings.anyScanOverlayEnabled()) {
            resetScannersOnly();
            lastScannedSession = null;
            lastScannedLevel = null;
            if (client == null
                    || client.level == null
                    || client.player == null
                    || client.getConnection() == null
                    || !settings.anyEnabled()) {
                extractedFrame = FrameSnapshot.EMPTY;
            }
            return;
        }

        Object session = client.getConnection();
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        WorldOverlayScanEngine26.Scope scope =
                new WorldOverlayScanEngine26.Scope(
                        session,
                        level,
                        level.dimension()
                );
        // Scans follow the rendered viewpoint. With Freecam detached the player
        // body stays behind, so scanning from the body would highlight blocks
        // around a location the user is no longer looking at.
        Vec3 viewpoint = scanViewpoint(player);
        BlockPos scanOrigin = BlockPos.containing(viewpoint);
        WorldOverlayScanEngine26.Point observer =
                new WorldOverlayScanEngine26.Point(
                        viewpoint.x(),
                        viewpoint.y(),
                        viewpoint.z()
                );

        if (settings.blockEsp().enabled()) {
            updateBlockOrigin(scanOrigin, settings.blockEsp());
            blockScanner.tick(
                    scope,
                    observer,
                    blockCandidates(
                            blockOrigin,
                            settings.blockEsp().range(),
                            scanAllocation.blockAdmissionBudget()
                    ),
                    candidate -> inspectBlock(
                            level,
                            candidate,
                            compiledBlockTargets
                    )
            );
        } else {
            blockScanner.reset();
            blockOrigin = null;
            blockCursor = 0L;
        }

        if (settings.holeEsp().enabled()) {
            updateHoleOrigin(scanOrigin, settings.holeEsp());
            holeScanner.tick(
                    scope,
                    observer,
                    holeCandidates(
                            holeOrigin,
                            settings.holeEsp().range(),
                            scanAllocation.holeAdmissionBudget()
                    ),
                    candidate -> inspectHole(level, candidate)
            );
        } else {
            holeScanner.reset();
            holeOrigin = null;
            holeCursor = 0L;
        }

        if (settings.storageEsp().enabled()) {
            updateStorageOrigin(player, settings.storageEsp());
            storageScanner.tick(
                    scope,
                    observer,
                    storageCandidates(level, settings.storageEsp()),
                    candidate -> inspectStorage(
                            level,
                            candidate,
                            settings.storageEsp().includeShulkers()
                    )
            );
        } else {
            storageScanner.reset();
            storageOriginPresent = false;
            storageChunkCursor = 0L;
        }

        lastScannedSession = session;
        lastScannedLevel = level;
    }

    private void extract(LevelExtractionContext context) {
        VisualOverlayConfiguration26 settings = configuration;
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = context.level();
        LocalPlayer local = client.player;
        if (!settings.anyEnabled()
                || local == null
                || client.level != level
                || client.getConnection() == null) {
            publishFrame(context, FrameSnapshot.EMPTY, Set.of());
            return;
        }

        float partialTick =
                context.deltaTracker().getGameTimeDeltaPartialTick(true);
        Camera camera = context.camera();
        Vec3 cameraPosition = camera.position();
        Frustum frustum =
                context.levelState().cameraRenderState.cullFrustum;
        FrameBuilder frame = new FrameBuilder();

        // Reserve the bounded trajectory path before high-cardinality boxes.
        extractTrajectory(
                level,
                local,
                settings.trajectories(),
                partialTick,
                frame
        );
        Set<Integer> nametagEntityIds = extractPlayers(
                level,
                local,
                client,
                settings,
                camera,
                cameraPosition,
                frustum,
                partialTick,
                frame
        );

        boolean scanScopeCurrent =
                lastScannedSession == client.getConnection()
                        && lastScannedLevel == level;
        if (scanScopeCurrent) {
            Vec3 scanObserver = local.position();
            extractStorage(
                    settings.storageEsp(),
                    cameraPosition,
                    scanObserver,
                    frustum,
                    frame
            );
            extractBlocks(
                    settings.blockEsp(),
                    cameraPosition,
                    scanObserver,
                    frustum,
                    frame
            );
            extractHoles(
                    settings.holeEsp(),
                    cameraPosition,
                    scanObserver,
                    frustum,
                    frame
            );
        }

        publishFrame(context, frame.build(), nametagEntityIds);
    }

    private Set<Integer> extractPlayers(
            ClientLevel level,
            LocalPlayer local,
            Minecraft client,
            VisualOverlayConfiguration26 settings,
            Camera camera,
            Vec3 cameraPosition,
            Frustum frustum,
            float partialTick,
            FrameBuilder frame
    ) {
        if (!settings.anyEntityOverlayEnabled()) {
            return Set.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        Map<Integer, PlayerFrame> playersById = new HashMap<>();
        int discovered = 0;
        for (AbstractClientPlayer player : level.players()) {
            if (discovered++ >= EntityOverlayDecisionEngine26.MAXIMUM_CANDIDATES) {
                break;
            }
            Vec3 position = player.getPosition(partialTick);
            AABB box = player.getBoundingBox().move(
                    position.subtract(player.position())
            );
            boolean inFrustum =
                    frustum == null || frustum.isVisible(box);
            boolean friend = relationResolver.isFriend(
                    player.getUUID(),
                    player.getGameProfile().name()
            );
            double distanceSquared =
                    position.distanceToSqr(cameraPosition);
            candidates.add(new Candidate(
                    player.getId(),
                    distanceSquared,
                    friend,
                    player == local,
                    player.isInvisible(),
                    player.isAlive(),
                    player.isSpectator(),
                    inFrustum,
                    true
            ));
            playersById.put(
                    player.getId(),
                    new PlayerFrame(
                            player,
                            position,
                            box,
                            friend,
                            distanceSquared
                    )
            );
        }

        OverlayPlan plan = EntityOverlayDecisionEngine26.decide(
                candidates,
                entityConfiguration(
                        settings,
                        !client.options.getCameraType().isFirstPerson()
                )
        );
        VisualOverlayConfiguration26.PlayerEsp playerEsp =
                settings.playerEsp();
        for (var selected : plan.playerEspTargets()) {
            PlayerFrame player = playersById.get(selected.entityId());
            if (player == null) {
                continue;
            }
            int color = player.friend()
                    ? playerEsp.friendColor()
                    : playerEsp.playerColor();
            if (playerEsp.fill()) {
                frame.addBox(toBox(player.box().inflate(0.03), color, 0.30F));
            }
            if (playerEsp.outline()) {
                frame.addBoxOutline(
                        player.box().inflate(0.03),
                        color,
                        1.5F
                );
            }
        }

        VisualOverlayConfiguration26.Tracers tracers = settings.tracers();
        Vec3 start = cameraPosition.add(
                camera.forwardVector().x() * 0.5,
                camera.forwardVector().y() * 0.5,
                camera.forwardVector().z() * 0.5
        );
        for (var selected : plan.tracerTargets()) {
            PlayerFrame player = playersById.get(selected.entityId());
            if (player == null) {
                continue;
            }
            int color = player.friend()
                    ? tracers.friendColor()
                    : tracers.playerColor();
            frame.addLine(new Line(
                    point(start),
                    point(player.position().add(
                            0.0,
                            player.entity().getBbHeight() * 0.5,
                            0.0
                    )),
                    color,
                    tracers.lineWidth()
            ));
        }

        VisualOverlayConfiguration26.Nametags nametags =
                settings.nametags();
        Set<Integer> nametagEntityIds = new HashSet<>();
        for (var selected : plan.nametagTargets()) {
            PlayerFrame player = playersById.get(selected.entityId());
            if (player == null) {
                continue;
            }
            nametagEntityIds.add(selected.entityId());
            int color = player.friend()
                    ? nametags.friendColor()
                    : nametags.playerColor();
            String text = buildNametag(
                    player.entity(),
                    Math.sqrt(player.distanceSquared()),
                    nametags
            );
            frame.addLabel(new Label(
                    point(player.position().add(
                            0.0,
                            player.entity().getBbHeight() + 0.35,
                            0.0
                    )),
                    net.minecraft.network.chat.Component.literal(text),
                    client.font.width(text),
                    color,
                    nametags.backgroundColor(),
                    nametags.scale()
            ));
        }
        return Set.copyOf(nametagEntityIds);
    }

    private void extractStorage(
            VisualOverlayConfiguration26.StorageEsp settings,
            Vec3 camera,
            Vec3 scanObserver,
            Frustum frustum,
            FrameBuilder frame
    ) {
        if (!settings.enabled()) {
            return;
        }
        List<WorldOverlayScanEngine26.CacheEntry<BlockKey, Boolean>> entries =
                entriesForCamera(
                        storageScanner.cacheEntries(),
                        camera,
                        scanObserver
                );
        int rendered = 0;
        int outlined = 0;
        double rangeSquared = square(settings.range());
        for (var entry : entries) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            if (distanceSquared(entry.position(), camera) > rangeSquared) {
                continue;
            }
            AABB box = blockBox(entry.key(), 0.01);
            if (!visible(frustum, box)) {
                continue;
            }
            frame.addBox(toBox(box, settings.color(), 0.22F));
            if (outlined < MAX_OUTLINED_BOXES_PER_WORLD_OVERLAY) {
                frame.addBoxOutline(box, settings.color(), 1.5F);
                outlined++;
            }
            rendered++;
        }
    }

    private void extractBlocks(
            VisualOverlayConfiguration26.BlockEsp settings,
            Vec3 camera,
            Vec3 scanObserver,
            Frustum frustum,
            FrameBuilder frame
    ) {
        if (!settings.enabled()) {
            return;
        }
        List<WorldOverlayScanEngine26.CacheEntry<BlockKey, Boolean>> entries =
                entriesForCamera(
                        blockScanner.cacheEntries(),
                        camera,
                        scanObserver
                );
        int rendered = 0;
        int outlined = 0;
        double rangeSquared = square(settings.range());
        for (var entry : entries) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            if (distanceSquared(entry.position(), camera) > rangeSquared) {
                continue;
            }
            AABB box = blockBox(entry.key(), 0.015);
            if (!visible(frustum, box)) {
                continue;
            }
            frame.addBox(toBox(box, settings.color(), 0.18F));
            if (outlined < MAX_OUTLINED_BOXES_PER_WORLD_OVERLAY) {
                frame.addBoxOutline(box, settings.color(), 1.5F);
                outlined++;
            }
            rendered++;
        }
    }

    private void extractHoles(
            VisualOverlayConfiguration26.HoleEsp settings,
            Vec3 camera,
            Vec3 scanObserver,
            Frustum frustum,
            FrameBuilder frame
    ) {
        if (!settings.enabled()) {
            return;
        }
        List<WorldOverlayScanEngine26.CacheEntry<BlockKey, HoleSafety>> entries =
                entriesForCamera(
                        holeScanner.cacheEntries(),
                        camera,
                        scanObserver
                );
        int rendered = 0;
        int outlined = 0;
        double rangeSquared = square(settings.range());
        for (var entry : entries) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            if (distanceSquared(entry.position(), camera) > rangeSquared) {
                continue;
            }
            HoleSafety safety = entry.value();
            if (safety == HoleSafety.UNSAFE && !settings.showUnsafe()) {
                continue;
            }
            int color = switch (safety) {
                case SAFE -> settings.safeColor();
                case MIXED -> settings.mixedColor();
                case UNSAFE -> settings.unsafeColor();
            };
            BlockKey key = entry.key();
            AABB box = new AABB(
                    key.x() + 0.05,
                    key.y() + 0.02,
                    key.z() + 0.05,
                    key.x() + 0.95,
                    key.y() + 0.12,
                    key.z() + 0.95
            );
            if (!visible(frustum, box)) {
                continue;
            }
            frame.addBox(toBox(box, color, 0.45F));
            if (outlined < MAX_OUTLINED_BOXES_PER_WORLD_OVERLAY) {
                frame.addBoxOutline(box, color, 1.5F);
                outlined++;
            }
            rendered++;
        }
    }

    private static void extractTrajectory(
            ClientLevel level,
            LocalPlayer player,
            VisualOverlayConfiguration26.Trajectories settings,
            float partialTick,
            FrameBuilder frame
    ) {
        if (!settings.enabled()) {
            return;
        }
        ProjectileParameters projectile = projectileParameters(player);
        if (projectile == null) {
            return;
        }
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 shooterMovement = player.getKnownMovement();
        Vector3 inheritedVelocity = new Vector3(
                shooterMovement.x,
                player.onGround() ? 0.0 : shooterMovement.y,
                shooterMovement.z
        );
        var result = TrajectoryDecisionEngine26.simulateFromRotation(
                vector(eye),
                player.getYRot(partialTick),
                player.getXRot(partialTick),
                inheritedVelocity,
                projectile,
                new Limits(settings.steps(), settings.range()),
                (start, end) -> traceTrajectory(
                        level,
                        player,
                        start,
                        end
                )
        );
        for (var segment : result.segments()) {
            frame.addLine(new Line(
                    point(segment.start()),
                    point(segment.end()),
                    settings.color(),
                    1.5F,
                    false
            ));
        }
        result.impact().ifPresent(impact -> {
            Vector3 hit = impact.position();
            AABB marker = new AABB(
                    hit.x() - 0.08,
                    hit.y() - 0.08,
                    hit.z() - 0.08,
                    hit.x() + 0.08,
                    hit.y() + 0.08,
                    hit.z() + 0.08
            );
            frame.addBox(toBox(
                    marker,
                    settings.color(),
                    0.70F,
                    false
            ));
            frame.addBoxOutline(
                    marker,
                    settings.color(),
                    1.5F,
                    false
            );
        });
    }

    private static Collision traceTrajectory(
            ClientLevel level,
            LocalPlayer player,
            Vector3 start,
            Vector3 end
    ) {
        Vec3 from = vec(start);
        Vec3 to = vec(end);
        HitResult worldHit = level.clip(new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                player
        ));
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                level,
                player,
                from,
                to,
                new AABB(from, to).inflate(1.0),
                entity -> entity != player
                        && entity.isPickable()
                        && !entity.isSpectator(),
                0.3F
        );

        double total = from.distanceTo(to);
        if (total < 1.0E-9) {
            return Collision.miss();
        }
        double worldDistance =
                worldHit.getType() == HitResult.Type.MISS
                        ? Double.POSITIVE_INFINITY
                        : from.distanceTo(worldHit.getLocation());
        double entityDistance =
                entityHit == null
                        ? Double.POSITIVE_INFINITY
                        : from.distanceTo(entityHit.getLocation());
        if (!Double.isFinite(worldDistance)
                && !Double.isFinite(entityDistance)) {
            return Collision.miss();
        }
        double closest = Math.min(worldDistance, entityDistance);
        CollisionKind worldKind = CollisionKind.BLOCK;
        if (worldHit instanceof BlockHitResult blockHit
                && !level.getFluidState(blockHit.getBlockPos()).isEmpty()) {
            worldKind = CollisionKind.FLUID;
        }
        return Collision.hit(
                entityDistance < worldDistance
                        ? CollisionKind.ENTITY
                        : worldKind,
                Math.clamp(closest / total, 0.0, 1.0)
        );
    }

    private static ProjectileParameters projectileParameters(
            LocalPlayer player
    ) {
        if (player.isUsingItem()) {
            return projectileParameters(
                    player.getUseItem(),
                    player.getTicksUsingItem(),
                    true
            );
        }
        ProjectileParameters main = projectileParameters(
                player.getMainHandItem(),
                0,
                false
        );
        return main != null
                ? main
                : projectileParameters(
                        player.getOffhandItem(),
                        0,
                        false
                );
    }

    private static ProjectileParameters projectileParameters(
            ItemStack stack,
            int useTicks,
            boolean activelyUsing
    ) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item == Items.BOW) {
            return TrajectoryDecisionEngine26.bowParameters(
                    BowItem.getPowerForTime(
                            activelyUsing
                                    ? useTicks
                                    : BowItem.MAX_DRAW_DURATION
                    )
            );
        }
        ProjectileType type;
        if (item == Items.CROSSBOW) {
            if (!CrossbowItem.isCharged(stack)) {
                return null;
            }
            ChargedProjectiles charged = stack.getOrDefault(
                    DataComponents.CHARGED_PROJECTILES,
                    ChargedProjectiles.EMPTY
            );
            if (charged.isEmpty()) {
                return null;
            }
            type = charged.contains(Items.FIREWORK_ROCKET)
                    ? ProjectileType.CROSSBOW_FIREWORK
                    : ProjectileType.CROSSBOW_ARROW;
        } else if (item == Items.TRIDENT) {
            type = ProjectileType.TRIDENT;
        } else if (item == Items.SNOWBALL) {
            type = ProjectileType.SNOWBALL;
        } else if (item == Items.EGG) {
            type = ProjectileType.EGG;
        } else if (item == Items.ENDER_PEARL) {
            type = ProjectileType.ENDER_PEARL;
        } else if (item == Items.SPLASH_POTION) {
            type = ProjectileType.SPLASH_POTION;
        } else if (item == Items.LINGERING_POTION) {
            type = ProjectileType.LINGERING_POTION;
        } else if (item == Items.EXPERIENCE_BOTTLE) {
            type = ProjectileType.EXPERIENCE_BOTTLE;
        } else if (item == Items.WIND_CHARGE) {
            type = ProjectileType.WIND_CHARGE;
        } else {
            return null;
        }
        return TrajectoryDecisionEngine26.parameters(type);
    }

    private Iterable<WorldOverlayScanEngine26.Candidate<BlockKey>>
            blockCandidates(BlockPos origin, int range, int count) {
        return nearFirstVolumeCandidates(
                origin,
                range,
                range,
                count,
                true
        );
    }

    private Iterable<WorldOverlayScanEngine26.Candidate<BlockKey>>
            holeCandidates(BlockPos origin, int range, int count) {
        return nearFirstVolumeCandidates(
                origin,
                range,
                Math.min(range, HOLE_VERTICAL_SCAN_RANGE),
                count,
                false
        );
    }

    private Iterable<WorldOverlayScanEngine26.Candidate<BlockKey>>
            nearFirstVolumeCandidates(
                    BlockPos origin,
                    int horizontalRange,
                    int verticalRange,
                    int count,
                    boolean block
            ) {
        long side = horizontalRange * 2L + 1L;
        long height = verticalRange * 2L + 1L;
        long volume = side * side * height;
        return () -> new Iterator<>() {
            private int emitted;

            @Override
            public boolean hasNext() {
                return emitted < count && volume > 0L;
            }

            @Override
            public WorldOverlayScanEngine26.Candidate<BlockKey> next() {
                long cursor = block ? blockCursor++ : holeCursor++;
                long index = Math.floorMod(cursor, volume);
                Offset offset = nearFirstOffset(
                        index,
                        horizontalRange,
                        verticalRange
                );
                emitted++;
                BlockKey key = new BlockKey(
                        origin.getX() + offset.x(),
                        origin.getY() + offset.y(),
                        origin.getZ() + offset.z()
                );
                return candidate(key);
            }
        };
    }

    /**
     * Maps a dense cursor to increasing Chebyshev shells without allocating
     * the search volume. Within a shell, the observer's Y plane is emitted
     * first, followed by alternating vertical layers. This prevents moving
     * players from repeatedly resetting a far-corner-first scan.
     */
    static Offset nearFirstOffset(
            long index,
            int horizontalRange,
            int verticalRange
    ) {
        if (horizontalRange < 0
                || verticalRange < 0
                || verticalRange > horizontalRange) {
            throw new IllegalArgumentException("invalid volume range");
        }
        long total = cumulativeVolume(
                horizontalRange,
                verticalRange
        );
        if (index < 0L || index >= total) {
            throw new IllegalArgumentException("index outside volume");
        }
        int low = 0;
        int high = horizontalRange;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (index < cumulativeVolume(middle, verticalRange)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        int radius = low;
        if (radius == 0) {
            return new Offset(0, 0, 0);
        }
        long before = cumulativeVolume(radius - 1, verticalRange);
        long within = index - before;
        int ringSize = radius * 8;

        if (radius <= verticalRange) {
            int innerLayers = radius * 2 - 1;
            long sideLayers = (long) ringSize * innerLayers;
            if (within < sideLayers) {
                int layer = (int) (within / ringSize);
                int ringIndex = (int) (within % ringSize);
                Offset ring = squareRingOffset(radius, ringIndex);
                return new Offset(
                        ring.x(),
                        alternatingLayer(layer),
                        ring.z()
                );
            }
            long capIndex = within - sideLayers;
            int side = radius * 2 + 1;
            int faceSize = side * side;
            int face = (int) (capIndex / faceSize);
            int cell = (int) (capIndex % faceSize);
            return new Offset(
                    cell % side - radius,
                    face == 0 ? radius : -radius,
                    cell / side - radius
            );
        }

        int layer = (int) (within / ringSize);
        int ringIndex = (int) (within % ringSize);
        Offset ring = squareRingOffset(radius, ringIndex);
        return new Offset(
                ring.x(),
                alternatingLayer(layer),
                ring.z()
        );
    }

    private static long cumulativeVolume(
            int radius,
            int verticalRange
    ) {
        long side = radius * 2L + 1L;
        long height = Math.min(radius, verticalRange) * 2L + 1L;
        return side * side * height;
    }

    private static int alternatingLayer(int index) {
        if (index == 0) {
            return 0;
        }
        int magnitude = (index + 1) / 2;
        return (index & 1) == 1 ? magnitude : -magnitude;
    }

    private static Offset squareRingOffset(int radius, int index) {
        int side = radius * 2 + 1;
        if (index < side) {
            return new Offset(-radius, 0, index - radius);
        }
        index -= side;
        if (index < side) {
            return new Offset(radius, 0, index - radius);
        }
        index -= side;
        int interior = side - 2;
        if (index < interior) {
            return new Offset(index - radius + 1, 0, -radius);
        }
        index -= interior;
        return new Offset(index - radius + 1, 0, radius);
    }

    private Iterable<WorldOverlayScanEngine26.Candidate<BlockKey>>
            storageCandidates(
                    ClientLevel level,
                    VisualOverlayConfiguration26.StorageEsp settings
            ) {
        List<WorldOverlayScanEngine26.Candidate<BlockKey>> candidates =
                new ArrayList<>(MAX_STORAGE_DISCOVERIES_PER_TICK);
        int chunkRange = (settings.range() + 15) / 16;
        int side = chunkRange * 2 + 1;
        long volume = (long) side * side;
        int chunksVisited = 0;
        int blockEntitiesExamined = 0;
        while (chunksVisited < MAX_STORAGE_CHUNKS_PER_TICK
                && blockEntitiesExamined
                < MAX_STORAGE_DISCOVERIES_PER_TICK
                && volume > 0L) {
            long index = Math.floorMod(storageChunkCursor++, volume);
            Offset offset = nearFirstOffset(index, chunkRange, 0);
            int chunkX = storageOriginChunkX + offset.x();
            int chunkZ = storageOriginChunkZ + offset.z();
            chunksVisited++;
            if (!level.hasChunk(chunkX, chunkZ)) {
                continue;
            }
            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            for (BlockEntity blockEntity :
                    chunk.getBlockEntities().values()) {
                if (blockEntitiesExamined++
                        >= MAX_STORAGE_DISCOVERIES_PER_TICK) {
                    break;
                }
                if (!isStorage(
                        blockEntity.getType(),
                        settings.includeShulkers()
                )) {
                    continue;
                }
                candidates.add(candidate(
                        BlockKey.from(blockEntity.getBlockPos())
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private static WorldOverlayScanEngine26.ProbeResult<Boolean>
            inspectBlock(
                    ClientLevel level,
                    WorldOverlayScanEngine26.Candidate<BlockKey> candidate,
                    Set<Identifier> targets
            ) {
        BlockKey key = candidate.key();
        if (!level.hasChunk(key.x() >> 4, key.z() >> 4)) {
            return WorldOverlayScanEngine26.ProbeResult.defer();
        }
        return targets.contains(BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(key.blockPos()).getBlock()
        ))
                ? WorldOverlayScanEngine26.ProbeResult.hit(Boolean.TRUE)
                : WorldOverlayScanEngine26.ProbeResult.miss();
    }

    private static WorldOverlayScanEngine26.ProbeResult<HoleSafety>
            inspectHole(
                    ClientLevel level,
                    WorldOverlayScanEngine26.Candidate<BlockKey> candidate
            ) {
        BlockKey key = candidate.key();
        if (!holeChunksLoaded(level, key)) {
            return WorldOverlayScanEngine26.ProbeResult.defer();
        }
        HoleSafety safety = classifyHole(level, key.blockPos());
        return safety == null
                ? WorldOverlayScanEngine26.ProbeResult.miss()
                : WorldOverlayScanEngine26.ProbeResult.hit(safety);
    }

    private static boolean holeChunksLoaded(
            ClientLevel level,
            BlockKey key
    ) {
        int chunkX = key.x() >> 4;
        int chunkZ = key.z() >> 4;
        if (!level.hasChunk(chunkX, chunkZ)) {
            return false;
        }
        int localX = key.x() & 15;
        int localZ = key.z() & 15;
        return (localX != 0 || level.hasChunk(chunkX - 1, chunkZ))
                && (localX != 15 || level.hasChunk(chunkX + 1, chunkZ))
                && (localZ != 0 || level.hasChunk(chunkX, chunkZ - 1))
                && (localZ != 15 || level.hasChunk(chunkX, chunkZ + 1));
    }

    private static WorldOverlayScanEngine26.ProbeResult<Boolean>
            inspectStorage(
                    ClientLevel level,
                    WorldOverlayScanEngine26.Candidate<BlockKey> candidate,
                    boolean includeShulkers
            ) {
        BlockKey key = candidate.key();
        if (!level.hasChunk(key.x() >> 4, key.z() >> 4)) {
            return WorldOverlayScanEngine26.ProbeResult.defer();
        }
        BlockEntity blockEntity = level.getBlockEntity(key.blockPos());
        return blockEntity != null
                && isStorage(blockEntity.getType(), includeShulkers)
                ? WorldOverlayScanEngine26.ProbeResult.hit(Boolean.TRUE)
                : WorldOverlayScanEngine26.ProbeResult.miss();
    }

    private static HoleSafety classifyHole(
            ClientLevel level,
            BlockPos position
    ) {
        BlockState feet = level.getBlockState(position);
        BlockState head = level.getBlockState(position.above());
        if (!feet.isAir()
                || !feet.getFluidState().isEmpty()
                || !head.isAir()
                || !head.getFluidState().isEmpty()) {
            return null;
        }
        boolean obsidian = false;
        boolean unsafe = false;
        BlockPos.MutableBlockPos wall = new BlockPos.MutableBlockPos();
        for (int[] offset : HOLE_WALL_OFFSETS) {
            wall.set(
                    position.getX() + offset[0],
                    position.getY() + offset[1],
                    position.getZ() + offset[2]
            );
            BlockState state = level.getBlockState(wall);
            if (!state.isCollisionShapeFullBlock(level, wall)) {
                return null;
            }
            if (state.is(Blocks.BEDROCK)) {
                continue;
            }
            if (state.is(Blocks.OBSIDIAN)
                    || state.is(Blocks.CRYING_OBSIDIAN)) {
                obsidian = true;
            } else {
                unsafe = true;
            }
        }
        if (unsafe) {
            return HoleSafety.UNSAFE;
        }
        return obsidian ? HoleSafety.MIXED : HoleSafety.SAFE;
    }

    private static boolean isStorage(
            BlockEntityType<?> type,
            boolean includeShulkers
    ) {
        return type == BlockEntityTypes.CHEST
                || type == BlockEntityTypes.TRAPPED_CHEST
                || type == BlockEntityTypes.ENDER_CHEST
                || type == BlockEntityTypes.BARREL
                || type == BlockEntityTypes.HOPPER
                || type == BlockEntityTypes.DISPENSER
                || type == BlockEntityTypes.DROPPER
                || type == BlockEntityTypes.FURNACE
                || type == BlockEntityTypes.BLAST_FURNACE
                || type == BlockEntityTypes.SMOKER
                || type == BlockEntityTypes.BREWING_STAND
                || type == BlockEntityTypes.CRAFTER
                || includeShulkers && type == BlockEntityTypes.SHULKER_BOX;
    }

    private void updateBlockOrigin(
            BlockPos player,
            VisualOverlayConfiguration26.BlockEsp settings
    ) {
        if (shouldMoveOrigin(blockOrigin, player, settings.range())) {
            blockOrigin = player.immutable();
            blockCursor = 0L;
            blockScanner.reset();
        }
    }

    private void updateHoleOrigin(
            BlockPos player,
            VisualOverlayConfiguration26.HoleEsp settings
    ) {
        if (shouldMoveOrigin(holeOrigin, player, settings.range())) {
            holeOrigin = player.immutable();
            holeCursor = 0L;
            holeScanner.reset();
        }
    }

    private void updateStorageOrigin(
            LocalPlayer player,
            VisualOverlayConfiguration26.StorageEsp settings
    ) {
        int nextX = player.chunkPosition().x();
        int nextZ = player.chunkPosition().z();
        int threshold = Math.max(1, (settings.range() + 15) / 48);
        if (!storageOriginPresent
                || Math.abs(nextX - storageOriginChunkX) > threshold
                || Math.abs(nextZ - storageOriginChunkZ) > threshold) {
            storageOriginPresent = true;
            storageOriginChunkX = nextX;
            storageOriginChunkZ = nextZ;
            storageChunkCursor = 0L;
            storageScanner.reset();
        }
    }

    private static boolean shouldMoveOrigin(
            BlockPos origin,
            BlockPos player,
            int range
    ) {
        if (origin == null) {
            return true;
        }
        int horizontal = Math.max(
                Math.abs(origin.getX() - player.getX()),
                Math.abs(origin.getZ() - player.getZ())
        );
        return horizontal > Math.max(4, range / 3)
                || Math.abs(origin.getY() - player.getY()) > 8;
    }

    private static Configuration entityConfiguration(
            VisualOverlayConfiguration26 settings,
            boolean thirdPerson
    ) {
        var esp = settings.playerEsp();
        var tracers = settings.tracers();
        var nametags = settings.nametags();
        return new Configuration(
                new OverlayPolicy(
                        esp.enabled(),
                        esp.range(),
                        esp.renderCap(),
                        esp.showFriends(),
                        esp.showSelf() && thirdPerson,
                        true,
                        true,
                        false
                ),
                new OverlayPolicy(
                        tracers.enabled(),
                        tracers.range(),
                        tracers.renderCap(),
                        tracers.showFriends(),
                        tracers.showSelf() && thirdPerson,
                        true,
                        false,
                        false
                ),
                new OverlayPolicy(
                        nametags.enabled(),
                        nametags.range(),
                        nametags.renderCap(),
                        nametags.showFriends(),
                        nametags.showSelf() && thirdPerson,
                        true,
                        true,
                        false
                )
        );
    }

    private static String buildNametag(
            AbstractClientPlayer player,
            double distance,
            VisualOverlayConfiguration26.Nametags settings
    ) {
        StringBuilder result = new StringBuilder(
                player.getGameProfile().name()
        );
        if (settings.showHealth()) {
            result.append(String.format(
                    Locale.ROOT,
                    " %.1f❤",
                    player.getHealth() + player.getAbsorptionAmount()
            ));
        }
        if (settings.showDistance()) {
            result.append(' ')
                    .append(Math.round(distance))
                    .append('m');
        }
        if (settings.showEquipment()) {
            appendEquipment(result, player);
        }
        if (result.length() > MAX_LABEL_CHARACTERS) {
            return result.substring(0, MAX_LABEL_CHARACTERS - 1) + "…";
        }
        return result.toString();
    }

    private static void appendEquipment(
            StringBuilder result,
            AbstractClientPlayer player
    ) {
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
            result.append(started ? " · " : " [");
            started = true;
            String name = stack.getHoverName().getString();
            result.append(
                    name.length() <= 48
                            ? name
                            : name.substring(0, 47) + "…"
            );
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int remaining =
                        stack.getMaxDamage() - stack.getDamageValue();
                result.append(' ')
                        .append(Math.round(
                                remaining * 100.0F / stack.getMaxDamage()
                        ))
                        .append('%');
            }
            if (result.length() >= MAX_LABEL_CHARACTERS) {
                break;
            }
        }
        if (started && result.length() < MAX_LABEL_CHARACTERS) {
            result.append(']');
        }
    }

    private void rebuildScanners(
            VisualOverlayConfiguration26 settings
    ) {
        scanAllocation = allocateScanBudgets(settings);
        rebuildBlockScanner(settings.blockEsp());
        rebuildHoleScanner(settings.holeEsp());
        rebuildStorageScanner(settings.storageEsp());
    }

    private void rebuildBlockScanner(
            VisualOverlayConfiguration26.BlockEsp settings
    ) {
        blockScanner = new WorldOverlayScanEngine26<>(
                scannerConfiguration(
                        scanAllocation.blockProbeBudget(),
                        scanAllocation.blockAdmissionBudget(),
                        settings.renderCap(),
                        settings.range() + 4.0,
                        volumeCacheAge(
                                settings.range(),
                                settings.range(),
                                scanAllocation.blockProbeBudget(),
                                100L
                        )
                )
        );
        blockOrigin = null;
        blockCursor = 0L;
    }

    private void rebuildHoleScanner(
            VisualOverlayConfiguration26.HoleEsp settings
    ) {
        holeScanner = new WorldOverlayScanEngine26<>(
                scannerConfiguration(
                        scanAllocation.holeProbeBudget(),
                        scanAllocation.holeAdmissionBudget(),
                        settings.renderCap(),
                        settings.range() + 4.0,
                        volumeCacheAge(
                                settings.range(),
                                Math.min(
                                        settings.range(),
                                        HOLE_VERTICAL_SCAN_RANGE
                                ),
                                scanAllocation.holeProbeBudget(),
                                40L
                        )
                )
        );
        holeOrigin = null;
        holeCursor = 0L;
    }

    private void rebuildStorageScanner(
            VisualOverlayConfiguration26.StorageEsp settings
    ) {
        storageScanner = new WorldOverlayScanEngine26<>(
                scannerConfiguration(
                        MAX_STORAGE_DISCOVERIES_PER_TICK,
                        MAX_STORAGE_DISCOVERIES_PER_TICK,
                        settings.renderCap(),
                        settings.range() + 16.0,
                        storageCacheAge(settings.range())
                )
        );
        storageOriginPresent = false;
        storageChunkCursor = 0L;
    }

    private static WorldOverlayScanEngine26.Configuration
            scannerConfiguration(
                    int probes,
                    int admissions,
                    int cacheEntries,
                    double range,
                    long age
            ) {
        int safeProbes = Math.max(1, probes);
        int safeAdmissions = Math.max(safeProbes, admissions);
        int pending = Math.max(
                safeProbes,
                Math.min(MAX_SCAN_PENDING, safeAdmissions * 2)
        );
        return new WorldOverlayScanEngine26.Configuration(
                safeProbes,
                safeAdmissions,
                pending,
                Math.max(1, Math.min(
                        VisualOverlayConfiguration26.MAX_WORLD_RENDER_CAP,
                        cacheEntries
                )),
                range,
                age
        );
    }

    private static long volumeCacheAge(
            int horizontalRange,
            int verticalRange,
            int probesPerTick,
            long margin
    ) {
        long volume = cumulativeVolume(
                horizontalRange,
                verticalRange
        );
        long sweep = (volume + probesPerTick - 1L) / probesPerTick;
        return Math.min(
                WorldOverlayScanEngine26.HARD_MAX_CACHE_AGE_TICKS,
                sweep + margin
        );
    }

    private static long storageCacheAge(int range) {
        long chunkRange = (range + 15L) / 16L;
        long side = chunkRange * 2L + 1L;
        long chunks = side * side;
        long sweep = (
                chunks + MAX_STORAGE_CHUNKS_PER_TICK - 1L
        ) / MAX_STORAGE_CHUNKS_PER_TICK;
        return Math.min(
                WorldOverlayScanEngine26.HARD_MAX_CACHE_AGE_TICKS,
                sweep + 40L
        );
    }

    private static ScanAllocation allocateScanBudgets(
            VisualOverlayConfiguration26 settings
    ) {
        int blockRequested = settings.blockEsp().enabled()
                ? settings.blockEsp().scanBudget()
                : 0;
        int holeRequested = settings.holeEsp().enabled()
                ? settings.holeEsp().scanBudget()
                : 0;
        int total = blockRequested + holeRequested;
        int block;
        int hole;
        if (total <= MAX_COMBINED_BLOCK_PROBES_PER_TICK) {
            block = blockRequested;
            hole = holeRequested;
        } else {
            block = (int) (
                    (long) blockRequested
                            * MAX_COMBINED_BLOCK_PROBES_PER_TICK
                            / total
            );
            hole = MAX_COMBINED_BLOCK_PROBES_PER_TICK - block;
            if (blockRequested > 0) {
                block = Math.max(128, block);
            }
            if (holeRequested > 0) {
                hole = Math.max(128, hole);
            }
            if (block + hole > MAX_COMBINED_BLOCK_PROBES_PER_TICK) {
                hole = MAX_COMBINED_BLOCK_PROBES_PER_TICK - block;
            }
        }
        int blockAdmissions =
                Math.min(MAX_SCAN_PENDING, Math.max(1, block) * 2);
        int holeAdmissions =
                Math.min(MAX_SCAN_PENDING, Math.max(1, hole) * 2);
        return new ScanAllocation(
                Math.max(1, block),
                blockAdmissions,
                Math.max(1, hole),
                holeAdmissions
        );
    }

    private static boolean sameBlockScanSettings(
            VisualOverlayConfiguration26.BlockEsp previous,
            VisualOverlayConfiguration26.BlockEsp next
    ) {
        return previous.enabled() == next.enabled()
                && previous.range() == next.range()
                && previous.scanBudget() == next.scanBudget()
                && previous.renderCap() == next.renderCap()
                && previous.targets().equals(next.targets());
    }

    private static boolean sameHoleScanSettings(
            VisualOverlayConfiguration26.HoleEsp previous,
            VisualOverlayConfiguration26.HoleEsp next
    ) {
        return previous.enabled() == next.enabled()
                && previous.range() == next.range()
                && previous.scanBudget() == next.scanBudget()
                && previous.renderCap() == next.renderCap();
    }

    private static boolean sameStorageScanSettings(
            VisualOverlayConfiguration26.StorageEsp previous,
            VisualOverlayConfiguration26.StorageEsp next
    ) {
        return previous.enabled() == next.enabled()
                && previous.range() == next.range()
                && previous.includeShulkers() == next.includeShulkers()
                && previous.renderCap() == next.renderCap();
    }

    private void resetScannersOnly() {
        blockScanner.reset();
        holeScanner.reset();
        storageScanner.reset();
        blockOrigin = null;
        holeOrigin = null;
        storageOriginPresent = false;
        blockCursor = 0L;
        holeCursor = 0L;
        storageChunkCursor = 0L;
    }

    private void resetWorldState() {
        resetScannersOnly();
        lastScannedSession = null;
        lastScannedLevel = null;
        extractedFrame = FrameSnapshot.EMPTY;
        selectedNametagEntityIds = Set.of();
    }

    private void publishFrame(
            LevelExtractionContext context,
            FrameSnapshot frame,
            Set<Integer> nametagEntityIds
    ) {
        FrameSnapshot immutable = Objects.requireNonNull(frame, "frame");
        Set<Integer> immutableNametagIds = Set.copyOf(
                Objects.requireNonNull(
                        nametagEntityIds,
                        "nametagEntityIds"
                )
        );
        extractedFrame = immutable;
        selectedNametagEntityIds = immutableNametagIds;
        fabricRenderState(context.levelState()).setData(
                FRAME_DATA_KEY,
                immutable
        );
    }

    private static FabricRenderState fabricRenderState(Object state) {
        if (state instanceof FabricRenderState fabricState) {
            return fabricState;
        }
        throw new IllegalStateException(
                "Fabric level render-state attachment is unavailable"
        );
    }

    private static Set<Identifier> compileBlockTargets(
            Set<String> identifiers
    ) {
        Set<Identifier> result = new java.util.HashSet<>();
        for (String value : identifiers) {
            Identifier identifier = Identifier.tryParse(value);
            if (identifier != null) {
                result.add(identifier);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean visible(Frustum frustum, AABB box) {
        return frustum == null || frustum.isVisible(box);
    }

    private static double distanceSquared(
            WorldOverlayScanEngine26.Point point,
            Vec3 camera
    ) {
        double dx = point.x() - camera.x;
        double dy = point.y() - camera.y;
        double dz = point.z() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static <V>
            List<WorldOverlayScanEngine26.CacheEntry<BlockKey, V>>
            entriesForCamera(
                    List<WorldOverlayScanEngine26.CacheEntry<BlockKey, V>>
                            nearestToPlayer,
                    Vec3 camera,
                    Vec3 scanObserver
            ) {
        if (camera.distanceToSqr(scanObserver) <= 16.0) {
            return nearestToPlayer;
        }
        List<WorldOverlayScanEngine26.CacheEntry<BlockKey, V>> copy =
                new ArrayList<>(nearestToPlayer);
        copy.sort(java.util.Comparator.comparingDouble(
                entry -> distanceSquared(entry.position(), camera)
        ));
        return copy;
    }

    private static AABB blockBox(BlockKey key, double inflate) {
        return new AABB(key.blockPos()).inflate(inflate);
    }

    private static Box toBox(AABB box, int color, float alpha) {
        return toBox(box, color, alpha, true);
    }

    private static Box toBox(
            AABB box,
            int color,
            float alpha,
            boolean throughWalls
    ) {
        return new Box(
                new Point(box.minX, box.minY, box.minZ),
                new Point(box.maxX, box.maxY, box.maxZ),
                color,
                alpha,
                throughWalls
        );
    }

    private static Point point(Vec3 vector) {
        return new Point(vector.x, vector.y, vector.z);
    }

    private static Point point(Vector3 vector) {
        return new Point(vector.x(), vector.y(), vector.z());
    }

    private static Vector3 vector(Vec3 vector) {
        return new Vector3(vector.x, vector.y, vector.z);
    }

    private static Vec3 vec(Vector3 vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static WorldOverlayScanEngine26.Candidate<BlockKey> candidate(
            BlockKey key
    ) {
        return new WorldOverlayScanEngine26.Candidate<>(
                key,
                WorldOverlayScanEngine26.Point.blockCenter(
                        key.x(),
                        key.y(),
                        key.z()
                )
        );
    }

    private static double square(double value) {
        return value * value;
    }

    public enum HoleSafety {
        SAFE,
        MIXED,
        UNSAFE
    }

    public record BlockKey(int x, int y, int z) {
        public static BlockKey from(BlockPos position) {
            return new BlockKey(
                    position.getX(),
                    position.getY(),
                    position.getZ()
            );
        }

        public BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }
    }

    @FunctionalInterface
    public interface PlayerRelationResolver {
        boolean isFriend(UUID uuid, String name);
    }

    public record BudgetSnapshot(
            int blockProbesPerTick,
            int blockAdmissionsPerTick,
            int holeProbesPerTick,
            int holeAdmissionsPerTick,
            int storageDiscoveriesPerTick,
            int entityCandidatesPerFrame,
            int boxesPerFrame,
            int linesPerFrame,
            int labelsPerFrame
    ) {
        public int combinedBlockProbesPerTick() {
            return blockProbesPerTick + holeProbesPerTick;
        }
    }

    public record ScanDiagnostics(
            int blockPending,
            int blockCached,
            int holePending,
            int holeCached,
            int storagePending,
            int storageCached
    ) {
    }

    private record PlayerFrame(
            AbstractClientPlayer entity,
            Vec3 position,
            AABB box,
            boolean friend,
            double distanceSquared
    ) {
    }

    private record ScanAllocation(
            int blockProbeBudget,
            int blockAdmissionBudget,
            int holeProbeBudget,
            int holeAdmissionBudget
    ) {
    }

    record Offset(int x, int y, int z) {
    }

    private static final class FrameBuilder {
        private final List<Box> boxes = new ArrayList<>();
        private final List<Line> lines = new ArrayList<>();
        private final List<Label> labels = new ArrayList<>();

        private void addBox(Box box) {
            if (boxes.size()
                    < VisualOverlayRenderer26.MAX_BOXES_PER_FRAME) {
                boxes.add(box);
            }
        }

        private void addLine(Line line) {
            if (lines.size()
                    < VisualOverlayRenderer26.MAX_LINES_PER_FRAME) {
                lines.add(line);
            }
        }

        private void addLabel(Label label) {
            if (labels.size()
                    < VisualOverlayRenderer26.MAX_LABELS_PER_FRAME) {
                labels.add(label);
            }
        }

        private void addBoxOutline(
                AABB box,
                int color,
                float width
        ) {
            addBoxOutline(box, color, width, true);
        }

        private void addBoxOutline(
                AABB box,
                int color,
                float width,
                boolean throughWalls
        ) {
            if (lines.size() + 12
                    > VisualOverlayRenderer26.MAX_LINES_PER_FRAME) {
                return;
            }
            Point p000 = new Point(box.minX, box.minY, box.minZ);
            Point p001 = new Point(box.minX, box.minY, box.maxZ);
            Point p010 = new Point(box.minX, box.maxY, box.minZ);
            Point p011 = new Point(box.minX, box.maxY, box.maxZ);
            Point p100 = new Point(box.maxX, box.minY, box.minZ);
            Point p101 = new Point(box.maxX, box.minY, box.maxZ);
            Point p110 = new Point(box.maxX, box.maxY, box.minZ);
            Point p111 = new Point(box.maxX, box.maxY, box.maxZ);
            addLine(new Line(p000, p001, color, width, throughWalls));
            addLine(new Line(p001, p011, color, width, throughWalls));
            addLine(new Line(p011, p010, color, width, throughWalls));
            addLine(new Line(p010, p000, color, width, throughWalls));
            addLine(new Line(p100, p101, color, width, throughWalls));
            addLine(new Line(p101, p111, color, width, throughWalls));
            addLine(new Line(p111, p110, color, width, throughWalls));
            addLine(new Line(p110, p100, color, width, throughWalls));
            addLine(new Line(p000, p100, color, width, throughWalls));
            addLine(new Line(p001, p101, color, width, throughWalls));
            addLine(new Line(p010, p110, color, width, throughWalls));
            addLine(new Line(p011, p111, color, width, throughWalls));
        }

        private FrameSnapshot build() {
            return new FrameSnapshot(boxes, lines, labels);
        }
    }
}
