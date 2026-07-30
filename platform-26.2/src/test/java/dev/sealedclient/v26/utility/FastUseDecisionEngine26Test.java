package dev.sealedclient.v26.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastUseDecisionEngine26Test {
    private static final FastUseDecisionEngine26.Configuration CONFIG =
            new FastUseDecisionEngine26.Configuration(
                    2,
                    true,
                    true,
                    true,
                    true
            );
    private final Object session = new Object();

    @Test
    void onlyExplicitImmediateUseWhitelistCanRun() {
        FastUseDecisionEngine26 engine =
                new FastUseDecisionEngine26(CONFIG);
        for (FastUseDecisionEngine26.ItemKind kind :
                FastUseDecisionEngine26.ItemKind.values()) {
            FastUseDecisionEngine26.Decision decision =
                    engine.step(observation(kind, false, false));
            if (kind == FastUseDecisionEngine26.ItemKind.OTHER
                    || kind == FastUseDecisionEngine26.ItemKind.FIREWORK) {
                assertFalse(decision.use());
            } else {
                assertTrue(decision.use());
            }
        }
        assertTrue(engine.step(observation(
                FastUseDecisionEngine26.ItemKind.FIREWORK,
                true,
                false
        )).use());
    }

    @Test
    void vanillaItemCooldownIsNeverBypassed() {
        FastUseDecisionEngine26 engine =
                new FastUseDecisionEngine26(CONFIG);

        FastUseDecisionEngine26.Decision decision =
                engine.step(observation(
                        FastUseDecisionEngine26.ItemKind.ENDER_PEARL,
                        false,
                        true
                ));

        assertEquals(
                FastUseDecisionEngine26.BlockReason.VANILLA_COOLDOWN,
                decision.blockReason()
        );
        assertFalse(decision.use());
    }

    @Test
    void ownDelayStartsOnlyAfterConfirmedNormalUse() {
        FastUseDecisionEngine26 engine =
                new FastUseDecisionEngine26(CONFIG);
        FastUseDecisionEngine26.Decision first =
                engine.step(observation(
                        FastUseDecisionEngine26.ItemKind.EXPERIENCE_BOTTLE,
                        false,
                        false
                ));
        engine.commit(first, false);
        assertTrue(engine.step(observation(
                FastUseDecisionEngine26.ItemKind.EXPERIENCE_BOTTLE,
                false,
                false
        )).use());

        FastUseDecisionEngine26.Decision applied =
                engine.step(observation(
                        FastUseDecisionEngine26.ItemKind.EXPERIENCE_BOTTLE,
                        false,
                        false
                ));
        engine.commit(applied, true);
        assertEquals(
                FastUseDecisionEngine26.BlockReason.OWN_COOLDOWN,
                engine.step(observation(
                        FastUseDecisionEngine26.ItemKind.EXPERIENCE_BOTTLE,
                        false,
                        false
                )).blockReason()
        );
    }

    @Test
    void screenSafetyLongUseAndReleasedKeyFailClosed() {
        FastUseDecisionEngine26 engine =
                new FastUseDecisionEngine26(CONFIG);
        FastUseDecisionEngine26.Observation base = observation(
                FastUseDecisionEngine26.ItemKind.PROJECTILE,
                false,
                false
        );
        assertEquals(
                FastUseDecisionEngine26.BlockReason.SAFETY,
                engine.step(copy(base, false, true, true, false))
                        .blockReason()
        );
        assertEquals(
                FastUseDecisionEngine26.BlockReason.SCREEN_OPEN,
                engine.step(copy(base, true, false, true, false))
                        .blockReason()
        );
        assertEquals(
                FastUseDecisionEngine26.BlockReason.KEY_NOT_DOWN,
                engine.step(copy(base, true, true, false, false))
                        .blockReason()
        );
        assertEquals(
                FastUseDecisionEngine26.BlockReason.LONG_USE_ACTIVE,
                engine.step(copy(base, true, true, true, true))
                        .blockReason()
        );
    }

    @Test
    void configurationAndSessionLifecycleAreValidated() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FastUseDecisionEngine26.Configuration(
                        1,
                        true,
                        false,
                        false,
                        false
                )
        );
        FastUseDecisionEngine26 engine =
                new FastUseDecisionEngine26(CONFIG);
        FastUseDecisionEngine26.Decision use =
                engine.step(observation(
                        FastUseDecisionEngine26.ItemKind.PROJECTILE,
                        false,
                        false
                ));
        engine.commit(use, true);
        assertTrue(engine.snapshot().cooldownTicks() > 0);

        assertTrue(engine.step(new FastUseDecisionEngine26.Observation(
                new Object(),
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                FastUseDecisionEngine26.ItemKind.PROJECTILE
        )).use());
        assertEquals(0, engine.snapshot().cooldownTicks());
    }

    private FastUseDecisionEngine26.Observation observation(
            FastUseDecisionEngine26.ItemKind kind,
            boolean flying,
            boolean vanillaCooldown
    ) {
        return new FastUseDecisionEngine26.Observation(
                session,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                vanillaCooldown,
                flying,
                kind
        );
    }

    private static FastUseDecisionEngine26.Observation copy(
            FastUseDecisionEngine26.Observation source,
            boolean safety,
            boolean screenClear,
            boolean keyDown,
            boolean using
    ) {
        return new FastUseDecisionEngine26.Observation(
                source.sessionIdentity(),
                source.enabled(),
                source.sessionReady(),
                safety,
                screenClear,
                source.playerAlive(),
                keyDown,
                using,
                source.vanillaItemCooldown(),
                source.fallFlying(),
                source.itemKind()
        );
    }
}
