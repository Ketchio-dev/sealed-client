package dev.sealedclient.module.utility;

import dev.sealedclient.core.setting.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selects only recipes represented by the synchronized client recipe book.
 *
 * <p>Minecraft 1.21.4 does not expose a recipe resource key for client recipe
 * book entries. The conservative selector is therefore the display shape plus
 * exact output item, for example {@code shaped:minecraft:ender_chest}. If more
 * than one craftable display has the same selector, that selector is rejected
 * as ambiguous.</p>
 */
final class AutoCraftRecipeSelector {
    private static final int MAX_RECIPE_ENTRIES_SCANNED = 4_096;

    private AutoCraftRecipeSelector() {
    }

    static Optional<Selection> choose(
            Minecraft minecraft,
            CraftingMenu menu,
            StringListSetting recipeWhitelist,
            StringListSetting outputWhitelist
    ) {
        if (minecraft.player == null
                || minecraft.level == null
                || recipeWhitelist.get().isEmpty()
                || outputWhitelist.get().isEmpty()) {
            return Optional.empty();
        }

        StackedItemContents available = new StackedItemContents();
        minecraft.player.getInventory().fillStackedContents(available);
        menu.fillCraftSlotsStackedContents(available);

        Map<Integer, RecipeDisplayEntry> uniqueDisplays = new LinkedHashMap<>();
        int inspected = 0;
        for (RecipeCollection collection : minecraft.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                uniqueDisplays.putIfAbsent(entry.id().index(), entry);
                if (++inspected >= MAX_RECIPE_ENTRIES_SCANNED) {
                    break;
                }
            }
            if (inspected >= MAX_RECIPE_ENTRIES_SCANNED) {
                break;
            }
        }

        Map<String, List<Selection>> candidates = new HashMap<>();
        for (RecipeDisplayEntry entry : uniqueDisplays.values()) {
            createSelection(minecraft, menu, entry, available, outputWhitelist.get())
                    .ifPresent(selection -> candidates
                            .computeIfAbsent(selection.selector(), ignored -> new ArrayList<>())
                            .add(selection));
        }

        return recipeWhitelist.get().stream()
                .sorted()
                .map(candidates::get)
                .filter(matches -> matches != null && matches.size() == 1)
                .map(List::getFirst)
                .min(Comparator
                        .comparing(Selection::selector)
                        .thenComparingInt(selection -> selection.entry().id().index()));
    }

    private static Optional<Selection> createSelection(
            Minecraft minecraft,
            CraftingMenu menu,
            RecipeDisplayEntry entry,
            StackedItemContents available,
            Set<String> outputWhitelist
    ) {
        if (entry.craftingRequirements().isEmpty()
                || !entry.canCraft(available)) {
            return Optional.empty();
        }

        String kind = displayKind(entry.display(), menu);
        if (kind == null) {
            return Optional.empty();
        }

        List<ItemStack> results;
        try {
            results = entry.resultItems(SlotDisplayContext.fromLevel(minecraft.level));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (results.size() != 1 || results.getFirst().isEmpty()) {
            return Optional.empty();
        }

        ItemStack result = results.getFirst();
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(result.getItem());
        String normalizedOutput = outputId.toString();
        if (!outputWhitelist.contains(normalizedOutput)) {
            return Optional.empty();
        }

        String selector = kind + ":" + normalizedOutput;
        return Optional.of(new Selection(entry, selector, normalizedOutput, result.copy()));
    }

    private static String displayKind(RecipeDisplay display, CraftingMenu menu) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= menu.getGridWidth()
                    && shaped.height() <= menu.getGridHeight()
                    ? "shaped"
                    : null;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() <= menu.getGridWidth() * menu.getGridHeight()
                    ? "shapeless"
                    : null;
        }
        return null;
    }

    record Selection(
            RecipeDisplayEntry entry,
            String selector,
            String outputId,
            ItemStack expectedResult
    ) {
    }
}
