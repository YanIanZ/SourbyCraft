package dev.iyanz.sourbycraft.startup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The on-disk Aurora Instant Startup cache: deterministic outputs keyed by what they were derived
 * from, and nothing else.
 *
 * <p>Only plain strings are stored. Plugin or class-loader instances, services, worlds, scheduler
 * handles, files, sockets and connections are never cacheable, and a string payload makes that
 * structurally true rather than a matter of discipline.</p>
 *
 * <p>Integrity model, from {@code docs/architecture/aurora-instant-startup.md}: the file header
 * carries the format version and the {@link CacheEnvironment}; a mismatch discards the file. Each
 * entry carries its source SHA-256, an output hash over the payload, and an entry hash over the
 * whole line, so a corrupt or truncated entry is discarded on its own while the rest are kept.
 * Nothing here throws on bad cache content: a cache can make a boot faster, never make it fail.</p>
 *
 * <p>Layout, one record per line:</p>
 * <pre>
 * aurora-startup-cache
 * env &lt;mc&gt;|&lt;sourby abi&gt;|&lt;bridge abi&gt;|&lt;format&gt;|&lt;java major&gt;
 * entry &lt;b64 key&gt; &lt;source sha&gt; &lt;size&gt; &lt;mtime&gt; &lt;output sha&gt; &lt;b64 payload&gt; &lt;entry sha&gt;
 * </pre>
 * <p>Fields in an entry line are tab-separated.</p>
 */
public final class StartupCache {

    static final String MAGIC = "aurora-startup-cache";

    /** Why a load produced the entries it did. */
    public enum LoadStatus {
        /** The file was read; individual entries may still have been discarded. */
        LOADED,
        /** No cache file yet: a cold start. */
        MISSING,
        /** Written by a different Minecraft, ABI, format or Java; every entry discarded. */
        STALE_ENVIRONMENT,
        /** Not a startup cache, or a damaged header; every entry discarded. */
        CORRUPT,
        /** The file exists but could not be read. */
        IO_ERROR
    }

    /** One cached output and the source it was derived from. */
    public record Entry(String key, SourceFingerprint source, String payload) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * The result of reading the cache.
     *
     * @param status what happened to the file as a whole
     * @param entries entries that passed every integrity check, by key
     * @param discardedEntries entry lines rejected individually
     */
    public record Loaded(LoadStatus status, Map<String, Entry> entries, int discardedEntries) {
        public Loaded {
            entries = Map.copyOf(entries);
        }

        /** Whether anything was thrown away for being corrupt or stale — the WARN-once case. */
        public boolean discardedAnything() {
            return this.discardedEntries > 0
                || this.status == LoadStatus.STALE_ENVIRONMENT
                || this.status == LoadStatus.CORRUPT
                || this.status == LoadStatus.IO_ERROR;
        }
    }

    private StartupCache() {}

    /** Reads the cache for this environment. Never throws on cache content. */
    public static Loaded load(final Path file, final CacheEnvironment environment) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final NoSuchFileException missing) {
            return new Loaded(LoadStatus.MISSING, Map.of(), 0);
        } catch (final IOException | RuntimeException unreadable) {
            // Malformed UTF-8 surfaces as an IOException subtype; either way the file is unusable.
            return new Loaded(LoadStatus.IO_ERROR, Map.of(), 0);
        }
        if (lines.size() < 2 || !MAGIC.equals(lines.get(0)) || !lines.get(1).startsWith("env ")) {
            return new Loaded(LoadStatus.CORRUPT, Map.of(), 0);
        }
        if (!environment.encode().equals(lines.get(1).substring(4))) {
            return new Loaded(LoadStatus.STALE_ENVIRONMENT, Map.of(), 0);
        }
        final Map<String, Entry> entries = new LinkedHashMap<>();
        int discarded = 0;
        for (int i = 2; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isEmpty()) continue;
            final Entry entry = decode(line);
            if (entry == null || entries.containsKey(entry.key())) {
                discarded++;
                continue;
            }
            entries.put(entry.key(), entry);
        }
        return new Loaded(LoadStatus.LOADED, entries, discarded);
    }

    /**
     * Replaces the cache atomically: written to a sibling temporary file, forced to disk, then
     * moved over the old one, so a crash mid-write leaves the previous cache intact.
     */
    public static void write(final Path file, final CacheEnvironment environment,
                             final Collection<Entry> entries) throws IOException {
        final Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                writer.write(MAGIC);
                writer.write('\n');
                writer.write("env " + environment.encode());
                writer.write('\n');
                for (final Entry entry : entries) {
                    writer.write(encode(entry));
                    writer.write('\n');
                }
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String encode(final Entry entry) {
        final Base64.Encoder b64 = Base64.getEncoder();
        final byte[] payload = entry.payload().getBytes(StandardCharsets.UTF_8);
        final String body = String.join("\t",
            b64.encodeToString(entry.key().getBytes(StandardCharsets.UTF_8)),
            entry.source().sha256(),
            Long.toString(entry.source().size()),
            Long.toString(entry.source().modifiedMillis()),
            SourceFingerprint.sha256(payload),
            b64.encodeToString(payload));
        return "entry " + body + "\t" + SourceFingerprint.sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    /** The entry on this line, or {@code null} if any integrity check fails. */
    static Entry decode(final String line) {
        if (!line.startsWith("entry ")) return null;
        final String rest = line.substring(6);
        final int lastTab = rest.lastIndexOf('\t');
        if (lastTab < 0) return null;
        final String body = rest.substring(0, lastTab);
        if (!SourceFingerprint.sha256(body.getBytes(StandardCharsets.UTF_8)).equals(rest.substring(lastTab + 1))) {
            return null;
        }
        final String[] fields = body.split("\t", -1);
        if (fields.length != 6) return null;
        try {
            final Base64.Decoder b64 = Base64.getDecoder();
            final String key = new String(b64.decode(fields[0]), StandardCharsets.UTF_8);
            final byte[] payload = b64.decode(fields[5]);
            if (!SourceFingerprint.sha256(payload).equals(fields[4])) return null;
            final SourceFingerprint source = new SourceFingerprint(
                fields[1], Long.parseLong(fields[2]), Long.parseLong(fields[3]));
            return new Entry(key, source, new String(payload, StandardCharsets.UTF_8));
        } catch (final IllegalArgumentException malformed) {
            // Bad base64, bad number or bad hash length: this entry only.
            return null;
        }
    }
}
