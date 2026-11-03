package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.IRONMAN;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileNaming;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileRule;
import dev.iyanz.sourbycraft.api.profile.ProfileRuleSet;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeSource;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Engine;
import dev.iyanz.sourbycraft.awf.multiprofile.Build54Fixtures.Warnings;
import dev.iyanz.sourbycraft.update.StoreDowngradeGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 54 profile type registry (docs/awf/multiprofile/PROFILE-TYPES.md): registration, override of a built-in,
 * unregister and plugin disable keeping the definition in force as STORED (a deny is never lifted), the fail-safe deny
 * of an unknown type with one WARN, the engine-owned rules gating bank, co-op, collections and salvage actions (the
 * owner's example: Ironman without a bank through a registered definition), the consumer's own rules, a type's naming
 * pool with account-unique names, the FILE persistence of definitions, and the store level 54 that the first profile of
 * a registered type raises (Build 53 refused by the OD-2 gate).
 *
 * <p>The registry is the process-wide {@link ProfileTypes#get()}; every case closes what it opens ({@link #closeAll}), so
 * the built-ins-only registry is in force again for the next class. Deterministic: no sleeps; a registry's background
 * store write is awaited by {@link ProfileTypes#close}.</p>
 */
class ProfileTypeRegistryTest {

    @TempDir
    Path directory;

    private final List<ProfileTypes> opened = new ArrayList<>();
    private final Plugin plugin = MemberPlayFixtures.plugin("TypesPlugin");
    private final Plugin otherPlugin = MemberPlayFixtures.plugin("OtherPlugin");

    @AfterEach
    void closeAll() {
        for (final ProfileTypes types : this.opened) Build54Fixtures.closeTypes(types);
        this.opened.clear();
        // The built-ins-only registry is in force again: it registers nothing.
        refusedWith(ProfileErrorCode.DISABLED, () -> ProfileTypes.get().register(this.plugin, hardcore()));
    }

    private ProfileTypes open(final String store, final ProfileSettings.TypeRegistry config) throws Exception {
        final ProfileTypes types = Build54Fixtures.openTypes(this.directory.resolve(store), config);
        this.opened.add(types);
        assertSame(types, ProfileTypes.get(), "the opened registry is not the one in force");
        return types;
    }

    private ProfileTypes open(final String store) throws Exception {
        return open(store, ProfileSettings.DEFAULT_TYPE_REGISTRY);
    }

    private void close(final ProfileTypes types) {
        Build54Fixtures.closeTypes(types);
        this.opened.remove(types);
    }

    private Path serverDir() {
        return this.directory.resolve("server");
    }

    private Path store() {
        return serverDir().resolve(ProfileSettings.DIRECTORY_DEFAULT);
    }

    private Engine engine(final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> convertible) throws Exception {
        final Engine h = new Engine(store(), Build54Fixtures.settings(convertible, ProfileSettings.DEFAULT_TYPE_REGISTRY));
        h.open();
        h.engine.storeWriter("Build 54 test");
        return h;
    }

    private static final List<String> POOL = List.of("Ember", "Cinder", "Ash");

    private static ProfileTypeDefinition hardcore() {
        return ProfileTypeDefinition.builder("hardcore").displayName("Hardcore").naming(ProfileNaming.pool(POOL))
            .deny("trade").deny(ProfileTypeRegistry.SALVAGE_CLAIM).build();
    }

    /** The owner's example: Ironman, by a registered definition, without a bank. */
    private static ProfileTypeDefinition ironmanWithoutBank() {
        return ProfileTypeDefinition.builder("ironman").displayName("Ironman")
            .description("No trading, auction house, bazaar or bank.")
            .deny("trade").deny("auction").deny("bazaar")
            .deny(ProfileTypeRegistry.BANK_DEPOSIT).deny(ProfileTypeRegistry.BANK_WITHDRAW).deny("island-bank")
            .build();
    }

    // ------------------------------------------------------------------ register / override / unregister / persistence

    /**
     * Register, override a built-in, refuse another plugin's id, unregister and plugin disable (the definition stays in
     * force as STORED, its denies unchanged), register again; the definitions survive a restart as STORED with their
     * owner; a stored override of a built-in is ignored with a WARN while overrides are disabled; the registry holds at
     * most 64 non-built-in types; with MultiProfile off nothing can be registered.
     */
    @Test
    void registerOverrideUnregisterKeepsTheStoredDefinition() throws Exception {
        refusedWith(ProfileErrorCode.DISABLED, () -> ProfileTypes.get().register(this.plugin, hardcore()));
        final ProfileTypes types = open("types");

        // Built-ins: Hypixel parity, the bank allowed for every one of them.
        assertEquals(List.of("classic", "ironman", "stranded", "bingo"),
            types.definitions().stream().map(ProfileTypeDefinition::id).toList());
        assertEquals(ProfileTypeSource.BUILTIN, types.source("ironman").orElseThrow());
        assertTrue(types.owner("ironman").isEmpty());
        for (final String builtin : ProfileTypeRegistry.builtinIds()) {
            for (final String action : ProfileTypes.ENGINE_ACTIONS) assertTrue(types.allows(builtin, action), builtin + " " + action);
        }
        assertFalse(types.allows("ironman", "trade"));
        assertFalse(types.allows("stranded", "bazaar"));
        assertTrue(types.allows("classic", "trade"));

        types.register(this.plugin, ironmanWithoutBank());
        types.register(this.plugin, hardcore());
        assertEquals(ProfileTypeSource.REGISTERED, types.source("ironman").orElseThrow());
        assertEquals("TypesPlugin", types.owner("ironman").orElseThrow());
        assertFalse(types.allows("ironman", ProfileTypeRegistry.BANK_DEPOSIT));
        assertTrue(types.allows("ironman", ProfileTypeRegistry.BANK_PERSONAL), "an action without a rule takes the default rule");
        assertFalse(types.allows("hardcore", ProfileTypeRegistry.SALVAGE_CLAIM));
        assertEquals(List.of("classic", "ironman", "stranded", "bingo", "hardcore"),
            types.definitions().stream().map(ProfileTypeDefinition::id).toList());
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> types.register(this.otherPlugin, hardcore()));
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> types.register(this.otherPlugin, ironmanWithoutBank()));

        // Unregister: the live registration ends, the definition (and its denies) stays in force.
        assertFalse(types.unregister(this.otherPlugin, "hardcore"), "another plugin ended the registration");
        assertTrue(types.unregister(this.plugin, "hardcore"));
        assertEquals(ProfileTypeSource.STORED, types.source("hardcore").orElseThrow());
        assertEquals(hardcore(), types.definition("hardcore").orElseThrow());
        assertFalse(types.allows("hardcore", ProfileTypeRegistry.SALVAGE_CLAIM), "unregister lifted a deny");
        assertFalse(types.unregister(this.plugin, "hardcore"), "a stored definition was unregistered twice");
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> types.register(this.otherPlugin, hardcore()));
        // Plugin disable: the same.
        types.ownerGone("TypesPlugin");
        assertEquals(ProfileTypeSource.STORED, types.source("ironman").orElseThrow());
        assertFalse(types.allows("ironman", ProfileTypeRegistry.BANK_DEPOSIT), "a disable lifted a deny");
        // Registering again replaces it.
        types.register(this.plugin, hardcore().toBuilder().allow(ProfileTypeRegistry.SALVAGE_CLAIM).build());
        assertEquals(ProfileTypeSource.REGISTERED, types.source("hardcore").orElseThrow());
        assertTrue(types.allows("hardcore", ProfileTypeRegistry.SALVAGE_CLAIM));
        types.register(this.plugin, hardcore());

        // At most 64 non-built-in types.
        for (int i = 1; i < ProfileTypes.MAX_TYPES; i++) types.register(this.plugin, ProfileTypeDefinition.builder("t" + i).build());
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> types.register(this.plugin, ProfileTypeDefinition.builder("one-more").build()));
        types.register(this.plugin, ProfileTypeDefinition.builder("t1").displayName("Replaced").build());
        close(types);
        assertNotEquals(types, ProfileTypes.get(), "a closed registry is still in force");
        refusedWith(ProfileErrorCode.DISABLED, () -> ProfileTypes.get().register(this.plugin, hardcore()));
        assertTrue(Files.isRegularFile(this.directory.resolve("types").resolve("hardcore.awpt")));

        // A restart reads them back as STORED, owners and rules intact.
        final ProfileTypes restarted = open("types");
        assertEquals(ProfileTypeSource.STORED, restarted.source("hardcore").orElseThrow());
        assertEquals("TypesPlugin", restarted.owner("hardcore").orElseThrow());
        assertEquals(hardcore(), restarted.definition("hardcore").orElseThrow());
        assertEquals(ironmanWithoutBank(), restarted.definition("ironman").orElseThrow());
        assertEquals("Replaced", restarted.definition("t1").orElseThrow().displayName());
        assertFalse(restarted.allows("ironman", ProfileTypeRegistry.BANK_WITHDRAW));
        assertEquals(ProfileTypes.MAX_TYPES + ProfileTypes.BUILTINS.size(), restarted.definitions().size());
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> restarted.register(this.otherPlugin, hardcore()));
        close(restarted);

        // Overrides disabled: the stored Ironman is ignored with a WARN, the built-in applies, a registration is refused.
        try (Warnings warnings = new Warnings()) {
            final ProfileTypes strict = open("types", new ProfileSettings.TypeRegistry(false, ProfileSettings.UnknownTypePolicy.DENY));
            assertEquals(ProfileTypeSource.BUILTIN, strict.source("ironman").orElseThrow());
            assertTrue(strict.allows("ironman", ProfileTypeRegistry.BANK_DEPOSIT));
            assertEquals(1L, warnings.containing("built-in profile type 'ironman'"), String.valueOf(warnings.messages));
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> strict.register(this.plugin, ironmanWithoutBank()));
            assertEquals(ProfileTypeSource.STORED, strict.source("hardcore").orElseThrow(), "non-built-in types are unaffected");
        }
    }

    /**
     * Review P2-3: a stored record this build cannot read (bad size, failed checksum) becomes a tombstone: a STORED
     * deny-all definition (no co-op, not creatable, no owner) with a WARN, so a lost definition never lifts a deny; this
     * holds for a built-in override too (the built-in's own rules are not substituted). Any plugin may register the id
     * again. While built-in overrides are disabled an unreadable override of a built-in is ignored with a WARN.
     */
    @Test
    void anUnreadableStoredDefinitionBecomesADenyAllTombstone() throws Exception {
        final Path dir = this.directory.resolve("types");
        Files.createDirectories(dir);
        Files.write(dir.resolve("broken.awpt"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        final byte[] good = ProfileTypesStore.encode(hardcore(), "TypesPlugin");
        final byte[] flipped = good.clone();
        flipped[flipped.length / 2] ^= 0x10;
        Files.write(dir.resolve("hardcore.awpt"), flipped);
        final byte[] override = ProfileTypesStore.encode(ironmanWithoutBank(), "TypesPlugin");
        Files.write(dir.resolve("ironman.awpt"), java.util.Arrays.copyOf(override, override.length - 1));
        try (Warnings warnings = new Warnings()) {
            final ProfileTypes types = open("types");
            for (final String id : List.of("broken", "hardcore", "ironman")) {
                assertEquals(ProfileTypes.unreadableDefinition(id), types.definition(id).orElseThrow(), id);
                assertEquals(ProfileTypeSource.STORED, types.source(id).orElseThrow(), id);
                assertTrue(types.owner(id).isEmpty(), id + " kept an owner");
                assertFalse(types.definition(id).orElseThrow().creatable(), id);
                assertFalse(types.definition(id).orElseThrow().coopAllowed(), id);
                for (final String action : ProfileTypes.ENGINE_ACTIONS) assertFalse(types.allows(id, action), id + " allows " + action);
                assertFalse(types.allows(id, "trade"), id);
                assertFalse(types.allows(id, "anything-else"), id);
                assertEquals(1L, warnings.containing("profile type '" + id + "' is unreadable"), id + ": " + warnings.messages);
            }
            assertFalse(types.allows("ironman", ProfileTypeRegistry.BANK_PERSONAL), "the built-in Ironman rules were substituted");
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> types.planCreate(ProfileRequest.of(UUID.randomUUID(), "skyblock")
                .withTypeId("hardcore"), ProfileSettings.DEFAULT_TYPES));
            // Registering again ends the tombstone (it has no owner, so any plugin may).
            types.register(this.otherPlugin, hardcore());
            assertEquals(ProfileTypeSource.REGISTERED, types.source("hardcore").orElseThrow());
            assertTrue(types.allows("hardcore", ProfileTypeRegistry.BANK_DEPOSIT));
            close(types);
        }
        try (Warnings warnings = new Warnings()) {
            Files.write(dir.resolve("ironman.awpt"), java.util.Arrays.copyOf(override, override.length - 1));
            final ProfileTypes strict = open("types", new ProfileSettings.TypeRegistry(false, ProfileSettings.UnknownTypePolicy.DENY));
            assertEquals(ProfileTypeSource.BUILTIN, strict.source("ironman").orElseThrow());
            assertEquals(1L, warnings.containing("unreadable stored override of the built-in profile type 'ironman'"),
                String.valueOf(warnings.messages));
        }
        assertEquals(new ProfileTypesStore.Stored(hardcore(), "TypesPlugin"), ProfileTypesStore.decode(good, "good"));
        assertNull(ProfileTypesStore.decode(good, "good").unreadable());
    }

    /**
     * Review P3-10, P3-19, P3-8: the evacuation create of a removed member bypasses {@code creatable} (still a known type);
     * a closed registry refuses a registration ({@code DISABLED}) instead of keeping it in memory only; a built-in
     * override that denies something is reported to the engine's store-level hook (at registration and when found stored),
     * one that denies nothing and a plugin type are not.
     */
    @Test
    void evacuationCreatesClosedRegistryAndBuiltinOverrideHook() throws Exception {
        final ProfileTypes types = open("types");
        types.register(this.plugin, ProfileTypeDefinition.builder("retired").creatable(false).build());
        final ProfileRequest retired = ProfileRequest.of(UUID.randomUUID(), "skyblock").withTypeId("retired");
        refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> types.planCreate(retired, ProfileSettings.DEFAULT_TYPES));
        assertEquals("retired", types.planCreate(retired, ProfileSettings.DEFAULT_TYPES, true).typeId(),
            "an evacuation was refused by creatable");
        refusedWith(ProfileErrorCode.TYPE_UNKNOWN, () -> types.planCreate(ProfileRequest.of(UUID.randomUUID(), "skyblock")
            .withTypeId("nosuch"), ProfileSettings.DEFAULT_TYPES, true));

        final List<String> reported = new java.util.concurrent.CopyOnWriteArrayList<>();
        types.onBuiltinOverrideDenied(reported::add);
        types.register(this.plugin, ProfileTypeDefinition.builder("bingo").displayName("Bingo").build());
        types.register(this.plugin, ProfileTypeDefinition.builder("plain").deny("trade").build());
        assertTrue(reported.isEmpty(), "reported " + reported);
        types.register(this.plugin, ironmanWithoutBank());
        assertEquals(List.of("ironman"), reported);
        close(types);
        refusedWith(ProfileErrorCode.DISABLED, () -> types.register(this.plugin, hardcore()));
        assertTrue(types.definition("hardcore").isEmpty(), "a refused registration stayed in memory");

        final ProfileTypes restarted = open("types");
        final List<String> found = new java.util.concurrent.CopyOnWriteArrayList<>();
        restarted.onBuiltinOverrideDenied(found::add);
        assertEquals(List.of("ironman"), found, "a stored built-in override with denies was not reported");
    }

    // ------------------------------------------------------------------ unknown type: fail-safe deny + one WARN

    /**
     * A profile whose type has no definition on this server is refused every engine action with
     * {@code ACTION_FORBIDDEN_BY_TYPE} (nothing written), with one WARN naming the type however often it is asked; its
     * rules read as unknown and denied. Under {@code unknown-type-policy: allow} the same profile may act. Registering
     * the type again (or reading its stored definition) restores its own rules.
     */
    @Test
    void unknownTypeIsDeniedWithOneWarn() throws Exception {
        try (Engine h = engine(Set.of(CLASSIC))) {
            final ProfileTypes first = open("types-a");
            first.attachLookup(h.engine::profileRecord);
            first.register(this.plugin, ProfileTypeDefinition.builder("hardcore").displayName("Hardcore")
                .naming(ProfileNaming.pool(POOL)).build());
            final UUID owner = UUID.randomUUID();
            final AuroraProfile profile = h.create(ProfileRequest.of(owner, "skyblock").withTypeId("hardcore"));
            final AuroraProfile coop = h.convert(owner, profile);
            h.activate(owner, coop.id());
            join(h.engine.deposit(owner, coop.id(), 10L));
            close(first);

            try (Warnings warnings = new Warnings()) {
                final ProfileTypes empty = open("types-empty");
                empty.attachLookup(h.engine::profileRecord);
                final Map<String, String> before = tree(store());
                final ProfileOperationException refused = refusal(() -> join(h.engine.deposit(owner, coop.id(), 1L)));
                assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, refused.code(), refused.getMessage());
                assertTrue(refused.getMessage().contains("hardcore") && refused.getMessage().contains(ProfileTypeRegistry.BANK_DEPOSIT),
                    refused.getMessage());
                refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.withdraw(owner, coop.id(), 1L)));
                refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.invite(owner, coop.id(), UUID.randomUUID())));
                assertEquals(before, tree(store()), "a refused action of an unknown type wrote something");
                assertFalse(empty.allows("hardcore", "trade"));
                final ProfileRuleSet rules = join(empty.rules(coop.id()));
                assertFalse(rules.known());
                assertFalse(rules.unknownAllows());
                assertFalse(rules.allows("anything"));
                assertEquals("hardcore", rules.typeId());
                assertFalse(join(empty.allows(coop.id(), "island-bank")));
                assertEquals(1L, warnings.containing("'hardcore'"), "WARNs: " + warnings.messages);
                final ProfileDetails details = join(h.engine.getDetails(coop.id())).orElseThrow();
                assertEquals("hardcore", details.typeId());
                assertEquals(CLASSIC, details.profileType());
                close(empty);
            }

            final ProfileTypes lenient = open("types-lenient", new ProfileSettings.TypeRegistry(true, ProfileSettings.UnknownTypePolicy.ALLOW));
            lenient.attachLookup(h.engine::profileRecord);
            assertEquals(11L, join(h.engine.deposit(owner, coop.id(), 1L)).sharedBalance());
            assertTrue(join(lenient.rules(coop.id())).unknownAllows());
            close(lenient);

            // The stored definition is read back at the next start: the profile has its own rules again.
            final ProfileTypes stored = open("types-a");
            assertEquals(ProfileTypeSource.STORED, stored.source("hardcore").orElseThrow());
            assertEquals(12L, join(h.engine.deposit(owner, coop.id(), 1L)).sharedBalance());
            refusedWith(ProfileErrorCode.PROFILE_NOT_FOUND, () -> {
                stored.attachLookup(h.engine::profileRecord);
                join(stored.rules(UUID.randomUUID()));
            });
        }
    }

    // ------------------------------------------------------------------ rules gate the engine's own actions

    /**
     * The engine checks the rule of each action it owns before it writes anything: the owner's example (Ironman with
     * {@code bank.deposit} and {@code bank.withdraw} denied by a registered definition; the built-in Ironman keeps its
     * bank), {@code bank.personal}, {@code bank.transfer}, {@code collections.contribute}, {@code coop.convert} (and
     * {@code coopAllowed: false}), {@code coop.invite}, {@code coop.accept} and {@code salvage.claim}; the purse refusal
     * (OD-4) comes first. A consumer's own action is answered by {@code rules}/{@code allows}.
     */
    @Test
    void typeRulesGateBankCoopCollectionsAndSalvage() throws Exception {
        try (Engine h = engine(Set.of(CLASSIC, IRONMAN))) {
            final ProfileTypes types = open("types");
            types.attachLookup(h.engine::profileRecord);

            // The owner's example.
            final UUID iron = UUID.randomUUID();
            final AuroraProfile ironman = h.convert(iron, h.create(ProfileRequest.of(iron, "skyblock", IRONMAN).withName("Lemon")));
            h.activate(iron, ironman.id());
            assertEquals(5L, join(h.engine.deposit(iron, ironman.id(), 5L)).sharedBalance(), "the built-in Ironman keeps its bank");
            types.register(this.plugin, ironmanWithoutBank());
            Map<String, String> before = tree(store());
            final ProfileOperationException noBank = refusal(() -> join(h.engine.deposit(iron, ironman.id(), 1L)));
            assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, noBank.code(), noBank.getMessage());
            assertTrue(noBank.getMessage().contains("ironman") && noBank.getMessage().contains("bank.deposit"), noBank.getMessage());
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.withdraw(iron, ironman.id(), 1L)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.applyInterest(iron, ironman.id())));
            assertEquals(before, tree(store()), "a denied bank action wrote something");
            assertEquals(3L, join(h.engine.depositPersonal(iron, ironman.id(), 3L)).personalBalance(), "bank.personal is not denied");
            // Review P2-2: no route around the denies through the personal bank.
            before = tree(store());
            final ProfileOperationException out = refusal(() -> join(h.engine.transfer(iron, ironman.id(), ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 1L)));
            assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, out.code(), out.getMessage());
            assertTrue(out.getMessage().contains(ProfileTypeRegistry.BANK_WITHDRAW), out.getMessage());
            final ProfileOperationException in = refusal(() -> join(h.engine.transfer(iron, ironman.id(), ProfileBank.Account.PERSONAL,
                ProfileBank.Account.SHARED, 1L)));
            assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, in.code(), in.getMessage());
            assertTrue(in.getMessage().contains(ProfileTypeRegistry.BANK_DEPOSIT), in.getMessage());
            assertEquals(before, tree(store()), "a denied transfer wrote something");
            assertEquals(5L, h.file.shared(ironman.id()).orElseThrow().sharedBalance(), "money left the denied shared bank");
            assertFalse(join(types.allows(ironman.id(), "island-bank")));
            assertTrue(join(types.allows(ironman.id(), "minions")));

            // A registered type on a co-op: one rule at a time, each before anything is written.
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            types.register(this.plugin, ProfileTypeDefinition.builder("duo").build());
            final AuroraProfile duo = h.convert(owner, h.create(ProfileRequest.of(owner, "skyblock").withTypeId("duo").withName("Mango")));
            h.admit(owner, duo.id(), member);
            h.activate(owner, duo.id());
            join(h.engine.deposit(owner, duo.id(), 100L));
            types.register(this.plugin, ProfileTypeDefinition.builder("duo").deny(ProfileTypeRegistry.BANK_PERSONAL)
                .deny(ProfileTypeRegistry.BANK_TRANSFER).deny(ProfileTypeRegistry.COLLECTIONS_CONTRIBUTE)
                .deny(ProfileTypeRegistry.COOP_INVITE).deny(ProfileTypeRegistry.SALVAGE_CLAIM).build());
            before = tree(store());
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.depositPersonal(owner, duo.id(), 1L)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.withdrawPersonal(owner, duo.id(), 1L)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.transfer(owner, duo.id(), ProfileBank.Account.SHARED,
                ProfileBank.Account.PERSONAL, 1L)));
            refusedWith(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, () -> join(h.engine.transfer(owner, duo.id(), ProfileBank.Account.PURSE,
                ProfileBank.Account.SHARED, 1L)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.contribute(owner, duo.id(), "wheat", 1L)));
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.invite(owner, duo.id(), UUID.randomUUID())));
            assertEquals(before, tree(store()), "a denied action wrote something");
            assertEquals(101L, join(h.engine.deposit(owner, duo.id(), 1L)).sharedBalance(), "an action without a rule is allowed");

            // coop.accept: an invitation issued while allowed cannot be accepted once the type denies it.
            types.register(this.plugin, ProfileTypeDefinition.builder("duo").deny(ProfileTypeRegistry.SALVAGE_CLAIM).build());
            final UUID third = UUID.randomUUID();
            final dev.iyanz.sourbycraft.api.profile.CoopInviteInfo invite = join(h.engine.invite(owner, duo.id(), third));
            types.register(this.plugin, ProfileTypeDefinition.builder("duo").deny(ProfileTypeRegistry.SALVAGE_CLAIM)
                .deny(ProfileTypeRegistry.COOP_ACCEPT).build());
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.acceptInvite(third, invite.id())));
            assertNull(h.profile(duo.id()).member(third));

            // salvage.claim: a removal's salvage cannot be claimed while the type denies it.
            final AuroraProfile kiwi = h.create(member, "Kiwi");
            h.activate(member, kiwi.id());
            join(h.engine.leave(member, duo.id()));
            final ProfileSalvage salvage = h.salvage(duo.id()).getFirst();
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.claimSalvage(owner, salvage.id())));
            assertEquals(salvage, h.salvageRecord(salvage.id()));

            // coop.convert, by a rule and by coopAllowed: false (coop.allowed-types [classic, ironman] lets classic through).
            final UUID loner = UUID.randomUUID();
            types.register(this.plugin, ProfileTypeDefinition.builder("lone").coopAllowed(false).build());
            types.register(this.plugin, ProfileTypeDefinition.builder("closed").deny(ProfileTypeRegistry.COOP_CONVERT).build());
            final AuroraProfile lone = h.create(ProfileRequest.of(loner, "skyblock").withTypeId("lone"));
            final AuroraProfile closed = h.create(ProfileRequest.of(loner, "skyblock").withTypeId("closed"));
            final ProfileOperationException noCoop = refusal(() -> join(h.engine.convertToCoop(loner, lone.id())));
            assertEquals(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, noCoop.code(), noCoop.getMessage());
            assertTrue(noCoop.getMessage().contains("does not allow co-op"), noCoop.getMessage());
            refusedWith(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, () -> join(h.engine.convertToCoop(loner, closed.id())));
            assertFalse(h.profile(lone.id()).coop());
            assertFalse(h.profile(closed.id()).coop());
            assertFalse(types.allows("lone", ProfileTypeRegistry.COOP_INVITE), "coopAllowed false denies every co-op action");
            assertTrue(types.allows("lone", ProfileTypeRegistry.BANK_DEPOSIT));

            // The rule set a consumer reads, parameters included.
            types.register(this.plugin, ProfileTypeDefinition.builder("duo").rule("island-bank",
                ProfileRule.allow().withParams(Map.of("limit", "5000"))).build());
            final ProfileRuleSet rules = join(types.rules(duo.id()));
            assertTrue(rules.known());
            assertEquals("duo", rules.typeId());
            assertEquals("5000", rules.definition().rule("island-bank").param("limit"));
            assertTrue(rules.allows(ProfileTypeRegistry.SALVAGE_CLAIM));
        }
    }

    // ------------------------------------------------------------------ naming pool, account-unique names, create refusals

    /**
     * A type with its own pool names its profiles from it: a requested name must be an entry (ignoring case, optionally
     * with a suffix 1-999), names stay unique in the account ignoring case, rolled names come from the pool, and the
     * pool is exhausted into suffixes. The server pool still applies to other types. A create naming an unregistered type
     * is {@code TYPE_UNKNOWN}, a non-creatable type {@code TYPE_NOT_ALLOWED}, a contradicting request or a malformed id
     * {@code INVALID_REQUEST}; none writes anything. A stored pool-named profile reads back after a restart without its
     * type registered.
     */
    @Test
    void typeNamingPoolAndAccountUniqueNames() throws Exception {
        final UUID player = UUID.randomUUID();
        final Set<UUID> created = new HashSet<>();
        try (Engine h = engine(Set.of(CLASSIC))) {
            final ProfileTypes types = open("types");
            types.register(this.plugin, hardcore());
            types.register(this.plugin, ProfileTypeDefinition.builder("retired").creatable(false).build());

            final ProfileDetails ember = join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock").withTypeId("hardcore")
                .withName("ember")));
            created.add(ember.info().id());
            assertEquals("Ember", ember.name(), "a requested name is stored in its pool spelling");
            assertEquals("hardcore", ember.typeId());
            assertEquals(CLASSIC, ember.profileType());
            assertEquals("hardcore", ember.info().metadata().get(ProfileTypeRegistry.TYPE_METADATA_KEY));

            final Map<String, String> before = tree(store());
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                .withTypeId("hardcore").withName("EMBER"))));
            for (final String invalid : List.of("Apple", "Ember 0", "Ember 1000", "Embers", "Ash2")) {
                refusedWith(ProfileErrorCode.NAME_INVALID, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                    .withTypeId("hardcore").withName(invalid))));
            }
            refusedWith(ProfileErrorCode.TYPE_UNKNOWN, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                .withTypeId("nosuch"))));
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                .withTypeId("retired"))));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.createDetailed(new ProfileRequest(player, "skyblock",
                Map.of(ProfileTypeRegistry.TYPE_METADATA_KEY, "hardcore"), IRONMAN, null))));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.createDetailed(new ProfileRequest(player, "skyblock",
                Map.of(ProfileTypeRegistry.TYPE_METADATA_KEY, "Bad Id"), null, null))));
            // The server pool still applies to the other types.
            refusedWith(ProfileErrorCode.NAME_INVALID, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                .withName("Ember"))));
            assertEquals(before, tree(store()), "a refused create wrote something");

            final ProfileDetails suffixed = join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock").withTypeId("hardcore")
                .withName("ember 2")));
            assertEquals("Ember 2", suffixed.name());
            created.add(suffixed.info().id());
            final ProfileDetails rolled = join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock").withTypeId("hardcore")));
            created.add(rolled.info().id());
            assertTrue(List.of("Cinder", "Ash").contains(rolled.name()), "a rolled name is not from the type's pool: " + rolled.name());
            refusedWith(ProfileErrorCode.SLOT_LIMIT, () -> join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock")
                .withTypeId("hardcore"))));
            // Another account may use the same names.
            assertEquals("Ember", join(h.engine.createDetailed(ProfileRequest.of(UUID.randomUUID(), "skyblock").withTypeId("hardcore")
                .withName("Ember"))).name());
        }
        final Set<String> all = new HashSet<>();
        for (final String name : POOL) all.add(CuteNames.key(name));
        assertEquals("Ember 2", CuteNames.generate(all, POOL), "the exhausted pool continues with the first entry's suffixes");
        assertTrue(CuteNames.validIn("cinder 999", POOL));
        assertFalse(CuteNames.validIn("Apple", POOL));
        assertEquals("Ash 3", CuteNames.normalizeIn("ASH 3", POOL));

        // Stored profiles named from the pool read back without the type registered (lenient decoding; the type is unknown).
        try (Engine h = engine(Set.of(CLASSIC))) {
            open("types-empty");
            final List<ProfileDetails> listed = join(h.engine.listDetails(player));
            assertEquals(created, new HashSet<>(listed.stream().map(details -> details.info().id()).toList()));
            assertTrue(listed.stream().allMatch(details -> details.typeId().equals("hardcore")));
            assertTrue(AuroraProfile.validStoredName("Ember 2"));
            assertTrue(AuroraProfile.validStoredName("A Name Of Some Pool"));
            assertFalse(AuroraProfile.validStoredName(" Ember"));
            assertFalse(AuroraProfile.validStoredName("Em\u0007ber"));
            assertFalse(AuroraProfile.validStoredName("x".repeat(CuteNames.MAX_LENGTH + 1)));
            assertFalse(AuroraProfile.validStoredName(""));
        }
    }

    // ------------------------------------------------------------------ store level 54

    /**
     * Profiles of built-in types named from the server pool keep the store level; the first profile of a registered type
     * raises it to minimum build 54 before its record is prepared (Build 53 would read it as plain classic and lift the
     * type's denies): the OD-2 gate then refuses Build 53 and passes Build 54.
     */
    @Test
    void theFirstRegisteredTypeProfileRaisesTheStoreToBuild54() throws Exception {
        try (Engine h = engine(Set.of(CLASSIC))) {
            final ProfileTypes types = open("types");
            types.register(this.plugin, ProfileTypeDefinition.builder("plain").build());
            final UUID player = UUID.randomUUID();
            h.create(player, "Apple");
            join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock", IRONMAN)));
            final int before = h.file.storeFormat().requiredBuild();
            assertTrue(before < StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD, h.file.storeFormat().toString());
            assertTrue(Build54Fixtures.downgradeRefusal("53", serverDir()).isEmpty(), "a store of built-in types refused Build 53");

            join(h.engine.createDetailed(ProfileRequest.of(player, "skyblock").withTypeId("plain")));
            assertEquals(StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD, h.file.storeFormat().requiredBuild());
            final java.util.Optional<String> refused = Build54Fixtures.downgradeRefusal("53", serverDir());
            assertTrue(refused.isPresent(), "Build 53 passed a store with a registered-type profile");
            assertTrue(refused.get().startsWith(StoreDowngradeGuard.REASON_TAG), refused.get());
            assertTrue(Build54Fixtures.downgradeRefusal("54", serverDir()).isEmpty());
            assertTrue(h.engine.storeStatus().marker().covers(StoreFormat.SCHEMA, StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD));
        }
    }

    /**
     * Review P3-8: a built-in override that denies an action raises the store to minimum build 54 through the engine hook
     * (as the runtime wires it), so a downgrade to Build 53, which would apply the built-in, is refused by the OD-2 gate.
     */
    @Test
    void aDenyingBuiltinOverrideRaisesTheStoreToBuild54() throws Exception {
        try (Engine h = engine(Set.of(CLASSIC))) {
            final ProfileTypes types = open("types");
            types.onBuiltinOverrideDenied(h.engine::protectBuiltinOverride);
            h.create(UUID.randomUUID(), "Apple");
            types.register(this.plugin, ProfileTypeDefinition.builder("bingo").displayName("Bingo").build());
            assertTrue(h.file.storeFormat().requiredBuild() < StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD, "an override without denies raised it");
            types.register(this.plugin, ironmanWithoutBank());
            // The raise runs on the engine's I/O lane: later lane steps are taken until it is visible (bounded, no sleep).
            for (int i = 0; i < 500 && h.file.storeFormat().requiredBuild() < StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD; i++) {
                join(h.engine.profileRecord(UUID.randomUUID()));
            }
            assertEquals(StoreFormat.REGISTERED_TYPE_MINIMUM_BUILD, h.file.storeFormat().requiredBuild());
            assertTrue(Build54Fixtures.downgradeRefusal("53", serverDir()).isPresent(), "Build 53 would lift the Ironman denies");
        }
    }
}
