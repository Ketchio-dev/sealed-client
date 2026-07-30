package dev.b2tclient.v26.hud;

import dev.b2tclient.common.module.ModuleRegistry;
import dev.b2tclient.common.module.RegisteredModule;
import dev.b2tclient.common.waypoint.Waypoint;
import dev.b2tclient.v26.ClientRuntime26;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ClientHud26 implements HudElement {
    static final int LINE_HEIGHT = 11;
    static final int FONT_HEIGHT = 9;
    /** Breathing room kept between a panel's text and the screen edge. */
    static final int PANEL_PADDING = 4;
    private static final int TEXT_COLOR = 0xFFE8F3FF;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    );
    private final ClientRuntime26 runtime;
    private final ModuleRegistry modules;
    private final long sessionStartedNanos = System.nanoTime();

    public ClientHud26(ClientRuntime26 runtime) {
        this.runtime = runtime;
        this.modules = runtime.modules();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        List<Line> lines = new ArrayList<>();
        int y = 4;

        if (enabled("watermark")) {
            y = draw(lines, client, "B2T Client 26.2", y);
        }
        if (enabled("coordinates") && client.player != null) {
            String coordinates = String.format(
                    Locale.ROOT,
                    "XYZ %.1f / %.1f / %.1f",
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ()
            );
            y = draw(lines, client, coordinates, y);
        }
        if (enabled("direction") && client.player != null) {
            String direction = client.player.getDirection().getName();
            y = draw(
                    lines,
                    client,
                    "Facing " + direction + " (" + Math.round(client.player.getYRot()) + "°)",
                    y
            );
        }
        if (enabled("speed") && client.player != null) {
            double blocksPerSecond = client.player.getDeltaMovement().horizontalDistance() * 20.0;
            y = draw(lines, client, String.format(Locale.ROOT, "Speed %.2f b/s", blocksPerSecond), y);
        }
        if (enabled("fps")) {
            y = draw(lines, client, client.getFps() + " FPS", y);
        }
        if (enabled("ping") && client.player != null && client.getConnection() != null) {
            PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (info != null) {
                y = draw(lines, client, "Ping " + info.getLatency() + " ms", y);
            }
        }
        if (enabled("health") && client.player != null) {
            y = draw(
                    lines,
                    client,
                    String.format(
                            Locale.ROOT,
                            "Health %.1f + %.1f",
                            client.player.getHealth(),
                            client.player.getAbsorptionAmount()
                    ),
                    y
            );
        }
        if (enabled("armor") && client.player != null) {
            y = draw(lines, client, "Armor " + client.player.getArmorValue(), y);
        }
        if (enabled("totem_count") && client.player != null) {
            y = draw(
                    lines,
                    client,
                    "Totems " + countItem(client, Items.TOTEM_OF_UNDYING, true),
                    y
            );
        }
        if (enabled("durability_warning") && client.player != null) {
            int lowestDurability = ARMOR_SLOTS.stream()
                    .map(client.player::getItemBySlot)
                    .filter(ItemStack::isDamageableItem)
                    .mapToInt(stack -> (stack.getMaxDamage() - stack.getDamageValue()) * 100 / stack.getMaxDamage())
                    .min()
                    .orElse(100);
            if (lowestDurability <= 25) {
                y = draw(lines, client, "Armor warning " + lowestDurability + "%", y, 0xFFFF7777);
            }
        }
        if (enabled("biome") && client.player != null) {
            String biome = client.player.level().getBiome(client.player.blockPosition())
                    .unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("unknown");
            y = draw(lines, client, "Biome " + biome, y);
        }
        if (enabled("player_count") && client.getConnection() != null) {
            y = draw(
                    lines,
                    client,
                    "Players " + client.getConnection().getOnlinePlayers().size(),
                    y
            );
        }
        if (enabled("inventory_space") && client.player != null) {
            long freeSlots = client.player.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.isEmpty())
                    .count();
            y = draw(lines, client, "Free slots " + freeSlots, y);
        }
        if (enabled("effects") && client.player != null) {
            y = draw(lines, client, "Effects " + client.player.getActiveEffects().size(), y);
        }
        if (enabled("supplies") && client.player != null) {
            String supplies = "Supplies O:" + countItem(client, Items.OBSIDIAN, false)
                    + " C:" + countItem(client, Items.END_CRYSTAL, false)
                    + " G:" + (
                    countItem(client, Items.GOLDEN_APPLE, false)
                            + countItem(client, Items.ENCHANTED_GOLDEN_APPLE, false)
            )
                    + " P:" + countItem(client, Items.ENDER_PEARL, false);
            y = draw(lines, client, supplies, y);
        }
        if (enabled("radar") && client.player != null && client.level != null) {
            List<AbstractClientPlayer> nearby = client.level.players().stream()
                    .filter(player -> player != client.player)
                    .sorted(Comparator.comparingDouble(client.player::distanceTo))
                    .limit(3)
                    .toList();
            for (AbstractClientPlayer player : nearby) {
                boolean isFriend = runtime.friends().findByUuid(player.getUUID()).isPresent()
                        || runtime.friends().findByName(player.getName().getString()).isPresent();
                String friend = isFriend ? " ★" : "";
                y = draw(
                        lines,
                        client,
                        player.getName().getString() + friend + " " + Math.round(client.player.distanceTo(player)) + "m",
                        y
                );
            }
        }
        if (enabled("death_position") && !runtime.lastDeathLabel().isBlank()) {
            y = draw(lines, client, runtime.lastDeathLabel(), y, 0xFFFF9999);
        }
        if (enabled("waypoints") && client.player != null) {
            String dimension = client.player.level().dimension().identifier().toString();
            for (Waypoint waypoint : runtime.waypoints().visibleFor(runtime.serverKey(client), dimension).stream()
                    .limit(3)
                    .toList()) {
                double dx = waypoint.x() - client.player.getX();
                double dy = waypoint.y() - client.player.getY();
                double dz = waypoint.z() - client.player.getZ();
                long distance = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                y = draw(lines, client, "WP " + waypoint.name() + " " + distance + "m", y, waypoint.color());
            }
        }
        if (enabled("portal_coords") && client.player != null) {
            String dimension = client.player.level().dimension().identifier().toString();
            if ("minecraft:overworld".equals(dimension)) {
                y = draw(
                        lines,
                        client,
                        String.format(
                                Locale.ROOT,
                                "Nether portal %.1f / %.1f",
                                client.player.getX() / 8.0,
                                client.player.getZ() / 8.0
                        ),
                        y
                );
            } else if ("minecraft:the_nether".equals(dimension)) {
                y = draw(
                        lines,
                        client,
                        String.format(
                                Locale.ROOT,
                                "Overworld portal %.1f / %.1f",
                                client.player.getX() * 8.0,
                                client.player.getZ() * 8.0
                        ),
                        y
                );
            }
        }
        if (enabled("target_hud")) {
            String targetLine = targetLine(client);
            if (targetLine != null) {
                y = draw(lines, client, targetLine, y);
            }
        }
        if (enabled("server_info")) {
            y = draw(lines, client, "Server " + runtime.serverKey(client), y);
        }
        if (enabled("tick_rate")) {
            y = draw(
                    lines,
                    client,
                    HudMetricsBridge26.tickRateSnapshot().displayText(),
                    y
            );
        }
        if (enabled("totem_pop_local")) {
            y = draw(
                    lines,
                    client,
                    HudMetricsBridge26.localTotemPopSnapshot()
                            .displayText(),
                    y
            );
        }
        if (enabled("session")) {
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - sessionStartedNanos);
            y = draw(
                    lines,
                    client,
                    String.format(
                            Locale.ROOT,
                            "Session %02d:%02d:%02d",
                            elapsedSeconds / 3600,
                            (elapsedSeconds / 60) % 60,
                            elapsedSeconds % 60
                    ),
                    y
            );
        }
        if (enabled("clock")) {
            draw(lines, client, LocalTime.now().format(CLOCK), y);
        }

        renderPanel(graphics, client, HudLayout26.Panel.INFO, lines, false);
        if (enabled("array_list")) {
            renderPanel(
                    graphics,
                    client,
                    HudLayout26.Panel.ARRAY_LIST,
                    enabledModuleLines(client),
                    true
            );
        }
    }

    /**
     * Measures a panel, places it through the layout, and draws it.
     *
     * <p>Placement always goes through {@link HudLayout26}, which clamps the
     * panel inside the current screen. That is what keeps a long stat column or
     * a high GUI scale from spilling off a small window.</p>
     */
    private void renderPanel(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            HudLayout26.Panel panel,
            List<Line> lines,
            boolean rightAligned
    ) {
        if (lines.isEmpty()) {
            return;
        }
        int panelWidth = panelWidth(client, lines);
        int panelHeight = panelHeight(lines);
        HudLayout26.Position position = runtime.hudLayout().resolve(
                panel,
                panelWidth + PANEL_PADDING * 2,
                panelHeight + PANEL_PADDING * 2,
                graphics.guiWidth(),
                graphics.guiHeight()
        );
        int left = position.x() + PANEL_PADDING;
        int top = position.y() + PANEL_PADDING;
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            int x = rightAligned
                    ? left + panelWidth - client.font.width(line.text())
                    : left;
            graphics.text(
                    client.font,
                    line.text(),
                    x,
                    top + index * LINE_HEIGHT,
                    line.color(),
                    true
            );
        }
    }

    /**
     * Builds the Target line from the combat modules' actual selection, and
     * only falls back to the crosshair when no combat module chose anything.
     * The fallback is labelled so the two cases are never confused.
     */
    private String targetLine(Minecraft client) {
        if (client.player != null && client.level != null) {
            int tick = client.player.tickCount;
            int entityId = runtime.combatTarget().entityId(tick);
            if (entityId != CombatTargetBridge26.NO_TARGET
                    && client.level.getEntity(entityId) instanceof LivingEntity selected) {
                return String.format(
                        Locale.ROOT,
                        "Target %s %.1f HP (%s)",
                        selected.getName().getString(),
                        selected.getHealth(),
                        runtime.combatTarget().source(tick).label()
                );
            }
        }
        if (client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity looked) {
            return String.format(
                    Locale.ROOT,
                    "Target %s %.1f HP (Crosshair)",
                    looked.getName().getString(),
                    looked.getHealth()
            );
        }
        return null;
    }

    static int panelHeight(List<Line> lines) {
        return Math.max(0, lines.size() * LINE_HEIGHT - (LINE_HEIGHT - FONT_HEIGHT));
    }

    private static int panelWidth(Minecraft client, List<Line> lines) {
        int widest = 0;
        for (Line line : lines) {
            widest = Math.max(widest, client.font.width(line.text()));
        }
        return widest;
    }

    /** One rendered HUD row. */
    record Line(String text, int color) {
    }

    private static int draw(List<Line> lines, Minecraft client, String text, int y) {
        return draw(lines, client, text, y, TEXT_COLOR);
    }

    /**
     * Appends one HUD line. The running {@code y} is kept so the long render
     * body reads unchanged; the actual screen position is applied later, once
     * the whole panel has been measured and placed.
     */
    private static int draw(
            List<Line> lines,
            Minecraft client,
            String text,
            int y,
            int color
    ) {
        lines.add(new Line(text, color));
        return y + LINE_HEIGHT;
    }

    private static int countItem(Minecraft client, Item item, boolean includeOffhand) {
        if (client.player == null) {
            return 0;
        }
        int count = client.player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.getItem() == item)
                .mapToInt(ItemStack::getCount)
                .sum();
        if (includeOffhand && client.player.getOffhandItem().getItem() == item) {
            count += client.player.getOffhandItem().getCount();
        }
        return count;
    }

    private List<Line> enabledModuleLines(Minecraft client) {
        return modules.all().stream()
                .filter(RegisteredModule::enabled)
                .filter(candidate -> !"array_list".equals(candidate.descriptor().id()))
                .sorted(Comparator.comparingInt(
                        candidate -> -client.font.width(candidate.descriptor().name())
                ))
                .limit(16)
                .map(module -> new Line(module.descriptor().name(), TEXT_COLOR))
                .toList();
    }

    private boolean enabled(String id) {
        return modules.find(id).map(RegisteredModule::enabled).orElse(false);
    }
}
