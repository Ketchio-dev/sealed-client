package dev.b2tclient.v26.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;

/**
 * Minecraft 26.2 submit-phase renderer for immutable visual snapshots.
 *
 * <p>This class never reads a level, entity, chunk, block state, or inventory.
 * Minecraft 26.2 separates extraction from submission; callers must prepare a
 * {@link FrameSnapshot} during extraction and pass only that value here from
 * {@code LevelRenderEvents.COLLECT_SUBMITS}. Through-wall geometry uses the
 * vanilla see-through text-background pipeline because it has the exact
 * position/color QUADS format, translucent blending, and no depth-stencil
 * state. Depth-tested trajectory lines remain in the vanilla translucent line
 * pipeline. Geometry is bounded and batched by depth policy.</p>
 */
public final class VisualOverlayRenderer26 {
    public static final int MAX_BOXES_PER_FRAME = 8_192;
    public static final int MAX_LINES_PER_FRAME = 16_384;
    public static final int MAX_LABELS_PER_FRAME = 256;
    private static final int FULL_BRIGHT = 0x00F000F0;

    /**
     * Adds render nodes only. No world-state access is permitted here.
     */
    public void submit(
            LevelRenderContext context,
            FrameSnapshot snapshot
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.empty()) {
            return;
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        if (camera == null) {
            return;
        }
        PoseStack poses = context.poseStack();
        OrderedSubmitNodeCollector collector =
                context.submitNodeCollector();

        if (!snapshot.filledBoxes().isEmpty()) {
            submitBoxes(
                    collector,
                    poses,
                    snapshot.filledBoxes(),
                    camera
            );
        }

        if (!snapshot.lines().isEmpty()) {
            submitLines(collector, poses, snapshot.lines(), camera);
        }

        for (Label label : snapshot.labels()) {
            submitLabel(
                    collector,
                    poses,
                    context,
                    label,
                    camera
            );
        }
    }

    private static void submitBoxes(
            OrderedSubmitNodeCollector collector,
            PoseStack poses,
            List<Box> boxes,
            Vec3 camera
    ) {
        boolean hasThroughWalls = false;
        boolean hasDepthTested = false;
        for (Box box : boxes) {
            hasThroughWalls |= box.throughWalls();
            hasDepthTested |= !box.throughWalls();
        }
        if (hasThroughWalls) {
            collector.submitCustomGeometry(
                    poses,
                    RenderTypes.textBackgroundSeeThrough(),
                    (pose, consumer) -> {
                        for (Box box : boxes) {
                            if (box.throughWalls()) {
                                addFilledBox(
                                        pose,
                                        consumer,
                                        box,
                                        camera
                                );
                            }
                        }
                    }
            );
        }
        if (hasDepthTested) {
            collector.submitCustomGeometry(
                    poses,
                    RenderTypes.debugFilledBox(),
                    (pose, consumer) -> {
                        for (Box box : boxes) {
                            if (!box.throughWalls()) {
                                addFilledBox(
                                        pose,
                                        consumer,
                                        box,
                                        camera
                                );
                            }
                        }
                    }
            );
        }
    }

    private static void submitLines(
            OrderedSubmitNodeCollector collector,
            PoseStack poses,
            List<Line> lines,
            Vec3 camera
    ) {
        boolean hasThroughWalls = false;
        boolean hasDepthTested = false;
        for (Line line : lines) {
            hasThroughWalls |= line.throughWalls();
            hasDepthTested |= !line.throughWalls();
        }
        if (hasThroughWalls) {
            collector.submitCustomGeometry(
                    poses,
                    RenderTypes.textBackgroundSeeThrough(),
                    (pose, consumer) -> {
                        for (Line line : lines) {
                            if (line.throughWalls()) {
                                addThroughWallLine(
                                        pose,
                                        consumer,
                                        line,
                                        camera
                                );
                            }
                        }
                    }
            );
        }
        if (hasDepthTested) {
            collector.submitCustomGeometry(
                    poses,
                    RenderTypes.linesTranslucent(),
                    (pose, consumer) -> {
                        for (Line line : lines) {
                            if (!line.throughWalls()) {
                                addDepthTestedLine(
                                        pose,
                                        consumer,
                                        line,
                                        camera
                                );
                            }
                        }
                    }
            );
        }
    }

