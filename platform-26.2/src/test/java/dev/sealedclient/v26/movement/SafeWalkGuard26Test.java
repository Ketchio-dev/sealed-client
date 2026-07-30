package dev.sealedclient.v26.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SafeWalkGuard26Test {
    @Test
    void authorizationExpiresAfterOneFollowingPlayerTick() {
        assertTrue(SafeWalkGuard26.leaseIsCurrent(100, 100, true));
        assertTrue(SafeWalkGuard26.leaseIsCurrent(100, 101, true));
        assertFalse(SafeWalkGuard26.leaseIsCurrent(100, 102, true));
        assertFalse(SafeWalkGuard26.leaseIsCurrent(100, 99, true));
        assertFalse(SafeWalkGuard26.leaseIsCurrent(
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                true
        ));
    }

    @Test
    void authorizationRequiresExactContextIdentity() {
        assertFalse(SafeWalkGuard26.leaseIsCurrent(100, 100, false));
    }
}
