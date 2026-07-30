package dev.sealedclient.module.hud;

import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;

public final class FpsModule extends HudModule {
    public FpsModule() {
        super("fps", "FPS", "Displays the current frame rate.", true);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        context.text(
                context.labelValue("FPS", Integer.toString(context.minecraft().getFps())),
                x,
                y,
                HudRenderContext.TEXT
        );
        return 10;
    }
}

