package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.DoubleSetting;

public final class NametagsModule extends Module {
    public static final String ID = "nametags";

    private final DoubleSetting range = addSetting(new DoubleSetting(
            "range",
            "Range",
            "Maximum distance at which enhanced nametags are shown.",
            128.0,
            16.0,
            512.0,
            8.0
    ));

    private final ColorSetting playerColor = addSetting(new ColorSetting(
            "player_color",
            "Player Color",
            "Text color used for non-friend players.",
            0xFFFFFFFF
    ));

    private final ColorSetting friendColor = addSetting(new ColorSetting(
            "friend_color",
            "Friend Color",
            "Text color used for players on the friend list.",
            0xFF55FF88
    ));

    private final ColorSetting backgroundColor = addSetting(new ColorSetting(
            "background_color",
            "Background Color",
            "Background color behind enhanced nametags.",
            0x99000000
    ));

    private final BooleanSetting showFriends = addSetting(new BooleanSetting(
            "show_friends",
            "Show Friends",
            "Shows enhanced nametags for friends.",
            true
    ));

    private final BooleanSetting showSelf = addSetting(new BooleanSetting(
            "show_self",
            "Show Self",
            "Shows an enhanced nametag for the local player in third-person view.",
            false
    ));

    private final BooleanSetting showHealth = addSetting(new BooleanSetting(
            "show_health",
            "Show Health",
            "Includes known health and absorption in the nametag.",
            true
    ));

    private final BooleanSetting showDistance = addSetting(new BooleanSetting(
            "show_distance",
            "Show Distance",
            "Includes distance from the camera in the nametag.",
            true
    ));

    private final BooleanSetting showEquipment = addSetting(new BooleanSetting(
            "show_equipment",
            "Show Equipment",
            "Shows visible equipment beneath the nametag.",
            true
    ));

    private final DoubleSetting scale = addSetting(new DoubleSetting(
            "scale",
            "Scale",
            "Base scale of enhanced nametags.",
            1.0,
            0.5,
            2.5,
            0.1
    ));

    public NametagsModule() {
        super(
                ID,
                "Nametags",
                "Shows enhanced information above nearby players.",
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

    public int backgroundColor() {
        return backgroundColor.get();
    }

    public boolean showFriends() {
        return showFriends.get();
    }

    public boolean showSelf() {
        return showSelf.get();
    }

    public boolean showHealth() {
        return showHealth.get();
    }

    public boolean showDistance() {
        return showDistance.get();
    }

    public boolean showEquipment() {
        return showEquipment.get();
    }

    public double scale() {
        return scale.get();
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

    public ColorSetting backgroundColorSetting() {
        return backgroundColor;
    }

    public BooleanSetting showFriendsSetting() {
        return showFriends;
    }

    public BooleanSetting showSelfSetting() {
        return showSelf;
    }

    public BooleanSetting showHealthSetting() {
        return showHealth;
    }

    public BooleanSetting showDistanceSetting() {
        return showDistance;
    }

    public BooleanSetting showEquipmentSetting() {
        return showEquipment;
    }

    public DoubleSetting scaleSetting() {
        return scale;
    }
}
