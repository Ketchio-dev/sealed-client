package dev.sealedclient.v26.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XRayController26Test {
    private final XRayController26 controller = new XRayController26();

    @AfterEach
    void clearPublishedSnapshot() {
        controller.release(null);
    }

    @Test
    void whitelistIsNormalizedDeduplicatedAndNamespaced() {
        XRayController26.Configuration configuration =
                XRayController26.Configuration.of(
                        List.of(
                                " Diamond_Ore ",
                                "minecraft:diamond_ore",
                                "modded:ore",
                                "bad id",
                                ""
                        ),
                        5
                );

        assertEquals(
                Set.of("minecraft:diamond_ore", "modded:ore"),
                configuration.visibleBlocks()
        );
    }

    @Test
    void whitelistHasAStableHardUpperBound() {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            entries.add("test:block_" + index);
        }

        XRayController26.Configuration configuration =
                XRayController26.Configuration.of(entries, 4);

        assertEquals(
                XRayController26.MAX_VISIBLE_BLOCKS,
                configuration.visibleBlocks().size()
        );
    }

    @Test
    void refreshDelayAndNullConfigurationFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.Configuration.of(
                        List.of("diamond_ore"),
                        XRayController26.MIN_REFRESH_DELAY_TICKS - 1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.Configuration.of(
                        List.of("diamond_ore"),
                        XRayController26.MAX_REFRESH_DELAY_TICKS + 1
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> controller.setConfiguration(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.Configuration.of(
                        List.of("diamond_ore"),
                        -1,
                        true,
                        4
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.Configuration.of(
                        List.of("diamond_ore"),
                        101,
                        true,
                        4
                )
        );
    }

    @Test
    void fullConfigurationPreservesOpacityAndRefreshPolicy() {
        XRayController26.Configuration configuration =
                XRayController26.Configuration.of(
                        List.of("diamond_ore"),
                        37,
                        false,
                        9
                );

        assertEquals(37, configuration.hiddenOpacity());
        assertFalse(configuration.autoRefresh());
        assertEquals(9, configuration.refreshDelayTicks());
        assertEquals(
                Set.of("minecraft:diamond_ore"),
                configuration.visibleBlocks()
        );
    }

    @Test
    void renderDirectivesEnforceExplicitOpacityModes() {
        assertEquals(
                XRayController26.RenderMode.NORMAL,
                XRayController26.RenderDirective.normal().mode()
        );
        assertEquals(
                XRayController26.RenderMode.HIDDEN,
                XRayController26.RenderDirective.hidden().mode()
        );
        assertEquals(
                new XRayController26.RenderDirective(
                        XRayController26.RenderMode.TRANSLUCENT,
                        37
                ),
                XRayController26.RenderDirective.translucent(37)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.RenderDirective.translucent(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> XRayController26.RenderDirective.translucent(100)
        );
    }

    @Test
    void missingClientNeverPublishesAnActiveSnapshot() {
        controller.setConfiguration(XRayController26.Configuration.DEFAULT);
        controller.tick(null, true);

        assertFalse(XRayController26.active());
        assertTrue(XRayController26.snapshot().visibleBlocks().isEmpty());
        assertEquals(0L, controller.refreshCount());
    }

    @Test
    void disabledSnapshotRejectsIllegalEnabledEmptyState() {
        assertFalse(XRayController26.RenderSnapshot.disabled().enabled());
        assertThrows(
                IllegalArgumentException.class,
                () -> new XRayController26.RenderSnapshot(true, Set.of())
        );
    }
}
