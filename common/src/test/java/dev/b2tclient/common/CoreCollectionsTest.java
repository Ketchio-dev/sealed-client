package dev.b2tclient.common;

import dev.b2tclient.common.module.*;
import dev.b2tclient.common.profile.ClientProfile;
import dev.b2tclient.common.profile.ProfileBook;
import dev.b2tclient.common.setting.StringSetting;
import dev.b2tclient.common.social.FriendBook;
import dev.b2tclient.common.social.FriendEntry;
import dev.b2tclient.common.waypoint.Waypoint;
import dev.b2tclient.common.waypoint.WaypointBook;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreCollectionsTest {
    @Test
    void friendsSupportCaseInsensitiveNamesAndUuidLookup() {
        FriendBook friends = new FriendBook();
        UUID uuid = UUID.randomUUID();
        friends.put(new FriendEntry("Steve", uuid));

        assertEquals("Steve", friends.findByName("steve").orElseThrow().displayName());
        assertEquals("Steve", friends.findByUuid(uuid).orElseThrow().name());
        assertTrue(friends.remove("STEVE"));
        assertTrue(friends.all().isEmpty());
    }

    @Test
    void waypointsCanBeFilteredByDimension() {
        WaypointBook waypoints = new WaypointBook();
        waypoints.put(new Waypoint("Main base", "2b2t.org", "minecraft:overworld", 1, 64, 2, 0xFFFFAA00, true));
        waypoints.put(new Waypoint("Portal", "2b2t.org", "minecraft:the_nether", 8, 70, 16, 0xFFFF5555, true));

        assertEquals(1, waypoints.inDimension("MINECRAFT:OVERWORLD").size());
        assertEquals("Portal", waypoints.find("PORTAL").orElseThrow().name());
        assertEquals(1, waypoints.visibleFor("2b2t.org", "minecraft:the_nether").size());
    }

    @Test
    void profilesCaptureAndApplyRegistryState() {
        ModuleRegistry registry = new ModuleRegistry();
        RegisteredModule module = registry.register(
                new ModuleDescriptor("coordinates", "Coordinates", "Draw coordinates", ModuleCategory.HUD, ModuleRisk.PASSIVE, true)
        );
        ProfileBook profiles = new ProfileBook();
        profiles.capture("default", "*", registry);

        module.setEnabled(false);
        assertTrue(profiles.activate("DEFAULT", registry));
        assertTrue(module.enabled());
        assertEquals("default", profiles.active().orElseThrow().name());
    }

    @Test
    void reconnectReappliesSnapshotEvenWhenProfileIsAlreadyActive() {
        ModuleRegistry registry = new ModuleRegistry();
        RegisteredModule module = registry.register(
                new ModuleDescriptor(
                        "coordinates",
                        "Coordinates",
                        "Draw coordinates",
                        ModuleCategory.HUD,
                        ModuleRisk.PASSIVE,
                        true
                )
        );
        ProfileBook profiles = new ProfileBook();
        profiles.capture("2b2t", "*.2b2t.org", registry);

        module.setEnabled(false);
        assertTrue(profiles.activateBestMatchForServer(
                "queue.2b2t.org",
                registry
        ));

        assertTrue(module.enabled());
        assertEquals("2b2t", profiles.active().orElseThrow().name());
    }

    @Test
    void invalidProfileActivationFailsWithoutPartialRegistryChanges() {
        ModuleRegistry registry = new ModuleRegistry();
        StringSetting token = new StringSetting(
                "token",
                "Token",
                "",
                "safe",
                16,
                value -> !"invalid".equals(value),
                () -> true
        );
        RegisteredModule module = registry.register(
                new ModuleDescriptor(
                        "coordinates",
                        "Coordinates",
                        "Draw coordinates",
                        ModuleCategory.HUD,
                        ModuleRisk.PASSIVE,
                        false
                ),
                token
        );
        ProfileBook profiles = new ProfileBook();
        ClientProfile valid = new ClientProfile(
                "valid",
                "*",
                registry.snapshot()
        );
        ClientProfile invalid = new ClientProfile(
                "invalid",
                "*",
                Map.of(
                        "coordinates",
                        new ModuleSnapshot(
                                true,
                                true,
                                80,
                                Map.of("token", "invalid")
                        )
                )
        );
        profiles.replaceAll(List.of(valid, invalid), "valid");

        assertFalse(profiles.activate("invalid", registry));
        assertFalse(module.enabled());
        assertFalse(module.favorite());
        assertEquals(-1, module.keyCode());
        assertEquals("safe", token.value());
        assertEquals("valid", profiles.active().orElseThrow().name());
    }
}
