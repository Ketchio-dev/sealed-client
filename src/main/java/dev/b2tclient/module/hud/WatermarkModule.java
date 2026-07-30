package dev.b2tclient.module.hud;

import dev.b2tclient.B2TClient;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;

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
                ? B2TClient.DISPLAY_NAME + " " + B2TClient.VERSION
                : B2TClient.DISPLAY_NAME;
        context.text(text, x, y, HudRenderContext.ACCENT);
        return 10;
    }
}

