package dev.sealedclient.v26.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded 26.2 client recipe-book selector with dual whitelisting.
 */
final class AutoCraftRecipeSelector26 {
    static final int MAXIMUM_RECIPE_ENTRIES_SCANNED = 4_096;

    private AutoCraftRecipeSelector26() {
    }

    static Optional<Selection> choose(
            Minecraft client,
            CraftingMenu menu,
            Set<String> recipeWhitelist,
            Set<String> outputWhitelist
    ) {
        Objects.requireNonNull(recipeWhitelist, "recipeWhitelist");
        Objects.requireNonNull(outputWhitelist, "outputWhitelist");
        if (client == null
                || client.player == null
                || client.level == null
                || recipeWhitelist.isEmpty()
                || outputWhitelist.isEmpty()) {
            return Optional.empty();
        }

        StackedItemContents available = new StackedItemContents();
        client.player.getInventory().fillStackedContents(available);
        menu.fillCraftSlotsStackedContents(available);

        Map<Integer, RecipeDisplayEntry> unique = new LinkedHashMap<>();
        int inspected = 0;
        outer:
        for (RecipeCollection collection :
                client.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                unique.putIfAbsent(entry.id().index(), entry);
                inspected++;
                if (inspected >= MAXIMUM_RECIPE_ENTRIES_SCANNED) {
                    break outer;
                }
            }
        }

        Map<String, List<Selection>> candidates = new HashMap<>();
        for (RecipeDisplayEntry entry : unique.values()) {
            createSelection(
                    client,
                    menu,
                    entry,
                    available,
                    recipeWhitelist,
                    outputWhitelist
            ).ifPresent(selection -> candidates
                    .computeIfAbsent(
                            selection.selector(),
                            ignored -> new ArrayList<>()
                    )
                    .add(selection));
        }

        return recipeWhitelist.stream()
                .sorted()
                .map(candidates::get)
                .filter(matches ->
                        matches != null && matches.size() == 1
                )
                .map(List::getFirst)
                .min(Comparator
                        .comparing(Selection::selector)
                        .thenComparingInt(
                                selection -> selection.entry()
                                        .id()
                                        .index()
                        ));
    }

    static boolean allowed(
            String selector,
            String outputId,
            Set<String> recipeWhitelist,
            Set<String> outputWhitelist
    ) {
        if (selector == null || outputId == null) {
            return false;
        }
        return Objects.requireNonNull(
                recipeWhitelist,
                "recipeWhitelist"
        ).contains(selector)
                && Objects.requireNonNull(
                outputWhitelist,
                "outputWhitelist"
        ).contains(outputId);
    }

    private static Optional<Selection> createSelection(
            Minecraft client,
            CraftingMenu menu,
            RecipeDisplayEntry entry,
            StackedItemContents available,
            Set<String> recipeWhitelist,
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
            results = entry.resultItems(
                    SlotDisplayContext.fromLevel(client.level)
            );
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (results.size() != 1 || results.getFirst().isEmpty()) {
            return Optional.empty();
        }

        ItemStack result = results.getFirst();
        Identifier outputIdentifier = BuiltInRegistries.ITEM.getKey(
                result.getItem()
        );
        String outputId = outputIdentifier.toString();
        String selector = kind + ":" + outputId;
        if (!allowed(
                selector,
                outputId,
                recipeWhitelist,
                outputWhitelist
        )) {
            return Optional.empty();
        }
        AutoCraftDecisionEngine26.Candidate candidate =
                new AutoCraftDecisionEngine26.Candidate(
                        selector,
                        outputId,
                        resultToken(result),
                        result.getCount()
                );
        return Optional.of(new Selection(
                entry,
                selector,
                outputId,
                result.copy(),
                candidate
        ));
    }

    private static String displayKind(
            RecipeDisplay display,
            CraftingMenu menu
    ) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= menu.getGridWidth()
                    && shaped.height() <= menu.getGridHeight()
                    ? "shaped"
                    : null;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size()
                    <= menu.getGridWidth() * menu.getGridHeight()
                    ? "shapeless"
                    : null;
        }
        return null;
    }

    static String resultToken(ItemStack stack) {
        return Integer.toUnsignedString(
                ItemStack.hashItemAndComponents(stack),
                16
        ) + ":" + stack.getCount();
    }

    record Selection(
            RecipeDisplayEntry entry,
            String selector,
            String outputId,
            ItemStack expectedResult,
            AutoCraftDecisionEngine26.Candidate candidate
    ) {
    }
}
