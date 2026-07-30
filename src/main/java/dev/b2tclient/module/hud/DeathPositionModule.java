package dev.b2tclient.module.hud;

import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.EnumSetting;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Objects;

public final class DeathPositionModule extends HudModule implements TickableModule {
    private final Runnable saveConfiguration;
    private final BooleanSetting recorded;
    private final DoubleSetting deathX;
    private final DoubleSetting deathY;
    private final DoubleSetting deathZ;
    private final EnumSetting<Dimension> dimension;
    private boolean wasDead;
    private String displayText;

    public DeathPositionModule(Runnable saveConfiguration) {
        super(
                "death_position",
                "Death Position",
                "Records and displays your most recent death coordinates.",
                true
        );
        this.saveConfiguration = Objects.requireNonNull(saveConfiguration, "saveConfiguration");
        recorded = addSetting(new BooleanSetting(
                "recorded", "Recorded", "Whether a death position has been stored.", false
        ));
        deathX = addSetting(new DoubleSetting(
                "x", "X", "Stored death X coordinate.", 0.0,
                -30_000_000.0, 30_000_000.0, 0.1
        ));
        deathY = addSetting(new DoubleSetting(
                "y", "Y", "Stored death Y coordinate.", 0.0,
                -2048.0, 2048.0, 0.1
        ));
        deathZ = addSetting(new DoubleSetting(
                "z", "Z", "Stored death Z coordinate.", 0.0,
                -30_000_000.0, 30_000_000.0, 0.1
        ));
        dimension = addSetting(new EnumSetting<>(
                "dimension", "Dimension", "Stored death dimension.", Dimension.OVERWORLD
        ));

        recorded.visibleWhen(() -> false);
        deathX.visibleWhen(() -> false);
        deathY.visibleWhen(() -> false);
        deathZ.visibleWhen(() -> false);
        dimension.visibleWhen(() -> false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            wasDead = false;
            displayText = null;
            return;
        }

        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead) {
            recorded.set(true);
            deathX.set(player.getX());
            deathY.set(player.getY());
            deathZ.set(player.getZ());
            dimension.set(Dimension.from(player.level()));
            saveConfiguration.run();
            minecraft.gui.setOverlayMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "Death recorded: %.1f, %.1f, %.1f (%s)",
                    deathX.get(),
                    deathY.get(),
                    deathZ.get(),
                    dimension.get().displayName
            )), false);
        }
        wasDead = dead;
        updateDisplayText(player);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        if (displayText == null) {
            return 0;
        }
        context.text(displayText, x, y, HudRenderContext.WARNING);
        return 10;
    }

    private void updateDisplayText(LocalPlayer player) {
        if (!recorded.get()) {
            displayText = null;
            return;
        }
        String distance = "";
        if (Dimension.from(player.level()) == dimension.get()) {
            double dx = player.getX() - deathX.get();
            double dz = player.getZ() - deathZ.get();
            distance = "  " + Math.round(Math.hypot(dx, dz)) + "m";
        }
        displayText = String.format(
                Locale.ROOT,
                "Death: %.1f, %.1f, %.1f (%s)%s",
                deathX.get(),
                deathY.get(),
                deathZ.get(),
                dimension.get().displayName,
                distance
        );
    }

    private enum Dimension {
        OVERWORLD("Overworld"),
        NETHER("Nether"),
        END("End"),
        OTHER("Other");

        private final String displayName;

        Dimension(String displayName) {
            this.displayName = displayName;
        }

        private static Dimension from(Level level) {
            if (level.dimension() == Level.OVERWORLD) {
                return OVERWORLD;
            }
            if (level.dimension() == Level.NETHER) {
                return NETHER;
            }
            if (level.dimension() == Level.END) {
                return END;
            }
            return OTHER;
        }
    }
}
