package dev.b2tclient.module.utility;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;
import dev.b2tclient.core.TickableModule;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.core.setting.StringListSetting;
import dev.b2tclient.service.ActionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Conservative crafting-table automation using the vanilla recipe-book route.
 *
 * <p>Every placement and result pickup is a separate, delayed action. Results
 * are swapped directly into an empty hotbar slot so the module never controls
 * the cursor and can be disabled without leaving a carried stack behind.</p>
 */
public final class AutoCraftModule extends Module implements TickableModule {
    private static final String OWNER = "auto_craft";
    private static final int ACTION_PRIORITY = 10;

    private final StringListSetting recipes = addSetting(new StringListSetting(
            "recipes",
            "Recipe Whitelist",
            "Allowed display selectors, such as shaped:minecraft:ender_chest.",
            List.of()
    ));
    private final StringListSetting outputs = addSetting(new StringListSetting(
            "outputs",
            "Output Whitelist",
            "Allowed output item IDs. Both recipe and output must be allowed.",
            List.of()
    ));
    private final IntegerSetting delay = addSetting(new IntegerSetting(
            "delay",
            "Action Delay",
            "Ticks between recipe placement and result pickup actions.",
            10,
            2,
            100,
            1
    ));
    private final IntegerSetting maximumCrafts = addSetting(new IntegerSetting(
            "maximum_crafts",
            "Maximum Crafts",
            "Maximum completed crafts during one crafting-table screen session.",
            8,
            1,
            64,
            1
    ));
    private final IntegerSetting preferredHotbarSlot = addSetting(new IntegerSetting(
            "preferred_hotbar_slot",
            "Preferred Hotbar Slot",
            "First empty hotbar slot to use for crafted output.",
            9,
            1,
            9,
            1
    ));

    private final ActionCoordinator actions;
    private CraftingScreen sessionScreen;
    private int sessionContainerId = -1;
    private int cooldown;
    private int completedCrafts;
    private int pendingAge;
    private AutoCraftRecipeSelector.Selection pending;

    public AutoCraftModule(ActionCoordinator actions) {
        super(
                "auto_craft",
                "Auto Craft",
                "Crafts explicitly whitelisted recipe-book outputs at a safe bounded rate.",
                Category.UTILITY,
                false,
                ModuleRisk.AUTOMATION
        );
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public void onTick(Minecraft minecraft) {
        CraftingContext context = validContext(minecraft);
        if (context == null) {
            resetSession(minecraft);
            return;
        }

        if (sessionScreen != context.screen()
                || sessionContainerId != context.menu().containerId) {
            beginSession(minecraft, context);
        }

        if (cooldown > 0) {
            cooldown--;
        }
        if (pending != null) {
            pendingAge++;
            if (pendingAge > Math.max(40, delay.get() * 4)) {
                pending = null;
                pendingAge = 0;
                return;
            }
        }
        if (cooldown > 0) {
            return;
        }

        if (pending != null) {
            takeExpectedResult(minecraft, context.menu());
            return;
        }
        if (completedCrafts >= maximumCrafts.get()
                || !context.menu().getResultSlot().getItem().isEmpty()
                || !inputGridIsEmpty(context.menu())) {
            return;
        }

        AutoCraftRecipeSelector.choose(
                        minecraft,
                        context.menu(),
                        recipes,
                        outputs
                )
                .ifPresent(selection -> placeRecipe(minecraft, context.menu(), selection));
    }

    private void placeRecipe(
            Minecraft minecraft,
            CraftingMenu menu,
            AutoCraftRecipeSelector.Selection selection
    ) {
        if (!actions.claim(
                ActionCoordinator.Channel.INVENTORY,
                OWNER,
                ACTION_PRIORITY,
                1
        )) {
            return;
        }

        // Recheck all mutable state immediately before the single network action.
        if (minecraft.gameMode == null
                || minecraft.player == null
                || minecraft.player.containerMenu != menu
                || !menu.getCarried().isEmpty()
                || !menu.getResultSlot().getItem().isEmpty()
                || !inputGridIsEmpty(menu)) {
            actions.releaseOwner(minecraft, OWNER);
            return;
        }

        minecraft.gameMode.handlePlaceRecipe(
                menu.containerId,
                selection.entry().id(),
                false
        );
        pending = selection;
        pendingAge = 0;
        cooldown = delay.get();
    }

    private void takeExpectedResult(Minecraft minecraft, CraftingMenu menu) {
        ItemStack result = menu.getResultSlot().getItem();
        if (result.isEmpty()) {
            return;
        }
        if (!outputs.contains(pending.outputId())
                || !recipes.contains(pending.selector())
                || !ItemStack.isSameItemSameComponents(result, pending.expectedResult())
                || result.getCount() != pending.expectedResult().getCount()) {
            pending = null;
            pendingAge = 0;
            return;
        }

        int hotbarSlot = findEmptyHotbarSlot(minecraft);
        int resultMenuSlot = menu.slots.indexOf(menu.getResultSlot());
        if (hotbarSlot < 0
                || resultMenuSlot < 0
                || minecraft.gameMode == null
                || minecraft.player == null
                || !menu.getCarried().isEmpty()
                || !menu.getResultSlot().mayPickup(minecraft.player)
                || !actions.claim(
                        ActionCoordinator.Channel.INVENTORY,
                        OWNER,
                        ACTION_PRIORITY,
                        1
                )) {
            return;
        }

        minecraft.gameMode.handleInventoryMouseClick(
                menu.containerId,
                resultMenuSlot,
                hotbarSlot,
                ClickType.SWAP,
                minecraft.player
        );
        completedCrafts++;
        pending = null;
        pendingAge = 0;
        cooldown = delay.get();
    }

    private int findEmptyHotbarSlot(Minecraft minecraft) {
        int preferred = preferredHotbarSlot.get() - 1;
        for (int offset = 0; offset < 9; offset++) {
            int index = (preferred + offset) % 9;
            if (minecraft.player.getInventory().getItem(index).isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private static boolean inputGridIsEmpty(CraftingMenu menu) {
        return menu.getInputGridSlots().stream().noneMatch(Slot::hasItem);
    }

    private static CraftingContext validContext(Minecraft minecraft) {
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.gameMode == null
                || !(minecraft.screen instanceof CraftingScreen screen)) {
            return null;
        }

        CraftingMenu menu = screen.getMenu();
        if (minecraft.player.containerMenu != menu
                || !menu.stillValid(minecraft.player)
                || !menu.getCarried().isEmpty()) {
            return null;
        }
        return new CraftingContext(screen, menu);
    }

    private void beginSession(Minecraft minecraft, CraftingContext context) {
        actions.releaseOwner(minecraft, OWNER);
        sessionScreen = context.screen();
        sessionContainerId = context.menu().containerId;
        cooldown = 0;
        completedCrafts = 0;
        pendingAge = 0;
        pending = null;
    }

    private void resetSession(Minecraft minecraft) {
        if (sessionScreen == null && sessionContainerId == -1 && pending == null) {
            return;
        }
        actions.releaseOwner(minecraft, OWNER);
        sessionScreen = null;
        sessionContainerId = -1;
        cooldown = 0;
        completedCrafts = 0;
        pendingAge = 0;
        pending = null;
    }

    @Override
    protected void onDisable(Minecraft minecraft) {
        resetSession(minecraft);
    }

    private record CraftingContext(CraftingScreen screen, CraftingMenu menu) {
    }
}
