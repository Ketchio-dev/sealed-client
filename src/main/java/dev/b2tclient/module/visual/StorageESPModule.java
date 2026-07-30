package dev.b2tclient.module.visual;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.ColorSetting;
import dev.b2tclient.core.setting.IntegerSetting;

public final class StorageESPModule extends Module {
    public static final String ID = "storage_esp";

    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Maximum distance at which storage blocks are highlighted.",
            96,
            16,
            256,
            8
    ));

    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Color",
            "Color used to highlight storage blocks.",
            0xCCFFB52E
    ));

    private final BooleanSetting includeShulkers = addSetting(new BooleanSetting(
            "include_shulkers",
            "Include Shulkers",
            "Highlights placed shulker boxes as storage.",
            true
    ));

    public StorageESPModule() {
        super(
                ID,
                "Storage ESP",
                "Highlights nearby containers and other storage blocks.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
    }

    public int range() {
        return range.get();
    }

    public int color() {
        return color.get();
    }

    public boolean includeShulkers() {
        return includeShulkers.get();
    }
}
