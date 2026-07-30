package dev.sealedclient.v26.world;

import dev.sealedclient.v26.visual.VisualOverlayRenderer26.FrameSnapshot;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTrackerRenderService26Test {
    @Test
    void assemblesBoundedGeometryFromImmutableTrackerSnapshot() {
        WorldTrackerService26.RenderSnapshot snapshot = snapshot();
        FakeView view = new FakeView();
        WorldTrackerRenderService26.Configuration defaults =
                WorldTrackerRenderService26.Configuration.defaults();
        WorldTrackerRenderService26.LogoutSpotsRender logout =
                defaults.logoutSpots();
        WorldTrackerRenderService26.Configuration withTracer =
                new WorldTrackerRenderService26.Configuration(
                        defaults.newChunks(),
                        new WorldTrackerRenderService26.LogoutSpotsRender(
                                logout.argb(),
                                logout.showName(),
                                true,
                                logout.lineWidth(),
                                logout.maximumDistanceBlocks(),
                                logout.renderCap(),
                                logout.labelScale()
                        ),
                        defaults.stashFinder()
                );

        FrameSnapshot frame = WorldTrackerRenderService26.buildFrame(
                snapshot,
                withTracer,
                view
        );

        assertEquals(3, frame.filledBoxes().size());
        assertEquals(37, frame.lines().size());
        assertEquals(2, frame.labels().size());
        assertEquals(
                -63.98,
                frame.filledBoxes().getFirst().minimum().y(),
                0.000_001
        );
        assertEquals(
                "Alice logged out",
                frame.labels().getFirst().text().getString()
        );
        assertEquals(
                "Stash \u00b7 2 containers",
                frame.labels().get(1).text().getString()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> frame.lines().clear()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.newChunks().clear()
        );
    }

    @Test
    void dimensionDistanceFrustumAndRenderCapAreConservative() {
        WorldTrackerService26.RenderSnapshot snapshot = snapshot();
        FakeView wrongDimension = new FakeView();
        wrongDimension.dimension = "minecraft:the_nether";
        assertTrue(WorldTrackerRenderService26.buildFrame(
                snapshot,
                WorldTrackerRenderService26.Configuration.defaults(),
                wrongDimension
        ).empty());

        FakeView hidden = new FakeView();
        hidden.visible = false;
        WorldTrackerService26.RenderSnapshot chunksOnly =
                new WorldTrackerService26.RenderSnapshot(
                        true,
                        1L,
                        2L,
                        "minecraft:overworld",
                        new WorldTrackerService26.ModuleState(
                                true,
                                false,
                                false
                        ),
                        List.of(
                                chunk(0, 0),
                                chunk(1, 0)
                        ),
                        List.of(),
                        List.of()
                );
        assertTrue(WorldTrackerRenderService26.buildFrame(
                chunksOnly,
                WorldTrackerRenderService26.Configuration.defaults(),
                hidden
        ).empty());

        WorldTrackerRenderService26.Configuration capOne =
                new WorldTrackerRenderService26.Configuration(
                        new WorldTrackerRenderService26.NewChunksRender(
                                0xFFFFFFFF,
                                false,
                                1.0F,
                                64.0,
                                1
                        ),
                        WorldTrackerRenderService26.Configuration.defaults()
                                .logoutSpots(),
                        WorldTrackerRenderService26.Configuration.defaults()
                                .stashFinder()
                );
        FakeView visible = new FakeView();
        FrameSnapshot capped = WorldTrackerRenderService26.buildFrame(
                chunksOnly,
                capOne,
                visible
        );
        assertTrue(capped.filledBoxes().isEmpty());
        assertEquals(12, capped.lines().size());

        visible.cameraX = 10_000.0;
        visible.cameraZ = 10_000.0;
        assertTrue(WorldTrackerRenderService26.buildFrame(
                chunksOnly,
                capOne,
                visible
        ).empty());
    }

    @Test
    void renderSettingsRejectUnboundedOrNonFiniteValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTrackerRenderService26.NewChunksRender(
                        0,
                        true,
                        Float.NaN,
                        128.0,
                        1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTrackerRenderService26.LogoutSpotsRender(
                        0,
                        true,
                        true,
                        1.0F,
                        Double.POSITIVE_INFINITY,
                        1,
                        1.0F
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTrackerRenderService26.StashRender(
                        0,
                        true,
                        true,
                        1.0F,
                        128.0,
                        129,
                        1.0F
                )
        );
        assertEquals(
                4.0F,
                new WorldTrackerRenderService26.NewChunksRender(
                        0,
                        true,
                        5.0F,
                        128.0,
                        1
                ).lineWidth()
        );
    }

    private static WorldTrackerService26.RenderSnapshot snapshot() {
        List<NewChunksDecisionEngine26.ChunkSnapshot> chunks =
                new ArrayList<>();
        chunks.add(chunk(0, 0));
        List<LogoutSpotsDecisionEngine26.LogoutSpotSnapshot> logouts =
                new ArrayList<>();
        logouts.add(new LogoutSpotsDecisionEngine26.LogoutSpotSnapshot(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002"
                ),
                "Alice",
                2.0,
                64.0,
                2.0,
                45.0F,
                "minecraft:overworld",
                1L,
                1L,
                99L
        ));
        List<StashFinderDecisionEngine26.StashSnapshot> stashes =
                new ArrayList<>();
        stashes.add(new StashFinderDecisionEngine26.StashSnapshot(
                1L,
                "minecraft:overworld",
                StashFinderDecisionEngine26.Evidence.FIRST_SEEN,
                8.0,
                64.0,
                8.0,
                0,
                0,
                0,
                0,
                2,
                Map.of(
                        StashFinderDecisionEngine26.ContainerKind.CHEST,
                        2
                ),
                1L,
                2L
        ));
        WorldTrackerService26.RenderSnapshot result =
                new WorldTrackerService26.RenderSnapshot(
                        true,
                        1L,
                        2L,
                        "minecraft:overworld",
                        new WorldTrackerService26.ModuleState(true, true, true),
                        chunks,
                        logouts,
                        stashes
                );
        chunks.clear();
        logouts.clear();
        stashes.clear();
        return result;
    }

    private static NewChunksDecisionEngine26.ChunkSnapshot chunk(
            int chunkX,
            int chunkZ
    ) {
        return new NewChunksDecisionEngine26.ChunkSnapshot(
                chunkX,
                chunkZ,
                chunkX << 4,
                chunkZ << 4,
                NewChunksDecisionEngine26.Classification.FIRST_SEEN,
                1L,
                2L
        );
    }

    private static final class FakeView
            implements WorldTrackerRenderService26.RenderView {
        private String dimension = "minecraft:overworld";
        private int minimumY = -64;
        private double cameraX;
        private double cameraY = 64.0;
        private double cameraZ;
        private boolean visible = true;

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public int minimumY() {
            return minimumY;
        }

        @Override
        public double cameraX() {
            return cameraX;
        }

        @Override
        public double cameraY() {
            return cameraY;
        }

        @Override
        public double cameraZ() {
            return cameraZ;
        }

        @Override
        public boolean visible(AABB bounds) {
            return visible;
        }

        @Override
        public int textWidth(String text) {
            return text.length() * 6;
        }
    }
}
