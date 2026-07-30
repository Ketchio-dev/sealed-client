package dev.b2tclient.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class WorldRenderPrimitives {
    private static final RenderType THROUGH_WALLS_FILL = new RenderType(
            "b2t_through_walls_fill",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLE_STRIP,
            1_536,
            false,
            true,
            () -> {
                RenderSystem.setShader(CoreShaders.POSITION_COLOR);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
                RenderSystem.clearShader();
            }
    ) {
    };
    private static final RenderType THROUGH_WALLS_LINES = new RenderType(
            "b2t_through_walls_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1_536,
            false,
            false,
            () -> {
                RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.lineWidth(1.5F);
            },
            () -> {
                RenderSystem.lineWidth(1.0F);
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
                RenderSystem.clearShader();
            }
    ) {
    };

    private WorldRenderPrimitives() {
    }

    static void filledBox(
            PoseStack poses,
            MultiBufferSource consumers,
            AABB cameraRelativeBox,
            int argb,
            float alphaScale
    ) {
        float[] color = color(argb, alphaScale);
        ShapeRenderer.addChainedFilledBoxVertices(
                poses,
                consumers.getBuffer(THROUGH_WALLS_FILL),
                cameraRelativeBox.minX,
                cameraRelativeBox.minY,
                cameraRelativeBox.minZ,
                cameraRelativeBox.maxX,
                cameraRelativeBox.maxY,
                cameraRelativeBox.maxZ,
                color[0],
                color[1],
                color[2],
                color[3]
        );
    }

    static void outlinedBox(
            PoseStack poses,
            MultiBufferSource consumers,
            AABB cameraRelativeBox,
            int argb
    ) {
        float[] color = color(argb, 1.0F);
        ShapeRenderer.renderLineBox(
                poses,
                consumers.getBuffer(THROUGH_WALLS_LINES),
                cameraRelativeBox,
                color[0],
                color[1],
                color[2],
                color[3]
        );
    }

    static void line(
            PoseStack poses,
            MultiBufferSource consumers,
            Vec3 cameraPosition,
            Vec3 from,
            Vec3 to,
            int argb
    ) {
        Vec3 start = from.subtract(cameraPosition);
        Vec3 end = to.subtract(cameraPosition);
        Vec3 direction = end.subtract(start);
        float length = (float) direction.length();
        if (length < 1.0E-4F) {
            return;
        }

        float[] color = color(argb, 1.0F);
        float nx = (float) (direction.x / length);
        float ny = (float) (direction.y / length);
        float nz = (float) (direction.z / length);
        VertexConsumer consumer = consumers.getBuffer(THROUGH_WALLS_LINES);
        PoseStack.Pose pose = poses.last();
        consumer.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(pose, nx, ny, nz);
    }

    static AABB cameraRelative(AABB worldBox, Vec3 cameraPosition) {
        return worldBox.move(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
    }

    static int opaque(int argb) {
        return argb | 0xFF000000;
    }

    private static float[] color(int argb, float alphaScale) {
        return new float[] {
                (argb >>> 16 & 0xFF) / 255.0F,
                (argb >>> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F,
                Math.min(1.0F, (argb >>> 24 & 0xFF) / 255.0F * alphaScale)
        };
    }
}
