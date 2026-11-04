package dev.iyanz.sourbycraft.api.profile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable view of one profile.
 *
 * @param id immutable profile id
 * @param accountId owning player
 * @param type consumer namespace chosen at creation
 * @param metadata bounded string metadata stored with the profile
 * @param createdAtMillis creation time
 * @param revision durable state revision; a switch result never reports an older revision
 * @param status {@code ACTIVE}, {@code LOCKED} or {@code DELETED}
 * @param active whether this is the account's active profile right now
 * @param worlds bound AWF worlds, in binding order
 */
public record ProfileInfo(UUID id, UUID accountId, String type, Map<String, String> metadata, long createdAtMillis,
                          long revision, String status, boolean active, java.util.List<String> worlds) {

    public ProfileInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        worlds = worlds == null ? java.util.List.of() : java.util.List.copyOf(worlds);
    }

    public boolean usable() {
        return "ACTIVE".equals(this.status);
    }
}
