package dev.b2tclient.v26.visual;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockIdDiagnostics26Test {
    private static final Set<String> KNOWN = Set.of(
            "minecraft:diamond_ore",
            "minecraft:ancient_debris",
            "minecraft:obsidian"
    );
    private static final Predicate<Identifier> REGISTRY =
            identifier -> KNOWN.contains(identifier.toString());

    @Test
    void aFullyValidListReportsNoProblems() {
        BlockIdDiagnostics26.Report report = BlockIdDiagnostics26.inspect(
                "diamond_ore, minecraft:ancient_debris", REGISTRY);

        assertFalse(report.hasProblems());
        assertNull(report.message());
        assertEquals(2, report.resolved());
    }

    @Test
    void anUnregisteredButWellFormedIdIsNamedAsMissing() {
        BlockIdDiagnostics26.Report report = BlockIdDiagnostics26.inspect(
                "diamond_ore, minecraft:diamnod_ore", REGISTRY);

        assertTrue(report.hasProblems());
        assertEquals(List.of(), report.malformed());
        assertEquals(List.of("minecraft:diamnod_ore"), report.unknown());
        assertEquals(1, report.resolved());
        assertTrue(report.message().contains("No such block: minecraft:diamnod_ore"));
        assertTrue(report.message().contains("(1 active)"));
    }

    @Test
    void anUnparseableEntryIsReportedSeparatelyFromAMissingBlock() {
        BlockIdDiagnostics26.Report report = BlockIdDiagnostics26.inspect(
                "Not A Block!, minecraft:nope, obsidian", REGISTRY);

        assertEquals(List.of("not a block!"), report.malformed());
        assertEquals(List.of("minecraft:nope"), report.unknown());
        assertEquals(1, report.resolved());
        assertTrue(report.message().startsWith("Invalid id: not a block!"));
        assertTrue(report.message().contains("No such block: minecraft:nope"));
    }

    @Test
    void blankAndEmptyListsAreNotProblems() {
        assertFalse(BlockIdDiagnostics26.inspect(null, REGISTRY).hasProblems());
        assertFalse(BlockIdDiagnostics26.inspect("", REGISTRY).hasProblems());
        assertFalse(BlockIdDiagnostics26.inspect("  ,  , ", REGISTRY).hasProblems());
        assertEquals(0, BlockIdDiagnostics26.inspect(" , ", REGISTRY).resolved());
    }

    @Test
    void duplicatesCollapseAndReportingIsBounded() {
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            many.append("minecraft:missing_").append(index).append(',');
        }
        BlockIdDiagnostics26.Report report =
                BlockIdDiagnostics26.inspect(many.toString(), REGISTRY);
        assertEquals(BlockIdDiagnostics26.MAX_REPORTED, report.unknown().size(),
                "a pasted list of typos must not produce an unbounded message");

        BlockIdDiagnostics26.Report duplicates = BlockIdDiagnostics26.inspect(
                "obsidian, minecraft:obsidian, OBSIDIAN", REGISTRY);
        assertEquals(1, duplicates.resolved(), "the same block counts once");
    }

    @Test
    void anOverlongEntryIsRejectedAsMalformedRatherThanQueried() {
        String overlong = "minecraft:" + "a".repeat(200);
        BlockIdDiagnostics26.Report report =
                BlockIdDiagnostics26.inspect(overlong, REGISTRY);
        assertEquals(1, report.malformed().size());
        assertEquals(0, report.resolved());
    }
}
