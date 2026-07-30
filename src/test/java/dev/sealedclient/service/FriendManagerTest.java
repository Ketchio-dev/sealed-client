package dev.sealedclient.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendManagerTest {
    @Test
    void namesAreTrimmedAndMatchedCaseInsensitively() {
        FriendManager manager = new FriendManager();
        UUID uuid = UUID.randomUUID();

        assertTrue(manager.add("  Alice  ", uuid));
        assertTrue(manager.isFriend("alice"));
        assertTrue(manager.isFriend("ALICE"));
        assertEquals("Alice", manager.find(" aLiCe ").orElseThrow().name());
        assertEquals(uuid, manager.find("alice").orElseThrow().uuid());
        assertFalse(manager.add("ALICE", null));
        assertEquals(1, manager.all().size());
        assertEquals(null, manager.find("alice").orElseThrow().uuid());

        assertTrue(manager.remove(" Alice "));
        assertFalse(manager.isFriend("alice"));
        assertFalse(manager.remove("alice"));
    }

    @Test
    void replaceAllClearsOldEntriesAndCopiesTheInput() {
        FriendManager manager = new FriendManager();
        manager.add("Old", null);

        manager.replaceAll(List.of(
                new FriendManager.Friend("Bob", null),
                new FriendManager.Friend("Carol", UUID.randomUUID())
        ));

        assertFalse(manager.isFriend("old"));
        assertTrue(manager.isFriend("bob"));
        assertTrue(manager.isFriend("carol"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manager.all().clear()
        );
    }

    @Test
    void blankFriendNamesAreRejected() {
        FriendManager manager = new FriendManager();

        assertThrows(IllegalArgumentException.class, () -> manager.add(" ", null));
        assertFalse(manager.isFriend((String) null));
    }
}
