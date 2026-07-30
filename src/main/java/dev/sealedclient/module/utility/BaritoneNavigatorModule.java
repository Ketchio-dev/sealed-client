package dev.sealedclient.module.utility;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.integration.BaritoneNavigator;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/**
 * Explicit GUI opt-in for a separately installed Baritone instance.
 */
public final class BaritoneNavigatorModule extends Module {
    private final BaritoneNavigator navigator;
    private final IntegerSetting targetX = addSetting(new IntegerSetting(
            "target_x",
            "Target X",
            "Destination X block coordinate.",
            0,
            -30_000_000,
            30_000_000,
            1
    ));
    private final IntegerSetting targetY = addSetting(new IntegerSetting(
            "target_y",
            "Target Y",
            "Destination Y block coordinate.",
            64,
            -64,
            319,
            1
    ));
    private final IntegerSetting targetZ = addSetting(new IntegerSetting(
            "target_z",
            "Target Z",
            "Destination Z block coordinate.",
            0,
            -30_000_000,
            30_000_000,
            1
    ));
    private final BooleanSetting confirmTarget = addSetting(new BooleanSetting(
            "confirm_target",
            "Confirm Target",
            "Arm one start. This resets after navigation begins.",
            false
    ));

    public BaritoneNavigatorModule(BaritoneNavigator navigator) {
        super(
                "baritone_navigator",
                "Baritone Navigator",
                "Starts optional Baritone pathfinding to the configured coordinates.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.navigator = Objects.requireNonNull(navigator, "navigator");
    }

    @Override
    protected void onEnable(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            throw new IllegalStateException("Join a world before starting navigation");
        }
        if (!navigator.available()) {
            throw new IllegalStateException(navigator.status().detail());
        }
        if (!confirmTarget.get()) {
            throw new IllegalStateException("Set Confirm Target before enabling");
        }
        if (targetY.get() < minecraft.level.getMinY()
                || targetY.get() >= minecraft.level.getMaxY()) {
            throw new IllegalStateException(
                    "Target Y is outside the current dimension"
            );
        }

        confirmTarget.set(false);
        BaritoneNavigator.NavigationResult result =
                navigator.goTo(targetX.get(), targetY.get(), targetZ.get());
        if (!result.success()) {
            throw new IllegalStateException(result.message());
        }
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        navigator.releaseOwnedNavigation();
        confirmTarget.set(false);
    }
}
