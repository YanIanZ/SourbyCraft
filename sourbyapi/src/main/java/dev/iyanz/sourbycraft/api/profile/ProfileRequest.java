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
 * @param metadata optional bounded metadata. Since Build 55 the keys {@code aurora:archived} and
 *     {@code aurora:archived-from-locked} are written by the server only (archive and unarchive); a request carrying
 *     either is refused with {@link ProfileErrorCode#INVALID_REQUEST}. {@link ProfileTypeRegistry#TYPE_METADATA_KEY} is
 *     set through {@link #withTypeId}.
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

    /**
     * Added in Build 54. A copy asking for a profile type by id ({@link ProfileTypeDefinition#id()}): a built-in id
     * sets {@link #profileType()} and removes {@link ProfileTypeRegistry#TYPE_METADATA_KEY}; any other id sets that
     * metadata key and leaves {@code profileType} unset (the server stores {@link ProfileType#CLASSIC} with it). The
     * server refuses an id it does not know ({@link ProfileErrorCode#TYPE_UNKNOWN}) or one that is not creatable
     * ({@link ProfileErrorCode#TYPE_NOT_ALLOWED}).
     */
    public ProfileRequest withTypeId(final String typeId) {
        Objects.requireNonNull(typeId, "typeId");
        final java.util.Map<String, String> next = new java.util.HashMap<>(this.metadata);
        final java.util.Optional<ProfileType> builtin = ProfileType.fromId(typeId);
        if (builtin.isPresent()) {
            next.remove(ProfileTypeRegistry.TYPE_METADATA_KEY);
            return new ProfileRequest(this.accountId, this.type, next, builtin.get(), this.name);
        }
        next.put(ProfileTypeRegistry.TYPE_METADATA_KEY, typeId);
        return new ProfileRequest(this.accountId, this.type, next, null, this.name);
    }

    /** A copy asking for the given name ({@code null} = the server rolls one). */
    public ProfileRequest withName(final @Nullable String name) {
        return new ProfileRequest(this.accountId, this.type, this.metadata, this.profileType, name);
    }
}
