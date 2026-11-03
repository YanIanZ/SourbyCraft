package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Durable storage of profiles, accounts, bindings, snapshots, journals and, in store format 2, member
 * heads, shared state, intents, receipts, indexes and the membership records.
 *
 * <p>Every method is a synchronous I/O call and must be used on an I/O worker, never on a region,
 * entity, global-tick or Netty thread. Implementations must be safe for concurrent calls. There is no
 * transaction type: a multi-record operation is <em>recoverable</em> through its {@link ProfileIntent},
 * not atomic, and the methods say so. Record CRUD by itself is never a transaction.</p>
 *
 * <h2>Two guard families</h2>
 *
 * <p>The format-1 methods take a {@link ProfileWriteGuard} (one claim, one revision) and keep PR #62's
 * contract: the repository compares the stored revision with the expected one in the same step as the
 * write (Redis: inside the Lua script that applies it; FILE: under its lock) and refuses otherwise.
 * The format-2 methods take a {@link CoopWriteGuard}: an operation identity, typed {@code P}/{@code Q}
 * claims and one expected revision per touched record. For every record a format-2 mutation changes,
 * the implementation</p>
 * <ol>
 *   <li>checks the store is {@link StoreFormat.Gate#FORMAT_2} (every write; a format-1 store refuses
 *       with {@code MIGRATION_REQUIRED}, an unknown or migrating one with {@code STORE_FORMAT_REFUSED});</li>
 *   <li>resolves the operation id against stored receipts and intents first: a replay returns the stored
 *       result ({@link CommitResult.Status#REPLAYED}) without changing anything; the same id with another
 *       request digest is {@code INVALID_REQUEST};</li>
 *   <li>validates each claim the guard carries against the stored lease — resource id, exact session
 *       ({@code node/incarnation/owner}), fence and unexpired by the store's clock (Redis {@code TIME};
 *       FILE a monotonic clock read under the commit lock) — and refuses with
 *       {@code OWNERSHIP_CONFLICT} ({@code FENCED}) otherwise; a claim on another resource, or of the
 *       other kind, never passes;</li>
 *   <li>compares every record it is about to change with {@link CoopWriteGuard#check} and the active
 *       profile predicate with {@link CoopWriteGuard#checkActive}, refusing with {@code STALE_STATE}
 *       ({@code STALE}); a record the guard does not name is refused with {@code INVALID_REQUEST};</li>
 *   <li>only then writes, all compared records in one atomic step (Redis: one script, every touched key
 *       passed explicitly; FILE: under one repository commit lock shared with claim validation, so a
 *       lease cannot expire or be taken over between validation and publication).</li>
 * </ol>
 * <p>Refusals write nothing and advance no counter. A refusal is a {@code ProfileOperationException}
 * (a reached bound of COOP-DESIGN §9, such as the pending intents per account, profile or store, the
 * permanent receipts of a resource or a full invite, vote or salvage index, is
 * {@code CAPACITY_EXCEEDED}); a failure before publication is an {@link IOException}; a failure <em>after</em> the logical commit
 * point (a lost reply, an unconfirmed {@code WAITAOF}, a rename or directory force that failed after the
 * commit marker) is reported as {@link CommitResult.Status#UNKNOWN}, never as an exception, so a caller
 * cannot mistake it for "not applied": it holds its gate, reads the intent or receipt, and resumes or
 * rolls forward. It never compensates on UNKNOWN.</p>
 *
 * <h2>Bounds</h2>
 *
 * <p>Every read is bounded by the record type's payload limit ({@link ProfileRecords#maxPayload}) and
 * reads the actual stream, never {@code size} then {@code readAllBytes}. Listings return at most
 * {@link ProfileIndexPage#LISTING_PAGE} records per page through durable indexes, never a scan.
 * Member lists hold at most {@link AuroraProfile#MAX_MEMBERS}.</p>
 *
 * <h2>Implementation status</h2>
 *
 * <p>Both backends override every format-2 method (Build 51 for the solo operations, Build 52 for the
 * membership after-images, compare-only vector entries, commit deadlines and index bounds). The
 * defaults below refuse with an {@link UnsupportedOperationException} naming the method and the
 * backend: a backend that does not implement one fails closed and writes nothing, it never pretends to
 * succeed. The owner's cases {@code CoopFileClaimAndCasAtCommit} and {@code CoopRedisFencedAndStaleNoWrites}
 * cover them.</p>
 */
public interface ProfileRepository extends AutoCloseable {

    /** Short description for logs and {@code /awf multiprofile status}; does no I/O and holds no credentials. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /**
     * Whether a format-1 mutation that failed with an {@link IOException} may still have been applied: a
     * remote store can run a write and lose the reply. A caller that must know the outcome (the switch
     * commit) has to establish it before it compensates. A local store's failed write wrote nothing.
     * Format-2 mutations report this per call through {@link CommitResult.Status#UNKNOWN} instead.
     */
    default boolean remote() {
        return false;
    }

    // ================================================================== format 1 (PR #62 contract, unchanged)

    /** The account, or an empty account (revision 0) with {@code defaultSlots} when none is stored. */
    PlayerAccount loadAccount(UUID playerId, int defaultSlots) throws IOException;

    /**
     * Writes the account atomically. The guard's expected revision is the revision of the account the
     * caller read ({@code 0} for one that was never stored).
     */
    void saveAccount(PlayerAccount account, ProfileWriteGuard guard) throws IOException;

    Optional<AuroraProfile> profile(UUID profileId) throws IOException;

    /** Writes the profile record; the guard expects the stored profile revision, or {@link ProfileWriteGuard#ABSENT}. */
    void saveProfile(AuroraProfile profile, ProfileWriteGuard guard) throws IOException;

    /** Every stored profile of an account, in account order; missing entries are skipped. */
    List<AuroraProfile> profiles(UUID accountId) throws IOException;

    Optional<ProfileWorldBinding> binding(UUID profileId) throws IOException;

    void saveBinding(ProfileWorldBinding binding, ProfileWriteGuard guard) throws IOException;

    /** The snapshot with the highest revision (the newest of equal revisions), checksum-verified. */
    Optional<ProfileSnapshot> latestSnapshot(UUID profileId) throws IOException;

    /** One exact snapshot, checksum-verified; the rollback point a journal names. */
    Optional<ProfileSnapshot> snapshotById(UUID profileId, UUID snapshotId) throws IOException;

    /**
     * Publishes a snapshot and the profile record that advances to its revision: the snapshot is
     * durable before the record is written. The guard expects the profile revision the snapshot was
     * derived from. Older snapshots beyond {@code retain} (minimum 1) are pruned, except those a
     * pending journal of the account still names.
     */
    void publishSnapshot(AuroraProfile next, ProfileSnapshot snapshot, int retain, ProfileWriteGuard guard)
        throws IOException;

    /**
     * Archives the state a player had before any profile was applied (the snapshot's profile id is the
     * player id). Written by the player's first switch; an earlier archive is kept, never overwritten,
     * and {@link #legacySnapshot} returns the newest.
     */
    void saveLegacySnapshot(UUID playerId, ProfileSnapshot snapshot, ProfileWriteGuard guard) throws IOException;

    /** The archived pre-profile state of a player, checksum-verified. */
    Optional<ProfileSnapshot> legacySnapshot(UUID playerId) throws IOException;

    Optional<ProfileTransition> transition(UUID transitionId) throws IOException;

    /**
     * The journals of one player, oldest first. Fails when one of them cannot be read: that player's
     * journal state is unknown, so callers refuse to act for them, while other players are unaffected.
     */
    List<ProfileTransition> transitionsFor(UUID playerId) throws IOException;

    /** Every readable journal, oldest first, for status output; unreadable ones are skipped. */
    List<ProfileTransition> transitions() throws IOException;

    /** Journal files set aside as unreadable, for status output. */
    default List<String> quarantinedJournals() {
        return List.of();
    }

    void saveTransition(ProfileTransition transition, ProfileWriteGuard guard) throws IOException;

    void deleteTransition(UUID transitionId, ProfileWriteGuard guard) throws IOException;

    /** Removes the binding and snapshots but keeps the profile record (a tombstone). Never touches AWF world data. */
    void deleteProfileData(UUID profileId, ProfileWriteGuard guard) throws IOException;

    // ================================================================== store format (OD-2)

    /**
     * The store's format marker, read from the marker file or the Redis {@code meta} hash;
     * {@link StoreFormat.Marker#legacy()} for a store without one. Implementations read it once at open
     * and re-check it on every format-2 write; the decision is {@link StoreFormat#gate}.
     */
    default StoreFormat.Marker storeFormat() throws IOException {
        throw unsupported("storeFormat");
    }

    /**
     * Writes the marker. Offline migration only, under the migration's exclusive lock and with every
     * writer stopped: {@code migrating} before the copy, {@code ready} after verification. Refused
     * ({@code STORE_FORMAT_REFUSED}) when the stored marker is a format this build does not know.
     */
    default void writeStoreFormat(StoreFormat.Marker marker) throws IOException {
        throw unsupported("writeStoreFormat");
    }

    /**
     * Raises the marker of a ready format-2 store, online, to at least payload schema {@code schema} and
     * {@code minimumBuild} (Build 52: {@link StoreFormat#COOP_SCHEMA} and {@link StoreFormat#COOP_MINIMUM_BUILD},
     * before the first co-op record is prepared), and returns the marker now stored. Never lowers either value and
     * writes nothing when the marker already covers both. Durable before it returns (FILE: fsynced marker file;
     * Redis: the {@code meta} hash, confirmed in the append-only file when {@code wait-for-aof} is on); a failure
     * throws and the caller writes no co-op record. Refused ({@code STORE_FORMAT_REFUSED}) on a format-1 store, a
     * store whose migration is in progress or whose marker this build does not know.
     */
    default StoreFormat.Marker requireStoreFeature(int schema, int minimumBuild, String writtenBy) throws IOException {
        throw unsupported("requireStoreFeature");
    }

    // ================================================================== format 2: guarded record writes

    /**
     * Writes the account at {@code after.revision()} under a format-2 guard
     * ({@link CoopWriteGuard#createProfile}, {@link CoopWriteGuard#alias}, {@link CoopWriteGuard#switchActive}, ...).
     * Checks {@code Q(account)}, {@code A} and the active-profile predicate; also refuses an alias that
     * is not unique in the account under lower-case comparison. {@code after.revision()} must be the
     * expected revision plus one.
     */
    default CommitResult commitAccount(PlayerAccount after, CoopWriteGuard guard) throws IOException {
        throw unsupported("commitAccount");
    }

    /**
     * Writes the profile metadata at {@code after.revision()} under {@link CoopWriteGuard#metadata} (or
     * {@link CoopWriteGuard#createProfile} with {@code M} absent). Checks {@code P} on this profile,
     * {@code Q(actor)}, {@code A} and {@code M}. Creation also refuses a name whose
     * {@link CuteNames#key} another profile of the owner's account already uses: the uniqueness check
     * and the write are one commit.
     */
    default CommitResult commitProfile(AuroraProfile after, CoopWriteGuard guard) throws IOException {
        throw unsupported("commitProfile");
    }

    /** The binding's own revision, or empty when no binding is stored (format 2). */
    default OptionalLong bindingRevision(UUID profileId) throws IOException {
        throw unsupported("bindingRevision");
    }

    /**
     * Writes the binding at {@code nextRevision} under {@link CoopWriteGuard#binding}: checks {@code P},
     * {@code Q(actor)}, {@code A}, {@code M} and the binding's revision. Preserves the world ownership
     * contract of {@link #saveBinding}.
     */
    default CommitResult commitBinding(ProfileWorldBinding binding, long nextRevision, CoopWriteGuard guard) throws IOException {
        throw unsupported("commitBinding");
    }

    // ------------------------------------------------------------------ member streams

    /** The head of a member stream, or empty when the membership never had one. */
    default Optional<MemberStateHead> memberHead(UUID profileId, UUID memberId, UUID epoch) throws IOException {
        throw unsupported("memberHead");
    }

    /**
     * The stored heads of the profile's current members (at most {@link AuroraProfile#MAX_MEMBERS}), by the
     * profile's member rows; a member whose stream never had a head is skipped, never reported as a
     * revision-0 head (a guard for such a stream expects {@link CoopWriteGuard#ABSENT}). Both backends agree.
     */
    default List<MemberStateHead> memberHeads(UUID profileId) throws IOException {
        throw unsupported("memberHeads");
    }

    /**
     * Exact lookup {@code (profile, member, epoch, snapshotId)}, checksum-verified. The caller compares
     * it with the head ({@link MemberStateHead#matches}); this method never substitutes a latest or
     * nearest snapshot.
     */
    default Optional<ProfileSnapshot> memberSnapshot(UUID profileId, UUID memberId, UUID epoch, UUID snapshotId) throws IOException {
        throw unsupported("memberSnapshot");
    }

    /**
     * Publishes a member snapshot and its head under {@link CoopWriteGuard#memberSave}: checks
     * {@code Q(member)} (its resource is the member), {@code A}, {@code M} (compared, not advanced),
     * {@code R} and that the member's active profile is this one. {@code next} must be
     * {@code head.published(snapshot)} (R + 1, READY, exact id and checksum); a head at
     * {@link CoopWriteGuard#ABSENT} initialises the stream. The snapshot is durable before the head is
     * written. Older snapshots of this stream beyond {@code retain} (minimum 1) are pruned, except
     * those pinned by any pending intent of any member ({@link #pinnedSnapshots}). No {@code P} is
     * required and none is checked.
     */
    default CommitResult publishMemberSnapshot(MemberStateHead next, ProfileSnapshot snapshot, int retain, CoopWriteGuard guard)
        throws IOException {
        throw unsupported("publishMemberSnapshot");
    }

    /**
     * Exact snapshots of a stream that pending intents keep alive, with reference counts folded in
     * (the reverse pin index). Pruning skips every entry; a missing or corrupt pin page disables
     * pruning for the stream rather than returning an empty list.
     */
    default List<ProfileIntent.SnapshotRef> pinnedSnapshots(UUID profileId, UUID memberId, UUID epoch) throws IOException {
        throw unsupported("pinnedSnapshots");
    }

    // ------------------------------------------------------------------ shared state

    /** The profile's shared state, or empty when none was ever written (a new profile starts at {@link ProfileSharedState#empty}). */
    default Optional<ProfileSharedState> shared(UUID profileId) throws IOException {
        throw unsupported("shared");
    }

    /**
     * Installs the shared after-image at {@code after.revision()} under {@link CoopWriteGuard#shared}:
     * checks {@code P}, {@code Q(actor)}, {@code A}, {@code M}, {@code S} and that the actor is active on
     * the profile. The balances were computed and checked in Java; the store compares the exact
     * {@code S} and installs the bytes, it never does arithmetic.
     */
    default CommitResult mutateShared(ProfileSharedState after, CoopWriteGuard guard) throws IOException {
        throw unsupported("mutateShared");
    }

    // ------------------------------------------------------------------ intents, receipts, indexes

    /**
     * Stages one immutable after-image of operation {@code operationId} before {@link #prepareIntent}:
     * the payload is decoded with its record type's codec (identity, schema, bounds) and written where
     * PREPARE copies it from (FILE: {@code staging/<operation>/}; Redis: a content-addressed key with a
     * TTL). The returned reference goes into the intent's {@code afterImages}; the payload must be encoded
     * at the revision COMMIT installs (expected + 1 after the reservation, {@code 0} for a record expected
     * {@link CoopWriteGuard#ABSENT}) and carry no reservation. Staging is outside the commit decision and
     * only creates new blobs; staging the same bytes twice is a no-op. A ready format-2 store is required.
     * Member snapshots (type 10) are not after-images in Build 51: they are published through
     * {@link #publishMemberSnapshot} and pinned.
     *
     * @param member member id for a head, else {@code null}
     * @param epoch epoch for a head, else {@code null}
     */
    default ProfileIntent.BlobRef stageAfterImage(UUID operationId, byte recordType, UUID id, UUID member, UUID epoch,
                                                  long revision, byte[] payload) throws IOException {
        throw unsupported("stageAfterImage");
    }

    /**
     * PREPARE: validates the guard, forces the staged after-image blobs the intent references, then
     * writes the PREPARED intent and installs its reservations ({@code pendingIntent}) on every record in
     * {@code intent.expectedAfter()} in one logical step, advancing each once. Pins are recorded in the
     * reverse pin index. Replay by operation id returns the stored intent's state as REPLAYED.
     */
    default CommitResult prepareIntent(ProfileIntent intent, CoopWriteGuard guard) throws IOException {
        throw unsupported("prepareIntent");
    }

    /**
     * COMMIT: re-validates the guard and reservations, compares the post-reservation vector, refuses a
     * PREPARED intent whose {@link ProfileIntent#deadlineMillis() deadline} passed by the store clock
     * ({@code TIMEOUT}; Redis compares {@code TIME} inside the script, FILE its monotonic clock under the
     * commit lock — an invite accept carries the invite's expiry here, so an expired invitation is never
     * consumed), checks the §9 index bounds of the membership records it installs ({@code CAPACITY_EXCEEDED}:
     * {@value ProfileInvite#MAX_OPEN} open invites per profile and per invitee, one undecided vote per
     * profile, {@value ProfileSalvage#MAX_OUTSTANDING_PER_PROFILE} outstanding salvage per profile) and the
     * identity of a profile after-image against the stored record (owner, name, namespace, type and
     * creation time immutable; kind one way; a membership change needs the operation's {@code P}), writes
     * the COMMITTED marker (the logical commit; Redis confirms AOF on the same connection before returning),
     * then installs the canonical after-images, receipt and index pages idempotently and marks the
     * intent FINALIZED. A failure after the marker is {@link CommitResult.Status#UNKNOWN} or a
     * COMMITTED intent left for {@link #resolveIntent}; before the marker it is an exception and nothing
     * is committed. Replay returns the receipt. A roll-forward of a COMMITTED intent re-checks neither the
     * deadline nor the bounds: the decision was taken.
     *
     * <p>Vector shape both backends accept: a record the intent reserves is at {@code N} before and
     * {@code N + 1} after (after-image at {@code N + 2}); a record it only compares — an invite, vote or
     * salvage record, which has no reservation field, or a metadata record read but not changed — is at
     * {@code N} before and after (after-image, if any, at {@code N + 1}); a created record is
     * {@link CoopWriteGuard#ABSENT} both times (after-image at {@code 0}). {@link CoopIntentPlan} builds
     * exactly these.</p>
     */
    default CommitResult commitIntent(UUID operationId, CoopWriteGuard guard) throws IOException {
        throw unsupported("commitIntent");
    }

    /**
     * ABORT: allowed only while PREPARED (or RECOVERY_REQUIRED and not committed); clears exactly this
     * operation's reservations (one increment each), releases its pins and writes the ABORTED intent and
     * an ABORTED receipt. After COMMITTED it is refused: the operation rolls forward.
     */
    default CommitResult abortIntent(UUID operationId, CoopWriteGuard guard) throws IOException {
        throw unsupported("abortIntent");
    }

    /**
     * Recovery: finishes a COMMITTED intent (install after-images, receipt, indexes, FINALIZED) or
     * reports a PREPARED one as still pending for the engine to decide; idempotent. Used by startup
     * intent indexing before API admission and by a reader that meets a reservation.
     */
    default CommitResult resolveIntent(UUID operationId, CoopWriteGuard guard) throws IOException {
        throw unsupported("resolveIntent");
    }

    /**
     * Whether everything this repository wrote so far is durable as far as the store can confirm: FILE
     * writes are forced before they are acknowledged ({@code true}); Redis probes its append-only file
     * when {@code wait-for-aof} is on. Called before an outcome read back from the store (instead of
     * acknowledged by its own write) is accepted as applied; a {@code false} keeps the outcome UNKNOWN.
     */
    default boolean durable() throws IOException {
        return true;
    }

    /**
     * The store's own clock in epoch milliseconds, for deadlines several nodes compare (Redis {@code TIME});
     * a single-node store answers with the local clock, never running backwards within the process. Invite
     * and vote expiries are issued and judged in this clock (COOP-DESIGN §9: a clock set back does not
     * revive an expired invitation).
     */
    default long storeClockMillis() throws IOException {
        return System.currentTimeMillis();
    }

    default Optional<ProfileIntent> intent(UUID operationId) throws IOException {
        throw unsupported("intent");
    }

    /** The permanent receipt of an operation on {@code resource} (the account or profile it acted on), point-indexed. */
    default Optional<ProfileReceipt> receipt(UUID resource, UUID operationId) throws IOException {
        throw unsupported("receipt");
    }

    /**
     * One listing page from a durable index: at most {@link ProfileIndexPage#LISTING_PAGE} ids in
     * {@code (createdAt, id)} order after {@code cursor} ({@code null} = from the start). A cursor from
     * another index generation is refused ({@code INVALID_REQUEST}, restart the listing). {@code member}
     * and {@code epoch} are required for {@link ProfileIndexPage.Kind#streamScoped() stream-scoped} kinds
     * and {@code null} otherwise. {@link ProfileIndexPage.Kind#SNAPSHOTS_BY_STREAM} lists a member stream's
     * snapshot ids in {@code R} order with the publishing revision in the time field; both repositories
     * serve it from the stream (its sorted set, its bounded directory). A corrupt or missing page fails
     * the call; it is never reported as empty.
     *
     * <p>Membership indexes hold current records only, maintained by the commit that installs the record
     * (both backends the same): {@link ProfileIndexPage.Kind#INVITES_BY_PROFILE} and
     * {@link ProfileIndexPage.Kind#INVITES_BY_INVITEE} the OPEN invites; {@link ProfileIndexPage.Kind#VOTES_BY_PROFILE}
     * and {@link ProfileIndexPage.Kind#VOTES_BY_TARGET} the undecided (OPEN or PASSED) votes;
     * {@link ProfileIndexPage.Kind#SALVAGE_BY_PROFILE} every salvage record not DELIVERED;
     * {@link ProfileIndexPage.Kind#SALVAGE_AVAILABLE_BY_PROFILE} the AVAILABLE ones. A closed record keeps
     * its file or hash (readable by id) and leaves the index in the same commit. An expired invite or vote
     * stays indexed until a cleanup operation closes it ({@link CoopMembership#closeInvite},
     * {@link CoopMembership#cancelVote}); listings therefore check expiry against the store clock.
     * {@link CoopMembership} wraps these pages into record listings.</p>
     */
    default ProfileIndexPage index(ProfileIndexPage.Kind kind, UUID resource, UUID member, UUID epoch, ProfileIndexPage.Cursor cursor)
        throws IOException {
        throw unsupported("index");
    }

    // ------------------------------------------------------------------ membership records (reads; writes are intent after-images)
    // Invites, votes and salvage records are written only as after-images of a membership intent
    // (CoopMembership -> CoopIntentPlan -> stageAfterImage/prepareIntent/commitIntent); they have no direct commit.

    default Optional<ProfileInvite> invite(UUID inviteId) throws IOException {
        throw unsupported("invite");
    }

    default Optional<KickVote> vote(UUID voteId) throws IOException {
        throw unsupported("vote");
    }

    default Optional<ProfileSalvage> salvage(UUID salvageId) throws IOException {
        throw unsupported("salvage");
    }

    @Override
    void close();

    private UnsupportedOperationException unsupported(final String method) {
        return new UnsupportedOperationException(describe() + " does not implement the format-2 operation " + method
            + "(); Build 51 requires both repositories to override it. Nothing was written (fail closed)");
    }

    /**
     * The result of a format-2 mutation that passed its guard.
     *
     * @param status see {@link Status}
     * @param operationId the operation
     * @param receipt the receipt when one exists (COMMITTED or REPLAYED of a decided operation), else {@code null};
     *     callers rely on its presence, operation id, digest, kind and outcome only, never on
     *     {@link ProfileReceipt#result()} (both backends store it empty) and never on which resource it
     *     was read from (FILE keeps one per actor, account and profile; Redis one per operation)
     * @param detail what happened, for logs
     */
    record CommitResult(Status status, UUID operationId, ProfileReceipt receipt, String detail) {

        public CommitResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(detail, "detail");
        }

        public static CommitResult committed(final UUID operationId, final ProfileReceipt receipt, final String detail) {
            return new CommitResult(Status.COMMITTED, operationId, receipt, detail);
        }

        public static CommitResult replayed(final UUID operationId, final ProfileReceipt receipt, final String detail) {
            return new CommitResult(Status.REPLAYED, operationId, receipt, detail);
        }

        public static CommitResult unknown(final UUID operationId, final String detail) {
            return new CommitResult(Status.UNKNOWN, operationId, null, detail);
        }

        /** Whether the caller must establish the outcome ({@link #intent}/{@link #receipt}) before acting. */
        public boolean unknown() {
            return this.status == Status.UNKNOWN;
        }

        /** How a guarded mutation ended. */
        public enum Status {
            /** Applied by this call; its effects are durable. */
            COMMITTED,
            /** The operation id was already decided; nothing changed, the stored result is returned. */
            REPLAYED,
            /**
             * The store may or may not have applied it: the reply was lost, durability was not confirmed, or
             * a step after the logical commit failed. Not a failure to commit. Hold the gate, query the
             * intent or receipt, re-confirm durability and resume; never compensate.
             */
            UNKNOWN
        }
    }
}
