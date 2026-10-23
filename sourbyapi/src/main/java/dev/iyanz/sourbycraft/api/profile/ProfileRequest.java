package dev.iyanz.sourbycraft.api.profile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A create request. The account is the player's UUID; a name is never stored as the identity.
 *
 * @param accountId owning player
 * @param type consumer namespace; {@code [a-z0-9_-]{1,32}}
 * @param metadata optional bounded metadata
 */
public record ProfileRequest(UUID accountId, String type, Map<String, String> metadata) {

    public ProfileRequest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ProfileRequest of(final UUID accountId, final String type) {
        return new ProfileRequest(accountId, type, Map.of());
    }
}
