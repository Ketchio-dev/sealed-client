package dev.sealedclient.common.arbitration;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionArbiterTest {
    private enum Channel {
        ATTACK,
        USE,
        HOTBAR,
        ROTATION
    }

    private static ActionArbiter<Channel> readyArbiter() {
        ActionArbiter<Channel> arbiter = new ActionArbiter<>(Channel.class, "Test");
        arbiter.beginTick(false, Set.of());
        return arbiter;
    }

    @Test
    void theHighestPriorityBundleWinsTheWholeContestedSet() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.submit("aura", 10, EnumSet.of(Channel.ATTACK, Channel.ROTATION));
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK, Channel.ROTATION));
        arbiter.resolve();

        assertTrue(arbiter.ownsAll("crystal", EnumSet.of(Channel.ATTACK, Channel.ROTATION)));
        assertFalse(arbiter.owns("aura", Channel.ATTACK));
        assertFalse(arbiter.owns("aura", Channel.ROTATION));
    }

    @Test
    void aPartiallyBlockedBundleIsDeniedEntirelyRatherThanSplit() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK));
        arbiter.submit("quiver", 10, EnumSet.of(Channel.ATTACK, Channel.USE, Channel.HOTBAR));
        arbiter.resolve();

        // The point of bundling: quiver must not get USE and HOTBAR without
        // ATTACK, which would use an item that was never aimed.
        assertFalse(arbiter.owns("quiver", Channel.USE));
        assertFalse(arbiter.owns("quiver", Channel.HOTBAR));
        assertEquals(ActionArbiter.Status.DENIED, arbiter.decision("quiver").status());
        assertEquals(
                Map.of(Channel.ATTACK, "crystal"),
                arbiter.decision("quiver").blockers()
        );
    }

    @Test
    void submissionOrderCannotChangeTheOutcome() {
        ActionArbiter<Channel> forward = readyArbiter();
        forward.submit("alpha", 50, EnumSet.of(Channel.USE));
        forward.submit("beta", 50, EnumSet.of(Channel.USE));
        forward.resolve();

        ActionArbiter<Channel> reverse = readyArbiter();
        reverse.submit("beta", 50, EnumSet.of(Channel.USE));
        reverse.submit("alpha", 50, EnumSet.of(Channel.USE));
        reverse.resolve();

        assertEquals(forward.grants(), reverse.grants());
        assertEquals("alpha", forward.grants().get(Channel.USE).owner());
    }

    @Test
    void everyGrantExpiresAtTheNextTick() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK));
        arbiter.resolve();
        assertTrue(arbiter.owns("crystal", Channel.ATTACK));

        arbiter.beginTick(false, Set.of());
        assertFalse(arbiter.owns("crystal", Channel.ATTACK));
        assertTrue(arbiter.grants().isEmpty());
    }

    @Test
    void aBlockedTickGrantsNothingAndAcceptsNoSubmissions() {
        ActionArbiter<Channel> arbiter = new ActionArbiter<>(Channel.class, "Test");
        arbiter.beginTick(true, Set.of());

        assertFalse(arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK)));
        arbiter.resolve();
        assertFalse(arbiter.owns("crystal", Channel.ATTACK));
        assertTrue(arbiter.grants().isEmpty());
    }

    @Test
    void externallyReservedChannelsAreHeldForTheWholeTick() {
        ActionArbiter<Channel> arbiter = new ActionArbiter<>(Channel.class, "Test");
        arbiter.beginTick(false, EnumSet.of(Channel.HOTBAR));
        arbiter.submit("mend", 40, EnumSet.of(Channel.HOTBAR, Channel.USE));
        arbiter.resolve();

        assertFalse(arbiter.owns("mend", Channel.HOTBAR));
        assertEquals(
                ActionArbiter.EXTERNAL_OWNER,
                arbiter.grants().get(Channel.HOTBAR).owner()
        );
    }

    @Test
    void releasingAnOwnerNeverPromotesAnAlreadyDeniedRequest() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK));
        arbiter.submit("aura", 10, EnumSet.of(Channel.ATTACK));
        arbiter.resolve();

        arbiter.releaseOwner("crystal");

        // A service that already saw a denial must not become authorized later
        // in the same tick, or two services would act on one channel.
        assertFalse(arbiter.owns("aura", Channel.ATTACK));
        assertEquals(ActionArbiter.Status.DENIED, arbiter.decision("aura").status());
    }

    @Test
    void releaseAllCancelsGrantsButKeepsExternalReservations() {
        ActionArbiter<Channel> arbiter = new ActionArbiter<>(Channel.class, "Test");
        arbiter.beginTick(false, EnumSet.of(Channel.ROTATION));
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK));
        arbiter.resolve();

        arbiter.releaseAll();

        assertFalse(arbiter.owns("crystal", Channel.ATTACK));
        assertEquals(
                ActionArbiter.EXTERNAL_OWNER,
                arbiter.grants().get(Channel.ROTATION).owner()
        );
    }

    @Test
    void oneOwnerMaySubmitOnlyOncePerTick() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        assertTrue(arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK)));
        assertFalse(arbiter.submit("crystal", 99, EnumSet.of(Channel.USE)));
    }

    @Test
    void theExternalOwnerIdentifierCannotBeImpersonated() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        assertThrows(IllegalArgumentException.class, () ->
                arbiter.submit(ActionArbiter.EXTERNAL_OWNER, 99, EnumSet.of(Channel.USE))
        );
    }

    @Test
    void invalidOwnersAndEmptyBundlesAreRejectedWithTheSubsystemLabel() {
        ActionArbiter<Channel> arbiter = readyArbiter();

        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                arbiter.submit(" ", 1, EnumSet.of(Channel.USE))
        ).getMessage().startsWith("Test action owner"));

        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                arbiter.submit("x".repeat(ActionArbiter.MAXIMUM_OWNER_LENGTH + 1),
                        1, EnumSet.of(Channel.USE))
        ).getMessage().contains("cannot exceed"));

        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                arbiter.submit("crystal", 1, EnumSet.noneOf(Channel.class))
        ).getMessage().contains("at least one channel"));
    }

    @Test
    void resolvingTwiceInOneTickIsRejected() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.resolve();
        assertThrows(IllegalStateException.class, arbiter::resolve);
    }

    @Test
    void exposedGrantAndDecisionViewsAreImmutable() {
        ActionArbiter<Channel> arbiter = readyArbiter();
        arbiter.submit("crystal", 90, EnumSet.of(Channel.ATTACK));
        arbiter.resolve();

        assertThrows(UnsupportedOperationException.class, () ->
                arbiter.grants().put(Channel.USE, new ActionArbiter.Grant("mutant", 1))
        );
        assertThrows(UnsupportedOperationException.class, () ->
                arbiter.decisions().remove("crystal")
        );
    }
}
