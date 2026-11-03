package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.function.Executable;

/**
 * Shared fixtures of the Build 51 co-op owner cases (COOP-DESIGN §10 slices 2 and 3).
 *
 * <p>Everything here is deterministic: fixed or random ids, no sleeps, no wall-clock dependence beyond the
 * stores' own lease clocks (whose leases are far longer than any test). Intents are built exactly the way
 * {@link ProfileEngine} builds its solo intents (stage the after-images, PREPARE under the claims with the
 * pre-reservation vector, COMMIT with the post-reservation vector), so a crash can be simulated between any
 * two steps by closing the repository and editing its files.</p>
 */
final class CoopFixtures {

    static final dev.iyanz.sourbycraft.api.profile.ProfileType CLASSIC = dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC;
    static final dev.iyanz.sourbycraft.api.profile.ProfileType IRONMAN = dev.iyanz.sourbycraft.api.profile.ProfileType.IRONMAN;
    static final dev.iyanz.sourbycraft.api.profile.ProfileType STRANDED = dev.iyanz.sourbycraft.api.profile.ProfileType.STRANDED;
    static final dev.iyanz.sourbycraft.api.profile.ProfileType BINGO = dev.iyanz.sourbycraft.api.profile.ProfileType.BINGO;
    static final int SLOTS = 3;
    static final int RETAIN = 3;
    static final String BUILD = "b51-test";
    /** Upper bound for any future a test waits on; the futures here complete in milliseconds. */
    static final long WAIT_SECONDS = 10L;

    private CoopFixtures() {}

    /** {@code 00000000-0000-0000-xxxx-xxxxxxxxxxxx}: readable fixed ids for golden bytes. */
    static UUID u(final long n) {
        return new UUID(0L, n);
    }

    static byte[] state() {
        return ProfileStateCodec.encode(ProfileState.empty());
    }

    static CoopOperation op(final CoopOperation.Kind kind, final UUID actor) {
        final UUID id = UUID.randomUUID();
        return new CoopOperation(id, kind, actor, CoopOperation.digest(kind, actor, id), 1_000L);
    }

