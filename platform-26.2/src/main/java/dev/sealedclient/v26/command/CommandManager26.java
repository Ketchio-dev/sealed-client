package dev.sealedclient.v26.command;

import dev.sealedclient.common.module.ModuleCategory;
import dev.sealedclient.common.module.RegisteredModule;
import dev.sealedclient.common.social.FriendEntry;
import dev.sealedclient.common.waypoint.Waypoint;
import dev.sealedclient.v26.ClientRuntime26;
import dev.sealedclient.v26.gui.HudEditorScreen26;
import dev.sealedclient.v26.gui.ProfileScreen26;
import dev.sealedclient.v26.integration.BaritoneNavigator26;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class CommandManager26 {
    private static final String PREFIX = ";sealed";
    private final ClientRuntime26 runtime;

    public CommandManager26(ClientRuntime26 runtime) {
        this.runtime = runtime;
    }

    public void initialize() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (!trimmed.equalsIgnoreCase(PREFIX) && !trimmed.toLowerCase(Locale.ROOT).startsWith(PREFIX + " ")) {
                return true;
            }
            try {
                execute(trimmed.length() == PREFIX.length()
                        ? ""
                        : trimmed.substring(PREFIX.length()).trim());
            } catch (RuntimeException exception) {
                dev.sealedclient.v26.SealedClient26.LOGGER.error(
                        "26.2 local command failed",
                        exception
                );
                reply("Command failed safely; see the client log for details");
            }
            return false;
        });
    }

    private void execute(String input) {
        String[] args = input.isBlank() ? new String[0] : input.split("\\s+");
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            reply("Commands: list [category], status <id>, toggle <id>, friend <add|remove|list>, "
                    + "waypoint <add|remove|list>, profile <save|use|list>, "
                    + "baritone <goto|stop|status>, hud <edit|reset>, panic");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> listModules(args);
            case "status" -> status(args);
            case "toggle" -> toggle(args);
            case "friend" -> friend(args);
            case "waypoint" -> waypoint(args);
            case "profile" -> profile(args);
            case "baritone" -> baritone(args);
            case "hud" -> hud(args);
            case "panic" -> panic();
            default -> reply("Unknown command. Use ;sealed help");
        }
    }

    private void listModules(String[] args) {
        ModuleCategory category = null;
        if (args.length > 1) {
            try {
                category = ModuleCategory.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                reply("Unknown category: " + args[1]);
                return;
            }
        }
        for (ModuleCategory candidate : ModuleCategory.values()) {
            if (category != null && category != candidate) {
                continue;
            }
            String listing = runtime.modules().byCategory().getOrDefault(candidate, java.util.List.of()).stream()
                    .map(module -> module.descriptor().id() + "=" + state(module))
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("(none)");
            reply(candidate.name() + ": " + listing);
        }
    }

    private void status(String[] args) {
        if (args.length < 2) {
            reply("Usage: ;sealed status <module-id>");
            return;
        }
        RegisteredModule module = runtime.modules().find(args[1]).orElse(null);
        if (module == null) {
            reply("Unknown module: " + args[1]);
            return;
        }
        reply(module.descriptor().id() + "=" + state(module) + " — " + module.descriptor().capabilityDetail());
    }

    private void toggle(String[] args) {
        if (args.length < 2) {
            reply("Usage: ;sealed toggle <module-id>");
            return;
        }
        RegisteredModule module = runtime.modules().find(args[1]).orElse(null);
        if (module == null) {
            reply("Unknown module: " + args[1]);
            return;
        }
        if (!module.descriptor().available()) {
            reply(module.descriptor().id() + " is UNAVAILABLE: " + module.descriptor().capabilityDetail());
            return;
        }
        if ("baritone_navigator".equals(module.descriptor().id())
                && (runtime.baritone() == null
                || !runtime.baritone().available())) {
            reply("baritone_navigator requires a separately installed compatible Baritone provider");
            return;
        }
        module.toggle();
        replySaved(
                module.descriptor().id() + " is now " + state(module),
                runtime.save()
        );
    }

    private void friend(String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            String names = runtime.friends().all().stream()
                    .map(FriendEntry::name)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("(none)");
            reply("Friends: " + names);
            return;
        }
        if (args.length < 3) {
            reply("Usage: ;sealed friend <add|remove> <name>");
            return;
        }
        if ("add".equalsIgnoreCase(args[1])) {
            runtime.friends().put(new FriendEntry(args[2], null));
            replySaved("Added friend " + args[2], runtime.save());
        } else if ("remove".equalsIgnoreCase(args[1])) {
            boolean removed = runtime.friends().remove(args[2]);
            if (!removed) {
                reply("Friend not found");
                return;
            }
            replySaved("Removed friend " + args[2], runtime.save());
        } else {
            reply("Usage: ;sealed friend <add|remove|list> [name]");
        }
    }

    private void waypoint(String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            String names = runtime.waypoints().all().stream()
                    .map(Waypoint::name)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("(none)");
            reply("Waypoints: " + names);
            return;
        }
        if ("remove".equalsIgnoreCase(args[1]) && args.length >= 3) {
            boolean removed = runtime.waypoints().remove(args[2]);
            if (!removed) {
                reply("Waypoint not found");
                return;
            }
            replySaved("Removed waypoint " + args[2], runtime.save());
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if ("add".equalsIgnoreCase(args[1]) && args.length >= 3 && client.player != null) {
            runtime.waypoints().put(new Waypoint(
                    args[2],
                    runtime.serverKey(client),
                    client.player.level().dimension().identifier().toString(),
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ(),
                    0xFFFFAA00,
                    true
            ));
            replySaved("Added waypoint " + args[2], runtime.save());
            return;
        }
        reply("Usage: ;sealed waypoint <add|remove|list> [name]");
    }

    private void profile(String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            String names = runtime.profiles().all().stream()
                    .map(profile -> profile.name())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("(none)");
            reply("Profiles: " + names);
            return;
        }
        if ("gui".equalsIgnoreCase(args[1])) {
            Minecraft client = Minecraft.getInstance();
            if (client.gui == null) {
                reply("The profile screen needs an active client screen");
                return;
            }
            client.schedule(() -> client.gui.setScreen(new ProfileScreen26(runtime)));
            reply("Opening the profile manager");
            return;
        }
        if (args.length < 3) {
            reply("Usage: ;sealed profile <save|use|delete> <name> [server-pattern]");
            return;
        }
        if ("delete".equalsIgnoreCase(args[1])) {
            switch (runtime.profiles().delete(args[2])) {
                case DELETED -> replySaved("Deleted profile " + args[2], runtime.save());
                case LAST_PROFILE -> reply(
                        "Refusing to delete the last profile; save another one first"
                );
                case NOT_FOUND -> reply("Profile not found: " + args[2]);
            }
            return;
        }
        if ("save".equalsIgnoreCase(args[1])) {
            runtime.clearBaritoneConfirmation();
            String serverPattern = args.length >= 4
                    ? args[3]
                    : runtime.serverKey(Minecraft.getInstance());
            runtime.profiles().capture(
                    args[2],
                    serverPattern,
                    runtime.modules()
            );
            replySaved(
                    "Saved profile " + args[2] + " for " + serverPattern,
                    runtime.save()
            );
        } else if ("use".equalsIgnoreCase(args[1])) {
            boolean activated = runtime.profiles().activate(args[2], runtime.modules());
            if (activated) {
                runtime.clearBaritoneConfirmation();
                replySaved(
                        "Activated profile " + args[2],
                        runtime.save()
                );
            } else {
                reply("Profile not found or invalid");
            }
        } else {
            reply("Usage: ;sealed profile <save|use|delete|list|gui> [name] [server-pattern]");
        }
    }

    private void baritone(String[] args) {
        BaritoneNavigator26 navigator = runtime.baritone();
        if (navigator == null) {
            reply("Baritone integration is not initialized");
            return;
        }
        if (args.length < 2 || "status".equalsIgnoreCase(args[1])) {
            BaritoneNavigator26.NavigationStatus status =
                    navigator.status();
            String target = status.target() == null
                    ? ""
                    : " target=" + status.target();
            reply(
                    "Baritone " + status.state()
                            + target
                            + " retries=" + status.retryCount()
                            + " — " + status.detail()
            );
            return;
        }
        if ("stop".equalsIgnoreCase(args[1])) {
            reply(runtime.requestBaritoneStop());
            return;
        }
        if (!"goto".equalsIgnoreCase(args[1]) || args.length != 5) {
            reply("Usage: ;sealed baritone <goto x y z|stop|status>");
            return;
        }
        try {
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            if (Math.abs((long) x) > 30_000_000L
                    || Math.abs((long) z) > 30_000_000L
                    || y < -64
                    || y > 319) {
                reply("Baritone target is outside the supported world bounds");
                return;
            }
            reply(runtime.requestBaritoneNavigation(x, y, z));
        } catch (NumberFormatException exception) {
            reply("Baritone coordinates must be whole numbers");
        }
    }

    private void panic() {
        int disabled = runtime.panic(Minecraft.getInstance());
        reply("Panic released platform state and disabled " + disabled
                + " active non-passive module(s)");
    }

    private static String state(RegisteredModule module) {
        if (!module.descriptor().available()) {
            return "UNAVAILABLE";
        }
        return module.enabled() ? "ON" : "OFF";
    }

    private void hud(String[] args) {
        if (args.length < 2 || "edit".equalsIgnoreCase(args[1])) {
            Minecraft client = Minecraft.getInstance();
            if (client.gui == null) {
                reply("The HUD editor needs an active client screen");
                return;
            }
            // Opening from chat: the chat screen is closing this frame, so the
            // editor is queued rather than set underneath it.
            client.schedule(() -> client.gui.setScreen(new HudEditorScreen26(runtime)));
            reply("Opening the HUD editor");
            return;
        }
        if ("reset".equalsIgnoreCase(args[1])) {
            runtime.hudLayout().reset();
            replySaved("HUD layout reset to defaults", runtime.save());
            return;
        }
        reply("Usage: ;sealed hud <edit|reset>");
    }

    private void replySaved(String success, boolean saved) {
        reply(saved
                ? success
                : success + " (memory only; config save failed)");
    }

    private static void reply(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Sealed] " + message));
        }
    }
}
