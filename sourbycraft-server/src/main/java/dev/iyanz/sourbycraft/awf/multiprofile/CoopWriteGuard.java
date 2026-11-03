package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a format-2 (co-op) {@link ProfileRepository} mutation is made under: an operation identity,
 * the typed claims it holds and the exact revisions it expects, one per revision domain it touches.
 *
 * <p><b>Revision domains.</b> Four counters move independently (COOP-DESIGN §2):</p>
 * <ul>
 *   <li>{@link Domain#ACCOUNT} {@code A(player)}: accessible profiles and aliases, slot limit, active
 *       profile and epoch, session generation, account reservations;</li>
 *   <li>{@link Domain#METADATA} {@code M(profile)}: owner, roles, members and epochs, name, policy
 *       type, status, binding revision reference, membership reservations;</li>
 *   <li>{@link Domain#SHARED} {@code S(profile)}: bank, personal subaccounts, collections,
 *       contributions, transfer reservations;</li>
 *   <li>{@link Domain#HEAD} {@code R(profile, member, epoch)}: that member's snapshot head.</li>
 * </ul>
 * {@link Domain#RECORD} is the own revision of a record that is none of these (binding, intent,
 * invite, vote, salvage). Every committed mutation advances the records it changed by exactly one
 * ({@link #next}); a refused write advances nothing; a replayed operation returns its receipt without
 * advancing. Counters are capped at {@link #MAX_COUNTER} so FILE and Redis (Lua {@code tonumber},
 * 53-bit exact) agree; reaching the cap refuses the mutation and needs an offline migration, it never
 * wraps or resets.</p>
 *
 * <p><b>Typed claims.</b> {@link ProfileClaim} ({@code P}) is the short exclusive authority over a
 * profile's metadata and shared state; {@link PlayerClaim} ({@code Q}) is the one online session of a
 * player across all profiles and nodes, or an administrator's short account claim. They are different
 * record types with different {@link ClaimKind#kind() kind strings} and disjoint store namespaces, so a
 * {@code P} cannot be passed where a {@code Q} is required, and a valid claim on one resource cannot be
 * attached to a write on another: the repository compares the claim's resource id with the record it
 * writes, in the same atomic step as the write (Redis: inside the Lua script; FILE: under the commit
 * lock, see {@link ProfileRepository}).</p>
 *
 * <p><b>No wildcard.</b> There is no {@code ANY} revision and no unguarded instance: a domain the
 * guard does not list is one the mutation may not change, and a repository that is about to change a
 * record whose domain the guard does not cover refuses with {@link ProfileErrorCode#INVALID_REQUEST}
 * ({@link #expected}). The factories below build the exact vector each COOP-DESIGN §2 mutation row
 * requires; a repository method documents which factory its callers use.</p>
 *
 * <p>Refusals: a claim that does not match the stored lease is {@link ProfileErrorCode#OWNERSHIP_CONFLICT}
 * ({@code FENCED} in Redis terms), a revision or predicate that moved is {@link ProfileErrorCode#STALE_STATE}
 * ({@code STALE}). Neither writes anything.</p>
 *
 * @param operation the operation this write belongs to (id, actor, request digest)
 * @param profile the {@code P} claim, or {@code null} for a mutation whose row does not require one
 * @param actor the {@code Q} claim of the acting account, or {@code null} only for repository-internal
 *     recovery and migration ({@link CoopOperation.Kind#MIGRATION}, {@link CoopOperation.Kind#CLEANUP})
 * @param affected the {@code Q} claim of another affected account (accept/remove), or {@code null}
 * @param vector the expected revisions, one per touched record; never empty
 * @param expectedActiveProfile the account's active profile the write expects, {@link #NO_ACTIVE_PROFILE}
 *     for none, or {@code null} when the row does not compare it
 * @param reservations ids of the operation's own reservations the write expects to find installed
 */
public record CoopWriteGuard(CoopOperation operation, ProfileClaim profile, PlayerClaim actor, PlayerClaim affected,
                             List<Expected> vector, UUID expectedActiveProfile, Set<UUID> reservations) {

    /** Largest revision, fence or epoch counter both backends store exactly: {@code 2^53 - 1}. */
    public static final long MAX_COUNTER = (1L << 53) - 1L;
    /** Expected revision of a record that must not exist yet. */
    public static final long ABSENT = -1L;
    /** {@code expectedActiveProfile} value for "the account has no active profile". */
    public static final UUID NO_ACTIVE_PROFILE = new UUID(0L, 0L);
    /** Most entries a vector may carry (two profiles' metadata and heads, accounts, records). */
    public static final int MAX_VECTOR = 64;

    public CoopWriteGuard {
        Objects.requireNonNull(operation, "operation");
        vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
        if (vector.isEmpty()) throw new IllegalArgumentException("a write guard compares at least one revision");
        if (vector.size() > MAX_VECTOR) throw new IllegalArgumentException("write guard vector too long");
        for (int i = 0; i < vector.size(); i++) {
            for (int j = i + 1; j < vector.size(); j++) {
                if (vector.get(i).sameRecord(vector.get(j))) {
                    throw new IllegalArgumentException("write guard names " + vector.get(i).describe() + " twice");
                }
            }
        }
        reservations = Set.copyOf(reservations == null ? Set.of() : reservations);
        if (profile == null && actor == null) {
            final CoopOperation.Kind kind = operation.kind();
            if (kind != CoopOperation.Kind.MIGRATION && kind != CoopOperation.Kind.CLEANUP) {
                throw new IllegalArgumentException(kind + " requires a claim; only migration and cleanup run without one");
            }
        }
    }

    // ------------------------------------------------------------------ counters

    /**
     * {@code current + 1}, the revision a committed mutation gives the record. Refuses (nothing is
     * written) when the counter is exhausted; {@link Math#addExact} never wraps.
     */
    public static long next(final long current, final String what) {
        if (current < 0L) throw new IllegalArgumentException(what + " revision " + current);
        final long next = Math.addExact(current, 1L);
        if (next > MAX_COUNTER) {
            throw new ProfileOperationException(ProfileErrorCode.COUNTER_EXHAUSTED, what + " revision counter is exhausted at "
                + current + "; the mutation is refused and the store needs an offline migration (counters never reset)");
        }
        return next;
    }

    /** Validates a stored counter (revision, fence, generation): {@code 0..MAX_COUNTER}. */
    public static long counter(final long value, final String what) {
        if (value < 0L || value > MAX_COUNTER) throw new IllegalArgumentException(what + " " + value + " is outside 0.." + MAX_COUNTER);
        return value;
    }

    // ------------------------------------------------------------------ factories (COOP-DESIGN §2 rows)

    /** Create: {@code Q(actor)}, expected {@code A}; the new profile's {@code M} absent. */
    public static CoopWriteGuard createProfile(final CoopOperation op, final PlayerClaim actor, final long expectedAccount,
                                               final UUID newProfileId) {
        return new CoopWriteGuard(op, null, require(actor, "actor"), null, List.of(
            Expected.account(actor.playerId(), expectedAccount), Expected.metadata(newProfileId, ABSENT)), null, Set.of());
    }

    /** Status, display name, role, rule, hold or binding change: {@code P}, {@code Q(actor)}, expected actor {@code A} and {@code M}. */
    public static CoopWriteGuard metadata(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                          final long expectedAccount, final long expectedMetadata) {
        require(profile, "profile");
        return new CoopWriteGuard(op, profile, require(actor, "actor"), null, List.of(
            Expected.account(actor.playerId(), expectedAccount), Expected.metadata(profile.profileId(), expectedMetadata)),
            null, Set.of());
    }

    /** Binding change: as {@link #metadata} plus the binding's own revision. */
    public static CoopWriteGuard binding(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                         final long expectedAccount, final long expectedMetadata, final long expectedBinding) {
        require(profile, "profile");
        return new CoopWriteGuard(op, profile, require(actor, "actor"), null, List.of(
            Expected.account(actor.playerId(), expectedAccount), Expected.metadata(profile.profileId(), expectedMetadata),
            Expected.record(profile.profileId(), expectedBinding)), null, Set.of());
    }

    /** Account-local alias rename: {@code Q(account)}, expected {@code A}; no {@code M} change. */
    public static CoopWriteGuard alias(final CoopOperation op, final PlayerClaim account, final long expectedAccount) {
        return new CoopWriteGuard(op, null, require(account, "account"), null,
            List.of(Expected.account(account.playerId(), expectedAccount)), null, Set.of());
    }

    /**
     * Save or initialise a member stream: {@code Q(member)}, expected {@code A}, {@code M} (compared,
     * not advanced), {@code R} of {@code (profile, member, epoch)} and the active profile. No {@code P}.
     */
    public static CoopWriteGuard memberSave(final CoopOperation op, final PlayerClaim member, final UUID profileId,
                                            final UUID epoch, final long expectedAccount, final long expectedMetadata,
                                            final long expectedHead) {
        require(member, "member");
        return new CoopWriteGuard(op, null, member, null, List.of(
            Expected.account(member.playerId(), expectedAccount), Expected.metadata(profileId, expectedMetadata),
            Expected.head(profileId, member.playerId(), epoch, expectedHead)), profileId, Set.of());
    }

    /**
     * Switch the active profile: {@code Q(player)}, expected {@code A}, the expected active profile
     * before the switch, and the vector of both profiles' {@code M} and the player's exact {@code R}
     * on each ({@link Expected#metadata}, {@link Expected#head}); {@code P} is not required for the
     * player's own independent member saves.
     */
    public static CoopWriteGuard switchActive(final CoopOperation op, final PlayerClaim player, final long expectedAccount,
                                              final UUID expectedActive, final List<Expected> profiles) {
        require(player, "player");
        final List<Expected> vector = new ArrayList<>(profiles.size() + 1);
        vector.add(Expected.account(player.playerId(), expectedAccount));
        vector.addAll(profiles);
        return new CoopWriteGuard(op, null, player, null, vector,
            expectedActive == null ? NO_ACTIVE_PROFILE : expectedActive, Set.of());
    }

    /** Shared or bank mutation: {@code P}, {@code Q(actor)}, expected {@code A}, {@code M} and {@code S}; actor active on the profile. */
    public static CoopWriteGuard shared(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                        final long expectedAccount, final long expectedMetadata, final long expectedShared) {
        require(profile, "profile");
        return new CoopWriteGuard(op, profile, require(actor, "actor"), null, List.of(
            Expected.account(actor.playerId(), expectedAccount), Expected.metadata(profile.profileId(), expectedMetadata),
            Expected.shared(profile.profileId(), expectedShared)), profile.profileId(), Set.of());
    }

    /** Invite or vote create/revoke/cast: {@code P}, {@code Q(actor)}, expected {@code A}, {@code M} and the record's revision (or {@link #ABSENT}). */
    public static CoopWriteGuard membershipRecord(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                                  final long expectedAccount, final long expectedMetadata,
                                                  final UUID recordId, final long expectedRecord) {
        require(profile, "profile");
        return new CoopWriteGuard(op, profile, require(actor, "actor"), null, List.of(
            Expected.account(actor.playerId(), expectedAccount), Expected.metadata(profile.profileId(), expectedMetadata),
            Expected.record(recordId, expectedRecord)), null, Set.of());
    }

    /**
     * Accept or remove a member: {@code P}, {@code Q(actor)} and {@code Q(affected)}, expected affected
     * {@code A}, {@code M}, the invite or vote revision and the affected member's {@code R}.
     */
    public static CoopWriteGuard membership(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                            final PlayerClaim affected, final long expectedAffectedAccount,
                                            final long expectedMetadata, final UUID recordId, final long expectedRecord,
                                            final UUID affectedEpoch, final long expectedAffectedHead,
                                            final Set<UUID> reservations) {
        require(profile, "profile");
        require(actor, "actor");
        require(affected, "affected");
        return new CoopWriteGuard(op, profile, actor, affected, List.of(
            Expected.account(affected.playerId(), expectedAffectedAccount), Expected.metadata(profile.profileId(), expectedMetadata),
            Expected.record(recordId, expectedRecord),
            Expected.head(profile.profileId(), affected.playerId(), affectedEpoch, expectedAffectedHead)), null, reservations);
    }

    /**
     * Intent transition, receipt or cleanup: the claims of the affected resource ({@code P} and/or
     * {@code Q}), the intent's expected revision and the vector the intent recorded.
     */
    public static CoopWriteGuard intent(final CoopOperation op, final ProfileClaim profile, final PlayerClaim actor,
                                        final long expectedIntentRevision, final List<Expected> recordedVector,
                                        final Set<UUID> reservations) {
        final List<Expected> vector = new ArrayList<>(recordedVector.size() + 1);
        vector.add(Expected.record(op.operationId(), expectedIntentRevision));
        vector.addAll(recordedVector);
        return new CoopWriteGuard(op, profile, actor, null, vector, null, reservations);
    }

    /** Offline migration: no claim; every store format and record the migration touches is in the vector. */
    public static CoopWriteGuard migration(final CoopOperation op, final List<Expected> vector) {
        if (op.kind() != CoopOperation.Kind.MIGRATION) throw new IllegalArgumentException("not a migration operation");
        return new CoopWriteGuard(op, null, null, null, vector, null, Set.of());
    }

    // ------------------------------------------------------------------ checks

    /**
     * The expected revision for a record, or a refusal: a repository calls this for every record it is
     * about to change, so a mutation can never touch a record its guard does not name.
     *
     * @throws ProfileOperationException {@link ProfileErrorCode#INVALID_REQUEST} when the guard has no entry
     */
    public Expected expected(final Domain domain, final UUID resource, final UUID member, final UUID epoch) {
        for (final Expected entry : this.vector) {
            if (entry.domain() == domain && entry.resource().equals(resource) && Objects.equals(entry.member(), member)
                && Objects.equals(entry.epoch(), epoch)) {
                return entry;
            }
        }
        throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "operation " + this.operation.operationId()
            + " (" + this.operation.kind() + ") would change " + domain + " of " + resource
            + (member == null ? "" : " member " + member + "/" + epoch) + " but its guard names no expected revision for it;"
            + " nothing was written");
    }

    /**
     * Refuses with {@link ProfileErrorCode#STALE_STATE} when {@code storedRevision} ({@link #ABSENT}
     * for no record) is not the expected one for that record.
     */
    public void check(final Domain domain, final UUID resource, final UUID member, final UUID epoch, final long storedRevision) {
        final Expected entry = expected(domain, resource, member, epoch);
        if (entry.revision() == storedRevision) return;
        throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, entry.describe() + " is at revision "
            + describe(storedRevision) + " but operation " + this.operation.operationId() + " expected "
            + describe(entry.revision()) + "; another operation changed it and nothing was written");
    }

    /** Refuses the active-profile predicate when the guard compares it and the stored value differs. */
    public void checkActive(final UUID storedActive) {
        if (this.expectedActiveProfile == null) return;
        final UUID stored = storedActive == null ? NO_ACTIVE_PROFILE : storedActive;
        if (stored.equals(this.expectedActiveProfile)) return;
        throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, "the account's active profile is "
            + (storedActive == null ? "none" : storedActive.toString()) + " but operation " + this.operation.operationId()
            + " expected " + (NO_ACTIVE_PROFILE.equals(this.expectedActiveProfile) ? "none" : this.expectedActiveProfile)
            + "; nothing was written");
    }

    /** {@link ProfileErrorCode#OWNERSHIP_CONFLICT}: the stored lease does not match a claim this write carries. */
    public ProfileOperationException fenced(final String claim, final String detail) {
        return new ProfileOperationException(ProfileErrorCode.OWNERSHIP_CONFLICT, claim + " of operation "
            + this.operation.operationId() + " does not hold its lease any more (" + detail
            + "); the write was refused and nothing was written. A lapsed lease is not permission to retry with the same claim");
    }

    private static <T> T require(final T claim, final String what) {
        if (claim == null) throw new IllegalArgumentException("this mutation requires the " + what + " claim");
        return claim;
    }

    private static String describe(final long revision) {
        return revision == ABSENT ? "none" : Long.toString(revision);
    }

    // ------------------------------------------------------------------ nested types

    /** The four revision domains plus the own revision of other records. */
    public enum Domain {
        /** {@code A(player)}; {@code resource} is the player id. */
        ACCOUNT(1),
        /** {@code M(profile)}; {@code resource} is the profile id. */
        METADATA(2),
        /** {@code S(profile)}; {@code resource} is the profile id. */
        SHARED(3),
        /** {@code R(profile, member, epoch)}; {@code resource} is the profile id, member and epoch set. */
        HEAD(4),
        /** A binding (keyed by profile id), intent (operation id), invite, vote or salvage record. */
        RECORD(5);

        private final int tag;

        Domain(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static Domain fromTag(final int tag, final String where) throws java.io.IOException {
            for (final Domain domain : values()) {
                if (domain.tag == tag) return domain;
            }
            throw new java.io.IOException("revision domain tag " + tag + " in " + where + " is not known to this build");
        }
    }

    /**
     * One expected revision: the record (domain, resource and for heads member and epoch) and the
     * revision the caller read, or {@link #ABSENT}.
     */
    public record Expected(Domain domain, UUID resource, UUID member, UUID epoch, long revision) {

        public Expected {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(resource, "resource");
            if (domain == Domain.HEAD) {
                Objects.requireNonNull(member, "member");
                Objects.requireNonNull(epoch, "epoch");
            } else if (member != null || epoch != null) {
                throw new IllegalArgumentException(domain + " has no member or epoch");
            }
            if (revision != ABSENT) counter(revision, domain + " expected revision");
        }

        public static Expected account(final UUID playerId, final long revision) {
            return new Expected(Domain.ACCOUNT, playerId, null, null, revision);
        }

        public static Expected metadata(final UUID profileId, final long revision) {
            return new Expected(Domain.METADATA, profileId, null, null, revision);
        }

        public static Expected shared(final UUID profileId, final long revision) {
            return new Expected(Domain.SHARED, profileId, null, null, revision);
        }

        public static Expected head(final UUID profileId, final UUID member, final UUID epoch, final long revision) {
            return new Expected(Domain.HEAD, profileId, member, epoch, revision);
        }

        public static Expected record(final UUID recordId, final long revision) {
            return new Expected(Domain.RECORD, recordId, null, null, revision);
        }

        boolean sameRecord(final Expected other) {
            return this.domain == other.domain && this.resource.equals(other.resource)
                && Objects.equals(this.member, other.member) && Objects.equals(this.epoch, other.epoch);
        }

        public String describe() {
            return this.domain + " " + this.resource + (this.member == null ? "" : " member " + this.member + "/" + this.epoch);
        }
    }

    /** The two claim kinds; their kind strings and store namespaces are disjoint. */
    public enum ClaimKind {
        /** {@code P}: {@code coop:profile-claim:<profile>}. */
        PROFILE("coop-profile"),
        /** {@code Q}: {@code coop:player-claim:<player>}. */
        PLAYER("coop-player");

        private final String kind;

        ClaimKind(final String kind) {
            this.kind = kind;
        }

        /** The {@link OwnerClaim#kind()} string of claims of this kind. */
        public String kind() {
            return this.kind;
        }

        public static ClaimKind of(final String kind) {
            for (final ClaimKind value : values()) {
                if (value.kind.equals(kind)) return value;
            }
            throw new IllegalArgumentException("not a co-op claim kind: " + kind);
        }
    }

    /**
     * {@code P}: exclusive, short authority over one profile's metadata and shared state. Held for a
     * bounded transaction, released once it is durable or recoverable; not a player's session and not
     * transferred when players change. Members on any node acquire it in turn; a live claim of another
     * owner token is refused, also on the same node ({@link ProfileOwnership#claimTransaction}).
     *
     * @param profileId the profile
     * @param node holder's node id
     * @param incarnation holder's process incarnation (one per JVM)
     * @param ownerToken one per claim, never reused
     * @param fence per-profile counter that never resets; the write-time comparison value
     * @param expiresAtMillis lease deadline in the provider's clock ({@code 0} when not reported)
     */
    public record ProfileClaim(UUID profileId, String node, String incarnation, UUID ownerToken, long fence,
                               long expiresAtMillis) {

        public ProfileClaim {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(incarnation, "incarnation");
            Objects.requireNonNull(ownerToken, "ownerToken");
            if (fence < 1L || fence > MAX_COUNTER) throw new IllegalArgumentException("fence " + fence);
        }

        /** Wraps a fabric claim; refuses any kind but {@link ClaimKind#PROFILE} so a {@code Q} cannot pose as {@code P}. */
        public static ProfileClaim of(final OwnerClaim claim, final String incarnation) {
            if (ClaimKind.of(claim.kind()) != ClaimKind.PROFILE) {
                throw new IllegalArgumentException("a " + claim.kind() + " claim is not a profile claim");
            }
            return new ProfileClaim(UUID.fromString(claim.id()), claim.node(), incarnation, UUID.fromString(claim.owner()),
                claim.fence(), claim.expiresAtMillis());
        }

        public OwnerClaim toOwnerClaim() {
            return new OwnerClaim(ClaimKind.PROFILE.kind(), this.profileId.toString(), this.node, this.ownerToken.toString(),
                this.fence, this.fence, this.expiresAtMillis);
        }

        /** The holder identity a store compares: {@code node/incarnation/owner}. */
        public String session() {
            return this.node + "/" + this.incarnation + "/" + this.ownerToken;
        }
    }

    /**
     * {@code Q}: the one online session of a player across every profile and node, or an administrator's
     * short claim on an offline account. Acquired at join before capture or restore admission, released
     * at quit; never reused. A live claim of another owner token is refused, also on the same node, so
     * two sessions of one player cannot both write ({@link PlayerSessionOwnership}).
     *
     * @param playerId the account
     * @param node holder's node id
     * @param incarnation holder's process incarnation
     * @param ownerToken one per session, never reused
     * @param fence per-player counter that never resets
     * @param expiresAtMillis lease deadline in the provider's clock ({@code 0} when not reported)
     */
    public record PlayerClaim(UUID playerId, String node, String incarnation, UUID ownerToken, long fence,
                              long expiresAtMillis) {

        public PlayerClaim {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(incarnation, "incarnation");
            Objects.requireNonNull(ownerToken, "ownerToken");
            if (fence < 1L || fence > MAX_COUNTER) throw new IllegalArgumentException("fence " + fence);
        }

        /** Wraps a fabric claim; refuses any kind but {@link ClaimKind#PLAYER} so a {@code P} cannot pose as {@code Q}. */
        public static PlayerClaim of(final OwnerClaim claim, final String incarnation) {
            if (ClaimKind.of(claim.kind()) != ClaimKind.PLAYER) {
                throw new IllegalArgumentException("a " + claim.kind() + " claim is not a player session claim");
            }
            return new PlayerClaim(UUID.fromString(claim.id()), claim.node(), incarnation, UUID.fromString(claim.owner()),
                claim.fence(), claim.expiresAtMillis());
        }

        public OwnerClaim toOwnerClaim() {
            return new OwnerClaim(ClaimKind.PLAYER.kind(), this.playerId.toString(), this.node, this.ownerToken.toString(),
                this.fence, this.fence, this.expiresAtMillis);
        }

        public String session() {
            return this.node + "/" + this.incarnation + "/" + this.ownerToken;
        }
    }
}
