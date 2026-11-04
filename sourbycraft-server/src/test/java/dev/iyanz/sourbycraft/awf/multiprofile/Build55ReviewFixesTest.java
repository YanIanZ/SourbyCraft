package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.bytes;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.CoopInviteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopInviteState;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteState;
import dev.iyanz.sourbycraft.api.profile.ProfileBankPolicy;
import dev.iyanz.sourbycraft.api.profile.ProfileCurrency;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.api.profile.ProfileType;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Engine;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.PlayingPorts;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.TestAdapter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cases for the Build 55 review fixes (b55-review P1-2, P1-3, P2-4, P2-6, P2-7, P3-9, P3-14 and the PM extras) through
 * the engine over a FILE store: a member may leave an archived co-op while every other mutation stays refused; a co-op
 * is not archived while a member has it active or plays it on this node; an unarchive restores an operator lock; a
 * switch rollback restores a best-effort adapter whose capture failed from the stored entry its rollback point carries,
 * and resets it when the source stores none (review N1: never the target's value); with more than one I/O partition a profile save and a member's save of the same stream serialise and both
 * land; {@code profile.switch} does not gate a profile whose type has no definition; a ballot is a {@code coop.kick}
 * action; {@code CoopInviteInfo} names the invited profile and {@code ProfileBankPolicy} validates itself; the Build 55
 * action ids ignore a deny default rule (N2); a create request may not carry the archive markers (N4).
 *
 * <p>Deterministic: the store clock the engine reads is shifted by a fixed offset through the repository proxy, never
 * waited for; no sleeps; waits are bounded ({@link CoopFixtures#join}).</p>
 */
class Build55ReviewFixesTest {

    @TempDir
    Path directory;

    private final AtomicLong clockOffset = new AtomicLong();

    private Path store() {
        return this.directory.resolve("store");
    }

    private Engine engine() throws Exception {
        return engine(Build54Fixtures.settings(), new PlayingPorts());
    }

    private Engine engine(final ProfileSettings settings, final PlayingPorts ports) throws Exception {
        final Engine h = new Engine(store(), settings, ports);
        h.hooks.put("storeClockMillis", (args, proceed) -> (Long) proceed.call() + this.clockOffset.get());
        h.open();
        h.engine.storeWriter("Build 55 test");
        h.engine.bankPolicy(Build54Fixtures.COINS);
        return h;
    }

    /** {@link Build54Fixtures#settings()} with {@code io.partitions} set. */
    private static ProfileSettings partitioned(final int partitions) {
        final ProfileSettings b = Build54Fixtures.settings();
        return new ProfileSettings(b.enabled(), b.mode(), b.defaultSlots(), b.maxSlots(), b.switching(), b.isolation(), b.worlds(),
            b.coordination(), b.directory(), b.repository(), b.types(), b.names(), b.migration(), b.coop(), b.shutdown(), b.login(),
            b.adapters(), b.typeRegistry(), b.bank(), b.audit(), new ProfileSettings.Io(partitions));
    }

    private record Party(UUID owner, UUID member, AuroraProfile coop, AuroraProfile kiwi) {}

    /** Owner with co-op "Apple" (not active), member admitted with solo "Kiwi" (not active). */
    private static Party party(final Engine h) throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final AuroraProfile coop = h.coop(owner, "Apple");
        final AuroraProfile kiwi = h.create(member, "Kiwi");
        h.admit(owner, coop.id(), member);
        return new Party(owner, member, h.profile(coop.id()), kiwi);
    }

    // ------------------------------------------------------------------ P1-3 archive refused while a member is inside

    /**
     * Review P1-3: archiving a co-op is refused while a member has it as their active profile ({@code PROFILE_LOCKED},
     * naming the member) or plays it in an admitted session on this node ({@code MEMBER_ONLINE}); once the member is
     * out it succeeds and the profile reads archived.
     */
    @Test
    void archiveIsRefusedWhileAMemberHasTheCoopActiveOrPlaysIt() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            h.activate(p.member(), p.coop().id());
            final ProfileOperationException active = refusal(() -> join(h.engine.setArchived(p.coop().id(), true)));
            assertEquals(ProfileErrorCode.PROFILE_LOCKED, active.code(), active.getMessage());
            assertTrue(active.getMessage().contains(p.member().toString()), "the refusal does not name the member: " + active.getMessage());
            assertFalse(h.profile(p.coop().id()).archived());

            h.activate(p.member(), p.kiwi().id());
            h.online(p.member());
            h.switched(p.member(), p.coop().id());
            final ProfileOperationException playing = refusal(() -> join(h.engine.setArchived(p.coop().id(), true)));
            assertEquals(ProfileErrorCode.MEMBER_ONLINE, playing.code(), playing.getMessage());
            assertFalse(h.profile(p.coop().id()).archived());

            h.switched(p.member(), p.kiwi().id());
            join(h.engine.setArchived(p.coop().id(), true));
            final AuroraProfile archived = h.profile(p.coop().id());
            assertTrue(archived.archived());
            assertEquals(ProfileStatus.LOCKED, archived.status());
            assertEquals("ARCHIVED", archived.apiStatus());
            // The member's own quit save goes to Kiwi, not to the archived co-op, and is accepted.
            h.ports.live.put(p.member(), level(7));
            assertTrue(h.quit(p.member()), "the quit save of a member who left the archived co-op first was refused");
        }
    }

    // ------------------------------------------------------------------ P1-2 leaving an archived co-op

    /**
     * PM decision (review P1-2): a member may leave an archived co-op; the row is dropped and the salvage recorded as for
     * any leave, while invite, label, bank and switch-in stay refused ({@code PROFILE_ARCHIVED}) and the profile stays
     * archived.
     */
    @Test
    void aMemberMayLeaveAnArchivedCoopWhileEveryOtherMutationStaysRefused() throws Exception {
        try (Engine h = engine()) {
            final Party p = party(h);
            final UUID other = UUID.randomUUID();
            h.activate(p.member(), p.kiwi().id());
            join(h.engine.setArchived(p.coop().id(), true));
            assertTrue(h.profile(p.coop().id()).archived());

            refusedWith(ProfileErrorCode.PROFILE_ARCHIVED, () -> join(h.engine.invite(p.owner(), p.coop().id(), other)));
            refusedWith(ProfileErrorCode.PROFILE_ARCHIVED, () -> join(h.engine.setCoopLabel(p.owner(), p.coop().id(), "Pears")));
            refusedWith(ProfileErrorCode.PROFILE_ARCHIVED, () -> join(h.engine.deposit(p.owner(), p.coop().id(), 5L)));
            h.online(p.member());
            final ProfileSwitchResult in = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertFalse(in.success());
            assertEquals(ProfileErrorCode.PROFILE_ARCHIVED, in.errorCode(), in.message());

            join(h.engine.leave(p.member(), p.coop().id()));
            final AuroraProfile after = h.profile(p.coop().id());
            assertNull(after.member(p.member()), "the member still holds a row in the archived co-op");
            assertTrue(after.archived(), "the leave lifted the archive");
            assertFalse(h.account(p.member()).profileIds().contains(p.coop().id()), "the account still lists the co-op");
            assertEquals(1, h.salvage(p.coop().id()).size(), "the leave recorded no salvage");
            refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.leave(p.member(), p.coop().id())));
        }
    }

    // ------------------------------------------------------------------ P3-14 unarchive restores a lock

    /** Review P3-14: an operator lock that preceded the archive is back after the unarchive; without one the profile is ACTIVE. */
    @Test
    void unarchiveRestoresThePriorStatus() throws Exception {
        try (Engine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final AuroraProfile lime = h.create(owner, "Lime");
            final AuroraProfile pear = h.create(owner, "Pear");
            join(h.engine.setLocked(lime.id(), true));
            assertEquals(ProfileStatus.LOCKED, h.profile(lime.id()).status());
            join(h.engine.setArchived(lime.id(), true));
            assertTrue(h.profile(lime.id()).archived());
            assertEquals("true", h.profile(lime.id()).metadata().get(AuroraProfile.ARCHIVED_FROM_LOCKED_METADATA_KEY));
            join(h.engine.setArchived(lime.id(), false));
            final AuroraProfile unarchived = h.profile(lime.id());
            assertFalse(unarchived.archived());
            assertEquals(ProfileStatus.LOCKED, unarchived.status(), "the unarchive lifted the operator's lock");
            assertFalse(unarchived.metadata().containsKey(AuroraProfile.ARCHIVED_FROM_LOCKED_METADATA_KEY));
            join(h.engine.setLocked(lime.id(), false));
            assertEquals(ProfileStatus.ACTIVE, h.profile(lime.id()).status());

            join(h.engine.setArchived(pear.id(), true));
            join(h.engine.setArchived(pear.id(), false));
            assertEquals(ProfileStatus.ACTIVE, h.profile(pear.id()).status(), "an archive without a prior lock unarchives to ACTIVE");
        }
    }

    // ------------------------------------------------------------------ P2-4 preserved adapters

    /** A best-effort adapter that counts {@code applyAbsent}. */
    private static final class CountingAdapter extends TestAdapter {
        final AtomicInteger absent = new AtomicInteger();

        CountingAdapter(final String id, final Plugin plugin) {
            super(id, 1, plugin, null);
        }

        @Override
        public void applyAbsent(final Player player) {
            this.absent.incrementAndGet();
        }
    }

    /**
     * Review P2-4, registry level: an adapter named untouched is not told {@code applyAbsent} for a state that holds no
     * entry of it, while the other absent adapters are; an entry present under an untouched id is still applied.
     */
    @Test
    void applyLeavesUntouchedAdaptersAlone() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final Plugin plugin = MemberPlayFixtures.plugin("SoftPlugin");
        final CountingAdapter soft = new CountingAdapter("soft", plugin);
        final CountingAdapter other = new CountingAdapter("other", plugin);
        registry.register(plugin, soft);
        registry.register(plugin, other);
        final Player player = MemberPlayFixtures.player(UUID.randomUUID());

        registry.apply(player, Map.of());
        assertEquals(1, soft.absent.get());
        assertEquals(1, other.absent.get());

        registry.apply(player, Map.of(), Set.of("soft"));
        assertEquals(1, soft.absent.get(), "an untouched adapter was reset");
        assertEquals(2, other.absent.get(), "an adapter not named untouched was not reset");

        final byte[] data = bytes(4, 9);
        registry.apply(player, Map.of("soft:1", data), Set.of("soft"));
        assertEquals(1, soft.absent.get());
        assertArrayEquals(data, soft.applied.getLast(), "an entry present under an untouched id was not applied");

        final ProfileStateAdapters.Capture capture = new ProfileStateAdapters.Capture(Map.of(), Set.of("soft:1", "x"), Set.of());
        assertEquals(Set.of("soft"), ProfileStateAdapters.preservedIds(capture), "preserved keys map to ids; a key without a version is skipped");
    }

    /**
     * Ports whose applies also reach the adapter registry, as the Bukkit port does, and record the untouched adapter ids
     * each apply named. With {@link #partialFailedApply} set beside {@code failNextApply}, that failing apply first puts
     * the state's adapter data on the player: a target apply that failed after its adapters ran.
     */
    private static final class AdapterApplyingPorts extends PlayingPorts {
        final java.util.concurrent.atomic.AtomicBoolean partialFailedApply = new java.util.concurrent.atomic.AtomicBoolean();
        final List<Set<String>> untouched = new java.util.concurrent.CopyOnWriteArrayList<>();

        AdapterApplyingPorts(final ProfileStateAdapters registry) {
            super(registry);
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation) {
            return applyNaming(playerId, state, isolation, Set.of());
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation,
                                             final Set<String> untouchedAdapterIds) {
            return applyNaming(playerId, state, isolation, untouchedAdapterIds);
        }

        private CompletableFuture<Void> applyNaming(final UUID playerId, final ProfileState state, final ProfileIsolation isolation,
                                                    final Set<String> untouchedAdapterIds) {
            this.untouched.add(Set.copyOf(untouchedAdapterIds));
            final Player player = MemberPlayFixtures.player(playerId);
            if (this.failNextApply.get() != null && this.partialFailedApply.getAndSet(false)) {
                this.registry.apply(player, state.adapters(), untouchedAdapterIds);
            }
            final CompletableFuture<Void> applied = super.apply(playerId, state, isolation);
            if (!applied.isCompletedExceptionally()) this.registry.apply(player, state.adapters(), untouchedAdapterIds);
            return applied;
        }
    }

    /** A best-effort adapter holding one live value: capture returns it (or fails when told), apply sets it, absent clears it. */
    private static final class PurseAdapter extends TestAdapter {
        final java.util.concurrent.atomic.AtomicReference<byte[]> value = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean failCapture = new java.util.concurrent.atomic.AtomicBoolean();

        PurseAdapter(final String id, final Plugin plugin) {
            super(id, 1, plugin, null);
        }

        @Override
        public byte[] capture(final Player player) {
            if (this.failCapture.get()) throw new IllegalStateException("scripted capture failure of " + this.id);
            return this.value.get();
        }

        @Override
        public void apply(final Player player, final byte[] data) {
            this.value.set(data.clone());
        }

        @Override
        public void applyAbsent(final Player player) {
            this.value.set(null);
        }
    }

    /**
     * Review P2-4 and N1, engine level: the rollback point of a switch is the carried state (the stored bytes of a
     * best-effort adapter whose capture failed are in it), so a rollback after a failed target apply restores them to
     * the adapter instead of applying the raw capture without them; the rollback apply leaves no adapter untouched.
     */
    @Test
    void aRollbackCarriesTheStoredAdapterEntriesOfAFailedBestEffortCapture() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final Plugin plugin = MemberPlayFixtures.plugin("SoftPlugin");
        final byte[] stored = bytes(8, 2);
        final TestAdapter soft = TestAdapter.bestEffort("soft", plugin).returning(stored);
        registry.register(plugin, soft);
        final AdapterApplyingPorts ports = new AdapterApplyingPorts(registry);
        try (Engine h = engine(Build54Fixtures.settings(), ports)) {
            final Party p = party(h);
            h.activate(p.member(), p.coop().id());
            h.playing(p.member());
            h.ports.live.put(p.member(), level(3));
            // The member's stream holds the adapter's bytes.
            final Map<String, byte[]> initial = new LinkedHashMap<>();
            initial.put("soft:1", stored);
            assertTrue(h.save(p.member(), level(3, initial)));
            final UUID epoch = h.profile(p.coop().id()).member(p.member()).epoch();
            assertArrayEquals(stored, h.stateOf(h.head(p.coop().id(), p.member(), epoch)).adapters().get("soft:1"));

            // The capture of the switch fails for the adapter (preserved), and the target apply fails: the switch rolls back.
            soft.throwing();
            h.ports.failNextApply.set(new IllegalStateException("scripted target apply failure"));
            final ProfileSwitchResult result = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertFalse(result.success(), "the switch committed although the target apply failed");
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile(), "the account moved although the switch rolled back");
            final ProfileState rolledBack = h.ports.applied.getLast();
            assertEquals(3, rolledBack.level());
            assertArrayEquals(stored, rolledBack.adapters().get("soft:1"),
                "the rollback applied the raw capture: the preserved adapter bytes are missing, so applyAbsent would reset the adapter");
            assertArrayEquals(stored, h.stateOf(h.head(p.coop().id(), p.member(), epoch)).adapters().get("soft:1"),
                "the rollback point written to the stream lost the preserved bytes");
            assertArrayEquals(stored, soft.applied.getLast(), "the rollback did not restore the stored bytes to the adapter");
            assertEquals(Set.of(), ports.untouched.getLast(), "the rollback apply left an adapter untouched");
        }
    }

    /**
     * Review N1: a best-effort adapter whose capture fails during a switch, with no entry stored for it in the source
     * profile, is reset by the rollback after the target apply put the target's value on the player; left untouched it
     * would keep the target's value inside the source profile and the next save would duplicate it.
     */
    @Test
    void aRollbackResetsAFailedBestEffortAdapterTheSourceStoresNothingFor() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final Plugin plugin = MemberPlayFixtures.plugin("PursePlugin");
        final PurseAdapter purse = new PurseAdapter("purse", plugin);
        registry.register(plugin, purse);
        final AdapterApplyingPorts ports = new AdapterApplyingPorts(registry);
        try (Engine h = engine(Build54Fixtures.settings(), ports)) {
            final Party p = party(h);
            final byte[] coins = bytes(5, 7);
            purse.value.set(coins);
            h.activate(p.member(), p.kiwi().id());
            h.online(p.member());
            h.ports.live.put(p.member(), level(3));
            // Kiwi stores the purse; the co-op stream starts empty, so playing it resets the purse.
            h.switched(p.member(), p.coop().id());
            assertNull(purse.value.get(), "the co-op holds no purse entry, yet the purse kept Kiwi's value");

            // In the co-op the purse capture fails, and the target apply fails after it put Kiwi's purse on the player.
            purse.failCapture.set(true);
            ports.partialFailedApply.set(true);
            h.ports.failNextApply.set(new IllegalStateException("scripted target apply failure"));
            final ProfileSwitchResult result = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertFalse(result.success(), "the switch committed although the target apply failed");
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile(), "the account moved although the switch rolled back");
            assertNull(purse.value.get(), "the rollback left Kiwi's purse on the player inside the co-op");
            assertEquals(Set.of(), ports.untouched.getLast(), "the rollback apply left an adapter untouched");
        }
    }

    // ------------------------------------------------------------------ P2-6 partitions above one

    /**
     * Review P2-6: with two partitions a profile save ({@code save}, keyed by profile) and a member's own save of the same
     * stream ({@code saveCaptured}, keyed by player and the ticket's profile) share the profile's partition, run one after
     * the other and both land; the quit save and the session release then complete with nothing pending.
     */
    @Test
    void withTwoPartitionsAProfileSaveAndAMemberSaveOfTheSameStreamBothLand() throws Exception {
        try (Engine h = engine(partitioned(2), new PlayingPorts())) {
            final Party p = party(h);
            h.activate(p.member(), p.kiwi().id());
            h.online(p.member());
            h.switched(p.member(), p.coop().id());
            final UUID epoch = h.profile(p.coop().id()).member(p.member()).epoch();
            h.ports.live.put(p.member(), level(3));
            for (int round = 0; round < 8; round++) {
                final CompletableFuture<Void> all = h.engine.save(p.coop().id());
                final CompletableFuture<Boolean> mine = h.engine.saveCaptured(p.member(),
                    PlayerStatePort.CaptureResult.of(level(10 + round)), h.engine.captureTicket(p.member()));
                join(all);
                assertTrue(join(mine), "the member's save was refused beside the profile save (round " + round + ")");
                final MemberStateHead head = h.head(p.coop().id(), p.member(), epoch);
                assertEquals(MemberStateHead.State.READY, head.state(), head.describe());
            }
            h.ports.live.put(p.member(), level(42));
            assertTrue(h.quit(p.member()), "the quit save was refused");
            assertEquals(42, h.stateOf(h.head(p.coop().id(), p.member(), epoch)).level());
            assertEquals(0, h.engine.pendingIo());
        }
    }

    // ------------------------------------------------------------------ P2-7 unknown types stay playable

    /**
     * Review P2-7: a profile whose type has no definition on the server is switched into under the default deny policy;
     * a defined type that denies {@code profile.switch} is still refused ({@code ACTION_FORBIDDEN_BY_TYPE}).
     */
    @Test
    void profileSwitchDoesNotGateAProfileOfAnUndefinedType() throws Exception {
        ProfileTypes types = null;
        try (Engine h = engine()) {
            types = Build54Fixtures.openTypes(this.directory.resolve("types"), ProfileSettings.DEFAULT_TYPE_REGISTRY);
            final Plugin plugin = MemberPlayFixtures.plugin("TypesPlugin");
            types.register(plugin, ProfileTypeDefinition.builder("frugal").build());
            final UUID owner = UUID.randomUUID();
            final AuroraProfile lime = h.create(ProfileRequest.of(owner, "skyblock").withTypeId("frugal").withName("Lime"));
            final AuroraProfile kiwi = h.create(owner, "Kiwi");
            assertEquals("frugal", ProfileTypes.typeIdOf(lime));

            // A defined type that denies playing refuses the switch.
            types.register(plugin, ProfileTypeDefinition.builder("frugal").deny(ProfileTypeRegistry.PROFILE_SWITCH).build());
            h.activate(owner, kiwi.id());
            h.online(owner);
            final ProfileSwitchResult denied = join(h.engine.switchProfile(owner, lime.id()));
            assertFalse(denied.success());
            assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, denied.errorCode(), denied.message());
            final ProfileTypes open = types;
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> open.requireIfDefined(lime, ProfileTypes.PROFILE_SWITCH));

            // The registry closes: "frugal" has no definition here and the deny policy applies to every other action,
            // but not to playing the profile.
            Build54Fixtures.closeTypes(types);
            types = null;
            assertFalse(ProfileTypes.get().allows(lime, ProfileTypes.PROFILE_SWITCH), "the unknown type is denied by policy");
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> ProfileTypes.get().require(lime, ProfileTypes.PROFILE_SWITCH));
            ProfileTypes.get().requireIfDefined(lime, ProfileTypes.PROFILE_SWITCH);
            final ProfileSwitchResult played = join(h.engine.switchProfile(owner, lime.id()));
            assertTrue(played.success(), played.errorCode() + ": " + played.message());
            assertEquals(lime.id(), h.account(owner).activeProfile());
        } finally {
            Build54Fixtures.closeTypes(types);
        }
    }

    // ------------------------------------------------------------------ N2 Build 55 ids and a deny default rule

    /**
     * Review N2: {@code profile.switch}, {@code coop.kick} and {@code coop.label} are allowed unless the definition names
     * them; a deny default rule (a Build 54 type) decides every other action but not these; a named deny refuses them;
     * {@code coopAllowed(false)} still refuses the two co-op ids; an unreadable stored definition denies them by name. A
     * profile of such a Build 54-style type is still switched into.
     */
    @Test
    void theBuild55ActionIdsIgnoreADenyDefaultRule() throws Exception {
        final ProfileTypeDefinition legacy = ProfileTypeDefinition.builder("legacy")
            .defaultRule(dev.iyanz.sourbycraft.api.profile.ProfileRule.deny()).build();
        for (final String action : List.of(ProfileTypeRegistry.PROFILE_SWITCH, ProfileTypeRegistry.COOP_KICK, ProfileTypeRegistry.COOP_LABEL)) {
            assertTrue(ProfileTypeRegistry.allowedUnlessNamed(action), action);
            assertTrue(legacy.allows(action), "the deny default rule decided " + action);
            assertFalse(legacy.toBuilder().deny(action).build().allows(action), "a named deny of " + action + " was ignored");
            assertFalse(ProfileTypes.unreadableDefinition("lost").allows(action), "an unreadable definition allowed " + action);
        }
        for (final String action : List.of(ProfileTypeRegistry.BANK_DEPOSIT, ProfileTypeRegistry.COOP_INVITE, "trade")) {
            assertFalse(ProfileTypeRegistry.allowedUnlessNamed(action), action);
            assertFalse(legacy.allows(action), "the deny default rule did not decide " + action);
        }
        final ProfileTypeDefinition solo = legacy.toBuilder().coopAllowed(false).build();
        assertTrue(solo.allows(ProfileTypeRegistry.PROFILE_SWITCH));
        assertFalse(solo.allows(ProfileTypeRegistry.COOP_KICK), "a type without co-op allowed a kick vote");
        assertFalse(solo.allows(ProfileTypeRegistry.COOP_LABEL), "a type without co-op allowed a co-op label");

        ProfileTypes types = null;
        try (Engine h = engine()) {
            types = Build54Fixtures.openTypes(this.directory.resolve("types"), ProfileSettings.DEFAULT_TYPE_REGISTRY);
            types.register(MemberPlayFixtures.plugin("TypesPlugin"), legacy);
            final UUID owner = UUID.randomUUID();
            final AuroraProfile lime = h.create(ProfileRequest.of(owner, "skyblock").withTypeId("legacy").withName("Lime"));
            final AuroraProfile kiwi = h.create(owner, "Kiwi");
            h.activate(owner, kiwi.id());
            h.online(owner);
            final ProfileSwitchResult played = join(h.engine.switchProfile(owner, lime.id()));
            assertTrue(played.success(), played.errorCode() + ": " + played.message());
            assertEquals(lime.id(), h.account(owner).activeProfile());
        } finally {
            Build54Fixtures.closeTypes(types);
        }
    }

    // ------------------------------------------------------------------ N4 reserved archive metadata

    /**
     * Review N4: a create request carrying {@code aurora:archived} or {@code aurora:archived-from-locked} is refused
     * ({@code INVALID_REQUEST}), so no profile reads as archived while its status is ACTIVE; other metadata still passes.
     */
    @Test
    void aCreateRequestMayNotCarryTheArchiveMarkers() throws Exception {
        try (Engine h = engine()) {
            final UUID owner = UUID.randomUUID();
            for (final String reserved : List.of(AuroraProfile.ARCHIVED_METADATA_KEY, AuroraProfile.ARCHIVED_FROM_LOCKED_METADATA_KEY)) {
                final ProfileRequest request = new ProfileRequest(owner, "skyblock", Map.of(reserved, "1"), ProfileType.CLASSIC, "Lime");
                refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.createDetailed(request)));
            }
            final AuroraProfile plain = h.create(new ProfileRequest(owner, "skyblock", Map.of("slot", "2"), ProfileType.CLASSIC, "Lime"));
            assertFalse(plain.archived());
            assertEquals(ProfileStatus.ACTIVE, plain.status());
            assertEquals("2", plain.metadata().get("slot"));
        }
    }

    // ------------------------------------------------------------------ P3-9 a ballot is a kick action

    /** Review P3-9: casting a ballot passes the type's {@code coop.kick} rule; a type that denies it refuses the vote. */
    @Test
    void castingABallotIsRefusedByATypeThatDeniesKicks() throws Exception {
        ProfileTypes types = null;
        try (Engine h = engine()) {
            types = Build54Fixtures.openTypes(this.directory.resolve("types"), ProfileSettings.DEFAULT_TYPE_REGISTRY);
            final Plugin plugin = MemberPlayFixtures.plugin("TypesPlugin");
            types.register(plugin, ProfileTypeDefinition.builder("frugal").build());
            final UUID owner = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final UUID c = UUID.randomUUID();
            final AuroraProfile coop = h.convert(owner, h.create(ProfileRequest.of(owner, "skyblock").withTypeId("frugal").withName("Lime")));
            h.admit(owner, coop.id(), b);
            h.admit(owner, coop.id(), c);
            final CoopKickVoteInfo vote = join(h.engine.startKickVote(owner, coop.id(), c));
            assertEquals(CoopKickVoteState.OPEN, vote.state());

            types.register(plugin, ProfileTypeDefinition.builder("frugal").deny(ProfileTypeRegistry.COOP_KICK).build());
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.voteKick(b, vote.id(), true)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.startKickVote(owner, coop.id(), b)));
            assertEquals(KickVote.Status.OPEN, h.file.vote(vote.id()).orElseThrow().status(), "a refused ballot changed the vote");
            assertNotNull(h.profile(coop.id()).member(c), "a refused ballot removed the target");

            types.register(plugin, ProfileTypeDefinition.builder("frugal").build());
            final CoopKickVoteInfo executed = join(h.engine.voteKick(b, vote.id(), true));
            assertEquals(CoopKickVoteState.EXECUTED, executed.state());
            assertNull(h.profile(coop.id()).member(c));
        } finally {
            Build54Fixtures.closeTypes(types);
        }
    }

    // ------------------------------------------------------------------ PM extras

    /** {@code CoopInviteInfo} names the invited profile (name and type) wherever the engine builds it; the Build 52 constructor still works. */
    @Test
    void inviteInfoCarriesTheProfileNameAndType() throws Exception {
        try (Engine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID invitee = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            final CoopInviteInfo issued = join(h.engine.invite(owner, coop.id(), invitee));
            assertEquals("Apple", issued.profileName());
            assertEquals(ProfileType.CLASSIC, issued.profileType());
            assertEquals(CoopInviteState.OPEN, issued.state());
            final List<CoopInviteInfo> forInvitee = join(h.engine.invitesFor(invitee, null)).items();
            assertEquals(1, forInvitee.size());
            assertEquals("Apple", forInvitee.getFirst().profileName());
            assertEquals(ProfileType.CLASSIC, forInvitee.getFirst().profileType());
            final List<CoopInviteInfo> ofProfile = join(h.engine.invitesOf(owner, coop.id(), null)).items();
            assertEquals("Apple", ofProfile.getFirst().profileName());

            final CoopInviteInfo legacy = new CoopInviteInfo(issued.id(), issued.profileId(), issued.inviter(), issued.invitee(),
                issued.createdAtMillis(), issued.expiresAtMillis(), issued.state());
            assertNull(legacy.profileName());
            assertNull(legacy.profileType());
            assertEquals(issued.id(), legacy.id());
        }
    }

    /** {@code ProfileBankPolicy} is a validated value: percentages 0-100, no negative cap or interval. */
    @Test
    void bankPolicyValidatesItself() {
        final ProfileCurrency coins = new ProfileCurrency("aurora", "coins", 0);
        final ProfileBankPolicy policy = new ProfileBankPolicy(coins, 0L, 20, 10, 24L * 60L * 60L * 1000L);
        assertEquals(coins, policy.currency());
        assertEquals(20, policy.personalCapPercent());
        assertThrows(IllegalArgumentException.class, () -> new ProfileBankPolicy(coins, -1L, 20, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProfileBankPolicy(coins, 0L, 101, 10, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProfileBankPolicy(coins, 0L, 20, -1, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProfileBankPolicy(coins, 0L, 20, 10, -1L));
        assertThrows(NullPointerException.class, () -> new ProfileBankPolicy(null, 0L, 20, 10, 1L));
    }
}
