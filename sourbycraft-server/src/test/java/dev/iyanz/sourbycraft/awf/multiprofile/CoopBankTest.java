package dev.iyanz.sourbycraft.awf.multiprofile;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Engine;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.Currency;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.MemberKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 54 co-op bank owner cases (COOP-DESIGN §8, §10 slice 6) over a FILE store, through the engine and at the
 * repository: exact minor-unit conservation over deposits, withdrawals, the shared/personal transfer and interest;
 * overflow and cap refusals that write nothing; CAS on the shared record; replay of a transfer intent and of a direct
 * mutation posting once; the OD-4 purse refusal; an UNKNOWN commit outcome settled from the store, never guessed; and
 * ledger events after the durable commit only.
 *
 * <p>Deterministic: no sleeps; ledger events are taken with bounded waits from the one FIFO event lane, so "the next
 * event is the one of the next successful call" proves a refused call fired nothing. The concurrency case asserts
 * conservation over whatever interleaving the two-thread I/O lane produces, never a particular one.</p>
 */
class CoopBankTest {

    @TempDir
    Path directory;

    private Path store() {
        return this.directory.resolve("store");
    }

    /** Scale-2 coins, shared cap 10 000.00, personal 20 % of it (2 000.00), interest 10 %. */
    private static final ProfileBank.Policy CENTS = new ProfileBank.Policy(new Currency("aurora", "coins", 2), 1_000_000L, 20, 10);

    /** An owner's co-op "Apple" with one member; both have it active. */
    private record Bank(UUID owner, UUID member, AuroraProfile coop) {}

    private static Bank bank(final Engine h, final ProfileBank.Policy policy) throws Exception {
        h.engine.bankPolicy(policy);
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final AuroraProfile coop = h.coop(owner, "Apple");
        h.admit(owner, coop.id(), member);
        h.activate(owner, coop.id());
        h.activate(member, coop.id());
        return new Bank(owner, member, h.profile(coop.id()));
    }

    private Engine engine() throws Exception {
        final Engine h = new Engine(store(), Build54Fixtures.settings());
        h.open();
        return h;
    }

    private static ProfileSharedState shared(final Engine h, final UUID profileId) throws Exception {
        return h.file.shared(profileId).orElseThrow(() -> new AssertionError("no shared record of " + profileId));
    }

    private static long personal(final Engine h, final AuroraProfile coop, final UUID player) throws Exception {
        return shared(h, coop.id()).personalBalance(MemberKey.of(h.profile(coop.id()).member(player)));
    }

    private static void ledger(final Engine h, final ProfileBank.Entry entry, final long amount, final long sharedAfter) throws Exception {
        final ProfileBank.LedgerEvent event = h.nextLedger();
        assertEquals(entry, event.entry(), event.toString());
        assertEquals(amount, event.amount(), event.toString());
        assertEquals(sharedAfter, event.sharedBalanceAfter(), event.toString());
        assertTrue(h.file.receipt(event.profileId(), event.operationId()).isPresent(), "no receipt of " + event);
    }

    // ------------------------------------------------------------------ CoopBankConservesMinorUnits

