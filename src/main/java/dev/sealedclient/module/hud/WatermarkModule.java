package dev.sealedclient.module.hud;

import dev.sealedclient.SealedClient;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;

public final class WatermarkModule extends HudModule {
    private final BooleanSetting showVersion = addSetting(new BooleanSetting(
            "show_version",
            "Show version",
            "Display the client version next to its name.",
            true
    ));

    public WatermarkModule() {
        super("watermark", "Watermark", "Displays the client name.", true);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        String text = showVersion.get()
                ? SealedClient.DISPLAY_NAME + " " + SealedClient.VERSION
                : SealedClient.DISPLAY_NAME;
        context.text(text, x, y, HudRenderContext.ACCENT);
        return 10;
    }
}

