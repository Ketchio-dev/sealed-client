package dev.sealedclient.module.hud;

import dev.sealedclient.hud.HudModule;
import dev.sealedclient.hud.HudRenderContext;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class ClockModule extends HudModule {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private long displayedEpochSecond = Long.MIN_VALUE;
    private String displayText;

    public ClockModule() {
        super("clock", "Clock", "Displays the computer's local time.", false);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        long epochSecond = System.currentTimeMillis() / 1000L;
        if (displayText == null || epochSecond != displayedEpochSecond) {
            displayedEpochSecond = epochSecond;
            displayText = "Time: " + FORMAT.format(LocalTime.now());
        }
        context.text(displayText, x, y, HudRenderContext.TEXT);
        return 10;
    }
}
