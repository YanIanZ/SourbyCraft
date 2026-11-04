package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;

/**
 * The node that currently owns a profile.
 *
 * @param node node identity
 * @param epoch monotonically increasing claim incarnation
 * @param expiresAtMillis advisory expiry from the ownership service's clock; never the only check
 * @param distributed whether the ownership is coordinated across nodes
 */
public record ProfileOwner(String node, long epoch, long expiresAtMillis, boolean distributed) {

    public ProfileOwner {
        Objects.requireNonNull(node, "node");
        if (epoch < 1) throw new IllegalArgumentException("epoch " + epoch);
    }
}
