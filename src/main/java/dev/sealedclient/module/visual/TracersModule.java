package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.DoubleSetting;

public final class TracersModule extends Module {
    public static final String ID = "tracers";

    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum distance at which tracer lines are drawn.",
            192.0,
            16.0,
            512.0,
            8.0
    ));

    private final ColorSetting playerColor = addSetting(new ColorSetting(
            "player_color",
            "Player Color",
            "Color used for tracers to non-friend players.",
            0xDDFF6666
    ));

    private final ColorSetting friendColor = addSetting(new ColorSetting(
            "friend_color",
            "Friend Color",
            "Color used for tracers to friends.",
            0xDD55FF88
    ));

    private final BooleanSetting showFriends = addSetting(new BooleanSetting(
            "show_friends",
            "Show Friends",
            "Draws tracers to players on the friend list.",
            true
    ));

    private final BooleanSetting showSelf = addSetting(new BooleanSetting(
            "show_self",
            "Show Self",
            "Draws a tracer to the local player in third-person view.",
            false
    ));

    private final DoubleSetting lineWidth = addSetting(new DoubleSetting(
            "line_width",
            "Line Width",
            "Width of tracer lines in screen pixels.",
            1.5,
            0.5,
            4.0,
            0.5
    ));

    public TracersModule() {
        super(
                ID,
                "Tracers",
                "Draws lines from the camera toward nearby players.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
    }

    public double range() {
        return range.get();
    }

    public int playerColor() {
        return playerColor.get();
    }

    public int friendColor() {
        return friendColor.get();
    }

    public boolean showFriends() {
        return showFriends.get();
    }

    public boolean showSelf() {
        return showSelf.get();
    }

    public double lineWidth() {
        return lineWidth.get();
    }

    public DoubleSetting rangeSetting() {
        return range;
    }

    public ColorSetting playerColorSetting() {
        return playerColor;
    }

    public ColorSetting friendColorSetting() {
        return friendColor;
    }

    public BooleanSetting showFriendsSetting() {
        return showFriends;
    }

    public BooleanSetting showSelfSetting() {
        return showSelf;
    }

    public DoubleSetting lineWidthSetting() {
        return lineWidth;
    }
}
