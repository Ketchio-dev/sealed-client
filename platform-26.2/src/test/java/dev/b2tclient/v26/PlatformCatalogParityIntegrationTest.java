package dev.b2tclient.v26;

import dev.b2tclient.common.module.BuiltinModuleCatalog;
import dev.b2tclient.common.module.RegisteredModule;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCatalogParityIntegrationTest {
    private static final int CATALOG_COUNT = 90;
    private static final int SUPPORTED_COUNT = 90;
    private static final int UNSUPPORTED_COUNT = CATALOG_COUNT - SUPPORTED_COUNT;

    @Test
    void platformRegistryMatchesEverySharedCatalogDescriptorAndCapability() {
        var entries = BuiltinModuleCatalog.entries();
        var registry = PlatformCapabilities26.createRegistry();
        Map<String, BuiltinModuleCatalog.CatalogEntry> catalog = entries.stream()
                .collect(Collectors.toUnmodifiableMap(
                        BuiltinModuleCatalog.CatalogEntry::id,
                        Function.identity()
                ));
        Set<String> registryIds = registry.all().stream()
                .map(module -> module.descriptor().id())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(CATALOG_COUNT, BuiltinModuleCatalog.EXPECTED_MODULE_COUNT);
        assertEquals(CATALOG_COUNT, entries.size());
        assertEquals(CATALOG_COUNT, new HashSet<>(catalog.keySet()).size());
        assertEquals(SUPPORTED_COUNT, PlatformCapabilities26.EXPECTED_SUPPORTED_COUNT);
        assertEquals(SUPPORTED_COUNT, PlatformCapabilities26.SUPPORTED_IDS.size());
        assertTrue(catalog.keySet().containsAll(PlatformCapabilities26.SUPPORTED_IDS));
        assertEquals(catalog.keySet(), registryIds);

        int supported = 0;
        int unsupported = 0;
        for (RegisteredModule module : registry.all()) {
            var descriptor = module.descriptor();
            var entry = catalog.get(descriptor.id());

            assertEquals(entry.name(), descriptor.name(), descriptor.id());
            assertEquals(entry.description(), descriptor.description(), descriptor.id());
            assertEquals(entry.category(), descriptor.category(), descriptor.id());
            assertEquals(entry.risk(), descriptor.risk(), descriptor.id());
            assertFalse(descriptor.capabilityDetail().isBlank(), descriptor.id());

            boolean expectedAvailable =
                    PlatformCapabilities26.SUPPORTED_IDS.contains(descriptor.id());
            assertEquals(expectedAvailable, descriptor.available(), descriptor.id());
            assertEquals(
                    expectedAvailable && entry.enabledByDefault(),
                    module.enabled(),
                    descriptor.id()
            );
            if (expectedAvailable) {
                supported++;
            } else {
                unsupported++;
            }
        }

        assertEquals(SUPPORTED_COUNT, supported);
        assertEquals(UNSUPPORTED_COUNT, unsupported);
    }

    @Test
    void repeatedCapabilityTogglesRemainFailClosedAndBounded() {
        var registry = PlatformCapabilities26.createRegistry();
        int attemptedToggles = 0;

        for (int pass = 0; pass < 8; pass++) {
            for (RegisteredModule module : registry.all()) {
                boolean initiallyEnabled = module.enabled();
                if (module.descriptor().available()) {
                    module.toggle();
                    module.toggle();
                    attemptedToggles += 2;
                    assertEquals(
                            initiallyEnabled,
                            module.enabled(),
                            module.descriptor().id()
                    );
                } else {
                    assertFalse(module.toggle(), module.descriptor().id());
                    attemptedToggles++;
                    assertFalse(module.enabled(), module.descriptor().id());
                    assertThrows(
                            IllegalStateException.class,
                            () -> module.setEnabled(true),
                            module.descriptor().id()
                    );
                }
            }
        }

        assertEquals(
                8 * (SUPPORTED_COUNT * 2 + UNSUPPORTED_COUNT),
                attemptedToggles
        );
    }
}
