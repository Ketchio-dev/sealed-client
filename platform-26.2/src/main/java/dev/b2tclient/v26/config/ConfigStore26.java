package dev.b2tclient.v26.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.b2tclient.common.module.ModuleRegistry;
import dev.b2tclient.common.module.ModuleSnapshot;
import dev.b2tclient.common.profile.ClientProfile;
import dev.b2tclient.common.profile.ProfileBook;
import dev.b2tclient.common.social.FriendBook;
import dev.b2tclient.common.social.FriendEntry;
import dev.b2tclient.common.waypoint.Waypoint;
import dev.b2tclient.common.waypoint.WaypointBook;
import dev.b2tclient.v26.hud.HudLayout26;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfigStore26 {
    static final int SCHEMA_VERSION = 1;
    static final long MAX_CONFIG_BYTES = 1_048_576;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public ConfigStore26(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static ConfigStore26 defaultStore() {
        return new ConfigStore26(FabricLoader.getInstance().getConfigDir().resolve("b2tclient-26.2.json"));
    }

    public LoadResult load(
            ModuleRegistry modules,
            ProfileBook profiles,
            FriendBook friends,
            WaypointBook waypoints
    ) {
        return load(modules, profiles, friends, waypoints, new HudLayout26());
    }

    public LoadResult load(
            ModuleRegistry modules,
            ProfileBook profiles,
            FriendBook friends,
            WaypointBook waypoints,
            HudLayout26 hudLayout
    ) {
        if (!Files.exists(file)) {
            return LoadResult.MISSING;
        }
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_CONFIG_BYTES) {
                throw new IOException("Config size is outside the safe range: " + size);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            ParsedConfig parsed = parse(root);

            modules.validate(parsed.modules());
            for (ClientProfile profile : parsed.profiles()) {
                modules.validate(profile.modules());
            }
            modules.apply(parsed.modules());
            profiles.replaceAll(parsed.profiles(), parsed.activeProfile());
            friends.replaceAll(parsed.friends());
            waypoints.replaceAll(parsed.waypoints());
            hudLayout.applySnapshot(parsed.hudLayout());
            return LoadResult.LOADED;
        } catch (Exception exception) {
            quarantineCorruptFile();
            return LoadResult.CORRUPT;
        }
    }

    public void save(
            ModuleRegistry modules,
            ProfileBook profiles,
            FriendBook friends,
            WaypointBook waypoints
    ) throws IOException {
        save(modules, profiles, friends, waypoints, new HudLayout26());
    }

    public void save(
            ModuleRegistry modules,
            ProfileBook profiles,
            FriendBook friends,
            WaypointBook waypoints,
            HudLayout26 hudLayout
    ) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("modules", encodeModules(modules.snapshot()));
        root.addProperty("activeProfile", profiles.active().map(ClientProfile::name).orElse(""));

        JsonArray profileArray = new JsonArray();
        for (ClientProfile profile : profiles.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", profile.name());
            entry.addProperty("serverPattern", profile.serverPattern());
            entry.add("modules", encodeModules(profile.modules()));
            profileArray.add(entry);
        }
        root.add("profiles", profileArray);

        JsonArray friendArray = new JsonArray();
        for (FriendEntry friend : friends.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", friend.name());
            if (friend.uuid() != null) {
                entry.addProperty("uuid", friend.uuid().toString());
            }
            friendArray.add(entry);
        }
        root.add("friends", friendArray);

        JsonArray waypointArray = new JsonArray();
        for (Waypoint waypoint : waypoints.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", waypoint.name());
            entry.addProperty("server", waypoint.server());
            entry.addProperty("dimension", waypoint.dimension());
            entry.addProperty("x", waypoint.x());
            entry.addProperty("y", waypoint.y());
            entry.addProperty("z", waypoint.z());
            entry.addProperty("color", waypoint.color());
            entry.addProperty("visible", waypoint.visible());
            waypointArray.add(entry);
        }
        root.add("waypoints", waypointArray);

        JsonObject hud = new JsonObject();
        hudLayout.snapshot().forEach((panel, anchor) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", anchor.xFraction());
            entry.addProperty("y", anchor.yFraction());
            hud.add(panel.name(), entry);
        });
        root.add("hudLayout", hud);

        byte[] encoded = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_CONFIG_BYTES) {
            throw new IOException("Refusing to write config larger than " + MAX_CONFIG_BYTES + " bytes");
        }
        writeAtomically(encoded);
    }

    private static ParsedConfig parse(JsonObject root) {
        int schema = required(root, "schemaVersion").getAsInt();
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema version: " + schema);
        }

        Map<String, ModuleSnapshot> modules = parseModules(required(root, "modules").getAsJsonObject());
        List<ClientProfile> profiles = new ArrayList<>();
        for (JsonElement element : required(root, "profiles").getAsJsonArray()) {
            JsonObject profile = element.getAsJsonObject();
            profiles.add(new ClientProfile(
                    required(profile, "name").getAsString(),
                    required(profile, "serverPattern").getAsString(),
                    parseModules(required(profile, "modules").getAsJsonObject())
            ));
        }

        List<FriendEntry> friends = new ArrayList<>();
        for (JsonElement element : required(root, "friends").getAsJsonArray()) {
            JsonObject friend = element.getAsJsonObject();
            UUID uuid = friend.has("uuid") ? UUID.fromString(friend.get("uuid").getAsString()) : null;
            friends.add(new FriendEntry(required(friend, "name").getAsString(), uuid));
        }

        List<Waypoint> waypoints = new ArrayList<>();
        for (JsonElement element : required(root, "waypoints").getAsJsonArray()) {
            JsonObject waypoint = element.getAsJsonObject();
            waypoints.add(new Waypoint(
                    required(waypoint, "name").getAsString(),
                    required(waypoint, "server").getAsString(),
                    required(waypoint, "dimension").getAsString(),
                    required(waypoint, "x").getAsDouble(),
                    required(waypoint, "y").getAsDouble(),
                    required(waypoint, "z").getAsDouble(),
                    required(waypoint, "color").getAsInt(),
                    required(waypoint, "visible").getAsBoolean()
            ));
        }

        String activeProfile = root.has("activeProfile") ? root.get("activeProfile").getAsString() : "";
        return new ParsedConfig(
                modules,
                profiles,
                activeProfile,
                friends,
                waypoints,
                parseHudLayout(root)
        );
    }

    /**
     * Reads the optional HUD layout. Unknown panel names and non-finite
     * fractions are dropped rather than rejected, so a config written by a
     * newer build still loads on this one.
     */
    private static Map<HudLayout26.Panel, HudLayout26.Anchor> parseHudLayout(JsonObject root) {
        Map<HudLayout26.Panel, HudLayout26.Anchor> layout = new LinkedHashMap<>();
        if (!root.has("hudLayout") || !root.get("hudLayout").isJsonObject()) {
            return layout;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("hudLayout").entrySet()) {
            HudLayout26.Panel panel = HudLayout26.Panel.byId(entry.getKey());
            if (panel == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject anchor = entry.getValue().getAsJsonObject();
            if (!anchor.has("x") || !anchor.has("y")) {
                continue;
            }
            layout.put(panel, HudLayout26.Anchor.clampFractions(new HudLayout26.Anchor(
                    anchor.get("x").getAsDouble(),
                    anchor.get("y").getAsDouble()
            )));
        }
        return layout;
    }

    private static Map<String, ModuleSnapshot> parseModules(JsonObject object) {
        Map<String, ModuleSnapshot> modules = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonObject state = entry.getValue().getAsJsonObject();
            Map<String, String> settings = new LinkedHashMap<>();
            if (state.has("settings")) {
                for (Map.Entry<String, JsonElement> setting
                        : state.getAsJsonObject("settings").entrySet()) {
                    String value = setting.getValue().getAsString();
                    settings.put(
                            setting.getKey(),
                            normalizeLegacySetting(
                                    entry.getKey(),
                                    setting.getKey(),
                                    value
                            )
                    );
                }
            }
            modules.put(entry.getKey(), new ModuleSnapshot(
                    required(state, "enabled").getAsBoolean(),
                    state.has("favorite") && state.get("favorite").getAsBoolean(),
                    state.has("keyCode") ? state.get("keyCode").getAsInt() : -1,
                    settings
            ));
        }
        return modules;
    }

    private static String normalizeLegacySetting(
            String moduleId,
            String settingId,
            String value
    ) {
        if ("chams".equals(moduleId)
                && "color".equals(settingId)
                && value != null
                && value.matches("[0-9A-Fa-f]{8}")
                && "00".equalsIgnoreCase(value.substring(0, 2))) {
            return "FF" + value.substring(2);
        }
        return value;
    }

    private static JsonObject encodeModules(Map<String, ModuleSnapshot> modules) {
        JsonObject result = new JsonObject();
        modules.forEach((id, snapshot) -> {
            JsonObject state = new JsonObject();
            state.addProperty("enabled", snapshot.enabled());
            state.addProperty("favorite", snapshot.favorite());
            state.addProperty("keyCode", snapshot.keyCode());
            JsonObject settings = new JsonObject();
            snapshot.settings().forEach(settings::addProperty);
            state.add("settings", settings);
            result.add(id, state);
        });
        return result;
    }

    private void writeAtomically(byte[] encoded) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "b2tclient-26.2-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                channel.write(ByteBuffer.wrap(encoded));
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void quarantineCorruptFile() {
        if (!Files.exists(file)) {
            return;
        }
        Path quarantine = file.resolveSibling(
                file.getFileName() + ".corrupt-" + System.currentTimeMillis()
        );
        try {
            Files.move(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Leave the original in place if even quarantine cannot be completed.
        }
    }

    private static JsonElement required(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Missing config member: " + member);
        }
        return value;
    }

    public Path file() {
        return file;
    }

    public enum LoadResult {
        LOADED,
        MISSING,
        CORRUPT
    }

    private record ParsedConfig(
            Map<String, ModuleSnapshot> modules,
            List<ClientProfile> profiles,
            String activeProfile,
            List<FriendEntry> friends,
            List<Waypoint> waypoints,
            Map<HudLayout26.Panel, HudLayout26.Anchor> hudLayout
    ) {
    }
}
