package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.carrying;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.everyItem;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.item;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Engine;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.MemberKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cases for the Build 54 review fixes (b54-review P1-1, P2-2, P2-4, P2-5, P3-22) through the engine over a FILE store:
 * the salvage delivery hold that refuses saves and switches while a delivery runs; interest that credits only the
 * current members' personal banks and only when the type allows {@code bank.personal}, at most once per
 * {@code bank.interest-interval-hours} on the store clock; the lazy compaction of removed memberships' money into the
 * archived totals, which (with the first interest) raises the store to minimum build 54; and caller-supplied operation
 * ids that make a ledger call idempotent. The Build 54 verification fixes: a retry under a decided operation id is
 * answered from its receipt before any validation (balance moved, interest interval), and a claim's capture is bounded by
 * {@code switching.timeout-ms} and its freeze does not outlive a rejoin.
 *
 * <p>Deterministic: the store clock the engine reads is shifted by a fixed offset through the repository proxy, never
 * waited for; a held capture is a future the case completes itself; waits are bounded.</p>
 */
class Build54ReviewFixesTest {

    @TempDir
    Path directory;

    private final AtomicLong clockOffset = new AtomicLong();

    private Path store() {
        return this.directory.resolve("store");
    }

    private Engine engine() throws Exception {
        return engine(Build54Fixtures.settings());
    }

    private Engine engine(final ProfileSettings settings) throws Exception {
        final Engine h = new Engine(store(), settings);
        h.hooks.put("storeClockMillis", (args, proceed) -> (Long) proceed.call() + this.clockOffset.get());
        h.open();
        h.engine.storeWriter("Build 54 test");
        h.engine.bankPolicy(Build54Fixtures.COINS);
        return h;
    }

    private record Party(UUID owner, UUID member, AuroraProfile coop, AuroraProfile kiwi) {}

