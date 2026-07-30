package dev.b2tclient.v26.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Confirmed, bounded Minecraft 26.2 crafting-table automation.
 */
public final class AutoCraftAutomation26 {
    public static final String OWNER = "auto_craft";
    public static final int PRIORITY = 10;
    public static final Set<UtilityActionArbiter26.Channel> CHANNELS =
            Set.of(UtilityActionArbiter26.Channel.INVENTORY);
    public static final Configuration DEFAULT_CONFIGURATION =
            new Configuration(
                    Set.of(),
                    Set.of(),
                    10,
                    8,
                    8,
                    40,
                    16,
                    100,
                    2
            );

    private final AutoCraftDecisionEngine26 engine;
    private Configuration configuration;
    private UtilityActionBudget26 actionBudget;
    private LocalPlayer observedPlayer;
    private Object observedConnection;
    private Object observedLevel;
    private CraftingScreen observedScreen;
    private int observedContainerId = -1;
    private Object craftSessionIdentity;
    private AutoCraftRecipeSelector26.Selection pendingSelection;
    private PickupExpectation pickupExpectation;
    private PendingAction pendingAction;

    public AutoCraftAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public AutoCraftAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        engine = new AutoCraftDecisionEngine26(
                configuration.engineConfiguration()
        );
        actionBudget = configuration.newBudget();
    }

    public void setConfiguration(Configuration configuration) {
        Configuration requested = Objects.requireNonNull(
                configuration,
                "configuration"
        );
        boolean budgetChanged = !requested.sameBudget(this.configuration);
        this.configuration = requested;
        engine.setConfiguration(requested.engineConfiguration());
        if (budgetChanged) {
            actionBudget = requested.newBudget();
        }
    }

    public Configuration configuration() {
        return configuration;
    }

    public void submit(
            Minecraft client,
            boolean enabled,
            boolean safetyReady,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pendingAction = null;
        LocalPlayer player = client == null ? null : client.player;
        if (observeWorldSession(client, player)) {
            actionBudget.reset();
            resetCraftSession();
        }
        CraftingContext context = craftingContext(client, player);
        observeCraftScreen(context);

        AutoCraftRecipeSelector26.Selection candidateSelection = null;
        if (context != null
                && engine.snapshot().phase()
                == AutoCraftDecisionEngine26.Phase.IDLE
                && inputGridIsEmpty(context.menu())
                && context.menu().getResultSlot().getItem().isEmpty()) {
            candidateSelection = AutoCraftRecipeSelector26.choose(
                    client,
                    context.menu(),
                    configuration.recipeWhitelist(),
                    configuration.outputWhitelist()
            ).orElse(null);
        }

        ItemStack result = context == null
                ? ItemStack.EMPTY
                : context.menu().getResultSlot().getItem();
        boolean resultMatches = pendingSelection != null
                && exactStack(
                result,
                pendingSelection.expectedResult()
        );
        PickupAssessment pickup = pickupAssessment(
                client,
                player,
                context,
                safetyReady
        );
        int outputTarget = context == null
                ? -1
                : findEmptyHotbarSlot(player);
        AutoCraftDecisionEngine26.Decision decision = engine.step(
                new AutoCraftDecisionEngine26.Observation(
                        craftSessionIdentity,
                        enabled,
                        sessionReady(client, player),
                        safetyReady,
                        context != null,
                        player != null
                                && player.isAlive()
                                && !player.isSpectator(),
                        context != null
                                && context.menu().getCarried().isEmpty(),
                        context != null
                                && inputGridIsEmpty(context.menu()),
                        !result.isEmpty(),
                        resultMatches,
                        outputTarget >= 0,
                        pickup == PickupAssessment.CONFIRMED,
                        pickup == PickupAssessment.INVALIDATED,
                        candidateSelection == null
                                ? null
                                : candidateSelection.candidate()
                )
        );
        synchronizePendingReferences();
        if (!decision.apply()
                || context == null
                || !actionBudget.canAcquire(tick(player))) {
            return;
        }

        AutoCraftRecipeSelector26.Selection actionSelection =
                decision.action()
                        == AutoCraftDecisionEngine26.Action.PLACE_RECIPE
                        ? candidateSelection
                        : pendingSelection;
        if (actionSelection == null
                || !Objects.equals(
                actionSelection.candidate(),
                decision.candidate()
        )) {
            return;
        }
        pendingAction = new PendingAction(
                decision,
                context.screen(),
                context.menu(),
                actionSelection,
                outputTarget,
                tick(player)
        );
        arbiter.submit(OWNER, PRIORITY, CHANNELS);
    }

    public boolean execute(
            Minecraft client,
            UtilityActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        PendingAction plan = pendingAction;
        pendingAction = null;
        if (plan == null
                || !arbiter.ownsAll(OWNER, CHANNELS)
                || !basePlanStillValid(client, plan)) {
            if (plan != null) {
                engine.commit(plan.decision(), false);
            }
            return false;
        }

        boolean applied = switch (plan.decision().action()) {
            case PLACE_RECIPE -> executePlace(client, plan);
            case PICKUP_OUTPUT -> executePickup(client, plan);
            case NONE -> false;
        };
        engine.commit(plan.decision(), applied);
        if (applied && plan.decision().action()
                == AutoCraftDecisionEngine26.Action.PLACE_RECIPE) {
            pendingSelection = plan.selection();
        }
        return applied;
    }

    public void release() {
        pendingAction = null;
        actionBudget.reset();
        observedPlayer = null;
        observedConnection = null;
        observedLevel = null;
        resetCraftSession();
    }

    public Status status(long tick) {
        return new Status(
                engine.snapshot(),
                actionBudget.snapshot(Math.max(0L, tick)),
                pendingSelection != null,
                pickupExpectation != null
        );
    }

    private boolean executePlace(
            Minecraft client,
            PendingAction plan
    ) {
        CraftingMenu menu = plan.menu();
        if (!menu.getCarried().isEmpty()
                || !inputGridIsEmpty(menu)
                || !menu.getResultSlot().getItem().isEmpty()
                || !whitelisted(plan.selection())
                || !actionBudget.acquire(plan.tick())) {
            return false;
        }
        client.gameMode.handlePlaceRecipe(
                menu.containerId,
                plan.selection().entry().id(),
                false
        );
        return true;
    }

    private boolean executePickup(
            Minecraft client,
            PendingAction plan
    ) {
        CraftingMenu menu = plan.menu();
        ItemStack result = menu.getResultSlot().getItem();
        int resultSlot = menu.slots.indexOf(menu.getResultSlot());
        if (!menu.getCarried().isEmpty()
                || resultSlot < 0
                || plan.outputHotbarSlot() < 0
                || plan.outputHotbarSlot() > 8
                || !client.player.getInventory()
                .getItem(plan.outputHotbarSlot()).isEmpty()
                || !menu.getResultSlot().mayPickup(client.player)
                || !exactStack(
                result,
                plan.selection().expectedResult()
        )
                || !whitelisted(plan.selection())
                || !actionBudget.acquire(plan.tick())) {
            return false;
        }
        client.gameMode.handleContainerInput(
                menu.containerId,
                resultSlot,
                plan.outputHotbarSlot(),
                ContainerInput.SWAP,
                client.player
        );
        pickupExpectation = new PickupExpectation(
                menu,
                plan.outputHotbarSlot(),
                playerStack(client.player, plan.outputHotbarSlot()),
                menu.getResultSlot().getItem().copy(),
                inputGridSnapshot(menu),
                confirmationStabilityTicks(
                        latencyMillis(client),
                        configuration.engineConfiguration()
                                .confirmationTimeoutTicks()
                ),
                0,
                !exactStack(
                        client.player.getInventory().getItem(
                                plan.outputHotbarSlot()
                        ),
                        plan.selection().expectedResult()
                )
                        || !menu.getResultSlot().getItem().isEmpty()
                        || !inputGridIsEmpty(menu)
        );
        return true;
    }

    private boolean basePlanStillValid(
            Minecraft client,
            PendingAction plan
    ) {
        LocalPlayer player = client == null ? null : client.player;
        CraftingContext current = craftingContext(client, player);
        return current != null
                && current.screen() == plan.screen()
                && current.menu() == plan.menu()
                && player == observedPlayer
                && current.menu().containerId == observedContainerId
                && current.menu().getCarried().isEmpty();
    }

    private boolean observeWorldSession(
            Minecraft client,
            LocalPlayer player
    ) {
        Object connection = client == null
                ? null
                : client.getConnection();
        Object level = client == null ? null : client.level;
        if (player == observedPlayer
                && connection == observedConnection
                && level == observedLevel) {
            return false;
        }
        observedPlayer = player;
        observedConnection = connection;
        observedLevel = level;
        return true;
    }

    private void observeCraftScreen(CraftingContext context) {
        CraftingScreen screen = context == null
                ? null
                : context.screen();
        int containerId = context == null
                ? -1
                : context.menu().containerId;
        if (screen == observedScreen
                && containerId == observedContainerId) {
            return;
        }
        observedScreen = screen;
        observedContainerId = containerId;
        craftSessionIdentity = context == null ? null : new Object();
        pendingSelection = null;
        pickupExpectation = null;
        pendingAction = null;
    }

    private void synchronizePendingReferences() {
        AutoCraftDecisionEngine26.Snapshot snapshot = engine.snapshot();
        if (snapshot.pendingCandidate() == null) {
            pendingSelection = null;
            pickupExpectation = null;
        }
    }

    private PickupAssessment pickupAssessment(
            Minecraft client,
            LocalPlayer player,
            CraftingContext context,
            boolean safetyReady
    ) {
        PickupExpectation expectation = pickupExpectation;
        if (expectation == null
                || player == null
                || context == null
                || context.menu() != expectation.menu()
                || expectation.hotbarSlot() < 0
                || expectation.hotbarSlot() > 8) {
            return PickupAssessment.WAITING;
        }
        boolean stateMatches = !expectation.invalidated()
                && sameStack(
                player.getInventory().getItem(expectation.hotbarSlot()),
                expectation.expectedTarget()
        )
                && sameStack(
                context.menu().getResultSlot().getItem(),
                expectation.expectedResult()
        )
                && sameStacks(
                inputGridSnapshot(context.menu()),
                expectation.expectedGrid()
        );
        int stable = nextStableObservationCount(
                expectation.stableObservations(),
                expectation.requiredStableObservations(),
                stateMatches,
                safetyReady
        );
        if (stable < 0) {
            pickupExpectation = expectation.invalidatedCopy();
            return PickupAssessment.INVALIDATED;
        }
        if (stable >= expectation.requiredStableObservations()) {
            pickupExpectation = expectation.withStableObservations(stable);
            return PickupAssessment.CONFIRMED;
        }
        if (stable == expectation.stableObservations()) {
            return PickupAssessment.WAITING;
        }
        pickupExpectation = expectation.withStableObservations(stable);
        return PickupAssessment.WAITING;
    }

    static int confirmationStabilityTicks(
            int latencyMillis,
            int confirmationTimeoutTicks
    ) {
        if (latencyMillis < -1) {
            throw new IllegalArgumentException(
                    "Latency cannot be below -1"
            );
        }
        if (confirmationTimeoutTicks < 1) {
            throw new IllegalArgumentException(
                    "Confirmation timeout must be positive"
            );
        }
        int roundTripTicks = latencyMillis < 0
                ? 20
                : Math.max(1, (latencyMillis + 49) / 50);
        int buffered = Math.max(8, Math.min(40, roundTripTicks + 4));
        return Math.min(confirmationTimeoutTicks, buffered);
    }

    static int nextStableObservationCount(
            int current,
            int required,
            boolean stateMatches,
            boolean safetyReady
    ) {
        if (current < 0 || required < 1 || current > required) {
            throw new IllegalArgumentException(
                    "Invalid stable observation state"
            );
        }
        if (!stateMatches) {
            return -1;
        }
        if (!safetyReady) {
            return current;
        }
        return Math.min(required, current + 1);
    }

    private boolean whitelisted(
            AutoCraftRecipeSelector26.Selection selection
    ) {
        return AutoCraftRecipeSelector26.allowed(
                selection.selector(),
                selection.outputId(),
                configuration.recipeWhitelist(),
                configuration.outputWhitelist()
        );
    }

    private static CraftingContext craftingContext(
            Minecraft client,
            LocalPlayer player
    ) {
        if (!sessionReady(client, player)
                || !(client.gui.screen()
                instanceof CraftingScreen screen)) {
            return null;
        }
        CraftingMenu menu = screen.getMenu();
        if (player.containerMenu != menu
                || !menu.stillValid(player)) {
            return null;
        }
        return new CraftingContext(screen, menu);
    }

    private static boolean sessionReady(
            Minecraft client,
            LocalPlayer player
    ) {
        return client != null
                && player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null;
    }

    private static boolean inputGridIsEmpty(CraftingMenu menu) {
        return menu.getInputGridSlots().stream().noneMatch(Slot::hasItem);
    }

    private static List<ItemStack> inputGridSnapshot(CraftingMenu menu) {
        return menu.getInputGridSlots().stream()
                .map(slot -> slot.getItem().copy())
                .toList();
    }

    private static ItemStack playerStack(
            LocalPlayer player,
            int slot
    ) {
        return player == null || slot < 0 || slot > 8
                ? ItemStack.EMPTY
                : player.getInventory().getItem(slot).copy();
    }

    private static boolean sameStacks(
            List<ItemStack> actual,
            List<ItemStack> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            if (!sameStack(actual.get(index), expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameStack(
            ItemStack actual,
            ItemStack expected
    ) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual.isEmpty() || expected.isEmpty()) {
            return actual.isEmpty() && expected.isEmpty();
        }
        return actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameComponents(actual, expected);
    }

    private static int latencyMillis(Minecraft client) {
        if (client == null
                || client.player == null
                || client.getConnection() == null) {
            return -1;
        }
        var info = client.getConnection().getPlayerInfo(
                client.player.getUUID()
        );
        return info == null ? -1 : Math.max(-1, info.getLatency());
    }

    private int findEmptyHotbarSlot(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        for (int offset = 0; offset < 9; offset++) {
            int slot = (configuration.preferredHotbarSlot() + offset) % 9;
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean exactStack(
            ItemStack actual,
            ItemStack expected
    ) {
        return actual != null
                && expected != null
                && !actual.isEmpty()
                && actual.getCount() == expected.getCount()
                && ItemStack.isSameItemSameComponents(actual, expected);
    }

    private static long tick(LocalPlayer player) {
        return player == null ? 0L : Math.max(0, player.tickCount);
    }

    private void resetCraftSession() {
        observedScreen = null;
        observedContainerId = -1;
        craftSessionIdentity = null;
        pendingSelection = null;
        pickupExpectation = null;
        pendingAction = null;
        engine.reset();
    }

    public record Configuration(
            Set<String> recipeWhitelist,
            Set<String> outputWhitelist,
            int actionDelayTicks,
            int maximumCrafts,
            int preferredHotbarSlot,
            int confirmationTimeoutTicks,
            int maximumActionsPerWindow,
            int actionWindowTicks,
            int minimumActionSpacingTicks
    ) {
        public Configuration {
            recipeWhitelist = normalized(recipeWhitelist, "recipeWhitelist");
            outputWhitelist = normalized(outputWhitelist, "outputWhitelist");
            if (preferredHotbarSlot < 0 || preferredHotbarSlot > 8) {
                throw new IllegalArgumentException(
                        "Preferred hotbar slot must be in [0, 8]"
                );
            }
            new AutoCraftDecisionEngine26.Configuration(
                    actionDelayTicks,
                    maximumCrafts,
                    confirmationTimeoutTicks
            );
            new UtilityActionBudget26(
                    maximumActionsPerWindow,
                    actionWindowTicks,
                    minimumActionSpacingTicks
            );
        }

        AutoCraftDecisionEngine26.Configuration engineConfiguration() {
            return new AutoCraftDecisionEngine26.Configuration(
                    actionDelayTicks,
                    maximumCrafts,
                    confirmationTimeoutTicks
            );
        }

        UtilityActionBudget26 newBudget() {
            return new UtilityActionBudget26(
                    maximumActionsPerWindow,
                    actionWindowTicks,
                    minimumActionSpacingTicks
            );
        }

        boolean sameBudget(Configuration other) {
            return maximumActionsPerWindow
                    == other.maximumActionsPerWindow
                    && actionWindowTicks == other.actionWindowTicks
                    && minimumActionSpacingTicks
                    == other.minimumActionSpacingTicks;
        }

        private static Set<String> normalized(
                Set<String> source,
                String name
        ) {
            Objects.requireNonNull(source, name);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : source) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                            name + " cannot contain blank entries"
                    );
                }
                normalized.add(value.trim());
            }
            if (normalized.size() > 256) {
                throw new IllegalArgumentException(
                        name + " cannot exceed 256 entries"
                );
            }
            return Set.copyOf(normalized);
        }
    }

    private record CraftingContext(
            CraftingScreen screen,
            CraftingMenu menu
    ) {
    }

    private record PendingAction(
            AutoCraftDecisionEngine26.Decision decision,
            CraftingScreen screen,
            CraftingMenu menu,
            AutoCraftRecipeSelector26.Selection selection,
            int outputHotbarSlot,
            long tick
    ) {
    }

    private record PickupExpectation(
            CraftingMenu menu,
            int hotbarSlot,
            ItemStack expectedTarget,
            ItemStack expectedResult,
            List<ItemStack> expectedGrid,
            int requiredStableObservations,
            int stableObservations,
            boolean invalidated
    ) {
        private PickupExpectation {
            expectedTarget = expectedTarget.copy();
            expectedResult = expectedResult.copy();
            expectedGrid = expectedGrid.stream()
                    .map(ItemStack::copy)
                    .toList();
            if (requiredStableObservations < 1) {
                throw new IllegalArgumentException(
                        "Stable observation count must be positive"
                );
            }
        }

        PickupExpectation withStableObservations(int observations) {
            return new PickupExpectation(
                    menu,
                    hotbarSlot,
                    expectedTarget,
                    expectedResult,
                    expectedGrid,
                    requiredStableObservations,
                    observations,
                    invalidated
            );
        }

        PickupExpectation invalidatedCopy() {
            return new PickupExpectation(
                    menu,
                    hotbarSlot,
                    expectedTarget,
                    expectedResult,
                    expectedGrid,
                    requiredStableObservations,
                    stableObservations,
                    true
            );
        }
    }

    private enum PickupAssessment {
        WAITING,
        CONFIRMED,
        INVALIDATED
    }

    public record Status(
            AutoCraftDecisionEngine26.Snapshot engine,
            UtilityActionBudget26.Snapshot actionBudget,
            boolean expectedRecipeTracked,
            boolean pickupConfirmationTracked
    ) {
    }
}
