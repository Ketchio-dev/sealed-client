package dev.b2tclient.common.profile;

import dev.b2tclient.common.module.BuiltinModuleCatalog;
import dev.b2tclient.common.module.ModuleRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBookDeleteTest {
    private static ModuleRegistry registry() {
        ModuleRegistry registry = new ModuleRegistry();
        BuiltinModuleCatalog.populate(
                registry,
                BuiltinModuleCatalog.entries().stream()
                        .map(BuiltinModuleCatalog.CatalogEntry::id)
                        .collect(java.util.stream.Collectors.toSet())
        );
        return registry;
    }

    @Test
    void deletingANonActiveProfileLeavesTheActiveOneAlone() {
        ProfileBook book = new ProfileBook();
        ModuleRegistry registry = registry();
        book.capture("first", "*", registry);
        book.capture("second", "2b2t.org", registry);

        assertEquals(ProfileBook.DeleteResult.DELETED, book.delete("second"));
        assertEquals(1, book.all().size());
        assertEquals("first", book.active().orElseThrow().name());
    }

    @Test
    void deletingTheActiveProfileHandsActiveStatusToASurvivor() {
        ProfileBook book = new ProfileBook();
        ModuleRegistry registry = registry();
        book.capture("first", "*", registry);
        book.capture("second", "2b2t.org", registry);
        assertTrue(book.activate("second", registry));

        assertEquals(ProfileBook.DeleteResult.DELETED, book.delete("second"));
        assertEquals("first", book.active().orElseThrow().name(),
                "the book must never point at a deleted profile");
    }

    @Test
    void theLastProfileIsNeverDeleted() {
        ProfileBook book = new ProfileBook();
        book.capture("only", "*", registry());

        assertEquals(ProfileBook.DeleteResult.LAST_PROFILE, book.delete("only"));
        assertEquals(1, book.all().size());
        assertEquals("only", book.active().orElseThrow().name());
    }

    @Test
    void deletingAnUnknownNameIsReportedRatherThanSilentlyIgnored() {
        ProfileBook book = new ProfileBook();
        ModuleRegistry registry = registry();
        book.capture("first", "*", registry);
        book.capture("second", "2b2t.org", registry);

        assertEquals(ProfileBook.DeleteResult.NOT_FOUND, book.delete("missing"));
        assertEquals(2, book.all().size());
    }

    @Test
    void nameLookupForDeleteIsCaseAndWhitespaceInsensitive() {
        ProfileBook book = new ProfileBook();
        ModuleRegistry registry = registry();
        book.capture("First", "*", registry);
        book.capture("second", "2b2t.org", registry);

        assertEquals(ProfileBook.DeleteResult.DELETED, book.delete("  FIRST "));
        assertEquals(1, book.all().size());
    }
}
