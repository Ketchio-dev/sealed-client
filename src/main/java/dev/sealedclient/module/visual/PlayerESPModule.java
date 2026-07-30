package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.DoubleSetting;

public final class PlayerESPModule extends Module {
    public static final String ID = "player_esp";

    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum distance at which players are highlighted.",
            128.0,
            16.0,
            512.0,
            8.0
    ));

    private final ColorSetting playerColor = addSetting(new ColorSetting(
            "player_color",
            "Player Color",
            "Color used for non-friend players.",
            0xCC55AAFF
    ));

    private final ColorSetting friendColor = addSetting(new ColorSetting(
            "friend_color",
            "Friend Color",
            "Color used for players on the friend list.",
            0xCC55FF88
    ));

    private final BooleanSetting showFriends = addSetting(new BooleanSetting(
            "show_friends",
            "Show Friends",
            "Highlights friends using the friend color.",
            true
    ));

    private final BooleanSetting showSelf = addSetting(new BooleanSetting(
            "show_self",
            "Show Self",
            "Highlights the local player in third-person view.",
            false
    ));

    private final BooleanSetting fill = addSetting(new BooleanSetting(
            "fill",
            "Fill",
            "Draws a translucent fill inside player boxes.",
            true
    ));

    private final BooleanSetting outline = addSetting(new BooleanSetting(
            "outline",
            "Outline",
            "Draws an outline around player boxes.",
            true
    ));

    public PlayerESPModule() {
        super(
                ID,
                "Player ESP",
                "Highlights nearby players through the world overlay.",
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

    public boolean fill() {
        return fill.get();
    }

    public boolean outline() {
        return outline.get();
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

    public BooleanSetting fillSetting() {
        return fill;
    }

    public BooleanSetting outlineSetting() {
        return outline;
    }
}
