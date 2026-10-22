package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The store-reading logic of the build before AWF-7 (commit 12b1232c: {@code GenerationStore}
 * {@code readPointer}, {@code verify}, {@code read}, {@code readRetained}, {@code recover},
 * {@code isNumber}; {@code AwfWorldStore.decodeIndex} and the writable-open scan that calls
 * {@code ObjectStore.retainOnly}), copied rule for rule so tests can model what that build does
 * to a store this build wrote: which generations it reads, which it skips as damaged, and which
 * objects its open-time collection deletes. Not the new parser: every check here is the old one.
 */
final class HeadBuildReader {

    private static final Pattern BLOB_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private HeadBuildReader() {}

    /** HEAD's {@code isNumber}: non-empty, at most 18 characters, digits only. */
    static boolean isNumber(final String text) {
        if (text.isEmpty() || text.length() > 18) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }

    record Pointer(long generation, String manifestSha) {}

    /** HEAD's {@code readPointer}. */
    static Pointer pointer(final Path generationsRoot) throws IOException {
        final String text;
        try {
            text = Files.readString(generationsRoot.resolve("CURRENT"), StandardCharsets.UTF_8).trim();
        } catch (final NoSuchFileException none) {
            return null;
        }
        final String[] parts = text.split("\t");
        if (parts.length != 2 || !isNumber(parts[0]) || parts[1].length() != 64) {
            throw new IOException("CURRENT is unreadable: " + text);
        }
        return new Pointer(Long.parseLong(parts[0]), parts[1]);
    }

    /** HEAD's {@code verify}: three tab-separated fields per line, nothing else is a manifest line. */
    static Map<String, byte[]> verify(final Path dir, final byte[] manifestBytes) throws IOException {
        final Map<String, byte[]> blobs = new LinkedHashMap<>();
        for (final String line : new String(manifestBytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split("\t");
            if (parts.length != 3 || !BLOB_NAME.matcher(parts[0]).matches() || !isNumber(parts[1])) {
                throw new IOException("malformed manifest line in " + dir + ": " + line);
            }
            final byte[] bytes;
            try {
                bytes = Files.readAllBytes(dir.resolve(parts[0]));
            } catch (final NoSuchFileException missing) {
                throw new IOException("missing blob " + parts[0] + " in " + dir);
            }
            if (bytes.length != Long.parseLong(parts[1]) || !GenerationStore.sha256(bytes).equals(parts[2])) {
                throw new IOException("blob " + parts[0] + " in " + dir + " fails verification");
            }
            blobs.put(parts[0], bytes);
        }
        return blobs;
    }

    /** HEAD's {@code read()}: the current generation's blobs, or null when nothing is committed. */
    static Map<String, byte[]> read(final Path generationsRoot) throws IOException {
        final Pointer pointer = pointer(generationsRoot);
        if (pointer == null) return null;
        final Path dir = generationsRoot.resolve("generations").resolve(Long.toString(pointer.generation()));
        final byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(dir.resolve("manifest"));
        } catch (final NoSuchFileException missing) {
            throw new IOException("generation " + pointer.generation() + " has no manifest");
        }
        if (!GenerationStore.sha256(manifestBytes).equals(pointer.manifestSha())) {
            throw new IOException("generation " + pointer.generation() + " manifest does not match CURRENT");
        }
        return verify(dir, manifestBytes);
    }