    static <T> T join(final CompletableFuture<T> future) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final java.util.concurrent.ExecutionException failed) {
            final Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new CompletionException(cause);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (final java.util.concurrent.TimeoutException timeout) {
            throw new AssertionError("a future did not complete within " + WAIT_SECONDS + " s", timeout);
        }
    }

    /** The {@link ProfileOperationException} a call raised directly or through a completion. */
    static ProfileOperationException refusal(final Executable call) {
        try {
            call.execute();
        } catch (final ProfileOperationException refused) {
            return refused;
        } catch (final CompletionException | java.util.concurrent.ExecutionException wrapped) {
            if (wrapped.getCause() instanceof ProfileOperationException refused) return refused;
            throw new AssertionError("expected a ProfileOperationException, got " + wrapped.getCause(), wrapped.getCause());
        } catch (final Throwable other) {
            throw new AssertionError("expected a ProfileOperationException, got " + other, other);
        }
        throw new AssertionError("expected a ProfileOperationException, but the call returned normally");
    }

    static void refusedWith(final ProfileErrorCode code, final Executable call) {
        final ProfileOperationException refused = refusal(call);
        assertEquals(code, refused.code(), refused.getMessage());
    }

    static void committed(final CommitResult result) {
        assertEquals(CommitResult.Status.COMMITTED, result.status(), result.detail());
    }

    // ------------------------------------------------------------------ file trees

    /**
     * Relative path to SHA-256 of every regular file below {@code root}. The lock file, temporaries and {@code staging/}
     * are excluded: staging holds uncommitted after-images outside the commit decision (as {@code CoopRedisTest.keyspace}
     * excludes Redis staging keys), and a refused prepare deliberately removes its own staging (review P3-17).
     */
    static Map<String, String> tree(final Path root) throws IOException {
        final TreeMap<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(root)) return files;
        try (var walk = Files.walk(root)) {
            for (final Path file : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(file)) continue;
                final String name = file.getFileName().toString();
                if (name.equals("repository.lock") || name.endsWith(".tmp")) continue;
                final String relative = root.relativize(file).toString();
                if (relative.startsWith("staging" + java.io.File.separator)) continue;
                files.put(relative, sha256(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Copies a directory tree (replacing what is there); a missing source copies nothing. */
    static void copyTree(final Path source, final Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (var walk = Files.walk(source)) {
            for (final Path from : (Iterable<Path>) walk::iterator) {
                final Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    // ------------------------------------------------------------------ claims

    /**
     * Commit-time claim validator over the local providers, with a switch that makes every claim read as
     * not live: a lease lost between the caller's pre-check and the repository's commit.
     */
    static final class SwitchableClaims implements FileProfileClaims {

        final FileProfileClaims delegate;
        final AtomicBoolean live = new AtomicBoolean(true);

        SwitchableClaims(final FileProfileClaims delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean profileClaimLive(final ProfileClaim claim) throws IOException {
            return this.live.get() && this.delegate.profileClaimLive(claim);
        }

        @Override
        public boolean playerClaimLive(final PlayerClaim claim) throws IOException {
            return this.live.get() && this.delegate.playerClaimLive(claim);
        }
    }

    /**
     * One local node: strict {@code P} and {@code Q} providers and a ready format-2 FILE store whose commit-time
     * validator is built over them (as {@code ProfileRuntime} does).
     */
    static final class FileStore implements AutoCloseable {

        final Path root;
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-a", 600);
        final SwitchableClaims claims = new SwitchableClaims(FileProfileClaims.of(this.profiles, this.players));
        FileProfileRepository repository;

        private FileStore(final Path root) throws IOException {
            this.root = root;
            this.repository = new FileProfileRepository(root, "node-a");
            this.repository.attachClaims(this.claims);
        }

        /** A new, empty, ready format-2 store. */
        static FileStore ready(final Path root) throws IOException {
            final FileStore store = new FileStore(root);
            store.repository.writeStoreFormat(StoreFormat.Marker.ready(BUILD));
            return store;
        }

        /** An existing store as it is on disk (any format). */
        static FileStore open(final Path root) throws IOException {
            return new FileStore(root);
        }

        /** Simulates a process crash and restart: the directory is closed and opened again (open-time recovery runs). */
        void reopen() throws IOException {
            this.repository.close();
            this.repository = new FileProfileRepository(this.root, "node-a");
            this.repository.attachClaims(this.claims);
        }

        PlayerClaim q(final UUID player) {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), "Q of " + player + ": " + grant.reason());
            return grant.claim();
        }

        ProfileClaim p(final UUID profile) {
            final ProfileOwnership.TransactionGrant grant = join(this.profiles.claimTransaction(profile, UUID.randomUUID()));
            assertTrue(grant.granted(), "P of " + profile + ": " + grant.reason());
            return grant.claim();
        }

        @Override
        public void close() {
            this.repository.close();
        }
    }

    // ------------------------------------------------------------------ intents

    /** One after-image an intent installs, encoded at the revision COMMIT writes. */
    record Image(byte type, UUID id, long revision, byte[] payload) {}

    /**
     * Stages the after-images and PREPAREs the intent (engine order); returns the stored PREPARED intent.
     */
    static ProfileIntent prepare(final ProfileRepository repository, final CoopOperation op, final ProfileClaim p,
                                 final PlayerClaim q, final List<Expected> before, final List<Expected> after,
                                 final List<Image> images, final List<ProfileIntent.SnapshotRef> pins) throws IOException {
        final List<ProfileIntent.BlobRef> refs = stage(repository, op, images);
        final CoopWriteGuard guard = CoopWriteGuard.intent(op, p, q, CoopWriteGuard.ABSENT, before, Set.of());
        final ProfileIntent intent = ProfileIntent.prepared(guard, after, List.of(), refs, pins, 0L);
        committed(repository.prepareIntent(intent, guard));
        final ProfileIntent stored = repository.intent(op.operationId()).orElseThrow();
        assertEquals(ProfileIntent.Phase.PREPARED, stored.phase());
        return stored;
    }

    static List<ProfileIntent.BlobRef> stage(final ProfileRepository repository, final CoopOperation op, final List<Image> images)
        throws IOException {
        final List<ProfileIntent.BlobRef> refs = new ArrayList<>(images.size());
        for (final Image image : images) {
            refs.add(repository.stageAfterImage(op.operationId(), image.type(), image.id(), null, null, image.revision(),
                image.payload()));
        }
        return refs;
    }

    /** The guard of a commit, abort or resolve: the intent's own revision, its post-reservation vector and its reservation. */
    static CoopWriteGuard intentGuard(final CoopOperation op, final ProfileClaim p, final PlayerClaim q, final ProfileIntent stored) {
        return CoopWriteGuard.intent(op, p, q, stored.revision(), stored.expectedAfter(), Set.of(op.operationId()));
    }

    static CommitResult commit(final ProfileRepository repository, final CoopOperation op, final ProfileClaim p,
                               final PlayerClaim q) throws IOException {
        final ProfileIntent stored = repository.intent(op.operationId()).orElseThrow();
        return repository.commitIntent(op.operationId(), intentGuard(op, p, q, stored));
    }

    static CommitResult abort(final ProfileRepository repository, final CoopOperation op, final ProfileClaim p,
                              final PlayerClaim q) throws IOException {
        final ProfileIntent stored = repository.intent(op.operationId()).orElseThrow();
        return repository.abortIntent(op.operationId(), intentGuard(op, p, q, stored));
    }

    /**
     * The solo create of {@link ProfileEngine#createDetailed}: reserve the account ({@code A + 1}), install the
     * new profile record ({@code M} absent, revision 0) and the account with the slot and alias ({@code A + 2}).
     */
    record CreatePlan(CoopOperation op, AuroraProfile profile, PlayerAccount accountBefore, PlayerAccount accountAfter,
                      List<Expected> before, List<Expected> after, List<Image> images) {}

    static CreatePlan planCreate(final ProfileRepository repository, final UUID player, final String name,
                                 final dev.iyanz.sourbycraft.api.profile.ProfileType type) throws IOException {
        final PlayerAccount account = repository.loadAccount(player, SLOTS);
        final AuroraProfile profile = AuroraProfile.create(player, "skyblock", type, name, Map.of("slot", "1"), 1_000L);
        final String alias = CuteNames.allocateAlias(CuteNames.alias(name), account.takenAliases());
        final long a = account.revision();
        final PlayerAccount after = account.withProfile(profile.id(), alias).atRevision(a + 2L);
        final CoopOperation op = op(CoopOperation.Kind.CREATE_PROFILE, player);
        return new CreatePlan(op, profile, account, after,
            List.of(Expected.account(player, a), Expected.metadata(profile.id(), CoopWriteGuard.ABSENT)),
            List.of(Expected.account(player, a + 1L), Expected.metadata(profile.id(), CoopWriteGuard.ABSENT)),
            List.of(new Image(ProfileRecords.TYPE_PROFILE, profile.id(), 0L, ProfileRecords.profilePayload2(profile)),
                new Image(ProfileRecords.TYPE_ACCOUNT, player, after.revision(), ProfileRecords.accountPayload2(after))));
    }

    static ProfileIntent prepareCreate(final ProfileRepository repository, final PlayerClaim q, final CreatePlan plan)
        throws IOException {
        return prepare(repository, plan.op(), null, q, plan.before(), plan.after(), plan.images(), List.of());
    }

    /** Creates a profile through one committed intent; returns the stored profile. */
    static AuroraProfile create(final ProfileRepository repository, final PlayerClaim q, final String name,
                                final dev.iyanz.sourbycraft.api.profile.ProfileType type) throws IOException {
        final CreatePlan plan = planCreate(repository, q.playerId(), name, type);
        prepareCreate(repository, q, plan);
        committed(commit(repository, plan.op(), null, q));
        return repository.profile(plan.profile().id()).orElseThrow();
    }

    /**
     * The solo delete of {@code ProfileEngine.deleteFormat2}: reserve metadata and account, install the
     * tombstone ({@code M + 2}) and the account without the profile ({@code A + 2}).
     */
    record DeletePlan(CoopOperation op, List<Expected> before, List<Expected> after, List<Image> images) {}

    static DeletePlan planDelete(final ProfileRepository repository, final AuroraProfile profile) throws IOException {
        final PlayerAccount account = repository.loadAccount(profile.accountId(), SLOTS);
        final long m = profile.revision();
        final long a = account.revision();
        final AuroraProfile deleted = profile.withStatus(ProfileStatus.DELETED).atRevision(m + 2L);
        final PlayerAccount unlisted = account.withoutProfile(profile.id()).atRevision(a + 2L);
        return new DeletePlan(op(CoopOperation.Kind.DELETE_PROFILE, profile.accountId()),
            List.of(Expected.metadata(profile.id(), m), Expected.account(profile.accountId(), a)),
            List.of(Expected.metadata(profile.id(), m + 1L), Expected.account(profile.accountId(), a + 1L)),
            List.of(new Image(ProfileRecords.TYPE_PROFILE, profile.id(), deleted.revision(), ProfileRecords.profilePayload2(deleted)),
                new Image(ProfileRecords.TYPE_ACCOUNT, profile.accountId(), unlisted.revision(),
                    ProfileRecords.accountPayload2(unlisted))));
    }

    /** Makes {@code profile} the account's active profile with the owner's epoch (a switch commit of the account only). */
    static PlayerAccount activate(final ProfileRepository repository, final PlayerClaim q, final AuroraProfile profile)
        throws IOException {
        final UUID player = q.playerId();
        final PlayerAccount account = repository.loadAccount(player, SLOTS);
        final PlayerAccount after = account.withActive(profile.id(), profile.member(player).epoch());
        final CoopWriteGuard guard = CoopWriteGuard.switchActive(op(CoopOperation.Kind.SWITCH_ACTIVE, player), q,
            account.revision(), account.activeProfile(), List.of(Expected.metadata(profile.id(), profile.revision())));
        committed(repository.commitAccount(after, guard));
        return repository.loadAccount(player, SLOTS);
    }

    /** Publishes the next snapshot of the member's own stream under {@link CoopWriteGuard#memberSave}; returns the new head. */
    static MemberStateHead save(final ProfileRepository repository, final PlayerClaim q, final UUID profileId)
        throws IOException {
        final UUID player = q.playerId();
        final AuroraProfile profile = repository.profile(profileId).orElseThrow();
        final UUID epoch = profile.member(player).epoch();
        final Optional<MemberStateHead> head = repository.memberHead(profileId, player, epoch);
        final MemberStateHead base = head.orElseGet(() -> MemberStateHead.uninitialized(profileId, player, epoch));
        final long expectedHead = head.isPresent() ? head.get().revision() : CoopWriteGuard.ABSENT;
        final CoopOperation op = op(CoopOperation.Kind.SAVE_MEMBER, player);
        final ProfileSnapshot snapshot = ProfileSnapshot.forMember(profileId, player, epoch, base.revision() + 1L, state(),
            op.operationId());
        final MemberStateHead next = base.published(snapshot);
        final PlayerAccount account = repository.loadAccount(player, SLOTS);
        committed(repository.publishMemberSnapshot(next, snapshot, RETAIN, CoopWriteGuard.memberSave(op, q, profileId, epoch,
            account.revision(), profile.revision(), expectedHead)));
        return repository.memberHead(profileId, player, epoch).orElseThrow();
    }

    // ------------------------------------------------------------------ engine

    /** Player and world ports that do nothing: the names, types and recovery cases never touch a player. */
    static final class QuietPlayers implements PlayerStatePort {

        final Set<UUID> online = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean online(final UUID playerId) {
            return this.online.contains(playerId);
        }

        @Override
        public CompletableFuture<ProfileState> capture(final UUID playerId, final ProfileIsolation isolation) {
            return CompletableFuture.completedFuture(ProfileState.empty());
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> teleport(final UUID playerId, final ProfileState.Location location) {
            return CompletableFuture.completedFuture(null);
        }
    }

    static ProfileSettings settings(final ProfileSettings.Types types) {
        return new ProfileSettings(true, ProfileSettings.Mode.STANDALONE, SLOTS, 8,
            new ProfileSettings.Switching(true, 15_000, true), ProfileIsolation.ALL, new ProfileSettings.Worlds(0),
            new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true), "awf/profiles",
            ProfileSettings.DEFAULT_REPOSITORY, types, ProfileSettings.DEFAULT_NAMES, ProfileSettings.DEFAULT_MIGRATION);
    }

    /** A store migration that must never run (the stores the engine cases open need none). */
    static final ProfileEngine.StoreMigration NO_MIGRATION = (repository, apply) -> {
        throw new AssertionError("the engine ran a migration it did not need");
    };

    /**
     * An engine over a FILE store with local providers that the store's commit-time validator shares, as
     * {@code ProfileRuntime} wires them. {@link #open} decides the store format before any call.
     */
    static final class EngineHarness implements AutoCloseable {

        final Path root;
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-a", 600);
        final QuietPlayers ports = new QuietPlayers();
        final FileProfileRepository repository;
        final ProfileEngine engine;

        EngineHarness(final Path root, final ProfileSettings settings, final boolean format2) throws IOException {
            this.root = root;
            this.repository = new FileProfileRepository(root, "node-a");
            if (format2 && this.repository.storeFormat().legacyFormat()) {
                this.repository.writeStoreFormat(StoreFormat.Marker.ready(BUILD));
            }
            this.repository.attachClaims(FileProfileClaims.of(this.profiles, this.players));
            this.engine = new ProfileEngine(settings, this.repository, this.profiles, this.players, this.ports,
                ProfileWorldPort.NONE, "node-a", this.io, null, null);
        }

        ProfileEngine.StoreStatus open() {
            return join(this.engine.open(NO_MIGRATION));
        }

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
        }
    }
}
