package dev.sealedclient.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps version-sensitive Minecraft access funnelled through the adapters.
 *
 * <p>These names get renamed between versions. When they were referenced
 * directly, a rename that is one line of real change produced sixty-one
 * compile errors across seventeen files. The adapters exist so that number
 * stays at one file, and this test is what stops new call sites from
 * reintroducing the problem.</p>
 */
final class PlatformAdapterPolicyTest {
    private record Rule(String pattern, String adapter) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("setSelectedHotbarSlot", "HotbarAccess"),
            new Rule("getInventory().selected", "HotbarAccess"),
            new Rule("input.forwardImpulse", "MovementInputAccess"),
            new Rule("input.leftImpulse", "MovementInputAccess"),
            new Rule("instanceof SwordItem", "ItemKinds"),
            new Rule("instanceof PickaxeItem", "ItemKinds"),
            new Rule("MobEffects.DAMAGE_RESISTANCE", "EntityAccess"),
            new Rule("getArmorSlots()", "EntityAccess"),
            new Rule("absMoveTo(", "EntityAccess"),
            new Rule("getInventory().getSelected()", "HotbarAccess")
    );

    @Test
    void versionSensitiveAccessGoesThroughAnAdapter() throws IOException {
        Path root = Path.of("src/main/java/dev/sealedclient");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getParent().getFileName().toString().equals("platform")) {
                    continue;
                }
                String source = Files.readString(file);
                for (Rule rule : RULES) {
                    if (source.contains(rule.pattern())) {
                        offenders.add(file + " uses '" + rule.pattern()
                                + "', which belongs in " + rule.adapter());
                    }
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "version-sensitive access outside the adapters:\n  "
                        + String.join("\n  ", offenders)
        );
    }
}
