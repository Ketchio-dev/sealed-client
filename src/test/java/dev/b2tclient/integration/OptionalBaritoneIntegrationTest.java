package dev.b2tclient.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalBaritoneIntegrationTest {
    @Test
    void absentBaritoneClasspathLoadsFacadeAndFailsClosed() {
        ClassLoader applicationLoader = OptionalIntegrationManager.class.getClassLoader();
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("baritone.api.BaritoneAPI", false, applicationLoader),
                "The optional Baritone API must not leak onto the runtime test classpath"
        );

        OptionalIntegrationManager integrations =
                assertDoesNotThrow(OptionalIntegrationManager::new);
        OptionalIntegrationManager.Integration metadata =
                integrations.integration("baritone");
        BaritoneNavigator navigator = assertDoesNotThrow(integrations::baritone);

        assertFalse(metadata.available());
        assertEquals("", metadata.version());
        assertFalse(navigator.available());
        assertEquals("", navigator.version());

        BaritoneNavigator.NavigationResult navigation =
                assertDoesNotThrow(() -> navigator.goTo(12, 64, -34));
        assertFalse(navigation.success());
        assertFalse(navigation.message().isBlank());

        BaritoneNavigator.NavigationStatus status =
                assertDoesNotThrow(navigator::status);
        assertEquals(BaritoneNavigator.NavigationState.UNAVAILABLE, status.state());
        assertFalse(status.ownedByB2T());
        assertFalse(status.detail().isBlank());

        assertFalse(assertDoesNotThrow(navigator::stop).success());
        assertFalse(assertDoesNotThrow(navigator::pause).success());
        assertFalse(assertDoesNotThrow(navigator::resume).success());
        assertDoesNotThrow(navigator::releaseOwnedNavigation);
        assertDoesNotThrow(navigator::resetSession);
    }
}
