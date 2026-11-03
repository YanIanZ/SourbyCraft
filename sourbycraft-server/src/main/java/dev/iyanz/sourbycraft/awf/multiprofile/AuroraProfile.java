package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable player profile: its metadata record ({@code M}, format-2 record type 2).
 *
 * <p>The id is a random {@code UUID} fixed at creation; names and slot numbers are never keys.
 * {@code type} is a free namespace owned by the consumer ({@code skyblock}, {@code survival});
 * {@code profileType} is the persisted policy type (classic, ironman, stranded, bingo; Build 51
 * stores and returns it, enforces nothing). {@code name} is chosen once from the {@link CuteNames} pool
 * and never changes; it is unique within the owner's account by {@link CuteNames#key}, which the
 * repository enforces in the commit that stores the profile.</p>
 *
 * <p><b>Revision.</b> {@code revision} is the metadata revision {@code M}: it advances exactly once
 * per committed change of this record (status, display name, members, binding reference, hold,
 * reservation) and never for a member's save or a shared-state mutation. In a format-1 store the same
 * counter also served as the snapshot freshness counter, which is why {@link #withHold} does not bump
 * it; in format 2 snapshot freshness is {@link MemberStateHead#revision()} per member, and the engine
 * compares a snapshot with its head, never with {@code M}. Until the solo engine paths are rewired to
 * heads they keep reading {@code revision} as before.</p>
 *
 * <p><b>Members.</b> At most {@value #MAX_MEMBERS} rows, exactly one {@link ProfileRole#OWNER} whose
 * player equals {@code accountId}, one row per player; each row carries its membership epoch. Build 51
 * writes the owner row only. {@code bindingRevision} is the revision of the binding record this
 * metadata was last committed against; {@code pendingIntent} is the operation that holds a reservation
 * on this record, or {@code null}: a reader that finds one resolves or helps that intent and never
 * treats a half-published relationship as valid.</p>
 *
 * @param id immutable profile id
 * @param accountId owning player (the OWNER member)
 * @param type consumer namespace; 1-32 characters {@code [a-z0-9_-]}
 * @param profileType persisted policy type
 * @param name immutable profile name from the {@link CuteNames} pool
 * @param metadata bounded string metadata (display name, slot hint, template choice)
 * @param createdAtMillis creation time
 * @param revision metadata revision {@code M}
 * @param status lifecycle status
 * @param members member rows, owner first
 * @param bindingRevision revision of the binding record referenced by this metadata ({@code 0} = none)
 * @param pendingIntent operation holding a reservation on this record, or {@code null}
 */
public record AuroraProfile(UUID id, UUID accountId, String type, dev.iyanz.sourbycraft.api.profile.ProfileType profileType,
                            String name, Map<String, String> metadata, long createdAtMillis, long revision,
                            ProfileStatus status, List<ProfileMember> members, long bindingRevision, UUID pendingIntent) {

    public static final int MAX_METADATA = 32;
    public static final int MAX_METADATA_KEY = 64;
    public static final int MAX_METADATA_VALUE = 512;
    /** Members per profile; the format-2 record holds no historical epochs beyond these rows. */
    public static final int MAX_MEMBERS = 64;

    public AuroraProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profileType, "profileType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        if (!type.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("profile type: " + type);
        if (!CuteNames.valid(name)) throw new IllegalArgumentException("profile name: " + name);
        if (metadata == null) metadata = Map.of();
        if (metadata.size() > MAX_METADATA) throw new IllegalArgumentException("too much profile metadata");
        final Map<String, String> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isEmpty() || key.length() > MAX_METADATA_KEY) throw new IllegalArgumentException("metadata key");
            if (value == null || value.length() > MAX_METADATA_VALUE) throw new IllegalArgumentException("metadata value");
            copy.put(key, value);
        });
        metadata = Collections.unmodifiableMap(copy);
        CoopWriteGuard.counter(revision, "profile revision");
        CoopWriteGuard.counter(bindingRevision, "binding revision");
        members = List.copyOf(members == null ? List.of() : members);
        if (members.size() > MAX_MEMBERS) throw new IllegalArgumentException("a profile has at most " + MAX_MEMBERS + " members");
        int owners = 0;
        for (int i = 0; i < members.size(); i++) {
            final ProfileMember member = members.get(i);
            if (member.role() == ProfileRole.OWNER) {
                owners++;
                if (!member.playerId().equals(accountId)) throw new IllegalArgumentException("the OWNER must be the account");
            }
            for (int j = i + 1; j < members.size(); j++) {
                if (members.get(j).playerId().equals(member.playerId())) {
                    throw new IllegalArgumentException("member " + member.playerId() + " listed twice");
                }
            }
        }
        if (!members.isEmpty() && owners != 1) throw new IllegalArgumentException("a profile has exactly one OWNER");
    }

    /**
     * A format-1 profile (no name, policy type or member rows stored): the name is derived from the id
     * ({@link CuteNames#fromId}), the policy type is {@code CLASSIC}, and the member list is empty
     * until migration allocates the owner's epoch. Used by the format-1 decoder and the solo engine
     * path; a format-2 record is never built through it.
     */
    public AuroraProfile(final UUID id, final UUID accountId, final String type, final Map<String, String> metadata,
                         final long createdAtMillis, final long revision, final ProfileStatus status) {
        this(id, accountId, type, dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC, CuteNames.fromId(id), metadata,
            createdAtMillis, revision, status, List.of(), 0L, null);
    }

    /** A new solo profile: owner row with a fresh epoch, revision 0, no binding, no reservation. */
    public static AuroraProfile create(final UUID accountId, final String type,
                                       final dev.iyanz.sourbycraft.api.profile.ProfileType profileType, final String name,
                                       final Map<String, String> metadata, final long now) {
        return new AuroraProfile(UUID.randomUUID(), accountId, type, profileType, name, metadata, now, 0L,
            ProfileStatus.ACTIVE, List.of(ProfileMember.owner(accountId, now)), 0L, null);
    }

    /** {@code M + 1}, refusing an exhausted counter ({@link CoopWriteGuard#next}). */
    public AuroraProfile nextRevision() {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            this.pendingIntent);
    }

    /** A status change: a committed metadata mutation, so {@code M} advances. */
    public AuroraProfile withStatus(final ProfileStatus next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), next, this.members, this.bindingRevision,
            this.pendingIntent);
    }

    /**
     * Format-1 compatibility: an administrative hold or its release ({@link ProfileStatus#LOCKED} /
     * {@link ProfileStatus#ACTIVE}) without a revision bump, because in a format-1 store the revision
     * is also the snapshot freshness counter and bumping it would make the latest snapshot stale. A
     * format-2 path uses {@link #withStatus}: freshness lives on the member head there.
     */
    public AuroraProfile withHold(final ProfileStatus next) {
        if (next == ProfileStatus.DELETED) throw new IllegalArgumentException("a deletion uses withStatus");
        return copy(this.revision, next, this.members, this.bindingRevision, this.pendingIntent);
    }

    /** The member rows after a committed membership change; {@code M} advances. */
    public AuroraProfile withMembers(final List<ProfileMember> next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, next, this.bindingRevision,
            this.pendingIntent);
    }

    /** Records the binding revision this metadata is committed against; {@code M} advances. */
    public AuroraProfile withBindingRevision(final long next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, next, this.pendingIntent);
    }

    /** Installs or clears ({@code null}) the reservation of an intent; a committed mutation, {@code M} advances. */
    public AuroraProfile withPendingIntent(final UUID operationId) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            operationId);
    }

    /** The same record at an explicit revision (decoders and after-images only). */
    public AuroraProfile atRevision(final long revision) {
        return copy(revision, this.status, this.members, this.bindingRevision, this.pendingIntent);
    }

    private AuroraProfile copy(final long revision, final ProfileStatus status, final List<ProfileMember> members,
                               final long bindingRevision, final UUID pendingIntent) {
        return new AuroraProfile(this.id, this.accountId, this.type, this.profileType, this.name, this.metadata,
            this.createdAtMillis, revision, status, members, bindingRevision, pendingIntent);
    }

    public boolean usable() {
        return this.status == ProfileStatus.ACTIVE;
    }

    /** The row of {@code playerId}, or {@code null} when not a current member. */
    public ProfileMember member(final UUID playerId) {
        for (final ProfileMember member : this.members) {
            if (member.playerId().equals(playerId)) return member;
        }
        return null;
    }

    /** Whether {@code (playerId, epoch)} is a current membership. */
    public boolean hasMember(final UUID playerId, final UUID epoch) {
        final ProfileMember member = member(playerId);
        return member != null && member.epoch().equals(epoch);
    }

    /** The owner row, or {@code null} for a format-1 record without member rows. */
    public ProfileMember owner() {
        for (final ProfileMember member : this.members) {
            if (member.role() == ProfileRole.OWNER) return member;
        }
        return null;
    }

    /** Current member ids, owner first. */
    public List<UUID> memberIds() {
        final List<UUID> ids = new ArrayList<>(this.members.size());
        for (final ProfileMember member : this.members) ids.add(member.playerId());
        return List.copyOf(ids);
    }

    /** Whether this record came from a format-1 store (no member rows yet). */
    public boolean legacy() {
        return this.members.isEmpty();
    }
}
