package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.IRONMAN;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner cases of COOP-DESIGN §10 slice 3 about the format-2 record layout: golden bytes, schema refusal and the
 * OD-2 downgrade gate.
 *
 * <p>The golden payloads below were written by hand from the field order documented in COOP-DESIGN §4 and the
 * {@code *2} codecs (schema u16, big-endian numbers, UUIDs as two longs, presence bytes, int counts, int-length
 * UTF-8); the frame digests are the SHA-256 of those payloads computed outside the JVM. They are not produced by
 * the encoder under test, so an accidental change of order, width, tag or presence encoding fails here.</p>
 */
class CoopFormat2RecordsTest {

    @TempDir
    Path directory;

    private static final HexFormat HEX = HexFormat.of();

    // Hand-built payloads (see the class comment) and the SHA-256 of each.
    private static final String ACCOUNT_PAYLOAD = "0001" + "00000000000000000000000000000001" + "0000000000000005" + "00000003"
        + "00000001" + "00000000000000000000000000000002" + "01" + "00000005" + "6170706c65"
        + "01" + "00000000000000000000000000000002" + "01" + "00000000000000000000000000000003" + "0000000000000007" + "00"
        + "00000000" + "01";
    private static final String ACCOUNT_SHA = "a08d3cf87b59d9ff8aff023aa050e5093b0b8bd71da4c53549a55d09e22cdb65";

    private static final String PROFILE_PAYLOAD = "0001" + "00000000000000000000000000000002" + "0000000000000004"
        + "00000000000000000000000000000001" + "00000008" + "736b79626c6f636b" + "02" + "00000005" + "4170706c65"
        + "00000000000003e8" + "01" + "00000001" + "00000001" + "6b" + "00000001" + "76" + "00000001"
        + "00000000000000000000000000000001" + "00000000000000000000000000000003" + "01" + "00000000000003e8"
        + "00000000000007d0" + "0000000000000000" + "00";
    private static final String PROFILE_SHA = "dd23a6560fead5a2c9f5581092cab76be272f1a1afbeaac03f8af71e604a6971";

    private static final String HEAD_PAYLOAD = "0001" + "00000000000000000000000000000002" + "0000000000000009"
        + "00000000000000000000000000000001" + "00000000000000000000000000000003" + "02" + "01"
        + "00000000000000000000000000000004" + "01" + "00000004" + "63616665" + "00" + "01" + "00000000000000000000000000000005";
    private static final String HEAD_SHA = "020ee4faf45197af9c77b5a92f15c4c106bb125b319492d88b47ea2b8f9eea4b";

    private static final String INDEX_PAYLOAD = "0001" + "01" + "00000009" + "00000000000000000000000000000000" + "00" + "00"
        + "0000000000000001" + "00000000" + "0000000000000002" + "00000002" + "0000000000000064"
        + "00000000000000000000000000000007" + "0000000000000000" + "00000000000000c8" + "00000000000000000000000000000006"
        + "0000000000000003";
    private static final String INDEX_SHA = "483656e452f86fcf952ca65dbae2e9befc9b0bae546e1e8be68ad35a732f8deb";

    private static final String RECEIPT_PAYLOAD = "0001" + "02" + "00000000000000000000000000000001" + "00000000"
        + "0000000000000000" + "00000001" + "00000000000000000000000000000008" + "11".repeat(32) + "00000001" + "01"
        + "00000000" + "0000000000000bb8";
    private static final String RECEIPT_SHA = "607c16993779f4cf539c4f21fd4153d00b9346e5e4f22356e8eba4ef0e3ce397";

