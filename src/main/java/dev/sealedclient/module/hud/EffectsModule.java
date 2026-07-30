package dev.sealedclient.module.hud;

import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.core.TickableModule;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EffectsModule extends HudModule implements TickableModule {
    private static final Comparator<MobEffectInstance> EFFECT_ORDER = Comparator
            .comparing((MobEffectInstance effect) ->
                    effect.getEffect().value().getDisplayName().getString())
            .thenComparingInt(MobEffectInstance::getDuration);

    private final IntegerSetting maximum = addSetting(new IntegerSetting(
            "maximum",
            "Maximum",
            "Maximum number of active effects to show.",
            5,
            1,
            12,
            1
    ));
    private final List<MobEffectInstance> sortedEffects = new ArrayList<>();
    private final List<String> displayLines = new ArrayList<>();

    public EffectsModule() {
        super("effects", "Effects", "Displays active potion effects and durations.", false);
    }

    @Override
    public void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        sortedEffects.clear();
        displayLines.clear();
        if (player == null || player.getActiveEffects().isEmpty()) {
            return;
        }

        sortedEffects.addAll(player.getActiveEffects());
        sortedEffects.sort(EFFECT_ORDER);

        for (MobEffectInstance effect : sortedEffects) {
            if (displayLines.size() >= maximum.get()) {
                break;
            }

            String name = effect.getEffect().value().getDisplayName().getString();
            if (effect.getAmplifier() > 0) {
                name += " " + roman(effect.getAmplifier() + 1);
            }
            String duration = effect.isInfiniteDuration()
                    ? "∞"
                    : formatDuration(effect.getDuration());
            displayLines.add(name + " " + duration);
        }
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        for (int line = 0; line < displayLines.size(); line++) {
            context.text(
                    displayLines.get(line),
                    x,
                    y + line * 10,
                    HudRenderContext.TEXT
            );
        }
        return displayLines.size() * 10;
    }

    private static String formatDuration(int ticks) {
        int totalSeconds = Math.max(0, ticks / 20);
        return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }
}
