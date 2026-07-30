package dev.b2tclient.v26.world;

import dev.b2tclient.v26.visual.VisualOverlayRenderer26;
import dev.b2tclient.v26.visual.VisualOverlayRenderer26.Box;
import dev.b2tclient.v26.visual.VisualOverlayRenderer26.FrameSnapshot;
import dev.b2tclient.v26.visual.VisualOverlayRenderer26.Label;
import dev.b2tclient.v26.visual.VisualOverlayRenderer26.Line;
import dev.b2tclient.v26.visual.VisualOverlayRenderer26.Point;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Independent 26.2 extraction/submit renderer for world tracker snapshots.
 *
 * <p>Extraction reads only the immutable
 * {@link WorldTrackerService26.RenderSnapshot}; the submit callback reads no
 * level, entity, chunk, or tracker engine state.</p>
 */
public final class WorldTrackerRenderService26 {
    private static final int MAX_TOTAL_LABELS = 256;
    private static final int OUTLINE_EDGE_COUNT = 12;

    private final WorldTrackerService26 trackers;
    private final VisualOverlayRenderer26 renderer =
            new VisualOverlayRenderer26();
    private volatile Configuration configuration = Configuration.defaults();
    private volatile FrameSnapshot extractedFrame = FrameSnapshot.EMPTY;
    private boolean initialized;

    public WorldTrackerRenderService26(WorldTrackerService26 trackers) {
        this.trackers = Objects.requireNonNull(trackers, "trackers");
    }

    /**
     * Registers 26.2 extraction and submit callbacks exactly once.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(
                context -> renderer.submit(context, extractedFrame)
        );
    }

    public boolean initialized() {
        return initialized;
    }

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration nextConfiguration) {
        configuration = Objects.requireNonNull(
                nextConfiguration,
                "nextConfiguration"
        );
    }

    public FrameSnapshot extractedFrame() {
        return extractedFrame;
    }

    public void reset() {
        extractedFrame = FrameSnapshot.EMPTY;
    }

    private void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        WorldTrackerService26.RenderSnapshot snapshot =
                trackers.renderSnapshot();
        if (!snapshot.activeSession()
                || client.level == null
                || client.player == null
                || client.getConnection() == null
                || context.level() != client.level
                || !snapshot.dimension().equals(
                        context.level().dimension().identifier().toString()
                )) {
            extractedFrame = FrameSnapshot.EMPTY;
            return;
        }

        extractedFrame = buildFrame(
                snapshot,
                configuration,
                new ExtractionView(context, client)
        );
    }

    /**
     * Pure geometry assembly boundary used by deterministic adapter tests.
     */
    static FrameSnapshot buildFrame(
            WorldTrackerService26.RenderSnapshot snapshot,
            Configuration configuration,
            RenderView view
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(view, "view");
        if (!snapshot.activeSession()
                || !snapshot.dimension().equals(view.dimension())) {
            return FrameSnapshot.EMPTY;
        }

        List<Box> boxes = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        WorldTrackerService26.ModuleState modules = snapshot.modules();

        if (modules.newChunks()) {
            appendNewChunks(
                    snapshot.newChunks(),
                    configuration.newChunks(),
                    view,
                    boxes,
                    lines
            );
        }
        if (modules.logoutSpots()) {
            appendLogoutSpots(
                    snapshot.logoutSpots(),
                    configuration.logoutSpots(),
                    view,
                    boxes,
                    lines,
                    labels
            );
        }
        if (modules.stashFinder()) {
            appendStashes(
                    snapshot.stashes(),
                    configuration.stashFinder(),
                    view,
                    boxes,
                    lines,
                    labels
            );
        }
        return new FrameSnapshot(boxes, lines, labels);
    }

