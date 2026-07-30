package dev.sealedclient.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ExpandedSecurityBoundaryTest {
    private static final List<Path> PRODUCTION_SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("common/src/main/java"),
            Path.of("platform-26.2/src/main/java")
    );
    private static final Path CONFIG_MANAGER = Path.of(
            "src/main/java/dev/sealedclient/config/ConfigManager.java"
    );
    private static final Path CONFIG_STORE_26 = Path.of(
            "platform-26.2/src/main/java/dev/sealedclient/v26/config/ConfigStore26.java"
    );
    private static final Path REFLECTIVE_BARITONE_ACCESS_26 = Path.of(
            "platform-26.2/src/main/java/dev/sealedclient/v26/integration/"
                    + "ReflectiveBaritoneAccess26.java"
    );

    @Test
    void reviewedSecurityBoundaryFilesExistAfterPackageMoves() {
        assertTrue(Files.isRegularFile(CONFIG_MANAGER));
        assertTrue(Files.isRegularFile(CONFIG_STORE_26));
        assertTrue(Files.isRegularFile(REFLECTIVE_BARITONE_ACCESS_26));
    }

    @Test
    void productionHasNoDirectExfiltrationExecutionOrDynamicLoadingApis() throws IOException {
        List<ForbiddenPattern> forbidden = List.of(
                rule("JDK networking", "\\b(?:java|javax)\\.net\\."),
                rule("third-party HTTP client", "\\b(?:okhttp3|org\\.apache\\.http)\\."),
                rule(
                        "socket or URL connection",
                        "\\b(?:Socket|ServerSocket|DatagramSocket|HttpClient|"
                                + "URLConnection|URLClassLoader)\\b"
                ),
                rule("process execution", "\\b(?:ProcessBuilder|ProcessHandle)\\b"),
                rule("runtime execution", "\\bRuntime\\s*\\.\\s*getRuntime\\s*\\("),
                rule("environment access", "\\bSystem\\s*\\.\\s*getenv\\s*\\("),
                rule(
                        "host property access",
                        "\\bSystem\\s*\\.\\s*(?:getProperty|getProperties)\\s*\\("
                ),
                rule(
                        "native library loading",
                        "\\bSystem\\s*\\.\\s*(?:load|loadLibrary)\\s*\\("
                ),
                rule("JDK internal API", "\\b(?:sun|jdk\\.internal)\\."),
                rule("reflection API", "\\bjava\\.lang\\.reflect\\."),
                rule("manual class loading", "\\bClass\\s*\\.\\s*forName\\s*\\("),
                rule(
                        "sensitive account token",
                        "(?i)\\b(?:access|refresh|session)[_ -]?token\\b"
                ),
                rule(
                        "Minecraft launcher account store",
                        "(?i)\\blauncher[_ -]?(?:accounts|profiles)\\b"
                ),
                rule(
                        "telemetry endpoint",
                        "(?i)\\b(?:webhook|telemetry|analytics|sentry)\\b"
                )
        );

        for (SourceFile sourceFile : productionSources()) {
            for (ForbiddenPattern forbiddenPattern : forbidden) {
                if (isReviewedBaritoneReflection(
                        sourceFile,
                        forbiddenPattern
                )) {
                    continue;
                }
                if (forbiddenPattern.pattern().matcher(sourceFile.source()).find()) {
                    fail(forbiddenPattern.description() + " found in " + sourceFile.path());
                }
            }
        }
    }

    @Test
    void reflectionIsConfinedToReviewedOptionalBaritoneAdapter()
            throws IOException {
        Pattern reflection = Pattern.compile(
                "\\b(?:java\\.lang\\.reflect\\.|Class\\s*\\.\\s*forName\\s*\\()"
        );
        Set<Path> reflectiveSources = productionSources().stream()
                .filter(source -> reflection.matcher(source.source()).find())
                .map(SourceFile::path)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(
                Set.of(REFLECTIVE_BARITONE_ACCESS_26),
                reflectiveSources,
                "New reflection requires an explicit security review"
        );
        String adapter = Files.readString(
                REFLECTIVE_BARITONE_ACCESS_26,
                StandardCharsets.UTF_8
        );
        assertTrue(adapter.contains("Class.forName(name, false, loader)"));
        assertTrue(adapter.contains("\"baritone.api.BaritoneAPI\""));
    }

    @Test
    void directFileSystemAccessIsConfinedToConfigurationPersistence() throws IOException {
        List<Path> directFileUsers = new ArrayList<>();
        Pattern fileApi = Pattern.compile(
                "^import\\s+java\\.(?:io|nio\\.file)(?:\\.|;)",
                Pattern.MULTILINE
        );
        Pattern fullyQualifiedFileApi = Pattern.compile(
                "\\bjava\\.(?:io|nio\\.file)\\."
        );

        for (SourceFile sourceFile : productionSources()) {
            if (fileApi.matcher(sourceFile.source()).find()
                    || fullyQualifiedFileApi.matcher(sourceFile.source()).find()) {
                directFileUsers.add(sourceFile.path());
            }
        }

        assertEquals(
                Set.of(CONFIG_MANAGER, CONFIG_STORE_26),
                Set.copyOf(directFileUsers),
                "New production file access requires an explicit security review"
        );

        String configSource = Files.readString(CONFIG_MANAGER, StandardCharsets.UTF_8);
        assertTrue(
                configSource.contains("FabricLoader.getInstance().getConfigDir()"),
                "The public configuration path must remain rooted in Fabric's config directory"
        );
        assertTrue(configSource.contains("resolve(SealedClient.MOD_ID)"));

        String config26Source = Files.readString(CONFIG_STORE_26, StandardCharsets.UTF_8);
        assertTrue(config26Source.contains("FabricLoader.getInstance().getConfigDir()"));
        assertTrue(config26Source.contains("resolve(\"sealedclient-26.2.json\")"));
    }

    private static ForbiddenPattern rule(String description, String regex) {
        return new ForbiddenPattern(description, Pattern.compile(regex));
    }

    private static boolean isReviewedBaritoneReflection(
            SourceFile sourceFile,
            ForbiddenPattern forbiddenPattern
    ) {
        return sourceFile.path().equals(REFLECTIVE_BARITONE_ACCESS_26)
                && ("reflection API".equals(forbiddenPattern.description())
                || "manual class loading".equals(forbiddenPattern.description()));
    }

    private static List<SourceFile> productionSources() throws IOException {
        List<SourceFile> result = new ArrayList<>();
        for (Path root : PRODUCTION_SOURCES) {
            try (var paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .map(path -> new SourceFile(path, read(path)))
                        .forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }

    private record ForbiddenPattern(String description, Pattern pattern) {
    }

    private record SourceFile(Path path, String source) {
    }
}
