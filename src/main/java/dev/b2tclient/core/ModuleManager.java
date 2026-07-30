package dev.b2tclient.core;

import dev.b2tclient.B2TClient;
import dev.b2tclient.hud.HudModule;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ModuleManager {
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Map<String, Module> lookup = new HashMap<>();
    private final Map<Category, List<Module>> categoryModules = new EnumMap<>(Category.class);
    private final Map<Category, List<Module>> categoryViews = new EnumMap<>(Category.class);
    private final List<Module> tickableModules = new ArrayList<>();
    private final List<HudModule> hudModules = new ArrayList<>();
    private final Collection<Module> allView = Collections.unmodifiableCollection(modules.values());
    private final List<HudModule> hudView = Collections.unmodifiableList(hudModules);

    public ModuleManager() {
        for (Category category : Category.values()) {
            List<Module> categoryList = new ArrayList<>();
            categoryModules.put(category, categoryList);
            categoryViews.put(category, Collections.unmodifiableList(categoryList));
        }
    }

    public void register(Module module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate module id: " + module.id());
        }

        lookup.putIfAbsent(normalize(module.name()), module);
        categoryModules.get(module.category()).add(module);
        if (module instanceof TickableModule) {
            tickableModules.add(module);
        }
        if (module instanceof HudModule hudModule) {
            hudModules.add(hudModule);
        }
    }

    public Collection<Module> all() {
        return allView;
    }

    public List<Module> inCategory(Category category) {
        return category == null ? List.of() : categoryViews.get(category);
    }

    public List<HudModule> hudModules() {
        return hudView;
    }

    public List<HudModule> enabledHudModules() {
        List<HudModule> result = new ArrayList<>();
        for (HudModule module : hudModules) {
            if (module.isEnabled()) {
                result.add(module);
            }
        }
        return result;
    }

    public Optional<Module> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }

        String normalized = normalize(idOrName);
        Module exact = modules.get(normalized);
        return Optional.ofNullable(exact != null ? exact : lookup.get(normalized));
    }

    public boolean tick(Minecraft minecraft) {
        boolean changed = false;
        for (Module module : tickableModules) {
            if (!module.isEnabled()) {
                continue;
            }

            try {
                ((TickableModule) module).onTick(minecraft);
            } catch (RuntimeException exception) {
                B2TClient.LOGGER.error("Disabling module {} after an error", module.id(), exception);
                changed |= module.setEnabled(false, minecraft);
            }
        }
        return changed;
    }

    public void shutdown(Minecraft minecraft) {
        List<Module> reverseOrder = new ArrayList<>(modules.values());
        Collections.reverse(reverseOrder);
        for (Module module : reverseOrder) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.setEnabled(false, minecraft);
            } catch (RuntimeException exception) {
                B2TClient.LOGGER.error("Could not clean up module {}", module.id(), exception);
            }
        }
    }

    public boolean panic(Minecraft minecraft) {
        boolean changed = false;
        for (Module module : modules.values()) {
            if (!module.isEnabled() || module.risk() == ModuleRisk.PASSIVE) {
                continue;
            }
            changed |= module.setEnabled(false, minecraft);
        }
        return changed;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
