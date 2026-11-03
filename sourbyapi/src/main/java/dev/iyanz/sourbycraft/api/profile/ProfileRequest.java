package dev.iyanz.sourbycraft.api.profile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A create request. The account is the player's UUID; a name is never stored as the identity.
 *
 * <p>The three-argument constructor and {@link #of(UUID, String)} of earlier builds are kept. A request
 * built with them leaves the profile type and the name to the server: the type is
 * {@code aurora.awf.multiprofile.types.default} and the name is rolled by the server.</p>
 *
 * @param accountId owning player
 * @param type consumer namespace; {@code [a-z0-9_-]{1,32}}. Not the {@linkplain ProfileType policy type}.
 * @param metadata optional bounded metadata
 * @param profileType policy type, or {@code null} for the server's {@code types.default}. Refused with
 *     {@link ProfileErrorCode#TYPE_NOT_ALLOWED} when it is not in {@code types.allowed}.
 * @param name requested profile name, or {@code null} to let the server roll one. With
 *     {@code names.policy: cute} the only accepted names are those of the server's name pool (see
 *     {@link ProfileDetails#name()}); any other text is refused with {@link ProfileErrorCode#NAME_INVALID},
 *     and a name the account already uses (ignoring case) with {@link ProfileErrorCode#NAME_TAKEN}.
 *     A service built before Build 51 ignores this component and the {@code profileType}.
 */
public record ProfileRequest(UUID accountId, String type, Map<String, String> metadata,
                             @Nullable ProfileType profileType, @Nullable String name) {

    public ProfileRequest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A request without a policy type or a name (the server chooses both); the constructor of earlier builds. */
    public ProfileRequest(final UUID accountId, final String type, final Map<String, String> metadata) {
        this(accountId, type, metadata, null, null);
    }

    public static ProfileRequest of(final UUID accountId, final String type) {
        return new ProfileRequest(accountId, type, Map.of());
    }

    /** A request for the given policy type; the name is rolled by the server. */
    public static ProfileRequest of(final UUID accountId, final String type, final ProfileType profileType) {
        return new ProfileRequest(accountId, type, Map.of(), Objects.requireNonNull(profileType, "profileType"), null);
    }

    /** A copy with the given policy type ({@code null} = the server default). */
    public ProfileRequest withProfileType(final @Nullable ProfileType profileType) {
        return new ProfileRequest(this.accountId, this.type, this.metadata, profileType, this.name);
    }

    /** A copy asking for the given name ({@code null} = the server rolls one). */
    public ProfileRequest withName(final @Nullable String name) {
        return new ProfileRequest(this.accountId, this.type, this.metadata, this.profileType, name);
    }
}
