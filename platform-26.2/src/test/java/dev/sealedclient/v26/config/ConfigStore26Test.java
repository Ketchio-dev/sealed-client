package dev.sealedclient.v26.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.sealedclient.common.profile.ProfileBook;
import dev.sealedclient.common.social.FriendBook;
import dev.sealedclient.common.social.FriendEntry;
import dev.sealedclient.common.waypoint.Waypoint;
import dev.sealedclient.common.waypoint.WaypointBook;
import dev.sealedclient.v26.PlatformCapabilities26;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStore26Test {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stateRoundTripsThroughAtomicLocalFile() throws Exception {
        Path file = temporaryDirectory.resolve("sealedclient-26.2.json");
        ConfigStore26 store = new ConfigStore26(file);
        var modules = PlatformCapabilities26.createRegistry();
        var profiles = new ProfileBook();
        var friends = new FriendBook();
        var waypoints = new WaypointBook();
        modules.find("clock").orElseThrow().setEnabled(true);
        modules.find("anti_afk").orElseThrow().setEnabled(true);
        profiles.capture("2b2t", "2b2t.org", modules);
        UUID uuid = UUID.randomUUID();
        friends.put(new FriendEntry("Steve", uuid));
        waypoints.put(new Waypoint(
                "base", "2b2t.org", "minecraft:overworld", 1, 64, 2, 0xFFFFAA00, true
        ));

        store.save(modules, profiles, friends, waypoints);

        var loadedModules = PlatformCapabilities26.createRegistry();
        var loadedProfiles = new ProfileBook();
        var loadedFriends = new FriendBook();
        var loadedWaypoints = new WaypointBook();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(loadedModules, loadedProfiles, loadedFriends, loadedWaypoints)
        );
        assertTrue(loadedModules.find("clock").orElseThrow().enabled());
        assertTrue(loadedModules.find("anti_afk").orElseThrow().enabled());
        assertEquals("2b2t", loadedProfiles.active().orElseThrow().name());
        assertEquals(uuid, loadedFriends.findByName("steve").orElseThrow().uuid());
        assertEquals("2b2t.org", loadedWaypoints.find("base").orElseThrow().server());
    }

    @Test
    void malformedFileIsQuarantinedWithoutApplyingPartialState() throws Exception {
        Path file = temporaryDirectory.resolve("sealedclient-26.2.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"modules\":{\"clock\":{\"enabled\":true}}");
        ConfigStore26 store = new ConfigStore26(file);
        var modules = PlatformCapabilities26.createRegistry();

        assertEquals(
                ConfigStore26.LoadResult.CORRUPT,
                store.load(modules, new ProfileBook(), new FriendBook(), new WaypointBook())
        );
        assertFalse(modules.find("clock").orElseThrow().enabled());
        assertFalse(Files.exists(file));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void invalidLateSettingAndProfileAreValidatedBeforeAnyCommit()
            throws Exception {
        Path file = temporaryDirectory.resolve("sealedclient-26.2.json");
        ConfigStore26 store = new ConfigStore26(file);
        var sourceModules = PlatformCapabilities26.createRegistry();
        sourceModules.find("clock").orElseThrow().setEnabled(true);
        var sourceProfiles = new ProfileBook();
        sourceProfiles.capture("unsafe", "*", sourceModules);
        store.save(
                sourceModules,
                sourceProfiles,
                new FriendBook(),
                new WaypointBook()
        );

        var root = JsonParser.parseString(Files.readString(file))
                .getAsJsonObject();
        root.getAsJsonArray("profiles")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("modules")
                .getAsJsonObject("chams")
                .getAsJsonObject("settings")
                .addProperty("color", "not-a-color");
        Files.writeString(file, new Gson().toJson(root));

        var targetModules = PlatformCapabilities26.createRegistry();
        assertFalse(targetModules.find("clock").orElseThrow().enabled());
        assertEquals(
                ConfigStore26.LoadResult.CORRUPT,
                store.load(
                        targetModules,
                        new ProfileBook(),
                        new FriendBook(),
                        new WaypointBook()
                )
        );
        assertFalse(targetModules.find("clock").orElseThrow().enabled());
        assertEquals(
                "A0FF5555",
                targetModules.find("chams").orElseThrow().settings().stream()
                        .filter(setting -> "color".equals(setting.id()))
                        .findFirst()
                        .orElseThrow()
                        .serialize()
        );
    }

    @Test
    void legacyTransparentChamsColorMigratesWithoutChangingRgb()
            throws Exception {
        Path file = temporaryDirectory.resolve("sealedclient-26.2.json");
        ConfigStore26 store = new ConfigStore26(file);
        var source = PlatformCapabilities26.createRegistry();
        var sourceProfiles = new ProfileBook();
        sourceProfiles.capture("legacy", "*", source);
        store.save(
                source,
                sourceProfiles,
                new FriendBook(),
                new WaypointBook()
        );

        var root = JsonParser.parseString(Files.readString(file))
                .getAsJsonObject();
        root.getAsJsonObject("modules")
                .getAsJsonObject("chams")
                .getAsJsonObject("settings")
                .addProperty("color", "00FF5555");
        root.getAsJsonArray("profiles")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("modules")
                .getAsJsonObject("chams")
                .getAsJsonObject("settings")
                .addProperty("color", "00FF5555");
        Files.writeString(file, new Gson().toJson(root));

        var loaded = PlatformCapabilities26.createRegistry();
        var profiles = new ProfileBook();
        assertEquals(
                ConfigStore26.LoadResult.LOADED,
                store.load(
                        loaded,
                        profiles,
                        new FriendBook(),
                        new WaypointBook()
                )
        );
        assertEquals(
                "FFFF5555",
                loaded.find("chams").orElseThrow().settings().stream()
                        .filter(setting -> "color".equals(setting.id()))
                        .findFirst()
                        .orElseThrow()
                        .serialize()
        );
        assertEquals(
                "FFFF5555",
                profiles.find("legacy").orElseThrow()
                        .modules().get("chams").settings().get("color")
        );
    }

    @Test
    void oversizedFileIsRejectedBeforeParsing() throws Exception {
        Path file = temporaryDirectory.resolve("sealedclient-26.2.json");
        Files.write(file, new byte[(int) ConfigStore26.MAX_CONFIG_BYTES + 1]);
        ConfigStore26 store = new ConfigStore26(file);

        assertEquals(
                ConfigStore26.LoadResult.CORRUPT,
                store.load(
                        PlatformCapabilities26.createRegistry(),
                        new ProfileBook(),
                        new FriendBook(),
                        new WaypointBook()
                )
        );
    }
}
