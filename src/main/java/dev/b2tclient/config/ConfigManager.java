package dev.b2tclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.b2tclient.B2TClient;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleManager;
import dev.b2tclient.core.setting.Setting;
import dev.b2tclient.service.FriendManager;
import dev.b2tclient.service.Waypoint;
import dev.b2tclient.service.WaypointManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class ConfigManager {
    public static final int FORMAT_VERSION = 2;
    public static final String DEFAULT_PROFILE = "default";
    public static final int PORTABLE_PROFILE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_CONFIG_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_PORTABLE_PROFILE_CHARS = 256 * 1024;
    private static final int MAX_PORTABLE_PROFILE_DEPTH = 32;
    private static final String PORTABLE_PROFILE_FORMAT = "b2t-profile";

    private final ModuleManager moduleManager;
    private final FriendManager friendManager;
    private final WaypointManager waypointManager;
    private final Path directory;
    private final Path configFile;
    private final Path backupFile;
    private final Map<String, JsonObject> profiles = new LinkedHashMap<>();
    private final Map<String, String> serverBindings = new LinkedHashMap<>();

    private String activeProfile = DEFAULT_PROFILE;
    private JsonObject rootExtras = new JsonObject();
    private JsonObject presetUndoSnapshot;
    private String presetUndoProfile;

    public ConfigManager(
            ModuleManager moduleManager,
            FriendManager friendManager,
            WaypointManager waypointManager
    ) {
        this(
                moduleManager,
                friendManager,
                waypointManager,
                FabricLoader.getInstance().getConfigDir().resolve(B2TClient.MOD_ID)
        );
    }

    ConfigManager(
            ModuleManager moduleManager,
            FriendManager friendManager,
            WaypointManager waypointManager,
            Path directory
    ) {
        this.moduleManager = moduleManager;
        this.friendManager = friendManager;
        this.waypointManager = waypointManager;
        this.directory = directory;
        configFile = directory.resolve("config.json");
        backupFile = directory.resolve("config.json.bak");
        profiles.put(DEFAULT_PROFILE, emptyProfile());
    }

    public synchronized void load(Minecraft minecraft) {
        clearPresetUndo();
        if (!Files.isRegularFile(configFile)) {
            save();
            return;
        }

        if (tryLoad(configFile, minecraft)) {
            refreshBackup();
            save();
            return;
        }

        resetAll(minecraft);
        preserveCorruptConfig();
        if (Files.isRegularFile(backupFile) && tryLoad(backupFile, minecraft)) {
            B2TClient.LOGGER.warn("Recovered B2T Client configuration from {}", backupFile);
        } else {
            profiles.clear();
            profiles.put(DEFAULT_PROFILE, emptyProfile());
            activeProfile = DEFAULT_PROFILE;
            friendManager.replaceAll(List.of());
            waypointManager.replaceAll(List.of());
            serverBindings.clear();
            rootExtras = new JsonObject();
            B2TClient.LOGGER.warn("Using safe defaults because no valid configuration was found");
        }
        save();
    }

    private boolean tryLoad(Path source, Minecraft minecraft) {
        try {
            long size = Files.size(source);
            if (size > MAX_CONFIG_BYTES) {
                throw new IOException(
                        "Configuration exceeds the " + MAX_CONFIG_BYTES + "-byte safety limit"
                );
            }
        } catch (IOException exception) {
            B2TClient.LOGGER.error("Could not inspect {}", source, exception);
            return false;
        }
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                throw new IOException("Configuration root is not an object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            int version = integerMember(root, "formatVersion").orElse(1);
            if (version > FORMAT_VERSION) {
                throw new IOException("Unsupported configuration version " + version);
            }
            captureRootExtras(root);

            profiles.clear();
            serverBindings.clear();
            friendManager.replaceAll(List.of());
            waypointManager.replaceAll(List.of());

            if (version <= 1) {
                JsonObject modules = objectMember(root, "modules");
                if (modules == null) {
                    throw new IOException("Legacy configuration does not contain modules");
                }
                JsonObject migrated = emptyProfile();
                migrated.add("modules", modules.deepCopy());
                profiles.put(DEFAULT_PROFILE, migrated);
                activeProfile = DEFAULT_PROFILE;
                B2TClient.LOGGER.info("Migrating B2T Client configuration from v1 to v2");
            } else {
                loadVersionTwo(root);
            }

            resetAll(minecraft);
            applyProfile(activeProfile, minecraft);
            return true;
        } catch (IOException | RuntimeException exception) {
            B2TClient.LOGGER.error("Could not load {}", source, exception);
            return false;
        }
    }

    private void loadVersionTwo(JsonObject root) throws IOException {
        JsonObject profileObject = objectMember(root, "profiles");
        if (profileObject == null || profileObject.size() == 0) {
            throw new IOException("Configuration does not contain profiles");
        }
        for (Map.Entry<String, JsonElement> entry : profileObject.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                profiles.put(normalizeProfile(entry.getKey()), entry.getValue().getAsJsonObject());
            }
        }
        if (profiles.isEmpty()) {
            throw new IOException("Configuration has no valid profiles");
        }

        String requestedProfile = stringMember(root, "activeProfile").orElse(DEFAULT_PROFILE);
        activeProfile = normalizeProfile(requestedProfile);
        if (!profiles.containsKey(activeProfile)) {
            activeProfile = profiles.keySet().iterator().next();
        }

        JsonObject bindings = objectMember(root, "serverBindings");
        if (bindings != null) {
            for (Map.Entry<String, JsonElement> entry : bindings.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String profile = normalizeProfile(entry.getValue().getAsString());
                if (profiles.containsKey(profile)) {
                    serverBindings.put(normalizeServer(entry.getKey()), profile);
                }
            }
        }
        loadFriends(root.get("friends"));
        loadWaypoints(root.get("waypoints"));
    }

    private void applyProfile(String profileName, Minecraft minecraft) {
        JsonObject profile = profiles.get(normalizeProfile(profileName));
        JsonObject modulesObject = profile == null ? null : objectMember(profile, "modules");
        if (modulesObject == null) {
            return;
        }

        for (Module module : moduleManager.all()) {
            JsonObject moduleObject = objectMember(modulesObject, module.id());
            if (moduleObject == null) {
                continue;
            }
            try {
                JsonElement keyElement = moduleObject.get("key");
                if (keyElement != null && keyElement.isJsonPrimitive()) {
                    module.setKeyCode(keyElement.getAsInt());
                }
                JsonElement favoriteElement = moduleObject.get("favorite");
                if (favoriteElement != null && favoriteElement.isJsonPrimitive()) {
                    module.setFavorite(favoriteElement.getAsBoolean());
                }

                JsonObject settingsObject = objectMember(moduleObject, "settings");
                if (settingsObject != null) {
                    for (Setting<?> setting : module.settings()) {
                        if (!settingsObject.has(setting.id())) {
                            continue;
                        }
                        try {
                            setting.fromJson(settingsObject.get(setting.id()));
                        } catch (RuntimeException exception) {
                            B2TClient.LOGGER.warn(
                                    "Ignoring invalid value for {}.{}",
                                    module.id(),
                                    setting.id()
                            );
                        }
                    }
                }

                JsonElement enabledElement = moduleObject.get("enabled");
                if (enabledElement != null && enabledElement.isJsonPrimitive()) {
                    module.setEnabled(enabledElement.getAsBoolean(), minecraft);
                }
            } catch (RuntimeException exception) {
                B2TClient.LOGGER.warn("Ignoring invalid module entry {}", module.id());
            }
        }
    }

    public synchronized void save() {
        captureActiveProfile();

        JsonObject root = rootExtras.deepCopy();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("activeProfile", activeProfile);

        JsonObject profileObject = new JsonObject();
        profiles.forEach((name, profile) -> profileObject.add(name, profile.deepCopy()));
        root.add("profiles", profileObject);

        JsonObject bindings = new JsonObject();
        serverBindings.forEach(bindings::addProperty);
        root.add("serverBindings", bindings);
        root.add("friends", serializeFriends());
        root.add("waypoints", serializeWaypoints());

        writeAtomically(root);
    }

    public synchronized boolean createProfile(String name, boolean copyCurrent) {
        String normalized = normalizeProfile(name);
        if (profiles.containsKey(normalized)) {
            return false;
        }
        captureActiveProfile();
        profiles.put(
                normalized,
                copyCurrent ? profiles.get(activeProfile).deepCopy() : emptyProfile()
        );
        save();
        return true;
    }

    public synchronized boolean switchProfile(String name, Minecraft minecraft) {
        String normalized = normalizeProfile(name);
        if (!profiles.containsKey(normalized) || normalized.equals(activeProfile)) {
            return false;
        }
        captureActiveProfile();
        resetAll(minecraft);
        activeProfile = normalized;
        applyProfile(activeProfile, minecraft);
        clearPresetUndo();
        save();
        return true;
    }

    public synchronized boolean deleteProfile(String name) {
        String normalized = normalizeProfile(name);
        if (normalized.equals(activeProfile) || normalized.equals(DEFAULT_PROFILE)) {
            return false;
        }
        boolean removed = profiles.remove(normalized) != null;
        if (removed) {
            serverBindings.entrySet().removeIf(entry -> entry.getValue().equals(normalized));
            save();
        }
        return removed;
    }

    public synchronized Set<String> profileNames() {
        return Set.copyOf(new TreeSet<>(profiles.keySet()));
    }

    public synchronized String activeProfile() {
        return activeProfile;
    }

    public synchronized void bindServer(String server, String profileName) {
        String normalizedProfile = normalizeProfile(profileName);
        if (!profiles.containsKey(normalizedProfile)) {
            throw new IllegalArgumentException("Unknown profile: " + profileName);
        }
        serverBindings.put(normalizeServer(server), normalizedProfile);
        save();
    }

    public synchronized Optional<String> profileForServer(String server) {
        return Optional.ofNullable(serverBindings.get(normalizeServer(server)));
    }

    public synchronized List<PresetInfo> builtInPresets() {
        return BuiltInPresetCatalog.all().stream()
                .map(preset -> new PresetInfo(
                        preset.id(),
                        preset.name(),
                        preset.description()
                ))
                .toList();
    }

    public synchronized PresetPreview previewPreset(String presetId) {
        BuiltInPresetCatalog.Preset preset = requirePreset(presetId);
        List<PresetChange> changes = new ArrayList<>();
        int missingModules = 0;
        int riskyEnables = 0;

        for (BuiltInPresetCatalog.ModulePatch patch : preset.modules()) {
            Module module = moduleManager.find(patch.moduleId()).orElse(null);
            if (module == null) {
                missingModules++;
                continue;
            }

            boolean riskyEnable = patch.enabled()
                    && !module.isEnabled()
                    && module.risk() != dev.b2tclient.core.ModuleRisk.PASSIVE;
            if (module.isEnabled() != patch.enabled()) {
                changes.add(new PresetChange(
                        module.id(),
                        module.name(),
                        "Enabled",
                        module.isEnabled() ? "On" : "Off",
                        patch.enabled() ? "On" : "Off",
                        riskyEnable
                ));
                if (riskyEnable) {
                    riskyEnables++;
                }
            }

            for (Map.Entry<String, JsonElement> requested : patch.settings().entrySet()) {
                Setting<?> setting = findSetting(module, requested.getKey());
                if (setting == null || setting.toJson().equals(requested.getValue())) {
                    continue;
                }
                changes.add(new PresetChange(
                        module.id(),
                        module.name(),
                        setting.name(),
                        displayJson(setting.toJson()),
                        displayJson(requested.getValue()),
                        false
                ));
            }
        }

        return new PresetPreview(
                new PresetInfo(preset.id(), preset.name(), preset.description()),
                List.copyOf(changes),
                riskyEnables,
                missingModules
        );
    }

    /**
     * Applies a partial preset to the active profile. Enabling any non-passive
     * module is skipped unless the caller explicitly confirms risky enables.
     */
    public synchronized PresetApplyResult applyPreset(
            String presetId,
            Minecraft minecraft,
            boolean confirmRiskyEnables
    ) {
        BuiltInPresetCatalog.Preset preset = requirePreset(presetId);
        captureActiveProfile();
        JsonObject before = profiles.get(activeProfile).deepCopy();
        int moduleChanges = 0;
        int settingChanges = 0;
        int skippedRiskyEnables = 0;
        int missingModules = 0;

        for (BuiltInPresetCatalog.ModulePatch patch : preset.modules()) {
            Module module = moduleManager.find(patch.moduleId()).orElse(null);
            if (module == null) {
                missingModules++;
                continue;
            }

            if (!patch.enabled() && module.isEnabled()) {
                if (!module.setEnabled(false, minecraft)) {
                    restoreProfileSnapshot(before, minecraft);
                    return PresetApplyResult.failed(preset.id(), module.id(), missingModules);
                }
                moduleChanges++;
            }

            for (Map.Entry<String, JsonElement> requested : patch.settings().entrySet()) {
                Setting<?> setting = findSetting(module, requested.getKey());
                if (setting == null) {
                    continue;
                }
                JsonElement previous = setting.toJson();
                try {
                    setting.fromJson(requested.getValue().deepCopy());
                } catch (RuntimeException exception) {
                    B2TClient.LOGGER.warn(
                            "Ignoring invalid preset value for {}.{}",
                            module.id(),
                            setting.id()
                    );
                }
                JsonElement applied = setting.toJson();
                if (!applied.equals(requested.getValue())) {
                    restoreProfileSnapshot(before, minecraft);
                    return PresetApplyResult.failed(preset.id(), module.id(), missingModules);
                }
                if (!previous.equals(applied)) {
                    settingChanges++;
                }
            }

            if (!patch.enabled() || module.isEnabled()) {
                continue;
            }
            if (module.risk() != dev.b2tclient.core.ModuleRisk.PASSIVE
                    && !confirmRiskyEnables) {
                skippedRiskyEnables++;
                continue;
            }
            if (!module.setEnabled(true, minecraft)) {
                restoreProfileSnapshot(before, minecraft);
                return PresetApplyResult.failed(preset.id(), module.id(), missingModules);
            }
            moduleChanges++;
        }

        if (moduleChanges + settingChanges > 0) {
            presetUndoSnapshot = before;
            presetUndoProfile = activeProfile;
            save();
        }
        return new PresetApplyResult(
                preset.id(),
                moduleChanges,
                settingChanges,
                skippedRiskyEnables,
                missingModules,
                true,
                null
        );
    }

    public synchronized boolean canUndoPreset() {
        return presetUndoSnapshot != null && activeProfile.equals(presetUndoProfile);
    }

    public synchronized boolean undoPreset(Minecraft minecraft) {
        if (!canUndoPreset()) {
            return false;
        }
        captureActiveProfile();
        JsonObject current = profiles.get(activeProfile).deepCopy();
        JsonObject snapshot = presetUndoSnapshot.deepCopy();
        resetAll(minecraft);
        profiles.put(activeProfile, snapshot);
        applyProfile(activeProfile, minecraft);
        if (!runtimeMatchesProfile(snapshot)) {
            restoreProfileSnapshot(current, minecraft);
            return false;
        }
        clearPresetUndo();
        save();
        return true;
    }

    /**
     * Exports only the active module profile. Friends, waypoints, server
     * bindings, and unknown config extensions are deliberately excluded.
     */
    public synchronized String exportActiveProfile() {
        captureActiveProfile();
        JsonObject root = new JsonObject();
        root.addProperty("format", PORTABLE_PROFILE_FORMAT);
        root.addProperty("version", PORTABLE_PROFILE_VERSION);
        root.addProperty("name", activeProfile);

        JsonObject modules = new JsonObject();
        for (Module module : moduleManager.all()) {
            JsonObject moduleObject = new JsonObject();
            moduleObject.addProperty("enabled", module.isEnabled());
            moduleObject.addProperty("key", module.keyCode());
            moduleObject.addProperty("favorite", module.isFavorite());
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.settings()) {
                settings.add(setting.id(), setting.toJson());
            }
            moduleObject.add("settings", settings);
            modules.add(module.id(), moduleObject);
        }
        root.add("modules", modules);
        return GSON.toJson(root);
    }

    public synchronized PortableProfilePreview previewPortableProfile(String payload) {
        JsonObject modules = parsePortableProfile(payload);
        List<PresetChange> changes = new ArrayList<>();
        int riskyEnables = 0;
        int unknownModules = 0;
        int unknownSettings = 0;

        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            Module module = moduleManager.find(entry.getKey()).orElse(null);
            if (module == null) {
                unknownModules++;
                continue;
            }
            JsonObject requested = entry.getValue().getAsJsonObject();
            Optional<Boolean> enabled = strictBoolean(requested, "enabled");
            if (enabled.isPresent() && enabled.get() != module.isEnabled()) {
                boolean risky = enabled.get()
                        && module.risk() != dev.b2tclient.core.ModuleRisk.PASSIVE;
                changes.add(new PresetChange(
                        module.id(),
                        module.name(),
                        "Enabled",
                        module.isEnabled() ? "On" : "Off",
                        enabled.get() ? "On" : "Off",
                        risky
                ));
                if (risky) {
                    riskyEnables++;
                }
            }
            Optional<Integer> key = strictKey(requested);
            if (key.isPresent() && key.get() != module.keyCode()) {
                changes.add(new PresetChange(
                        module.id(),
                        module.name(),
                        "Key",
                        Integer.toString(module.keyCode()),
                        Integer.toString(key.get()),
                        false
                ));
            }
            Optional<Boolean> favorite = strictBoolean(requested, "favorite");
            if (favorite.isPresent() && favorite.get() != module.isFavorite()) {
                changes.add(new PresetChange(
                        module.id(),
                        module.name(),
                        "Favorite",
                        module.isFavorite() ? "On" : "Off",
                        favorite.get() ? "On" : "Off",
                        false
                ));
            }

            JsonObject settings = objectMember(requested, "settings");
            if (settings == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> settingEntry : settings.entrySet()) {
                Setting<?> setting = findSetting(module, settingEntry.getKey());
                if (setting == null) {
                    unknownSettings++;
                } else if (!setting.toJson().equals(settingEntry.getValue())) {
                    changes.add(new PresetChange(
                            module.id(),
                            module.name(),
                            setting.name(),
                            displayJson(setting.toJson()),
                            displayJson(settingEntry.getValue()),
                            false
                    ));
                }
            }
        }
        return new PortableProfilePreview(
                List.copyOf(changes),
                riskyEnables,
                unknownModules,
                unknownSettings
        );
    }

    /**
     * Applies a portable profile as a partial, transactional patch. Enabling
     * combat, movement, packet, or automation modules requires an explicit
     * confirmation from the caller. Unknown forward-compatible entries are
     * counted and ignored.
     */
    public synchronized PortableProfileApplyResult importPortableProfile(
            String payload,
            Minecraft minecraft,
            boolean confirmRiskyEnables
    ) {
        JsonObject modules = parsePortableProfile(payload);
        captureActiveProfile();
        JsonObject before = profiles.get(activeProfile).deepCopy();
        int moduleChanges = 0;
        int settingChanges = 0;
        int skippedRiskyEnables = 0;
        int unknownModules = 0;
        int unknownSettings = 0;

        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            Module module = moduleManager.find(entry.getKey()).orElse(null);
            if (module == null) {
                unknownModules++;
                continue;
            }
            JsonObject requested = entry.getValue().getAsJsonObject();
            Optional<Boolean> enabled = strictBoolean(requested, "enabled");

            if (enabled.isPresent() && !enabled.get() && module.isEnabled()) {
                if (!module.setEnabled(false, minecraft)) {
                    restoreProfileSnapshot(before, minecraft);
                    return PortableProfileApplyResult.failed(module.id());
                }
                moduleChanges++;
            }

            Optional<Integer> key = strictKey(requested);
            if (key.isPresent() && key.get() != module.keyCode()) {
                module.setKeyCode(key.get());
                moduleChanges++;
            }
            Optional<Boolean> favorite = strictBoolean(requested, "favorite");
            if (favorite.isPresent() && favorite.get() != module.isFavorite()) {
                module.setFavorite(favorite.get());
                moduleChanges++;
            }

            JsonObject settings = objectMember(requested, "settings");
            if (settings != null) {
                for (Map.Entry<String, JsonElement> settingEntry : settings.entrySet()) {
                    Setting<?> setting = findSetting(module, settingEntry.getKey());
                    if (setting == null) {
                        unknownSettings++;
                        continue;
                    }
                    JsonElement previous = setting.toJson();
                    try {
                        setting.fromJson(settingEntry.getValue().deepCopy());
                    } catch (RuntimeException exception) {
                        restoreProfileSnapshot(before, minecraft);
                        return PortableProfileApplyResult.failed(module.id());
                    }
                    JsonElement applied = setting.toJson();
                    if (!applied.equals(settingEntry.getValue())) {
                        restoreProfileSnapshot(before, minecraft);
                        return PortableProfileApplyResult.failed(module.id());
                    }
                    if (!previous.equals(applied)) {
                        settingChanges++;
                    }
                }
            }

            if (enabled.isEmpty() || !enabled.get() || module.isEnabled()) {
                continue;
            }
            if (module.risk() != dev.b2tclient.core.ModuleRisk.PASSIVE
                    && !confirmRiskyEnables) {
                skippedRiskyEnables++;
                continue;
            }
            if (!module.setEnabled(true, minecraft)) {
                restoreProfileSnapshot(before, minecraft);
                return PortableProfileApplyResult.failed(module.id());
            }
            moduleChanges++;
        }

        if (moduleChanges + settingChanges > 0) {
            presetUndoSnapshot = before;
            presetUndoProfile = activeProfile;
            save();
        }
        return new PortableProfileApplyResult(
                moduleChanges,
                settingChanges,
                skippedRiskyEnables,
                unknownModules,
                unknownSettings,
                true,
                null
        );
    }

    public Path configFile() {
        return configFile;
    }

    private void captureActiveProfile() {
        JsonObject profile = profiles.computeIfAbsent(activeProfile, ignored -> emptyProfile());
        JsonObject previousModules = objectMember(profile, "modules");
        JsonObject modules = previousModules == null ? new JsonObject() : previousModules.deepCopy();

        for (Module module : moduleManager.all()) {
            JsonObject moduleObject = objectMember(modules, module.id());
            if (moduleObject == null) {
                moduleObject = new JsonObject();
            } else {
                moduleObject = moduleObject.deepCopy();
            }
            moduleObject.addProperty("enabled", module.isEnabled());
            moduleObject.addProperty("key", module.keyCode());
            moduleObject.addProperty("favorite", module.isFavorite());

            JsonObject settings = objectMember(moduleObject, "settings");
            settings = settings == null ? new JsonObject() : settings.deepCopy();
            for (Setting<?> setting : module.settings()) {
                settings.add(setting.id(), setting.toJson());
            }
            moduleObject.add("settings", settings);
            modules.add(module.id(), moduleObject);
        }
        profile.add("modules", modules);
    }

    private JsonArray serializeFriends() {
        JsonArray array = new JsonArray();
        for (FriendManager.Friend friend : friendManager.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", friend.name());
            if (friend.uuid() != null) {
                entry.addProperty("uuid", friend.uuid().toString());
            }
            array.add(entry);
        }
        return array;
    }

    private void loadFriends(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject entry = value.getAsJsonObject();
            String name = stringMember(entry, "name").orElse("");
            if (name.isBlank()) {
                continue;
            }
            UUID uuid = stringMember(entry, "uuid").flatMap(ConfigManager::parseUuid).orElse(null);
            friendManager.add(name, uuid);
        }
    }

    private JsonArray serializeWaypoints() {
        JsonArray array = new JsonArray();
        for (Waypoint waypoint : waypointManager.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", waypoint.name());
            entry.addProperty("server", waypoint.server());
            entry.addProperty("dimension", waypoint.dimension());
            entry.addProperty("x", waypoint.x());
            entry.addProperty("y", waypoint.y());
            entry.addProperty("z", waypoint.z());
            entry.addProperty("color", waypoint.color());
            entry.addProperty("visible", waypoint.visible());
            array.add(entry);
        }
        return array;
    }

    private void loadWaypoints(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                continue;
            }
            try {
                JsonObject entry = value.getAsJsonObject();
                waypointManager.add(new Waypoint(
                        stringMember(entry, "name").orElseThrow(),
                        stringMember(entry, "server").orElse("singleplayer"),
                        stringMember(entry, "dimension").orElse("minecraft:overworld"),
                        entry.get("x").getAsDouble(),
                        entry.get("y").getAsDouble(),
                        entry.get("z").getAsDouble(),
                        integerMember(entry, "color").orElse(0xff55d6be),
                        booleanMember(entry, "visible").orElse(true)
                ));
            } catch (RuntimeException ignored) {
                B2TClient.LOGGER.warn("Ignoring an invalid waypoint entry");
            }
        }
    }

    private void writeAtomically(JsonObject root) {
        Path temporaryFile = directory.resolve("config.json.tmp");
        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(
                        temporaryFile,
                        configFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            B2TClient.LOGGER.error("Could not save {}", configFile, exception);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ignored) {
                // The next save safely replaces a stale temporary file.
            }
        }
    }

    private void refreshBackup() {
        try {
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            B2TClient.LOGGER.warn("Could not refresh configuration backup {}", backupFile);
        }
    }

    private void resetAll(Minecraft minecraft) {
        for (Module module : moduleManager.all()) {
            module.reset(minecraft);
        }
    }

    private void preserveCorruptConfig() {
        if (!Files.isRegularFile(configFile)) {
            return;
        }
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        Path destination = directory.resolve("config.corrupt-" + timestamp + ".json");
        try {
            Files.move(configFile, destination, StandardCopyOption.REPLACE_EXISTING);
            B2TClient.LOGGER.warn("Preserved unreadable configuration as {}", destination);
        } catch (IOException exception) {
            B2TClient.LOGGER.error("Could not preserve unreadable configuration {}", configFile);
        }
    }

    private void captureRootExtras(JsonObject root) {
        rootExtras = root.deepCopy();
        rootExtras.remove("formatVersion");
        rootExtras.remove("activeProfile");
        rootExtras.remove("profiles");
        rootExtras.remove("modules");
        rootExtras.remove("serverBindings");
        rootExtras.remove("friends");
        rootExtras.remove("waypoints");
    }

    private void clearPresetUndo() {
        presetUndoSnapshot = null;
        presetUndoProfile = null;
    }

    private void restoreProfileSnapshot(JsonObject snapshot, Minecraft minecraft) {
        resetAll(minecraft);
        profiles.put(activeProfile, snapshot.deepCopy());
        applyProfile(activeProfile, minecraft);
    }

    private boolean runtimeMatchesProfile(JsonObject profile) {
        JsonObject modulesObject = objectMember(profile, "modules");
        if (modulesObject == null) {
            return true;
        }
        for (Module module : moduleManager.all()) {
            JsonObject moduleObject = objectMember(modulesObject, module.id());
            if (moduleObject == null) {
                continue;
            }
            Optional<Boolean> enabled = booleanMember(moduleObject, "enabled");
            if (enabled.isPresent() && enabled.get() != module.isEnabled()) {
                return false;
            }
            Optional<Integer> key = integerMember(moduleObject, "key");
            if (key.isPresent() && key.get() != module.keyCode()) {
                return false;
            }
            Optional<Boolean> favorite = booleanMember(moduleObject, "favorite");
            if (favorite.isPresent() && favorite.get() != module.isFavorite()) {
                return false;
            }
            JsonObject settings = objectMember(moduleObject, "settings");
            if (settings == null) {
                continue;
            }
            for (Setting<?> setting : module.settings()) {
                if (settings.has(setting.id())
                        && !settings.get(setting.id()).equals(setting.toJson())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BuiltInPresetCatalog.Preset requirePreset(String presetId) {
        return BuiltInPresetCatalog.find(presetId).orElseThrow(
                () -> new IllegalArgumentException("Unknown built-in preset: " + presetId)
        );
    }

    private static Setting<?> findSetting(Module module, String settingId) {
        for (Setting<?> setting : module.settings()) {
            if (setting.id().equals(settingId)) {
                return setting;
            }
        }
        return null;
    }

    private static String displayJson(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        return value.toString();
    }

    private static JsonObject parsePortableProfile(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Portable profile is empty");
        }
        if (payload.length() > MAX_PORTABLE_PROFILE_CHARS) {
            throw new IllegalArgumentException("Portable profile exceeds the size limit");
        }
        requireBoundedJsonDepth(payload);

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(payload);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Portable profile is not valid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Portable profile root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!strictString(root, "format").filter(PORTABLE_PROFILE_FORMAT::equals).isPresent()) {
            throw new IllegalArgumentException("Portable profile format is not supported");
        }
        if (strictInteger(root, "version").orElse(-1) != PORTABLE_PROFILE_VERSION) {
            throw new IllegalArgumentException("Portable profile version is not supported");
        }
        JsonObject modules = objectMember(root, "modules");
        if (modules == null) {
            throw new IllegalArgumentException("Portable profile has no modules object");
        }
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_]{1,64}")
                    || !entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Portable profile contains an invalid module entry");
            }
            JsonObject module = entry.getValue().getAsJsonObject();
            validateOptionalBoolean(module, "enabled");
            validateOptionalBoolean(module, "favorite");
            strictKey(module);
            JsonElement settings = module.get("settings");
            if (settings != null && !settings.isJsonObject()) {
                throw new IllegalArgumentException("Portable profile settings must be an object");
            }
        }
        return modules;
    }

    private static void requireBoundedJsonDepth(String payload) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < payload.length(); index++) {
            char current = payload.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                depth++;
                if (depth > MAX_PORTABLE_PROFILE_DEPTH) {
                    throw new IllegalArgumentException("Portable profile is nested too deeply");
                }
            } else if (current == '}' || current == ']') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("Portable profile has unbalanced JSON");
                }
            }
        }
        if (inString || depth != 0) {
            throw new IllegalArgumentException("Portable profile has incomplete JSON");
        }
    }

    private static Optional<String> strictString(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                ? Optional.of(element.getAsString())
                : Optional.empty();
    }

    private static Optional<Integer> strictInteger(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)
                || value != Math.rint(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return Optional.of((int) value);
    }

    private static Optional<Integer> strictKey(JsonObject parent) {
        Optional<Integer> key = strictInteger(parent, "key");
        if (key.isPresent() && (key.get() < -1 || key.get() > 512)) {
            throw new IllegalArgumentException("Portable profile key is outside the supported range");
        }
        return key;
    }

    private static Optional<Boolean> strictBoolean(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return Optional.of(element.getAsBoolean());
    }

    private static void validateOptionalBoolean(JsonObject parent, String name) {
        strictBoolean(parent, name);
    }

    private static JsonObject emptyProfile() {
        JsonObject result = new JsonObject();
        result.add("modules", new JsonObject());
        return result;
    }

    private static String normalizeProfile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Profile name cannot be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid profile name: " + value);
        }
        return normalized;
    }

    private static String normalizeServer(String value) {
        if (value == null || value.isBlank()) {
            return "singleplayer";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static JsonObject objectMember(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static Optional<String> stringMember(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive()
                ? Optional.of(element.getAsString())
                : Optional.empty();
    }

    private static Optional<Integer> integerMember(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        try {
            return element != null && element.isJsonPrimitive()
                    ? Optional.of(element.getAsInt())
                    : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> booleanMember(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        try {
            return element != null && element.isJsonPrimitive()
                    ? Optional.of(element.getAsBoolean())
                    : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public record PresetInfo(String id, String name, String description) {
    }

    public record PresetChange(
            String moduleId,
            String moduleName,
            String field,
            String before,
            String after,
            boolean requiresRiskConfirmation
    ) {
    }

    public record PresetPreview(
            PresetInfo preset,
            List<PresetChange> changes,
            int riskyEnableCount,
            int missingModuleCount
    ) {
    }

    public record PresetApplyResult(
            String presetId,
            int moduleChanges,
            int settingChanges,
            int skippedRiskyEnables,
            int missingModuleCount,
            boolean successful,
            String failedModuleId
    ) {
        public boolean changed() {
            return successful && moduleChanges + settingChanges > 0;
        }

        private static PresetApplyResult failed(
                String presetId,
                String failedModuleId,
                int missingModuleCount
        ) {
            return new PresetApplyResult(
                    presetId,
                    0,
                    0,
                    0,
                    missingModuleCount,
                    false,
                    failedModuleId
            );
        }
    }

    public record PortableProfilePreview(
            List<PresetChange> changes,
            int riskyEnableCount,
            int unknownModuleCount,
            int unknownSettingCount
    ) {
    }

    public record PortableProfileApplyResult(
            int moduleChanges,
            int settingChanges,
            int skippedRiskyEnables,
            int unknownModuleCount,
            int unknownSettingCount,
            boolean successful,
            String failedModuleId
    ) {
        public boolean changed() {
            return successful && moduleChanges + settingChanges > 0;
        }

        private static PortableProfileApplyResult failed(String failedModuleId) {
            return new PortableProfileApplyResult(
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    failedModuleId
            );
        }
    }
}
