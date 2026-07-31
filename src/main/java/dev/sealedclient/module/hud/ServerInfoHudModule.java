package dev.sealedclient.module.hud;

import dev.sealedclient.common.hud.QueueTextParser;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;

public final class ServerInfoHudModule extends HudModule implements TickableModule {
    private String serverLine;
    private String queueLine;

    public ServerInfoHudModule() {
        super(
                "server_info",
                "Server Info",
                "Shows the server address, visible player count, ping, and sidebar queue text.",
                false
        );
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            serverLine = null;
            queueLine = null;
            return;
        }

        String address = minecraft.getCurrentServer() == null
                ? "singleplayer"
                : minecraft.getCurrentServer().ip;
        int playerCount = minecraft.getConnection().getOnlinePlayers().size();
        PlayerInfo localInfo = minecraft.getConnection().getPlayerInfo(
                minecraft.player.getUUID()
        );
        int ping = localInfo == null ? -1 : localInfo.getLatency();
        serverLine = address
                + "  Players "
                + playerCount
                + "  Ping "
                + (ping < 0 ? "?" : ping + " ms");
        queueLine = findQueueText(minecraft.level.getScoreboard());
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (serverLine == null) {
            return 0;
        }
        context.text(serverLine, x, y, HudRenderContext.TEXT);
        if (queueLine == null) {
            return 10;
        }
        context.text(queueLine, x, y + 10, HudRenderContext.ACCENT);
        return 20;
    }

    private static String findQueueText(Scoreboard scoreboard) {
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            if (!entry.isHidden()) {
                lines.add(entry.ownerName().getString());
            }
        }
        return QueueTextParser
                .parse(sidebar.getDisplayName().getString(), lines)
                .orElse(null);
    }
}
