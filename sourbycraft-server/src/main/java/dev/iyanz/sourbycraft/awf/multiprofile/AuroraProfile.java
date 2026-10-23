package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable player profile.
 *
 * <p>The id is a random {@code UUID} fixed at creation; the player's name and the slot number are
 * never keys. {@code type} is a free namespace owned by the consumer (for example {@code skyblock}
 * or {@code survival}); the AWF core does not interpret it. {@code revision} is bumped on every
 * durable change and is used for stale-write rejection together with the ownership fence.</p>
 *
 * @param id immutable profile id
 * @param accountId owning player
 * @param type consumer namespace; 1-32 characters {@code [a-z0-9_-]}
 * @param metadata bounded string metadata (display name, slot hint, template choice)
 * @param createdAtMillis creation time
 * @param revision durable state revision
 * @param status lifecycle status
 */
public record AuroraProfile(UUID id, UUID accountId, String type, Map<String, String> metadata, long createdAtMillis,
                            long revision, ProfileStatus status) {

    public static final int MAX_METADATA = 32;

    public AuroraProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (!type.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("profile type: " + type);
        if (metadata == null) metadata = Map.of();
        if (metadata.size() > MAX_METADATA) throw new IllegalArgumentException("too much profile metadata");
        final Map<String, String> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("metadata key");
            if (value == null || value.length() > 512) throw new IllegalArgumentException("metadata value");
            copy.put(key, value);
        });
        metadata = java.util.Collections.unmodifiableMap(copy);
        if (revision < 0) throw new IllegalArgumentException("revision " + revision);
    }

    public AuroraProfile nextRevision() {
        return new AuroraProfile(this.id, this.accountId, this.type, this.metadata, this.createdAtMillis,
            this.revision + 1, this.status);
    }

    public AuroraProfile withStatus(final ProfileStatus next) {
        return new AuroraProfile(this.id, this.accountId, this.type, this.metadata, this.createdAtMillis,
            this.revision + 1, next);
    }

    public boolean usable() {
        return this.status == ProfileStatus.ACTIVE;
    }
}