    /** HEAD's {@code readRetained()}: every numbered directory up to the current one that verifies; damaged ones skipped. */
    static List<Map<String, byte[]>> readRetained(final Path generationsRoot) throws IOException {
        final Pointer pointer = pointer(generationsRoot);
        if (pointer == null) return List.of();
        final List<Map<String, byte[]>> out = new ArrayList<>();
        final List<Long> numbers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(generationsRoot.resolve("generations"))) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                if (isNumber(name) && Long.parseLong(name) <= pointer.generation()) numbers.add(Long.parseLong(name));
            }
        }
        numbers.sort(Comparator.reverseOrder());
        for (final long number : numbers) {
            final Path dir = generationsRoot.resolve("generations").resolve(Long.toString(number));
            try {
                final byte[] manifest = Files.readAllBytes(dir.resolve("manifest"));
                if (number == pointer.generation() && !GenerationStore.sha256(manifest).equals(pointer.manifestSha())) continue;
                out.add(verify(dir, manifest));
            } catch (final IOException damaged) {
                // Skipped, as HEAD does.
            }
        }
        return out;
    }

    /** HEAD's {@code AwfWorldStore.decodeIndex}: the {@code index} blob, {@code "x z <64 hex | ->"} lines. */
    static SortedMap<ChunkKey, String> decodeIndex(final Map<String, byte[]> blobs) throws IOException {
        final byte[] bytes = blobs.get("index");
        if (bytes == null) throw new IOException("generation has no index");
        final SortedMap<ChunkKey, String> index = new TreeMap<>();
        for (final String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split(" ");
            try {
                if (parts.length != 3 || (parts[2].length() != 64 && !"-".equals(parts[2]))) throw new NumberFormatException(line);
                index.put(new ChunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), parts[2]);
            } catch (final NumberFormatException malformed) {
                throw new IOException("malformed index line: " + line);
            }
        }
        return index;
    }

    private static Set<String> names(final SortedMap<ChunkKey, String> index) {
        final Set<String> names = new HashSet<>(index.values());
        names.remove("-");
        return names;
    }

    /** Which generations HEAD's retained scan accepts, by number, newest first. */
    static List<Long> retainedNumbersRead(final Path generationsRoot) throws IOException {
        final Pointer pointer = pointer(generationsRoot);
        final List<Long> accepted = new ArrayList<>();
        final List<Long> numbers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(generationsRoot.resolve("generations"))) {
            for (final Path dir : stream) {
                final String name = dir.getFileName().toString();
                if (isNumber(name) && Long.parseLong(name) <= pointer.generation()) numbers.add(Long.parseLong(name));
            }
        }
        numbers.sort(Comparator.reverseOrder());
        for (final long number : numbers) {
            final Path dir = generationsRoot.resolve("generations").resolve(Long.toString(number));
            try {
                final byte[] manifest = Files.readAllBytes(dir.resolve("manifest"));
                if (number == pointer.generation() && !GenerationStore.sha256(manifest).equals(pointer.manifestSha())) continue;
                verify(dir, manifest);
                accepted.add(number);
            } catch (final IOException damaged) {
                // skipped
            }
        }
        return accepted;
    }

    /**
     * HEAD's writable {@code AwfWorldStore.open}: {@code GenerationStore.open} recovery (delete
     * {@code *.tmp} and generations above the current), read the current index, collect the names
     * of every retained generation its scan accepts, then {@code ObjectStore.retainOnly}: delete
     * every object in place that none of them names. Returns the current index it would serve.
     *
     * @return the current index, or null for a store with no generation
     */
    static SortedMap<ChunkKey, String> openWritable(final Path storeRoot) throws IOException {
        final Path generationsRoot = storeRoot.resolve("generations-root");
        // GenerationStore.recover()
        Files.deleteIfExists(generationsRoot.resolve("CURRENT.tmp"));
        final Pointer pointer = pointer(generationsRoot);
        final long current = pointer == null ? 0L : pointer.generation();
        if (Files.isDirectory(generationsRoot.resolve("generations"))) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(generationsRoot.resolve("generations"))) {
                for (final Path dir : stream) {
                    final String name = dir.getFileName().toString();
                    if (name.endsWith(".tmp") || (isNumber(name) && Long.parseLong(name) > current)) {
                        try (var walk = Files.walk(dir)) {
                            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                        }
                    }
                }
            }
        }
        // ObjectStore constructor: unfinished writes.
        final Path objects = storeRoot.resolve("objects");
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(objects)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard, "*.tmp")) {
                    for (final Path temp : files) Files.deleteIfExists(temp);
                }
            }
        }
        // AwfWorldStore.open
        final Map<String, byte[]> currentBlobs = read(generationsRoot);
        final SortedMap<ChunkKey, String> index = currentBlobs == null ? new TreeMap<>() : decodeIndex(currentBlobs);
        final List<Set<String>> retainedNames = new ArrayList<>();
        for (final Map<String, byte[]> retained : readRetained(generationsRoot)) {
            retainedNames.add(names(decodeIndex(retained)));
        }
        if (retainedNames.isEmpty() && currentBlobs != null) retainedNames.add(names(index));
        final Set<String> live = new HashSet<>();
        retainedNames.forEach(live::addAll);
        // ObjectStore.retainOnly(live)
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(objects)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (final Path object : files) {
                        final String name = object.getFileName().toString();
                        if (name.endsWith(".tmp") || live.contains(name)) continue;
                        Files.deleteIfExists(object);
                    }
                }
            }
        }
        return currentBlobs == null ? null : index;
    }
}
