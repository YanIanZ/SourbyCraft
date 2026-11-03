package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.PlayingPorts;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.update.BuildVersion;
import dev.iyanz.sourbycraft.update.StoreDowngradeGuard;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Shared fixtures of the Build 54 cases (COOP-DESIGN §10 slice 6, the profile type registry, the bank and salvage
 * delivery): settings with co-op, member play and the Build 54 components, an engine harness over a FILE store whose
 * repository calls can be intercepted by method name, item states told apart by their bytes, the type registry opened
 * on a store of the case's own, a WARN collector and the OD-2 downgrade gate.
 *
 * <p>Deterministic: no sleeps. Events are taken from queues with bounded waits; the event lane is one FIFO thread, so
 * the next event after a call is that call's (or an earlier call's) event.</p>
 */
final class Build54Fixtures {

    /** Whole coins, no shared cap, personal 20 %, interest 10 % (the defaults). */
    static final ProfileBank.Policy COINS = ProfileBank.Policy.DEFAULT;

    private Build54Fixtures() {}

    // ------------------------------------------------------------------ settings

    /**
     * MultiProfile, co-op (max 5 members, invites 5 min, votes 10 min, 35 days) and switching on, authoritative login,
     * the given convertible types and type-registry settings, the default bank settings (the engine's bank policy is set
     * by the case, as the runtime does once at start).
     */
    static ProfileSettings settings(final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> convertible,
                                    final ProfileSettings.TypeRegistry registry) {
        final ProfileSettings base = MemberPlayFixtures.playSettings();
        return new ProfileSettings(base.enabled(), base.mode(), base.defaultSlots(), base.maxSlots(), base.switching(),
            base.isolation(), base.worlds(), base.coordination(), base.directory(), base.repository(), base.types(),
            base.names(), base.migration(), new ProfileSettings.Coop(true, 5, 5, 10, 35, EnumSet.copyOf(convertible)),
            base.shutdown(), base.login(), base.adapters(), registry, ProfileSettings.DEFAULT_BANK);
    }

    static ProfileSettings settings() {
        return settings(Set.of(CoopFixtures.CLASSIC), ProfileSettings.DEFAULT_TYPE_REGISTRY);
    }

    // ------------------------------------------------------------------ items

    /** An item stack whose bytes name it: {@code 5A <tag low> <tag high>}. */
    static ProfileState.Item item(final int slot, final int tag) {
        return new ProfileState.Item(slot, new byte[] {(byte) 0x5A, (byte) tag, (byte) (tag >> 8)});
    }

    /** A state carrying the given item sections (level as given, everything else empty). */
    static ProfileState carrying(final List<ProfileState.Item> inventory, final List<ProfileState.Item> armor,
                                 final List<ProfileState.Item> offhand, final List<ProfileState.Item> ender, final int level) {
        final ProfileState empty = ProfileState.empty();
        return new ProfileState(ProfileState.SCHEMA, inventory, armor, offhand, ender, level, 0.0f, empty.health(), empty.food(),
            empty.saturation(), List.of(), null, null, Map.of());
    }

    /** A state whose first {@code storage} storage slots and first {@code ender} ender-chest slots hold an item. */
    static ProfileState filled(final int storage, final int ender, final int tagBase, final int level) {
        final List<ProfileState.Item> inventory = new ArrayList<>();
        for (int slot = 0; slot < storage; slot++) inventory.add(item(slot, tagBase + slot));
        final List<ProfileState.Item> chest = new ArrayList<>();
        for (int slot = 0; slot < ender; slot++) chest.add(item(slot, tagBase + 100 + slot));
        return carrying(inventory, List.of(), List.of(), chest, level);
    }

    /** The bytes of the items, in order, as hex. */
    static List<String> tags(final List<ProfileState.Item> items) {
        final List<String> tags = new ArrayList<>(items.size());
        for (final ProfileState.Item item : items) tags.add(HexFormat.of().formatHex(item.data()));
        return tags;
    }

