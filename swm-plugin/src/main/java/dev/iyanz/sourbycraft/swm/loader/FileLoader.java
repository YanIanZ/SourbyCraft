package dev.iyanz.sourbycraft.swm.loader;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class FileLoader implements SlimeLoader {
    private final Path baseDir;

    public FileLoader(String baseDir) { this(Path.of(baseDir)); }
    public FileLoader(Path baseDir) { this.baseDir = baseDir; }

    @Override public byte[] readWorld(String name) throws IOException {
        Path file = baseDir.resolve(name + ".slime");
        if (!Files.exists(file)) throw new FileNotFoundException("World not found: " + name);
        return Files.readAllBytes(file);
    }

    @Override public boolean worldExists(String name) throws IOException {
        return Files.exists(baseDir.resolve(name + ".slime"));
    }

    @Override public List<String> listWorlds() throws IOException {
        if (!Files.exists(baseDir)) return List.of();
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.toString().endsWith(".slime"))
                .map(p -> p.getFileName().toString().replace(".slime", ""))
                .collect(Collectors.toList());
        }
    }

    @Override public void saveWorld(String name, byte[] data) throws IOException {
        Files.createDirectories(baseDir);
        Files.write(baseDir.resolve(name + ".slime"), data);
    }

    @Override public void deleteWorld(String name) throws IOException {
        Files.deleteIfExists(baseDir.resolve(name + ".slime"));
    }
}
