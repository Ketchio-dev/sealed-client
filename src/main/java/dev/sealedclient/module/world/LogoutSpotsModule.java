package dev.sealedclient.module.world;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.DoubleSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Records the last known position of players that disappear from both the
 * current level and the connection's online-player list.
 */
public final class LogoutSpotsModule extends Module implements TickableModule {
    private static final int MAX_TRACKED_VISIBLE_PLAYERS = 512;

    private final IntegerSetting lifetime = addSetting(new IntegerSetting(
            "lifetime",
            "Lifetime",
            "Seconds that logout spots remain available.",
            900,
            30,
            3600,
            30
    ));
    private final IntegerSetting maximumEntries = addSetting(new IntegerSetting(
            "maximum_entries",
            "Maximum Entries",
            "Maximum number of logout spots retained.",
            128,
            8,
            1024,
            8
    ));
    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Color",
            "ARGB color used by world renderers.",
            0xCCFF6B6B
    ));
    private final BooleanSetting showName = addSetting(new BooleanSetting(
            "show_name",
            "Show Name",
            "Whether world renderers should show the player's name.",
            true
    ));
    private final BooleanSetting tracer = addSetting(new BooleanSetting(
            "tracer",
            "Tracer",
            "Whether world renderers should draw a tracer to the spot.",
            false
    ));
    private final DoubleSetting lineWidth = addSetting(new DoubleSetting(
            "line_width",
            "Line Width",
            "Preferred outline width for world renderers.",
            1.5,
            0.5,
            5.0,
            0.5
    ));

    private final Map<UUID, TrackedPlayer> previousPlayers = new LinkedHashMap<>();
    private final Map<UUID, StoredSpot> spots = new LinkedHashMap<>();

    private ClientLevel activeLevel;
    private ResourceLocation activeDimension;
    private long moduleTick;

    public LogoutSpotsModule() {
        super(
                "logout_spots",
                "Logout Spots",
                "Remembers where visible players logged out.",
                Category.UTILITY,
                false,
                ModuleRisk.PASSIVE
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clearSession();
            return;
        }

        ClientLevel level = minecraft.level;
        ResourceLocation dimension = level.dimension().location();
        if (level != activeLevel || !Objects.equals(dimension, activeDimension)) {
            beginSession(minecraft, level, dimension);
            return;
        }

        moduleTick++;
        pruneExpired();

        Map<UUID, TrackedPlayer> currentPlayers = collectVisiblePlayers(minecraft);
        for (TrackedPlayer previous : previousPlayers.values()) {
            if (currentPlayers.containsKey(previous.playerId())) {
                continue;
            }

            // Entity unloading alone is not enough evidence of a logout. Requiring
            // the tab-list entry to disappear avoids recording ordinary chunk unloads.
            if (minecraft.getConnection() == null
                    || minecraft.getConnection().getPlayerInfo(previous.playerId()) != null) {
                continue;
            }
            recordSpot(previous);
        }

        for (UUID currentPlayer : currentPlayers.keySet()) {
            spots.remove(currentPlayer);
        }

        previousPlayers.clear();
        previousPlayers.putAll(currentPlayers);
        enforceMaximumEntries();
    }

    public List<LogoutSpotSnapshot> snapshot() {
        List<LogoutSpotSnapshot> result = new ArrayList<>(spots.size());
        long lifetimeTicks = lifetime.get() * 20L;
        for (StoredSpot spot : spots.values()) {
            long ageTicks = Math.max(0, moduleTick - spot.observedAtTick());
            long remainingTicks = Math.max(0, lifetimeTicks - ageTicks);
            result.add(new LogoutSpotSnapshot(
                    spot.playerId(),
                    spot.playerName(),
                    spot.x(),
                    spot.y(),
                    spot.z(),
                    spot.yaw(),
                    spot.dimension(),
                    spot.observedAtTick(),
                    ageTicks,
                    remainingTicks
            ));
        }
        return List.copyOf(result);
    }

    public RenderSettings renderSettings() {
        return new RenderSettings(
                color.get(),
                showName.get(),
                tracer.get(),
                lineWidth.get()
        );
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        clearSession();
    }

    private void beginSession(
            Minecraft minecraft,
            ClientLevel level,
            ResourceLocation dimension
    ) {
        clearSession();
        activeLevel = level;
        activeDimension = dimension;
        previousPlayers.putAll(collectVisiblePlayers(minecraft));
    }

    private void clearSession() {
        activeLevel = null;
        activeDimension = null;
        moduleTick = 0;
        previousPlayers.clear();
        spots.clear();
    }

    private Map<UUID, TrackedPlayer> collectVisiblePlayers(Minecraft minecraft) {
        Map<UUID, TrackedPlayer> result = new LinkedHashMap<>();
        UUID localPlayerId = minecraft.player == null ? null : minecraft.player.getUUID();

        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player.getUUID().equals(localPlayerId)) {
                continue;
            }
            result.put(player.getUUID(), new TrackedPlayer(
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot()
            ));
            if (result.size() >= MAX_TRACKED_VISIBLE_PLAYERS) {
                break;
            }
        }
        return result;
    }

    private void recordSpot(TrackedPlayer player) {
        spots.remove(player.playerId());
        spots.put(player.playerId(), new StoredSpot(
                player.playerId(),
                player.playerName(),
                player.x(),
                player.y(),
                player.z(),
                player.yaw(),
                activeDimension.toString(),
                moduleTick
        ));
        enforceMaximumEntries();
    }

    private void pruneExpired() {
        long lifetimeTicks = lifetime.get() * 20L;
        spots.values().removeIf(
                spot -> moduleTick - spot.observedAtTick() > lifetimeTicks
        );
    }

    private void enforceMaximumEntries() {
        while (spots.size() > maximumEntries.get()) {
            UUID oldest = spots.keySet().iterator().next();
            spots.remove(oldest);
        }
    }

    public record LogoutSpotSnapshot(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw,
            String dimension,
            long observedAtTick,
            long ageTicks,
            long remainingTicks
    ) {
    }

    public record RenderSettings(
            int argb,
            boolean showName,
            boolean tracer,
            double lineWidth
    ) {
    }

    private record TrackedPlayer(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw
    ) {
    }

    private record StoredSpot(
            UUID playerId,
            String playerName,
            double x,
            double y,
            double z,
            float yaw,
            String dimension,
            long observedAtTick
    ) {
    }
}