    /**
     * Every committed posting moves exact long minor units: shared plus every personal balance equals deposits minus
     * withdrawals at every step, a transfer moves money between the two without changing the total, interest is the
     * floored policy percentage of each balance, the decimal parser is exact at the currency's scale, and each posting
     * fires one ledger event (after its permanent receipt exists) while a refused call fires none.
     */
    @Test
    void CoopBankConservesMinorUnits() throws Exception {
        try (Engine h = engine()) {
            final Bank b = bank(h, CENTS);
            final UUID coop = b.coop().id();

            final ProfileBank.View first = join(h.engine.deposit(b.owner(), coop, 12_345L));
            assertEquals(12_345L, first.sharedBalance());
            assertEquals(CENTS.currency(), first.currency());
            assertEquals(1_000_000L, first.sharedCapacity(), "a new record takes the policy capacity");
            assertEquals(200_000L, first.personalCapacity(), "personal cap = 20 % of the shared capacity");
            assertEquals(0L, shared(h, coop).revision(), "a new shared record starts at 0");
            ledger(h, ProfileBank.Entry.DEPOSIT, 12_345L, 12_345L);

            // A refused call in between fires nothing and writes nothing.
            final Map<String, String> before = tree(store());
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.withdraw(b.member(), coop, 1L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.deposit(b.owner(), coop, 0L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.deposit(b.owner(), coop, -5L)));
            assertEquals(before, tree(store()), "a refused bank call wrote something");

            assertEquals(1_000L, join(h.engine.depositPersonal(b.member(), coop, 1_000L)).personalBalance());
            ledger(h, ProfileBank.Entry.DEPOSIT_PERSONAL, 1_000L, 12_345L);

            final ProfileBank.View out = join(h.engine.transfer(b.owner(), coop, ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL, 345L));
            assertEquals(12_000L, out.sharedBalance());
            assertEquals(345L, out.personalBalance());
            final ProfileBank.LedgerEvent moved = h.nextLedger();
            assertEquals(ProfileBank.Entry.TRANSFER, moved.entry());
            assertEquals(CoopOperation.Kind.BANK_TRANSFER, moved.kind());
            assertEquals(ProfileBank.Account.SHARED, moved.from());
            assertEquals(ProfileBank.Account.PERSONAL, moved.to());
            assertEquals(345L, moved.amount());
            assertTrue(h.file.receipt(coop, moved.operationId()).isPresent());
            // A member may move their own money in; only the OWNER or a MANAGER takes it out of the shared bank.
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.transfer(b.member(), coop, ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 1L)));
            assertEquals(12_400L, join(h.engine.transfer(b.member(), coop, ProfileBank.Account.PERSONAL, ProfileBank.Account.SHARED, 400L))
                .sharedBalance());
            ledger(h, ProfileBank.Entry.TRANSFER, 400L, 12_400L);

            assertEquals(10_400L, join(h.engine.withdraw(b.owner(), coop, 2_000L)).sharedBalance());
            ledger(h, ProfileBank.Entry.WITHDRAW, 2_000L, 10_400L);
            assertEquals(500L, join(h.engine.withdrawPersonal(b.member(), coop, 100L)).personalBalance());
            ledger(h, ProfileBank.Entry.WITHDRAW_PERSONAL, 100L, 10_400L);

            // Conservation: deposits 12 345 + 1 000, withdrawals 2 000 + 100.
            final long held = shared(h, coop).sharedBalance() + personal(h, b.coop(), b.owner()) + personal(h, b.coop(), b.member());
            assertEquals(12_345L + 1_000L - 2_000L - 100L, held);
            assertEquals(345L, personal(h, b.coop(), b.owner()));
            assertEquals(500L, personal(h, b.coop(), b.member()));