    /** Every item of a state (storage, armour, off-hand, ender chest) as hex, sorted: a multiset of what it holds. */
    static List<String> everyItem(final ProfileState state) {
        final List<String> all = new ArrayList<>();
        all.addAll(tags(state.inventory()));
        all.addAll(tags(state.armor()));
        all.addAll(tags(state.offhand()));
        all.addAll(tags(state.enderChest()));
        all.sort(null);
        return all;
    }

    static List<String> sorted(final List<String> tags) {
        final List<String> copy = new ArrayList<>(tags);
        copy.sort(null);
        return copy;
    }

    // ------------------------------------------------------------------ type registry

    /** A registry over a FILE store in {@code directory} ({@link ProfileTypes#get()} returns it until it is closed). */
    static ProfileTypes openTypes(final Path directory, final ProfileSettings.TypeRegistry config) throws IOException {
        return ProfileTypes.open(null, config, ProfileTypesStore.file(directory));
    }

    /** Closes a registry (waiting for its pending store writes); {@code null} is ignored. */
    static void closeTypes(final ProfileTypes types) {
        if (types != null) types.close(10_000L);
    }

    // ------------------------------------------------------------------ OD-2 downgrade gate

    @SuppressWarnings("unchecked")
    static Optional<String> downgradeRefusal(final String target, final Path serverDir) throws Exception {
        final Method refuse = StoreDowngradeGuard.class.getDeclaredMethod("refuse", BuildVersion.class, Path.class);
        refuse.setAccessible(true);
        return (Optional<String>) refuse.invoke(null, BuildVersion.parse(target), serverDir);
    }

    // ------------------------------------------------------------------ WARN collector

    /** Collects the messages of the {@code SourbyCraft} logger at WARNING and above while open. */
    static final class Warnings implements AutoCloseable {

        private final Logger logger = Logger.getLogger("SourbyCraft");
        final List<String> messages = new CopyOnWriteArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) Warnings.this.messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        Warnings() {
            this.logger.addHandler(this.handler);
        }

        long containing(final String text) {
            return this.messages.stream().filter(message -> message.contains(text)).count();
        }

