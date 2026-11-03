package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.carrying;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.everyItem;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.filled;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.item;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.sorted;
import static dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.tags;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Engine;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.update.StoreDowngradeGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 54 salvage owner cases (owner decision OD-3, COOP-DESIGN §7, §10 slice 6) through the engine over a FILE
 * store: a removed membership's items are delivered exactly once into a member who has the co-op active and plays it
 * here; a full inventory loses nothing (the rest stays in the salvage); a failed apply keeps the committed delivery; a
 * crash before COMMITTED aborts and one after it rolls forward without a second copy; a later save is never undone by a
 * replay; a removed member's next login does not bring revoked items back; and the first delivery raises the store to
 * minimum build 54 (Build 53 refused by the OD-2 gate).
 *
 * <p>Items are told apart by their bytes ({@link Build54Fixtures#item}); "exactly once" is asserted as the multiset of
 * item bytes a state holds. Deterministic: no sleeps; events are taken with bounded waits.</p>
 */
class CoopSalvageDeliveryTest {

    @TempDir
    Path directory;

    private Path serverDir() {
        return this.directory.resolve("server");
    }

    /** The FILE store where the OD-2 downgrade gate looks for it (the default directory below the server folder). */
    private Path store() {
        return serverDir().resolve(ProfileSettings.DIRECTORY_DEFAULT);
    }

    private Engine engine() throws Exception {
        final Engine h = new Engine(store(), Build54Fixtures.settings());
        h.open();
        h.engine.storeWriter("Build 54 test");
        return h;
    }

    /** The items the removed member left with: two storage stacks, armour, off-hand, ender chest. */
    private static ProfileState source() {
        return carrying(List.of(item(7, 2), item(3, 1)), List.of(item(100, 3)), List.of(item(40, 4)), List.of(item(5, 5)), 30);
    }

    /** {@link #source()}'s item bytes in extraction order: storage by slot, armour, off-hand, ender chest. */
    private static final List<String> SOURCE_ITEMS = tags(List.of(item(0, 1), item(0, 2), item(0, 3), item(0, 4), item(0, 5)));

    /** An owner's co-op "Apple" and a member who also owns the solo "Kiwi" (the member's active profile). */
    private record Party(UUID owner, UUID member, AuroraProfile coop, AuroraProfile kiwi) {}

    private static Party party(final Engine h) throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final AuroraProfile coop = h.coop(owner, "Apple");
        final AuroraProfile kiwi = h.create(member, "Kiwi");
        h.admit(owner, coop.id(), member);
        h.activate(member, kiwi.id());
        return new Party(owner, member, h.profile(coop.id()), kiwi);
    }

    /** The member plays the co-op here with {@link #source()} on them and leaves; returns the salvage record. */
    private static ProfileSalvage leaveWithItems(final Engine h, final Party p) throws Exception {
        h.online(p.member());
        h.switched(p.member(), p.coop().id());
        h.ports.live.put(p.member(), source());
        join(h.engine.leave(p.member(), p.coop().id()));
        final List<ProfileSalvage> salvage = h.salvage(p.coop().id());
        assertEquals(1, salvage.size(), "salvage: " + salvage);
        final ProfileSalvage record = salvage.getFirst();
        assertEquals(ProfileSalvage.State.UNAVAILABLE, record.state());
        assertNotEquals(ProfileSalvage.NIL, record.sourceSnapshotId());
        assertEquals(sorted(SOURCE_ITEMS), everyItem(h.snapshotState(p.coop().id(), record.removedMember(), record.removedEpoch(),
            record.sourceSnapshotId())), "the salvage source is not what the member left with");
        assertTrue(everyItem(h.ports.state(p.member())).isEmpty(), "the member kept the co-op items after leaving");
        return record;
    }

    /** The owner has the co-op active and plays it here, holding {@code state}. */
    private static void ownerPlays(final Engine h, final Party p, final ProfileState state) throws Exception {
        h.activate(p.owner(), p.coop().id());
        h.playing(p.owner());
        h.ports.live.put(p.owner(), state);
    }

    private static UUID epochOf(final Engine h, final UUID profileId, final UUID player) throws Exception {
        return h.profile(profileId).member(player).epoch();
    }

    /** Refused with {@code code}, and the store (marker included) is as it was. */
    private ProfileOperationException unchanged(final ProfileErrorCode code, final org.junit.jupiter.api.function.Executable call)
        throws Exception {
        final Map<String, String> before = records();
        final ProfileOperationException refused = refusal(call);
        assertEquals(code, refused.code(), refused.getMessage());
        assertEquals(before, records(), "a refused claim (" + code + ") wrote something");
        return refused;
    }

    /**
     * The whole store tree, marker included: since review P3-17 a refused claim does not raise the store level either
     * (the level is raised only for a delivery about to be written).
     */
    private Map<String, String> records() throws Exception {
        return tree(store());
    }

    // ------------------------------------------------------------------ delivery, exactly once, store level 54

    /**
     * A claim moves the removed member's items into the claimant's committed head and onto the player, in extraction
     * order into free storage slots; the record is DELIVERED naming claimant, epoch, destination and operation; the
     * permanent receipt exists; the delivery event fires once after the commit; a second claim is refused. The first
     * delivery raises the store to minimum build 54: the OD-2 gate then refuses Build 53 and passes Build 54.
     */
    @Test
    void salvageIsDeliveredExactlyOnceAndRaisesTheStoreToBuild54() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final ProfileSalvage record = leaveWithItems(h, p);
            assertTrue(h.file.storeFormat().requiredBuild() < StoreFormat.SALVAGE_MINIMUM_BUILD, h.file.storeFormat().toString());
            assertTrue(Build54Fixtures.downgradeRefusal("53", serverDir()).isEmpty(), "a store without a delivery refused Build 53");
            ownerPlays(h, p, level(5));
            final UUID ownerEpoch = epochOf(h, p.coop().id(), p.owner());

            final ProfileEngine.SalvageDelivery result = join(h.engine.claimSalvage(p.owner(), record.id()));
            assertEquals(5, result.delivered());
            assertEquals(0, result.remaining());
            assertTrue(result.complete());
            assertTrue(result.applied());
            assertEquals(p.coop().id(), result.profileId());
            assertEquals(p.owner(), result.claimant());

            final ProfileSalvage delivered = h.salvageRecord(record.id());
            assertEquals(ProfileSalvage.State.DELIVERED, delivered.state());
            assertEquals(p.owner(), delivered.claimant());
            assertEquals(ownerEpoch, delivered.claimantEpoch());
            assertEquals(result.operationId(), delivered.deliveryOperationId());
            assertEquals(record.revision() + 1L, delivered.revision());
            final MemberStateHead head = h.head(p.coop().id(), p.owner(), ownerEpoch);
            assertEquals(delivered.destinationSnapshotId(), head.snapshotId(), "the record does not name the installed head");
            final ProfileState committed = h.stateOf(head);
            assertEquals(SOURCE_ITEMS, tags(committed.inventory()), "the items are not in extraction order");
            for (int slot = 0; slot < 5; slot++) assertEquals(slot, committed.inventory().get(slot).slot());
            assertEquals(5, committed.level(), "the claimant's own state was not the base of the delivery");
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(p.owner())), "the committed state did not reach the player");
            assertTrue(everyItem(h.ports.state(p.member())).isEmpty());
            assertTrue(Files.isRegularFile(store().resolve("receipts").resolve(p.coop().id().toString())
                .resolve(FileProfileRepository.PERMANENT_RECEIPTS).resolve(result.operationId() + ".awfp")),
                "the delivery receipt is not permanent");
            assertEquals(result, h.nextDelivery());

            // Never granted twice.
            final Map<String, String> before = tree(store());
            final ProfileOperationException again = refusal(() -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(ProfileErrorCode.INVALID_REQUEST, again.code(), again.getMessage());
            assertTrue(again.getMessage().contains("nothing is granted twice"), again.getMessage());
            assertEquals(before, tree(store()));
            assertTrue(h.deliveries.isEmpty(), "a refused claim fired a delivery event");

            // Store level 54 and the OD-2 gate.
            assertEquals(StoreFormat.SALVAGE_MINIMUM_BUILD, h.file.storeFormat().requiredBuild());
            final Optional<String> refused53 = Build54Fixtures.downgradeRefusal("53", serverDir());
            assertTrue(refused53.isPresent(), "Build 53 passed a store with a delivered salvage");
            assertTrue(refused53.get().startsWith(StoreDowngradeGuard.REASON_TAG), refused53.get());
            assertTrue(refused53.get().contains("54"), refused53.get());
            assertTrue(Build54Fixtures.downgradeRefusal("54", serverDir()).isEmpty());
        }
        // This build reads the raised store again.
        try (Engine h = engine()) {
            assertEquals(StoreFormat.SALVAGE_MINIMUM_BUILD, h.file.storeFormat().requiredBuild());
        }
    }

    // ------------------------------------------------------------------ CoopSalvageActiveProfileRequired

    /**
     * OD-3: the claimant must be a member ({@code NOT_A_MEMBER}) with the co-op as the active profile
     * ({@code STALE_STATE}: no active profile, or another one) and an admitted online session here
     * ({@code PLAYER_OFFLINE}); an unknown record is {@code INVALID_REQUEST}. Each refusal leaves the records as they
     * were; the claim succeeds once all of it holds.
     */
    @Test
    void CoopSalvageActiveProfileRequired() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final ProfileSalvage record = leaveWithItems(h, p);

            unchanged(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), UUID.randomUUID())));
            unchanged(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.claimSalvage(UUID.randomUUID(), record.id())));
            assertNull(h.account(p.owner()).activeProfile(), "the owner was expected to have no active profile yet");
            final ProfileOperationException none = unchanged(ProfileErrorCode.STALE_STATE,
                () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertTrue(none.getMessage().contains("OD-3"), none.getMessage());
            final AuroraProfile pear = h.create(p.owner(), "Pear");
            h.activate(p.owner(), pear.id());
            unchanged(ProfileErrorCode.STALE_STATE, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            h.activate(p.owner(), p.coop().id());
            unchanged(ProfileErrorCode.PLAYER_OFFLINE, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(record, h.salvageRecord(record.id()), "a refused claim changed the salvage record");
            assertTrue(h.deliveries.isEmpty());

            h.playing(p.owner());
            h.ports.live.put(p.owner(), level(1));
            assertTrue(join(h.engine.claimSalvage(p.owner(), record.id())).complete());
        }
    }

    // ------------------------------------------------------------------ CoopSalvageRemovalRejoinNoDuplication

    /**
     * A member who leaves and is admitted again gets a new epoch: their stream starts empty (the old items stay in the
     * salvage), their old personal bank does not follow them, and the salvage they claim is delivered once; when they
     * leave again the delivered items are in the new salvage only, and the owner's claim of it holds every item exactly
     * once.
     */
    @Test
    void CoopSalvageRemovalRejoinNoDuplication() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            h.online(p.member());
            h.switched(p.member(), p.coop().id());
            final UUID first = epochOf(h, p.coop().id(), p.member());
            assertEquals(50L, join(h.engine.depositPersonal(p.member(), p.coop().id(), 50L)).personalBalance());
            h.ports.live.put(p.member(), source());
            join(h.engine.leave(p.member(), p.coop().id()));
            final ProfileSalvage s1 = h.salvage(p.coop().id()).getFirst();
            assertEquals(first, s1.removedEpoch());

            // Admitted again: a new epoch, an empty first entry, no inherited money.
            h.admit(p.owner(), p.coop().id(), p.member());
            final UUID second = epochOf(h, p.coop().id(), p.member());
            assertNotEquals(first, second, "a rejoin reused the removed epoch");
            h.switched(p.member(), p.coop().id());
            assertTrue(everyItem(h.ports.state(p.member())).isEmpty(), "the rejoined member got the removed epoch's items back");
            final ProfileBank.View bank = join(h.engine.bank(p.member(), p.coop().id()));
            assertEquals(0L, bank.personalBalance(), "the rejoined member inherited the removed epoch's personal bank");

            // The member claims the old salvage: once.
            final ProfileEngine.SalvageDelivery claimed = join(h.engine.claimSalvage(p.member(), s1.id()));
            assertEquals(5, claimed.delivered());
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(p.member())));
            assertEquals(second, h.salvageRecord(s1.id()).claimantEpoch());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.member(), s1.id())));

            // They leave again: the items are in the new salvage only.
            join(h.engine.leave(p.member(), p.coop().id()));
            assertTrue(everyItem(h.ports.state(p.member())).isEmpty());
            final List<ProfileSalvage> outstanding = new ArrayList<>();
            for (final ProfileSalvage record : h.salvage(p.coop().id())) if (record.claimable()) outstanding.add(record);
            assertEquals(1, outstanding.size(), "outstanding: " + outstanding);
            final ProfileSalvage s2 = outstanding.getFirst();
            assertEquals(second, s2.removedEpoch());
            assertEquals(ProfileSalvage.State.DELIVERED, h.salvageRecord(s1.id()).state());

            ownerPlays(h, p, level(2));
            join(h.engine.claimSalvage(p.owner(), s2.id()));
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(p.owner())), "an item is missing or doubled");
            assertTrue(everyItem(h.ports.state(p.member())).isEmpty());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), s1.id())));
        }
    }

    // ------------------------------------------------------------------ CoopRemovedNoActiveLoginClearsRevokedItems

    /**
     * A member whose only profile is the co-op plays it, quits and is removed while offline: the removal moves their
     * account to a fresh solo profile (they have no other), so the next authoritative login applies that profile's empty
     * initial state over the vanilla playerdata that still holds the co-op items: the revoked items are cleared from
     * the player and exist only in the salvage, from which the owner's claim takes them once.
     */
    @Test
    void CoopRemovedNoActiveLoginClearsRevokedItems() throws Exception {
        try (Engine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            h.activate(member, coop.id());
            assertEquals(List.of(coop.id()), h.account(member).profileIds(), "the co-op is the member's only profile");

            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, h.rejoin(member).outcome());
            h.ports.live.put(member, source());
            assertTrue(h.quit(member), "the quit did not save the co-op state");
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.stateOf(h.head(coop.id(), member, epochOf(h, coop.id(), member)))));

            join(h.engine.leave(member, coop.id()));
            final PlayerAccount account = h.account(member);
            assertFalse(account.owns(coop.id()));
            final UUID fresh = account.activeProfile();
            assertNotNull(fresh, "the removed member was left without an active profile");
            assertNotEquals(coop.id(), fresh);
            final ProfileSalvage record = h.salvage(coop.id()).getFirst();
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.snapshotState(coop.id(), member, record.removedEpoch(),
                record.sourceSnapshotId())));

            // The vanilla playerdata still holds the co-op items, and it is newer than anything stored.
            h.ports.live.put(member, source());
            h.ports.vanillaModified.put(member, System.currentTimeMillis() + 60_000L);
            final ProfileEngine.LoginRestore restore = h.rejoin(member);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(fresh, restore.profileId());
            assertTrue(everyItem(h.ports.state(member)).isEmpty(), "the revoked co-op items came back onto the removed member");

            ownerPlaysCoop(h, owner, coop.id());
            join(h.engine.claimSalvage(owner, record.id()));
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(owner)));
            assertTrue(everyItem(h.ports.state(member)).isEmpty());
        }
    }

    private static void ownerPlaysCoop(final Engine h, final UUID owner, final UUID coop) throws Exception {
        h.activate(owner, coop);
        h.playing(owner);
        h.ports.live.put(owner, level(3));
    }

    // ------------------------------------------------------------------ CoopSalvageApplyFailureRetainsDelivery

    /**
     * The delivery is committed before it is applied: when the apply to the player fails, the head, the DELIVERED record
     * and the receipt stand (nothing refunded or re-offered), the claim reports {@code applied = false}, the player's
     * saves are refused so no pre-delivery capture overwrites the head, and the next login restores the head with the
     * items.
     */
    @Test
    void CoopSalvageApplyFailureRetainsDelivery() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final ProfileSalvage record = leaveWithItems(h, p);
            ownerPlays(h, p, level(5));
            final UUID ownerEpoch = epochOf(h, p.coop().id(), p.owner());
            h.ports.failNextApply.set(new IllegalStateException("scripted apply failure"));

            final ProfileEngine.SalvageDelivery result = join(h.engine.claimSalvage(p.owner(), record.id()));
            assertFalse(result.applied(), "a failed apply was reported as applied");
            assertTrue(result.complete());
            assertEquals(ProfileSalvage.State.DELIVERED, h.salvageRecord(record.id()).state());
            final MemberStateHead head = h.head(p.coop().id(), p.owner(), ownerEpoch);
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.stateOf(head)));
            assertTrue(everyItem(h.ports.state(p.owner())).isEmpty(), "the failed apply changed the player");
            assertTrue(h.engine.savesRefused(p.owner()), "saves were not refused after the failed apply");
            // Review P1-1: the delivery hold stays until the session ends: no capture of the pre-delivery state is written.
            assertTrue(h.engine.deliveryInProgress(p.owner()), "the hold ended with the failed apply");
            assertTrue(h.engine.switchInProgress(p.owner()));
            assertTrue(h.engine.frozen(p.owner()));
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.saveCaptured(p.owner(),
                PlayerStatePort.CaptureResult.of(h.ports.state(p.owner())), h.engine.captureTicket(p.owner()))));
            assertEquals(head, h.head(p.coop().id(), p.owner(), ownerEpoch), "a pre-delivery capture overwrote the delivery");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), record.id())));

            // The quit's save is refused: the pre-delivery state on the player does not overwrite the head.
            assertFalse(h.quit(p.owner()), "a refused save was reported as saved");
            assertEquals(head, h.head(p.coop().id(), p.owner(), ownerEpoch));

            assertFalse(h.engine.deliveryInProgress(p.owner()), "the quit did not end the hold");
            final ProfileEngine.LoginRestore restore = h.rejoin(p.owner());
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(p.coop().id(), restore.profileId());
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(p.owner())), "the login did not restore the delivery");
        }
    }

    // ------------------------------------------------------------------ CoopSalvageCrashAfterCommitNoDuplicate

    /** The delivery plan the engine would run for the offline {@code owner}, PREPARED under fresh claims. */
    private record Prepared(CoopOperation op, ProfileSnapshot destination, ProfileIntent intent) {}

    private static Prepared prepareDelivery(final Engine h, final UUID owner, final UUID coop, final ProfileSalvage record)
        throws Exception {
        h.file.requireStoreFeature(StoreFormat.COOP_SCHEMA, StoreFormat.SALVAGE_MINIMUM_BUILD, CoopFixtures.BUILD);
        final ProfileSalvage salvage = h.salvageRecord(record.id());
        final AuroraProfile profile = h.profile(coop);
        final ProfileMember row = profile.member(owner);
        final PlayerAccount account = h.account(owner);
        final MemberStateHead head = h.file.memberHead(coop, owner, row.epoch()).orElse(null);
        final ProfileSnapshot source = h.file.memberSnapshot(coop, salvage.removedMember(), salvage.removedEpoch(),
            salvage.sourceSnapshotId()).orElseThrow();
        final ProfileSalvageDelivery.Merged merged = ProfileSalvageDelivery.merge(ProfileState.empty(),
            ProfileSalvageDelivery.extract(ProfileStateCodec.decode(source.state())));
        assertTrue(merged.remainder().isEmpty());
        final CoopOperation op = op(CoopOperation.Kind.SALVAGE_DELIVER, owner);
        final ProfileSnapshot destination = ProfileSnapshot.forMember(coop, owner, row.epoch(), head == null ? 0L : head.revision() + 2L,
            ProfileStateCodec.encode(merged.state()), op.operationId());
        final ProfileSalvage after = salvage.delivered(owner, row.epoch(), destination, op.operationId(), source);
        h.file.stageMemberSnapshot(destination);
        final CoopIntentPlan plan = ProfileSalvageDelivery.plan(op, profile, account, row, head, destination, salvage, after, null, 0L);
        final PlayerSessionOwnership.Grant q = join(h.players.claim(owner, UUID.randomUUID()));
        assertTrue(q.granted(), q.reason());
        final ProfileOwnership.TransactionGrant pGrant = join(h.profiles.claimTransaction(coop, UUID.randomUUID()));
        assertTrue(pGrant.granted(), pGrant.reason());
        final PlayerClaim actor = q.claim();
        final ProfileClaim profileClaim = pGrant.claim();
        return new Prepared(op, destination, CoopMembershipFixtures.preparePlan(h.file, plan, profileClaim, actor, null));
    }

    /**
     * A crash after PREPARE (no COMMITTED marker) is aborted at the next start: the salvage stays claimable, the
     * claimant's head is unchanged, and the claim afterwards delivers once. A crash after the COMMITTED marker is rolled
     * forward at the next start: the head names the destination, the record is DELIVERED, the next login restores the
     * items, and a new claim finds nothing to deliver: no second copy anywhere.
     */
    @Test
    void CoopSalvageCrashAfterCommitNoDuplicate() throws Exception {
        final Party p;
        final ProfileSalvage first;
        final ProfileSalvage second;
        final MemberStateHead untouched;
        final Prepared aborted;
        try (Engine h = engine()) {
            p = party(h);
            first = leaveWithItems(h, p);
            // A second member leaves too, so the two crash points have their own records.
            final UUID other = UUID.randomUUID();
            final AuroraProfile lime = h.create(other, "Lime");
            h.admit(p.owner(), p.coop().id(), other);
            h.activate(other, lime.id());
            h.online(other);
            h.switched(other, p.coop().id());
            h.ports.live.put(other, carrying(List.of(item(0, 9)), List.of(), List.of(), List.of(), 1));
            join(h.engine.leave(other, p.coop().id()));
            second = h.salvage(p.coop().id()).stream().filter(record -> record.removedMember().equals(other)).findFirst().orElseThrow();
            h.activate(p.owner(), p.coop().id());
            untouched = h.file.memberHead(p.coop().id(), p.owner(), epochOf(h, p.coop().id(), p.owner())).orElse(null);
            aborted = prepareDelivery(h, p.owner(), p.coop().id(), first);
        }
        // Crash before COMMITTED: aborted at the next start.
        try (Engine h = engine()) {
            assertTrue(h.engine.storeStatus().unresolvedIntents().isEmpty(), h.engine.storeStatus().toString());
            assertEquals(ProfileIntent.Phase.ABORTED, h.file.intent(aborted.op().operationId()).orElseThrow().phase());
            final ProfileSalvage kept = h.salvageRecord(first.id());
            assertEquals(ProfileSalvage.State.UNAVAILABLE, kept.state());
            assertEquals(first.revision(), kept.revision());
            assertNull(kept.claimant());
            assertEquals(untouched, h.file.memberHead(p.coop().id(), p.owner(), epochOf(h, p.coop().id(), p.owner())).orElse(null),
                "an uncommitted delivery moved the claimant's head");

            // Crash after COMMITTED: the second record, rolled forward at the next start.
            final Prepared decided = prepareDelivery(h, p.owner(), p.coop().id(), second);
            h.close();
            CoopMembershipFixtures.writeIntent(store(), decided.intent().at(ProfileIntent.Phase.COMMITTED, "committed"));
            try (Engine restarted = engine()) {
                assertTrue(restarted.engine.storeStatus().unresolvedIntents().isEmpty(), restarted.engine.storeStatus().toString());
                assertEquals(ProfileIntent.Phase.FINALIZED, restarted.file.intent(decided.op().operationId()).orElseThrow().phase());
                final ProfileSalvage rolled = restarted.salvageRecord(second.id());
                assertEquals(ProfileSalvage.State.DELIVERED, rolled.state());
                assertEquals(decided.destination().snapshotId(), rolled.destinationSnapshotId());
                final UUID ownerEpoch = epochOf(restarted, p.coop().id(), p.owner());
                final MemberStateHead head = restarted.head(p.coop().id(), p.owner(), ownerEpoch);
                assertEquals(decided.destination().snapshotId(), head.snapshotId());
                assertEquals(List.of(tags(List.of(item(0, 9))).getFirst()), everyItem(restarted.stateOf(head)));

                final ProfileEngine.LoginRestore restore = restarted.rejoin(p.owner());
                assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
                assertEquals(everyItem(restarted.stateOf(head)), everyItem(restarted.ports.state(p.owner())));
                refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(restarted.engine.claimSalvage(p.owner(), second.id())));

                // The aborted record is still claimable and is delivered once, on top of the rolled-forward head.
                join(restarted.engine.claimSalvage(p.owner(), first.id()));
                final List<String> expected = new ArrayList<>(SOURCE_ITEMS);
                expected.addAll(tags(List.of(item(0, 9))));
                assertEquals(sorted(expected), everyItem(restarted.ports.state(p.owner())), "an item is missing or doubled");
                assertEquals(sorted(expected), everyItem(restarted.stateOf(restarted.head(p.coop().id(), p.owner(), ownerEpoch))));
            }
        }
    }

    // ------------------------------------------------------------------ CoopSalvageFullInventoryNoLoss

    /**
     * No free slot: {@code CAPACITY_EXCEEDED}, nothing written, the salvage stays. Two free slots: two stacks are
     * delivered and the rest stays in the salvage (AVAILABLE, with an immutable remainder blob in the consumed source
     * stream); a later claim delivers the rest. Nothing is dropped, nothing doubled.
     */
    @Test
    void CoopSalvageFullInventoryNoLoss() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final ProfileSalvage record = leaveWithItems(h, p);
            ownerPlays(h, p, filled(ProfileSalvageDelivery.STORAGE_SLOTS, ProfileSalvageDelivery.ENDER_CHEST_SLOTS, 1_000, 4));
            final UUID ownerEpoch = epochOf(h, p.coop().id(), p.owner());

            final Map<String, String> before = records();
            final ProfileOperationException full = refusal(() -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, full.code(), full.getMessage());
            assertEquals(before, records(), "a claim with no free slot wrote something");
            assertTrue(h.file.storeFormat().requiredBuild() < StoreFormat.SALVAGE_MINIMUM_BUILD,
                "a refused claim raised the store level (review P3-17)");
            assertFalse(h.engine.deliveryInProgress(p.owner()), "a refused claim kept its delivery hold");
            assertFalse(h.engine.frozen(p.owner()));
            assertEquals(record, h.salvageRecord(record.id()));
            assertTrue(h.deliveries.isEmpty());

            // Two storage slots free: the first two stacks in extraction order.
            final ProfileState twoFree = filled(ProfileSalvageDelivery.STORAGE_SLOTS - 2, ProfileSalvageDelivery.ENDER_CHEST_SLOTS, 1_000, 4);
            h.ports.live.put(p.owner(), twoFree);
            final ProfileEngine.SalvageDelivery partial = join(h.engine.claimSalvage(p.owner(), record.id()));
            assertEquals(2, partial.delivered());
            assertEquals(3, partial.remaining());
            assertFalse(partial.complete());
            final ProfileSalvage available = h.salvageRecord(record.id());
            assertEquals(ProfileSalvage.State.AVAILABLE, available.state());
            assertNull(available.claimant());
            assertNotEquals(ProfileSalvage.NIL, available.payloadBlobId());
            final ProfileState remainder = h.snapshotState(p.coop().id(), available.removedMember(), available.removedEpoch(),
                available.payloadBlobId());
            assertEquals(SOURCE_ITEMS.subList(2, 5), tags(remainder.inventory()), "the remainder is not the stacks that did not fit");
            final ProfileState afterPartial = h.ports.state(p.owner());
            final List<String> expected = new ArrayList<>(everyItem(twoFree));
            expected.addAll(SOURCE_ITEMS.subList(0, 2));
            assertEquals(sorted(expected), everyItem(afterPartial));
            assertEquals(sorted(expected), everyItem(h.stateOf(h.head(p.coop().id(), p.owner(), ownerEpoch))));

            // The player clears room (the filler leaves, the delivered stacks stay); the rest arrives once.
            final List<ProfileState.Item> keep = new ArrayList<>();
            for (final ProfileState.Item stack : afterPartial.inventory()) {
                if (stack.slot() >= ProfileSalvageDelivery.STORAGE_SLOTS - 2) keep.add(stack);
            }
            final ProfileState room = carrying(keep, List.of(), List.of(), List.of(), 4);
            h.ports.live.put(p.owner(), room);
            final ProfileEngine.SalvageDelivery rest = join(h.engine.claimSalvage(p.owner(), record.id()));
            assertEquals(3, rest.delivered());
            assertEquals(0, rest.remaining());
            assertTrue(rest.complete());
            assertEquals(ProfileSalvage.State.DELIVERED, h.salvageRecord(record.id()).state());
            assertEquals(sorted(SOURCE_ITEMS), everyItem(h.ports.state(p.owner())), "an item is missing or doubled");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
        }
    }

    // ------------------------------------------------------------------ CoopSalvageReplayAfterLaterSaveDoesNotReapply

    /**
     * After a delivery the claimant plays on and saves (the items are used up); a repeated claim, a restart (recovery
     * finds nothing pending) and the next login never re-apply the delivery over the later save, and the delivery's
     * permanent receipt is still there.
     */
    @Test
    void CoopSalvageReplayAfterLaterSaveDoesNotReapply() throws Exception {
        final Party p;
        final ProfileSalvage record;
        final UUID operation;
        final MemberStateHead later;
        try (Engine h = engine()) {
            p = party(h);
            record = leaveWithItems(h, p);
            ownerPlays(h, p, level(5));
            operation = join(h.engine.claimSalvage(p.owner(), record.id())).operationId();
            final UUID ownerEpoch = epochOf(h, p.coop().id(), p.owner());
            final MemberStateHead delivered = h.head(p.coop().id(), p.owner(), ownerEpoch);

            h.ports.live.put(p.owner(), level(9));
            assertTrue(h.save(p.owner(), level(9)));
            later = h.head(p.coop().id(), p.owner(), ownerEpoch);
            assertEquals(delivered.revision() + 1L, later.revision());
            assertTrue(everyItem(h.stateOf(later)).isEmpty());

            final Map<String, String> before = tree(store());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(before, tree(store()));
            assertEquals(later, h.head(p.coop().id(), p.owner(), ownerEpoch));
            assertEquals(9, h.ports.levelOf(p.owner()));
            assertTrue(h.quit(p.owner()));
        }
        try (Engine h = engine()) {
            assertTrue(h.engine.storeStatus().unresolvedIntents().isEmpty());
            assertEquals(ProfileIntent.Phase.FINALIZED, h.file.intent(operation).orElseThrow().phase());
            assertEquals(ProfileReceipt.Outcome.COMMITTED, h.file.receipt(p.coop().id(), operation).orElseThrow().outcome());
            final UUID ownerEpoch = epochOf(h, p.coop().id(), p.owner());
            final MemberStateHead head = h.head(p.coop().id(), p.owner(), ownerEpoch);
            assertEquals(9, h.stateOf(head).level());
            assertTrue(everyItem(h.stateOf(head)).isEmpty(), "the restart re-applied the delivery");
            final ProfileEngine.LoginRestore restore = h.rejoin(p.owner());
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(9, h.ports.levelOf(p.owner()));
            assertTrue(everyItem(h.ports.state(p.owner())).isEmpty(), "the login re-applied the delivery");
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.claimSalvage(p.owner(), record.id())));
            assertEquals(ProfileSalvage.State.DELIVERED, h.salvageRecord(record.id()).state());
        }
    }
}
