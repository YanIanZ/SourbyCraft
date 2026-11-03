package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Shared fixtures of the Build 52 co-op membership owner cases (COOP-DESIGN §10 slice 4).
 *
 * <p>Two levels, both deterministic:</p>
 * <ul>
 *   <li>Repository level: the {@link CoopMembership} builders run as intents exactly the way the engine runs them
 *       ({@code stage → prepareIntent → commitIntent}), so a crash can be simulated between any two steps. Times the
 *       builders judge (invite expiry, vote expiry, the two-member inactivity rule) are passed explicitly; the
 *       repository's own commit-time clock is only ever compared against deadlines that are clearly past or clearly
 *       absent, never against a deadline a few milliseconds away.</li>
 *   <li>Engine level ({@link CoopEngine}): the real {@link ProfileEngine} over a FILE store, seen through a
 *       pass-through proxy whose {@link ProfileRepository#storeClockMillis} the test can shift by a fixed offset
 *       (the FILE store's own commit-time clock is not shifted) and whose {@code prepareIntent} reply can be replaced
 *       (a lost reply). Co-op events are collected in a queue that the tests drain with bounded waits.</li>
 * </ul>
 */
final class CoopMembershipFixtures {

    static final long MINUTE = 60_000L;
    static final long DAY = 86_400_000L;
    /** Policy of the repository-level cases: up to 8 members, invites 5 minutes, votes 10 minutes, 35 days inactivity. */
    static final CoopMembership.Policy POLICY = new CoopMembership.Policy(8, 5 * MINUTE, 10 * MINUTE, 35 * DAY);
    /** A store-clock shift beyond the default 35-day inactivity rule. */
    static final long INACTIVE_SHIFT = 36 * DAY;

    private CoopMembershipFixtures() {}

    // ------------------------------------------------------------------ settings

    /** MultiProfile on, all policy types creatable, co-op as given (invites 5 min, votes 10 min, 35 days). */
    static ProfileSettings coopSettings(final boolean coopEnabled, final int maxMembers,
                                        final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> convertible) {
        final ProfileSettings base = CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES);
        return new ProfileSettings(base.enabled(), base.mode(), base.defaultSlots(), base.maxSlots(), base.switching(),
            base.isolation(), base.worlds(), base.coordination(), base.directory(), base.repository(), base.types(),
            base.names(), base.migration(), new ProfileSettings.Coop(coopEnabled, maxMembers, 5, 10, 35,
                EnumSet.copyOf(convertible)));
    }

    static ProfileSettings coopSettings() {
        return coopSettings(true, 5, Set.of(CoopFixtures.CLASSIC));
    }

    // ------------------------------------------------------------------ plans as intents

    /** Stages and PREPAREs {@code plan} under the claims; returns the stored PREPARED intent. */
    static ProfileIntent preparePlan(final ProfileRepository repository, final CoopIntentPlan plan, final ProfileClaim p,
                                     final PlayerClaim q, final PlayerClaim affected) throws IOException {
        final List<ProfileIntent.BlobRef> refs = plan.stage(repository);
        final CoopWriteGuard guard = plan.prepareGuard(p, q, affected);
        committed(repository.prepareIntent(plan.prepared(guard, refs), guard));
        final ProfileIntent stored = repository.intent(plan.op().operationId()).orElseThrow();
        assertEquals(ProfileIntent.Phase.PREPARED, stored.phase());
        return stored;
    }

    static CommitResult commitPlan(final ProfileRepository repository, final CoopIntentPlan plan, final ProfileClaim p,
                                   final PlayerClaim q, final PlayerClaim affected) throws IOException {
        final ProfileIntent stored = repository.intent(plan.op().operationId()).orElseThrow();
        return repository.commitIntent(plan.op().operationId(), plan.intentGuard(p, q, affected, stored));
    }

    static CommitResult abortPlan(final ProfileRepository repository, final CoopIntentPlan plan, final ProfileClaim p,
                                  final PlayerClaim q, final PlayerClaim affected) throws IOException {
        final ProfileIntent stored = repository.intent(plan.op().operationId()).orElseThrow();
        return repository.abortIntent(plan.op().operationId(), plan.intentGuard(p, q, affected, stored));
    }

    /** PREPARE and COMMIT; the commit must be COMMITTED. */
    static void runPlan(final ProfileRepository repository, final CoopIntentPlan plan, final ProfileClaim p, final PlayerClaim q,
                        final PlayerClaim affected) throws IOException {
        preparePlan(repository, plan, p, q, affected);
        committed(commitPlan(repository, plan, p, q, affected));
    }

    // ------------------------------------------------------------------ repository-level scenario steps

    static PlayerAccount account(final ProfileRepository repository, final UUID player) throws IOException {
        return repository.loadAccount(player, CoopFixtures.SLOTS);
    }

    static AuroraProfile profile(final ProfileRepository repository, final UUID profileId) throws IOException {
        return repository.profile(profileId).orElseThrow();
    }

    /** Raises the store feature level (as the engine does before its first co-op record) and converts the solo profile. */
    static AuroraProfile convert(final ProfileRepository repository, final ProfileClaim p, final PlayerClaim owner,
                                 final AuroraProfile solo) throws IOException {
        final StoreFormat.Marker raised = repository.requireStoreFeature(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD,
            CoopFixtures.BUILD);
        assertTrue(raised.covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD), String.valueOf(raised));
        final AuroraProfile current = profile(repository, solo.id());
        runPlan(repository, CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP, owner.playerId()), current,
            account(repository, owner.playerId()), 0L), p, owner, null);
        final AuroraProfile coop = profile(repository, solo.id());
        assertTrue(coop.coop());
        return coop;
    }

    /** An owner's new co-op profile: create (one intent), then convert (one intent). */
    static AuroraProfile coopProfile(final ProfileRepository repository, final PlayerClaim owner, final Function<UUID, ProfileClaim> p,
                                     final String name) throws IOException {
        final AuroraProfile solo = CoopFixtures.create(repository, owner, name, CoopFixtures.CLASSIC);
        return convert(repository, p.apply(solo.id()), owner, solo);
    }

    /** Issues an invite at {@code issuedAt} (store-clock milliseconds the builder uses); returns the stored OPEN invite. */
    static ProfileInvite invite(final ProfileRepository repository, final ProfileClaim p, final PlayerClaim inviter,
                                final UUID profileId, final UUID invitee, final long issuedAt, final CoopMembership.Policy policy)
        throws IOException {
        final CoopIntentPlan plan = CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, inviter.playerId()),
            profile(repository, profileId), account(repository, inviter.playerId()), invitee, account(repository, invitee), List.of(),
            policy, issuedAt, 0L);
        runPlan(repository, plan, p, inviter, null);
        final ProfileInvite stored = repository.invite(plan.result().recordId()).orElseThrow();
        assertEquals(ProfileInvite.Status.OPEN, stored.status());
        return stored;
    }

    static ProfileInvite invite(final ProfileRepository repository, final ProfileClaim p, final PlayerClaim inviter,
                                final UUID profileId, final UUID invitee) throws IOException {
        return invite(repository, p, inviter, profileId, invitee, repository.storeClockMillis(), POLICY);
    }

    /** The accept of {@code invite} by its invitee, built from the records as stored now. */
    static CoopIntentPlan acceptPlan(final ProfileRepository repository, final ProfileInvite invite, final long storeNow,
                                     final CoopMembership.Policy policy) throws IOException {
        return CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, invite.invitee()),
            profile(repository, invite.profileId()), repository.invite(invite.id()).orElseThrow(), account(repository, invite.invitee()),
            policy, List.of(), storeNow, 0L);
    }

    static CoopIntentPlan acceptPlan(final ProfileRepository repository, final ProfileInvite invite) throws IOException {
        return acceptPlan(repository, invite, repository.storeClockMillis(), POLICY);
    }

    /** Invite and accept in two intents; returns the member's row. */
    static ProfileMember admit(final ProfileRepository repository, final ProfileClaim p, final PlayerClaim owner,
                               final UUID profileId, final PlayerClaim member) throws IOException {
        final ProfileInvite invite = invite(repository, p, owner, profileId, member.playerId());
        final CoopIntentPlan accept = acceptPlan(repository, invite);
        runPlan(repository, accept, p, member, null);
        final ProfileMember row = profile(repository, profileId).member(member.playerId());
        assertNotNull(row);
        assertEquals(accept.result().epoch(), row.epoch());
        return row;
    }

    /**
     * A kick vote against {@code target} opened by the owner of a two-member co-op at {@code openedAt} (a store-clock
     * time at least {@link #INACTIVE_SHIFT} after the target joined, so the two-member rule allows it): PASSED at once.
     */
    static KickVote passedVote(final ProfileRepository repository, final ProfileClaim p, final PlayerClaim owner,
                               final UUID profileId, final UUID target, final long openedAt) throws IOException {
        final CoopIntentPlan plan = CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, owner.playerId()),
            profile(repository, profileId), account(repository, owner.playerId()), target, List.of(), POLICY, openedAt, 0L);
        runPlan(repository, plan, p, owner, null);
        final KickVote vote = repository.vote(plan.result().recordId()).orElseThrow();
        assertEquals(KickVote.Status.PASSED, vote.status());
        return vote;
    }

    /** The removal of a passed vote's target by {@code actor}, built from the records as stored now. */
    static CoopIntentPlan kickPlan(final ProfileRepository repository, final UUID actor, final KickVote vote, final long storeNow)
        throws IOException {
        final AuroraProfile profile = profile(repository, vote.profileId());
        final ProfileMember target = profile.member(vote.target());
        final MemberStateHead head = target == null ? null
            : repository.memberHead(profile.id(), target.playerId(), target.epoch()).orElse(null);
        return CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, actor), profile, repository.vote(vote.id()).orElseThrow(),
            account(repository, vote.target()), head, List.of(), POLICY, storeNow, 0L);
    }

    /** The ids an index lists now (first page). */
    static List<UUID> indexed(final ProfileRepository repository, final ProfileIndexPage.Kind kind, final UUID resource)
        throws IOException {
        final List<UUID> ids = new ArrayList<>();
        for (final ProfileIndexPage.Entry entry : repository.index(kind, resource, null, null, null).entries()) ids.add(entry.id());
        return ids;
    }

    /** Deletes every receipt file of {@code operationId} below a FILE store (the crash point before receipts). */
    static void deleteReceipts(final Path store, final UUID operationId) throws IOException {
        final Path receipts = store.resolve("receipts");
        if (!Files.isDirectory(receipts)) return;
        try (var walk = Files.walk(receipts)) {
            for (final Path file : walk.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals(operationId + ".awfp")) Files.delete(file);
            }
        }
    }

    /** Writes a stored intent record directly (the crash point after the COMMITTED marker, FILE layout). */
    static void writeIntent(final Path store, final ProfileIntent intent) throws IOException {
        final Path file = store.resolve("intents").resolve(intent.operationId() + ".awfp");
        FileProfileIo.writeDurable(file, ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(),
            ProfileRecords.intentPayload2(intent), file.toString()));
    }

    // ------------------------------------------------------------------ assertions (both backends)


    static PlayerAccount accountImage(final CoopIntentPlan plan, final UUID player) throws Exception {
        for (final CoopIntentPlan.Image image : plan.images()) {
            if (image.recordType() == ProfileRecords.TYPE_ACCOUNT && image.id().equals(player)) {
                return ProfileRecords.account2(ProfileRecords.unframe(ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, image.revision(),
                    image.payload(), "image"), ProfileRecords.FORMAT_2, ProfileRecords.TYPE_ACCOUNT, "image"), player, "image");
            }
        }
        throw new AssertionError("the plan has no account image of " + player);
    }

    /** Everything an accept commits is visible, all of it, exactly once. */
    static void assertAccepted(final ProfileRepository repository, final CoopIntentPlan accept, final ProfileInvite invite)
        throws Exception {
        final UUID member = accept.op().actor();
        final UUID epoch = accept.result().epoch();
        assertEquals(ProfileIntent.Phase.FINALIZED, repository.intent(accept.op().operationId()).orElseThrow().phase());
        final AuroraProfile profile = profile(repository, accept.profileId());
        final ProfileMember row = profile.member(member);
        assertNotNull(row, "the accepted member has no row");
        assertEquals(epoch, row.epoch());
        assertEquals(ProfileRole.MEMBER, row.role());
        assertNull(profile.pendingIntent());
        final PlayerAccount account = account(repository, member);
        assertEquals(accountImage(accept, member), account, "the account is exactly its after-image (not advanced twice)");
        assertTrue(account.owns(profile.id()));
        assertEquals(accept.result().alias(), account.alias(profile.id()));
        assertNull(account.pendingIntent());
        final ProfileInvite stored = repository.invite(invite.id()).orElseThrow();
        assertEquals(ProfileInvite.Status.ACCEPTED, stored.status());
        assertEquals(epoch, stored.acceptedEpoch());
        assertEquals(MemberStateHead.State.UNINITIALIZED, repository.memberHead(profile.id(), member, epoch).orElseThrow().state());
        assertFalse(indexed(repository, ProfileIndexPage.Kind.INVITES_BY_INVITEE, member).contains(invite.id()));
        assertFalse(indexed(repository, ProfileIndexPage.Kind.INVITES_BY_PROFILE, profile.id()).contains(invite.id()));
        // One receipt per intent under the profile (both backends; FILE writes it under every account and profile touched).
        assertEquals(ProfileReceipt.Outcome.COMMITTED, repository.receipt(accept.profileId(), accept.op().operationId()).orElseThrow().outcome());
    }

    /** Nothing of an accept is visible. */
    static void assertNotAccepted(final ProfileRepository repository, final UUID profileId, final ProfileInvite invite)
        throws Exception {
        final UUID member = invite.invitee();
        assertNull(profile(repository, profileId).member(member), "a member row without its committed accept");
        assertFalse(account(repository, member).owns(profileId), "account access without its committed accept");
        assertEquals(ProfileInvite.Status.OPEN, repository.invite(invite.id()).orElseThrow().status());
        assertEquals(invite.revision(), repository.invite(invite.id()).orElseThrow().revision());
    }

    /** Everything a kick commits is visible: row gone, access gone, epoch consumed, salvage UNAVAILABLE, vote EXECUTED. */
    static void assertKicked(final ProfileRepository repository, final CoopIntentPlan kick, final ProfileMember removed)
        throws Exception {
        final UUID operation = kick.op().operationId();
        assertEquals(ProfileIntent.Phase.FINALIZED, repository.intent(operation).orElseThrow().phase());
        final AuroraProfile profile = profile(repository, kick.profileId());
        assertNull(profile.member(removed.playerId()));
        assertNull(profile.pendingIntent());
        final PlayerAccount account = account(repository, removed.playerId());
        assertEquals(accountImage(kick, removed.playerId()), account);
        assertFalse(account.owns(profile.id()));
        assertNull(account.pendingIntent());
        final MemberStateHead head = repository.memberHead(profile.id(), removed.playerId(), removed.epoch()).orElseThrow();
        assertEquals(MemberStateHead.State.CONSUMED, head.state());
        assertEquals(operation, head.consumingOperationId());
        final ProfileSalvage salvage = repository.salvage(kick.result().salvageId()).orElseThrow();
        assertEquals(ProfileSalvage.State.UNAVAILABLE, salvage.state());
        assertEquals(removed.playerId(), salvage.removedMember());
        assertEquals(removed.epoch(), salvage.removedEpoch());
        assertEquals(operation, salvage.removalIntentId());
        assertTrue(indexed(repository, ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, profile.id()).contains(salvage.id()));
        final KickVote vote = repository.vote(kick.result().recordId()).orElseThrow();
        assertEquals(KickVote.Status.EXECUTED, vote.status());
        assertEquals(operation, vote.removalIntentId());
        assertTrue(indexed(repository, ProfileIndexPage.Kind.VOTES_BY_PROFILE, profile.id()).isEmpty());
    }

    // ------------------------------------------------------------------ engine

    /**
     * The engine over a FILE store, as {@link CoopFixtures.EngineHarness}, but through a pass-through repository proxy
     * that can shift the store clock the engine reads and replace a {@code prepareIntent} reply. Co-op events go to
     * {@link #events}.
     */
    static final class CoopEngine implements AutoCloseable {

        final Path root;
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-a", 600);
        final CoopFixtures.QuietPlayers ports;
        final FileProfileRepository file;
        /** Added to every {@code storeClockMillis()} the engine reads; the FILE commit clock is not shifted. */
        final AtomicLong clockOffset = new AtomicLong();
        /** When set and it returns a result for an intent, that result replaces the {@code prepareIntent} call (not delegated). */
        final AtomicReference<Function<ProfileIntent, CommitResult>> prepareReply = new AtomicReference<>();
        final ProfileRepository repository;
        final ProfileEngine engine;
        final BlockingQueue<org.bukkit.event.Event> events = new LinkedBlockingQueue<>();

        CoopEngine(final Path root, final ProfileSettings settings, final boolean format2) throws IOException {
            this(root, settings, format2, new CoopFixtures.QuietPlayers());
        }

        /** As above with the given player port (the Build 53 member-play cases pass a stateful one). */
        CoopEngine(final Path root, final ProfileSettings settings, final boolean format2, final CoopFixtures.QuietPlayers ports)
            throws IOException {
            this.root = root;
            this.ports = ports;
            this.file = new FileProfileRepository(root, "node-a");
            if (format2 && this.file.storeFormat().legacyFormat()) this.file.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            this.file.attachClaims(FileProfileClaims.of(this.profiles, this.players));
            this.repository = proxy(this.file);
            this.engine = new ProfileEngine(settings, this.repository, this.profiles, this.players, this.ports,
                ProfileWorldPort.NONE, "node-a", this.io, null, null);
            this.engine.onCoopEvent(this.events::add);
        }

        private ProfileRepository proxy(final FileProfileRepository delegate) {
            final InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "CoopEngine.proxy(" + delegate + ")";
                    };
                }
                if (method.getName().equals("storeClockMillis") && method.getParameterCount() == 0) {
                    return delegate.storeClockMillis() + this.clockOffset.get();
                }
                if (method.getName().equals("prepareIntent") && args != null && args.length > 0 && args[0] instanceof ProfileIntent intent) {
                    final Function<ProfileIntent, CommitResult> reply = this.prepareReply.get();
                    if (reply != null) {
                        final CommitResult replaced = reply.apply(intent);
                        if (replaced != null) return replaced;
                    }
                }
                try {
                    return method.invoke(delegate, args);
                } catch (final InvocationTargetException failed) {
                    throw failed.getCause();
                }
            };
            return (ProfileRepository) Proxy.newProxyInstance(ProfileRepository.class.getClassLoader(),
                new Class<?>[] {ProfileRepository.class}, handler);
        }

        ProfileEngine.StoreStatus open() {
            return join(this.engine.open(CoopFixtures.NO_MIGRATION));
        }

        /** A solo profile of {@code player} created through the engine. */
        AuroraProfile create(final UUID player, final String name) throws IOException {
            return create(player, name, CoopFixtures.CLASSIC);
        }

        AuroraProfile create(final UUID player, final String name, final dev.iyanz.sourbycraft.api.profile.ProfileType type)
            throws IOException {
            final dev.iyanz.sourbycraft.api.profile.ProfileDetails details = join(this.engine.createDetailed(
                dev.iyanz.sourbycraft.api.profile.ProfileRequest.of(player, "skyblock", type).withName(name)));
            return this.file.profile(details.info().id()).orElseThrow();
        }

        /** A co-op profile of {@code owner} named {@code name}, created and converted through the engine. */
        AuroraProfile coop(final UUID owner, final String name) throws IOException {
            final AuroraProfile solo = create(owner, name);
            join(this.engine.convertToCoop(owner, solo.id()));
            return this.file.profile(solo.id()).orElseThrow();
        }

        /** Invite and accept through the engine; returns the member's row. */
        ProfileMember admit(final UUID owner, final UUID profileId, final UUID member) throws IOException {
            final dev.iyanz.sourbycraft.api.profile.CoopInviteInfo invite = join(this.engine.invite(owner, profileId, member));
            join(this.engine.acceptInvite(member, invite.id()));
            final ProfileMember row = this.file.profile(profileId).orElseThrow().member(member);
            assertNotNull(row, member + " was not admitted");
            return row;
        }

        AuroraProfile profile(final UUID profileId) throws IOException {
            return this.file.profile(profileId).orElseThrow();
        }

        PlayerAccount account(final UUID player) throws IOException {
            return this.file.loadAccount(player, CoopFixtures.SLOTS);
        }

        /**
         * Makes {@code profileId} the active profile of {@code player} directly in the store (a switch commit of the
         * account only, as {@link CoopFixtures#activate}); the claim is released again so the engine can take its own.
         */
        void activate(final UUID player, final UUID profileId) throws IOException {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), grant.reason());
            try {
                CoopFixtures.activate(this.file, grant.claim(), profile(profileId));
            } finally {
                join(this.players.release(grant.claim()));
            }
        }

        /** The next co-op event, waiting at most {@link CoopFixtures#WAIT_SECONDS}. */
        org.bukkit.event.Event nextEvent() throws InterruptedException {
            final org.bukkit.event.Event event = this.events.poll(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(event, "no co-op event within " + CoopFixtures.WAIT_SECONDS + " s");
            return event;
        }

        /** The next co-op event, which must be of {@code type}. */
        <E extends org.bukkit.event.Event> E nextEvent(final Class<E> type) throws InterruptedException {
            final org.bukkit.event.Event event = nextEvent();
            assertTrue(type.isInstance(event), "expected " + type.getSimpleName() + ", got " + event.getEventName());
            return type.cast(event);
        }

        /**
         * Takes events until one matches {@code last} (bounded wait per event) and returns every event taken, the match
         * last. The event lane is one FIFO thread fed after each commit, so an event an earlier, completed call fired is
         * always taken before the match: "nothing before the match" proves an earlier call fired nothing.
         */
        List<org.bukkit.event.Event> drainUntil(final java.util.function.Predicate<org.bukkit.event.Event> last)
            throws InterruptedException {
            final List<org.bukkit.event.Event> taken = new ArrayList<>();
            for (int i = 0; i < 4096; i++) {
                final org.bukkit.event.Event event = nextEvent();
                taken.add(event);
                if (last.test(event)) return taken;
            }
            throw new AssertionError("no matching co-op event among " + taken.size() + " events");
        }

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
        }
    }
}