    /** SHA-256 of the state bytes {@code 01 02 03}, as the hex text the snapshot stores. */
    private static final String STATE_CHECKSUM = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
    private static final String SNAPSHOT_PAYLOAD = "0001" + "00000000000000000000000000000002" + "0000000000000009" + "01"
        + "00000000000000000000000000000001" + "01" + "00000000000000000000000000000003" + "00000000000000000000000000000004"
        + "00000001" + "00000000000003e8" + "00000040" + HEX.formatHex(STATE_CHECKSUM.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        + "00" + "00000003" + "010203";
    private static final String SNAPSHOT_SHA = "ea8aa0bf84d92788de0e07b8b5d094a3a4ee64b2df5f70af240f7fdf04d16648";

    private static String frame(final int type, final long revision, final String payload, final String sha) {
        return "41574652" + "02" + String.format("%02x", type) + String.format("%016x", revision)
            + String.format("%08x", payload.length() / 2) + payload + sha;
    }

    private static PlayerAccount goldenAccount() {
        final Map<UUID, String> aliases = new LinkedHashMap<>();
        aliases.put(u(2), "apple");
        return new PlayerAccount(u(1), List.of(u(2)), aliases, u(2), u(3), 3, 5L, 7L, null, List.of(), true);
    }

    private static AuroraProfile goldenProfile() {
        return new AuroraProfile(u(2), u(1), "skyblock", IRONMAN, "Apple", Map.of("k", "v"), 1000L, 4L, ProfileStatus.ACTIVE,
            List.of(new ProfileMember(u(1), u(3), ProfileRole.OWNER, 1000L, 2000L)), 0L, null);
    }

    private static MemberStateHead goldenHead() {
        return new MemberStateHead(u(2), u(1), u(3), 9L, MemberStateHead.State.READY, u(4), "cafe", null, u(5));
    }

    private static ProfileIndexPage goldenIndex() {
        return new ProfileIndexPage(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, 1L, 0, 2L,
            List.of(new ProfileIndexPage.Entry(100L, u(7), 0L), new ProfileIndexPage.Entry(200L, u(6), 3L)));
    }

    private static ProfileReceipt.Page goldenReceipts() {
        final byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) 0x11);
        return new ProfileReceipt.Page(u(1), 0, 0L, List.of(new ProfileReceipt(u(8), digest, CoopOperation.Kind.CREATE_PROFILE,
            ProfileReceipt.Outcome.COMMITTED, new byte[0], 3000L)));
    }

    private static ProfileSnapshot goldenSnapshot() {
        return new ProfileSnapshot(u(2), u(1), u(3), u(4), 1, 9L, new byte[] {1, 2, 3}, STATE_CHECKSUM, 1000L, null);
    }

    // ------------------------------------------------------------------ CoopFormat2GoldenRoundTrip

    @Test
    void CoopFormat2GoldenRoundTrip() throws IOException {
        // Exact payload and frame bytes.
        assertEquals(ACCOUNT_PAYLOAD, HEX.formatHex(ProfileRecords.accountPayload2(goldenAccount())), "account layout");
        assertEquals(PROFILE_PAYLOAD, HEX.formatHex(ProfileRecords.profilePayload2(goldenProfile())), "profile layout");
        assertEquals(HEAD_PAYLOAD, HEX.formatHex(ProfileRecords.memberHeadPayload2(goldenHead())), "member head layout");
        assertEquals(INDEX_PAYLOAD, HEX.formatHex(ProfileRecords.indexPagePayload2(goldenIndex())), "index page layout");
        assertEquals(RECEIPT_PAYLOAD, HEX.formatHex(ProfileRecords.receiptPagePayload2(goldenReceipts())), "receipt page layout");
        assertEquals(SNAPSHOT_PAYLOAD, HEX.formatHex(ProfileRecords.memberSnapshotPayload2(goldenSnapshot())), "snapshot layout");

        assertEquals(frame(1, 5L, ACCOUNT_PAYLOAD, ACCOUNT_SHA), HEX.formatHex(ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT,
            5L, ProfileRecords.accountPayload2(goldenAccount()), "golden")));
        assertEquals(frame(2, 4L, PROFILE_PAYLOAD, PROFILE_SHA), HEX.formatHex(ProfileRecords.frame2(ProfileRecords.TYPE_PROFILE,
            4L, ProfileRecords.profilePayload2(goldenProfile()), "golden")));
        assertEquals(frame(11, 9L, HEAD_PAYLOAD, HEAD_SHA), HEX.formatHex(ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_HEAD,
            9L, ProfileRecords.memberHeadPayload2(goldenHead()), "golden")));
        assertEquals(frame(13, 2L, INDEX_PAYLOAD, INDEX_SHA), HEX.formatHex(ProfileRecords.frame2(ProfileRecords.TYPE_PAGE,
            2L, ProfileRecords.indexPagePayload2(goldenIndex()), "golden")));
        assertEquals(frame(13, 0L, RECEIPT_PAYLOAD, RECEIPT_SHA), HEX.formatHex(ProfileRecords.frame2(ProfileRecords.TYPE_PAGE,
            0L, ProfileRecords.receiptPagePayload2(goldenReceipts()), "golden")));
        assertEquals(frame(10, 9L, SNAPSHOT_PAYLOAD, SNAPSHOT_SHA), HEX.formatHex(ProfileRecords.frame2(
            ProfileRecords.TYPE_MEMBER_SNAPSHOT, 9L, ProfileRecords.memberSnapshotPayload2(goldenSnapshot()), "golden")));

        // The golden frames decode (from the hand-written bytes, not from the encoder) to equal records.
        assertEquals(goldenAccount(), ProfileRecords.account2(unframe(frame(1, 5L, ACCOUNT_PAYLOAD, ACCOUNT_SHA),
            ProfileRecords.TYPE_ACCOUNT), u(1), "golden"));
        assertEquals(goldenProfile(), ProfileRecords.profile2(unframe(frame(2, 4L, PROFILE_PAYLOAD, PROFILE_SHA),
            ProfileRecords.TYPE_PROFILE), u(2), "golden"));
        assertEquals(goldenHead(), ProfileRecords.memberHead2(unframe(frame(11, 9L, HEAD_PAYLOAD, HEAD_SHA),
            ProfileRecords.TYPE_MEMBER_HEAD), u(2), u(1), u(3), "golden"));
        assertEquals(goldenIndex(), ProfileRecords.indexPage2(unframe(frame(13, 2L, INDEX_PAYLOAD, INDEX_SHA),
            ProfileRecords.TYPE_PAGE), ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, "golden"));
        final ProfileReceipt.Page receipts = ProfileRecords.receiptPage2(unframe(frame(13, 0L, RECEIPT_PAYLOAD, RECEIPT_SHA),
            ProfileRecords.TYPE_PAGE), u(1), "golden");
        assertEquals(1, receipts.receipts().size());
        final ProfileReceipt receipt = receipts.receipts().getFirst();
        assertEquals(u(8), receipt.operationId());
        assertEquals(CoopOperation.Kind.CREATE_PROFILE, receipt.kind());
        assertEquals(ProfileReceipt.Outcome.COMMITTED, receipt.outcome());
        assertEquals(3000L, receipt.decidedAtMillis());
        final ProfileSnapshot snapshot = ProfileRecords.memberSnapshot2(unframe(frame(10, 9L, SNAPSHOT_PAYLOAD, SNAPSHOT_SHA),
            ProfileRecords.TYPE_MEMBER_SNAPSHOT), u(2), u(1), u(3), "golden");
        assertEquals(u(4), snapshot.snapshotId());
        assertArrayEquals(new byte[] {1, 2, 3}, snapshot.state());
        assertTrue(goldenHead().revision() == snapshot.revision());

        // Stable numeric tags (never ordinals).
        assertEquals(1, dev.iyanz.sourbycraft.awf.multiprofile.ProfileType.tag(CLASSIC));
        assertEquals(2, dev.iyanz.sourbycraft.awf.multiprofile.ProfileType.tag(IRONMAN));
        assertEquals(3, dev.iyanz.sourbycraft.awf.multiprofile.ProfileType.tag(CoopFixtures.STRANDED));
        assertEquals(4, dev.iyanz.sourbycraft.awf.multiprofile.ProfileType.tag(CoopFixtures.BINGO));
        assertEquals(List.of(1, 2, 3, 4, 5), List.of(CoopWriteGuard.Domain.ACCOUNT.tag(), CoopWriteGuard.Domain.METADATA.tag(),
            CoopWriteGuard.Domain.SHARED.tag(), CoopWriteGuard.Domain.HEAD.tag(), CoopWriteGuard.Domain.RECORD.tag()));
        assertEquals(List.of(1, 2, 3, 4, 5), java.util.Arrays.stream(ProfileIntent.Phase.values()).map(ProfileIntent.Phase::tag).toList());
        assertEquals(List.of(1, 2, 3), java.util.Arrays.stream(MemberStateHead.State.values()).map(MemberStateHead.State::tag).toList());
        assertEquals(1, CoopOperation.Kind.CREATE_PROFILE.tag());
        assertEquals(8, CoopOperation.Kind.MIGRATION.tag());
        assertEquals(22, CoopOperation.Kind.INVITE_ACCEPT.tag());

        // Every type round-trips through its codec, also the ones without a golden constant.
        final ProfileIntent intent = sampleIntent();
        final byte[] intentFrame = ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(),
            ProfileRecords.intentPayload2(intent), "intent");
        final ProfileIntent decoded = ProfileRecords.intent2(ProfileRecords.unframe(intentFrame, ProfileRecords.FORMAT_2,
            ProfileRecords.TYPE_INTENT, "intent"), intent.operationId(), "intent");
        assertEquals(intent.operationId(), decoded.operationId());
        assertEquals(intent.expectedBefore(), decoded.expectedBefore());
        assertEquals(intent.expectedAfter(), decoded.expectedAfter());
        assertEquals(intent.playerClaims(), decoded.playerClaims());
        assertEquals(intent.pins(), decoded.pins());
        assertEquals(intent.afterImages().size(), decoded.afterImages().size());
        assertArrayEquals(intent.afterImages().getFirst().sha256(), decoded.afterImages().getFirst().sha256());
        assertArrayEquals(intent.requestDigest(), decoded.requestDigest());
        assertEquals(intent.phase(), decoded.phase());
        assertArrayEquals(intentFrame, ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, decoded.revision(),
            ProfileRecords.intentPayload2(decoded), "intent"), "intent re-encodes to the same bytes");

        final ProfileSharedState shared = ProfileSharedState.empty(u(2), new ProfileSharedState.Currency("test", "coins", 2), 500L);
        final byte[] sharedFrame = ProfileRecords.frame2(ProfileRecords.TYPE_SHARED, 0L, ProfileRecords.sharedPayload2(shared), "s");
        assertEquals(shared, ProfileRecords.shared2(ProfileRecords.unframe(sharedFrame, ProfileRecords.FORMAT_2,
            ProfileRecords.TYPE_SHARED, "s"), u(2), "s"));

        final ProfileWorldBinding binding = new ProfileWorldBinding(u(2), Map.of("island", ProfileWorldRole.PRIMARY), "template");
        final byte[] bindingFrame = ProfileRecords.frame2(ProfileRecords.TYPE_BINDING, 3L,
            ProfileRecords.bindingPayload2(binding, 3L, null), "b");
        final ProfileRecords.Binding2 binding2 = ProfileRecords.binding2(ProfileRecords.unframe(bindingFrame, ProfileRecords.FORMAT_2,
            ProfileRecords.TYPE_BINDING, "b"), u(2), "b");
        assertEquals(binding.worlds(), binding2.binding().worlds());
        assertEquals(3L, binding2.revision());

        // The marker file layout is fixed too.
        final StoreFormat.Marker marker = new StoreFormat.Marker(2, StoreFormat.MigrationState.READY, 1, 4096L, "b51");
        assertEquals("41574646" + "00000002" + "00000003" + "00000001" + "0000000000001000" + "00000003" + "623531",
            HEX.formatHex(StoreFormat.encode(marker)));
        assertEquals(marker, StoreFormat.decode(StoreFormat.encode(marker), "marker"));
    }

    private static ProfileRecords.Frame unframe(final String hex, final byte type) throws IOException {
        return ProfileRecords.unframe(HEX.parseHex(hex), ProfileRecords.FORMAT_2, type, "golden");
    }

    private static ProfileIntent sampleIntent() {
        final PlayerClaim q = new PlayerClaim(u(1), "node-a", "inc", u(9), 3L, 0L);
        final CoopOperation op = new CoopOperation(u(10), CoopOperation.Kind.CREATE_PROFILE, u(1), CoopOperation.digest("x"), 5L);
        final CoopWriteGuard guard = CoopWriteGuard.intent(op, null, q, CoopWriteGuard.ABSENT,
            List.of(Expected.account(u(1), 4L), Expected.metadata(u(2), CoopWriteGuard.ABSENT)), java.util.Set.of());
        final ProfileIntent.BlobRef blob = new ProfileIntent.BlobRef(ProfileRecords.TYPE_PROFILE, u(2), null, null, 0L, 12,
            ProfileRecords.sha256(new byte[12]));
        return ProfileIntent.prepared(guard, List.of(Expected.account(u(1), 5L), Expected.metadata(u(2), CoopWriteGuard.ABSENT)),
            List.of(), List.of(blob), List.of(new ProfileIntent.SnapshotRef(u(2), u(1), u(3), u(4))), 0L);
    }

    // ------------------------------------------------------------------ CoopUnknownSchemaRefusesWrite

    @Test
    void CoopUnknownSchemaRefusesWrite() throws Exception {
        // Record level: a payload of schema 2 is refused, not skipped or truncated.
        final byte[] payload = ProfileRecords.accountPayload2(goldenAccount());
        payload[0] = 0;
        payload[1] = 2;
        final byte[] newer = ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, 5L, payload, "newer");
        final IOException refused = assertThrows(IOException.class, () -> ProfileRecords.account2(
            ProfileRecords.unframe(newer, ProfileRecords.FORMAT_2, ProfileRecords.TYPE_ACCOUNT, "newer"), u(1), "newer"));
        assertTrue(refused.getMessage().contains("schema"), refused.getMessage());
        // Trailing bytes, duplicate metadata keys and unknown tags are refused as well.
        final byte[] trailing = java.util.Arrays.copyOf(ProfileRecords.accountPayload2(goldenAccount()), payload.length + 1);
        assertThrows(IOException.class, () -> ProfileRecords.account2(ProfileRecords.unframe(ProfileRecords.frame2(
            ProfileRecords.TYPE_ACCOUNT, 5L, trailing, "t"), ProfileRecords.FORMAT_2, ProfileRecords.TYPE_ACCOUNT, "t"), u(1), "t"));
        final byte[] badType = HEX.parseHex(PROFILE_PAYLOAD.replaceFirst("736b79626c6f636b02", "736b79626c6f636b09"));
        assertThrows(IOException.class, () -> ProfileRecords.profile2(ProfileRecords.unframe(ProfileRecords.frame2(
            ProfileRecords.TYPE_PROFILE, 4L, badType, "p"), ProfileRecords.FORMAT_2, ProfileRecords.TYPE_PROFILE, "p"), u(2), "p"));
        final byte[] wrongRevision = ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, 6L, ProfileRecords.accountPayload2(goldenAccount()), "r");
        assertThrows(IOException.class, () -> ProfileRecords.account2(ProfileRecords.unframe(wrongRevision, ProfileRecords.FORMAT_2,
            ProfileRecords.TYPE_ACCOUNT, "r"), u(1), "r"), "the header revision must equal the payload revision");

        // Store level: a marker with a newer schema or format refuses the store; nothing is written.
        assertEquals(StoreFormat.Gate.REFUSED, StoreFormat.gate(new StoreFormat.Marker(2, StoreFormat.MigrationState.READY, 2, 1L, "x")).gate());
        assertEquals(StoreFormat.Gate.REFUSED, StoreFormat.gate(new StoreFormat.Marker(3, StoreFormat.MigrationState.READY, 1, 1L, "x")).gate());
        assertEquals(StoreFormat.Gate.REFUSED, StoreFormat.gate(StoreFormat.Marker.migrating("x")).gate());
        assertEquals(StoreFormat.Gate.LEGACY, StoreFormat.gate(StoreFormat.Marker.legacy()).gate());
        assertEquals(StoreFormat.Gate.FORMAT_2, StoreFormat.gate(StoreFormat.Marker.ready("x")).gate());

        final UUID player = UUID.randomUUID();
        final AuroraProfile profile;
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final PlayerClaim q = store.q(player);
            profile = CoopFixtures.create(store.repository, q, "Lime", CLASSIC);
        }
        Files.write(this.directory.resolve(StoreFormat.FILE_NAME), StoreFormat.encode(
            new StoreFormat.Marker(2, StoreFormat.MigrationState.READY, 2, 1L, "a newer build")));
        final Map<String, String> before = tree(this.directory);
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.open(this.directory)) {
            final PlayerClaim q = store.q(player);
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.loadAccount(player, SLOTS));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.commitAccount(
                PlayerAccount.empty(player, SLOTS).withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, 2L)));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.stageAfterImage(UUID.randomUUID(),
                ProfileRecords.TYPE_PROFILE, profile.id(), null, null, 0L, ProfileRecords.profilePayload2(profile)));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD)));
        }
        assertEquals(before, tree(this.directory), "a store with a newer schema was written to");

        // An unparseable marker is refused too, never read as "legacy".
        Files.write(this.directory.resolve(StoreFormat.FILE_NAME), new byte[] {1, 2, 3});
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.open(this.directory)) {
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.loadAccount(player, SLOTS));
        }
        assertThrows(IOException.class, () -> StoreFormat.decode(new byte[] {1, 2, 3}, "m"));
    }

    // ------------------------------------------------------------------ CoopOldWriterDowngradeBlocked

    @Test
    void CoopOldWriterDowngradeBlocked() throws Exception {
        // Per record: a format-1 reader refuses every format-2 frame.
        final byte[] frame2 = ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, 5L, ProfileRecords.accountPayload2(goldenAccount()), "a");
        assertThrows(IOException.class, () -> ProfileRecords.unframe(frame2, ProfileRecords.FORMAT, ProfileRecords.TYPE_ACCOUNT, "a"));
        // And a frame of an unknown format is refused by this build.
        final byte[] format3 = frame2.clone();
        format3[4] = 3;
        assertThrows(IOException.class, () -> ProfileRecords.unframe(format3, ProfileRecords.TYPE_ACCOUNT, "a"));

        final UUID player = UUID.randomUUID();
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final FileProfileRepository repository = store.repository;
            final PlayerClaim q = store.q(player);
            final AuroraProfile profile = CoopFixtures.create(repository, q, "Mango", CLASSIC);
            final Map<String, String> before = tree(this.directory);
            // Format-1 writers of the same binary are refused on a format-2 store (an old writer path cannot interleave).
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.saveAccount(PlayerAccount.empty(player, SLOTS)));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.saveProfile(new AuroraProfile(UUID.randomUUID(),
                player, "skyblock", Map.of(), 1L, 0L, ProfileStatus.ACTIVE)));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.saveBinding(ProfileWorldBinding.empty(profile.id())));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.deleteProfileData(profile.id()));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.latestSnapshot(profile.id()));
            // No downgrade marker is ever written.
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> repository.writeStoreFormat(StoreFormat.Marker.legacy()));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> repository.writeStoreFormat(StoreFormat.Marker.migrating(CoopFixtures.BUILD)));
            assertEquals(before, tree(this.directory), "a refused old-writer path changed the store");
            assertTrue(repository.storeFormat().readyFormat2());
        }

        // Every record file of the store is format 2, so a pre-Build-51 reader (format 1 only) refuses each of them.
        try (var walk = Files.walk(this.directory)) {
            for (final Path file : (Iterable<Path>) walk::iterator) {
                if (!file.getFileName().toString().endsWith(".awfp")) continue;
                final byte[] bytes = Files.readAllBytes(file);
                assertEquals(2, bytes[4], file + " is not a format-2 frame");
            }
        }

        // A marker that disappears after the process saw format 2 refuses everything instead of falling back to format 1.
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.open(this.directory)) {
            assertTrue(store.repository.storeFormat().readyFormat2());
            Files.delete(this.directory.resolve(StoreFormat.FILE_NAME));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.saveAccount(PlayerAccount.empty(player, SLOTS)));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.loadAccount(player, SLOTS));
        }
        // Format-2 directories without a marker are refused at open as well (a deleted marker never re-enables format 1).
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.open(this.directory)) {
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> store.repository.saveAccount(PlayerAccount.empty(player, SLOTS)));
            assertFalse(Files.exists(this.directory.resolve(StoreFormat.FILE_NAME)));
        }
    }
}
