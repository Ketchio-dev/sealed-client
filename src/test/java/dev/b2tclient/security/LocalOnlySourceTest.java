package dev.b2tclient.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class LocalOnlySourceTest {
    private static final List<Path> PRODUCTION_SOURCES = List.of(
            Path.of("src/main/java"),
            Path.of("common/src/main/java"),
            Path.of("platform-26.2/src/main/java")
    );

    @Test
    void productionSourceContainsNoNetworkOrProcessApis() throws IOException {
        List<String> forbidden = List.of(
                "java." + "net",
                "Http" + "Client",
                "URL" + "Connection",
                "Server" + "Socket",
                "Process" + "Builder",
                "Runtime." + "getRuntime",
                "System." + "getenv",
                "launcher_" + "accounts",
                "access" + "Token",
                "refresh" + "Token",
                "web" + "hook"
        );

        for (Path root : PRODUCTION_SOURCES) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    for (String snippet : forbidden) {
                        if (source.contains(snippet)) {
                            fail("Forbidden API marker '" + snippet + "' found in " + path);
                        }
                    }
                }
            }
        }
    }
}
