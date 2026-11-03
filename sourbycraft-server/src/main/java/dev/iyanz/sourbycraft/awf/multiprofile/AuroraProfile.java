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
 * {@code profileType} is the persisted policy type (classic, ironman, stranded, bingo; stored and
 * returned, enforced from Build 55). {@code name} is chosen once from the {@link CuteNames} pool
 * and never changes; it is unique within the owner's account by {@link CuteNames#key}, which the
 * repository enforces in the commit that stores the profile.</p>
 *
 * <p><b>Revision.</b> {@code revision} is the metadata revision {@code M}: it advances exactly once
 * per committed change of this record (status, kind, display label, members, binding reference, hold,
 * reservation) and never for a member's save or a shared-state mutation. In a format-1 store the same
 * counter also served as the snapshot freshness counter, which is why {@link #withHold} does not bump
 * it; in format 2 snapshot freshness is {@link MemberStateHead#revision()} per member, and the engine
 * compares a snapshot with its head, never with {@code M}. Until the solo engine paths are rewired to
 * heads they keep reading {@code revision} as before.</p>
 *
 * <p><b>Members.</b> At most {@value #MAX_MEMBERS} rows, exactly one {@link ProfileRole#OWNER} whose
 * player equals {@code accountId}, one row per player; each row carries its membership epoch. A
 * {@link ProfileKind#SOLO} profile has the owner row only; a {@link ProfileKind#COOP} profile may hold
 * more (the configured co-op cap, at most 8 in Build 52, is checked by the operation that adds a member
 * against the {@code M} it read, so a concurrent accept is refused as stale rather than overfilling).
 * Removing a member drops its row; its epoch is never reused, so the same player rejoining gets a new
 * row and a new epoch ({@link ProfileMember#admitted}). {@code bindingRevision} is the revision of the
 * binding record this metadata was last committed against; {@code pendingIntent} is the operation that
 * holds a reservation on this record, or {@code null}: a reader that finds one resolves or helps that
 * intent and never treats a half-published relationship as valid.</p>
 *
 * <p><b>Kind and display label.</b> {@code kind} is one way ({@link #convertedToCoop}). {@code displayLabel}
 * is the shared label every member sees for a co-op profile, changed by the OWNER under {@code P}/{@code M}
 * ({@link #withDisplayLabel}); it is not unique and it is not any member's account-local alias
 * ({@link PlayerAccount#aliases()}): renaming it changes no account record. {@code null} means "show the
 * profile name".</p>
 *
 * @param id immutable profile id
 * @param accountId owning player (the OWNER member); immutable (owner transfer is refused)
 * @param type consumer namespace; 1-32 characters {@code [a-z0-9_-]}
 * @param profileType persisted policy type
 * @param name immutable profile name from the {@link CuteNames} pool
 * @param metadata bounded string metadata (slot hint, template choice)
 * @param createdAtMillis creation time
 * @param revision metadata revision {@code M}
 * @param status lifecycle status
 * @param members member rows, owner first
 * @param bindingRevision revision of the binding record referenced by this metadata ({@code 0} = none)
 * @param pendingIntent operation holding a reservation on this record, or {@code null}
 * @param kind solo or co-op (one way)
 * @param displayLabel shared display label, 1-{@value #MAX_DISPLAY_LABEL} characters, or {@code null}
 */
public record AuroraProfile(UUID id, UUID accountId, String type, dev.iyanz.sourbycraft.api.profile.ProfileType profileType,
                            String name, Map<String, String> metadata, long createdAtMillis, long revision,
                            ProfileStatus status, List<ProfileMember> members, long bindingRevision, UUID pendingIntent,
                            ProfileKind kind, String displayLabel) {

    public static final int MAX_METADATA = 32;
    public static final int MAX_METADATA_KEY = 64;
    public static final int MAX_METADATA_VALUE = 512;
    /** Members per profile the record can hold; the format-2 record holds no historical epochs beyond these rows. */
    public static final int MAX_MEMBERS = 64;
    /** Longest shared display label (characters). */
    public static final int MAX_DISPLAY_LABEL = 32;

    public AuroraProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profileType, "profileType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        if (!type.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("profile type: " + type);
        // Decoding is lenient (a stored name of a type pool whose definition is not registered here still reads);
        // the create path validates strictly against the pool (ProfileTypes.chooseName).
        if (!validStoredName(name)) throw new IllegalArgumentException("profile name: " + name);
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
        if (kind == ProfileKind.SOLO && members.size() > 1) {
            throw new IllegalArgumentException("a solo profile has the owner row only; convert it to co-op first");
        }
        if (displayLabel != null && !validDisplayLabel(displayLabel)) throw new IllegalArgumentException("display label: " + displayLabel);
    }

    /**
     * The Build 51 shape (no kind, no display label): a {@link ProfileKind#SOLO} profile without a label. Kept so
     * every Build 51 caller and golden fixture builds the same record; a co-op profile is never built through it.
     */
    public AuroraProfile(final UUID id, final UUID accountId, final String type,
                         final dev.iyanz.sourbycraft.api.profile.ProfileType profileType, final String name,
                         final Map<String, String> metadata, final long createdAtMillis, final long revision,
                         final ProfileStatus status, final List<ProfileMember> members, final long bindingRevision,
                         final UUID pendingIntent) {
        this(id, accountId, type, profileType, name, metadata, createdAtMillis, revision, status, members, bindingRevision,
            pendingIntent, ProfileKind.SOLO, null);
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
            createdAtMillis, revision, status, List.of(), 0L, null, ProfileKind.SOLO, null);
    }

    /** A new solo profile: owner row with a fresh epoch, revision 0, no binding, no reservation. */
    public static AuroraProfile create(final UUID accountId, final String type,
                                       final dev.iyanz.sourbycraft.api.profile.ProfileType profileType, final String name,
                                       final Map<String, String> metadata, final long now) {
        return new AuroraProfile(UUID.randomUUID(), accountId, type, profileType, name, metadata, now, 0L,
            ProfileStatus.ACTIVE, List.of(ProfileMember.owner(accountId, now)), 0L, null, ProfileKind.SOLO, null);
    }

    /**
     * Whether a name may be <em>stored</em>: 1-{@value CuteNames#MAX_LENGTH} printable characters without surrounding
     * whitespace. Build 54: creation chooses names from the server pool or the profile type's pool
     * ({@code ProfileTypes.chooseName}); a record whose type definition is not registered on this node must still decode,
     * so this check is deliberately not the pool check ({@link CuteNames#valid}). Never brick startup over a name.
     */
    public static boolean validStoredName(final String name) {
        if (name == null || name.isEmpty() || name.length() > CuteNames.MAX_LENGTH || !name.equals(name.strip())) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Whether a display label is well formed: 1-{@value #MAX_DISPLAY_LABEL} characters, no leading or trailing
     * whitespace, no ISO control characters. The label is not normalised or made unique anywhere.
     */
    public static boolean validDisplayLabel(final String label) {
        if (label == null || label.isEmpty() || label.length() > MAX_DISPLAY_LABEL) return false;
        if (!label.equals(label.strip())) return false;
        for (int i = 0; i < label.length(); i++) {
            if (Character.isISOControl(label.charAt(i))) return false;
        }
        return true;
    }

    /** {@code M + 1}, refusing an exhausted counter ({@link CoopWriteGuard#next}). */
    public AuroraProfile nextRevision() {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            this.pendingIntent, this.kind, this.displayLabel);
    }

    /** A status change: a committed metadata mutation, so {@code M} advances. */
    public AuroraProfile withStatus(final ProfileStatus next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), next, this.members, this.bindingRevision,
            this.pendingIntent, this.kind, this.displayLabel);
    }

    /**
     * Format-1 compatibility: an administrative hold or its release ({@link ProfileStatus#LOCKED} /
     * {@link ProfileStatus#ACTIVE}) without a revision bump, because in a format-1 store the revision
     * is also the snapshot freshness counter and bumping it would make the latest snapshot stale. A
     * format-2 path uses {@link #withStatus}: freshness lives on the member head there.
     */
    public AuroraProfile withHold(final ProfileStatus next) {
        if (next == ProfileStatus.DELETED) throw new IllegalArgumentException("a deletion uses withStatus");
        return copy(this.revision, next, this.members, this.bindingRevision, this.pendingIntent, this.kind, this.displayLabel);
    }

    /** The member rows after a committed membership change; {@code M} advances. */
    public AuroraProfile withMembers(final List<ProfileMember> next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, next, this.bindingRevision,
            this.pendingIntent, this.kind, this.displayLabel);
    }

    /** Records the binding revision this metadata is committed against; {@code M} advances. */
    public AuroraProfile withBindingRevision(final long next) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, next, this.pendingIntent,
            this.kind, this.displayLabel);
    }

    /** Installs or clears ({@code null}) the reservation of an intent; a committed mutation, {@code M} advances. */
    public AuroraProfile withPendingIntent(final UUID operationId) {
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            operationId, this.kind, this.displayLabel);
    }

    /** The same record at an explicit revision (decoders and after-images only). */
    public AuroraProfile atRevision(final long revision) {
        return copy(revision, this.status, this.members, this.bindingRevision, this.pendingIntent, this.kind, this.displayLabel);
    }

    // ------------------------------------------------------------------ co-op mutations (each advances M once)

    /**
     * The one-way conversion to a co-op profile; {@code M} advances. Refuses ({@link IllegalStateException}) a
     * profile that already is one: the caller reports {@code ALREADY_COOP} before building an after-image.
     */
    public AuroraProfile convertedToCoop() {
        if (this.kind == ProfileKind.COOP) throw new IllegalStateException("profile " + this.id + " is already a co-op profile");
        if (this.members.isEmpty()) throw new IllegalStateException("profile " + this.id + " has no owner row (format 1); migrate first");
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            this.pendingIntent, ProfileKind.COOP, this.displayLabel);
    }

    /** A new or cleared ({@code null}) shared display label; {@code M} advances, no account record changes. */
    public AuroraProfile withDisplayLabel(final String label) {
        if (label != null && !validDisplayLabel(label)) throw new IllegalArgumentException("display label: " + label);
        return copy(CoopWriteGuard.next(this.revision, "profile " + this.id), this.status, this.members, this.bindingRevision,
            this.pendingIntent, this.kind, label);
    }

    /**
     * Adds a member row (a fresh epoch, see {@link ProfileMember#admitted}); {@code M} advances. Refuses a player
     * that is already a member, a second OWNER, or a row on a solo profile; the co-op size cap is the caller's
     * (configuration), only the record bound {@value #MAX_MEMBERS} is enforced here.
     */
    public AuroraProfile withMember(final ProfileMember row) {
        Objects.requireNonNull(row, "row");
        if (member(row.playerId()) != null) throw new IllegalArgumentException("player " + row.playerId() + " is already a member");
        if (row.role() == ProfileRole.OWNER) throw new IllegalArgumentException("a profile has exactly one OWNER");
        final List<ProfileMember> next = new ArrayList<>(this.members);
        next.add(row);
        return withMembers(next);
    }

    /**
     * Removes the row of {@code (playerId, epoch)}; {@code M} advances. Refuses the OWNER (owner removal is
     * refused in this port) and a row whose epoch differs from the one the caller read (a rejoin happened).
     */
    public AuroraProfile withoutMember(final UUID playerId, final UUID epoch) {
        final ProfileMember row = member(playerId);
        if (row == null || !row.epoch().equals(epoch)) {
            throw new IllegalArgumentException("player " + playerId + " is not a member at epoch " + epoch);
        }
        if (row.role() == ProfileRole.OWNER) throw new IllegalArgumentException("the OWNER cannot be removed");
        final List<ProfileMember> next = new ArrayList<>(this.members);
        next.remove(row);
        return withMembers(next);
    }

    /** Replaces one member row (role or last-seen change) keeping its position; {@code M} advances. */
    public AuroraProfile withMemberRow(final ProfileMember row) {
        final List<ProfileMember> next = new ArrayList<>(this.members);
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).playerId().equals(row.playerId())) {
                if (!next.get(i).epoch().equals(row.epoch())) {
                    throw new IllegalArgumentException("member " + row.playerId() + " changed epoch; a row is replaced within its epoch");
                }
                next.set(i, row);
                replaced = true;
            }
        }
        if (!replaced) throw new IllegalArgumentException("player " + row.playerId() + " is not a member");
        return withMembers(next);
    }

    private AuroraProfile copy(final long revision, final ProfileStatus status, final List<ProfileMember> members,
                               final long bindingRevision, final UUID pendingIntent, final ProfileKind kind, final String displayLabel) {
        return new AuroraProfile(this.id, this.accountId, this.type, this.profileType, this.name, this.metadata,
            this.createdAtMillis, revision, status, members, bindingRevision, pendingIntent, kind, displayLabel);
    }

    public boolean usable() {
        return this.status == ProfileStatus.ACTIVE;
    }

    public boolean coop() {
        return this.kind == ProfileKind.COOP;
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

    /** The label members see: the display label when set, else the profile name. */
    public String shownLabel() {
        return this.displayLabel == null ? this.name : this.displayLabel;
    }

    /**
     * Whether the immutable identity of this record is the same as {@code other}'s: id, owner, name, consumer
     * namespace, policy type and creation time. Both repositories compare a profile after-image with the stored
     * record this way and refuse a change (owner transfer, rename and re-typing are not operations of this port).
     */
    public boolean sameIdentity(final AuroraProfile other) {
        return other != null && this.id.equals(other.id) && this.accountId.equals(other.accountId) && this.name.equals(other.name)
            && this.type.equals(other.type) && this.profileType == other.profileType && this.createdAtMillis == other.createdAtMillis;
    }

    /** Whether this record came from a format-1 store (no member rows yet). */
    public boolean legacy() {
        return this.members.isEmpty();
    }
}