            // Interest: 10 % floored per balance, OWNER only: 1 040 + 34 + 50.
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.applyInterest(b.member(), coop)));
            final ProfileBank.View interest = join(h.engine.applyInterest(b.owner(), coop));
            assertEquals(11_440L, interest.sharedBalance());
            assertEquals(379L, interest.personalBalance());
            ledger(h, ProfileBank.Entry.INTEREST, 1_040L + 34L + 50L, 11_440L);
            final ProfileBank.View seen = join(h.engine.bank(b.member(), coop));
            assertEquals(Map.of(b.owner(), 379L, b.member(), 550L), seen.personalBalances());
            assertEquals(550L, seen.personalBalance());
            // S advanced once per direct posting and twice per transfer intent: 0,1 | 3 | 5 | 6,7 | 8.
            assertEquals(8L, shared(h, coop).revision());
            assertNull(shared(h, coop).pendingIntent());
            assertTrue(h.ledger.isEmpty(), "an extra ledger event: " + h.ledger);

            // Collections: a ledger per member, no money moves.
            join(h.engine.contribute(b.owner(), coop, "wheat", 64L));
            final ProfileBank.Collections collections = join(h.engine.contributeCollections(b.member(), coop, "wheat", 36L));
            assertEquals(Map.of("wheat", 100L), collections.totals());
            assertEquals(Map.of(b.owner(), 64L, b.member(), 36L), collections.contributions().get("wheat"));
            assertEquals(11_440L, shared(h, coop).sharedBalance(), "a contribution moved money");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.contribute(b.owner(), coop, "Wheat!", 1L)));
            assertEquals(collections, join(h.engine.collections(b.owner(), coop)));
        }

        // Exact decimal conversion at the currency's scale.
        final Currency cents = CENTS.currency();
        assertEquals(12_345L, ProfileBank.parseAmount("123.45", cents));
        assertEquals(12_300L, ProfileBank.parseAmount("123", cents));
        assertEquals(Long.MAX_VALUE, ProfileBank.parseAmount("92233720368547758.07", cents));
        for (final String bad : List.of("1.234", "0", "-1", "0.00", "abc", "", "92233720368547758.08")) {
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> ProfileBank.parseAmount(bad, cents));
        }
        assertEquals("123.45", ProfileBank.format(12_345L, cents));
        assertEquals("0.05", ProfileBank.format(5L, cents));
        assertEquals("7", ProfileBank.format(7L, Build54Fixtures.COINS.currency()));
    }

    // ------------------------------------------------------------------ CoopBankOverflowNoMutation

    /**
     * A deposit past the shared cap, a personal deposit or a transfer past the personal cap, a withdrawal of more than
     * the available balance and a {@code long} overflow without a cap are refused before anything is written: the store
     * tree, {@code S} and the balances are unchanged, no intent exists and no ledger event fires.
     */
    @Test
    void CoopBankOverflowNoMutation() throws Exception {
        final ProfileBank.Policy capped = new ProfileBank.Policy(new Currency("aurora", "coins", 0), 1_000L, 20, 10);
        try (Engine h = engine()) {
            final Bank b = bank(h, capped);
            final UUID coop = b.coop().id();
            join(h.engine.deposit(b.owner(), coop, 1_000L));
            h.nextLedger();
            final ProfileSharedState full = shared(h, coop);
            final Map<String, String> before = tree(store());

            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.deposit(b.owner(), coop, 1L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.depositPersonal(b.member(), coop, 201L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.transfer(b.owner(), coop, ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 201L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.transfer(b.owner(), coop, ProfileBank.Account.SHARED,
                ProfileBank.Account.SHARED, 1L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.withdraw(b.owner(), coop, 1_001L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.withdrawPersonal(b.member(), coop, 1L)));
            assertEquals(before, tree(store()), "a refused overflow wrote something");
            assertEquals(full, shared(h, coop));

            // At the caps exactly: allowed.
            assertEquals(200L, join(h.engine.transfer(b.owner(), coop, ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL, 200L))
                .personalBalance());
            assertEquals(800L, shared(h, coop).sharedBalance());
            assertEquals(ProfileBank.Entry.TRANSFER, h.nextLedger().entry());

            // Without a cap the long range is the bound: checked, never wrapped.
            h.engine.bankPolicy(Build54Fixtures.COINS);
            final UUID other = UUID.randomUUID();
            final AuroraProfile banana = h.coop(other, "Banana");
            h.activate(other, banana.id());
            assertEquals(Long.MAX_VALUE - 1L, join(h.engine.deposit(other, banana.id(), Long.MAX_VALUE - 1L)).sharedBalance());
            assertEquals(0L, shared(h, banana.id()).sharedCapacity(), "COINS has no shared cap");
            final Map<String, String> nearMax = tree(store());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.deposit(other, banana.id(), 2L)));
            assertEquals(nearMax, tree(store()));
            assertEquals(Long.MAX_VALUE, join(h.engine.deposit(other, banana.id(), 1L)).sharedBalance());
            assertEquals(Long.MAX_VALUE, join(h.engine.depositPersonal(other, banana.id(), Long.MAX_VALUE)).personalBalance());
            final Map<String, String> atMax = tree(store());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.depositPersonal(other, banana.id(), 1L)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.transfer(other, banana.id(), ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 1L)));
            assertEquals(atMax, tree(store()), "an overflow wrote something");
            assertEquals(Long.MAX_VALUE, shared(h, banana.id()).sharedBalance());
        }
    }

    // ------------------------------------------------------------------ CoopBankConcurrentCas

    /**
     * Two after-images computed from the same {@code S}: the first commits, the second (a consistent request: read
     * {@code S}, after-image at {@code S + 1}) is {@code STALE_STATE} and writes nothing; a writer that reads again
     * commits. Through the engine, concurrent deposits of distinct powers of two leave exactly the sum of the ones that
     * succeeded; every other one is refused with nothing written.
     */
    @Test
    void CoopBankConcurrentCas() throws Exception {
        try (Engine h = engine()) {
            final Bank b = bank(h, Build54Fixtures.COINS);
            final UUID coop = b.coop().id();
            join(h.engine.deposit(b.owner(), coop, 100L));
            final ProfileSharedState read = shared(h, coop);
            final long a = h.account(b.owner()).revision();
            final long m = h.profile(coop).revision();

            final PlayerSessionOwnership.Grant qGrant = join(h.players.claim(b.owner(), UUID.randomUUID()));
            assertTrue(qGrant.granted(), qGrant.reason());
            final PlayerClaim q = qGrant.claim();
            final ProfileOwnership.TransactionGrant pGrant = join(h.profiles.claimTransaction(coop, UUID.randomUUID()));
            assertTrue(pGrant.granted(), pGrant.reason());
            final ProfileClaim p = pGrant.claim();
            try {
                final ProfileSharedState left = read.depositShared(10L);
                final ProfileSharedState right = read.depositShared(20L);
                assertEquals(read.revision() + 1L, right.revision(), "the losing writer's request is consistent");
                committed(h.file.mutateShared(left, CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, b.owner()), p, q, a, m,
                    read.revision())));
                final Map<String, String> before = tree(store());
                refusedWith(ProfileErrorCode.STALE_STATE, () -> h.file.mutateShared(right,
                    CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, b.owner()), p, q, a, m, read.revision())));
                assertEquals(before, tree(store()), "a stale shared write changed the store");
                assertEquals(110L, shared(h, coop).sharedBalance());

                final ProfileSharedState again = shared(h, coop);
                committed(h.file.mutateShared(again.depositShared(20L), CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE,
                    b.owner()), p, q, a, m, again.revision())));
                assertEquals(130L, shared(h, coop).sharedBalance());
                assertEquals(read.revision() + 2L, shared(h, coop).revision());
            } finally {
                join(h.profiles.releaseTransaction(p));
                join(h.players.release(q));
            }

            // Through the engine, racing.
            final long start = shared(h, coop).sharedBalance();
            final List<CompletableFuture<ProfileBank.View>> racing = new ArrayList<>();
            final List<Long> amounts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final long amount = 1L << i;
                amounts.add(amount);
                racing.add(h.engine.deposit(i % 2 == 0 ? b.owner() : b.member(), coop, amount));
            }
            long succeeded = 0L;
            int commits = 0;
            for (int i = 0; i < racing.size(); i++) {
                try {
                    racing.get(i).get(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
                    succeeded += amounts.get(i);
                    commits++;
                } catch (final java.util.concurrent.ExecutionException failed) {
                    final ProfileOperationException refused = (ProfileOperationException) failed.getCause();
                    assertTrue(Set.of(ProfileErrorCode.STALE_STATE, ProfileErrorCode.OWNERSHIP_CONFLICT).contains(refused.code()),
                        refused.code() + ": " + refused.getMessage());
                }
            }
            assertTrue(commits >= 1, "no racing deposit committed");
            assertEquals(start + succeeded, shared(h, coop).sharedBalance(), "a racing deposit was lost or posted twice");
        }
    }

    // ------------------------------------------------------------------ CoopTransferReplayOnePosting

    /**
     * A transfer is one intent with a permanent receipt: a repeated COMMIT and a repeated PREPARE of the same operation
     * are answered REPLAYED from the store and post nothing again; a repeated direct mutation is REPLAYED the same way.
     * The receipt lives apart from the pruned receipts (FILE {@code receipts/<profile>/permanent/}).
     */
    @Test
    void CoopTransferReplayOnePosting() throws Exception {
        try (Engine h = engine()) {
            final Bank b = bank(h, CENTS);
            final UUID coop = b.coop().id();
            join(h.engine.deposit(b.owner(), coop, 500L));
            assertEquals(500L, h.nextLedger().amount());
            final PlayerSessionOwnership.Grant qGrant = join(h.players.claim(b.owner(), UUID.randomUUID()));
            assertTrue(qGrant.granted(), qGrant.reason());
            final PlayerClaim q = qGrant.claim();
            final ProfileOwnership.TransactionGrant pGrant = join(h.profiles.claimTransaction(coop, UUID.randomUUID()));
            assertTrue(pGrant.granted(), pGrant.reason());
            final ProfileClaim p = pGrant.claim();
            try {
                final AuroraProfile profile = h.profile(coop);
                final ProfileSharedState stored = shared(h, coop);
                final MemberKey key = MemberKey.of(profile.member(b.owner()));
                final ProfileSharedState after = ProfileBank.transferred(stored, key, ProfileBank.Account.SHARED,
                    ProfileBank.Account.PERSONAL, 120L, CENTS);
                final CoopOperation transfer = op(CoopOperation.Kind.BANK_TRANSFER, b.owner());
                final CoopIntentPlan plan = ProfileBank.transferPlan(transfer, profile, h.account(b.owner()), stored, true, after,
                    h.file.storeClockMillis() + 120_000L);
                CoopMembershipFixtures.runPlan(h.file, plan, p, q, null);

                final ProfileSharedState posted = shared(h, coop);
                assertEquals(380L, posted.sharedBalance());
                assertEquals(120L, posted.personalBalance(key));
                assertEquals(stored.revision() + 2L, posted.revision());
                assertNull(posted.pendingIntent(), "the commit left the reservation");
                assertEquals(ProfileIntent.Phase.FINALIZED, h.file.intent(transfer.operationId()).orElseThrow().phase());
                assertEquals(ProfileReceipt.Outcome.COMMITTED, h.file.receipt(coop, transfer.operationId()).orElseThrow().outcome());
                assertTrue(Files.isRegularFile(store().resolve("receipts").resolve(coop.toString())
                    .resolve(FileProfileRepository.PERMANENT_RECEIPTS).resolve(transfer.operationId() + ".awfp")),
                    "the transfer receipt is not kept apart from the pruned receipts");

                final Map<String, String> settled = tree(store());
                assertEquals(CommitResult.Status.REPLAYED, CoopMembershipFixtures.commitPlan(h.file, plan, p, q, null).status());
                assertEquals(CommitResult.Status.REPLAYED, h.file.prepareIntent(h.file.intent(transfer.operationId()).orElseThrow(),
                    plan.prepareGuard(p, q, null)).status());
                assertEquals(settled, tree(store()), "a replayed transfer changed the store");
                assertEquals(posted, shared(h, coop), "a replayed transfer posted again");

                // A direct mutation replayed with the same operation: REPLAYED, the balance is not doubled.
                final long a = h.account(b.owner()).revision();
                final long m = h.profile(coop).revision();
                final CoopWriteGuard guard = CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, b.owner()), p, q, a, m,
                    posted.revision());
                final ProfileSharedState deposited = posted.depositShared(7L);
                committed(h.file.mutateShared(deposited, guard));
                final Map<String, String> once = tree(store());
                assertEquals(CommitResult.Status.REPLAYED, h.file.mutateShared(deposited, guard).status());
                assertEquals(once, tree(store()));
                assertEquals(387L, shared(h, coop).sharedBalance());
            } finally {
                join(h.profiles.releaseTransaction(p));
                join(h.players.release(q));
            }

            // Through the engine: one transfer, one posting, one event, one permanent receipt.
            join(h.engine.transfer(b.owner(), coop, ProfileBank.Account.PERSONAL, ProfileBank.Account.SHARED, 20L));
            final ProfileBank.LedgerEvent event = h.nextLedger();
            assertEquals(20L, event.amount());
            assertEquals(407L, shared(h, coop).sharedBalance());
            assertEquals(100L, personal(h, b.coop(), b.owner()));
            assertEquals(ProfileReceipt.Outcome.COMMITTED, h.file.receipt(coop, event.operationId()).orElseThrow().outcome());
            assertEquals(ProfileIntent.Phase.FINALIZED, h.file.intent(event.operationId()).orElseThrow().phase());
        }
    }

    // ------------------------------------------------------------------ CoopPurseUnknownOutcomePending (OD-4)

    /**
     * Owner decision OD-4 replaces the purse half of this case: the purse stays per profile through its REQUIRED adapter
     * and the server has no transactional purse provider, so any transfer naming the purse is refused with
     * {@code PURSE_TRANSFER_UNSUPPORTED} before anything else is checked or written: no intent, no reservation, no
     * receipt, no event. (The purse prepare/abort/commit cases of slice 6 have nothing to run against.)
     */
    @Test
    void CoopPurseUnknownOutcomePending() throws Exception {
        try (Engine h = engine()) {
            final Bank b = bank(h, Build54Fixtures.COINS);
            final UUID coop = b.coop().id();
            join(h.engine.deposit(b.owner(), coop, 50L));
            h.nextLedger();
            final Map<String, String> before = tree(store());
            for (final ProfileBank.Account other : ProfileBank.Account.values()) {
                refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> join(h.engine.transfer(b.owner(), coop,
                    ProfileBank.Account.PURSE, other, 10L)));
                refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> join(h.engine.transfer(b.owner(), coop, other,
                    ProfileBank.Account.PURSE, 10L)));
            }
            // Before membership, profile or amount checks: an outsider and a missing profile get the same answer.
            refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> join(h.engine.transfer(UUID.randomUUID(), coop,
                ProfileBank.Account.PURSE, ProfileBank.Account.SHARED, -1L)));
            refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> join(h.engine.transfer(b.owner(), UUID.randomUUID(),
                ProfileBank.Account.SHARED, ProfileBank.Account.PURSE, 1L)));
            assertEquals(before, tree(store()), "a purse transfer wrote something");
            assertEquals(50L, shared(h, coop).sharedBalance());
            assertNull(shared(h, coop).pendingIntent());
            assertTrue(shared(h, coop).reserved().isEmpty());
            assertTrue(h.ledger.isEmpty(), "a refused purse transfer fired " + h.ledger);
        }
        refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> ProfileBank.requireBankAccount(ProfileBank.Account.PURSE));
        assertEquals(ProfileBank.Account.PURSE, ProfileBank.Account.parse("wallet"));
        assertEquals(ProfileBank.Account.SHARED, ProfileBank.Account.parse("CO-OP"));
        assertEquals(ProfileBank.Account.PERSONAL, ProfileBank.Account.parse("personal"));
        final ProfileSharedState empty = Build54Fixtures.COINS.empty(UUID.randomUUID());
        final MemberKey key = new MemberKey(UUID.randomUUID(), UUID.randomUUID());
        refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> ProfileBank.transferred(empty, key, ProfileBank.Account.PURSE,
            ProfileBank.Account.SHARED, 1L, Build54Fixtures.COINS));
    }

    // ------------------------------------------------------------------ bank admission and UNKNOWN outcomes

    /**
     * A bank call needs a co-op ({@code NOT_COOP}), a member ({@code NOT_A_MEMBER}) who has the co-op active
     * ({@code STALE_STATE}). An UNKNOWN commit reply is settled from the store: when the record shows the after-image the
     * call succeeds (once, with its event); when it does not, the call fails {@code UNKNOWN_OUTCOME}, nothing is retried
     * or compensated and no event fires.
     */
    @Test
    void bankAdmissionAndUnknownOutcomes() throws Exception {
        try (Engine h = engine()) {
            final Bank b = bank(h, Build54Fixtures.COINS);
            final UUID coop = b.coop().id();
            final AuroraProfile solo = h.create(b.owner(), "Kiwi");
            refusedWith(ProfileErrorCode.NOT_COOP, () -> join(h.engine.deposit(b.owner(), solo.id(), 1L)));
            refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.deposit(UUID.randomUUID(), coop, 1L)));
            h.activate(b.member(), h.create(b.member(), "Lime").id());
            final ProfileOperationException inactive = refusal(() -> join(h.engine.deposit(b.member(), coop, 1L)));
            assertEquals(ProfileErrorCode.STALE_STATE, inactive.code(), inactive.getMessage());
            assertTrue(inactive.getMessage().contains("active"), inactive.getMessage());

            // Lost reply, applied: settled from the receipt.
            h.hooks.put("mutateShared", (args, proceed) -> {
                proceed.call();
                return CommitResult.unknown(((CoopWriteGuard) args[1]).operation().operationId(), "scripted lost reply");
            });
            assertEquals(5L, join(h.engine.deposit(b.owner(), coop, 5L)).sharedBalance());
            assertEquals(5L, h.nextLedger().amount());
            // Lost write: not established, refused UNKNOWN_OUTCOME, no event, nothing compensated.
            h.hooks.put("mutateShared", (args, proceed) -> CommitResult.unknown(((CoopWriteGuard) args[1]).operation().operationId(),
                "scripted unknown"));
            final Map<String, String> before = tree(store());
            final UUID chosen = UUID.randomUUID();
            final ProfileOperationException unknown = refusal(() -> join(h.engine.deposit(b.owner(), coop, 7L, chosen)));
            assertEquals(ProfileErrorCode.UNKNOWN_OUTCOME, unknown.code(), unknown.getMessage());
            assertEquals(chosen, unknown.operationId(), "an UNKNOWN_OUTCOME does not name the operation to reconcile (review P2-5)");
            assertEquals(before, tree(store()));
            assertTrue(join(h.engine.receipt(b.owner(), coop, chosen)).isEmpty(), "an unconfirmed write has a receipt");
            h.hooks.clear();
            assertEquals(5L, shared(h, coop).sharedBalance());
            join(h.engine.deposit(b.owner(), coop, 1L));
            assertEquals(1L, h.nextLedger().amount(), "the UNKNOWN deposit fired an event");
            assertFalse(h.ledger.iterator().hasNext());
            assertNotNull(join(h.engine.bank(null, coop)), "the operator's view");
        }
    }
}
