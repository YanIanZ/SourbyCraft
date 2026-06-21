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

    /**
     * Resolve {@code <name>.slime} inside {@link #baseDir} and verify the
     * result stays inside the base. Blocks path traversal via crafted
     * world names ({@code "../etc/passwd"}, absolute paths, dot segments).
     */
    private Path resolve(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("Empty world name");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.startsWith(".")) {
            throw new IOException("Refusing unsafe world name: " + name);
        }
        Path root = baseDir.toAbsolutePath().normalize();
        Path file = root.resolve(name + ".slime").normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IOException("Refusing world path outside base dir: " + file);
        }
        return file;
    }

    @Override public byte[] readWorld(String name) throws IOException {
        Path file = resolve(name);
        if (!Files.exists(file)) throw new FileNotFoundException("World not found: " + name);
        return Files.readAllBytes(file);
    }

    @Override public boolean worldExists(String name) throws IOException {
        try {
            return Files.exists(resolve(name));
        } catch (IOException e) {
            return false;
        }
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
        Path file = resolve(name);
        Files.createDirectories(baseDir);
        Files.write(file, data);
    }

    @Override public void deleteWorld(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }
}
