package dev.b2tclient.v26.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Objects;

/**
 * Delegating submit-node collector that changes only model render types and
 * packed colors. Non-model features are forwarded byte-for-byte.
 */
final class ChamsSubmitNodeCollector26 implements SubmitNodeCollector {
    private final SubmitNodeCollector delegate;
    private final int tint;

    ChamsSubmitNodeCollector26(SubmitNodeCollector delegate, int tint) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tint = tint;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new ChamsOrderedSubmitNodeCollector26(
                delegate.order(order),
                tint
        );
    }

    @Override
    public void submitShadow(
            PoseStack poses,
            float opacity,
            List<EntityRenderState.ShadowPiece> pieces
    ) {
        delegate.submitShadow(poses, opacity, pieces);
    }

    @Override
    public void submitNameTag(
            PoseStack poses,
            Vec3 attachment,
            int yOffset,
            Component text,
            boolean discrete,
            int light,
            CameraRenderState camera
    ) {
        delegate.submitNameTag(
                poses,
                attachment,
                yOffset,
                text,
                discrete,
                light,
                camera
        );
    }

    @Override
    public void submitText(
            PoseStack poses,
            float x,
            float y,
            FormattedCharSequence text,
            boolean dropShadow,
            Font.DisplayMode mode,
            int color,
            int backgroundColor,
            int light,
            int outlineColor
    ) {
        delegate.submitText(
                poses,
                x,
                y,
                text,
                dropShadow,
                mode,
                color,
                backgroundColor,
                light,
                outlineColor
        );
    }

    @Override
    public void submitFlame(
            PoseStack poses,
            EntityRenderState state,
            org.joml.Quaternionf rotation
    ) {
        delegate.submitFlame(poses, state, rotation);
    }

    @Override
    public void submitLeash(
            PoseStack poses,
            EntityRenderState.LeashState state
    ) {
        delegate.submitLeash(poses, state);
    }

    @Override
    public <S> void submitModel(
            Model<? super S> model,
            S state,
            PoseStack poses,
            RenderType renderType,
            int light,
            int overlay,
            int color,
            TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumbling
    ) {
        ChamsController26.RenderTransform transformed =
                ChamsController26.transform(renderType, color, tint);
        if (transformed == null) {
            delegate.submitModel(
                    model,
                    state,
                    poses,
                    renderType,
                    light,
                    overlay,
                    color,
                    sprite,
                    outlineColor,
                    crumbling
            );
            return;
        }
        delegate.submitModel(
                model,
                state,
                poses,
                transformed.renderType(),
                light,
                overlay,
                transformed.color(),
                sprite,
                outlineColor,
                crumbling
        );
    }

    @Override
    public void submitMovingBlock(
            PoseStack poses,
            MovingBlockRenderState state,
            int light
    ) {
        delegate.submitMovingBlock(poses, state, light);
    }

    @Override
    public void submitBlockModel(
            PoseStack poses,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintColors,
            int light,
            int overlay,
            int outlineColor
    ) {
        delegate.submitBlockModel(
                poses,
                renderType,
                parts,
                tintColors,
                light,
                overlay,
                outlineColor
        );
    }

    @Override
    public void submitBreakingBlockModel(
            PoseStack poses,
            List<BlockStateModelPart> parts,
            int overlay
    ) {
        delegate.submitBreakingBlockModel(poses, parts, overlay);
    }

    @Override
    public void submitShapeOutline(
            PoseStack poses,
            VoxelShape shape,
            RenderType renderType,
            int color,
            float lineWidth,
            boolean alwaysOnTop
    ) {
        delegate.submitShapeOutline(
                poses,
                shape,
                renderType,
                color,
                lineWidth,
                alwaysOnTop
        );
    }

    @Override
    public void submitItem(
            PoseStack poses,
            ItemDisplayContext context,
            int light,
            int overlay,
            int outlineColor,
            int[] tintColors,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType
    ) {
        delegate.submitItem(
                poses,
                context,
                light,
                overlay,
                outlineColor,
                tintColors,
                quads,
                foilType
        );
    }

    @Override
    public void submitCustomGeometry(
            PoseStack poses,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer
    ) {
        delegate.submitCustomGeometry(poses, renderType, renderer);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState state) {
        delegate.submitQuadParticleGroup(state);
    }

    @Override
    public void submitGizmoPrimitives(
            DrawableGizmoPrimitives.Group group,
            CameraRenderState camera,
            boolean alwaysOnTop
    ) {
        delegate.submitGizmoPrimitives(group, camera, alwaysOnTop);
    }

    private static final class ChamsOrderedSubmitNodeCollector26
            implements OrderedSubmitNodeCollector {
        private final OrderedSubmitNodeCollector delegate;
        private final int tint;

        private ChamsOrderedSubmitNodeCollector26(
                OrderedSubmitNodeCollector delegate,
                int tint
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.tint = tint;
        }

        @Override
        public void submitShadow(
                PoseStack poses,
                float opacity,
                List<EntityRenderState.ShadowPiece> pieces
        ) {
            delegate.submitShadow(poses, opacity, pieces);
        }

        @Override
        public void submitNameTag(
                PoseStack poses,
                Vec3 attachment,
                int yOffset,
                Component text,
                boolean discrete,
                int light,
                CameraRenderState camera
        ) {
            delegate.submitNameTag(
                    poses,
                    attachment,
                    yOffset,
                    text,
                    discrete,
                    light,
                    camera
            );
        }

        @Override
        public void submitText(
                PoseStack poses,
                float x,
                float y,
                FormattedCharSequence text,
                boolean dropShadow,
                Font.DisplayMode mode,
                int color,
                int backgroundColor,
                int light,
                int outlineColor
        ) {
            delegate.submitText(
                    poses,
                    x,
                    y,
                    text,
                    dropShadow,
                    mode,
                    color,
                    backgroundColor,
                    light,
                    outlineColor
            );
        }

        @Override
        public void submitFlame(
                PoseStack poses,
                EntityRenderState state,
                org.joml.Quaternionf rotation
        ) {
            delegate.submitFlame(poses, state, rotation);
        }

        @Override
        public void submitLeash(
                PoseStack poses,
                EntityRenderState.LeashState state
        ) {
            delegate.submitLeash(poses, state);
        }

        @Override
        public <S> void submitModel(
                Model<? super S> model,
                S state,
                PoseStack poses,
                RenderType renderType,
                int light,
                int overlay,
                int color,
                TextureAtlasSprite sprite,
                int outlineColor,
                ModelFeatureRenderer.CrumblingOverlay crumbling
        ) {
            ChamsController26.RenderTransform transformed =
                    ChamsController26.transform(renderType, color, tint);
            if (transformed == null) {
                delegate.submitModel(
                        model,
                        state,
                        poses,
                        renderType,
                        light,
                        overlay,
                        color,
                        sprite,
                        outlineColor,
                        crumbling
                );
                return;
            }
            delegate.submitModel(
                    model,
                    state,
                    poses,
                    transformed.renderType(),
                    light,
                    overlay,
                    transformed.color(),
                    sprite,
                    outlineColor,
                    crumbling
            );
        }

        @Override
        public void submitMovingBlock(
                PoseStack poses,
                MovingBlockRenderState state,
                int light
        ) {
            delegate.submitMovingBlock(poses, state, light);
        }

        @Override
        public void submitBlockModel(
                PoseStack poses,
                RenderType renderType,
                List<BlockStateModelPart> parts,
                int[] tintColors,
                int light,
                int overlay,
                int outlineColor
        ) {
            delegate.submitBlockModel(
                    poses,
                    renderType,
                    parts,
                    tintColors,
                    light,
                    overlay,
                    outlineColor
            );
        }

        @Override
        public void submitBreakingBlockModel(
                PoseStack poses,
                List<BlockStateModelPart> parts,
                int overlay
        ) {
            delegate.submitBreakingBlockModel(poses, parts, overlay);
        }

        @Override
        public void submitShapeOutline(
                PoseStack poses,
                VoxelShape shape,
                RenderType renderType,
                int color,
                float lineWidth,
                boolean alwaysOnTop
        ) {
            delegate.submitShapeOutline(
                    poses,
                    shape,
                    renderType,
                    color,
                    lineWidth,
                    alwaysOnTop
            );
        }

        @Override
        public void submitItem(
                PoseStack poses,
                ItemDisplayContext context,
                int light,
                int overlay,
                int outlineColor,
                int[] tintColors,
                List<BakedQuad> quads,
                ItemStackRenderState.FoilType foilType
        ) {
            delegate.submitItem(
                    poses,
                    context,
                    light,
                    overlay,
                    outlineColor,
                    tintColors,
                    quads,
                    foilType
            );
        }

        @Override
        public void submitCustomGeometry(
                PoseStack poses,
                RenderType renderType,
                SubmitNodeCollector.CustomGeometryRenderer renderer
        ) {
            delegate.submitCustomGeometry(poses, renderType, renderer);
        }

        @Override
        public void submitQuadParticleGroup(
                QuadParticleRenderState state
        ) {
            delegate.submitQuadParticleGroup(state);
        }

        @Override
        public void submitGizmoPrimitives(
                DrawableGizmoPrimitives.Group group,
                CameraRenderState camera,
                boolean alwaysOnTop
        ) {
            delegate.submitGizmoPrimitives(group, camera, alwaysOnTop);
        }
    }
}
