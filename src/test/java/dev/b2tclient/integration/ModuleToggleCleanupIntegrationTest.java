package dev.b2tclient.integration;

import dev.b2tclient.core.Module;
import dev.b2tclient.module.combat.AutoTotemModule;
import dev.b2tclient.module.movement.ElytraSwapModule;
import dev.b2tclient.service.ActionCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleToggleCleanupIntegrationTest {
    private static final int CYCLES = 256;
    private static final int CLAIM_PRIORITY = 100;
    private static final int CLAIM_DURATION_TICKS = 4;

    @Test
    void autoTotemRepeatedToggleReleasesEveryClaimAndResetsCooldown() throws Exception {
        ActionCoordinator actions = new ActionCoordinator();
        AutoTotemModule module = new AutoTotemModule(actions);
        Field cooldown = field(AutoTotemModule.class, "cooldown");

        int successfulTransitions = 0;
        int successfulClaims = 0;
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            assertTrue(module.setEnabled(true, null));
            successfulTransitions++;

            successfulClaims += claimEveryChannel(actions, module.id());
            assertEquals(ActionCoordinator.Channel.values().length, claimCount(actions));
            cooldown.setInt(module, cycle + 1);

            assertTrue(module.setEnabled(false, null));
            successfulTransitions++;

            assertFalse(module.isEnabled());
            assertEquals(0, cooldown.getInt(module));
            assertEquals(0, claimCount(actions));
            assertOwnsNoChannel(actions, module.id());
        }

        assertEquals(CYCLES * 2, successfulTransitions);
        assertEquals(
                CYCLES * ActionCoordinator.Channel.values().length,
                successfulClaims
        );
    }

    @Test
    void elytraSwapRepeatedToggleReleasesEveryClaimAndResetsRestoreState() throws Exception {
        ActionCoordinator actions = new ActionCoordinator();
        ElytraSwapModule module = new ElytraSwapModule(actions);
        Field cooldown = field(ElytraSwapModule.class, "cooldown");
        Field restoreSlot = field(ElytraSwapModule.class, "restoreSlot");
        Field restoreHadArmor = field(ElytraSwapModule.class, "restoreHadArmor");

        int successfulTransitions = 0;
        int successfulClaims = 0;
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            assertTrue(module.setEnabled(true, null));
            successfulTransitions++;

            successfulClaims += claimEveryChannel(actions, module.id());
            assertEquals(ActionCoordinator.Channel.values().length, claimCount(actions));
            cooldown.setInt(module, cycle + 1);
            restoreSlot.setInt(module, cycle % 36);
            restoreHadArmor.setBoolean(module, true);

            assertTrue(module.setEnabled(false, null));
            successfulTransitions++;

            assertFalse(module.isEnabled());
            assertEquals(0, cooldown.getInt(module));
            assertEquals(-1, restoreSlot.getInt(module));
            assertFalse(restoreHadArmor.getBoolean(module));
            assertEquals(0, claimCount(actions));
            assertOwnsNoChannel(actions, module.id());
        }

        assertEquals(CYCLES * 2, successfulTransitions);
        assertEquals(
                CYCLES * ActionCoordinator.Channel.values().length,
                successfulClaims
        );
    }

    private static int claimEveryChannel(ActionCoordinator actions, String owner) {
        int successfulClaims = 0;
        for (ActionCoordinator.Channel channel : ActionCoordinator.Channel.values()) {
            if (actions.claim(
                    channel,
                    owner,
                    CLAIM_PRIORITY,
                    CLAIM_DURATION_TICKS
            )) {
                successfulClaims++;
            }
        }
        return successfulClaims;
    }

    private static void assertOwnsNoChannel(ActionCoordinator actions, String owner) {
        for (ActionCoordinator.Channel channel : ActionCoordinator.Channel.values()) {
            assertFalse(actions.owns(channel, owner));
        }
    }

    private static int claimCount(ActionCoordinator actions) throws Exception {
        Field claims = field(ActionCoordinator.class, "claims");
        return ((Map<?, ?>) claims.get(actions)).size();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
