package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Exclusive, epoch-versioned ownership of a profile session in Redis.
 *
 * <p>A session is claimed by one node at a time. A second node is refused while the first claim is
 * alive ({@code duplicate}); it may only take over after the claim expired, and that takeover
 * increments the epoch. Expiry is never treated as proof that the old node stopped writing: the
 * epoch is carried into every durable write, and a write path checks {@link #valid} (session, owner,
 * epoch and remaining TTL) before it commits. That check and a separate write are two steps, so a
 * store that must never accept a stale writer has to compare the epoch in the same atomic step as
 * the write.</p>
 *
 * <p><b>Keys</b> (under the fabric namespace):</p>
 * <ul>
 *   <li>{@code session:<profile>} — hash {@code session, node, incarnation, owner, epoch, expiresAt,
 *       state}, with a TTL equal to the claim's; {@code expiresAt} is Redis time in ms;</li>
 *   <li>{@code session-epoch:<profile>} — the epoch counter; never expires and is never deleted, so a
 *       released or lapsed session never restarts at an old epoch.</li>
 * </ul>
 *
 * <p><b>Who holds a claim</b> is three values: the node id (stable across restarts, names the server
 * for routing), the incarnation ({@link NodeIdentity}: one per process, fixed for this instance) and
 * the owner token (one per claim). The {@code session} field is {@code node/incarnation/owner}, and
 * refresh, validation and release compare it, so a process with the same node id but another
 * incarnation (a restarted server, or a second server that shares the id) can never extend, validate
 * or release a claim it did not make, even with the right owner token and epoch.</p>
 *
 * <p><b>Scripts</b> (each atomic in Redis):</p>
 * <ul>
 *   <li>{@code CLAIM}: no live claim, or an expired one — a new claim with the next epoch. A live
 *       claim with the same {@code session} — refreshed, same epoch. A live claim by this node and
 *       this incarnation with another owner token (a rejoin after a switch) — the token is replaced,
 *       same epoch, since it is the same process. A live claim by this node's proven-stopped previous
 *       incarnation ({@link NodeIdentity#previousIncarnation()}) — taken over with the next epoch. Any
 *       other live claim, also by another process with this node id — refused ({@code duplicate}).</li>
 *   <li>{@code RECLAIM}: operator recovery; takes the session over, with the next epoch, only when
 *       the stored epoch is exactly the expected one.</li>
 *   <li>{@code REFRESH} / {@code RELEASE}: extend or delete the claim when session, owner and epoch
 *       all match.</li>
 *   <li>{@code VALID}: the same match, and the claim has not expired by Redis time.</li>
 * </ul>
 *
 * <p>Clock source: Redis {@code TIME}, so nodes with skewed clocks agree on expiry.</p>
 */
public final class SessionFabric {

    /** KEYS: session, epoch. ARGV: session, node, owner, ttl ms, incarnation, previous incarnation or ''. */
    private static final String CLAIM = """
        local key = KEYS[1]
        local epochKey = KEYS[2]
        local session = ARGV[1]
        local node = ARGV[2]
        local owner = ARGV[3]
        local ttl = tonumber(ARGV[4])
        local incarnation = ARGV[5]
        local previous = ARGV[6]
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        local cur = redis.call('HMGET', key, 'session', 'node', 'owner', 'epoch', 'expiresAt', 'incarnation')
        local epoch = tonumber(cur[4] or '0') or 0
        local expires = tonumber(cur[5] or '0') or 0
        if expires > now then
          if cur[1] == session then
            redis.call('HSET', key, 'expiresAt', now + ttl)
            redis.call('PEXPIRE', key, ttl)
            return {1, epoch, cur[3] or owner, cur[2] or node}
          end
          if cur[2] == node and cur[6] == incarnation then
            -- Same process, new owner token (for example a join after a switch): not a duplicate.
            redis.call('HSET', key, 'session', session, 'owner', owner, 'expiresAt', now + ttl)
            redis.call('PEXPIRE', key, ttl)
            return {1, epoch, owner, node}
          end
          if previous == '' or cur[2] ~= node or cur[6] ~= previous then
            return {0, epoch, cur[3] or '', cur[2] or ''}
          end
          -- This node's previous incarnation, proven stopped: taken over below with the next epoch.
        end
        epoch = redis.call('INCR', epochKey)
        redis.call('HSET', key, 'session', session, 'node', node, 'owner', owner, 'epoch', epoch,
          'expiresAt', now + ttl, 'state', 'ACTIVE', 'incarnation', incarnation)
        redis.call('PEXPIRE', key, ttl)
        return {1, epoch, owner, node}
        """;

    /** KEYS: session, epoch. ARGV: expected epoch, session, node, owner, ttl ms, incarnation. */
    private static final String RECLAIM = """
        local key = KEYS[1]
        local epochKey = KEYS[2]
        local expected = tonumber(ARGV[1])
        local session = ARGV[2]
        local node = ARGV[3]
        local owner = ARGV[4]
        local ttl = tonumber(ARGV[5])
        local cur = redis.call('HMGET', key, 'epoch', 'owner', 'node')
        local epoch = tonumber(cur[1] or '0') or 0
        if epoch ~= expected then
          return {0, epoch, cur[2] or '', cur[3] or ''}
        end
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        epoch = redis.call('INCR', epochKey)
        redis.call('HSET', key, 'session', session, 'node', node, 'owner', owner, 'epoch', epoch,
          'expiresAt', now + ttl, 'state', 'ACTIVE', 'incarnation', ARGV[6])
        redis.call('PEXPIRE', key, ttl)
        return {1, epoch, owner, node}
        """;

    private static final String REFRESH = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'owner', 'epoch')
        if cur[1] == ARGV[1] and cur[2] == ARGV[2] and tonumber(cur[3] or '0') == tonumber(ARGV[3]) then
          local ttl = tonumber(ARGV[4])
          local now = redis.call('TIME')
          now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
          redis.call('HSET', key, 'expiresAt', now + ttl)
          redis.call('PEXPIRE', key, ttl)
          return 1
        end
        return 0
        """;

    private static final String VALID = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'owner', 'epoch', 'expiresAt')
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        if cur[1] == ARGV[1] and cur[2] == ARGV[2] and tonumber(cur[3] or '0') == tonumber(ARGV[3])
           and tonumber(cur[4] or '0') > now then
          return 1
        end
        return 0
        """;

    private static final String RELEASE = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'owner', 'epoch')
        if cur[1] == ARGV[1] and cur[2] == ARGV[2] and tonumber(cur[3] or '0') == tonumber(ARGV[3]) then
          return redis.call('DEL', key)
        end
        return 0
        """;

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    /** Handed to the scripts in milliseconds; PEXPIRE and the Redis-TIME comparison are both ms. */
    private final int ttlSeconds;
    /** This process's incarnation, written into every claim this instance makes. */
    private final String incarnation;
    /** This node's proven-stopped previous incarnation, or {@code ""}; see {@link NodeIdentity}. */
    private final String previousIncarnation;

    /** Claims as this JVM's incarnation, with no predecessor to take over. */
    SessionFabric(final RedisClient client, final String prefix, final FabricMetrics metrics, final int ttlSeconds) {
        this(client, prefix, metrics, ttlSeconds, NodeIdentity.processIncarnation(), null);
    }

    SessionFabric(final RedisClient client, final String prefix, final FabricMetrics metrics, final int ttlSeconds,
                  final String incarnation, final String previousIncarnation) {
        this.client = client;
        this.prefix = prefix;
        this.metrics = metrics;
        this.ttlSeconds = ttlSeconds;
        this.incarnation = java.util.Objects.requireNonNull(incarnation, "incarnation");
        this.previousIncarnation = previousIncarnation == null ? "" : previousIncarnation;
    }

    public String key(final UUID profileId) {
        return this.prefix + "session:" + profileId;
    }

    /** The incarnation this instance claims as. */
    public String incarnation() {
        return this.incarnation;
    }

    /**
     * The session field: one claim of one process ({@code node/incarnation/owner}); the profile id
     * alone is shared by all nodes, and the node id alone by every process that shares it.
     */
    String session(final String node, final UUID owner) {
        return node + "/" + this.incarnation + "/" + owner;
    }

    /** Never expires: the epoch must not restart when a session record's TTL lapses. */
    String epochKey(final UUID profileId) {
        return this.prefix + "session-epoch:" + profileId;
    }

    /**
     * Claims the session for {@code node} or refreshes its own claim. A live claim by another node,
     * or by another incarnation of this node that is not its proven-stopped predecessor, is refused
     * as {@code duplicate}; see the class comment for every case.
     */
    public ClaimResult claim(final UUID profileId, final String node, final UUID owner) throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.eval(CLAIM, 2, key(profileId), epochKey(profileId),
            session(node, owner), node, owner.toString(), Long.toString(this.ttlSeconds * 1000L), this.incarnation,
            this.previousIncarnation));
        if (reply.size() < 4) throw new IOException("session claim returned a malformed reply");
        final boolean granted = RedisReplies.number(reply.get(0), 0L) == 1L;
        final long epoch = RedisReplies.number(reply.get(1), 0L);
        final String holderOwner = RedisReplies.text(reply.get(2));
        final String holderNode = RedisReplies.text(reply.get(3));
        if (!granted) {
            this.metrics.ownershipConflict();
            final OwnerClaim holder = epoch > 0L && holderOwner != null && !holderOwner.isEmpty()
                ? new OwnerClaim("session", profileId.toString(), holderNode == null ? "" : holderNode,
                    holderOwner, Math.max(1L, epoch), Math.max(1L, epoch), 0L)
                : null;
            return ClaimResult.refused("duplicate", holder);
        }
        return ClaimResult.granted(new OwnerClaim("session", profileId.toString(), node, owner.toString(),
            Math.max(1L, epoch), Math.max(1L, epoch), 0L));
    }

    /** Takes over a session only when its epoch is exactly {@code expectedEpoch} (operator recovery). */
    public ClaimResult reclaim(final UUID profileId, final long expectedEpoch, final String node, final UUID owner)
        throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.eval(RECLAIM, 2, key(profileId), epochKey(profileId),
            Long.toString(expectedEpoch), session(node, owner), node, owner.toString(),
            Long.toString(this.ttlSeconds * 1000L), this.incarnation));
        if (reply.size() < 2) throw new IOException("session reclaim returned a malformed reply");
        final boolean granted = RedisReplies.number(reply.get(0), 0L) == 1L;
        final long epoch = RedisReplies.number(reply.get(1), 0L);
        if (!granted) {
            this.metrics.staleFence();
            return ClaimResult.refused("stale-fence", null);
        }
        this.metrics.ownershipConflict();
        return ClaimResult.granted(new OwnerClaim("session", profileId.toString(), node, owner.toString(),
            Math.max(1L, epoch), Math.max(1L, epoch), 0L));
    }

    /** Renews the claim's TTL when the session, owner and epoch still match. */
    public boolean refresh(final UUID profileId, final String node, final UUID owner, final long epoch)
        throws IOException {
        final Object reply = this.client.eval(REFRESH, 1, key(profileId), session(node, owner), owner.toString(),
            Long.toString(epoch), Long.toString(this.ttlSeconds * 1000L));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.leaseRenewFailure();
        return ok;
    }

    /** Whether the claim is still held and unexpired. */
    public boolean valid(final UUID profileId, final String node, final UUID owner, final long epoch)
        throws IOException {
        final Object reply = this.client.eval(VALID, 1, key(profileId), session(node, owner), owner.toString(),
            Long.toString(epoch));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.staleFence();
        return ok;
    }

    /** Releases the claim when the session, owner and epoch match. */
    public boolean release(final UUID profileId, final String node, final UUID owner, final long epoch)
        throws IOException {
        final Object reply = this.client.eval(RELEASE, 1, key(profileId), session(node, owner), owner.toString(),
            Long.toString(epoch));
        return RedisReplies.number(reply, 0L) == 1L;
    }

    /** The current holder, or {@code null} when no session record exists. Never extends anything. */
    public OwnerClaim holder(final UUID profileId) throws IOException {
        final String key = key(profileId);
        final List<Object> reply = RedisReplies.list(this.client.call("HMGET", key, "node", "owner", "epoch", "expiresAt"));
        if (reply.size() < 4) return null;
        final String node = RedisReplies.text(reply.get(0));
        final String owner = RedisReplies.text(reply.get(1));
        final long epoch = RedisReplies.number(reply.get(2), 0L);
        if (node == null || owner == null || epoch < 1L) return null;
        return new OwnerClaim("session", profileId.toString(), node, owner, epoch, epoch,
            RedisReplies.number(reply.get(3), 0L));
    }
}