        @Override
        public void close() {
            this.logger.removeHandler(this.handler);
        }
    }

    // ------------------------------------------------------------------ engine

    /** What a hooked repository call does instead of (or around) the call itself. */
    interface Hook {
        Object invoke(Object[] args, Proceed proceed) throws Throwable;
    }

    interface Proceed {
        Object call() throws Throwable;
    }

    /**
     * The real {@link ProfileEngine} over a FILE store, as {@link CoopMembershipFixtures.CoopEngine} wires it, with a
     * {@link PlayingPorts} player port and a repository proxy that runs a {@link Hook} for any method named in
     * {@link #hooks}. Co-op events, ledger events and salvage deliveries go to their queues.
     */
    static final class Engine implements AutoCloseable {

        final Path root;
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-a", 600);
        final PlayingPorts ports;
        final FileProfileRepository file;
        final Map<String, Hook> hooks = new ConcurrentHashMap<>();
        final ProfileRepository repository;
        final ProfileEngine engine;
        final BlockingQueue<org.bukkit.event.Event> events = new LinkedBlockingQueue<>();
        final BlockingQueue<ProfileBank.LedgerEvent> ledger = new LinkedBlockingQueue<>();
        final BlockingQueue<ProfileEngine.SalvageDelivery> deliveries = new LinkedBlockingQueue<>();

        Engine(final Path root, final ProfileSettings settings, final PlayingPorts ports) throws IOException {
            this.root = root;
            this.ports = ports;
            this.file = new FileProfileRepository(root, "node-a");
            if (this.file.storeFormat().legacyFormat()) this.file.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            this.file.attachClaims(FileProfileClaims.of(this.profiles, this.players));
            this.repository = proxy(this.file);
            this.engine = new ProfileEngine(settings, this.repository, this.profiles, this.players, this.ports,
                ProfileWorldPort.NONE, "node-a", this.io, null, null);
            this.engine.onCoopEvent(this.events::add);
            this.engine.onLedgerEvent(this.ledger::add);
            this.engine.onSalvageDelivered(this.deliveries::add);
        }

        Engine(final Path root, final ProfileSettings settings) throws IOException {
            this(root, settings, new PlayingPorts());
        }

        private ProfileRepository proxy(final FileProfileRepository delegate) {
            final InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "Build54Fixtures.proxy(" + delegate + ")";
                    };
                }
                final Proceed proceed = () -> {
                    try {
                        return method.invoke(delegate, args);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                };
                final Hook hook = this.hooks.get(method.getName());
                return hook == null ? proceed.call() : hook.invoke(args, proceed);
            };
            return (ProfileRepository) Proxy.newProxyInstance(ProfileRepository.class.getClassLoader(),
                new Class<?>[] {ProfileRepository.class}, handler);
        }

        ProfileEngine.StoreStatus open() {
            final ProfileEngine.StoreStatus status = join(this.engine.open(CoopFixtures.NO_MIGRATION));
            assertEquals(StoreFormat.Gate.FORMAT_2, status.gate(), status.toString());
            return status;
        }

        AuroraProfile create(final dev.iyanz.sourbycraft.api.profile.ProfileRequest request) throws IOException {
            return this.file.profile(join(this.engine.createDetailed(request)).info().id()).orElseThrow();
        }

        AuroraProfile create(final UUID player, final String name) throws IOException {
            return create(dev.iyanz.sourbycraft.api.profile.ProfileRequest.of(player, "skyblock", CoopFixtures.CLASSIC).withName(name));
        }

        /** A co-op profile of {@code owner}: create, then convert, through the engine. */
        AuroraProfile coop(final UUID owner, final String name) throws IOException {
            return convert(owner, create(owner, name));
        }

        AuroraProfile convert(final UUID owner, final AuroraProfile solo) throws IOException {
            join(this.engine.convertToCoop(owner, solo.id()));
            return profile(solo.id());
        }

        /** Invite and accept through the engine; returns the member's row. */
        ProfileMember admit(final UUID owner, final UUID profileId, final UUID member) throws IOException {
            final dev.iyanz.sourbycraft.api.profile.CoopInviteInfo invite = join(this.engine.invite(owner, profileId, member));
            join(this.engine.acceptInvite(member, invite.id()));
            final ProfileMember row = profile(profileId).member(member);
            assertNotNull(row, member + " was not admitted");
            return row;
        }

        AuroraProfile profile(final UUID profileId) throws IOException {
            return this.file.profile(profileId).orElseThrow();
        }

        PlayerAccount account(final UUID player) throws IOException {
            return this.file.loadAccount(player, CoopFixtures.SLOTS);
        }

        /** Makes {@code profileId} the active profile of an offline {@code player} directly in the store. */
        void activate(final UUID player, final UUID profileId) throws IOException {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), grant.reason());
            try {
                CoopFixtures.activate(this.file, grant.claim(), profile(profileId));
            } finally {
                join(this.players.release(grant.claim()));
            }
        }

        /** The player joins: online, session admitted (no login gate). */
        void online(final UUID player) {
            this.ports.online.add(player);
            final ProfileEngine.Admission admission = join(this.engine.admitSession(player));
            assertTrue(admission.admitted(), admission.reason());
        }

        /** Online and admitted with the active profile's node claim taken, as a join does before play. */
        void playing(final UUID player) throws IOException {
            online(player);
            final UUID active = account(player).activeProfile();
            assertNotNull(active, player + " has no active profile");
            final ClaimResult claim = join(this.engine.claimSession(player, active));
            assertTrue(claim.granted(), "the node claim of " + active);
        }

        void switched(final UUID player, final UUID target) {
            final ProfileSwitchResult result = join(this.engine.switchProfile(player, target));
            assertTrue(result.success(), result.errorCode() + ": " + result.message());
        }

        /** The runtime's quit: ticket, capture of what is on the player, the final save, release. Returns whether it saved. */
        boolean quit(final UUID player) {
            final ProfileEngine.CaptureTicket ticket = this.engine.captureTicket(player);
            final PlayerClaim session = this.engine.playerSessionClaim(player);
            boolean saved = false;
            if (ticket != null && ticket.profileId() != null) {
                try {
                    saved = join(this.engine.saveCapturedAndClose(player, PlayerStatePort.CaptureResult.of(this.ports.state(player)), ticket));
                } catch (final dev.iyanz.sourbycraft.api.profile.ProfileOperationException refused) {
                    saved = false;
                }
            }
            this.ports.online.remove(player);
            join(this.engine.releaseSession(player));
            if (session != null) join(this.engine.releasePlayerSession(player, session));
            return saved;
        }

        /** The join under authoritative login in the runtime's order; returns the login restore. */
        ProfileEngine.LoginRestore rejoin(final UUID player) throws IOException {
            this.ports.online.add(player);
            final UUID gate = this.engine.gateLogin(player);
            assertNotNull(gate, "authoritative login gates the join");
            final ProfileEngine.Admission admission = join(this.engine.admitSession(player));
            assertTrue(admission.admitted(), admission.reason());
            join(this.engine.recoverPending(player));
            final UUID active = account(player).activeProfile();
            if (active != null) assertTrue(join(this.engine.claimSession(player, active)).granted(), "the node claim of " + active);
            final ProfileEngine.LoginRestore restore = join(this.engine.restoreOnLogin(player, gate, () -> false, false));
            this.engine.releaseLoginGate(player, gate);
            return restore;
        }

        boolean save(final UUID player, final ProfileState state) {
            return join(this.engine.saveCaptured(player, PlayerStatePort.CaptureResult.of(state), this.engine.captureTicket(player)));
        }

        MemberStateHead head(final UUID profileId, final UUID player, final UUID epoch) throws IOException {
            return this.file.memberHead(profileId, player, epoch).orElseThrow(() -> new AssertionError("no head " + profileId + "/" + player));
        }

        /** The state the exact snapshot of a READY head holds. */
        ProfileState stateOf(final MemberStateHead head) throws IOException {
            assertEquals(MemberStateHead.State.READY, head.state(), head.describe());
            return snapshotState(head.profileId(), head.memberId(), head.epoch(), head.snapshotId());
        }

        ProfileState snapshotState(final UUID profileId, final UUID player, final UUID epoch, final UUID snapshotId) throws IOException {
            final ProfileSnapshot snapshot = this.file.memberSnapshot(profileId, player, epoch, snapshotId)
                .orElseThrow(() -> new AssertionError("snapshot " + snapshotId + " of " + profileId + "/" + player + " is not stored"));
            return ProfileStateCodec.decode(snapshot.state());
        }

        /** The outstanding salvage of a profile (first page, oldest first). */
        List<ProfileSalvage> salvage(final UUID profileId) throws IOException {
            return CoopMembership.salvageByProfile(this.file, profileId, null).items();
        }

        ProfileSalvage salvageRecord(final UUID salvageId) throws IOException {
            return this.file.salvage(salvageId).orElseThrow(() -> new AssertionError("no salvage " + salvageId));
        }

        ProfileBank.LedgerEvent nextLedger() throws InterruptedException {
            final ProfileBank.LedgerEvent event = this.ledger.poll(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(event, "no ledger event within " + CoopFixtures.WAIT_SECONDS + " s");
            return event;
        }

        /** Takes ledger events (bounded waits) until one of {@code entry}; returns it. */
        ProfileBank.LedgerEvent nextLedgerOf(final ProfileBank.Entry entry) throws InterruptedException {
            for (int i = 0; i < 1024; i++) {
                final ProfileBank.LedgerEvent event = nextLedger();
                if (event.entry() == entry) return event;
            }
            throw new AssertionError("no " + entry + " ledger event");
        }

        ProfileEngine.SalvageDelivery nextDelivery() throws InterruptedException {
            final ProfileEngine.SalvageDelivery event = this.deliveries.poll(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(event, "no salvage delivery event within " + CoopFixtures.WAIT_SECONDS + " s");
            return event;
        }

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
        }
    }
}
