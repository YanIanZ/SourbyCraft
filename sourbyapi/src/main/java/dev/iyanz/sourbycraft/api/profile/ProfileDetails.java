package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A profile together with the fields added in Build 51: its display name and its policy type.
 *
 * <p>{@link ProfileInfo} is unchanged so that code compiled against earlier builds keeps working;
 * this record is the additive view. Obtain it with {@link AuroraProfiles#getProfileDetails(UUID)} or
 * {@link AuroraProfiles#listProfileDetails(UUID)}.</p>
 *
 * <p>The record carries no co-op membership and is unchanged in Build 52 (binary compatibility): the
 * co-op view is {@link CoopDetails}, from {@link AuroraProfiles#getCoopDetails(UUID)}.</p>
 *
 * @param info the profile as {@link AuroraProfiles#getProfile(UUID)} returns it
 * @param name the profile's name. Chosen once when the profile is created, never changed afterwards
 *     and unique per account (compared ignoring case). With {@code names.policy: cute} it is a name
 *     from the server's fruit and vegetable pool, optionally followed by a space and a number when
 *     the pool is exhausted for the account. A profile created before Build 51 receives its name when
 *     the store is migrated to format 2.
 * @param profileType the policy type chosen at creation, see {@link ProfileType}
 * @param archivedAtMillis added in Build 55: when the profile was archived read-only (the store clock), or
 *     {@code null} while it is not archived. An archived profile's {@code info().status()} reads {@code ARCHIVED}
 *     and {@code info().usable()} is false
 * @param seasonal added in Build 55: whether the profile's type is {@linkplain ProfileTypeDefinition#seasonal() seasonal}
 *     (meant to be archived when its event ends) on this server
 */
public record ProfileDetails(ProfileInfo info, String name, ProfileType profileType, @Nullable Long archivedAtMillis,
                             boolean seasonal) {

    public ProfileDetails {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profileType, "profileType");
    }

    /** The Build 51-54 shape: not archived, not seasonal. */
    public ProfileDetails(final ProfileInfo info, final String name, final ProfileType profileType) {
        this(info, name, profileType, null, false);
    }

    /** Added in Build 55. Whether the profile is archived (read-only). */
    public boolean archived() {
        return this.archivedAtMillis != null;
    }

    /** The profile id; same as {@code info().id()}. */
    public UUID id() {
        return this.info.id();
    }

    /**
     * Added in Build 54. The profile's type id ({@link ProfileTypeRegistry#typeId}): a registered type's id, else
     * {@code profileType().id()}.
     */
    public String typeId() {
        return ProfileTypeRegistry.typeId(this.profileType, this.info.metadata());
    }

    /** The owning account; same as {@code info().accountId()}. */
    public UUID accountId() {
        return this.info.accountId();
    }
}
