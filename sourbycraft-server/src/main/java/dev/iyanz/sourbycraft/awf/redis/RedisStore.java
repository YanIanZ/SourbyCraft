package dev.iyanz.sourbycraft.awf.redis;

import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import dev.iyanz.sourbycraft.execution.ExecutionLane;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One AWF store in Redis. Four keys share the store's hash tag, so they live in one cluster slot:
 * <ul>
 *   <li>{@code <prefix>{<id>}:chunks} — hash, field {@code x,z}, value the chunk's bytes;</li>
 *   <li>{@code <prefix>{<id>}:deleted} — set of {@code x,z} the world deleted (they shadow its base);</li>
 *   <li>{@code <prefix>{<id>}:gen} — the committed generation, a counter;</li>
 *   <li>{@code <prefix>{<id>}:lock} — the lease of the one server allowed to write.</li>
 * </ul>
 *
 * <p><b>Commits are atomic.</b> A commit is one Lua script: it checks the lease, applies every
 * change and increments the generation. A lease that expired while nobody else took it — this
 * server stalled past it — is taken back, but only if the generation is still the one this store
 * last saw, i.e. no other server opened the world and committed in between. Redis runs a script without interleaving anything else, and
 * a {@code #!lua} script is refused up front when Redis is out of memory instead of failing
 * half-way. If the connection drops before the reply, the commit may or may not have been applied;
 * {@code AwfWorld} keeps the chunks dirty and commits them again, which writes the same values.</p>
 *
 * <p><b>Opening is lazy.</b> Only the chunk coordinates are read when a store opens
 * ({@code HSCAN ... NOVALUES}); a chunk's bytes are fetched the first time the engine asks for it.</p>
 *
 * <p><b>Durability</b> is Redis's: with {@code wait-for-aof} a commit returns only after Redis has
 * written it to its append-only file; otherwise it is acknowledged from memory. Redis keeps no
 * older generations, so {@code retained-generations} does not apply.</p>
 */
final class RedisStore implements AwfStore {

    /**
     * KEYS: chunks, deleted, gen, lock. ARGV: token, changed, removed and deleted counts, the
     * generation this store last saw, the lease in ms, then the fields.
     */
    static final String COMMIT_SCRIPT = """
        #!lua
        local holder = redis.call('GET', KEYS[4])
        if holder ~= ARGV[1] then
          if holder or (redis.call('GET', KEYS[3]) or '0') ~= ARGV[5] then
            return redis.error_reply('AWFLEASE this server no longer holds the world')
          end
          redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[6])
        end
        local i = 7
        for n = 1, tonumber(ARGV[2]) do
          redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
          redis.call('SREM', KEYS[2], ARGV[i])
          i = i + 2
        end
        for n = 1, tonumber(ARGV[3]) do
          redis.call('HDEL', KEYS[1], ARGV[i])
          redis.call('SREM', KEYS[2], ARGV[i])
          i = i + 1
        end
        for n = 1, tonumber(ARGV[4]) do
          redis.call('HDEL', KEYS[1], ARGV[i])
          redis.call('SADD', KEYS[2], ARGV[i])
          i = i + 1
        end
        return redis.call('INCR', KEYS[3])
        """;

    private final RedisBackend backend;
    private final String id;
    private final WorldRole role;
    private final String token;
    final String chunksKey;
    final String deletedKey;
    final String genKey;
    final String lockKey;
    private volatile Set<ChunkKey> live;
    private volatile Set<ChunkKey> tombstones;
    private volatile boolean closed;
    /** The generation this store last read or committed; see the commit script. */
    private volatile long knownGeneration;

    RedisStore(final RedisBackend backend, final String id, final WorldRole role, final String token) {
        this.backend = backend;
        this.id = id;
        this.role = role;
        this.token = token;
        final String base = backend.settings().keyPrefix() + "{" + id + "}:";
        this.chunksKey = base + "chunks";
        this.deletedKey = base + "deleted";
        this.genKey = base + "gen";
        this.lockKey = base + "lock";
    }

    String id() {
        return this.id;
    }

    /** The lease token, or {@code null} for a store opened read-only. */
    String token() {
        return this.token;
    }

    long knownGeneration() {
        return this.knownGeneration;
    }

    /** Reads the chunk coordinates; called once by the backend after the lease is held. */
    void loadIndex() throws IOException {
        this.knownGeneration = generation();
        final Set<ChunkKey> chunks = new HashSet<>();
        for (final byte[] field : this.backend.hashFields(this.chunksKey)) chunks.add(parse(field));
        final Set<ChunkKey> deleted = new HashSet<>();
        final Object members = this.backend.client().call("SMEMBERS", this.deletedKey);
        if (members instanceof List<?> list) {
            for (final Object member : list) deleted.add(parse((byte[]) member));
        }
        this.live = Collections.unmodifiableSet(chunks);
        this.tombstones = Collections.unmodifiableSet(deleted);
    }

    static String field(final ChunkKey key) {
        return key.x() + "," + key.z();
    }

    static ChunkKey parse(final byte[] field) throws IOException {
        final String text = new String(field, java.nio.charset.StandardCharsets.US_ASCII);
        final int comma = text.indexOf(',');
        try {
            return new ChunkKey(Integer.parseInt(text.substring(0, comma)), Integer.parseInt(text.substring(comma + 1)));
        } catch (final RuntimeException malformed) {
            throw new IOException("malformed AWF chunk field '" + text + "' in Redis");
        }
    }

    @Override
    public boolean has(final ChunkKey key) {
        return this.live.contains(key) || this.tombstones.contains(key);
    }

    @Override
    public Set<ChunkKey> deleted() {
        return new TreeSet<>(this.tombstones);
    }

    @Override
    public Set<ChunkKey> keys() {
        return new TreeSet<>(this.live);
    }

    @Override
    public long generation() throws IOException {
        final String value = RedisClient.text(this.backend.client().call("GET", this.genKey));
        return value == null ? 0L : Long.parseLong(value);
    }

    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        if (!this.live.contains(key)) return Optional.empty();
        final Object bytes = this.backend.client().call("HGET", this.chunksKey, field(key));
        if (!(bytes instanceof byte[] raw)) {
            throw new IOException("AWF store " + this.id + " lists chunk " + key + " but Redis has no bytes for it");
        }
        return Optional.of(raw);
    }

    @Override
    public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                            final Set<ChunkKey> deleted, final PersistenceMode mode)
        throws IOException {
        if (ExecutionLane.of(Thread.currentThread().getName()) == ExecutionLane.REGION_TICK) {
            throw new IllegalStateException("blocking world storage I/O on a region thread; use commitAsync");
        }
        if (mode == PersistenceMode.READ_ONLY || !this.role.acceptsCommits() || this.token == null) {
            throw new IllegalStateException("a " + this.role + " world in " + mode + " mode does not accept commits");
        }
        if (this.closed) throw new IOException("AWF store " + this.id + " is closed");
        final List<Object> command = new ArrayList<>(8 + changed.size() * 2 + removed.size() + deleted.size());
        command.add("EVAL");
        command.add(COMMIT_SCRIPT);
        command.add("4");
        command.add(this.chunksKey);
        command.add(this.deletedKey);
        command.add(this.genKey);
        command.add(this.lockKey);
        command.add(this.token);
        command.add(Integer.toString(changed.size()));
        command.add(Integer.toString(removed.size()));
        command.add(Integer.toString(deleted.size()));
        command.add(Long.toString(this.knownGeneration));
        command.add(Long.toString(this.backend.settings().leaseSeconds() * 1000L));
        long bytes = 0;
        for (final Map.Entry<ChunkKey, byte[]> chunk : changed.entrySet()) {
            command.add(field(chunk.getKey()));
            command.add(chunk.getValue());
            bytes += chunk.getValue().length;
        }
        for (final ChunkKey key : removed) command.add(field(key));
        for (final ChunkKey key : deleted) command.add(field(key));
        final long generation = (Long) this.backend.client().call(command.toArray());
        this.knownGeneration = generation;
        this.backend.awaitDurable();

        final Set<ChunkKey> nextLive = new HashSet<>(this.live);
        final Set<ChunkKey> nextDeleted = new HashSet<>(this.tombstones);
        nextLive.addAll(changed.keySet());
        nextDeleted.removeAll(changed.keySet());
        nextLive.removeAll(removed);
        nextDeleted.removeAll(removed);
        nextLive.removeAll(deleted);
        nextDeleted.addAll(deleted);
        this.live = Collections.unmodifiableSet(nextLive);
        this.tombstones = Collections.unmodifiableSet(nextDeleted);
        return new CommitResult(generation, nextLive.size() + nextDeleted.size(), changed.size(), bytes, 0);
    }

    @Override
    public void close() throws IOException {
        if (this.closed) return;
        this.closed = true;
        this.backend.release(this);
    }
}
