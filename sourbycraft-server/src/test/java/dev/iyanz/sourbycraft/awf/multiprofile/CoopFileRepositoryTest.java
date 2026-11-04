package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner cases of COOP-DESIGN §10 slice 3 for the FILE repository: claims and CAS at commit, intent crash
 * recovery at every phase, replay of a lost reply, all-member pins, bounded reads and paged/corrupt indexes.
 * Crashes are simulated by closing the repository between two protocol steps and, where a step is internal to
 * one call, by putting the directory back into the state that step leaves (the files a crash would leave).
 */
class CoopFileRepositoryTest {

    @TempDir
    Path directory;

    private Path store() {
        return this.directory.resolve("store");
    }

    private Path scratch() {
        return this.directory.resolve("scratch");
    }

    // ------------------------------------------------------------------ CoopFileClaimAndCasAtCommit

    @Test
    void CoopFileClaimAndCasAtCommit() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);
            final AuroraProfile profile = CoopFixtures.create(repository, q, "Apple", CLASSIC);
            CoopFixtures.activate(repository, q, profile);
            final long a = repository.loadAccount(player, SLOTS).revision();

            // Stale expected revision: refused, nothing written.
            Map<String, String> before = tree(store());
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            refusedWith(ProfileErrorCode.STALE_STATE, () -> repository.commitAccount(account.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, a - 1L)));
            assertEquals(before, tree(store()));

            // The claim is checked at commit, after the revisions: a lease that lapsed after the caller's own
            // validation is refused by the store, for direct commits, member saves and intents alike.
            store.claims.live.set(false);
            assertTrue(join(store.players.validate(q)), "the caller's own pre-check still passes");
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.commitAccount(account.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, a)));
            final PlayerClaim heldQ = q;
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> CoopFixtures.save(repository, heldQ, profile.id()));
            final UUID other = UUID.randomUUID();
            final PlayerClaim otherQ = store.q(other);
            final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(repository, other, "Banana", CLASSIC);
            final List<ProfileIntent.BlobRef> refs = CoopFixtures.stage(repository, plan.op(), plan.images());
            before = tree(store());
            final CoopWriteGuard prepareGuard = CoopWriteGuard.intent(plan.op(), null, otherQ, CoopWriteGuard.ABSENT, plan.before(),
                Set.of());
            final ProfileIntent intent = ProfileIntent.prepared(prepareGuard, plan.after(), List.of(), refs, List.of(), 0L);
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.prepareIntent(intent, prepareGuard));
            assertEquals(before, tree(store()), "a fenced write changed the store");
            store.claims.live.set(true);

            // A released claim whose player was taken by another session is fenced, the new session writes.
            assertTrue(join(store.players.release(q)));
            final PlayerClaim taken = store.q(player);
            before = tree(store());
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.commitAccount(account.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), heldQ, a)));
            assertEquals(before, tree(store()));
            committed(repository.commitAccount(account.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), taken, a)));
            assertEquals(a + 1L, repository.loadAccount(player, SLOTS).revision());

            // CAS between two operations on one account: the second prepare from the same read is refused.
            final UUID racer = UUID.randomUUID();
            final PlayerClaim racerQ = store.q(racer);
            final CoopFixtures.CreatePlan first = CoopFixtures.planCreate(repository, racer, "Cherry", CLASSIC);
            final CoopFixtures.CreatePlan second = CoopFixtures.planCreate(repository, racer, "Grapes", CLASSIC);
            CoopFixtures.prepareCreate(repository, racerQ, first);
            final List<ProfileIntent.BlobRef> secondRefs = CoopFixtures.stage(repository, second.op(), second.images());
            before = tree(store());
            final CoopWriteGuard secondGuard = CoopWriteGuard.intent(second.op(), null, racerQ, CoopWriteGuard.ABSENT,
                second.before(), Set.of());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> repository.prepareIntent(ProfileIntent.prepared(secondGuard,
                second.after(), List.of(), secondRefs, List.of(), 0L), secondGuard));
            assertEquals(before, tree(store()), "a refused prepare changed the store");
            // A direct write to the reserved account is refused while the intent is pending.
            final PlayerAccount reserved = repository.loadAccount(racer, SLOTS);
            assertEquals(first.op().operationId(), reserved.pendingIntent());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> repository.commitAccount(reserved.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, racer), racerQ, reserved.revision())));
            committed(CoopFixtures.commit(repository, first.op(), null, racerQ));
            assertEquals(1, repository.profiles(racer).size());
        }

        // Without a validator every write that carries a claim is refused.
        final Path bare = this.directory.resolve("bare");
        try (FileProfileRepository repository = new FileProfileRepository(bare, "node-b")) {
            repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = join(PlayerSessionOwnership.local("node-b", 600).claim(player, UUID.randomUUID())).claim();
            final Map<String, String> before = tree(bare);
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.commitAccount(
                PlayerAccount.empty(player, SLOTS).withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, 0L)));
            assertEquals(before, tree(bare));
        }
    }

    /**
     * A write whose claim is fenced is refused even when its after-image already equals the stored record: the
     * store must not answer it as a replay or write a receipt for an operation that never held the lease.
     */
    @Test
    void aFencedWriteWhoseAfterImageIsAlreadyStoredWritesNothing() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);
            CoopFixtures.create(repository, q, "Apple", CLASSIC);
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            final PlayerAccount after = account.withSlotLimit(4);
            committed(repository.commitAccount(after, CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q,
                account.revision())));
            assertTrue(join(store.players.release(q)));
            store.q(player);
            final CoopOperation intruder = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
            final Map<String, String> before = tree(store());
            refusal(() -> repository.commitAccount(after, CoopWriteGuard.alias(intruder, q, account.revision())));
            assertTrue(repository.receipt(player, intruder.operationId()).isEmpty(), "a fenced operation got a receipt");
            assertEquals(before, tree(store()), "a fenced write changed the store");
        }
    }

    // ------------------------------------------------------------------ CoopIntentCrashAtEveryPhase

    @Test
    void CoopIntentCrashAtEveryPhase() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            // 1. Crash after staging, before PREPARE: nothing is visible; the operation can be prepared again.
            final UUID p1 = UUID.randomUUID();
            final PlayerClaim q1 = store.q(p1);
            final CoopFixtures.CreatePlan c1 = CoopFixtures.planCreate(store.repository, p1, "Apple", CLASSIC);
            CoopFixtures.stage(store.repository, c1.op(), c1.images());
            store.reopen();
            assertTrue(store.repository.intent(c1.op().operationId()).isEmpty());
            assertTrue(store.repository.profile(c1.profile().id()).isEmpty());
            assertNull(store.repository.loadAccount(p1, SLOTS).pendingIntent());
            assertEquals(0L, store.repository.loadAccount(p1, SLOTS).revision());
            CoopFixtures.prepareCreate(store.repository, q1, c1);
            committed(CoopFixtures.commit(store.repository, c1.op(), null, q1));
            assertEquals(2L, store.repository.loadAccount(p1, SLOTS).revision());

            // 2. Crash after PREPARED: the reservation survives, nothing is published, abort clears exactly it.
            final UUID p2 = UUID.randomUUID();
            final PlayerClaim q2 = store.q(p2);
            final CoopFixtures.CreatePlan c2 = CoopFixtures.planCreate(store.repository, p2, "Banana", CLASSIC);
            CoopFixtures.prepareCreate(store.repository, q2, c2);
            store.reopen();
            assertEquals(ProfileIntent.Phase.PREPARED, store.repository.intent(c2.op().operationId()).orElseThrow().phase());
            final PlayerAccount reserved = store.repository.loadAccount(p2, SLOTS);
            assertEquals(c2.op().operationId(), reserved.pendingIntent());
            assertEquals(1L, reserved.revision(), "installing the reservation advanced A once");
            assertTrue(store.repository.profile(c2.profile().id()).isEmpty(), "a PREPARED create published its profile");
            final CommitResult resolved = store.repository.resolveIntent(c2.op().operationId(),
                CoopFixtures.intentGuard(c2.op(), null, q2, store.repository.intent(c2.op().operationId()).orElseThrow()));
            assertEquals(CommitResult.Status.UNKNOWN, resolved.status(), "an undecided intent is reported, not guessed");
            final CommitResult aborted = CoopFixtures.abort(store.repository, c2.op(), null, q2);
            committed(aborted);
            assertEquals(ProfileReceipt.Outcome.ABORTED, aborted.receipt().outcome());
            final PlayerAccount cleared = store.repository.loadAccount(p2, SLOTS);
            assertNull(cleared.pendingIntent());
            assertEquals(2L, cleared.revision(), "clearing the reservation advanced A once more");
            assertTrue(cleared.profileIds().isEmpty());
            assertTrue(store.repository.profile(c2.profile().id()).isEmpty());
            assertEquals(CommitResult.Status.REPLAYED, CoopFixtures.abort(store.repository, c2.op(), null, q2).status());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> CoopFixtures.commit(store.repository, c2.op(), null, q2));

            // 3. Crash after the PREPARED intent was forced but before its reservations and player index: resolve
            //    installs the reservation and the intent can still commit.
            final UUID p3 = UUID.randomUUID();
            final PlayerClaim q3 = store.q(p3);
            final CoopFixtures.CreatePlan c3 = CoopFixtures.planCreate(store.repository, p3, "Coconut", CLASSIC);
            CoopFixtures.prepareCreate(store.repository, q3, c3);
            store.repository.close();
            Files.delete(store().resolve("accounts").resolve(p3 + ".awfp"));
            CoopFixtures.deleteTree(store().resolve("indexes").resolve(Integer.toString(ProfileIndexPage.Kind.INTENTS_BY_PLAYER.tag()))
                .resolve(p3.toString()));
            store.reopen();
            assertNull(store.repository.loadAccount(p3, SLOTS).pendingIntent());
            final ProfileIntent prepared3 = store.repository.intent(c3.op().operationId()).orElseThrow();
            assertEquals(CommitResult.Status.UNKNOWN, store.repository.resolveIntent(c3.op().operationId(),
                CoopFixtures.intentGuard(c3.op(), null, q3, prepared3)).status());
            assertEquals(c3.op().operationId(), store.repository.loadAccount(p3, SLOTS).pendingIntent(), "resolve installed it");
            committed(CoopFixtures.commit(store.repository, c3.op(), null, q3));
            assertEquals(2L, store.repository.loadAccount(p3, SLOTS).revision());
            assertTrue(store.repository.profile(c3.profile().id()).isPresent());

            // 4. Crash after the COMMITTED marker, before the after-images: open rolls it forward.
            final UUID p4 = UUID.randomUUID();
            final PlayerClaim q4 = store.q(p4);
            final CoopFixtures.CreatePlan c4 = CoopFixtures.planCreate(store.repository, p4, "Cucumber", CLASSIC);
            final ProfileIntent prepared4 = CoopFixtures.prepareCreate(store.repository, q4, c4);
            store.repository.close();
            writeIntent(prepared4.at(ProfileIntent.Phase.COMMITTED, "committed"));
            store.reopen();
            assertFinalizedCreate(store, c4);
            refusedWith(ProfileErrorCode.STALE_STATE, () -> CoopFixtures.abort(store.repository, c4.op(), null, q4));
            assertEquals(CommitResult.Status.REPLAYED, CoopFixtures.commit(store.repository, c4.op(), null, q4).status());

            // 5. Crash after the after-images were installed, before receipts and FINALIZED: open finishes it
            //    idempotently (A is not advanced a third time).
            final UUID p5 = UUID.randomUUID();
            final PlayerClaim q5 = store.q(p5);
            final CoopFixtures.CreatePlan c5 = CoopFixtures.planCreate(store.repository, p5, "Grapes", CLASSIC);
            final ProfileIntent prepared5 = CoopFixtures.prepareCreate(store.repository, q5, c5);
            CoopFixtures.copyTree(store().resolve("indexes"), scratch().resolve("indexes"));
            CoopFixtures.copyTree(store().resolve("staging"), scratch().resolve("staging"));
            committed(CoopFixtures.commit(store.repository, c5.op(), null, q5));
            store.repository.close();
            writeIntent(prepared5.at(ProfileIntent.Phase.COMMITTED, "committed"));
            CoopFixtures.copyTree(scratch().resolve("indexes"), store().resolve("indexes"));
            CoopFixtures.copyTree(scratch().resolve("staging"), store().resolve("staging"));
            Files.deleteIfExists(store().resolve("receipts").resolve(p5.toString()).resolve(c5.op().operationId() + ".awfp"));
            Files.deleteIfExists(store().resolve("receipts").resolve(c5.profile().id().toString())
                .resolve(c5.op().operationId() + ".awfp"));
            store.reopen();
            assertFinalizedCreate(store, c5);

            // Nothing is left pending for any phase.
            assertTrue(store.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, null)
                .entries().isEmpty());
        }
    }

    private void assertFinalizedCreate(final CoopFixtures.FileStore store, final CoopFixtures.CreatePlan plan) throws IOException {
        final UUID player = plan.accountBefore().playerId();
        final ProfileIntent intent = store.repository.intent(plan.op().operationId()).orElseThrow();
        assertEquals(ProfileIntent.Phase.FINALIZED, intent.phase());
        final PlayerAccount account = store.repository.loadAccount(player, SLOTS);
        assertEquals(plan.accountAfter(), account, "the account is exactly its after-image");
        assertEquals(2L, account.revision());
        assertNull(account.pendingIntent());
        final AuroraProfile profile = store.repository.profile(plan.profile().id()).orElseThrow();
        assertEquals(0L, profile.revision());
        assertEquals(plan.profile(), profile);
        final ProfileReceipt receipt = store.repository.receipt(player, plan.op().operationId()).orElseThrow();
        assertEquals(ProfileReceipt.Outcome.COMMITTED, receipt.outcome());
        assertTrue(store.repository.index(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, player, null, null, null).entries().isEmpty());
    }

    private void writeIntent(final ProfileIntent intent) throws IOException {
        final Path file = store().resolve("intents").resolve(intent.operationId() + ".awfp");
        FileProfileIo.writeDurable(file, ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(),
            ProfileRecords.intentPayload2(intent), file.toString()));
    }

    // ------------------------------------------------------------------ CoopLostCommitReplyReconciles

    /**
     * A caller that lost the reply retries with the same operation id: it gets the stored receipt (REPLAYED) and
     * nothing advances again; the same id with another request is refused.
     */
    @Test
    void CoopLostCommitReplyReconciles() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);

            final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(repository, player, "Kiwi", CLASSIC);
            CoopFixtures.prepareCreate(repository, q, plan);
            assertEquals(CommitResult.Status.REPLAYED, repository.prepareIntent(
                repository.intent(plan.op().operationId()).orElseThrow(),
                CoopWriteGuard.intent(plan.op(), null, q, CoopWriteGuard.ABSENT, plan.before(), Set.of())).status(),
                "a repeated prepare is answered from the stored intent");
            committed(CoopFixtures.commit(repository, plan.op(), null, q));
            final Map<String, String> settled = tree(store());
            final CommitResult again = CoopFixtures.commit(repository, plan.op(), null, q);
            assertEquals(CommitResult.Status.REPLAYED, again.status());
            assertNotNull(again.receipt());
            assertEquals(plan.op().operationId(), again.receipt().operationId());
            assertEquals(2L, repository.loadAccount(player, SLOTS).revision(), "a replayed commit advanced A");
            assertEquals(settled, tree(store()), "a replay changed the store");

            // Direct commit replay.
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            final CoopOperation limit = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
            final CoopWriteGuard guard = CoopWriteGuard.alias(limit, q, account.revision());
            committed(repository.commitAccount(account.withSlotLimit(4), guard));
            final CommitResult replay = repository.commitAccount(account.withSlotLimit(4), guard);
            assertEquals(CommitResult.Status.REPLAYED, replay.status());
            assertEquals(account.revision() + 1L, repository.loadAccount(player, SLOTS).revision());
            final CoopOperation forged = new CoopOperation(limit.operationId(), limit.kind(), player,
                CoopOperation.digest("another request"), 1L);
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> repository.commitAccount(account.withSlotLimit(5),
                CoopWriteGuard.alias(forged, q, account.revision())));
            final CoopOperation forgedIntent = new CoopOperation(plan.op().operationId(), plan.op().kind(), player,
                CoopOperation.digest("another create"), 1L);
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> repository.commitIntent(forgedIntent.operationId(),
                CoopFixtures.intentGuard(forgedIntent, null, q, repository.intent(plan.op().operationId()).orElseThrow())));
            assertEquals(account.revision() + 1L, repository.loadAccount(player, SLOTS).revision());
        }
    }

    // ------------------------------------------------------------------ CoopAllMemberJournalPins

    /**
     * A pending intent of another account pins an exact snapshot of a member stream: pruning keeps it until the
     * intent ends, then prunes it. Pins are the reverse index of all pending intents, not the owner's journals.
     */
    @Test
    void CoopAllMemberJournalPins() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final UUID owner = UUID.randomUUID();
            final PlayerClaim q = store.q(owner);
            final AuroraProfile profile = CoopFixtures.create(repository, q, "Lemon", CLASSIC);
            CoopFixtures.activate(repository, q, profile);
            final UUID epoch = profile.owner().epoch();
            final MemberStateHead first = CoopFixtures.save(repository, q, profile.id());
            final UUID pinned = first.snapshotId();

            final UUID other = UUID.randomUUID();
            final PlayerClaim otherQ = store.q(other);
            final CoopOperation pinOp = op(CoopOperation.Kind.ACCOUNT_LIMIT, other);
            final PlayerAccount otherAfter = PlayerAccount.empty(other, SLOTS).withSlotLimit(4).atRevision(2L);
            CoopFixtures.prepare(repository, pinOp, null, otherQ, List.of(Expected.account(other, 0L)),
                List.of(Expected.account(other, 1L)),
                List.of(new CoopFixtures.Image(ProfileRecords.TYPE_ACCOUNT, other, 2L, ProfileRecords.accountPayload2(otherAfter))),
                List.of(new ProfileIntent.SnapshotRef(profile.id(), owner, epoch, pinned)));
            assertEquals(List.of(new ProfileIntent.SnapshotRef(profile.id(), owner, epoch, pinned)),
                repository.pinnedSnapshots(profile.id(), owner, epoch));

            for (int i = 0; i < 5; i++) CoopFixtures.save(repository, q, profile.id());
            assertTrue(repository.memberSnapshot(profile.id(), owner, epoch, pinned).isPresent(),
                "a snapshot pinned by another account's pending intent was pruned");
            final Set<UUID> listed = new HashSet<>();
            for (final ProfileIndexPage.Entry entry : repository.index(ProfileIndexPage.Kind.SNAPSHOTS_BY_STREAM, profile.id(), owner,
                epoch, null).entries()) {
                listed.add(entry.id());
            }
            assertTrue(listed.contains(pinned));
            assertEquals(CoopFixtures.RETAIN + 1, listed.size(), "retained snapshots plus the pinned one");

            committed(CoopFixtures.abort(repository, pinOp, null, otherQ));
            assertTrue(repository.pinnedSnapshots(profile.id(), owner, epoch).isEmpty(), "an ended intent still pins");
            CoopFixtures.save(repository, q, profile.id());
            assertTrue(repository.memberSnapshot(profile.id(), owner, epoch, pinned).isEmpty(), "an unpinned old snapshot was kept");
        }
    }

    /** Exact lookup by (profile, member, epoch, snapshotId) never substitutes another snapshot, older or newer. */
    @Test
    void exactMemberLookupNeverSubstitutesAnotherSnapshot() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final UUID owner = UUID.randomUUID();
            final PlayerClaim q = store.q(owner);
            final AuroraProfile profile = CoopFixtures.create(repository, q, "Pear", CLASSIC);
            CoopFixtures.activate(repository, q, profile);
            final UUID epoch = profile.owner().epoch();
            final MemberStateHead h1 = CoopFixtures.save(repository, q, profile.id());
            final MemberStateHead h2 = CoopFixtures.save(repository, q, profile.id());
            final ProfileSnapshot s1 = repository.memberSnapshot(profile.id(), owner, epoch, h1.snapshotId()).orElseThrow();
            final ProfileSnapshot s2 = repository.memberSnapshot(profile.id(), owner, epoch, h2.snapshotId()).orElseThrow();
            assertTrue(h2.matches(s2));
            assertFalse(h2.matches(s1), "an older snapshot matched the head");
            final ProfileSnapshot newer = ProfileSnapshot.forMember(profile.id(), owner, epoch, 3L, CoopFixtures.state(), null);
            assertFalse(h2.matches(newer), "a newer snapshot matched the head");
            final ProfileSnapshot sameIdOtherChecksum = new ProfileSnapshot(profile.id(), owner, epoch, s2.snapshotId(),
                s2.schemaVersion(), s2.revision(), new byte[] {9}, ProfileStateCodec.checksum(new byte[] {9}), s2.createdAtMillis(), null);
            assertFalse(h2.matches(sameIdOtherChecksum));
            assertTrue(repository.memberSnapshot(profile.id(), owner, UUID.randomUUID(), h2.snapshotId()).isEmpty(),
                "another epoch's stream answered");
            assertTrue(repository.memberSnapshot(profile.id(), owner, epoch, UUID.randomUUID()).isEmpty());

            // A corrupted named snapshot is an error, never a fallback to the other one.
            final Path stream = store().resolve("snapshots").resolve(profile.id().toString()).resolve(owner.toString())
                .resolve(epoch.toString());
            final Path file = stream.resolve(h2.revision() + "-" + h2.snapshotId() + ".awfp");
            final byte[] bytes = Files.readAllBytes(file);
            bytes[bytes.length - 40] ^= 0x55;
            Files.write(file, bytes);
            assertThrows(IOException.class, () -> repository.memberSnapshot(profile.id(), owner, epoch, h2.snapshotId()));
        }
    }

    // ------------------------------------------------------------------ CoopGrowingFileReadBounded

    @Test
    void CoopGrowingFileReadBounded() throws Exception {
        // An unbounded stream whose size reads as 0: the read stops at limit + 1 and refuses.
        final Path zero = Path.of("/dev/zero");
        Assumptions.assumeTrue(Files.exists(zero), "needs /dev/zero");
        final IOException endless = assertThrows(IOException.class, () -> FileProfileIo.readBounded(zero, 4096));
        assertTrue(endless.getMessage().contains("limit"), endless.getMessage());

        final Path exact = this.directory.resolve("exact");
        Files.write(exact, new byte[1024]);
        assertEquals(1024, FileProfileIo.readBounded(exact, 1024).length);
        Files.write(exact, new byte[1025]);
        assertThrows(IOException.class, () -> FileProfileIo.readBounded(exact, 1024));

        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);
            final AuroraProfile profile = CoopFixtures.create(store.repository, q, "Lime", CLASSIC);
            // The record grew past its type's bound (a control record is at most 64 KiB plus framing).
            final Path account = store().resolve("accounts").resolve(player + ".awfp");
            final byte[] grown = new byte[ProfileRecords.maxPayload(ProfileRecords.TYPE_ACCOUNT) + ProfileRecords.OVERHEAD + 1];
            System.arraycopy(Files.readAllBytes(account), 0, grown, 0, (int) Files.size(account));
            Files.write(account, grown);
            final IOException refused = assertThrows(IOException.class, () -> store.repository.loadAccount(player, SLOTS));
            assertTrue(refused.getMessage().contains("limit"), refused.getMessage());
            // Other records stay readable.
            assertTrue(store.repository.profile(profile.id()).isPresent());
        }
    }

    // ------------------------------------------------------------------ CoopPagedIndexesRecover

    @Test
    void CoopPagedIndexesRecover() throws Exception {
        final int count = 40;
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final List<CoopFixtures.CreatePlan> plans = new ArrayList<>(count);
            final List<PlayerClaim> claims = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final UUID player = UUID.randomUUID();
                final PlayerClaim q = store.q(player);
                final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(store.repository, player, "Apple", CLASSIC);
                CoopFixtures.prepareCreate(store.repository, q, plan);
                plans.add(plan);
                claims.add(q);
            }
            assertEquals(ids(plans), listAll(store.repository));

            // Cursors are scoped and generation-checked; a foreign cursor is refused, never answered partially.
            final ProfileIndexPage page = store.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL,
                null, null, null);
            assertEquals(ProfileIndexPage.LISTING_PAGE, page.entries().size());
            final ProfileIndexPage.Cursor cursor = page.cursorAfterLast();
            assertEquals(cursor, ProfileIndexPage.Cursor.decode(cursor.encode()));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> store.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS,
                ProfileIndexPage.GLOBAL, null, null, new ProfileIndexPage.Cursor(cursor.kind(), cursor.resource(),
                    cursor.generation() + 1L, cursor.lastCreatedAtMillis(), cursor.lastId())));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> store.repository.index(ProfileIndexPage.Kind.INTENTS_BY_PLAYER,
                plans.getFirst().accountBefore().playerId(), null, null, cursor));
            assertThrows(IOException.class, () -> ProfileIndexPage.Cursor.decode("not a cursor"));

            // Crash of one prepare before its intent was forced (its staged copy and index entries exist, its intent,
            // reservation and player index do not): open removes exactly that entry; the others recover from the pages.
            final CoopFixtures.CreatePlan interrupted = plans.getFirst();
            final UUID interruptedPlayer = interrupted.accountBefore().playerId();
            store.repository.close();
            Files.delete(store().resolve("intents").resolve(interrupted.op().operationId() + ".awfp"));
            Files.delete(store().resolve("accounts").resolve(interruptedPlayer + ".awfp"));
            CoopFixtures.deleteTree(store().resolve("indexes").resolve(Integer.toString(ProfileIndexPage.Kind.INTENTS_BY_PLAYER.tag()))
                .resolve(interruptedPlayer.toString()));
            store.reopen();
            final List<UUID> remaining = ids(plans.subList(1, count));
            assertEquals(remaining, listAll(store.repository));
            assertEquals(List.of(plans.get(1).op().operationId()), store.repository.index(ProfileIndexPage.Kind.INTENTS_BY_PLAYER,
                plans.get(1).accountBefore().playerId(), null, null, null).entries().stream().map(ProfileIndexPage.Entry::id).toList());

            // Ending intents removes them from every page.
            for (int i = 1; i <= 10; i++) committed(CoopFixtures.abort(store.repository, plans.get(i).op(), null, claims.get(i)));
            assertEquals(ids(plans.subList(11, count)), listAll(store.repository));
        }
    }

    private static List<UUID> ids(final List<CoopFixtures.CreatePlan> plans) {
        return plans.stream().map(plan -> plan.op().operationId()).sorted().toList();
    }

    /** Every id of the global pending index through the paged listing; checks page bounds and order. */
    private static List<UUID> listAll(final ProfileRepository repository) throws IOException {
        final List<ProfileIndexPage.Entry> all = new ArrayList<>();
        ProfileIndexPage.Cursor cursor = null;
        for (int pages = 0; pages < 100; pages++) {
            final ProfileIndexPage page = repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null,
                cursor);
            assertTrue(page.entries().size() <= ProfileIndexPage.LISTING_PAGE);
            all.addAll(page.entries());
            cursor = page.cursorAfterLast();
            if (cursor == null || page.entries().size() < ProfileIndexPage.LISTING_PAGE) break;
        }
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1).compareTo(all.get(i)) < 0, "listing out of order");
        return all.stream().map(ProfileIndexPage.Entry::id).sorted().toList();
    }

    // ------------------------------------------------------------------ CoopCorruptIndexFailsClosed

    @Test
    void CoopCorruptIndexFailsClosed() throws Exception {
        final UUID owner = UUID.randomUUID();
        final AuroraProfile profile;
        final UUID epoch;
        final List<UUID> saved = new ArrayList<>();
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final FileProfileRepository repository = store.repository;
            final PlayerClaim q = store.q(owner);
            profile = CoopFixtures.create(repository, q, "Peach", CLASSIC);
            CoopFixtures.activate(repository, q, profile);
            epoch = profile.owner().epoch();
            saved.add(CoopFixtures.save(repository, q, profile.id()).snapshotId());

            // Another account's pending intent pins the owner's snapshot (a pin page and a pending page exist).
            final UUID other = UUID.randomUUID();
            final PlayerClaim otherQ = store.q(other);
            final PlayerAccount otherAfter = PlayerAccount.empty(other, SLOTS).withSlotLimit(4).atRevision(2L);
            CoopFixtures.prepare(repository, op(CoopOperation.Kind.ACCOUNT_LIMIT, other), null, otherQ,
                List.of(Expected.account(other, 0L)), List.of(Expected.account(other, 1L)),
                List.of(new CoopFixtures.Image(ProfileRecords.TYPE_ACCOUNT, other, 2L, ProfileRecords.accountPayload2(otherAfter))),
                List.of(new ProfileIntent.SnapshotRef(profile.id(), owner, epoch, saved.getFirst())));

            // A corrupt pin page: the pin read fails (never "no pins") and pruning keeps everything.
            corrupt(store().resolve("indexes").resolve(Integer.toString(ProfileIndexPage.Kind.PINS_BY_STREAM.tag()))
                .resolve(profile.id().toString()).resolve(owner.toString()).resolve(epoch.toString()).resolve("0.awfp"));
            assertThrows(IOException.class, () -> repository.pinnedSnapshots(profile.id(), owner, epoch));
            for (int i = 0; i < 5; i++) saved.add(CoopFixtures.save(repository, q, profile.id()).snapshotId());
            for (final UUID id : saved) {
                assertTrue(repository.memberSnapshot(profile.id(), owner, epoch, id).isPresent(),
                    "a snapshot was pruned while the pins were unknown");
            }

            // A corrupt pending-intent page: listings fail, and admission of a new intent is refused before any write.
            corrupt(store().resolve("indexes").resolve(Integer.toString(ProfileIndexPage.Kind.PENDING_INTENTS.tag()))
                .resolve(ProfileIndexPage.GLOBAL.toString()).resolve("0.awfp"));
            assertThrows(IOException.class, () -> repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL,
                null, null, null));
            final UUID third = UUID.randomUUID();
            final PlayerClaim thirdQ = store.q(third);
            final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(repository, third, "Plum", CLASSIC);
            final List<ProfileIntent.BlobRef> refs = CoopFixtures.stage(repository, plan.op(), plan.images());
            final Map<String, String> before = tree(store());
            final CoopWriteGuard guard = CoopWriteGuard.intent(plan.op(), null, thirdQ, CoopWriteGuard.ABSENT, plan.before(), Set.of());
            assertThrows(IOException.class, () -> repository.prepareIntent(ProfileIntent.prepared(guard, plan.after(), List.of(), refs,
                List.of(), 0L), guard));
            assertEquals(before, tree(store()), "a prepare against a corrupt index wrote something");
        }

        // A restart does not treat the corrupt index as empty: the engine refuses to start instead of admitting players.
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(store(),
            CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES), true)) {
            assertThrows(RuntimeException.class, harness::open);
        }
    }

    private static void corrupt(final Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " does not exist");
        final byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
    }
}
