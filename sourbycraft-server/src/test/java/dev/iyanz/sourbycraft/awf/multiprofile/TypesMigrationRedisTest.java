package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileNaming;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review P2-3: the file-to-Redis import of a format-1 store copies the stored profile type definitions
 * ({@code types/<id>.awpt}) into the Redis hash {@code <prefix>mp:types} with their owners, so no type becomes unknown
 * (denied) or, for a built-in override, loses its denies on the way; an unreadable stored definition stops the import
 * before anything is written to Redis instead of being dropped. Each test starts its own {@code redis-server} ({@link RedisTestServer}); without the binary
 * the tests are skipped, and with {@code SOURBY_REQUIRE_REDIS=1} (CI) they fail instead.
 */
class TypesMigrationRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private RedisProfileRepository redis;
    private FileProfileRepository file;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings settings = new RedisSettings(this.server.uri(), "t:types54:", 4, 1_000, 30, false);
        this.fabric = RedisFabric.start(FabricSettings.parse(Map.of(FabricSettings.ENABLED_KEY, true)).settings(), settings, "node-a");
        this.redis = RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true, false),
            this.directory.resolve("no-file-store"));
        this.file = new FileProfileRepository(this.directory.resolve("store"), "node-a");
        // A format-1 store: one account with one profile.
        final UUID player = UUID.randomUUID();
        final UUID profile = UUID.randomUUID();
        this.file.saveProfile(new AuroraProfile(profile, player, "skyblock", Map.of(), 100L, 0L, ProfileStatus.ACTIVE));
        this.file.saveAccount(PlayerAccount.empty(player, SLOTS).withProfile(profile));
        assertTrue(this.file.storeFormat().legacyFormat());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.file != null) this.file.close();
        if (this.redis != null) this.redis.close();
        if (this.fabric != null) this.fabric.close();
        if (this.server != null) this.server.close();
    }

    private static ProfileTypeDefinition hardcore() {
        return ProfileTypeDefinition.builder("hardcore").displayName("Hardcore").coopAllowed(false)
            .naming(ProfileNaming.pool(List.of("Ember", "Cinder"))).deny("trade").build();
    }

    private static ProfileTypeDefinition ironman() {
        return ProfileTypeDefinition.builder("ironman").deny(ProfileTypeRegistry.BANK_DEPOSIT).deny(ProfileTypeRegistry.BANK_WITHDRAW).build();
    }

    @Test
    void theImportCopiesTheStoredTypeDefinitions() throws Exception {
        this.file.typesStore().save("hardcore", ProfileTypesStore.encode(hardcore(), "TypesPlugin"));
        this.file.typesStore().save("ironman", ProfileTypesStore.encode(ironman(), "TypesPlugin"));

        ProfileRepositoryMigration.fileToRedis(this.file, this.redis);

        final Map<String, ProfileTypesStore.Stored> copied = this.redis.typesStore().load(ProfileTypes.MAX_TYPES + ProfileTypes.BUILTINS.size());
        assertEquals(Map.of("hardcore", new ProfileTypesStore.Stored(hardcore(), "TypesPlugin"),
            "ironman", new ProfileTypesStore.Stored(ironman(), "TypesPlugin")), copied);
        // The directory is kept as it was.
        assertTrue(Files.isRegularFile(this.directory.resolve("store").resolve("types").resolve("hardcore.awpt")));
    }

    @Test
    void anUnreadableStoredDefinitionStopsTheImport() throws Exception {
        this.file.typesStore().save("hardcore", ProfileTypesStore.encode(hardcore(), "TypesPlugin"));
        final Path types = this.directory.resolve("store").resolve("types");
        Files.write(types.resolve("broken.awpt"), new byte[] {9, 9, 9});
        final IOException refused = assertThrows(IOException.class, () -> ProfileRepositoryMigration.fileToRedis(this.file, this.redis));
        assertTrue(refused.getMessage().contains("broken") && refused.getMessage().contains("unreadable"), refused.getMessage());
        // Build 54 verification: the definitions are checked before the import begins, so no account or profile reached Redis.
        assertTrue(this.redis.profileIds().isEmpty(), "profiles were imported before the unreadable definition stopped the import");
        assertTrue(this.redis.accountIds().isEmpty(), "accounts were imported before the unreadable definition stopped the import");
    }
}
