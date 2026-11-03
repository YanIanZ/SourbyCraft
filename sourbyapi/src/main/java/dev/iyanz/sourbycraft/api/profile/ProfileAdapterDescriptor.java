package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a {@link ProfileStateAdapter} declares about itself (Build 53), returned by
 * {@link ProfileStateAdapter#descriptor()} and read once when the adapter is registered.
 *
 * @param id the adapter id, {@code [a-z0-9_.:-]{1,64}}; must equal {@link ProfileStateAdapter#id()}
 * @param version the data version, at least 1; must equal {@link ProfileStateAdapter#version()}
 * @param scope whose data the adapter stores
 * @param criticality whether the adapter's data is required for a correct switch; an operator can force an
 *     adapter to {@link ProfileDataCriticality#REQUIRED} with {@code adapters.required} (never the other way)
 * @param maxBytes the largest blob this adapter stores, 1 to {@value #MAX_BYTES_LIMIT}; the server's own
 *     per-adapter cap ({@value #MAX_BYTES_LIMIT} bytes) and the aggregate cap of one snapshot still apply, the
 *     smaller bound wins
 */
public record ProfileAdapterDescriptor(String id, int version, ProfileDataScope scope,
                                       ProfileDataCriticality criticality, long maxBytes) {

    /** The server's per-adapter blob cap: 2 MiB. */
    public static final long MAX_BYTES_LIMIT = 2L * 1024 * 1024;

    private static final Pattern ID = Pattern.compile("[a-z0-9_.:-]{1,64}");

    public ProfileAdapterDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(criticality, "criticality");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("adapter id: " + id);
        if (version < 1) throw new IllegalArgumentException("adapter version " + version);
        if (maxBytes < 1 || maxBytes > MAX_BYTES_LIMIT) {
            throw new IllegalArgumentException("adapter maxBytes " + maxBytes);
        }
    }
}
