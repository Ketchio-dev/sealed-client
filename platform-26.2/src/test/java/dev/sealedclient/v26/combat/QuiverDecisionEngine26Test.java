package dev.sealedclient.v26.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuiverDecisionEngine26Test {
    @Test
    void selectsUsefulWhollyBeneficialArrow() {
        QuiverDecisionEngine26.ArrowDecision decision =
                QuiverDecisionEngine26.select(
                        List.of(arrow(
                                40,
                                effect(
                                        "minecraft:speed",
                                        true,
                                        false,
                                        false,
                                        0,
                                        200,
                                        -1,
                                        0
                                )
                        )),
                        100,
                        0.0
                ).orElseThrow();

        assertTrue(decision.safeAndUseful());
        assertEquals(
                List.of("minecraft:speed"),
                decision.usefulEffectKeys()
        );
    }

    @Test
    void harmfulMixtureFailsClosed() {
        QuiverDecisionEngine26.ArrowDecision decision =
                QuiverDecisionEngine26.evaluate(
                        new QuiverDecisionEngine26.ArrowCandidate(
                                40,
                                1,
                                List.of(
                                        effect(
                                                "minecraft:speed",
                                                true,
                                                false,
                                                false,
                                                0,
                                                200,
                                                -1,
                                                0
                                        ),
                                        effect(
                                                "minecraft:poison",
                                                false,
                                                false,
                                                false,
                                                0,
                                                100,
                                                -1,
                                                0
                                        )
                                )
                        ),
                        100,
                        0.0
                );

        assertFalse(decision.safeAndUseful());
        assertEquals(
                QuiverDecisionEngine26.BlockReason.HARMFUL_EFFECT,
                decision.blockReason()
        );
    }

    @Test
    void redundantEffectIsRejectedUntilRefreshThreshold() {
        QuiverDecisionEngine26.EffectCandidate speed = effect(
                "minecraft:speed",
                true,
                false,
                false,
                0,
                200,
                0,
                150
        );

        assertTrue(QuiverDecisionEngine26.select(
                List.of(arrow(40, speed)),
                100,
                0.0
        ).isEmpty());
        assertTrue(QuiverDecisionEngine26.select(
                List.of(arrow(40, speed)),
                180,
                0.0
        ).isPresent());
    }

    @Test
    void instantHealthRequiresMissingHealth() {
        QuiverDecisionEngine26.EffectCandidate healing = effect(
                "minecraft:instant_health",
                true,
                true,
                true,
                0,
                0,
                -1,
                0
        );

        assertTrue(QuiverDecisionEngine26.select(
                List.of(arrow(40, healing)),
                100,
                5.0
        ).isPresent());
        assertTrue(QuiverDecisionEngine26.select(
                List.of(arrow(40, healing)),
                100,
                0.0
        ).isEmpty());
    }

    @Test
    void otherInstantEffectsAreRejectedBecauseTheyCannotBeConfirmed() {
        QuiverDecisionEngine26.ArrowDecision decision =
                QuiverDecisionEngine26.evaluate(
                        arrow(
                                40,
                                effect(
                                        "example:instant_buff",
                                        true,
                                        true,
                                        false,
                                        0,
                                        0,
                                        -1,
                                        0
                                )
                        ),
                        100,
                        5.0
                );

        assertEquals(
                QuiverDecisionEngine26.BlockReason
                        .UNCONFIRMABLE_INSTANT_EFFECT,
                decision.blockReason()
        );
    }

    @Test
    void strongerUsefulArrowWinsWithStableSlotTieBreak() {
        QuiverDecisionEngine26.ArrowCandidate weak = arrow(
                40,
                effect(
                        "minecraft:strength",
                        true,
                        false,
                        false,
                        0,
                        200,
                        -1,
                        0
                )
        );
        QuiverDecisionEngine26.ArrowCandidate strong =
                new QuiverDecisionEngine26.ArrowCandidate(
                        8,
                        1,
                        List.of(effect(
                                "minecraft:strength",
                                true,
                                false,
                                false,
                                1,
                                300,
                                -1,
                                0
                        ))
                );

        assertEquals(
                8,
                QuiverDecisionEngine26.select(
                        List.of(weak, strong),
                        100,
                        0.0
                ).orElseThrow().candidate().inventorySlot()
        );
    }

    @Test
    void shotAcceptanceRequiresAmmoOrDurabilityReflection() {
        assertTrue(QuiverDecisionEngine26.shotAccepted(
                4,
                3,
                10,
                10
        ));
        assertTrue(QuiverDecisionEngine26.shotAccepted(
                4,
                4,
                10,
                11
        ));
        assertFalse(QuiverDecisionEngine26.shotAccepted(
                4,
                4,
                10,
                10
        ));
        assertFalse(QuiverDecisionEngine26.shotAccepted(
                -1,
                0,
                10,
                11
        ));
    }

    @Test
    void effectConfirmationRequiresImprovement() {
        QuiverDecisionEngine26.EffectObservation duration =
                observation(
                        "minecraft:speed",
                        false,
                        false,
                        0,
                        80,
                        0,
                        160
                );
        assertTrue(QuiverDecisionEngine26.effectConfirmed(
                List.of(duration),
                20.0,
                20.0
        ));
        assertFalse(QuiverDecisionEngine26.effectConfirmed(
                List.of(observation(
                        "minecraft:speed",
                        false,
                        false,
                        0,
                        80,
                        0,
                        81
                )),
                20.0,
                20.0
        ));
    }

    @Test
    void instantHealthConfirmationUsesEffectiveHealth() {
        QuiverDecisionEngine26.EffectObservation healing =
                observation(
                        "minecraft:instant_health",
                        true,
                        true,
                        -1,
                        0,
                        -1,
                        0
                );

        assertTrue(QuiverDecisionEngine26.effectConfirmed(
                List.of(healing),
                12.0,
                15.0
        ));
        assertFalse(QuiverDecisionEngine26.effectConfirmed(
                List.of(healing),
                12.0,
                12.2
        ));
    }

    @Test
    void configurationRejectsUnsafeTimingAndDurability() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatQuiverAutomation26.Configuration(
                        4,
                        16,
                        2,
                        100,
                        100,
                        8,
                        100,
                        40
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatQuiverAutomation26.Configuration(
                        20,
                        16,
                        1,
                        100,
                        100,
                        8,
                        100,
                        40
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CombatQuiverAutomation26.Configuration(
                        20,
                        16,
                        2,
                        100,
                        100,
                        101,
                        100,
                        40
                )
        );
    }

    private static QuiverDecisionEngine26.ArrowCandidate arrow(
            int slot,
            QuiverDecisionEngine26.EffectCandidate... effects
    ) {
        return new QuiverDecisionEngine26.ArrowCandidate(
                slot,
                1,
                List.of(effects)
        );
    }

    private static QuiverDecisionEngine26.EffectCandidate effect(
            String key,
            boolean beneficial,
            boolean instantaneous,
            boolean healthRestoring,
            int amplifier,
            int duration,
            int currentAmplifier,
            int currentDuration
    ) {
        return new QuiverDecisionEngine26.EffectCandidate(
                key,
                beneficial,
                instantaneous,
                healthRestoring,
                amplifier,
                duration,
                currentAmplifier,
                currentDuration
        );
    }

    private static QuiverDecisionEngine26.EffectObservation observation(
            String key,
            boolean instantaneous,
            boolean healthRestoring,
            int beforeAmplifier,
            int beforeRemaining,
            int currentAmplifier,
            int currentRemaining
    ) {
        return new QuiverDecisionEngine26.EffectObservation(
                key,
                instantaneous,
                healthRestoring,
                beforeAmplifier,
                beforeRemaining,
                currentAmplifier,
                currentRemaining
        );
    }
}
