package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * This server's identity in Redis: a stable node id and a per-boot incarnation.
 *
 * <p><b>Node id.</b> {@code node-} and 12 hex digits of a SHA-256 over a random UUID kept in
 * {@code awf/node-id} (created on first use; any non-blank text an operator writes there is used as
 * is), the host name ({@code HOSTNAME} or {@code /etc/hostname}; no DNS lookup, which can block for
 * seconds) and the server directory. It survives restarts and names the server in
 * {@code /awf redis}, routing records and session claims. It is not proof of being one process: a
 * copied server directory on a host with the same name and path (two containers started from one
 * volume snapshot, say) has the same id.</p>
 *
 * <p><b>Incarnation.</b> A random UUID per JVM, never reused. Every AWF Redis lease token and every
 * session claim carries it, and only the incarnation that holds a lease or claim may extend or
 * replace it while it is live. Any other process waits for it to expire, also one with the same node
 * id.</p>
 *
 * <p><b>Restart after a crash.</b> The one exception is this server's own previous incarnation once
 * it is proven stopped. Each process holds an exclusive lock on {@code awf/node.lock} for its whole
 * life and, while holding it, records its incarnation in {@code awf/incarnation}, together with a
 * fingerprint of where it holds the lock: the lock file's device and inode and, on Linux, the
 * kernel's boot id. A process that gets the lock reads the incarnation recorded there as its
 * predecessor only when the fingerprint is its own: that process held this very lock file on this
 * boot and no longer holds it, so it has exited, and its leases and claims may be taken over at once.
 * A copy of the directory (the files copied, a cloned volume, the same files on another host) or a
 * reboot gives another fingerprint, so a copy taken while the server ran never takes the running
 * server's leases. A process that cannot get the lock (a live process uses this directory), cannot
 * use the files or finds another fingerprint has no predecessor and waits for expiry like any other
 * server; so does any process on a platform without file keys. The lock is an operating-system file
 * lock: it proves the predecessor stopped for processes on one host, and across hosts only on a
 * network file system that implements such locks. A clone of a whole running machine (memory
 * included) is not told apart.</p>
 *
 * <p>The files are used only once the boot sequence has said the working directory is a server
 * directory ({@link #useServerDirectory()}, the fabric's boot stage). {@link #current()} then
 * resolves them on its first call (still a startup path: the fabric's start, the AWF engine's Redis
 * backend or MultiProfile) and never touches them again. Without that call (unit tests, tools) no
 * file under {@code awf/} is read or written: the node id is random per JVM and there is no
 * predecessor, so every lease this process did not create waits for expiry.</p>
 */
public final class NodeIdentity {

    /** Relative to the server directory, beside {@code awf/exports} and {@code awf/profiles}. */
    static final Path DIRECTORY = Path.of("awf");
    private static final String NODE_ID_FILE = "node-id";
    private static final String INCARNATION_FILE = "incarnation";
    private static final String LOCK_FILE = "node.lock";

    private static final String PROCESS_INCARNATION = UUID.randomUUID().toString();

    /** Set at boot by {@link #useServerDirectory()}; guarded by the class lock, like {@code current}. */
    private static boolean serverDirectory;
    private static NodeIdentity current;
    /**
     * The lock and its channel, referenced for the life of the process: an unreachable channel is
     * closed by its cleaner, and closing it would release the lock.
     */
    private static FileChannel lockChannel;
    private static FileLock lock;

    private final String nodeId;
    private final String leaseOwner;
    private final String incarnation;
    private final String previousIncarnation;

    private NodeIdentity(final String nodeId, final String leaseOwner, final String incarnation,
                         final String previousIncarnation) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation");
        this.previousIncarnation = previousIncarnation;
    }

    /**
     * Says the working directory is this server's directory, so {@link #current()} may keep the
     * identity under {@code awf/}. Called once at boot, before anything asks for the identity; reads
     * and writes nothing itself. When something already asked, the unpersisted identity stays for
     * this run, and that is logged.
     */
    public static synchronized void useServerDirectory() {
        if (current != null && !serverDirectory) {
            SourbyLogger.warn("Aurora node identity was needed before the boot stage that allows its files under "
                + DIRECTORY + "; this run keeps the unpersisted node id " + current.nodeId + " and takes over no"
                + " earlier run's Redis leases or session claims (they expire first)");
        }
        serverDirectory = true;
    }

    /**
     * This process's identity: resolved from the files under {@code awf/} on the first call after
     * {@link #useServerDirectory()}, or, before or without it, a random node id with no predecessor
     * that touches no file under {@code awf/}. Fixed for the life of the process once returned.
     */
    public static synchronized NodeIdentity current() {
        if (current == null) current = serverDirectory ? resolve(DIRECTORY) : of(UUID.randomUUID().toString(), null);
        return current;
    }

    /**
     * This JVM's incarnation, without touching any file. {@link #current()} carries the same value;
     * code given an explicit node id (tests, {@link RedisFabric#start(FabricSettings,
     * dev.iyanz.sourbycraft.awf.redis.RedisSettings, String)}) uses this one, with no predecessor.
     */
    public static String processIncarnation() {
        return PROCESS_INCARNATION;
    }

    /** {@code node-} and 12 hex digits; at most 64 characters, as routing records require. */
    public String nodeId() {
        return this.nodeId;
    }

    /** The node part of an AWF Redis lease token: 16 hex digits of the same hash as {@link #nodeId()}. */
    public String leaseOwner() {
        return this.leaseOwner;
    }

    public String incarnation() {
        return this.incarnation;
    }

    /** This server's previous incarnation when it is proven stopped (see the class comment), else {@code null}. */
    public String previousIncarnation() {
        return this.previousIncarnation;
    }

    private static NodeIdentity resolve(final Path directory) {
        String persisted = null;
        String previous = null;
        String note;
        try {
            Files.createDirectories(directory);
            if (lock(directory)) {
                persisted = readOrCreateNodeId(directory.resolve(NODE_ID_FILE));
                final String here = fingerprint(directory.resolve(LOCK_FILE));
                previous = readIncarnation(directory.resolve(INCARNATION_FILE), here);
                note = previous == null ? "no previous run proven stopped in this directory" : "previous run " + previous;
                try {
                    writeAtomically(directory.resolve(INCARNATION_FILE),
                        here == null ? PROCESS_INCARNATION : PROCESS_INCARNATION + "\n" + here);
                } catch (final IOException failed) {
                    SourbyLogger.warn("Aurora node identity: could not record this run's incarnation in "
                        + directory.resolve(INCARNATION_FILE) + " (" + failed.getMessage() + "); after a crash the"
                        + " next start waits for this run's Redis leases and session claims to expire");
                }
            } else {
                // A live process holds the lock: it owns the files, and none of its leases are ours.
                SourbyLogger.warn("Aurora node identity: another running process holds " + directory.resolve(LOCK_FILE)
                    + ", so it uses this server directory too. This process takes over no earlier run's Redis"
                    + " leases or session claims and waits for them to expire; two servers sharing one directory"
                    + " is not supported");
                persisted = readNodeId(directory.resolve(NODE_ID_FILE));
                note = "no previous run proven stopped";
            }
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("Aurora node identity: could not use " + directory + " (" + failed.getMessage()
                + "); after a crash the next start waits for this run's Redis leases and session claims to expire");
            note = "files unavailable";
            previous = null;
        }
        if (persisted == null) {
            persisted = UUID.randomUUID().toString();
            note += ", node id not persisted: it changes at the next start";
        }
        final NodeIdentity identity = of(persisted, previous);
        SourbyLogger.info("Aurora node identity: " + identity.nodeId + ", incarnation " + identity.incarnation
            + " (" + note + ")");
        return identity;
    }

    /** The identity for a persisted (or random) id: hashed with the host name and the server directory. */
    private static NodeIdentity of(final String persisted, final String previous) {
        final byte[] digest = sha256(persisted + "|" + hostName() + "|" + Path.of("").toAbsolutePath().normalize());
        return new NodeIdentity("node-" + HexFormat.of().formatHex(digest, 0, 6), HexFormat.of().formatHex(digest, 0, 8),
            PROCESS_INCARNATION, previous);
    }

    /** Takes the process-lifetime lock; {@code false} when another process holds it. */
    private static boolean lock(final Path directory) throws IOException {
        final FileChannel channel = FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
        final FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (final IOException | OverlappingFileLockException failed) {
            channel.close();
            throw failed instanceof IOException io ? io : new IOException("the lock is already held in this JVM");
        }
        if (acquired == null) {
            channel.close();
            return false;
        }
        lockChannel = channel;
        lock = acquired;
        return true;
    }

    private static String readOrCreateNodeId(final Path file) throws IOException {
        final String existing = readNodeId(file);
        if (existing != null) return existing;
        final String created = UUID.randomUUID().toString();
        writeAtomically(file, created);
        return created;
    }

    /** The persisted id, or {@code null} when the file is absent or blank. */
    private static String readNodeId(final Path file) throws IOException {
        if (!Files.exists(file)) return null;
        final String text = Files.readString(file, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The recorded incarnation, or {@code null} when absent, unreadable, not one this class wrote, or
     * recorded under another fingerprint than {@code fingerprint} (or none can be taken here).
     */
    private static String readIncarnation(final Path file, final String fingerprint) {
        if (fingerprint == null) return null;
        try {
            if (!Files.exists(file)) return null;
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !fingerprint.equals(lines.get(1).trim())) return null;
            return UUID.fromString(lines.get(0).trim()).toString();
        } catch (final IOException | IllegalArgumentException unusable) {
            return null;
        }
    }

    /**
     * Where this process holds the lock: the lock file's key (device and inode) and the kernel's boot
     * id ({@code /proc/sys/kernel/random/boot_id}, empty where there is none). A copied lock file has
     * another inode, a cloned volume another device on the same host, and another host or a reboot
     * another boot id. {@code null} when the platform has no file key: then no predecessor is accepted.
     */
    private static String fingerprint(final Path lockFile) {
        try {
            final Object key = Files.readAttributes(lockFile, BasicFileAttributes.class).fileKey();
            if (key == null) return null;
            String boot;
            try {
                boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id"), StandardCharsets.US_ASCII).trim();
            } catch (final IOException | RuntimeException none) {
                boot = "";
            }
            return key + " boot=" + boot;
        } catch (final IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static void writeAtomically(final Path file, final String text) throws IOException {
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String hostName() {
        final String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) return host;
        try {
            return Files.readString(Path.of("/etc/hostname")).trim();
        } catch (final IOException | RuntimeException none) {
            return "";
        }
    }

    private static byte[] sha256(final String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
