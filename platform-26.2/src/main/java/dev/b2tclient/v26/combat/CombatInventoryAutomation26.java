package dev.b2tclient.v26.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Conservative Minecraft 26.2 inventory and hotbar combat automation.
 *
 * <p>The service is deliberately split into {@link #submit} and
 * {@link #execute}. The first phase only evaluates immutable action plans and
 * submits their channel claims. The second phase revalidates the live session
 * and inventory before performing a mutation, and only runs an action whose
 * complete channel set was awarded by {@link CombatActionArbiter26}.</p>
 *
 * <p>At most one offhand swap and one hotbar mutation can be performed per
 * tick. Auto Totem has priority over Offhand; Anti Weakness has priority over
 * Auto Weapon. Hotbar state is restored only while the service still owns the
 * exact slot it selected, so a manual scroll is never overwritten.</p>
 */
public final class CombatInventoryAutomation26 {
    public static final Configuration DEFAULT_CONFIGURATION = new Configuration(
            20.0F,
            16.0F,
            OffhandItem.END_CRYSTAL,
            true,
            true,
            3,
            true,
            3,
            3
    );

    private static final String AUTO_TOTEM_OWNER = "auto_totem";
    private static final String OFFHAND_OWNER = "offhand";
    private static final String ANTI_WEAKNESS_OWNER = "anti_weakness";
    private static final String AUTO_WEAPON_OWNER = "auto_weapon";

    private static final int AUTO_TOTEM_PRIORITY = 100;
    private static final int OFFHAND_PRIORITY = 90;
    private static final int ANTI_WEAKNESS_PRIORITY = 85;
    private static final int AUTO_WEAPON_PRIORITY = 55;
    private static final Set<CombatActionArbiter26.Channel> INVENTORY_CHANNEL =
            Set.of(
                    CombatActionArbiter26.Channel.INVENTORY,
                    CombatActionArbiter26.Channel.HOTBAR,
                    CombatActionArbiter26.Channel.USE
            );
    private static final Set<CombatActionArbiter26.Channel> HOTBAR_CHANNEL =
            Set.of(CombatActionArbiter26.Channel.HOTBAR);

    private final HotbarLease hotbarLease = new HotbarLease();
    private final WeaponMetrics weaponMetrics = new WeaponMetrics();
    private final InventoryCooldowns inventoryCooldowns =
            new InventoryCooldowns();

    private Configuration configuration;
    private LocalPlayer observedPlayer;
    private InventoryPlan pendingInventory;
    private HotbarPlan pendingHotbar;

    public CombatInventoryAutomation26() {
        this(DEFAULT_CONFIGURATION);
    }

    public CombatInventoryAutomation26(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public Configuration configuration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Evaluates this tick and submits all required arbiter claims without
     * changing Minecraft state.
     */
    public void submit(
            Minecraft client,
            boolean autoTotemEnabled,
            boolean offhandEnabled,
            boolean antiWeaknessEnabled,
            boolean autoWeaponEnabled,
            CombatActionArbiter26 arbiter
    ) {
        Objects.requireNonNull(arbiter, "arbiter");
        pendingInventory = null;
        pendingHotbar = null;
        inventoryCooldowns.advance();

        LocalPlayer player = client == null ? null : client.player;
        if (player != observedPlayer) {
            observedPlayer = player;
            hotbarLease.abandon();
            inventoryCooldowns.clear();
        }
        if (!sessionAllowsAutomation(client)) {
            return;
        }

        pendingInventory = planInventory(
                client,
                autoTotemEnabled,
                offhandEnabled
        );
        if (pendingInventory != null) {
            arbiter.submit(
                    pendingInventory.owner(),
                    pendingInventory.priority(),
                    INVENTORY_CHANNEL
            );
        }

        pendingHotbar = planHotbar(
                client,
                antiWeaknessEnabled,
                autoWeaponEnabled
        );
        if (pendingHotbar != null) {
            arbiter.submit(
                    pendingHotbar.owner(),
                    pendingHotbar.priority(),
                    HOTBAR_CHANNEL
            );
        }
    }

    /**
     * Applies previously submitted plans after the shared arbiter resolves.
     */
    public void execute(Minecraft client, CombatActionArbiter26 arbiter) {
        Objects.requireNonNull(arbiter, "arbiter");
        if (!sessionAllowsAutomation(client) || client.player != observedPlayer) {
            pendingInventory = null;
            pendingHotbar = null;
            return;
        }

        InventoryPlan inventoryPlan = pendingInventory;
        pendingInventory = null;
        if (inventoryPlan != null
                && arbiter.ownsAll(inventoryPlan.owner(), INVENTORY_CHANNEL)
                && inventoryPlanStillValid(client, inventoryPlan)) {
            int menuSlot = inventoryIndexToMenuSlot(inventoryPlan.inventorySlot());
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId,
                    menuSlot,
                    net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                    ContainerInput.SWAP,
                    client.player
            );
            inventoryCooldowns.start(
                    inventoryPlan.action(),
                    inventoryPlan.cooldownTicks()
            );
        }

        HotbarPlan hotbarPlan = pendingHotbar;
        pendingHotbar = null;
        if (hotbarPlan == null
                || !arbiter.ownsAll(hotbarPlan.owner(), HOTBAR_CHANNEL)
                || !validHotbarSlot(hotbarPlan.slot())
                || client.player.getInventory().getSelectedSlot()
                != hotbarPlan.expectedCurrentSlot()) {
            return;
        }
        if (hotbarPlan.restore()) {
            client.player.getInventory().setSelectedSlot(hotbarPlan.slot());
            hotbarLease.commitRestore();
        } else {
            client.player.getInventory().setSelectedSlot(hotbarPlan.slot());
            hotbarLease.commitSwitch(
                    hotbarPlan.expectedCurrentSlot(),
                    hotbarPlan.slot(),
                    hotbarPlan.owner()
            );
        }
    }

    /**
     * Releases tick-local state and restores a service-owned hotbar selection
     * when the exact applied slot is still selected.
     */
    public void release(Minecraft client) {
        pendingInventory = null;
        pendingHotbar = null;
        inventoryCooldowns.clear();
        LocalPlayer currentPlayer = client == null ? null : client.player;
        if (canRestoreHotbar(currentPlayer, observedPlayer)) {
            int current = currentPlayer.getInventory().getSelectedSlot();
            int restore = hotbarLease.restoreSlotIfOwned(current);
            if (validHotbarSlot(restore) && restore != current) {
                currentPlayer.getInventory().setSelectedSlot(restore);
            }
        }
        hotbarLease.abandon();
        observedPlayer = null;
    }

    static boolean canRestoreHotbar(
            LocalPlayer currentPlayer,
            LocalPlayer observedPlayer
    ) {
        return currentPlayer != null && currentPlayer == observedPlayer;
    }

    private InventoryPlan planInventory(
            Minecraft client,
            boolean autoTotemEnabled,
            boolean offhandEnabled
    ) {
        if (!inventoryReady(client)) {
            return null;
        }

        float effectiveHealth = client.player.getHealth()
                + client.player.getAbsorptionAmount();
        DesiredOffhand desired = desiredOffhand(
                autoTotemEnabled,
                offhandEnabled,
                effectiveHealth,
                configuration
        );
        if (desired == null) {
            return null;
        }
        if (!inventoryCooldowns.ready(desired.action())) {
            return null;
        }

        Item desiredItem = desired.item().item();
        ItemStack held = client.player.getOffhandItem();
        if (held.getItem() == desiredItem
                || (!held.isEmpty() && !desired.replaceOffhand())) {
            return null;
        }

        List<ItemCandidate> candidates = new ArrayList<>();
        int selected = client.player.getInventory().getSelectedSlot();
        int inventorySize = Math.min(
                net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE,
                client.player.getInventory().getNonEquipmentItems().size()
        );
        for (int slot = 0; slot < inventorySize; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.getItem() == desiredItem && !stack.isEmpty()) {
                candidates.add(new ItemCandidate(
                        slot,
                        stack.getCount(),
                        slot == selected,
                        true
                ));
            }
        }
        int source = selectInventorySource(candidates);
        return source < 0
                ? null
                : new InventoryPlan(
                        desired.owner(),
                        desired.priority(),
                        source,
                        desired.item(),
                        desired.action(),
                        desired.replaceOffhand(),
                        desired.cooldownTicks()
                );
    }

    private HotbarPlan planHotbar(
            Minecraft client,
            boolean antiWeaknessEnabled,
            boolean autoWeaponEnabled
    ) {
        boolean attackTargeted = client.options.keyAttack.isDown()
                && client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity living
                && living.isAlive()
                && !living.isDeadOrDying();
        boolean weaknessRequested = attackTargeted
                && antiWeaknessEnabled
                && client.player.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
        boolean weaponRequested = attackTargeted
                && (weaknessRequested || autoWeaponEnabled);

        int selected = client.player.getInventory().getSelectedSlot();
        if (!weaponRequested) {
            return hotbarLease.previewRestore(selected);
        }

        List<WeaponCandidate> candidates = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            candidates.add(weaponCandidate(
                    client.player.getInventory().getItem(slot),
                    slot
            ));
        }
        int best = selectBestWeapon(
                candidates,
                selected,
                configuration.minimumWeaponDurability()
        );
        if (best < 0) {
            return hotbarLease.previewRestore(selected);
        }

        String owner = weaknessRequested
                ? ANTI_WEAKNESS_OWNER
                : AUTO_WEAPON_OWNER;
        int priority = weaknessRequested
                ? ANTI_WEAKNESS_PRIORITY
                : AUTO_WEAPON_PRIORITY;
        return hotbarLease.previewSwitch(selected, best, owner, priority);
    }

    private WeaponCandidate weaponCandidate(ItemStack stack, int slot) {
        if (stack == null || stack.isEmpty()) {
            return new WeaponCandidate(slot, false, 0, 0.0, 0.0);
        }
        int remainingDurability = stack.isDamageableItem()
                ? stack.getMaxDamage() - stack.getDamageValue()
                : Integer.MAX_VALUE;
        weaponMetrics.reset();
        stack.forEachModifier(EquipmentSlot.MAINHAND, weaponMetrics);
        return new WeaponCandidate(
                slot,
                stack.has(DataComponents.WEAPON),
                remainingDurability,
                weaponMetrics.attackDamage(),
                weaponMetrics.attackSpeed()
        );
    }

    private boolean inventoryPlanStillValid(
            Minecraft client,
            InventoryPlan plan
    ) {
        if (!inventoryReady(client)
                || !inventoryCooldowns.ready(plan.action())
                || plan.inventorySlot() < 0
                || plan.inventorySlot()
                >= net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) {
            return false;
        }
        ItemStack source = client.player.getInventory().getItem(plan.inventorySlot());
        ItemStack held = client.player.getOffhandItem();
        return !source.isEmpty()
                && source.getItem() == plan.item().item()
                && held.getItem() != plan.item().item()
                && (held.isEmpty() || plan.replaceOffhand());
    }

    private static boolean sessionAllowsAutomation(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && client.getConnection() != null
                && client.gui.screen() == null
                && client.player.isAlive()
                && !client.player.isDeadOrDying()
                && !client.player.isSpectator();
    }

    private static boolean inventoryReady(Minecraft client) {
        return sessionAllowsAutomation(client)
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    static DesiredOffhand desiredOffhand(
            boolean autoTotemEnabled,
            boolean offhandEnabled,
            float effectiveHealth,
            Configuration configuration
    ) {
        if (configuration == null || !Float.isFinite(effectiveHealth)) {
            return null;
        }
        if (autoTotemEnabled
                && effectiveHealth <= configuration.autoTotemHealth()) {
            return new DesiredOffhand(
                    InventoryAction.AUTO_TOTEM,
                    AUTO_TOTEM_OWNER,
                    AUTO_TOTEM_PRIORITY,
                    OffhandItem.TOTEM,
                    configuration.autoTotemReplaceOffhand(),
                    configuration.autoTotemCooldownTicks()
            );
        }
        if (!offhandEnabled) {
            return null;
        }
        OffhandItem item = configuration.emergencyTotem()
                && effectiveHealth <= configuration.emergencyTotemHealth()
                ? OffhandItem.TOTEM
                : configuration.preferredOffhand();
        return new DesiredOffhand(
                InventoryAction.OFFHAND,
                OFFHAND_OWNER,
                OFFHAND_PRIORITY,
                item,
                configuration.offhandReplaceOffhand(),
                configuration.offhandCooldownTicks()
        );
    }

    static int selectInventorySource(List<ItemCandidate> candidates) {
        if (candidates == null) {
            return -1;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(ItemCandidate::valid)
                .filter(candidate -> candidate.slot() >= 0
                        && candidate.slot()
                        < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE)
                .filter(candidate -> candidate.count() > 0)
                .min(Comparator
                        .comparing(ItemCandidate::selected)
                        .thenComparing(candidate -> candidate.slot() < 9)
                        .thenComparing(
                                Comparator.comparingInt(ItemCandidate::count)
                                        .reversed()
                        )
                        .thenComparingInt(ItemCandidate::slot))
                .map(ItemCandidate::slot)
                .orElse(-1);
    }

    static int selectBestWeapon(
            List<WeaponCandidate> candidates,
            int selectedSlot,
            int minimumDurability
    ) {
        if (candidates == null || minimumDurability < 0) {
            return -1;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(WeaponCandidate::meleeWeapon)
                .filter(candidate -> validHotbarSlot(candidate.slot()))
                .filter(candidate -> candidate.remainingDurability() > minimumDurability)
                .filter(candidate -> Double.isFinite(candidate.attackDamage())
                        && Double.isFinite(candidate.attackSpeed()))
                .filter(candidate -> candidate.attackDamage() > 0.0)
                .max(Comparator
                        .comparingDouble(CombatInventoryAutomation26::weaponScore)
                        .thenComparing(candidate -> candidate.slot() == selectedSlot)
                        .thenComparingInt(WeaponCandidate::remainingDurability)
                        .thenComparingInt(candidate -> -candidate.slot()))
                .map(WeaponCandidate::slot)
                .orElse(-1);
    }

    static double weaponScore(WeaponCandidate candidate) {
        return candidate.attackDamage() * 10.0 + candidate.attackSpeed();
    }

    static int inventoryIndexToMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0
                || inventoryIndex
                >= net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) {
            throw new IllegalArgumentException(
                    "Not a main inventory index: " + inventoryIndex
            );
        }
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    private static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    public enum OffhandItem {
        END_CRYSTAL,
        TOTEM,
        ENCHANTED_GOLDEN_APPLE,
        SHIELD;

        private Item item() {
            return switch (this) {
                case END_CRYSTAL -> Items.END_CRYSTAL;
                case TOTEM -> Items.TOTEM_OF_UNDYING;
                case ENCHANTED_GOLDEN_APPLE -> Items.ENCHANTED_GOLDEN_APPLE;
                case SHIELD -> Items.SHIELD;
            };
        }
    }

    public record Configuration(
            float autoTotemHealth,
            float emergencyTotemHealth,
            OffhandItem preferredOffhand,
            boolean emergencyTotem,
            boolean autoTotemReplaceOffhand,
            int autoTotemCooldownTicks,
            boolean offhandReplaceOffhand,
            int offhandCooldownTicks,
            int minimumWeaponDurability
    ) {
        /**
         * Compatibility constructor for callers that intentionally use one
         * inventory policy for both modules.
         */
        public Configuration(
                float autoTotemHealth,
                float emergencyTotemHealth,
                OffhandItem preferredOffhand,
                boolean emergencyTotem,
                boolean replaceOffhand,
                int inventoryCooldownTicks,
                int minimumWeaponDurability
        ) {
            this(
                    autoTotemHealth,
                    emergencyTotemHealth,
                    preferredOffhand,
                    emergencyTotem,
                    replaceOffhand,
                    inventoryCooldownTicks,
                    replaceOffhand,
                    inventoryCooldownTicks,
                    minimumWeaponDurability
            );
        }

        public Configuration {
            if (!Float.isFinite(autoTotemHealth)
                    || autoTotemHealth < 1.0F
                    || autoTotemHealth > 40.0F) {
                throw new IllegalArgumentException("autoTotemHealth must be 1..40");
            }
            if (!Float.isFinite(emergencyTotemHealth)
                    || emergencyTotemHealth < 1.0F
                    || emergencyTotemHealth > 40.0F) {
                throw new IllegalArgumentException(
                        "emergencyTotemHealth must be 1..40"
                );
            }
            Objects.requireNonNull(preferredOffhand, "preferredOffhand");
            if (autoTotemCooldownTicks < 1
                    || autoTotemCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "autoTotemCooldownTicks must be 1..20"
                );
            }
            if (offhandCooldownTicks < 1 || offhandCooldownTicks > 20) {
                throw new IllegalArgumentException(
                        "offhandCooldownTicks must be 1..20"
                );
            }
            if (minimumWeaponDurability < 0
                    || minimumWeaponDurability > 100) {
                throw new IllegalArgumentException(
                        "minimumWeaponDurability must be 0..100"
                );
            }
        }

        /**
         * Compatibility view used by the original single-policy adapter.
         */
        public boolean replaceOffhand() {
            return offhandReplaceOffhand;
        }

        /**
         * Compatibility view used by the original single-policy adapter.
         */
        public int inventoryCooldownTicks() {
            return offhandCooldownTicks;
        }
    }

    enum InventoryAction {
        AUTO_TOTEM,
        OFFHAND
    }

    record DesiredOffhand(
            InventoryAction action,
            String owner,
            int priority,
            OffhandItem item,
            boolean replaceOffhand,
            int cooldownTicks
    ) {
    }

    record ItemCandidate(int slot, int count, boolean selected, boolean valid) {
    }

    record WeaponCandidate(
            int slot,
            boolean meleeWeapon,
            int remainingDurability,
            double attackDamage,
            double attackSpeed
    ) {
    }

    private record InventoryPlan(
            String owner,
            int priority,
            int inventorySlot,
            OffhandItem item,
            InventoryAction action,
            boolean replaceOffhand,
            int cooldownTicks
    ) {
    }

    record HotbarPlan(
            String owner,
            int priority,
            int expectedCurrentSlot,
            int slot,
            boolean restore
    ) {
    }

    static final class BoundedCooldown {
        private int remainingTicks;

        boolean ready() {
            return remainingTicks == 0;
        }

        void start(int ticks) {
            remainingTicks = Math.max(0, Math.min(20, ticks));
        }

        void advance() {
            if (remainingTicks > 0) {
                remainingTicks--;
            }
        }

        void clear() {
            remainingTicks = 0;
        }

        int remainingTicks() {
            return remainingTicks;
        }
    }

    static final class InventoryCooldowns {
        private final BoundedCooldown autoTotem = new BoundedCooldown();
        private final BoundedCooldown offhand = new BoundedCooldown();

        boolean ready(InventoryAction action) {
            return cooldown(action).ready();
        }

        void start(InventoryAction action, int ticks) {
            cooldown(action).start(ticks);
        }

        void advance() {
            autoTotem.advance();
            offhand.advance();
        }

        void clear() {
            autoTotem.clear();
            offhand.clear();
        }

        int remainingTicks(InventoryAction action) {
            return cooldown(action).remainingTicks();
        }

        private BoundedCooldown cooldown(InventoryAction action) {
            return switch (Objects.requireNonNull(action, "action")) {
                case AUTO_TOTEM -> autoTotem;
                case OFFHAND -> offhand;
            };
        }
    }

    static final class HotbarLease {
        private int originalSlot = -1;
        private int appliedSlot = -1;
        private String owner;
        private boolean suppressedUntilIdle;

        HotbarPlan previewSwitch(
                int currentSlot,
                int desiredSlot,
                String requestedOwner,
                int priority
        ) {
            if (!validHotbarSlot(currentSlot)
                    || !validHotbarSlot(desiredSlot)
                    || requestedOwner == null
                    || requestedOwner.isBlank()) {
                return null;
            }
            if (active() && currentSlot != appliedSlot) {
                abandon();
                suppressedUntilIdle = true;
                return null;
            }
            if (suppressedUntilIdle || currentSlot == desiredSlot) {
                return null;
            }
            return new HotbarPlan(
                    requestedOwner,
                    priority,
                    currentSlot,
                    desiredSlot,
                    false
            );
        }

        HotbarPlan previewRestore(int currentSlot) {
            suppressedUntilIdle = false;
            if (!active()) {
                return null;
            }
            if (currentSlot != appliedSlot) {
                abandon();
                return null;
            }
            if (originalSlot == currentSlot) {
                abandon();
                return null;
            }
            return new HotbarPlan(
                    owner,
                    AUTO_WEAPON_PRIORITY,
                    currentSlot,
                    originalSlot,
                    true
            );
        }

        void commitSwitch(int currentSlot, int selectedSlot, String newOwner) {
            if (!active()) {
                originalSlot = currentSlot;
            }
            appliedSlot = selectedSlot;
            owner = newOwner;
        }

        void commitRestore() {
            abandon();
        }

        int restoreSlotIfOwned(int currentSlot) {
            return active() && currentSlot == appliedSlot ? originalSlot : -1;
        }

        void abandon() {
            originalSlot = -1;
            appliedSlot = -1;
            owner = null;
            suppressedUntilIdle = false;
        }

        boolean active() {
            return validHotbarSlot(originalSlot)
                    && validHotbarSlot(appliedSlot)
                    && owner != null;
        }

        boolean suppressedUntilIdle() {
            return suppressedUntilIdle;
        }
    }

    private static final class WeaponMetrics
            implements BiConsumer<Holder<Attribute>, AttributeModifier> {
        private double attackDamage;
        private double attackSpeed;

        @Override
        public void accept(
                Holder<Attribute> attribute,
                AttributeModifier modifier
        ) {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
                attackDamage += modifier.amount();
            } else if (attribute.equals(Attributes.ATTACK_SPEED)) {
                attackSpeed += modifier.amount();
            }
        }

        void reset() {
            attackDamage = 0.0;
            attackSpeed = 0.0;
        }

        double attackDamage() {
            return attackDamage;
        }

        double attackSpeed() {
            return attackSpeed;
        }
    }
}
