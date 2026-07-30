package dev.b2tclient.e2e;

import dev.b2tclient.B2TClient;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.setting.Setting;

import java.util.Objects;

final class E2EAssertions {
    private E2EAssertions() {
    }

    static Module module(String id) {
        return B2TClient.runtime().modules().find(id)
                .orElseThrow(() -> new AssertionError("Missing module: " + id));
    }

    static Setting<?> setting(Module module, String id) {
        return module.settings().stream()
                .filter(setting -> setting.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing setting: " + module.id() + "." + id
                ));
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + " (expected=" + expected + ", actual=" + actual + ")"
            );
        }
    }

    static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                    message + " (expected=" + expected + ", actual=" + actual + ")"
            );
        }
    }
}
