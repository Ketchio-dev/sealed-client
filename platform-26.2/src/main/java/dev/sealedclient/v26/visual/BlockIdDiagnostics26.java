package dev.sealedclient.v26.visual;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Explains why entries in a block-id list will not do anything.
 *
 * <p>XRay and Block ESP both silently drop ids they cannot resolve, which makes
 * a single typo look identical to a module that simply is not working. This
 * classifies each comma-separated entry so the GUI can name the bad ones.</p>
 *
 * <p>The registry lookup is injected as a predicate so the classification is
 * testable without a bootstrapped Minecraft registry.</p>
 */
public final class BlockIdDiagnostics26 {
    /** Matches the per-list cap the overlay configuration enforces. */
    public static final int MAX_REPORTED = 8;

    private BlockIdDiagnostics26() {
    }

    /**
     * Classifies a raw comma-separated block list against the live registry.
     */
    public static Report inspect(String rawList) {
        return inspect(rawList, BlockIdDiagnostics26::registryContains);
    }

    static Report inspect(String rawList, Predicate<Identifier> known) {
        List<String> malformed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> resolved = new LinkedHashSet<>();
        if (rawList == null || rawList.isBlank()) {
            return new Report(List.of(), List.of(), 0);
        }
        for (String raw : rawList.split(",")) {
            String candidate = raw.trim().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.length() > 128) {
                addBounded(malformed, candidate);
                continue;
            }
            Identifier identifier = candidate.indexOf(':') >= 0
                    ? Identifier.tryParse(candidate)
                    : Identifier.tryBuild("minecraft", candidate);
            if (identifier == null) {
                addBounded(malformed, candidate);
                continue;
            }
            if (!known.test(identifier)) {
                addBounded(unknown, identifier.toString());
                continue;
            }
            resolved.add(identifier.toString());
        }
        return new Report(List.copyOf(malformed), List.copyOf(unknown), resolved.size());
    }

    private static void addBounded(List<String> sink, String value) {
        if (sink.size() < MAX_REPORTED && !sink.contains(value)) {
            sink.add(value);
        }
    }

    private static boolean registryContains(Identifier identifier) {
        try {
            return BuiltInRegistries.BLOCK.containsKey(identifier);
        } catch (RuntimeException ignored) {
            // Before the registry is bootstrapped nothing can be validated;
            // reporting the id as known avoids a screen full of false alarms.
            return true;
        }
    }

    /**
     * @param malformed  entries that are not valid identifiers at all
     * @param unknown    well-formed identifiers with no such block registered
     * @param resolved   how many entries will actually take effect
     */
    public record Report(List<String> malformed, List<String> unknown, int resolved) {
        public boolean hasProblems() {
            return !malformed.isEmpty() || !unknown.isEmpty();
        }

        /** A single line naming the problems, or null when there are none. */
        public String message() {
            if (!hasProblems()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            if (!malformed.isEmpty()) {
                text.append("Invalid id: ").append(String.join(", ", malformed));
            }
            if (!unknown.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("  ");
                }
                text.append("No such block: ").append(String.join(", ", unknown));
            }
            return text + "  (" + resolved + " active)";
        }
    }
}
