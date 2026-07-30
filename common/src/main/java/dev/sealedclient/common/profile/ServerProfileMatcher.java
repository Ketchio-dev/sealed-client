package dev.sealedclient.common.profile;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects the most specific whole-endpoint glob without compiling regular
 * expressions. Supported wildcards are {@code *} (zero or more characters)
 * and {@code ?} (exactly one character); every other character is literal.
 */
public final class ServerProfileMatcher {
    public static final int MAX_SERVER_LENGTH = 255;
    public static final int MAX_PATTERN_LENGTH = 255;
    public static final int MAX_CANDIDATES = 512;
    public static final int MAX_MATCH_OPERATIONS = 2_048;

    private ServerProfileMatcher() {
    }

    public static Optional<ClientProfile> select(
            Collection<ClientProfile> profiles,
            String server
    ) {
        Objects.requireNonNull(profiles, "profiles");
        String endpoint = normalizeServer(server);
        Candidate best = null;
        ClientProfile fallback = null;
        int inspected = 0;

        for (ClientProfile profile : profiles) {
            if (inspected++ >= MAX_CANDIDATES) {
                break;
            }
            if (profile == null) {
                continue;
            }
            String pattern = normalizePattern(profile.serverPattern());
            if (pattern == null) {
                continue;
            }
            if ("*".equals(pattern)) {
                if (fallback == null) {
                    fallback = profile;
                }
                continue;
            }
            if (endpoint == null || !globMatches(pattern, endpoint)) {
                continue;
            }

            Candidate candidate = Candidate.of(profile, pattern);
            if (best == null || candidate.moreSpecificThan(best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best == null ? fallback : best.profile());
    }

    static boolean globMatches(String pattern, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int lastStar = -1;
        int valueAfterStar = -1;
        int operations = 0;

        while (valueIndex < value.length()) {
            if (++operations > MAX_MATCH_OPERATIONS) {
                return false;
            }
            if (patternIndex < pattern.length()) {
                char token = pattern.charAt(patternIndex);
                if (token == '?' || token == value.charAt(valueIndex)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (token == '*') {
                    lastStar = patternIndex++;
                    valueAfterStar = valueIndex;
                    continue;
                }
            }
            if (lastStar < 0) {
                return false;
            }
            patternIndex = lastStar + 1;
            valueIndex = ++valueAfterStar;
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            if (++operations > MAX_MATCH_OPERATIONS) {
                return false;
            }
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private static String normalizeServer(String server) {
        if (server == null || containsControlCharacter(server)) {
            return null;
        }
        String normalized = server.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.length() > MAX_SERVER_LENGTH) {
            return null;
        }
        return normalized;
    }

    private static String normalizePattern(String pattern) {
        if (pattern == null || containsControlCharacter(pattern)) {
            return null;
        }
        String normalized = pattern.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.length() > MAX_PATTERN_LENGTH) {
            return null;
        }

        StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean previousStar = false;
        for (int index = 0; index < normalized.length(); index++) {
            char token = normalized.charAt(index);
            if (token == '*' && previousStar) {
                continue;
            }
            collapsed.append(token);
            previousStar = token == '*';
        }
        return collapsed.toString();
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(
            ClientProfile profile,
            int literalCharacters,
            int wildcardCharacters,
            int starCharacters,
            boolean exact
    ) {
        private static Candidate of(ClientProfile profile, String pattern) {
            int wildcards = 0;
            int stars = 0;
            for (int index = 0; index < pattern.length(); index++) {
                char token = pattern.charAt(index);
                if (token == '*' || token == '?') {
                    wildcards++;
                }
                if (token == '*') {
                    stars++;
                }
            }
            return new Candidate(
                    profile,
                    pattern.length() - wildcards,
                    wildcards,
                    stars,
                    wildcards == 0
            );
        }

        private boolean moreSpecificThan(Candidate other) {
            if (literalCharacters != other.literalCharacters) {
                return literalCharacters > other.literalCharacters;
            }
            if (exact != other.exact) {
                return exact;
            }
            if (starCharacters != other.starCharacters) {
                return starCharacters < other.starCharacters;
            }
            return wildcardCharacters < other.wildcardCharacters;
        }
    }
}
