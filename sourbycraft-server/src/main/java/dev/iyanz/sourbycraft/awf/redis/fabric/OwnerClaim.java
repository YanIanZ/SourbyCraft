package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.Objects;

/**
 * A fenced claim on one object (a profile session, a profile, a world, a node task).
 *
 * <p>{@code epoch} and {@code fence} are both monotonic, but they answer different questions:</p>
 * <ul>
 *   <li>{@code epoch} counts successive claims of the same session; a reconnecting node sees that it
 *       is not the same incarnation as before.</li>
 *   <li>{@code fence} is a per-object counter that is never reset and survives TTL expiry. A durable
 *       write carries the fence it was issued under; Redis rejects the write when the object has been
 *       re-claimed since. TTL expiry alone therefore never authorises a stale writer — only a new
 *       fence does.</li>
 * </ul>
 *
 * @param kind object family, for example {@code profile} or {@code world}
 * @param id object identifier
 * @param node the node that holds the claim
 * @param owner the owner token (a node-local UUID, not a player name)
 * @param epoch session incarnation
 * @param fence per-object monotonic fence
 * @param expiresAtMillis Redis-time deadline of the claim
 */
public record OwnerClaim(String kind, String id, String node, String owner, long epoch, long fence,
                         long expiresAtMillis) {

    public OwnerClaim {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(owner, "owner");
        if (epoch < 1) throw new IllegalArgumentException("epoch " + epoch);
        if (fence < 1) throw new IllegalArgumentException("fence " + fence);
    }

    public boolean sameIncarnation(final OwnerClaim other) {
        return other != null && this.kind.equals(other.kind) && this.id.equals(other.id)
            && this.node.equals(other.node) && this.owner.equals(other.owner) && this.fence == other.fence;
    }
}