    private static void appendNewChunks(
            List<NewChunksDecisionEngine26.ChunkSnapshot> chunks,
            NewChunksRender settings,
            RenderView view,
            List<Box> boxes,
            List<Line> lines
    ) {
        int rendered = 0;
        double maximumDistanceSquared = square(settings.maximumDistanceBlocks());
        for (NewChunksDecisionEngine26.ChunkSnapshot chunk : chunks) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            double minimumX = chunk.minimumBlockX();
            double minimumZ = chunk.minimumBlockZ();
            double centerX = minimumX + 8.0;
            double centerZ = minimumZ + 8.0;
            if (horizontalDistanceSquared(
                    centerX,
                    centerZ,
                    view.cameraX(),
                    view.cameraZ()
            ) > maximumDistanceSquared) {
                continue;
            }
            AABB bounds = new AABB(
                    minimumX,
                    view.minimumY() + 0.02,
                    minimumZ,
                    minimumX + 16.0,
                    view.minimumY() + 0.16,
                    minimumZ + 16.0
            );
            if (!view.visible(bounds)) {
                continue;
            }
            if (settings.filled()) {
                boxes.add(box(bounds, settings.argb(), 0.25F));
            }
            addOutline(lines, bounds, settings.argb(), settings.lineWidth());
            rendered++;
        }
    }

    private static void appendLogoutSpots(
            List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> spots,
            LogoutSpotsRender settings,
            RenderView view,
            List<Box> boxes,
            List<Line> lines,
            List<Label> labels
    ) {
        int rendered = 0;
        double maximumDistanceSquared = square(settings.maximumDistanceBlocks());
        Point camera = new Point(
                view.cameraX(),
                view.cameraY(),
                view.cameraZ()
        );
        for (LogoutSpotsDecisionEngine26.LogoutSpotSnapshot spot : spots) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            double distanceSquared = distanceSquared(
                    spot.x(),
                    spot.y(),
                    spot.z(),
                    view.cameraX(),
                    view.cameraY(),
                    view.cameraZ()
            );
            if (distanceSquared > maximumDistanceSquared) {
                continue;
            }

            AABB bounds = new AABB(
                    spot.x() - 0.3,
                    spot.y(),
                    spot.z() - 0.3,
                    spot.x() + 0.3,
                    spot.y() + 1.8,
                    spot.z() + 0.3
            );
            if (view.visible(bounds)) {
                boxes.add(box(bounds, settings.argb(), 0.18F));
                addOutline(
                        lines,
                        bounds,
                        settings.argb(),
                        settings.lineWidth()
                );
            }
            if (settings.tracer()) {
                lines.add(new Line(
                        camera,
                        new Point(spot.x(), spot.y() + 0.9, spot.z()),
                        settings.argb(),
                        settings.lineWidth()
                ));
            }
            if (settings.showName() && labels.size() < MAX_TOTAL_LABELS) {
                String text = spot.playerName() + " logged out";
                labels.add(label(
                        view,
                        text,
                        spot.x(),
                        spot.y() + 2.1,
                        spot.z(),
                        opaque(settings.argb()),
                        settings.labelScale()
                ));
            }
            rendered++;
        }
    }

    private static void appendStashes(
            List<StashFinderDecisionEngine26.StashSnapshot> stashes,
            StashRender settings,
            RenderView view,
            List<Box> boxes,
            List<Line> lines,
            List<Label> labels
    ) {
        int rendered = 0;
        double maximumDistanceSquared = square(settings.maximumDistanceBlocks());
        for (StashFinderDecisionEngine26.StashSnapshot stash : stashes) {
            if (rendered >= settings.renderCap()) {
                break;
            }
            if (!view.dimension().equals(stash.dimension())
                    || distanceSquared(
                    stash.centerX(),
                    stash.centerY(),
                    stash.centerZ(),
                    view.cameraX(),
                    view.cameraY(),
                    view.cameraZ()
            ) > maximumDistanceSquared) {
                continue;
            }
            AABB bounds = new AABB(
                    stash.minimumChunkX() * 16.0,
                    stash.centerY() - 0.5,
                    stash.minimumChunkZ() * 16.0,
                    (stash.maximumChunkX() + 1) * 16.0,
                    stash.centerY() + 0.5,
                    (stash.maximumChunkZ() + 1) * 16.0
            );
            if (!view.visible(bounds)) {
                continue;
            }
            if (settings.filled()) {
                boxes.add(box(bounds, settings.argb(), 0.12F));
            }
            addOutline(lines, bounds, settings.argb(), settings.lineWidth());
            if (settings.showLabel() && labels.size() < MAX_TOTAL_LABELS) {
                String text = String.format(
                        Locale.ROOT,
                        "Stash \u00b7 %d containers",
                        stash.containerCount()
                );
                labels.add(label(
                        view,
                        text,
                        stash.centerX(),
                        stash.centerY() + 1.2,
                        stash.centerZ(),
                        opaque(settings.argb()),
                        settings.labelScale()
                ));
            }
            rendered++;
        }
    }

    private static Box box(AABB bounds, int argb, float alphaScale) {
        return new Box(
                point(bounds.minX, bounds.minY, bounds.minZ),
                point(bounds.maxX, bounds.maxY, bounds.maxZ),
                argb,
                alphaScale
        );
    }

    private static Label label(
            RenderView view,
            String text,
            double x,
            double y,
            double z,
            int color,
            float scale
    ) {
        return new Label(
                point(x, y, z),
                Component.literal(text),
                view.textWidth(text),
                color,
                0x99000000,
                scale
        );
    }

    private static void addOutline(
            List<Line> lines,
            AABB bounds,
            int color,
            float width
    ) {
        if (lines.size() + OUTLINE_EDGE_COUNT
                > VisualOverlayRenderer26.MAX_LINES_PER_FRAME) {
            return;
        }
        Point p000 = point(bounds.minX, bounds.minY, bounds.minZ);
        Point p001 = point(bounds.minX, bounds.minY, bounds.maxZ);
        Point p010 = point(bounds.minX, bounds.maxY, bounds.minZ);
        Point p011 = point(bounds.minX, bounds.maxY, bounds.maxZ);
        Point p100 = point(bounds.maxX, bounds.minY, bounds.minZ);
        Point p101 = point(bounds.maxX, bounds.minY, bounds.maxZ);
        Point p110 = point(bounds.maxX, bounds.maxY, bounds.minZ);
        Point p111 = point(bounds.maxX, bounds.maxY, bounds.maxZ);

        edge(lines, p000, p001, color, width);
        edge(lines, p000, p010, color, width);
        edge(lines, p000, p100, color, width);
        edge(lines, p111, p110, color, width);
        edge(lines, p111, p101, color, width);
        edge(lines, p111, p011, color, width);
        edge(lines, p001, p011, color, width);
        edge(lines, p001, p101, color, width);
        edge(lines, p010, p011, color, width);
        edge(lines, p010, p110, color, width);
        edge(lines, p100, p101, color, width);
        edge(lines, p100, p110, color, width);
    }

    private static void edge(
            List<Line> lines,
            Point from,
            Point to,
            int color,
            float width
    ) {
        lines.add(new Line(from, to, color, width));
    }

    private static Point point(double x, double y, double z) {
        return new Point(x, y, z);
    }

    private static double square(double value) {
        return value * value;
    }

    private static double distanceSquared(
            double x,
            double y,
            double z,
            double otherX,
            double otherY,
            double otherZ
    ) {
        double dx = x - otherX;
        double dy = y - otherY;
        double dz = z - otherZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSquared(
            double x,
            double z,
            double otherX,
            double otherZ
    ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz;
    }

    private static int opaque(int argb) {
        return argb | 0xFF000000;
    }

    interface RenderView {
        String dimension();

        int minimumY();

        double cameraX();

        double cameraY();

        double cameraZ();

        boolean visible(AABB bounds);

        int textWidth(String text);
    }

    public record Configuration(
            NewChunksRender newChunks,
            LogoutSpotsRender logoutSpots,
            StashRender stashFinder
    ) {
        public Configuration {
            Objects.requireNonNull(newChunks, "newChunks");
            Objects.requireNonNull(logoutSpots, "logoutSpots");
            Objects.requireNonNull(stashFinder, "stashFinder");
        }

        public static Configuration defaults() {
            return new Configuration(
                    new NewChunksRender(
                            0x9900D7FF,
                            true,
                            1.5F,
                            1_024.0,
                            128
                    ),
                    new LogoutSpotsRender(
                            0xCCFF6B6B,
                            true,
                            false,
                            1.5F,
                            512.0,
                            128,
                            1.0F
                    ),
                    new StashRender(
                            0xCCFFAA00,
                            true,
                            true,
                            1.5F,
                            512.0,
                            128,
                            1.0F
                    )
            );
        }
    }

    public record NewChunksRender(
            int argb,
            boolean filled,
            float lineWidth,
            double maximumDistanceBlocks,
            int renderCap
    ) {
        public NewChunksRender {
            lineWidth = normalizeLineWidth(lineWidth);
            validateDistanceAndCap(maximumDistanceBlocks, renderCap);
        }
    }

    public record LogoutSpotsRender(
            int argb,
            boolean showName,
            boolean tracer,
            float lineWidth,
            double maximumDistanceBlocks,
            int renderCap,
            float labelScale
    ) {
        public LogoutSpotsRender {
            lineWidth = normalizeLineWidth(lineWidth);
            validateDistanceAndCap(maximumDistanceBlocks, renderCap);
            validateLabelScale(labelScale);
        }
    }

    public record StashRender(
            int argb,
            boolean filled,
            boolean showLabel,
            float lineWidth,
            double maximumDistanceBlocks,
            int renderCap,
            float labelScale
    ) {
        public StashRender {
            lineWidth = normalizeLineWidth(lineWidth);
            validateDistanceAndCap(maximumDistanceBlocks, renderCap);
            validateLabelScale(labelScale);
        }
    }

    private static float normalizeLineWidth(float width) {
        if (!Float.isFinite(width) || width < 0.5F || width > 5.0F) {
            throw new IllegalArgumentException(
                    "lineWidth must be in [0.5, 5.0]"
            );
        }
        // The persisted platform setting allows 5.0 while the hardened
        // overlay backend intentionally caps submitted lines at 4.0.
        return Math.min(width, 4.0F);
    }

    private static void validateDistanceAndCap(
            double distance,
            int renderCap
    ) {
        if (!Double.isFinite(distance)
                || distance < 16.0
                || distance > 4_096.0) {
            throw new IllegalArgumentException(
                    "maximumDistanceBlocks must be in [16, 4096]"
            );
        }
        if (renderCap < 1 || renderCap > 128) {
            throw new IllegalArgumentException(
                    "renderCap must be in [1, 128]"
            );
        }
    }

    private static void validateLabelScale(float scale) {
        if (!Float.isFinite(scale) || scale < 0.5F || scale > 2.5F) {
            throw new IllegalArgumentException(
                    "labelScale must be in [0.5, 2.5]"
            );
        }
    }

    private record ExtractionView(
            LevelExtractionContext context,
            Minecraft client
    ) implements RenderView {
        private ExtractionView {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(client, "client");
        }

        @Override
        public String dimension() {
            return context.level().dimension().identifier().toString();
        }

        @Override
        public int minimumY() {
            return context.level().getMinY();
        }

        @Override
        public double cameraX() {
            return cameraPosition().x;
        }

        @Override
        public double cameraY() {
            return cameraPosition().y;
        }

        @Override
        public double cameraZ() {
            return cameraPosition().z;
        }

        @Override
        public boolean visible(AABB bounds) {
            Frustum frustum =
                    context.levelState().cameraRenderState.cullFrustum;
            return frustum == null || frustum.isVisible(bounds);
        }

        @Override
        public int textWidth(String text) {
            return client.font.width(text);
        }

        private Vec3 cameraPosition() {
            Camera camera = context.camera();
            return camera.position();
        }
    }
}
