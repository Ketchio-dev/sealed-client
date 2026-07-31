package dev.sealedclient.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces that aim is written in exactly one place per platform.
 *
 * <p>Before the shared arbiter existed, fourteen production call sites wrote the
 * player's rotation directly and silently overwrote one another within a tick.
 * This test fails the build if any of them come back.</p>
 */
class RotationOwnershipPolicyTest {
    private static final Pattern ROTATION_WRITE =
            Pattern.compile("\\.set[XY]Rot\\s*\\(");

    /** The only production classes allowed to write rotation directly. */
    private static final List<Path> APPLIERS = List.of(
            Path.of("src/main/java/dev/sealedclient/service/RotationApplier.java"),
            Path.of("platform-26.2/src/main/java/dev/sealedclient/v26/RotationApplier26.java")
    );

    private static final List<Path> PRODUCTION_SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("common/src/main/java"),
            Path.of("platform-26.2/src/main/java")
    );

    @Test
    void bothPlatformAppliersExist() {
        for (Path applier : APPLIERS) {
            assertTrue(Files.isRegularFile(applier),
                    "Missing rotation applier: " + applier
                            + ". If it moved, update this policy test rather than deleting it.");
        }
    }

    @Test
    void productionSourceTreesArePresent() {
        for (Path root : PRODUCTION_SOURCES) {
            assertTrue(Files.isDirectory(root),
                    "Production source root vanished: " + root
                            + ". A silent pass here would make this policy meaningless.");
        }
    }

    @Test
    void onlyTheAppliersWriteRotationDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : PRODUCTION_SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!file.toString().endsWith(".java") || isApplier(file) || isMixin(file)) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (ROTATION_WRITE.matcher(source).find()) {
                        offenders.add(file.toString());
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("These classes write rotation directly instead of bidding through the "
                    + "rotation applier, so two modules can overwrite each other's aim: "
                    + offenders);
        }
    }

    @Test
    void appliersDelegateAngleMathsToTheSharedController() throws IOException {
        for (Path applier : APPLIERS) {
            String source = Files.readString(applier, StandardCharsets.UTF_8);
            assertTrue(source.contains("RotationController"),
                    applier + " must resolve competing bids through the shared controller");
            assertTrue(source.contains("stepYaw") && source.contains("stepPitch"),
                    applier + " must apply the shared turn-rate limiting");
        }
    }

    @Test
    void mixinsAreExemptOnlyForServerDrivenCorrections() throws IOException {
        // The No Rotate mixins intentionally write rotation: they restore the
        // local aim after a server correction. They must not aim at anything.
        for (Path root : PRODUCTION_SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!isMixin(file) || !file.toString().endsWith(".java")) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (!ROTATION_WRITE.matcher(source).find()) {
                        continue;
                    }
                    assertTrue(file.getFileName().toString().contains("NoRotate"),
                            "Only No Rotate mixins may write rotation outside the applier: " + file);
                }
            }
        }
    }

    @Test
    void combatUtilNoLongerExposesADirectRotationHelper() throws IOException {
        Path combatUtil = Path.of("src/main/java/dev/sealedclient/combat/CombatUtil.java");
        assertTrue(Files.isRegularFile(combatUtil), "CombatUtil moved: update this policy test");
        String source = Files.readString(combatUtil, StandardCharsets.UTF_8);
        assertTrue(source.contains("RotationApplier"),
                "CombatUtil.rotateToward must route through the applier");
        assertFalse(ROTATION_WRITE.matcher(source).find(),
                "CombatUtil must not write rotation directly");
    }

    private static boolean isApplier(Path file) {
        return APPLIERS.stream().anyMatch(applier -> file.endsWith(applier.getFileName()));
    }

    private static boolean isMixin(Path file) {
        return file.toString().contains("/mixin/");
    }
}
