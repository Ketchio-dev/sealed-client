package dev.b2tclient.command;

import dev.b2tclient.config.ConfigManager;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.setting.BooleanSetting;
import dev.b2tclient.core.setting.ColorSetting;
import dev.b2tclient.core.setting.DoubleSetting;
import dev.b2tclient.core.setting.EnumSetting;
import dev.b2tclient.core.setting.IntegerSetting;
import dev.b2tclient.core.setting.Setting;
import dev.b2tclient.core.setting.StringListSetting;
import dev.b2tclient.core.setting.StringSetting;
import dev.b2tclient.integration.BaritoneNavigator;
import dev.b2tclient.module.combat.AutoCrystalModule;
import dev.b2tclient.module.movement.MovementNetworkTracker;
import dev.b2tclient.service.ActionCoordinator;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.Waypoint;
import dev.b2tclient.service.WaypointManager;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CommandManager {
    public static final String PREFIX = ";b2t";

    private final ModuleManager modules;
    private final ConfigManager config;
    private final FriendManager friends;
    private final WaypointManager waypoints;
    private final ActionCoordinator actions;
    private final BaritoneNavigator baritone;
    private final Supplier<MovementNetworkTracker.Snapshot> movementNetwork;

    public CommandManager(
            ModuleManager modules,
            ConfigManager config,
            FriendManager friends,
            WaypointManager waypoints,
            ActionCoordinator actions,
            BaritoneNavigator baritone
    ) {
        this(
                modules,
                config,
                friends,
                waypoints,
                actions,
                baritone,
                () -> new MovementNetworkTracker.Snapshot(0L, -1L)
        );
    }

    public CommandManager(
            ModuleManager modules,
            ConfigManager config,
            FriendManager friends,
            WaypointManager waypoints,
            ActionCoordinator actions,
            BaritoneNavigator baritone,
            Supplier<MovementNetworkTracker.Snapshot> movementNetwork
    ) {
        this.modules = modules;
        this.config = config;
        this.friends = friends;
        this.waypoints = waypoints;
        this.actions = actions;
        this.baritone = baritone;
        this.movementNetwork = movementNetwork;
    }

    public void initialize() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (!trimmed.equalsIgnoreCase(PREFIX)
                    && !trimmed.toLowerCase(Locale.ROOT).startsWith(PREFIX + " ")) {
                return true;
            }
            execute(trimmed.substring(PREFIX.length()).trim(), Minecraft.getInstance());
            return false;
        });
    }

    public boolean execute(String input, Minecraft minecraft) {
        List<String> arguments = tokenize(input);
        if (arguments.isEmpty()) {
            help(minecraft);
            return true;
        }

        try {
            String command = arguments.getFirst().toLowerCase(Locale.ROOT);
            List<String> tail = arguments.subList(1, arguments.size());
            return switch (command) {
                case "help" -> {
                    help(minecraft);
                    yield true;
                }
                case "toggle" -> toggle(tail, minecraft);
                case "bind" -> bind(tail, minecraft);
                case "set" -> set(tail);
                case "profile" -> profile(tail, minecraft);
                case "friend" -> friend(tail, minecraft);
                case "waypoint", "wp" -> waypoint(tail, minecraft);
                case "baritone", "nav" -> baritone(tail, minecraft);
                case "config" -> configuration(tail, minecraft);
                case "diagnostics", "diag" -> diagnostics(tail, minecraft);
                case "panic" -> panic(minecraft);
                case "modules" -> listModules(tail);
                default -> {
                    error("Unknown command: " + command);
                    help(minecraft);
                    yield false;
                }
            };
        } catch (IllegalArgumentException exception) {
            error(exception.getMessage());
            return false;
        } catch (RuntimeException exception) {
            error("Command failed safely: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean toggle(List<String> arguments, Minecraft minecraft) {
        Module module = requireModule(requireArgument(arguments, 0, "module"));
        module.toggle(minecraft);
        config.save();
        info(module.name() + " is now " + (module.isEnabled() ? "enabled" : "disabled"));
        return true;
    }

    private boolean bind(List<String> arguments, Minecraft minecraft) {
        Module module = requireModule(requireArgument(arguments, 0, "module"));
        String keyName = requireArgument(arguments, 1, "key");
        int key = parseKey(keyName);
        module.setKeyCode(key);
        config.save();
        info(module.name() + " bound to " + (key == GLFW.GLFW_KEY_UNKNOWN ? "none" : keyName));
        return true;
    }

    private boolean set(List<String> arguments) {
        Module module = requireModule(requireArgument(arguments, 0, "module"));
        String settingId = requireArgument(arguments, 1, "setting");
        Setting<?> setting = module.settings().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(settingId)
                        || candidate.name().equalsIgnoreCase(settingId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown setting " + settingId + " for " + module.name()
                ));
        String value = join(arguments, 2);
        if (value.isBlank()) {
            throw new IllegalArgumentException("A setting value is required");
        }
        applySetting(setting, value);
        config.save();
        info(module.name() + "." + setting.id() + " = " + setting.get());
        return true;
    }

    private boolean profile(List<String> arguments, Minecraft minecraft) {
        String action = arguments.isEmpty() ? "list" : arguments.getFirst().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> {
                info("Profiles: " + String.join(", ", config.profileNames())
                        + " • active: " + config.activeProfile());
                yield true;
            }
            case "create" -> {
                String name = requireArgument(arguments, 1, "profile name");
                boolean copy = arguments.size() < 3
                        || !arguments.get(2).equalsIgnoreCase("empty");
                if (!config.createProfile(name, copy)) {
                    throw new IllegalArgumentException("Profile already exists: " + name);
                }
                info("Created profile " + name);
                yield true;
            }
            case "use" -> {
                String name = requireArgument(arguments, 1, "profile name");
                if (!config.switchProfile(name, minecraft)) {
                    throw new IllegalArgumentException("Profile is already active or unknown: " + name);
                }
                baritone.releaseOwnedNavigation();
                actions.releaseAll(minecraft);
                info("Active profile: " + config.activeProfile());
                yield true;
            }
            case "delete" -> {
                String name = requireArgument(arguments, 1, "profile name");
                if (!config.deleteProfile(name)) {
                    throw new IllegalArgumentException("Cannot delete profile: " + name);
                }
                info("Deleted profile " + name);
                yield true;
            }
            case "bind" -> {
                String server = requireArgument(arguments, 1, "server");
                String name = requireArgument(arguments, 2, "profile name");
                config.bindServer(server, name);
                info("Bound " + server + " to profile " + name);
                yield true;
            }
            default -> throw new IllegalArgumentException(
                    "Usage: profile list|create|use|delete|bind"
            );
        };
    }

    private boolean friend(List<String> arguments, Minecraft minecraft) {
        String action = arguments.isEmpty() ? "list" : arguments.getFirst().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> {
                String names = friends.all().stream()
                        .map(FriendManager.Friend::name)
                        .collect(Collectors.joining(", "));
                info("Friends: " + (names.isBlank() ? "none" : names));
                yield true;
            }
            case "add" -> {
                String name = requireArgument(arguments, 1, "player name");
                UUID uuid = resolvePlayerUuid(minecraft, name);
                if (!friends.add(name, uuid)) {
                    throw new IllegalArgumentException(name + " is already a friend");
                }
                config.save();
                info("Added friend " + name);
                yield true;
            }
            case "remove" -> {
                String name = requireArgument(arguments, 1, "player name");
                if (!friends.remove(name)) {
                    throw new IllegalArgumentException(name + " is not a friend");
                }
                config.save();
                info("Removed friend " + name);
                yield true;
            }
            default -> throw new IllegalArgumentException("Usage: friend list|add|remove");
        };
    }

    private boolean waypoint(List<String> arguments, Minecraft minecraft) {
        String action = arguments.isEmpty() ? "list" : arguments.getFirst().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> {
                String names = waypoints.all().stream()
                        .map(Waypoint::name)
                        .collect(Collectors.joining(", "));
                info("Waypoints: " + (names.isBlank() ? "none" : names));
                yield true;
            }
            case "add" -> {
                if (minecraft.player == null) {
                    throw new IllegalArgumentException("Join a world before adding a waypoint");
                }
                String name = requireArgument(arguments, 1, "waypoint name");
                Waypoint waypoint = new Waypoint(
                        name,
                        serverId(minecraft),
                        minecraft.player.level().dimension().location().toString(),
                        minecraft.player.getX(),
                        minecraft.player.getY(),
                        minecraft.player.getZ(),
                        0xff55d6be,
                        true
                );
                waypoints.add(waypoint);
                config.save();
                info("Saved waypoint " + name);
                yield true;
            }
            case "remove" -> {
                String name = requireArgument(arguments, 1, "waypoint name");
                if (!waypoints.remove(name)) {
                    throw new IllegalArgumentException("Unknown waypoint: " + name);
                }
                config.save();
                info("Removed waypoint " + name);
                yield true;
            }
            default -> throw new IllegalArgumentException("Usage: waypoint list|add|remove");
        };
    }

    private boolean configuration(List<String> arguments, Minecraft minecraft) {
        String action = arguments.isEmpty() ? "save" : arguments.getFirst().toLowerCase(Locale.ROOT);
        if (action.equals("save")) {
            config.save();
            info("Configuration saved");
            return true;
        }
        if (action.equals("reload")) {
            baritone.releaseOwnedNavigation();
            actions.releaseAll(minecraft);
            config.load(minecraft);
            info("Configuration reloaded");
            return true;
        }
        throw new IllegalArgumentException("Usage: config save|reload");
    }

    private boolean panic(Minecraft minecraft) {
        modules.panic(minecraft);
        baritone.releaseOwnedNavigation();
        actions.releaseAll(minecraft);
        config.save();
        info("Panic complete: combat, movement and automation modules disabled");
        return true;
    }

    private boolean diagnostics(List<String> arguments, Minecraft minecraft) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("Usage: diagnostics");
        }
        long enabled = modules.all().stream().filter(Module::isEnabled).count();
        String risky = modules.all().stream()
                .filter(Module::isEnabled)
                .filter(module -> module.risk() != dev.b2tclient.core.ModuleRisk.PASSIVE)
                .map(Module::id)
                .collect(Collectors.joining(", "));
        info("Diagnostics: profile " + config.activeProfile()
                + " • modules " + enabled + "/" + modules.all().size()
                + " • risky " + (risky.isBlank() ? "none" : risky));

        MovementNetworkTracker.Snapshot network = movementNetwork.get();
        int ping = -1;
        if (minecraft != null
                && minecraft.player != null
                && minecraft.getConnection() != null) {
            var playerInfo = minecraft.getConnection()
                    .getPlayerInfo(minecraft.player.getUUID());
            ping = playerInfo == null ? -1 : playerInfo.getLatency();
        }
        info("Network: ping " + diagnosticMillis(ping)
                + " • inbound silence " + diagnosticMillis(network.inboundSilenceMillis())
                + " • corrections " + network.correctionSequence());

        ActionCoordinator.DiagnosticSnapshot actionStatus = actions.diagnostics();
        String claims = actionStatus.channelOwners().entrySet().stream()
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT)
                        + '=' + entry.getValue())
                .collect(Collectors.joining(", "));
        info("Actions: tick " + actionStatus.tick()
                + " • claims " + (claims.isBlank() ? "none" : claims)
                + " • held keys " + actionStatus.controlledKeyCount());

        String crystal = modules.find("auto_crystal")
                .filter(AutoCrystalModule.class::isInstance)
                .map(AutoCrystalModule.class::cast)
                .map(AutoCrystalModule::transactionStatus)
                .orElse("unavailable");
        BaritoneNavigator.NavigationStatus navigation = baritone.status();
        info("Engines: crystal " + crystal
                + " • baritone " + navigation.state().name().toLowerCase(Locale.ROOT)
                + (navigation.ownedByB2T() ? " (owned)" : ""));
        return true;
    }

    private static String diagnosticMillis(long value) {
        return value < 0L ? "not observed" : value + "ms";
    }

    private boolean baritone(List<String> arguments, Minecraft minecraft) {
        String action = arguments.isEmpty()
                ? "status"
                : arguments.getFirst().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "status" -> {
                BaritoneNavigator.NavigationStatus status = baritone.status();
                String version = baritone.version().isBlank()
                        ? ""
                        : " " + baritone.version();
                String target = status.target() == null
                        ? ""
                        : " • target " + status.target();
                String retries = status.retryCount() == 0
                        ? ""
                        : " • retries " + status.retryCount();
                String elapsed = status.target() == null
                        ? ""
                        : String.format(
                                Locale.ROOT,
                                " • %.1fs",
                                status.elapsedTicks() / 20.0D
                        );
                info("Baritone" + version + ": " + status.state()
                        + " • " + status.detail()
                        + target
                        + retries
                        + elapsed
                        + (status.ownedByB2T() ? " • owned by B2T" : ""));
                yield true;
            }
            case "pause" -> {
                requireBaritoneSuccess(baritone.pause());
                info("B2T-owned Baritone navigation paused");
                yield true;
            }
            case "resume" -> {
                requireBaritoneSuccess(baritone.resume());
                info("B2T-owned Baritone navigation resumed");
                yield true;
            }
            case "stop" -> {
                requireBaritoneSuccess(baritone.stop());
                info("B2T-owned Baritone navigation stopped");
                yield true;
            }
            case "goto", "go" -> baritoneGoto(
                    arguments.subList(1, arguments.size()),
                    minecraft
            );
            default -> throw new IllegalArgumentException(
                    "Usage: baritone status|pause|resume|stop"
                            + "|goto <x> [y] <z>|goto waypoint <name>"
            );
        };
    }

    private boolean baritoneGoto(List<String> arguments, Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalArgumentException("Join a world before starting navigation");
        }
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Usage: baritone goto <x> [y] <z>|waypoint <name>"
            );
        }

        int x;
        int y;
        int z;
        if (arguments.getFirst().equalsIgnoreCase("waypoint")
                || arguments.getFirst().equalsIgnoreCase("wp")) {
            String name = join(arguments, 1);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Missing waypoint name");
            }
            Waypoint waypoint = waypoints.find(name).orElseThrow(() ->
                    new IllegalArgumentException("Unknown waypoint: " + name)
            );
            String currentDimension =
                    minecraft.player.level().dimension().location().toString();
            if (!waypoint.server().equalsIgnoreCase(serverId(minecraft))
                    || !waypoint.dimension().equals(currentDimension)) {
                throw new IllegalArgumentException(
                        "Waypoint belongs to another server or dimension"
                );
            }
            x = coordinate(waypoint.x(), "waypoint X");
            y = coordinate(waypoint.y(), "waypoint Y");
            z = coordinate(waypoint.z(), "waypoint Z");
        } else if (arguments.size() == 2) {
            x = coordinate(arguments.get(0), "X");
            y = minecraft.player.blockPosition().getY();
            z = coordinate(arguments.get(1), "Z");
        } else if (arguments.size() == 3) {
            x = coordinate(arguments.get(0), "X");
            y = coordinate(arguments.get(1), "Y");
            z = coordinate(arguments.get(2), "Z");
        } else {
            throw new IllegalArgumentException(
                    "Usage: baritone goto <x> [y] <z>|waypoint <name>"
            );
        }
        if (Math.abs((long) x) > 30_000_000L
                || Math.abs((long) z) > 30_000_000L) {
            throw new IllegalArgumentException(
                    "Baritone target must stay within Minecraft's coordinate limit"
            );
        }
        if (y < minecraft.level.getMinY() || y >= minecraft.level.getMaxY()) {
            throw new IllegalArgumentException(
                    "Baritone target Y is outside the current dimension"
            );
        }

        requireBaritoneSuccess(baritone.goTo(x, y, z));
        info("Baritone navigating to " + x + ", " + y + ", " + z);
        return true;
    }

    private static int coordinate(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static int coordinate(double value, String name) {
        if (!Double.isFinite(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return (int) Math.floor(value);
    }

    private static void requireBaritoneSuccess(
            BaritoneNavigator.NavigationResult result
    ) {
        if (!result.success()) {
            throw new IllegalArgumentException(result.message());
        }
    }

    private boolean listModules(List<String> arguments) {
        String category = arguments.isEmpty() ? "" : arguments.getFirst();
        String names = modules.all().stream()
                .filter(module -> category.isBlank()
                        || module.category().name().equalsIgnoreCase(category))
                .map(Module::name)
                .collect(Collectors.joining(", "));
        info("Modules: " + names);
        return true;
    }

    private static void applySetting(Setting<?> setting, String value) {
        if (setting instanceof BooleanSetting typed) {
            typed.set(parseBoolean(value));
        } else if (setting instanceof IntegerSetting typed) {
            typed.set(Integer.parseInt(value));
        } else if (setting instanceof DoubleSetting typed) {
            typed.set(Double.parseDouble(value));
        } else if (setting instanceof StringSetting typed) {
            typed.set(value);
        } else if (setting instanceof ColorSetting typed) {
            String requested = value.startsWith("#") ? value.substring(1) : value;
            if (requested.length() == 6) {
                requested = "FF" + requested;
            }
            typed.set((int) Long.parseLong(requested, 16));
        } else if (setting instanceof StringListSetting typed) {
            typed.set(Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty())
                    .collect(Collectors.toSet()));
        } else if (setting instanceof EnumSetting<?> typed) {
            setEnum(typed, value);
        } else {
            throw new IllegalArgumentException("Unsupported setting type");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnum(EnumSetting setting, String value) {
        for (Object constant : ((Enum) setting.get()).getDeclaringClass().getEnumConstants()) {
            Enum enumValue = (Enum) constant;
            if (enumValue.name().equalsIgnoreCase(value.replace(' ', '_'))) {
                setting.set(enumValue);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown enum value: " + value);
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new IllegalArgumentException("Expected on/off, got: " + value);
        };
    }

    private static int parseKey(String value) {
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("clear")) {
            return GLFW.GLFW_KEY_UNKNOWN;
        }
        if (value.length() == 1) {
            char character = Character.toUpperCase(value.charAt(0));
            if (character >= 'A' && character <= 'Z') {
                return GLFW.GLFW_KEY_A + character - 'A';
            }
            if (character >= '0' && character <= '9') {
                return GLFW.GLFW_KEY_0 + character - '0';
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Use a letter, digit, numeric GLFW key, or none");
        }
    }

    private static UUID resolvePlayerUuid(Minecraft minecraft, String name) {
        if (minecraft.level == null) {
            return null;
        }
        for (Player player : minecraft.level.players()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) {
                return player.getUUID();
            }
        }
        return null;
    }

    private static String serverId(Minecraft minecraft) {
        return minecraft.getCurrentServer() == null
                ? "singleplayer"
                : minecraft.getCurrentServer().ip;
    }

    private Module requireModule(String name) {
        return modules.find(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + name));
    }

    private static String requireArgument(List<String> arguments, int index, String name) {
        if (index >= arguments.size() || arguments.get(index).isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return arguments.get(index);
    }

    private static String join(List<String> arguments, int start) {
        return start >= arguments.size() ? "" : String.join(" ", arguments.subList(start, arguments.size()));
    }

    static List<String> tokenize(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private static void help(Minecraft minecraft) {
        info("Commands: toggle, bind, set, profile, friend, waypoint, baritone, config, diagnostics, modules, panic");
        info("Example: ;b2t toggle auto_totem");
    }

    private static void info(String message) {
        output("§b[B2T] §f" + message);
    }

    private static void error(String message) {
        output("§c[B2T] " + message);
    }

    private static void output(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
    }
}
