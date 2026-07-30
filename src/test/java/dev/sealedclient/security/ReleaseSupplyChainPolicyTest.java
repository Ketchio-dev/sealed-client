package dev.sealedclient.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSupplyChainPolicyTest {
    @Test
    void dependencyVerificationPinsArtifactChecksumsWithoutBroadBypasses()
            throws IOException {
        String metadata = Files.readString(
                Path.of("gradle/verification-metadata.xml"),
                StandardCharsets.UTF_8
        );

        assertTrue(metadata.contains("<verify-metadata>true</verify-metadata>"));
        assertFalse(metadata.contains("<verification-mode>off</verification-mode>"));
        assertFalse(metadata.contains("<trusted-artifacts>"));
        assertTrue(
                occurrences(metadata, "<sha256 value=") >= 20,
                "Dependency verification metadata must contain pinned SHA-256 values"
        );
    }

    @Test
    void releaseBundleDeclaresReproducibleArchivesSbomAndChecksums()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);

        assertTrue(build.contains("preserveFileTimestamps = false"));
        assertTrue(build.contains("reproducibleFileOrder = true"));
        assertTrue(build.contains("tasks.register(\"generateSbom\")"));
        assertTrue(build.contains("\"CycloneDX\""));
        assertTrue(build.contains("tasks.register(\"generateReleaseChecksums\")"));
        assertTrue(build.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(build.contains("tasks.register(\"releaseBundle\""));
        assertTrue(build.contains("from(\"SECURITY.md\")"));
        assertTrue(build.contains("from(\"NOTICE\")"));
        assertTrue(build.contains("from(\"LICENSE\")"));
        assertTrue(build.contains("tasks.register(\"multiVersionRelease\""));

        String platformBuild = Files.readString(
                Path.of("platform-26.2/build.gradle"),
                StandardCharsets.UTF_8
        );
        assertTrue(platformBuild.contains("preserveFileTimestamps = false"));
        assertTrue(platformBuild.contains("reproducibleFileOrder = true"));
        assertTrue(platformBuild.contains("tasks.register(\"sha256Jar\")"));
        assertTrue(platformBuild.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(platformBuild.contains("rootProject.file(\"LICENSE\")"));
    }

    @Test
    void shippedFabricMetadataHasOnlyTheReviewedClientEntrypoint() throws IOException {
        assertClientEntrypoint(
                Path.of("src/main/resources/fabric.mod.json"),
                "dev.sealedclient.SealedClient"
        );
        assertClientEntrypoint(
                Path.of("platform-26.2/src/main/resources/fabric.mod.json"),
                "dev.sealedclient.v26.SealedClient26"
        );
    }

    private static void assertClientEntrypoint(Path path, String expectedEntrypoint)
            throws IOException {
        JsonObject metadata = JsonParser.parseString(Files.readString(
                path,
                StandardCharsets.UTF_8
        )).getAsJsonObject();
        JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");

        assertEquals(1, entrypoints.size());
        assertTrue(entrypoints.has("client"));
        assertEquals(
                expectedEntrypoint,
                entrypoints.getAsJsonArray("client").get(0).getAsString()
        );
        assertEquals("client", metadata.get("environment").getAsString());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
