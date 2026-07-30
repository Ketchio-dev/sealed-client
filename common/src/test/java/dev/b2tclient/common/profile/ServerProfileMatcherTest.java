package dev.b2tclient.common.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerProfileMatcherTest {
    @Test
    void exactThenMostSpecificGlobThenDefaultFallbackAreSelected() {
        ClientProfile fallback = profile("default", "*");
        ClientProfile network = profile("network", "*.2b2t.org");
        ClientProfile queue = profile("queue", "queue.2b2t.org");
        ProfileBook book = new ProfileBook();
        book.replaceAll(List.of(fallback, network, queue), "default");

        assertEquals(
                "queue",
                book.bestMatchForServer(" QUEUE.2B2T.ORG ").orElseThrow().name()
        );
        assertEquals(
                "network",
                book.bestMatchForServer("play.2b2t.org").orElseThrow().name()
        );
        assertEquals(
                "default",
                book.bestMatchForServer("example.org").orElseThrow().name()
        );
    }

    @Test
    void questionWildcardMatchesOneLiteralCharacter() {
        ClientProfile fallback = profile("default", "*");
        ClientProfile numbered = profile("numbered", "node?.example.org");

        assertEquals(
                "numbered",
                ServerProfileMatcher.select(
                        List.of(fallback, numbered),
                        "node7.example.org"
                ).orElseThrow().name()
        );
        assertEquals(
                "default",
                ServerProfileMatcher.select(
                        List.of(fallback, numbered),
                        "node77.example.org"
                ).orElseThrow().name()
        );
    }

    @Test
    void regexMetacharactersAreAlwaysMatchedLiterally() {
        ClientProfile fallback = profile("default", "*");
        ClientProfile dotStar = profile("dot-star", ".*");
        ClientProfile brackets = profile("brackets", "[ab]*");

        assertEquals(
                "default",
                ServerProfileMatcher.select(
                        List.of(fallback, dotStar, brackets),
                        "alpha.example"
                ).orElseThrow().name()
        );
        assertEquals(
                "dot-star",
                ServerProfileMatcher.select(
                        List.of(fallback, dotStar),
                        ".hidden"
                ).orElseThrow().name()
        );
        assertEquals(
                "brackets",
                ServerProfileMatcher.select(
                        List.of(fallback, brackets),
                        "[ab]server"
                ).orElseThrow().name()
        );
    }

    @Test
    void invalidLengthsAndControlCharactersFallBackWithoutUnboundedWork() {
        ClientProfile fallback = profile("default", "*");
        ClientProfile oversized = profile(
                "oversized",
                "a".repeat(ServerProfileMatcher.MAX_PATTERN_LENGTH + 1)
        );
        ClientProfile control = profile("control", "bad\n*");
        String oversizedServer = "x".repeat(ServerProfileMatcher.MAX_SERVER_LENGTH + 1);

        assertEquals(
                "default",
                ServerProfileMatcher.select(
                        List.of(fallback, oversized, control),
                        oversizedServer
                ).orElseThrow().name()
        );
        String adversarial = "*a".repeat(120) + "z";
        assertEquals(
                "default",
                ServerProfileMatcher.select(
                        List.of(fallback, profile("bounded", adversarial)),
                        "a".repeat(255)
                ).orElseThrow().name()
        );
    }

    @Test
    void candidateInspectionIsCappedAndTieBreakingIsInsertionStable() {
        ClientProfile fallback = profile("default", "*");
        ClientProfile first = profile("first", "node*.example.org");
        ClientProfile second = profile("second", "node?.example.org");

        assertEquals(
                "second",
                ServerProfileMatcher.select(
                        List.of(fallback, first, second),
                        "node1.example.org"
                ).orElseThrow().name()
        );
        assertEquals(
                "tie-first",
                ServerProfileMatcher.select(
                        List.of(
                                fallback,
                                profile("tie-first", "*.tie.example"),
                                profile("tie-second", "*.tie.example")
                        ),
                        "node.tie.example"
                ).orElseThrow().name()
        );

        List<ClientProfile> many = new ArrayList<>();
        many.add(fallback);
        for (int index = 1; index < ServerProfileMatcher.MAX_CANDIDATES; index++) {
            many.add(profile("profile-" + index, "never-" + index));
        }
        many.add(profile("outside-limit", "target.example.org"));
        assertEquals(
                "default",
                ServerProfileMatcher.select(many, "target.example.org")
                        .orElseThrow()
                        .name()
        );
    }

    private static ClientProfile profile(String name, String pattern) {
        return new ClientProfile(name, pattern, Map.of());
    }
}
