package dev.b2tclient.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureBoundaryTest {
    @Test
    void commonHasNoMinecraftFabricOrLwjglImports() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("net.minecraft"), file + " imports Minecraft");
                assertFalse(source.contains("net.fabricmc"), file + " imports Fabric");
                assertFalse(source.contains("org.lwjgl"), file + " imports LWJGL");
            }
        }
    }
}
