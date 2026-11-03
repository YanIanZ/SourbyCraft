package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Strict, typed, fenced claims for co-op MultiProfile: the profile transaction claim {@code P} and
 * the player session claim {@code Q}.
 *
 * <p>This is deliberately not {@link SessionFabric}. That fabric keys one session per <em>profile</em>
 * and lets a second owner token of the same process replace a live claim (a rejoin after a switch);
 * co-op needs the opposite: a live claim is renewed only by the exact same {@code node/incarnation/owner}
 * session and refused for every other token, also one of the same node and process, because two
 * sessions of one player, or two transactions on one profile, must never both hold authority.
 * The two kinds live in disjoint namespaces, so a claim on a profile id can never be read as a claim
 * on a player id even where a UUID is reused across the two spaces.</p>
 *
 * <p><b>Keys</b> (under the fabric namespace):</p>
 * <ul>
 *   <li>{@code coop:profile-claim:<profile>} and {@code coop:player-claim:<player>} — hash
 *       {@code session, node, incarnation, owner, fence, expiresAt} with a TTL equal to the lease;</li>
 *   <li>{@code coop:profile-fence:<profile>} and {@code coop:player-fence:<player>} — the fence
 *       counter; never expires, never deleted, so a lapsed claim never restarts at an old fence.</li>
 * </ul>
 *
 * <p><b>Scripts</b> (each atomic, Redis {@code TIME} as the clock):</p>
 * <ul>
 *   <li>{@code CLAIM}: no live claim, or an expired one — a new claim with the next fence; the fence is
 *       refused ({@code exhausted}) instead of incremented past {@code 2^53 - 1}, the largest value
 *       Lua compares exactly. A live claim with the same session — renewed, same fence. Any other live
 *       claim — refused ({@code held}) with the holder reported.</li>
 *   <li>{@code RENEW} / {@code VALID} / {@code RELEASE}: session and fence must both match; {@code VALID}
 *       also requires the lease not to have expired by Redis time.</li>
 * </ul>
 *
 * <p>A write path never trusts {@code VALID} alone: the repository script that applies a guarded
 * write reads the claim hash itself ({@link #key}) and compares {@link #session} and the fence in the
 * same atomic step as the write. Expiry is never proof the holder stopped writing; only a higher
 * fence fences it. Every method blocks on Redis and must run on an I/O worker.</p>
 */
public final class CoopClaimFabric {

    /** KEYS: claim, fence. ARGV: session, node, owner, ttl ms, incarnation, max fence. */
    private static final String CLAIM = """
        local key = KEYS[1]
        local fenceKey = KEYS[2]
        local session = ARGV[1]
        local node = ARGV[2]
        local owner = ARGV[3]
        local ttl = tonumber(ARGV[4])
        local incarnation = ARGV[5]
        local maxFence = tonumber(ARGV[6])
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        local cur = redis.call('HMGET', key, 'session', 'node', 'owner', 'fence', 'expiresAt', 'incarnation')
        local fence = tonumber(cur[4] or '0') or 0
        local expires = tonumber(cur[5] or '0') or 0
        if expires > now then
          if cur[1] == session then
            redis.call('HSET', key, 'expiresAt', now + ttl)
            redis.call('PEXPIRE', key, ttl)
            return {1, fence, cur[3], cur[2], now + ttl, cur[6]}
          end
          return {0, fence, cur[3] or '', cur[2] or '', expires, cur[6] or ''}
        end
        local current = tonumber(redis.call('GET', fenceKey) or '0') or 0
        if current >= maxFence then
          return {-1, current, '', '', 0, ''}
        end
        fence = redis.call('INCR', fenceKey)
        redis.call('HSET', key, 'session', session, 'node', node, 'owner', owner, 'fence', fence,
          'expiresAt', now + ttl, 'incarnation', incarnation)
        redis.call('PEXPIRE', key, ttl)
        return {1, fence, owner, node, now + ttl, incarnation}
        """;

    /** KEYS: claim. ARGV: session, fence, ttl ms. */
    private static final String RENEW = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'fence', 'expiresAt')
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        if cur[1] == ARGV[1] and tonumber(cur[2] or '0') == tonumber(ARGV[2]) and tonumber(cur[3] or '0') > now then
          local ttl = tonumber(ARGV[3])
          redis.call('HSET', key, 'expiresAt', now + ttl)
          redis.call('PEXPIRE', key, ttl)
          return 1
        end
        return 0
        """;

    /** KEYS: claim. ARGV: session, fence. */
    private static final String VALID = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'fence', 'expiresAt')
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        if cur[1] == ARGV[1] and tonumber(cur[2] or '0') == tonumber(ARGV[2]) and tonumber(cur[3] or '0') > now then
          return 1
        end
        return 0
        """;

    /** KEYS: claim. ARGV: session, fence. */
    private static final String RELEASE = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'session', 'fence')
        if cur[1] == ARGV[1] and tonumber(cur[2] or '0') == tonumber(ARGV[2]) then
          return redis.call('DEL', key)
        end
        return 0
        """;

    /** Largest fence both Lua and Java compare exactly; the same cap as the repository revisions. */
    public static final long MAX_FENCE = (1L << 53) - 1L;

    /** The two claim kinds; the kind strings are those of {@link OwnerClaim#kind()}. */
    public enum Kind {
        PROFILE("coop-profile", "coop:profile-claim:", "coop:profile-fence:"),
        PLAYER("coop-player", "coop:player-claim:", "coop:player-fence:");

        private final String kind;
        private final String claimPrefix;
        private final String fencePrefix;

        Kind(final String kind, final String claimPrefix, final String fencePrefix) {
            this.kind = kind;
            this.claimPrefix = claimPrefix;
            this.fencePrefix = fencePrefix;
        }

        public String kind() {
            return this.kind;
        }

        public static Kind of(final String kind) {
            for (final Kind value : values()) {
                if (value.kind.equals(kind)) return value;
            }
            throw new IllegalArgumentException("not a co-op claim kind: " + kind);
        }
    }

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    private final int ttlSeconds;
    private final String incarnation;

    CoopClaimFabric(final RedisClient client, final String prefix, final FabricMetrics metrics, final int ttlSeconds,
                    final String incarnation) {
        this.client = Objects.requireNonNull(client, "client");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (ttlSeconds < 1) throw new IllegalArgumentException("ttlSeconds " + ttlSeconds);
        this.ttlSeconds = ttlSeconds;
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation");
    }

    /** The claim hash of a resource; a repository write script reads it to compare the holder. */
    public String key(final Kind kind, final UUID id) {
        return this.prefix + kind.claimPrefix + id;
    }

    /** Never expires: the fence must not restart when a claim's TTL lapses. */
    public String fenceKey(final Kind kind, final UUID id) {
        return this.prefix + kind.fencePrefix + id;
    }

    /** The key prefix of every fence counter of {@code kind}; a backup exports them with the records they fence. */
    public String fencePrefix(final Kind kind) {
        return this.prefix + kind.fencePrefix;
    }

    /** This process's incarnation, written into every claim this instance makes. */
    public String incarnation() {
        return this.incarnation;
    }

    public int ttlSeconds() {
        return this.ttlSeconds;
    }

    /** The holder identity stored and compared: {@code node/incarnation/owner}. */
    public String session(final String node, final UUID owner) {
        return node + "/" + this.incarnation + "/" + owner;
    }

    /**
     * Claims {@code id} for this process or renews its own live claim. Any other live claim is
     * refused ({@code held}, holder reported with its fence and expiry); an exhausted fence counter
     * is refused ({@code exhausted}) and needs an offline migration.
     */
    public ClaimResult claim(final Kind kind, final UUID id, final String node, final UUID owner) throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.eval(CLAIM, 2, key(kind, id), fenceKey(kind, id),
            session(node, owner), node, owner.toString(), Long.toString(this.ttlSeconds * 1000L), this.incarnation,
            Long.toString(MAX_FENCE)));
        if (reply.size() < 6) throw new IOException("co-op " + kind + " claim returned a malformed reply");
        final long status = RedisReplies.number(reply.get(0), 0L);
        final long fence = RedisReplies.number(reply.get(1), 0L);
        if (status == -1L) {
            this.metrics.ownershipConflict();
            return ClaimResult.refused("exhausted", null);
        }
        final String holderOwner = RedisReplies.text(reply.get(2));
        final String holderNode = RedisReplies.text(reply.get(3));
        final long expiresAt = RedisReplies.number(reply.get(4), 0L);
        if (status != 1L) {
            this.metrics.ownershipConflict();
            final OwnerClaim holder = fence > 0L && holderOwner != null && !holderOwner.isEmpty()
                ? new OwnerClaim(kind.kind(), id.toString(), holderNode == null ? "" : holderNode, holderOwner, fence, fence,
                    expiresAt)
                : null;
            return ClaimResult.refused("held", holder);
        }
        return ClaimResult.granted(new OwnerClaim(kind.kind(), id.toString(), node, owner.toString(), fence, fence, expiresAt));
    }

    /** Extends the lease when session and fence match and it has not expired; never changes the fence. */
    public boolean renew(final Kind kind, final UUID id, final String node, final UUID owner, final long fence)
        throws IOException {
        final Object reply = this.client.eval(RENEW, 1, key(kind, id), session(node, owner), Long.toString(fence),
            Long.toString(this.ttlSeconds * 1000L));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.leaseRenewFailure();
        return ok;
    }

    /**
     * {@link #renew} for many claims of this instance in one pipeline, results in the order of
     * {@code claims}. An error reply for any claim fails the whole call: which claims were renewed is
     * then unknown, so a caller keeps every claim and retries; it drops none.
     */
    public List<Boolean> renewAll(final List<OwnerClaim> claims) throws IOException {
        if (claims.isEmpty()) return List.of();
        final String ttl = Long.toString(this.ttlSeconds * 1000L);
        final List<Object[]> calls = new ArrayList<>(claims.size());
        for (final OwnerClaim claim : claims) {
            final Kind kind = Kind.of(claim.kind());
            final UUID owner = UUID.fromString(claim.owner());
            calls.add(new Object[] {1, key(kind, UUID.fromString(claim.id())), session(claim.node(), owner),
                Long.toString(claim.fence()), ttl});
        }
        final List<Object> replies = this.client.evalPipeline(RENEW, calls);
        if (replies.size() != claims.size()) throw new IOException("co-op claim renew returned a malformed reply");
        final List<Boolean> renewed = new ArrayList<>(replies.size());
        for (final Object reply : replies) {
            if (RedisReplies.isError(reply)) throw new IOException("co-op claim renew failed: " + RedisReplies.errorText(reply));
            final boolean ok = RedisReplies.number(reply, 0L) == 1L;
            if (!ok) this.metrics.leaseRenewFailure();
            renewed.add(ok);
        }
        return List.copyOf(renewed);
    }

    /** Whether the claim is still held by this session at this fence and unexpired by Redis time. */
    public boolean valid(final Kind kind, final UUID id, final String node, final UUID owner, final long fence)
        throws IOException {
        final Object reply = this.client.eval(VALID, 1, key(kind, id), session(node, owner), Long.toString(fence));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.staleFence();
        return ok;
    }

    /** Deletes the claim when session and fence match; the fence counter is kept. */
    public boolean release(final Kind kind, final UUID id, final String node, final UUID owner, final long fence)
        throws IOException {
        final Object reply = this.client.eval(RELEASE, 1, key(kind, id), session(node, owner), Long.toString(fence));
        return RedisReplies.number(reply, 0L) == 1L;
    }

    /** The current holder (expired or not; compare {@code expiresAtMillis} with Redis time), or {@code null}. */
    public OwnerClaim holder(final Kind kind, final UUID id) throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.call("HMGET", key(kind, id), "node", "owner", "fence",
            "expiresAt"));
        if (reply.size() < 4) return null;
        final String node = RedisReplies.text(reply.get(0));
        final String owner = RedisReplies.text(reply.get(1));
        final long fence = RedisReplies.number(reply.get(2), 0L);
        if (node == null || owner == null || fence < 1L) return null;
        return new OwnerClaim(kind.kind(), id.toString(), node, owner, fence, fence, RedisReplies.number(reply.get(3), 0L));
    }

    /** The incarnation recorded in the holder's hash, or {@code null} when no claim is stored. */
    public String holderIncarnation(final Kind kind, final UUID id) throws IOException {
        return RedisReplies.text(this.client.call("HGET", key(kind, id), "incarnation"));
    }
}
