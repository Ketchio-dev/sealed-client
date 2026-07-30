package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.BooleanSetting;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.IntegerSetting;

public final class HoleESPModule extends Module {
    public static final String ID = "hole_esp";

    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Horizontal distance in which safe holes are highlighted.",
            24,
            4,
            64,
            2
    ));

    private final ColorSetting safeColor = addSetting(new ColorSetting(
            "safe_color",
            "Safe Color",
            "Color used for holes surrounded entirely by bedrock.",
            0xCC32D26E
    ));

    private final ColorSetting mixedColor = addSetting(new ColorSetting(
            "mixed_color",
            "Mixed Color",
            "Color used for holes surrounded by bedrock and obsidian.",
            0xCCE4B640
    ));

    private final ColorSetting unsafeColor = addSetting(new ColorSetting(
            "unsafe_color",
            "Unsafe Color",
            "Color used for otherwise valid holes with a breakable wall.",
            0xCCD94A4A
    ));

    private final BooleanSetting showUnsafe = addSetting(new BooleanSetting(
            "show_unsafe",
            "Show Unsafe",
            "Also highlights holes whose walls are not fully blast resistant.",
            false
    ));

    public HoleESPModule() {
        super(
                ID,
                "Hole ESP",
                "Highlights nearby one-block combat holes by safety.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
    }

    public int range() {
        return range.get();
    }

    public int safeColor() {
        return safeColor.get();
    }

    public int mixedColor() {
        return mixedColor.get();
    }

    public int unsafeColor() {
        return unsafeColor.get();
    }

    public boolean showUnsafe() {
        return showUnsafe.get();
    }
}
