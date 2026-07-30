package dev.sealedclient.v26.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryDecisionEngine26Test {
    private static final TrajectoryDecisionEngine26.Vector3 ORIGIN =
            new TrajectoryDecisionEngine26.Vector3(0.0, 64.0, 0.0);
    private static final TrajectoryDecisionEngine26.CollisionQuery MISS =
            (start, end) ->
                    TrajectoryDecisionEngine26.Collision.miss();

    @Test
    void exposesAccurateProfilesForAllSupportedProjectiles() {
        assertEquals(
                11,
                TrajectoryDecisionEngine26.ProjectileType.values().length
        );
        for (TrajectoryDecisionEngine26.ProjectileType type
                : TrajectoryDecisionEngine26.ProjectileType.values()) {
            TrajectoryDecisionEngine26.ProjectileParameters parameters =
                    TrajectoryDecisionEngine26.parameters(type);
            assertEquals(type, parameters.type());
            assertTrue(parameters.speed() > 0.0);
            assertTrue(parameters.drag() >= 0.0);
            assertTrue(parameters.drag() <= 1.0);
            assertTrue(parameters.gravity() >= 0.0);
        }

        assertEquals(
                3.15,
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType
                                .CROSSBOW_ARROW
                ).speed(),
                1.0E-9
        );
        assertEquals(
                1.6,
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType
                                .CROSSBOW_FIREWORK
                ).speed(),
                1.0E-9
        );
        assertEquals(
                2.5,
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType.TRIDENT
                ).speed(),
                1.0E-9
        );
        assertEquals(
                0.07,
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType
                                .EXPERIENCE_BOTTLE
                ).gravity(),
                1.0E-9
        );
        assertEquals(
                0.0,
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType.WIND_CHARGE
                ).forwardAcceleration(),
                1.0E-9
        );
    }

    @Test
    void bowDrawPowerIsBoundedAndChangesLaunchSpeed() {
        assertEquals(
                0.3,
                TrajectoryDecisionEngine26.bowParameters(0.0).speed(),
                1.0E-9
        );
        assertEquals(
                1.5,
                TrajectoryDecisionEngine26.bowParameters(0.5).speed(),
                1.0E-9
        );
        assertEquals(
                3.0,
                TrajectoryDecisionEngine26.bowParameters(1.0).speed(),
                1.0E-9
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> TrajectoryDecisionEngine26.bowParameters(-0.01)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> TrajectoryDecisionEngine26.bowParameters(1.01)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> TrajectoryDecisionEngine26.bowParameters(
                        Double.NaN
                )
        );
    }

    @Test
    void gravityAndDragAreAppliedAfterEachClearTick() {
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.0,
                                0.0,
                                1.0
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .SNOWBALL
                        ),
                        new TrajectoryDecisionEngine26.Limits(2, 20.0),
                        MISS
                );

        assertEquals(
                TrajectoryDecisionEngine26.Termination.STEP_LIMIT,
                result.termination()
        );
        assertEquals(2, result.segments().size());
        assertEquals(
                1.5,
                result.segments().get(0).velocity().z(),
                1.0E-9
        );
        assertEquals(
                1.485,
                result.segments().get(1).velocity().z(),
                1.0E-9
        );
        assertEquals(
                -0.03,
                result.segments().get(1).velocity().y(),
                1.0E-9
        );
        assertTrue(result.segments().get(1).end().y() < 64.0);
    }

    @Test
    void launchInheritsFiniteShooterVelocity() {
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulateFromRotation(
                        ORIGIN,
                        0.0,
                        0.0,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.4,
                                -0.2,
                                0.1
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .SNOWBALL
                        ),
                        new TrajectoryDecisionEngine26.Limits(1, 10.0),
                        MISS
                );

        assertEquals(0.4, result.finalPosition().x(), 1.0E-9);
        assertEquals(63.8, result.finalPosition().y(), 1.0E-9);
        assertEquals(1.6, result.finalPosition().z(), 1.0E-9);
        assertEquals(
                TrajectoryDecisionEngine26.Termination.STEP_LIMIT,
                result.termination()
        );
    }

    @Test
    void rotationUsesMinecraftAxesAndProjectilePitchOffset() {
        TrajectoryDecisionEngine26.Result forward =
                TrajectoryDecisionEngine26.simulateFromRotation(
                        ORIGIN,
                        0.0,
                        0.0,
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .SNOWBALL
                        ),
                        new TrajectoryDecisionEngine26.Limits(1, 10.0),
                        MISS
                );
        assertEquals(
                1.5,
                forward.finalPosition().z(),
                1.0E-9
        );
        assertEquals(0.0, forward.finalPosition().x(), 1.0E-9);

        TrajectoryDecisionEngine26.Result east =
                TrajectoryDecisionEngine26.simulateFromRotation(
                        ORIGIN,
                        -90.0,
                        0.0,
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .SNOWBALL
                        ),
                        new TrajectoryDecisionEngine26.Limits(1, 10.0),
                        MISS
                );
        assertEquals(1.5, east.finalPosition().x(), 1.0E-9);

        TrajectoryDecisionEngine26.Result potion =
                TrajectoryDecisionEngine26.simulateFromRotation(
                        ORIGIN,
                        0.0,
                        0.0,
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .SPLASH_POTION
                        ),
                        new TrajectoryDecisionEngine26.Limits(1, 10.0),
                        MISS
                );
        assertTrue(potion.finalPosition().y() > ORIGIN.y());
    }

    @Test
    void firstCollisionStopsAtReportedSegmentFraction() {
        AtomicInteger queries = new AtomicInteger();
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.0,
                                0.0,
                                1.0
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .TRIDENT
                        ),
                        new TrajectoryDecisionEngine26.Limits(120, 96.0),
                        (start, end) -> {
                            queries.incrementAndGet();
                            return TrajectoryDecisionEngine26.Collision.hit(
                                    TrajectoryDecisionEngine26.CollisionKind
                                            .BLOCK,
                                    0.4
                            );
                        }
                );

        assertEquals(
                TrajectoryDecisionEngine26.Termination.COLLISION,
                result.termination()
        );
        assertEquals(1, queries.get());
        assertEquals(1, result.simulatedSteps());
        assertEquals(1.0, result.travelledDistance(), 1.0E-9);
        assertEquals(1.0, result.finalPosition().z(), 1.0E-9);
        assertEquals(
                TrajectoryDecisionEngine26.CollisionKind.BLOCK,
                result.impact().orElseThrow().kind()
        );
        assertEquals(0, result.impact().orElseThrow().step());
    }

    @Test
    void rangeLimitClipsFinalQueryAndSegmentExactly() {
        List<TrajectoryDecisionEngine26.Vector3> queriedEnds =
                new ArrayList<>();
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.0,
                                0.0,
                                2.0
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .TRIDENT
                        ),
                        new TrajectoryDecisionEngine26.Limits(20, 1.25),
                        (start, end) -> {
                            queriedEnds.add(end);
                            return TrajectoryDecisionEngine26.Collision.miss();
                        }
                );

        assertEquals(
                TrajectoryDecisionEngine26.Termination.RANGE_LIMIT,
                result.termination()
        );
        assertEquals(1, queriedEnds.size());
        assertEquals(1.25, result.travelledDistance(), 1.0E-9);
        assertEquals(1.25, result.finalPosition().z(), 1.0E-9);
        assertEquals(
                result.finalPosition(),
                queriedEnds.get(0)
        );
    }

    @Test
    void stepLimitStrictlyBoundsCollisionQueries() {
        AtomicInteger queries = new AtomicInteger();
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.0,
                                0.0,
                                1.0
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .BOW_ARROW
                        ),
                        new TrajectoryDecisionEngine26.Limits(7, 96.0),
                        (start, end) -> {
                            queries.incrementAndGet();
                            return TrajectoryDecisionEngine26.Collision.miss();
                        }
                );

        assertEquals(
                TrajectoryDecisionEngine26.Termination.STEP_LIMIT,
                result.termination()
        );
        assertEquals(7, queries.get());
        assertEquals(7, result.segments().size());
        assertEquals(7, result.simulatedSteps());
    }

    @Test
    void collisionOnRangeClippedSegmentWinsOverRangeTermination() {
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(
                                0.0,
                                0.0,
                                1.0
                        ),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .TRIDENT
                        ),
                        new TrajectoryDecisionEngine26.Limits(20, 1.0),
                        (start, end) ->
                                TrajectoryDecisionEngine26.Collision.hit(
                                        TrajectoryDecisionEngine26
                                                .CollisionKind.ENTITY,
                                        0.5
                                )
                );

        assertEquals(
                TrajectoryDecisionEngine26.Termination.COLLISION,
                result.termination()
        );
        assertEquals(0.5, result.travelledDistance(), 1.0E-9);
        assertEquals(
                TrajectoryDecisionEngine26.CollisionKind.ENTITY,
                result.impact().orElseThrow().kind()
        );
    }

    @Test
    void nullMalformedAndThrowingCollisionQueriesFailClosed() {
        TrajectoryDecisionEngine26.ProjectileParameters projectile =
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType.SNOWBALL
                );
        TrajectoryDecisionEngine26.Limits limits =
                new TrajectoryDecisionEngine26.Limits(10, 20.0);

        TrajectoryDecisionEngine26.Result nullCollision =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(0, 0, 1),
                        projectile,
                        limits,
                        (start, end) -> null
                );
        assertEquals(
                TrajectoryDecisionEngine26.Termination.INVALID_COLLISION,
                nullCollision.termination()
        );
        assertTrue(nullCollision.segments().isEmpty());

        TrajectoryDecisionEngine26.Result malformed =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(0, 0, 1),
                        projectile,
                        limits,
                        (start, end) ->
                                new TrajectoryDecisionEngine26.Collision(
                                        TrajectoryDecisionEngine26
                                                .CollisionKind.BLOCK,
                                        1.5
                                )
                );
        assertEquals(
                TrajectoryDecisionEngine26.Termination.INVALID_COLLISION,
                malformed.termination()
        );

        TrajectoryDecisionEngine26.Result throwing =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(0, 0, 1),
                        projectile,
                        limits,
                        (start, end) -> {
                            throw new IllegalStateException("world unloaded");
                        }
                );
        assertEquals(
                TrajectoryDecisionEngine26.Termination
                        .COLLISION_QUERY_ERROR,
                throwing.termination()
        );
    }

    @Test
    void invalidLaunchDataFailsClosedWithoutCallingWorld() {
        AtomicInteger queries = new AtomicInteger();
        TrajectoryDecisionEngine26.CollisionQuery query =
                (start, end) -> {
                    queries.incrementAndGet();
                    return TrajectoryDecisionEngine26.Collision.miss();
                };
        TrajectoryDecisionEngine26.ProjectileParameters projectile =
                TrajectoryDecisionEngine26.parameters(
                        TrajectoryDecisionEngine26.ProjectileType.SNOWBALL
                );

        TrajectoryDecisionEngine26.Result zeroDirection =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        TrajectoryDecisionEngine26.Vector3.ZERO,
                        projectile,
                        TrajectoryDecisionEngine26.Limits.DEFAULT,
                        query
                );
        assertEquals(
                TrajectoryDecisionEngine26.Termination.INVALID_INPUT,
                zeroDirection.termination()
        );

        TrajectoryDecisionEngine26.Result invalidRotation =
                TrajectoryDecisionEngine26.simulateFromRotation(
                        ORIGIN,
                        Double.NaN,
                        0.0,
                        projectile,
                        TrajectoryDecisionEngine26.Limits.DEFAULT,
                        query
                );
        assertEquals(
                TrajectoryDecisionEngine26.Termination.INVALID_INPUT,
                invalidRotation.termination()
        );
        assertEquals(0, queries.get());
    }

    @Test
    void hardBoundsAndParameterValidationRejectUnsafeWork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrajectoryDecisionEngine26.Limits(0, 10.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrajectoryDecisionEngine26.Limits(
                        TrajectoryDecisionEngine26.HARD_MAXIMUM_STEPS + 1,
                        10.0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrajectoryDecisionEngine26.Limits(
                        10,
                        TrajectoryDecisionEngine26.HARD_MAXIMUM_RANGE + 0.1
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrajectoryDecisionEngine26.ProjectileParameters(
                        TrajectoryDecisionEngine26.ProjectileType.SNOWBALL,
                        1.5,
                        1.01,
                        0.03,
                        0.0,
                        0.0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrajectoryDecisionEngine26.ProjectileParameters(
                        TrajectoryDecisionEngine26.ProjectileType.SNOWBALL,
                        1.0E-9,
                        0.99,
                        0.03,
                        0.0,
                        0.0
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> TrajectoryDecisionEngine26.parameters(null)
        );
    }

    @Test
    void resultAndSegmentsAreImmutableSnapshots() {
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(0, 0, 1),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType.EGG
                        ),
                        new TrajectoryDecisionEngine26.Limits(2, 10.0),
                        MISS
                );

        assertThrows(
                UnsupportedOperationException.class,
                () -> result.segments().clear()
        );
        assertNotSame(List.of(), result.segments());
        assertFalse(result.impact().isPresent());
    }

    @Test
    void playerThrownWindChargeKeepsConstantVelocity() {
        TrajectoryDecisionEngine26.Result result =
                TrajectoryDecisionEngine26.simulate(
                        ORIGIN,
                        new TrajectoryDecisionEngine26.Vector3(0, 0, 1),
                        TrajectoryDecisionEngine26.parameters(
                                TrajectoryDecisionEngine26.ProjectileType
                                        .WIND_CHARGE
                        ),
                        new TrajectoryDecisionEngine26.Limits(3, 20.0),
                        MISS
                );

        assertEquals(
                1.5,
                result.segments().get(0).velocity().z(),
                1.0E-9
        );
        assertEquals(
                1.5,
                result.segments().get(1).velocity().z(),
                1.0E-9
        );
        assertEquals(
                1.5,
                result.segments().get(2).velocity().z(),
                1.0E-9
        );
        assertEquals(
                64.0,
                result.finalPosition().y(),
                1.0E-9
        );
    }
}
