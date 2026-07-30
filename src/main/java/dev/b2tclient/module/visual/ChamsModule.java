package dev.b2tclient.module.visual;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-routes player model buffers through render types with depth testing
 * disabled. Render state is restored by every wrapped render type.
 */
public final class ChamsModule extends Module {
    public static final String ID = "chams";

    private static final int MAX_CACHED_RENDER_TYPES = 512;
    private static final AtomicInteger RENDER_TYPE_SEQUENCE = new AtomicInteger();
    private static final Map<RenderType, RenderType> THROUGH_WALL_TYPES =
            new IdentityHashMap<>();
    private static volatile ChamsModule instance;

    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Color",
            "Tint and opacity applied to player models.",
            0xA0FF5555
    ));

    private final BooleanSetting showSelf = addSetting(new BooleanSetting(
            "show_self",
            "Show Self",
            "Applies chams to the local player in third-person view.",
            false
    ));

    public ChamsModule() {
        super(
                ID,
                "Chams",
                "Renders player models through walls with a configurable tint.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
        instance = this;
    }

    public static ChamsModule activeFor(PlayerRenderState state) {
        ChamsModule current = instance;
        if (current == null || !current.isEnabled() || state == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!current.showSelf.get() && minecraft.player != null
                && state.id == minecraft.player.getId()) {
            return null;
        }
        return current;
    }

    public MultiBufferSource wrap(MultiBufferSource delegate) {
        int argb = color.get();
        return renderType -> {
            if (renderType.format() != DefaultVertexFormat.NEW_ENTITY) {
                return delegate.getBuffer(renderType);
            }
            return new TintingVertexConsumer(
                    delegate.getBuffer(throughWalls(renderType)),
                    argb
            );
        };
    }

    public int color() {
        return color.get();
    }

    public boolean showSelf() {
        return showSelf.get();
    }

    public ColorSetting colorSetting() {
        return color;
    }

    public BooleanSetting showSelfSetting() {
        return showSelf;
    }

    private static RenderType throughWalls(RenderType original) {
        synchronized (THROUGH_WALL_TYPES) {
            RenderType cached = THROUGH_WALL_TYPES.get(original);
            if (cached != null) {
                return cached;
            }
            if (THROUGH_WALL_TYPES.size() >= MAX_CACHED_RENDER_TYPES) {
                THROUGH_WALL_TYPES.clear();
            }
            RenderType created = new RenderType(
                    "b2t_chams_" + RENDER_TYPE_SEQUENCE.incrementAndGet(),
                    original.format(),
                    original.mode(),
                    original.bufferSize(),
                    original.affectsCrumbling(),
                    original.sortOnUpload(),
                    () -> {
                        original.setupRenderState();
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        RenderSystem.disableDepthTest();
                        RenderSystem.depthMask(false);
                    },
                    () -> {
                        original.clearRenderState();
                        RenderSystem.depthMask(true);
                        RenderSystem.enableDepthTest();
                        RenderSystem.disableBlend();
                    }
            ) {
            };
            THROUGH_WALL_TYPES.put(original, created);
            return created;
        }
    }

    private static final class TintingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int alpha;
        private final int red;
        private final int green;
        private final int blue;

        private TintingVertexConsumer(VertexConsumer delegate, int argb) {
            this.delegate = delegate;
            alpha = argb >>> 24 & 0xFF;
            red = argb >>> 16 & 0xFF;
            green = argb >>> 8 & 0xFF;
            blue = argb & 0xFF;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int originalRed, int originalGreen, int originalBlue, int originalAlpha) {
            delegate.setColor(
                    originalRed * red / 255,
                    originalGreen * green / 255,
                    originalBlue * blue / 255,
                    originalAlpha * alpha / 255
            );
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
