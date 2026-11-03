package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopIntentPlan.Image;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileState.Item;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Salvage delivery (Build 54, owner decision OD-3; COOP-DESIGN §7): the deterministic movement of the isolated items a
 * removed membership left behind into a claimant's member stream, exactly once.
 *
 * <p>The source was consumed when the membership was removed (Build 52: head CONSUMED, salvage record pinning the exact
 * source snapshot). A claim <em>extracts</em> the item sections of that snapshot (or of the remainder blob of an earlier
 * partial delivery), <em>places</em> them into the free storage and ender-chest slots of the claimant's captured state,
 * and runs one intent that installs the claimant's head at the resulting destination snapshot together with the salvage
 * record's after-image (DELIVERED, or AVAILABLE with the remainder as its blob) and leaves the permanent receipt. Items
 * that do not fit stay in the salvage; nothing is dropped into the world and no independent item grant happens. The
 * destination and remainder snapshots are staged as immutable, checksum-named blobs before PREPARE
 * ({@link ProfileRepository#stageMemberSnapshot}) and pinned by the intent; only the committed head makes the destination
 * authoritative, and the claimant's isolated state is replaced from that committed snapshot afterwards.</p>
 */
final class ProfileSalvageDelivery {

    /** Vanilla main inventory storage slots the merge fills (hotbar and three rows). */
    static final int STORAGE_SLOTS = 36;
    /** Vanilla ender chest slots the merge fills after the storage. */
    static final int ENDER_CHEST_SLOTS = 27;

    private ProfileSalvageDelivery() {}

    /**
     * The isolated items of a state in deterministic order: storage by slot, armour, off-hand, ender chest by slot.
     * XP, bank, purse and adapter blobs are never salvage.
     */
    static List<Item> extract(final ProfileState state) {
        final List<Item> items = new ArrayList<>();
        items.addAll(sorted(state.inventory()));
        items.addAll(sorted(state.armor()));
        items.addAll(sorted(state.offhand()));
        items.addAll(sorted(state.enderChest()));
        return List.copyOf(items);
    }

    private static List<Item> sorted(final List<Item> items) {
        final List<Item> copy = new ArrayList<>(items);
        copy.sort(java.util.Comparator.comparingInt(Item::slot));
        return copy;
    }

    /** The result of a merge: the destination state and the items that did not fit (in their extraction order). */
    record Merged(ProfileState state, List<Item> placed, List<Item> remainder) {}

    /**
     * Places {@code salvage} into the free storage slots of {@code destination}, then its free ender-chest slots; one item
     * stack per free slot, never stacking onto an existing stack (deterministic and independent of item semantics).
     * The remainder keeps its order.
     */
    static Merged merge(final ProfileState destination, final List<Item> salvage) {
        final BitSet storageUsed = new BitSet(STORAGE_SLOTS);
        for (final Item item : destination.inventory()) {
            if (item.slot() < STORAGE_SLOTS) storageUsed.set(item.slot());
        }
        final BitSet enderUsed = new BitSet(ENDER_CHEST_SLOTS);
        for (final Item item : destination.enderChest()) {
            if (item.slot() < ENDER_CHEST_SLOTS) enderUsed.set(item.slot());
        }
        final List<Item> inventory = new ArrayList<>(destination.inventory());
        final List<Item> ender = new ArrayList<>(destination.enderChest());
        final List<Item> placed = new ArrayList<>();
        final List<Item> remainder = new ArrayList<>();
        for (final Item item : salvage) {
            final int storage = storageUsed.nextClearBit(0);
            if (storage < STORAGE_SLOTS && inventory.size() < ProfileState.MAX_ITEMS) {
                storageUsed.set(storage);
                inventory.add(new Item(storage, item.data()));
                placed.add(item);
                continue;
            }
            final int chest = enderUsed.nextClearBit(0);
            if (chest < ENDER_CHEST_SLOTS && ender.size() < ProfileState.MAX_ITEMS) {
                enderUsed.set(chest);
                ender.add(new Item(chest, item.data()));
                placed.add(item);
                continue;
            }
            remainder.add(item);
        }
        final ProfileState state = new ProfileState(destination.schema(), inventory, destination.armor(), destination.offhand(), ender,
            destination.level(), destination.experience(), destination.health(), destination.food(), destination.saturation(),
            destination.effects(), destination.spawn(), destination.returnPoint(), destination.adapters());
        return new Merged(state, List.copyOf(placed), List.copyOf(remainder));
    }

    /**
     * A state holding only {@code items} as storage entries with sequential slots (the remainder blob); everything else
     * empty. At most {@link ProfileState#MAX_ITEMS} items per section: the sections of one source hold at most
     * {@code 36 + 4 + 1 + 27}, so a remainder always fits.
     */
    static ProfileState remainderState(final List<Item> items) {
        final List<Item> inventory = new ArrayList<>(items.size());
        final List<Item> ender = new ArrayList<>();
        for (final Item item : items) {
            if (inventory.size() < ProfileState.MAX_ITEMS) {
                inventory.add(new Item(inventory.size(), item.data()));
            } else {
                ender.add(new Item(ender.size(), item.data()));
            }
        }
        final ProfileState empty = ProfileState.empty();
        return new ProfileState(empty.schema(), inventory, List.of(), List.of(), ender, 0, 0.0f, empty.health(), empty.food(),
            empty.saturation(), List.of(), null, null, java.util.Map.of());
    }

    /**
     * The delivery intent. Vector: the claimant's account and the profile's metadata compared; the profile's metadata
     * reserved as every co-op plan does (serialising deliveries against membership changes); the claimant's head reserved
     * (its after-image at {@code R + 2} is READY at {@code destination}; a stream without a head is ABSENT and its head
     * after-image at 0); the salvage record compared, its after-image at revision + 1. Pins keep the destination, the
     * remainder and the exact source snapshot alive while the intent is pending.
     *
     * @param destination the staged destination snapshot of the claimant's stream, revision {@code R + 2} (or 0)
     * @param remainder the staged remainder snapshot in the consumed source stream, or {@code null} when everything fits
     */
    static CoopIntentPlan plan(final CoopOperation op, final AuroraProfile profile, final PlayerAccount claimantAccount,
                               final ProfileMember claimant, final MemberStateHead head, final ProfileSnapshot destination,
                               final ProfileSalvage salvage, final ProfileSalvage after, final ProfileSnapshot remainder,
                               final long deadlineMillis) throws IOException {
        Objects.requireNonNull(op, "op");
        if (op.kind() != CoopOperation.Kind.SALVAGE_DELIVER) throw new IllegalArgumentException("a delivery plan is a SALVAGE_DELIVER");
        if (!op.actor().equals(claimant.playerId())) throw new IllegalArgumentException("the claimant is the actor");
        if (!destination.inStream(profile.id(), claimant.playerId(), claimant.epoch())) {
            throw new IllegalArgumentException("the destination snapshot is not in the claimant's stream");
        }
        if (after.revision() != salvage.revision() + 1L || !after.id().equals(salvage.id())) {
            throw new IllegalArgumentException("the salvage after-image is the record at revision + 1");
        }
        final long headRevision = head == null ? CoopWriteGuard.ABSENT : head.revision();
        final long headImageRevision = head == null ? 0L : head.revision() + 2L;
        if (destination.revision() != headImageRevision) {
            throw new IllegalArgumentException("the destination snapshot is at revision " + destination.revision() + ", the head installs "
                + headImageRevision);
        }
        final MemberStateHead headAfter = new MemberStateHead(profile.id(), claimant.playerId(), claimant.epoch(), headImageRevision,
            MemberStateHead.State.READY, destination.snapshotId(), destination.checksum(), null, null);
        final List<Expected> before = new ArrayList<>(4);
        final List<Expected> afterVector = new ArrayList<>(4);
        final List<Image> images = new ArrayList<>(2);
        before.add(Expected.account(claimant.playerId(), claimantAccount.revision()));
        afterVector.add(Expected.account(claimant.playerId(), claimantAccount.revision()));
        before.add(Expected.metadata(profile.id(), profile.revision()));
        afterVector.add(Expected.metadata(profile.id(), profile.revision() + 1L));
        before.add(Expected.head(profile.id(), claimant.playerId(), claimant.epoch(), headRevision));
        afterVector.add(Expected.head(profile.id(), claimant.playerId(), claimant.epoch(),
            head == null ? CoopWriteGuard.ABSENT : head.revision() + 1L));
        images.add(new Image(ProfileRecords.TYPE_MEMBER_HEAD, profile.id(), claimant.playerId(), claimant.epoch(), headImageRevision,
            ProfileRecords.memberHeadPayload2(headAfter)));
        before.add(Expected.record(salvage.id(), salvage.revision()));
        afterVector.add(Expected.record(salvage.id(), salvage.revision()));
        images.add(new Image(ProfileRecords.TYPE_SALVAGE, salvage.id(), null, null, after.revision(), ProfileRecords.salvagePayload2(after)));
        final List<SnapshotRef> pins = new ArrayList<>(3);
        pins.add(SnapshotRef.of(destination));
        if (remainder != null) pins.add(SnapshotRef.of(remainder));
        if (!ProfileSalvage.NIL.equals(salvage.sourceSnapshotId())) {
            pins.add(new SnapshotRef(profile.id(), salvage.removedMember(), salvage.removedEpoch(), salvage.sourceSnapshotId()));
        }
        if (salvage.state() == ProfileSalvage.State.AVAILABLE && !ProfileSalvage.NIL.equals(salvage.payloadBlobId())) {
            pins.add(new SnapshotRef(profile.id(), salvage.removedMember(), salvage.removedEpoch(), salvage.payloadBlobId()));
        }
        return new CoopIntentPlan(op, profile.id(), null, before, afterVector, images, pins, deadlineMillis,
            new CoopIntentPlan.Result(salvage.id(), claimant.epoch(), null, salvage.id()));
    }
}
