package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.List;

/**
 * Exclusive, fenced ownership of an arbitrary named object: a profile, a world, a gameplay island.
 *
 * <p>Every successful {@link #claim} increments a per-object fence counter that is stored in its own
 * key and never expires. The fence is the only thing a durable write path has to check: a node whose
 * lease expired and was replaced cannot write with the old fence. A TTL expiring is therefore not a
 * grant — the replacement gets a strictly higher fence, and
 * {@link #validate(OwnerClaim)} refuses the old one even if the new owner has not written yet.</p>
 *
 * <p>All scripts compare with Redis {@code TIME}; no node clock participates in expiry.</p>
 */
public final class CoordinationFabric {

    private static final String CLAIM = """
        local key = KEYS[1]
        local counter = KEYS[2]
        local node = ARGV[1]
        local owner = ARGV[2]
        local ttl = tonumber(ARGV[3])
        local now = redis.call('TIME')
        now = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        local cur = redis.call('HMGET', key, 'owner', 'node', 'fence', 'expiresAt')
        local expires = tonumber(cur[4] or '0') or 0
        if expires > now and cur[1] then
          if cur[1] == owner and cur[2] == node then
            redis.call('HSET', key, 'expiresAt', now + ttl)
            redis.call('PEXPIRE', key, ttl)
            return {1, tonumber(cur[3] or '0') or 0, owner, node}
          end
          return {0, tonumber(cur[3] or '0') or 0, cur[1], cur[2] or ''}
        end
        local fence = redis.call('INCR', counter)
        redis.call('HSET', key, 'owner', owner, 'node', node, 'fence', fence, 'expiresAt', now + ttl)
        redis.call('PEXPIRE', key, ttl)
        return {1, fence, owner, node}
        """;

    private static final String RENEW = """
        local key = KEYS[1]
        local cur = redis.call('HMGET', key, 'owner', 'node', 'fence')
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
        local cur = redis.call('HMGET', key, 'owner', 'node', 'fence', 'expiresAt')
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
        local cur = redis.call('HMGET', key, 'owner', 'node', 'fence')
        if cur[1] == ARGV[1] and cur[2] == ARGV[2] and tonumber(cur[3] or '0') == tonumber(ARGV[3]) then
          return redis.call('DEL', key)
        end
        return 0
        """;

    private static final String CURRENT = """
        local cur = redis.call('HMGET', KEYS[1], 'owner', 'node', 'fence', 'expiresAt')
        if not cur[1] then return {} end
        return {cur[1], cur[2] or '', tonumber(cur[3] or '0') or 0, tonumber(cur[4] or '0') or 0}
        """;

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    private final int claimSeconds;

    CoordinationFabric(final RedisClient client, final String prefix, final FabricMetrics metrics,
                       final int claimSeconds) {
        this.client = client;
        this.prefix = prefix;
        this.metrics = metrics;
        this.claimSeconds = claimSeconds;
    }

    public String key(final String kind, final String id) {
        return this.prefix + "ownership:" + kind + ":" + id;
    }

    private String fenceKey(final String kind, final String id) {
        return this.prefix + "fence:" + kind + ":" + id;
    }

    /**
     * Claims or refreshes {@code kind:id} for this owner. A live claim by another owner is refused as
     * {@code held}; the caller must not write.
     */
    public ClaimResult claim(final String kind, final String id, final String node, final String owner)
        throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.eval(CLAIM, 2, key(kind, id), fenceKey(kind, id),
            node, owner, Long.toString(this.claimSeconds * 1000L)));
        if (reply.size() < 4) throw new IOException("coordination claim returned a malformed reply");
        if (RedisReplies.number(reply.get(0), 0L) != 1L) {
            this.metrics.ownershipConflict();
            final long fence = RedisReplies.number(reply.get(1), 0L);
            final String holderOwner = RedisReplies.text(reply.get(2));
            final String holderNode = RedisReplies.text(reply.get(3));
            final OwnerClaim holder = holderOwner == null || holderOwner.isEmpty() ? null
                : new OwnerClaim(kind, id, holderNode == null ? "" : holderNode, holderOwner, Math.max(1L, fence),
                    Math.max(1L, fence), 0L);
            return ClaimResult.refused("held", holder);
        }
        final long fence = RedisReplies.number(reply.get(1), 0L);
        return ClaimResult.granted(new OwnerClaim(kind, id, node, owner, Math.max(1L, fence), Math.max(1L, fence), 0L));
    }

    /** Renews the lease without changing the fence. */
    public boolean renew(final OwnerClaim claim) throws IOException {
        final Object reply = this.client.eval(RENEW, 1, key(claim.kind(), claim.id()), claim.owner(), claim.node(),
            Long.toString(claim.fence()), Long.toString(this.claimSeconds * 1000L));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.leaseRenewFailure();
        return ok;
    }

    /**
     * The write-path fence check: true only when this exact owner still holds an unexpired lease with
     * this exact fence. Callers that persist a protected mutation must call this immediately before
     * the write and abort on {@code false}.
     */
    public boolean validate(final OwnerClaim claim) throws IOException {
        final Object reply = this.client.eval(VALID, 1, key(claim.kind(), claim.id()), claim.owner(), claim.node(),
            Long.toString(claim.fence()));
        final boolean ok = RedisReplies.number(reply, 0L) == 1L;
        if (!ok) this.metrics.staleFence();
        return ok;
    }

    /** Releases the claim when the fence still matches. A later claim gets a higher fence. */
    public boolean release(final OwnerClaim claim) throws IOException {
        final Object reply = this.client.eval(RELEASE, 1, key(claim.kind(), claim.id()), claim.owner(), claim.node(),
            Long.toString(claim.fence()));
        return RedisReplies.number(reply, 0L) == 1L;
    }

    /** The current holder, or {@code null}; never extends the lease. */
    public OwnerClaim current(final String kind, final String id) throws IOException {
        final List<Object> reply = RedisReplies.list(this.client.eval(CURRENT, 1, key(kind, id)));
        if (reply.size() < 4) return null;
        return new OwnerClaim(kind, id, RedisReplies.text(reply.get(1)), RedisReplies.text(reply.get(0)),
            Math.max(1L, RedisReplies.number(reply.get(2), 1L)), Math.max(1L, RedisReplies.number(reply.get(2), 1L)),
            RedisReplies.number(reply.get(3), 0L));
    }

    /** The fence a durable writer must carry for {@code kind:id}; {@code 0} when never claimed. */
    public long fence(final String kind, final String id) throws IOException {
        final Object reply = this.client.call("GET", fenceKey(kind, id));
        return RedisReplies.number(reply, 0L);
    }
}