    private static void submitLabel(
            OrderedSubmitNodeCollector collector,
            PoseStack poses,
            LevelRenderContext context,
            Label label,
            Vec3 camera
    ) {
        poses.pushPose();
        poses.translate(
                label.position().x() - camera.x,
                label.position().y() - camera.y,
                label.position().z() - camera.z
        );
        poses.mulPose(
                context.levelState().cameraRenderState.orientation
        );
        float scale = 0.025F * label.scale();
        poses.scale(scale, -scale, scale);
        int width = label.estimatedTextWidth();
        collector.submitText(
                poses,
                -width / 2.0F,
                0.0F,
                label.text().getVisualOrderText(),
                false,
                Font.DisplayMode.SEE_THROUGH,
                label.color(),
                label.backgroundColor(),
                FULL_BRIGHT,
                0
        );
        poses.popPose();
    }

    private static void addFilledBox(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            Box box,
            Vec3 camera
    ) {
        float x0 = relative(box.minimum().x(), camera.x);
        float y0 = relative(box.minimum().y(), camera.y);
        float z0 = relative(box.minimum().z(), camera.z);
        float x1 = relative(box.maximum().x(), camera.x);
        float y1 = relative(box.maximum().y(), camera.y);
        float z1 = relative(box.maximum().z(), camera.z);
        int color = scaleAlpha(box.color(), box.alphaScale());

        quad(pose, consumer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, color);
        quad(pose, consumer, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, color);
        quad(pose, consumer, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, color);
        quad(pose, consumer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, color);
        quad(pose, consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, color);
        quad(pose, consumer, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, color);
    }

