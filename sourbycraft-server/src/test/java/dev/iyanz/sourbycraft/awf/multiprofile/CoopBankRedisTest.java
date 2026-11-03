package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileNaming;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeSource;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.Currency;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.MemberKey;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 54 bank and type-registry cases on the Redis store (COOP-DESIGN §8, §10 slice 6;
 * docs/awf/multiprofile/PROFILE-TYPES.md): balances at the edge of the {@code long} range survive Redis exactly (the
 * store compares revisions and installs bytes, Java does the arithmetic), a transfer intent posts once and replays as
 * REPLAYED with its receipt under the profile, and profile type definitions persist in the {@code <prefix>mp:types}
 * hash (an unreadable field is skipped, its type unknown). Each test starts its own {@code redis-server}
 * ({@link RedisTestServer}); without the binary the tests are skipped, and with {@code SOURBY_REQUIRE_REDIS=1} (CI)
 * they fail instead.
 */
class CoopBankRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private RedisProfileRepository repository;
    private PlayerSessionOwnership players;
    private RedisProfileOwnership profiles;
    private ProfileTypes types;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:coop54:", 4, 1_000, 30, false);
        final FabricSettings settings = FabricSettings.parse(Map.of(FabricSettings.ENABLED_KEY, true)).settings();
        this.fabric = RedisFabric.start(settings, redis, "node-a");
        this.repository = open();
        this.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
        this.players = PlayerSessionOwnership.redis(this.fabric.sessions().coopClaims(), "node-a");
        this.profiles = new RedisProfileOwnership(this.fabric.sessions(), "node-a", 1_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        Build54Fixtures.closeTypes(this.types);
        if (this.repository != null) this.repository.close();
        if (this.fabric != null) this.fabric.close();
        if (this.server != null) this.server.close();
    }

    private RedisProfileRepository open() throws IOException {
        return RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true, false),
            this.directory.resolve("no-file-store"));
    }

    private void reopen() throws IOException {
        this.repository.close();
        this.repository = open();
    }

    private PlayerClaim q(final UUID player) {
        final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
        assertTrue(grant.granted(), grant.reason());
        return grant.claim();
    }

    private ProfileClaim p(final UUID profile) {
        final ProfileOwnership.TransactionGrant grant = join(this.profiles.claimTransaction(profile, UUID.randomUUID()));
        assertTrue(grant.granted(), grant.reason());
        return grant.claim();
    }

    private ProfileTypesStore typesStore() {
        return ProfileTypesStore.redis(this.fabric.storageClient(), this.fabric.prefix() + "mp:types");
    }

    // ------------------------------------------------------------------ CoopRedisLongBalanceExact

    /**
     * Shared and personal balances of {@code Long.MAX_VALUE - 1} and {@code Long.MAX_VALUE} are stored and read back
     * exactly, also after a reopen; a transfer of almost the whole range moves it exactly in one intent with its receipt
     * under the profile, and a replay posts nothing; an overflow is refused in Java before Redis is asked anything.
     */
    @Test
    void CoopRedisLongBalanceExact() throws Exception {
        final UUID owner = UUID.randomUUID();
        final PlayerClaim q = q(owner);
        final AuroraProfile profile = CoopFixtures.create(this.repository, q, "Apple", CLASSIC);
        CoopFixtures.activate(this.repository, q, profile);
        final ProfileClaim p = p(profile.id());
        final ProfileBank.Policy noCaps = new ProfileBank.Policy(new Currency("aurora", "coins", 0), 0L, 0, 10);
        final MemberKey key = MemberKey.of(this.repository.profile(profile.id()).orElseThrow().member(owner));

        final ProfileSharedState first = noCaps.empty(profile.id()).depositShared(Long.MAX_VALUE - 1L).atRevision(0L);
        committed(this.repository.mutateShared(first, CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, owner), p, q,
            this.repository.loadAccount(owner, SLOTS).revision(), this.repository.profile(profile.id()).orElseThrow().revision(),
            CoopWriteGuard.ABSENT)));
        assertEquals(Long.MAX_VALUE - 1L, this.repository.shared(profile.id()).orElseThrow().sharedBalance());
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> this.repository.shared(profile.id()).orElseThrow().depositShared(2L));

        final ProfileSharedState stored = this.repository.shared(profile.id()).orElseThrow();
        final ProfileSharedState full = stored.depositShared(1L);
        committed(this.repository.mutateShared(full, CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, owner), p, q,
            this.repository.loadAccount(owner, SLOTS).revision(), this.repository.profile(profile.id()).orElseThrow().revision(),
            stored.revision())));
        reopen();
        assertEquals(Long.MAX_VALUE, this.repository.shared(profile.id()).orElseThrow().sharedBalance(), "Redis lost precision");

        // A transfer of Long.MAX_VALUE - 7 into the personal bank: one intent, exact.
        final ProfileSharedState before = this.repository.shared(profile.id()).orElseThrow();
        final long amount = Long.MAX_VALUE - 7L;
        final ProfileSharedState after = ProfileBank.transferred(before, key, ProfileBank.Account.SHARED, ProfileBank.Account.PERSONAL,
            amount, noCaps);
        final CoopOperation transfer = op(CoopOperation.Kind.BANK_TRANSFER, owner);
        final CoopIntentPlan plan = ProfileBank.transferPlan(transfer, this.repository.profile(profile.id()).orElseThrow(),
            this.repository.loadAccount(owner, SLOTS), before, true, after, this.repository.storeClockMillis() + 120_000L);
        CoopMembershipFixtures.runPlan(this.repository, plan, p, q, null);
        reopen();
        final ProfileSharedState posted = this.repository.shared(profile.id()).orElseThrow();
        assertEquals(7L, posted.sharedBalance());
        assertEquals(amount, posted.personalBalance(key));
        assertEquals(before.revision() + 2L, posted.revision());
        assertNull(posted.pendingIntent());
        assertEquals(ProfileReceipt.Outcome.COMMITTED, this.repository.receipt(profile.id(), transfer.operationId()).orElseThrow()
            .outcome(), "Redis keeps the intent receipt under the profile");
        assertEquals(CommitResult.Status.REPLAYED, CoopMembershipFixtures.commitPlan(this.repository, plan, p, q, null).status());
        assertEquals(posted, this.repository.shared(profile.id()).orElseThrow(), "a replayed transfer posted again");

        // The personal bank at the top of the range, then one more: refused before any write.
        final ProfileSharedState top = posted.depositPersonal(key, 7L, 0L);
        assertEquals(Long.MAX_VALUE, top.personalBalance(key));
        committed(this.repository.mutateShared(top, CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, owner), p, q,
            this.repository.loadAccount(owner, SLOTS).revision(), this.repository.profile(profile.id()).orElseThrow().revision(),
            posted.revision())));
        reopen();
        assertEquals(Long.MAX_VALUE, this.repository.shared(profile.id()).orElseThrow().personalBalance(key));
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> this.repository.shared(profile.id()).orElseThrow()
            .depositPersonal(key, 1L, 0L));
    }

    // ------------------------------------------------------------------ type definitions in Redis

    /**
     * {@code register} stores the definition as one field of {@code <prefix>mp:types}; a new registry over the same hash
     * reads it back as STORED with its owner and rules; an unreadable field is skipped (its type unknown, denied).
     */
    @Test
    void profileTypeDefinitionsPersistInRedis() throws Exception {
        final Plugin plugin = MemberPlayFixtures.plugin("TypesPlugin");
        final ProfileTypeDefinition hardcore = ProfileTypeDefinition.builder("hardcore").displayName("Hardcore").coopAllowed(false)
            .maxMembers(2).naming(ProfileNaming.pool(List.of("Ember", "Cinder"))).deny("trade")
            .deny(ProfileTypeRegistry.BANK_WITHDRAW).build();
        this.types = ProfileTypes.open(null, ProfileSettings.DEFAULT_TYPE_REGISTRY, typesStore());
        this.types.register(plugin, hardcore);
        this.types.register(plugin, ProfileTypeDefinition.builder("ironman").deny(ProfileTypeRegistry.BANK_DEPOSIT).build());
        Build54Fixtures.closeTypes(this.types);
        this.types = null;
        assertEquals(2L, ((Number) this.fabric.storageClient().call("HLEN", this.fabric.prefix() + "mp:types")).longValue());

        this.fabric.storageClient().call("HSET", this.fabric.prefix() + "mp:types", "broken", new byte[] {1, 2, 3});
        this.types = ProfileTypes.open(null, ProfileSettings.DEFAULT_TYPE_REGISTRY, typesStore());
        assertEquals(hardcore, this.types.definition("hardcore").orElseThrow());
        assertEquals(ProfileTypeSource.STORED, this.types.source("hardcore").orElseThrow());
        assertEquals("TypesPlugin", this.types.owner("hardcore").orElseThrow());
        assertFalse(this.types.allows("hardcore", ProfileTypeRegistry.BANK_WITHDRAW));
        assertFalse(this.types.allows("ironman", ProfileTypeRegistry.BANK_DEPOSIT), "the stored built-in override was not read");
        // Review P2-3: an unreadable field is a deny-all tombstone, never forgotten.
        assertEquals(ProfileTypes.unreadableDefinition("broken"), this.types.definition("broken").orElseThrow());
        assertEquals(ProfileTypeSource.STORED, this.types.source("broken").orElseThrow());
        assertFalse(this.types.allows("broken", "trade"), "an unreadable definition was allowed");
        assertFalse(this.types.allows("broken", ProfileTypeRegistry.BANK_DEPOSIT));
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> this.types.register(MemberPlayFixtures.plugin("OtherPlugin"), hardcore));
    }
}