    private static Party party(final Engine h) throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final AuroraProfile coop = h.coop(owner, "Apple");
        final AuroraProfile kiwi = h.create(member, "Kiwi");
        h.admit(owner, coop.id(), member);
        return new Party(owner, member, h.profile(coop.id()), kiwi);
    }

    // ------------------------------------------------------------------ P1-1 the delivery hold

    /**
     * From the first step of a claim until the delivery is applied the claimant is held: {@code deliveryInProgress},
     * {@code switchInProgress} and {@code frozen} are true, a save of a capture is refused ({@code SWITCH_IN_PROGRESS}),
     * the profile save skips them, a switch is refused and a second claim is refused; the hold ends with the delivery and
     * saves work again.
     */
    @Test
    void theDeliveryHoldRefusesSavesAndSwitchesWhileItRuns() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            h.activate(p.member(), p.kiwi().id());
            h.online(p.member());
            h.switched(p.member(), p.coop().id());
            h.ports.live.put(p.member(), carrying(List.of(item(0, 1), item(1, 2)), List.of(), List.of(), List.of(), 1));
            join(h.engine.leave(p.member(), p.coop().id()));
            final ProfileSalvage record = h.salvage(p.coop().id()).getFirst();
            final AuroraProfile pear = h.create(p.owner(), "Pear");
            h.activate(p.owner(), p.coop().id());
            h.playing(p.owner());
            h.ports.live.put(p.owner(), level(5));

            final CompletableFuture<PlayerStatePort.CaptureResult> held = new CompletableFuture<>();
            final CountDownLatch entered = new CountDownLatch(1);
            h.ports.heldCapture.set(held);
            h.ports.captureEntered.set(entered);
            final CompletableFuture<ProfileEngine.SalvageDelivery> claim = h.engine.claimSalvage(p.owner(), record.id());
            assertTrue(entered.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the claim did not reach its capture");

            assertTrue(h.engine.deliveryInProgress(p.owner()));
            assertTrue(h.engine.switchInProgress(p.owner()));
            assertTrue(h.engine.frozen(p.owner()));
            final Map<String, String> before = tree(store());
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.saveCaptured(p.owner(),
                PlayerStatePort.CaptureResult.of(level(6)), h.engine.captureTicket(p.owner()))));
            final ProfileOperationException skipped = refusal(() -> join(h.engine.save(p.coop().id())));
            assertEquals(ProfileErrorCode.PLAYER_OFFLINE, skipped.code(), skipped.getMessage());
            assertTrue(skipped.getMessage().contains("salvage"), skipped.getMessage());
            final ProfileSwitchResult switched = join(h.engine.switchProfile(p.owner(), pear.id()));
            assertFalse(switched.success());
            assertEquals(ProfileErrorCode.SWITCH_IN_PROGRESS, switched.errorCode(), switched.message());
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(before, tree(store()), "a refused call wrote something while the delivery was held");

            held.complete(PlayerStatePort.CaptureResult.of(level(5)));
            final ProfileEngine.SalvageDelivery result = claim.get(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(2, result.delivered());
            assertTrue(result.applied());
            assertFalse(h.engine.deliveryInProgress(p.owner()), "the hold outlived a successful delivery");
            assertFalse(h.engine.frozen(p.owner()));
            assertEquals(2, everyItem(h.ports.state(p.owner())).size());
            assertTrue(h.save(p.owner(), h.ports.state(p.owner())), "saves stayed refused after the delivery");
            assertEquals(2, everyItem(h.stateOf(h.head(p.coop().id(), p.owner(), h.profile(p.coop().id()).member(p.owner()).epoch())))
                .size());
        }
    }

    // ------------------------------------------------------------------ P2-4 compaction, P2-2/P3-22 interest

    /**
     * A removed membership's personal balance and contributions are folded into the archived totals by the next bank
     * mutation (the per-member maps keep the current members only); the first such record raises the store to minimum
     * build 54. Interest credits the shared bank and the current members only (the archived money earns nothing), at
     * most once per interval on the store clock; a second posting within it is refused with nothing written.
     */
    @Test
    void compactionAndInterestRules() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            h.activate(p.owner(), p.coop().id());
            h.activate(p.member(), p.coop().id());
            join(h.engine.deposit(p.owner(), p.coop().id(), 1_000L));
            join(h.engine.depositPersonal(p.owner(), p.coop().id(), 100L));
            join(h.engine.depositPersonal(p.member(), p.coop().id(), 200L));
            join(h.engine.contribute(p.member(), p.coop().id(), "wheat", 30L));
            final MemberKey ownerKey = MemberKey.of(h.profile(p.coop().id()).member(p.owner()));
            final MemberKey memberKey = MemberKey.of(h.profile(p.coop().id()).member(p.member()));
            h.activate(p.member(), p.kiwi().id());
            join(h.engine.leave(p.member(), p.coop().id()));
            assertEquals(200L, h.file.shared(p.coop().id()).orElseThrow().personalBalance(memberKey), "the removal moved money");
            assertTrue(h.file.storeFormat().requiredBuild() < StoreFormat.SALVAGE_MINIMUM_BUILD, h.file.storeFormat().toString());

            join(h.engine.deposit(p.owner(), p.coop().id(), 10L));
            final ProfileSharedState compacted = h.file.shared(p.coop().id()).orElseThrow();
            assertEquals(Map.of(ownerKey, 100L), compacted.personalBalances(), "a removed membership kept its personal key");
            assertEquals(200L, compacted.archivedPersonal());
            assertEquals(Map.of("wheat", 30L), compacted.archivedContributions());
            assertFalse(compacted.contributions().containsKey("wheat"), "a removed membership kept its contribution ledger");
            assertEquals(30L, compacted.collections().get("wheat"), "the collection total changed");
            assertEquals(1_010L, compacted.sharedBalance());
            assertTrue(compacted.extended());
            assertEquals(StoreFormat.SALVAGE_MINIMUM_BUILD, h.file.storeFormat().requiredBuild(),
                "an extended shared record was written below minimum build 54");

            final ProfileBank.View first = join(h.engine.applyInterest(p.owner(), p.coop().id()));
            assertEquals(1_111L, first.sharedBalance());
            assertEquals(110L, first.personalBalance());
            final ProfileSharedState credited = h.file.shared(p.coop().id()).orElseThrow();
            assertEquals(200L, credited.archivedPersonal(), "archived money earned interest");
            assertTrue(credited.lastInterestAtMillis() > 0L);
            final Map<String, String> before = tree(store());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.applyInterest(p.owner(), p.coop().id())));
            this.clockOffset.addAndGet(23L * 60L * 60L * 1000L);
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.applyInterest(p.owner(), p.coop().id())));
            assertEquals(before, tree(store()), "a refused interest posting wrote something");
            this.clockOffset.addAndGet(60L * 60L * 1000L + 60_000L);
            assertEquals(1_111L + 111L, join(h.engine.applyInterest(p.owner(), p.coop().id())).sharedBalance());
        }

        // The record's Build 54 components: the Build 51 shape has none of them, equality includes them.
        final ProfileSharedState old = new ProfileSharedState(UUID.randomUUID(), 3L, Build54Fixtures.COINS.currency(), 5L, 0L, Map.of(),
            Map.of(), Map.of(), Map.of(), null);
        assertEquals(0L, old.archivedPersonal());
        assertTrue(old.archivedContributions().isEmpty());
        assertEquals(0L, old.lastInterestAtMillis());
        assertFalse(old.extended());
        assertNotEquals(old, old.withLastInterestAt(1L));
        assertTrue(old.withLastInterestAt(1L).extended());
        assertTrue(old.compacted(java.util.Set.of()) == old, "a record with nothing to fold was copied");
    }

    /**
     * Review P2-2: personal subaccounts earn interest only while the type allows {@code bank.personal}; the shared bank
     * still does.
     */
    @Test
    void interestSkipsPersonalBanksWhenTheTypeDeniesThem() throws Exception {
        ProfileTypes types = null;
        try (Engine h = engine()) {
            types = Build54Fixtures.openTypes(this.directory.resolve("types"), ProfileSettings.DEFAULT_TYPE_REGISTRY);
            final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("TypesPlugin");
            types.register(plugin, ProfileTypeDefinition.builder("frugal").build());
            final UUID owner = UUID.randomUUID();
            final AuroraProfile coop = h.convert(owner, h.create(ProfileRequest.of(owner, "skyblock").withTypeId("frugal").withName("Lime")));
            h.activate(owner, coop.id());
            join(h.engine.deposit(owner, coop.id(), 1_000L));
            join(h.engine.depositPersonal(owner, coop.id(), 500L));
            types.register(plugin, ProfileTypeDefinition.builder("frugal").deny(ProfileTypeRegistry.BANK_PERSONAL).build());
            final ProfileBank.View view = join(h.engine.applyInterest(owner, coop.id()));
            assertEquals(1_100L, view.sharedBalance());
            assertEquals(500L, view.personalBalance(), "a denied personal bank earned interest");
            assertEquals(100L, h.nextLedgerOf(ProfileBank.Entry.INTEREST).amount());
        } finally {
            Build54Fixtures.closeTypes(types);
        }
    }

    // ------------------------------------------------------------------ P2-5 caller-supplied operation ids

    /**
     * A ledger call under a caller-supplied operation id posts once: a retry with the same id and the same request returns
     * the committed bank without posting again and fires no second event (the documented contract, review P2-5); the
     * same id with another request is {@code INVALID_REQUEST}; the permanent receipt is found by the id.
     */
    @Test
    void aCallerOperationIdMakesALedgerCallIdempotent() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            h.activate(p.owner(), p.coop().id());
            final UUID id = UUID.randomUUID();
            assertEquals(25L, join(h.engine.deposit(p.owner(), p.coop().id(), 25L, id)).sharedBalance());
            final ProfileBank.LedgerEvent event = h.nextLedger();
            assertEquals(id, event.operationId());
            final Map<String, String> once = tree(store());

            assertEquals(25L, join(h.engine.deposit(p.owner(), p.coop().id(), 25L, id)).sharedBalance(), "a retry posted again");
            assertEquals(once, tree(store()), "a retry changed the store");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.deposit(p.owner(), p.coop().id(), 26L, id)));
            assertEquals(ProfileReceipt.Outcome.COMMITTED, join(h.engine.receipt(p.owner(), p.coop().id(), id)).orElseThrow().outcome());

            final UUID moved = UUID.randomUUID();
            join(h.engine.transfer(p.owner(), p.coop().id(), ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL, 5L, moved));
            final ProfileBank.View again = join(h.engine.transfer(p.owner(), p.coop().id(), ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 5L, moved));
            assertEquals(20L, again.sharedBalance(), "a retried transfer moved money again");
            assertEquals(5L, again.personalBalance());
            // Only the first deposit and the first transfer fired; the sentinel deposit's event is next.
            join(h.engine.deposit(p.owner(), p.coop().id(), 1L));
            assertEquals(ProfileBank.Entry.TRANSFER, h.nextLedger().entry());
            final ProfileBank.LedgerEvent sentinel = h.nextLedger();
            assertEquals(ProfileBank.Entry.DEPOSIT, sentinel.entry());
            assertEquals(1L, sentinel.amount(), "a retry fired a ledger event");
        }
    }

    // ------------------------------------------------------------------ verification: replay before validation

    /**
     * A retry under a decided operation id is answered from its receipt before any validation: a withdraw retried after
     * the balance moved below its amount, and a transfer retried after the shared bank was emptied, return REPLAYED (the
     * record as stored now) with nothing written, no ledger event and, for the transfer, no staged after-image left behind;
     * the same id with another amount or direction is {@code INVALID_REQUEST}, naming the id.
     */
    @Test
    void aRetryAfterTheBalanceMovedIsReplayed() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final UUID coop = p.coop().id();
            h.activate(p.owner(), coop);
            join(h.engine.deposit(p.owner(), coop, 100L));
            final UUID withdrawal = UUID.randomUUID();
            assertEquals(40L, join(h.engine.withdraw(p.owner(), coop, 60L, withdrawal)).sharedBalance());
            assertEquals(10L, join(h.engine.withdraw(p.owner(), coop, 30L)).sharedBalance());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.withdraw(p.owner(), coop, 60L)));

            final Map<String, String> before = tree(store());
            assertEquals(10L, join(h.engine.withdraw(p.owner(), coop, 60L, withdrawal)).sharedBalance(),
                "a retry of a committed withdraw was validated against the moved balance");
            assertEquals(before, tree(store()), "a replayed withdraw wrote something");
            final ProfileOperationException other = refusal(() -> join(h.engine.withdraw(p.owner(), coop, 61L, withdrawal)));
            assertEquals(ProfileErrorCode.INVALID_REQUEST, other.code(), other.getMessage());
            assertEquals(withdrawal, other.operationId());
            assertTrue(other.getMessage().contains("different request"), other.getMessage());

            final UUID moved = UUID.randomUUID();
            final ProfileBank.View out = join(h.engine.transfer(p.owner(), coop, ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL,
                5L, moved));
            assertEquals(5L, out.sharedBalance());
            assertEquals(5L, out.personalBalance());
            assertEquals(0L, join(h.engine.withdraw(p.owner(), coop, 5L)).sharedBalance());
            final Map<String, String> emptied = tree(store());
            final ProfileBank.View again = join(h.engine.transfer(p.owner(), coop, ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL,
                5L, moved));
            assertEquals(0L, again.sharedBalance(), "a retried transfer moved money again");
            assertEquals(5L, again.personalBalance());
            assertEquals(emptied, tree(store()), "a replayed transfer wrote something");
            assertFalse(Files.exists(store().resolve("staging").resolve(moved.toString())), "a replayed transfer left a staged after-image");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.transfer(p.owner(), coop, ProfileBank.Account.PERSONAL,
                ProfileBank.Account.SHARED, 5L, moved)));

            // Ledger events of the postings only, in order; the sentinel deposit's event comes right after them.
            join(h.engine.deposit(p.owner(), coop, 1L));
            final List<String> expected = List.of("DEPOSIT 100", "WITHDRAW 60", "WITHDRAW 30", "TRANSFER 5", "WITHDRAW 5", "DEPOSIT 1");
            for (final String entry : expected) {
                final ProfileBank.LedgerEvent event = h.nextLedger();
                assertEquals(entry, event.entry() + " " + event.amount(), "a replay fired a ledger event");
            }
        }
    }

    /**
     * Interest retried under its operation id inside {@code bank.interest-interval-hours} is REPLAYED (not refused as too
     * soon), and so is a retry after the interval: it never posts a second time. A new posting inside the interval is still
     * refused.
     */
    @Test
    void aRetriedInterestInsideTheIntervalIsReplayed() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final UUID coop = p.coop().id();
            h.activate(p.owner(), coop);
            join(h.engine.deposit(p.owner(), coop, 1_000L));
            final UUID interest = UUID.randomUUID();
            assertEquals(1_100L, join(h.engine.applyInterest(p.owner(), coop, interest)).sharedBalance());
            final Map<String, String> once = tree(store());

            assertEquals(1_100L, join(h.engine.applyInterest(p.owner(), coop, interest)).sharedBalance(),
                "a retried interest inside the interval was refused or posted again");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.applyInterest(p.owner(), coop)));
            this.clockOffset.addAndGet(25L * 60L * 60L * 1000L);
            assertEquals(1_100L, join(h.engine.applyInterest(p.owner(), coop, interest)).sharedBalance(),
                "a retried interest after the interval posted again");
            assertEquals(once, tree(store()), "a replayed interest wrote something");

            join(h.engine.deposit(p.owner(), coop, 1L));
            for (final String entry : List.of("DEPOSIT 1000", "INTEREST 100", "DEPOSIT 1")) {
                final ProfileBank.LedgerEvent event = h.nextLedger();
                assertEquals(entry, event.entry() + " " + event.amount(), "a replay fired a ledger event");
            }
        }
    }

    /**
     * A decided id whose actor is no longer a member cannot be matched to its request (the digest names the membership
     * epoch): {@code INVALID_REQUEST} naming the id, never {@code NOT_A_MEMBER} as if nothing had been posted; the receipt
     * still tells the outcome.
     */
    @Test
    void aRetryByARemovedMemberIsNotAnsweredAsNotPosted() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final UUID coop = p.coop().id();
            h.activate(p.member(), coop);
            final UUID id = UUID.randomUUID();
            assertEquals(7L, join(h.engine.deposit(p.member(), coop, 7L, id)).sharedBalance());
            h.activate(p.member(), p.kiwi().id());
            join(h.engine.leave(p.member(), coop));

            final ProfileOperationException gone = refusal(() -> join(h.engine.deposit(p.member(), coop, 7L, id)));
            assertEquals(ProfileErrorCode.INVALID_REQUEST, gone.code(), gone.getMessage());
            assertEquals(id, gone.operationId());
            assertTrue(gone.getMessage().contains("no longer a member"), gone.getMessage());
            assertEquals(ProfileReceipt.Outcome.COMMITTED, join(h.engine.receipt(null, coop, id)).orElseThrow().outcome());
            assertEquals(7L, h.file.shared(coop).orElseThrow().sharedBalance());
        }
    }

    // ------------------------------------------------------------------ verification: bounded claim capture

    /** {@link Build54Fixtures#settings()} with {@code switching.timeout-ms} as given. */
    private static ProfileSettings switchTimeout(final int timeoutMillis) {
        final ProfileSettings base = Build54Fixtures.settings();
        return new ProfileSettings(base.enabled(), base.mode(), base.defaultSlots(), base.maxSlots(),
            new ProfileSettings.Switching(true, timeoutMillis, base.switching().requireDurableSave()), base.isolation(), base.worlds(),
            base.coordination(), base.directory(), base.repository(), base.types(), base.names(), base.migration(), base.coop(),
            base.shutdown(), base.login(), base.adapters(), base.typeRegistry(), base.bank());
    }

    /** The member plays the co-op with two stacks and leaves; the owner plays the co-op at level 5. Returns the record. */
    private static ProfileSalvage salvageForTheOwner(final Engine h, final Party p) throws Exception {
        h.activate(p.member(), p.kiwi().id());
        h.online(p.member());
        h.switched(p.member(), p.coop().id());
        h.ports.live.put(p.member(), carrying(List.of(item(0, 1), item(1, 2)), List.of(), List.of(), List.of(), 1));
        join(h.engine.leave(p.member(), p.coop().id()));
        final ProfileSalvage record = h.salvage(p.coop().id()).getFirst();
        h.activate(p.owner(), p.coop().id());
        h.playing(p.owner());
        h.ports.live.put(p.owner(), level(5));
        return record;
    }

    /**
     * A claim whose capture never finishes fails {@code TIMEOUT} after {@code switching.timeout-ms} (here 3 s, the case's
     * only real-time wait, bounded by the fixtures' 10 s join): nothing is written, the salvage stays as it was, the freeze
     * and the delivery hold are released, and the next claim delivers.
     */
    @Test
    void aClaimCaptureTimeoutReleasesTheHold() throws Exception {
        try (Engine h = engine(switchTimeout(3_000))) {
            final Party p = party(h);
            final ProfileSalvage record = salvageForTheOwner(h, p);
            final Map<String, String> before = tree(store());

            final CountDownLatch entered = new CountDownLatch(1);
            h.ports.heldCapture.set(new CompletableFuture<>());
            h.ports.captureEntered.set(entered);
            final CompletableFuture<ProfileEngine.SalvageDelivery> claim = h.engine.claimSalvage(p.owner(), record.id());
            assertTrue(entered.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the claim did not reach its capture");
            final ProfileOperationException timedOut = refusal(() -> join(claim));
            assertEquals(ProfileErrorCode.TIMEOUT, timedOut.code(), timedOut.getMessage());

            assertFalse(h.engine.deliveryInProgress(p.owner()), "the delivery hold outlived a timed-out capture");
            assertFalse(h.engine.frozen(p.owner()), "the freeze outlived a timed-out capture");
            assertFalse(h.engine.switchInProgress(p.owner()));
            assertEquals(before, tree(store()), "a timed-out claim wrote something");
            assertEquals(record, h.salvageRecord(record.id()), "a timed-out claim changed the salvage record");

            final ProfileEngine.SalvageDelivery delivered = join(h.engine.claimSalvage(p.owner(), record.id()));
            assertEquals(2, delivered.delivered());
            assertTrue(delivered.complete());
        }
    }

    /**
     * A claimant who quits while the claim's capture is still running and joins again starts the new session unfrozen
     * ({@link ProfileEngine#admitSession} clears the stale freeze and hold); the old claim, finishing later, cannot commit
     * ({@code CLAIM_LOST}: its ticket names the old session) and does not touch the new session's state; a claim in the new
     * session delivers.
     */
    @Test
    void aRejoinClearsTheFreezeOfAnUnfinishedClaim() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final ProfileSalvage record = salvageForTheOwner(h, p);

            final CompletableFuture<PlayerStatePort.CaptureResult> held = new CompletableFuture<>();
            final CountDownLatch entered = new CountDownLatch(1);
            h.ports.heldCapture.set(held);
            h.ports.captureEntered.set(entered);
            final CompletableFuture<ProfileEngine.SalvageDelivery> claim = h.engine.claimSalvage(p.owner(), record.id());
            assertTrue(entered.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the claim did not reach its capture");
            assertFalse(h.quit(p.owner()), "the quit saved over a running delivery");
            assertTrue(h.engine.frozen(p.owner()), "the old claim's freeze was expected to remain until the next session");

            h.playing(p.owner());
            assertFalse(h.engine.frozen(p.owner()), "a new session started frozen by the previous session's claim");
            assertFalse(h.engine.deliveryInProgress(p.owner()));

            held.complete(PlayerStatePort.CaptureResult.of(level(5)));
            final ProfileOperationException lost = refusal(() -> join(claim));
            assertEquals(ProfileErrorCode.CLAIM_LOST, lost.code(), lost.getMessage());
            assertFalse(h.engine.frozen(p.owner()), "the old claim changed the new session's freeze");
            assertEquals(record, h.salvageRecord(record.id()), "the old claim changed the salvage record");

            assertEquals(2, join(h.engine.claimSalvage(p.owner(), record.id())).delivered());
        }
    }
}
