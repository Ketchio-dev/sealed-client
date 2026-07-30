package dev.sealedclient.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointManagerTest {
    @Test
    void lookupIsCaseInsensitiveAndVisibleFilteringUsesServerAndDimension() {
        WaypointManager manager = new WaypointManager();
        Waypoint home = waypoint(
                " Home ",
                "2b2t.org",
                "minecraft:overworld",
                true
        );
        Waypoint hidden = waypoint(
                "Hidden",
                "2b2t.org",
                "minecraft:overworld",
                false
        );
        Waypoint nether = waypoint(
                "Portal",
                "2b2t.org",
                "minecraft:the_nether",
                true
        );
        Waypoint otherServer = waypoint(
                "Other",
                "example.org",
                "minecraft:overworld",
                true
        );

        assertTrue(manager.add(home));
        manager.add(hidden);
        manager.add(nether);
        manager.add(otherServer);

        assertEquals(home, manager.find("HOME").orElseThrow());
        assertEquals(
                List.of(home),
                manager.visibleFor("2B2T.ORG", "minecraft:overworld")
        );
        assertTrue(manager.visibleFor("2b2t.org", "minecraft:the_end").isEmpty());
    }

    @Test
    void duplicateNameReplacesTheWaypointAndReplaceAllClearsExistingOnes() {
        WaypointManager manager = new WaypointManager();
        Waypoint first = waypoint("Base", "first.org", "minecraft:overworld", true);
        Waypoint replacement = waypoint("base", "second.org", "minecraft:the_end", true);

        assertTrue(manager.add(first));
        assertFalse(manager.add(replacement));
        assertEquals(replacement, manager.find("BASE").orElseThrow());
        assertEquals(1, manager.all().size());

        Waypoint spawn = new Waypoint(
                "Spawn", null, null, 0, 64, 0, 0xffffffff, true
        );
        manager.replaceAll(List.of(spawn));

        assertFalse(manager.find("base").isPresent());
        assertEquals("singleplayer", manager.find("spawn").orElseThrow().server());
        assertEquals(
                "minecraft:overworld",
                manager.find("spawn").orElseThrow().dimension()
        );
        assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
    }

    private static Waypoint waypoint(
            String name,
            String server,
            String dimension,
            boolean visible
    ) {
        return new Waypoint(name, server, dimension, 1, 2, 3, 0xff55d6be, visible);
    }
}
