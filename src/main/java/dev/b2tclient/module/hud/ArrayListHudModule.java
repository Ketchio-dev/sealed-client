package dev.b2tclient.module.hud;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.hud.HudModule;
import dev.b2tclient.hud.HudRenderContext;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ArrayListHudModule extends HudModule implements TickableModule {
    private final IntegerSetting maximum = addSetting(new IntegerSetting(
            "maximum",
            "Maximum",
            "Maximum number of enabled modules to display.",
            12,
            1,
            40,
            1
    ));

    private final BooleanSetting showCategory = addSetting(new BooleanSetting(
            "show_category",
            "Show category",
            "Adds each enabled module's category to its display name.",
            false
    ));

    private final ModuleManager moduleManager;
    private final List<String> displayLines = new ArrayList<>();

    public ArrayListHudModule(ModuleManager moduleManager) {
        super(
                "array_list",
                "Array List",
                "Lists enabled non-HUD modules.",
                true
        );
        this.moduleManager = Objects.requireNonNull(moduleManager, "moduleManager");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        displayLines.clear();
        moduleManager.all().stream()
                .filter(Module::isEnabled)
                .filter(module -> module.category() != Category.HUD)
                .sorted(Comparator.comparing(Module::name, String.CASE_INSENSITIVE_ORDER))
                .limit(maximum.get())
                .map(this::displayName)
                .forEach(displayLines::add);
    }

    @Override
    public int render(HudRenderContext context, int x, int y) {
        for (int line = 0; line < displayLines.size(); line++) {
            context.text(
                    displayLines.get(line),
                    x,
                    y + line * 10,
                    HudRenderContext.ACCENT
            );
        }
        return displayLines.size() * 10;
    }

    private String displayName(Module module) {
        if (!showCategory.get()) {
            return module.name();
        }
        return module.name() + " [" + module.category().name() + "]";
    }
}
