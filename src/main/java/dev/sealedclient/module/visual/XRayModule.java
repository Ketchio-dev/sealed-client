package dev.sealedclient.module.visual;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.core.setting.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * Client-side block render filter backed by chunk rebuild mixins.
 *
 * <p>The module deliberately exposes only immutable snapshots to asynchronous
 * chunk compilation. It never reads or changes world data.</p>
 */
public final class XRayModule extends Module implements TickableModule {
    public static final String ID = "xray";

    private static volatile XRayModule instance;

    private final StringListSetting visibleBlocks = addSetting(new StringListSetting(
            "visible_blocks",
            "Visible Blocks",
            "Block IDs that remain fully visible.",
            List.of(
                    "minecraft:ancient_debris",
                    "minecraft:coal_ore",
                    "minecraft:copper_ore",
                    "minecraft:deepslate_coal_ore",
                    "minecraft:deepslate_copper_ore",
                    "minecraft:deepslate_diamond_ore",
                    "minecraft:deepslate_emerald_ore",
                    "minecraft:deepslate_gold_ore",
                    "minecraft:deepslate_iron_ore",
                    "minecraft:deepslate_lapis_ore",
                    "minecraft:deepslate_redstone_ore",
                    "minecraft:diamond_ore",
                    "minecraft:emerald_ore",
                    "minecraft:gold_ore",
                    "minecraft:iron_ore",
                    "minecraft:lapis_ore",
                    "minecraft:nether_gold_ore",
                    "minecraft:nether_quartz_ore",
                    "minecraft:redstone_ore"
            )
    ));

    private final IntegerSetting hiddenOpacity = addSetting(new IntegerSetting(
            "hidden_opacity",
            "Hidden Opacity",
            "Opacity of blocks outside the whitelist. Zero removes them from the mesh.",
            0,
            0,
            100,
            5
    ));

    private final BooleanSetting autoRefresh = addSetting(new BooleanSetting(
            "auto_refresh",
            "Auto Refresh",
            "Rebuilds visible chunks after XRay settings change.",
            true
    ));

    private final IntegerSetting refreshDelay = addSetting(new IntegerSetting(
            "refresh_delay",
            "Refresh Delay",
            "Ticks to debounce chunk rebuilds after settings change.",
            4,
            1,
            40,
            1
    ));

    private int observedSettingsHash;
    private int refreshCountdown = -1;
    private ClientLevel observedLevel;
    private volatile Set<String> renderVisibleBlocks = Set.of();
    private volatile int renderHiddenOpacity;

    public XRayModule() {
        super(
                ID,
                "XRay",
                "Filters world geometry to reveal selected blocks.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
        updateRenderSnapshot();
        observedSettingsHash = settingsHash();
        instance = this;
    }

    public static XRayModule active() {
        XRayModule current = instance;
        return current != null && current.isEnabled() ? current : null;
    }

    public boolean isTarget(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        Set<String> configured = renderVisibleBlocks;
        return configured.contains(id.toString())
                || ("minecraft".equals(id.getNamespace()) && configured.contains(id.getPath()));
    }

    public int hiddenOpacity() {
        return renderHiddenOpacity;
    }

    public boolean hides(BlockState state) {
        return !isTarget(state) && hiddenOpacity() == 0;
    }

    public boolean makesTransparent(BlockState state) {
        int opacity = hiddenOpacity();
        return !isTarget(state) && opacity > 0 && opacity < 100;
    }

    public VertexConsumer opacityConsumer(VertexConsumer delegate) {
        return new OpacityVertexConsumer(delegate, hiddenOpacity() / 100.0F);
    }

    public StringListSetting visibleBlocksSetting() {
        return visibleBlocks;
    }

    public IntegerSetting hiddenOpacitySetting() {
        return hiddenOpacity;
    }

    public BooleanSetting autoRefreshSetting() {
        return autoRefresh;
    }

    public IntegerSetting refreshDelaySetting() {
        return refreshDelay;
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        updateRenderSnapshot();
        observedSettingsHash = settingsHash();
        observedLevel = minecraft.level;
        refreshCountdown = -1;
        refresh(minecraft);
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        observedLevel = null;
        refreshCountdown = -1;
        refresh(minecraft);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.level != observedLevel) {
            observedLevel = minecraft.level;
            refreshCountdown = -1;
            refresh(minecraft);
        }

        int currentHash = settingsHash();
        if (currentHash != observedSettingsHash) {
            updateRenderSnapshot();
            observedSettingsHash = currentHash;
            refreshCountdown = autoRefresh.get() ? refreshDelay.get() : -1;
        }
        if (refreshCountdown > 0) {
            refreshCountdown--;
        }
        if (refreshCountdown == 0) {
            refreshCountdown = -1;
            refresh(minecraft);
        }
    }

    private int settingsHash() {
        int result = visibleBlocks.get().hashCode();
        result = 31 * result + hiddenOpacity.get();
        result = 31 * result + Boolean.hashCode(autoRefresh.get());
        result = 31 * result + refreshDelay.get();
        return result;
    }

    private void updateRenderSnapshot() {
        renderVisibleBlocks = visibleBlocks.get();
        renderHiddenOpacity = hiddenOpacity.get();
    }

    private static void refresh(Minecraft minecraft) {
        if (minecraft.level != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }

    private static final class OpacityVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float opacity;

        private OpacityVertexConsumer(VertexConsumer delegate, float opacity) {
            this.delegate = delegate;
            this.opacity = Math.max(0.0F, Math.min(1.0F, opacity));
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, Math.round(alpha * opacity));
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
