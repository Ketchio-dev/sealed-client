package dev.b2tclient.performance;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceBoundedBlockScannerInvariantTest {
    private static final String SCANNER_CLASS = "dev.b2tclient.render.BoundedBlockScanner";

    @Test
    void scannerCachesHaveExplicitFiniteProductionBudgets() throws Exception {
        Class<?> scanner = Class.forName(SCANNER_CLASS);

        assertEquals(8_192, readStaticInt(scanner, "MAX_BLOCK_MATCHES"));
        assertEquals(2_048, readStaticInt(scanner, "MAX_HOLES"));
    }

    @Test
    void boundedInsertionNeverExceedsItsBudgetAndEvictsOldestEntries() throws Exception {
        Class<?> scanner = Class.forName(SCANNER_CLASS);
        Method putBounded = scanner.getDeclaredMethod(
                "putBounded",
                Map.class,
                Object.class,
                Object.class,
                int.class
        );
        putBounded.setAccessible(true);

        int entryBudget = 128;
        Map<BlockPos, Integer> entries = new LinkedHashMap<>();
        for (int index = 0; index < 4_096; index++) {
            putBounded.invoke(
                    null,
                    entries,
                    new BlockPos(index, 64, 0),
                    index,
                    entryBudget
            );
            assertTrue(entries.size() <= entryBudget);
        }

        assertEquals(entryBudget, entries.size());
        assertFalse(entries.containsKey(new BlockPos(0, 64, 0)));
        assertTrue(entries.containsKey(new BlockPos(4_095, 64, 0)));
    }

    @Test
    void cleanupKeepsOnlyHorizontalAndVerticalBudgetBoundaries() throws Exception {
        Class<?> scanner = Class.forName(SCANNER_CLASS);
        Method removeOutside = scanner.getDeclaredMethod(
                "removeOutside",
                Map.class,
                BlockPos.class,
                int.class
        );
        removeOutside.setAccessible(true);

        BlockPos centre = new BlockPos(0, 64, 0);
        Map<BlockPos, String> entries = new LinkedHashMap<>();
        entries.put(centre, "centre");
        entries.put(new BlockPos(10, 64, 0), "horizontal-boundary");
        entries.put(new BlockPos(0, 82, 0), "vertical-boundary");
        entries.put(new BlockPos(11, 64, 0), "outside-horizontal");
        entries.put(new BlockPos(8, 64, 8), "outside-radius");
        entries.put(new BlockPos(0, 83, 0), "outside-vertical");

        removeOutside.invoke(null, entries, centre, 10);

        assertEquals(3, entries.size());
        assertTrue(entries.containsKey(centre));
        assertTrue(entries.containsKey(new BlockPos(10, 64, 0)));
        assertTrue(entries.containsKey(new BlockPos(0, 82, 0)));
    }

    private static int readStaticInt(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