    private static void quad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz,
            float dx,
            float dy,
            float dz,
            int color
    ) {
        vertex(pose, consumer, ax, ay, az, color);
        vertex(pose, consumer, bx, by, bz, color);
        vertex(pose, consumer, cx, cy, cz, color);
        vertex(pose, consumer, dx, dy, dz, color);
    }

    private static void vertex(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x,
            float y,
            float z,
            int color
    ) {
        consumer.addVertex(pose, x, y, z).setColor(color);
    }

    /**
     * Emits a camera-facing quad through the no-depth position/color
     * pipeline. Its world width is distance-scaled to approximate the
     * configured pixel width while remaining strictly bounded.
     */
    private static void addThroughWallLine(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            Line line,
            Vec3 camera
    ) {
        double dx = line.to().x() - line.from().x();
        double dy = line.to().y() - line.from().y();
        double dz = line.to().z() - line.from().z();
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0E-10) {
            return;
        }

        double mx = (line.from().x() + line.to().x()) * 0.5;
        double my = (line.from().y() + line.to().y()) * 0.5;
        double mz = (line.from().z() + line.to().z()) * 0.5;
        double vx = camera.x - mx;
        double vy = camera.y - my;
        double vz = camera.z - mz;

        // view x direction produces a side whose winding faces the camera.
        double sx = vy * dz - vz * dy;
        double sy = vz * dx - vx * dz;
        double sz = vx * dy - vy * dx;
        double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (!Double.isFinite(sideLength) || sideLength < 1.0E-8) {
            sx = -dz;
            sy = 0.0;
            sz = dx;
            sideLength = Math.sqrt(sx * sx + sz * sz);
            if (!Double.isFinite(sideLength) || sideLength < 1.0E-8) {
                sx = 1.0;
                sy = 0.0;
                sz = 0.0;
                sideLength = 1.0;
            }
        }
        double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double halfWidth = Math.clamp(
                line.width() * Math.max(1.0, distance) * 0.00045,
                0.0025,
                0.09
        );
        sx = sx / sideLength * halfWidth;
        sy = sy / sideLength * halfWidth;
        sz = sz / sideLength * halfWidth;

        vertex(
                pose,
                consumer,
                relative(line.from().x() - sx, camera.x),
                relative(line.from().y() - sy, camera.y),
                relative(line.from().z() - sz, camera.z),
                line.color()
        );
        vertex(
                pose,
                consumer,
                relative(line.to().x() - sx, camera.x),
                relative(line.to().y() - sy, camera.y),
                relative(line.to().z() - sz, camera.z),
                line.color()
        );
        vertex(
                pose,
                consumer,
                relative(line.to().x() + sx, camera.x),
                relative(line.to().y() + sy, camera.y),
                relative(line.to().z() + sz, camera.z),
                line.color()
        );
        vertex(
                pose,
                consumer,
                relative(line.from().x() + sx, camera.x),
                relative(line.from().y() + sy, camera.y),
                relative(line.from().z() + sz, camera.z),
                line.color()
        );
    }

    private static void addDepthTestedLine(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            Line line,
            Vec3 camera
    ) {
        double dx = line.to().x() - line.from().x();
        double dy = line.to().y() - line.from().y();
        double dz = line.to().z() - line.from().z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length) || length < 1.0E-5) {
            return;
        }

        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        lineVertex(
                pose,
                consumer,
                line.from(),
                camera,
                line.color(),
                nx,
                ny,
                nz,
                line.width()
        );
        lineVertex(
                pose,
                consumer,
                line.to(),
                camera,
                line.color(),
                nx,
                ny,
                nz,
                line.width()
        );
    }

    private static void lineVertex(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            Point point,
            Vec3 camera,
            int color,
            float nx,
            float ny,
            float nz,
            float width
    ) {
        consumer.addVertex(
                        pose,
                        relative(point.x(), camera.x),
                        relative(point.y(), camera.y),
                        relative(point.z(), camera.z)
                )
                .setColor(color)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(width);
    }

    private static float relative(double coordinate, double camera) {
        return (float) (coordinate - camera);
    }

    private static int scaleAlpha(int argb, float scale) {
        int alpha = argb >>> 24 & 0xFF;
        int scaled = Math.clamp(Math.round(alpha * scale), 0, 255);
        return argb & 0x00FFFFFF | scaled << 24;
    }

    public record FrameSnapshot(
            List<Box> filledBoxes,
            List<Line> lines,
            List<Label> labels
    ) {
        public static final FrameSnapshot EMPTY =
                new FrameSnapshot(List.of(), List.of(), List.of());

        public FrameSnapshot {
            filledBoxes = boundedCopy(
                    filledBoxes,
                    MAX_BOXES_PER_FRAME,
                    "filledBoxes"
            );
            lines = boundedCopy(
                    lines,
                    MAX_LINES_PER_FRAME,
                    "lines"
            );
            labels = boundedCopy(
                    labels,
                    MAX_LABELS_PER_FRAME,
                    "labels"
            );
        }

        public boolean empty() {
            return filledBoxes.isEmpty()
                    && lines.isEmpty()
                    && labels.isEmpty();
        }
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException(
                        "point coordinates must be finite"
                );
            }
        }
    }

    public record Box(
            Point minimum,
            Point maximum,
            int color,
            float alphaScale,
            boolean throughWalls
    ) {
        public Box(
                Point minimum,
                Point maximum,
                int color,
                float alphaScale
        ) {
            this(minimum, maximum, color, alphaScale, true);
        }

        public Box {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            if (minimum.x() > maximum.x()
                    || minimum.y() > maximum.y()
                    || minimum.z() > maximum.z()) {
                throw new IllegalArgumentException(
                        "box minimum must not exceed maximum"
                );
            }
            if (!Float.isFinite(alphaScale)
                    || alphaScale < 0.0F
                    || alphaScale > 1.0F) {
                throw new IllegalArgumentException(
                        "alphaScale must be in [0, 1]"
                );
            }
        }
    }

    public record Line(
            Point from,
            Point to,
            int color,
            float width,
            boolean throughWalls
    ) {
        public Line(Point from, Point to, int color, float width) {
            this(from, to, color, width, true);
        }

        public Line {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!Float.isFinite(width) || width < 0.5F || width > 4.0F) {
                throw new IllegalArgumentException(
                        "line width must be in [0.5, 4.0]"
                );
            }
        }
    }

    public record Label(
            Point position,
            Component text,
            int estimatedTextWidth,
            int color,
            int backgroundColor,
            float scale
    ) {
        public Label {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(text, "text");
            if (estimatedTextWidth < 0 || estimatedTextWidth > 16_384) {
                throw new IllegalArgumentException(
                        "estimatedTextWidth is outside the safe range"
                );
            }
            if (!Float.isFinite(scale) || scale < 0.5F || scale > 2.5F) {
                throw new IllegalArgumentException(
                        "label scale must be in [0.5, 2.5]"
                );
            }
        }
    }

    private static <T> List<T> boundedCopy(
            List<T> input,
            int maximum,
            String name
    ) {
        List<T> required = List.copyOf(
                Objects.requireNonNull(input, name)
        );
        if (required.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " exceeds the hard frame budget " + maximum
            );
        }
        return required;
    }
}
