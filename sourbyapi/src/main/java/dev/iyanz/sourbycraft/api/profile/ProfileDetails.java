package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;

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
 */
public record ProfileDetails(ProfileInfo info, String name, ProfileType profileType) {

    public ProfileDetails {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profileType, "profileType");
    }

    /** The profile id; same as {@code info().id()}. */
    public UUID id() {
        return this.info.id();
    }

    /** The owning account; same as {@code info().accountId()}. */
    public UUID accountId() {
        return this.info.accountId();
    }
}
