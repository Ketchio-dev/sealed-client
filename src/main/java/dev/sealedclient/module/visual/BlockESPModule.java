package dev.sealedclient.module.visual;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;
import dev.sealedclient.core.setting.ColorSetting;
import dev.sealedclient.core.setting.IntegerSetting;
import dev.sealedclient.core.setting.StringListSetting;

import java.util.List;
import java.util.Set;

public final class BlockESPModule extends Module {
    public static final String ID = "block_esp";

    private final StringListSetting targets = addSetting(new StringListSetting(
            "targets",
            "Target Blocks",
            "Namespaced block identifiers to highlight.",
            List.of(
                    "minecraft:ancient_debris",
                    "minecraft:nether_portal",
                    "minecraft:end_portal_frame"
            )
    ));

    private final IntegerSetting range = addSetting(new IntegerSetting(
            "range",
            "Range",
            "Maximum block search distance.",
            64,
            8,
            192,
            8
    ));

    private final IntegerSetting scanBudget = addSetting(new IntegerSetting(
            "scan_budget",
            "Scan Budget",
            "Maximum number of block positions inspected per client tick.",
            2_048,
            128,
            16_384,
            128
    ));

    private final ColorSetting color = addSetting(new ColorSetting(
            "color",
            "Color",
            "Color used to highlight matching blocks.",
            0xCC9B59FF
    ));

    public BlockESPModule() {
        super(
                ID,
                "Block ESP",
                "Highlights configured block types within a bounded search range.",
                Category.VISUAL,
                false,
                ModuleRisk.PASSIVE
        );
    }

    public Set<String> targets() {
        return targets.get();
    }

    public int range() {
        return range.get();
    }

    public int scanBudget() {
        return scanBudget.get();
    }

    public int color() {
        return color.get();
    }
}
