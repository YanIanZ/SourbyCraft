package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Deterministic staged-rollout bucket for one server (signed release manifest, {@link ReleaseManifest}).
 *
 * <p>The server id is 128 random bits written once to {@code auto_update/}{@value #ID_FILE}. It is
 * read locally only: nothing logs it, sends it, or derives it from the host, the IP, the world or a
 * player. Deleting the file gives the server a new bucket.
 *
 * <p>{@code bucket = SHA-256("sourbycraft-rollout:v1:" + id + ":" + tag)} — the first 8 bytes as an
 * unsigned number, modulo 100 — so it is in 0..99, stable for a server and a release across
 * restarts, and independent between releases (the same servers are not always the first wave).
 * A server takes a release iff {@code bucket < rollout_percent}: 0 reaches no server, 100 every
 * server, and raising the percentage for one tag only ever adds servers.
 *
 * <p>A server whose id cannot be read or created (read-only directory) gets {@link #FALLBACK_BUCKET}
 * 99, i.e. only full (100 %) rollouts reach it: the conservative side of an unknown position.
 */
public final class RolloutBucket {

    static final String ID_FILE = "rollout.id";
    static final int FALLBACK_BUCKET = 99;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RolloutBucket() {}

    /** Bucket 0..99 of {@code serverId} for {@code tag}; {@link #FALLBACK_BUCKET} when the id is unknown. */
    public static int bucket(@Nullable String serverId, String tag) {
        if (serverId == null) return FALLBACK_BUCKET;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("sourbycraft-rollout:v1:" + serverId + ":" + tag).getBytes(StandardCharsets.UTF_8));
            return (int) Long.remainderUnsigned(ByteBuffer.wrap(digest, 0, 8).getLong(), 100);
        } catch (NoSuchAlgorithmException e) {
            return FALLBACK_BUCKET; // every JDK has SHA-256; never reached in practice
        }
    }

    /** True when this server is inside {@code rolloutPercent} for {@code tag}. */
    public static boolean included(@Nullable String serverId, String tag, int rolloutPercent) {
        return bucket(serverId, tag) < rolloutPercent;
    }

    private static final Object ID_LOCK = new Object();

    /**
     * This server's id from {@code autoUpdateDir/}{@value #ID_FILE}, created on first use. Returns
     * {@code null} (fallback bucket) when the file is unreadable or malformed and cannot be replaced.
     *
     * <p>Race-free first use: callers in this JVM (a scheduled check and {@code /update}) are
     * serialised; the candidate id is written to a unique temporary file and published with a hard
     * link, which fails if the id file already exists, so a concurrent or earlier writer always wins
     * and every caller returns the id that is actually on disk, re-read after publication. Where the
     * file system has no hard links, a move without replace is used (the lock still serialises callers).
     */
    public static @Nullable String serverId(Path autoUpdateDir) {
        synchronized (ID_LOCK) {
            Path file = autoUpdateDir.resolve(ID_FILE);
            try {
                String existing = readId(file);
                if (existing != null) return existing;
                if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    SourbyLogger.warn("auto-updater: " + file + " is malformed; writing a new rollout id.");
                    Files.delete(file);
                }
                byte[] raw = new byte[16];
                RANDOM.nextBytes(raw);
                Files.createDirectories(autoUpdateDir);
                Path temp = Files.createTempFile(autoUpdateDir, ID_FILE + ".", ".part");
                try {
                    Files.writeString(temp, HexFormat.of().formatHex(raw) + "\n", StandardCharsets.US_ASCII);
                    try {
                        Files.createLink(file, temp);
                    } catch (java.nio.file.FileAlreadyExistsException lost) {
                        // another writer published first: its id wins
                    } catch (UnsupportedOperationException | IOException noLinks) {
                        if (!Files.exists(file)) {
                            try {
                                Files.move(temp, file);
                            } catch (java.nio.file.FileAlreadyExistsException lost) {
                                // another writer published first
                            }
                        }
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                String persisted = readId(file);
                if (persisted == null) throw new IOException(file + " did not hold a valid id after writing it");
                return persisted;
            } catch (IOException | RuntimeException e) {
                SourbyLogger.warn("auto-updater: no rollout id (" + e.getMessage() + "); only 100% rollouts will reach this server.");
                return null;
            }
        }
    }

    private static @Nullable String readId(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        String id = Files.readString(file, StandardCharsets.ISO_8859_1).trim();
        return ID.matcher(id).matches() ? id : null;
    }
}
