package dev.sealedclient.common.hud;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueTextParserTest {
    @Test
    void anExplicitQueueLineWins() {
        assertEquals(
                "Queue: Position in queue: 412",
                QueueTextParser.parse(
                        "2b2t",
                        List.of("Welcome", "Position in queue: 412", "ETA: 3h")
                ).orElseThrow()
        );
    }

    @Test
    void aQueueSidebarFallsBackToItsFirstLine() {
        assertEquals(
                "Queue: 517",
                QueueTextParser.parse("Queue", List.of("517", "ETA 2h")).orElseThrow()
        );
    }

    @Test
    void aQueueSidebarWithNoLinesFallsBackToTheTitle() {
        assertEquals(
                "Queue: In queue",
                QueueTextParser.parse("In queue", List.of()).orElseThrow()
        );
    }

    @Test
    void blankAndNullLinesAreIgnored() {
        assertEquals(
                "Queue: 88",
                QueueTextParser.parse("Queue", Arrays.asList(null, "   ", "88")).orElseThrow()
        );
    }

    @Test
    void anUnrelatedSidebarProducesNothing() {
        assertTrue(QueueTextParser.parse("Stats", List.of("Kills: 3", "Deaths: 1")).isEmpty());
    }

    @Test
    void anAbsentSidebarProducesNothing() {
        assertTrue(QueueTextParser.parse(null, List.of("Position in queue: 1")).isEmpty());
    }

    @Test
    void queueTermsAreMatchedCaseInsensitively() {
        assertTrue(QueueTextParser.mentionsQueue("QUEUE"));
        assertTrue(QueueTextParser.mentionsQueue("Your Position"));
        assertTrue(QueueTextParser.mentionsQueue("2nd place"));
        assertTrue(!QueueTextParser.mentionsQueue("Kills: 3"));
        assertTrue(!QueueTextParser.mentionsQueue(null));
    }
}
