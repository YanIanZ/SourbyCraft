package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.Currency;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Build 54 MultiProfile keys: {@code types.registry.allow-override-builtins}, {@code types.unknown-type-policy} and
 * the bank policy ({@code bank.currency.provider/id/scale}, {@code bank.shared-capacity}, {@code bank.personal-cap-percent},
 * {@code bank.interest-percent}): defaults (Hypixel parity: personal 20 %, interest 10 %, no shared cap; unknown types
 * denied), parsing, per-key fallback with the key reported, the policy values the engine derives, and the AuroraKeys
 * rows matching {@link ProfileSettings#defaultsAsMap()}. All RESTART_REQUIRED.
 */
class ProfileSettingsBuild54Test {

    private static final List<String> KEYS = List.of(ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY, ProfileSettings.TYPES_UNKNOWN_POLICY_KEY,
        ProfileSettings.BANK_CURRENCY_PROVIDER_KEY, ProfileSettings.BANK_CURRENCY_ID_KEY, ProfileSettings.BANK_CURRENCY_SCALE_KEY,
        ProfileSettings.BANK_SHARED_CAPACITY_KEY, ProfileSettings.BANK_PERSONAL_CAP_KEY, ProfileSettings.BANK_INTEREST_KEY,
        ProfileSettings.BANK_INTEREST_INTERVAL_KEY);

    @Test
    void defaults() {
        assertEquals("aurora.awf.multiprofile.types.registry.allow-override-builtins", ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY);
        assertEquals("aurora.awf.multiprofile.types.unknown-type-policy", ProfileSettings.TYPES_UNKNOWN_POLICY_KEY);
        assertEquals("aurora.awf.multiprofile.bank.currency.provider", ProfileSettings.BANK_CURRENCY_PROVIDER_KEY);
        assertEquals("aurora.awf.multiprofile.bank.currency.id", ProfileSettings.BANK_CURRENCY_ID_KEY);
        assertEquals("aurora.awf.multiprofile.bank.currency.scale", ProfileSettings.BANK_CURRENCY_SCALE_KEY);
        assertEquals("aurora.awf.multiprofile.bank.shared-capacity", ProfileSettings.BANK_SHARED_CAPACITY_KEY);
        assertEquals("aurora.awf.multiprofile.bank.personal-cap-percent", ProfileSettings.BANK_PERSONAL_CAP_KEY);
        assertEquals("aurora.awf.multiprofile.bank.interest-percent", ProfileSettings.BANK_INTEREST_KEY);
        assertEquals("aurora.awf.multiprofile.bank.interest-interval-hours", ProfileSettings.BANK_INTEREST_INTERVAL_KEY);
        assertTrue(ProfileSettings.KEYS.containsAll(KEYS));

        assertTrue(ProfileSettings.DEFAULT.typeRegistry().allowOverrideBuiltins());
        assertEquals(ProfileSettings.UnknownTypePolicy.DENY, ProfileSettings.DEFAULT.typeRegistry().unknownTypePolicy(),
            "unknown types must fail safe by default");
        final ProfileBank.Policy policy = ProfileSettings.DEFAULT.bank().policy();
        assertEquals(ProfileBank.Policy.DEFAULT, policy);
        assertEquals(new Currency("aurora", "coins", 0), policy.currency());
        assertEquals(0L, policy.sharedCapacity());
        assertEquals(20, policy.personalCapPercent());
        assertEquals(10, policy.interestPercent());
        assertEquals(24L * 60L * 60L * 1000L, policy.interestIntervalMillis());
        assertEquals(ProfileBank.Policy.DEFAULT_INTEREST_INTERVAL_MILLIS, new ProfileBank.Policy(policy.currency(), 0L, 20, 10)
            .interestIntervalMillis(), "the short policy constructor takes the default interval");
        // The shorter constructors (Build 51-53 callers) take the Build 54 defaults.
        final ProfileSettings legacy = CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES);
        assertEquals(ProfileSettings.DEFAULT_TYPE_REGISTRY, legacy.typeRegistry());
        assertEquals(ProfileSettings.DEFAULT_BANK, legacy.bank());
        assertEquals(ProfileSettings.DEFAULT_BANK, MemberPlayFixtures.playSettings().bank());
    }

    @Test
    void parsesValidValues() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY, false);
        values.put(ProfileSettings.TYPES_UNKNOWN_POLICY_KEY, "Allow");
        values.put(ProfileSettings.BANK_CURRENCY_PROVIDER_KEY, "vault");
        values.put(ProfileSettings.BANK_CURRENCY_ID_KEY, "gold.coins");
        values.put(ProfileSettings.BANK_CURRENCY_SCALE_KEY, 2);
        values.put(ProfileSettings.BANK_SHARED_CAPACITY_KEY, 5_000_000_000L);
        values.put(ProfileSettings.BANK_PERSONAL_CAP_KEY, 25);
        values.put(ProfileSettings.BANK_INTEREST_KEY, 0);
        values.put(ProfileSettings.BANK_INTEREST_INTERVAL_KEY, 0);
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertFalse(parsed.settings().typeRegistry().allowOverrideBuiltins());
        assertEquals(ProfileSettings.UnknownTypePolicy.ALLOW, parsed.settings().typeRegistry().unknownTypePolicy());
        final ProfileBank.Policy policy = parsed.settings().bank().policy();
        assertEquals(new Currency("vault", "gold.coins", 2), policy.currency());
        assertEquals(5_000_000_000L, policy.sharedCapacity(), "a capacity beyond the int range");
        assertEquals(25, policy.personalCapPercent());
        assertEquals(0, policy.interestPercent());
        assertEquals(0L, policy.interestIntervalMillis(), "0 = no minimum interval");
        assertEquals(6L * 60L * 60L * 1000L, ProfileSettings.parse(Map.of(ProfileSettings.BANK_INTEREST_INTERVAL_KEY, 6)).settings().bank()
            .policy().interestIntervalMillis());
        assertEquals(1_250_000_000L, policy.personalCapacity(policy.empty(UUID.randomUUID())));

        // The YAML reader's other whole-number shapes.
        assertEquals(1_000L, ProfileSettings.parse(Map.of(ProfileSettings.BANK_SHARED_CAPACITY_KEY, 1_000)).settings().bank().sharedCapacity());
        assertEquals(Long.MAX_VALUE, ProfileSettings.parse(Map.of(ProfileSettings.BANK_SHARED_CAPACITY_KEY,
            BigInteger.valueOf(Long.MAX_VALUE))).settings().bank().sharedCapacity());
        assertEquals(ProfileSettings.UnknownTypePolicy.DENY, ProfileSettings.parse(Map.of(ProfileSettings.TYPES_UNKNOWN_POLICY_KEY,
            " deny ")).settings().typeRegistry().unknownTypePolicy());
    }

    @Test
    void invalidValuesFallBackPerKey() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY, "yes");
        values.put(ProfileSettings.TYPES_UNKNOWN_POLICY_KEY, "maybe");
        values.put(ProfileSettings.BANK_CURRENCY_PROVIDER_KEY, "Bad Provider");
        values.put(ProfileSettings.BANK_CURRENCY_ID_KEY, 7);
        values.put(ProfileSettings.BANK_CURRENCY_SCALE_KEY, 10);
        values.put(ProfileSettings.BANK_SHARED_CAPACITY_KEY, -1);
        values.put(ProfileSettings.BANK_PERSONAL_CAP_KEY, 101);
        values.put(ProfileSettings.BANK_INTEREST_KEY, -1);
        values.put(ProfileSettings.BANK_INTEREST_INTERVAL_KEY, ProfileSettings.Bank.MAX_INTEREST_INTERVAL_HOURS + 1);
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().containsAll(KEYS), String.valueOf(parsed.invalidKeys()));
        assertEquals(ProfileSettings.DEFAULT_TYPE_REGISTRY, parsed.settings().typeRegistry());
        assertEquals(ProfileSettings.DEFAULT_BANK, parsed.settings().bank(), "an invalid bank key changed the policy");

        // Each key falls back on its own: the valid ones stay.
        final ProfileSettings.Parsed mixed = ProfileSettings.parse(Map.of(ProfileSettings.BANK_CURRENCY_SCALE_KEY, 2,
            ProfileSettings.BANK_INTEREST_KEY, 2.5, ProfileSettings.TYPES_UNKNOWN_POLICY_KEY, true));
        assertEquals(List.of(ProfileSettings.BANK_INTEREST_KEY, ProfileSettings.TYPES_UNKNOWN_POLICY_KEY).stream().sorted().toList(),
            mixed.invalidKeys().stream().filter(KEYS::contains).sorted().toList());
        assertEquals(2, mixed.settings().bank().currencyScale());
        assertEquals(10, mixed.settings().bank().interestPercent());
        assertTrue(ProfileSettings.parse(Map.of(ProfileSettings.BANK_SHARED_CAPACITY_KEY, "100")).invalidKeys()
            .contains(ProfileSettings.BANK_SHARED_CAPACITY_KEY), "a quoted capacity was read as a number");
        assertTrue(ProfileSettings.parse(Map.of(ProfileSettings.BANK_SHARED_CAPACITY_KEY, BigInteger.ONE.shiftLeft(64))).invalidKeys()
            .contains(ProfileSettings.BANK_SHARED_CAPACITY_KEY));
    }

    @Test
    void recordBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("aurora", "coins", 10, 0L, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("Aurora", "coins", 0, 0L, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("aurora", "coins", 0, -1L, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("aurora", "coins", 0, 0L, 101, 10));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("aurora", "coins", 0, 0L, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Bank("aurora", "coins", 0, 0L, 20, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileBank.Policy(new Currency("a", "b", 0), 0L, 0, 10, -1L));
        assertThrows(NullPointerException.class, () -> new ProfileSettings.TypeRegistry(true, null));
        assertThrows(IllegalArgumentException.class, () -> new ProfileBank.Policy(new Currency("a", "b", 0), 0L, 0, 101));

        // The personal cap is a percentage of the record's own capacity, floored; 0 on either side = no cap.
        final ProfileBank.Policy policy = new ProfileBank.Policy(new Currency("aurora", "coins", 0), 999L, 20, 10);
        final UUID profile = UUID.randomUUID();
        assertEquals(199L, policy.personalCapacity(policy.empty(profile)));
        assertEquals(0L, policy.personalCapacity(ProfileSharedState.empty(profile, policy.currency(), 0L)));
        assertEquals(0L, new ProfileBank.Policy(policy.currency(), 999L, 0, 10).personalCapacity(policy.empty(profile)));
        assertEquals(Long.MAX_VALUE, new ProfileBank.Policy(policy.currency(), Long.MAX_VALUE, 100, 0)
            .personalCapacity(ProfileSharedState.empty(profile, policy.currency(), Long.MAX_VALUE)), "the cap is computed without overflow");
    }

    @Test
    void auroraKeyRowsMatchTheDefaults() {
        final Map<String, Object> defaults = ProfileSettings.defaultsAsMap();
        for (final String path : KEYS) {
            final ConfigKey key = AuroraKeys.byPath(path);
            assertNotNull(key, path + " has no row in AuroraKeys");
            assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), path);
            assertTrue(defaults.containsKey(path), path);
            assertEquals(defaults.get(path), key.defaultValue(), path);
            assertTrue(AuroraKeys.AWF_MULTIPROFILE_KEYS.contains(key), path + " is not in the MultiProfile table");
        }
        assertEquals(true, defaults.get(ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY));
        assertEquals("deny", defaults.get(ProfileSettings.TYPES_UNKNOWN_POLICY_KEY));
        assertEquals(0, defaults.get(ProfileSettings.BANK_SHARED_CAPACITY_KEY));
        assertEquals(20, defaults.get(ProfileSettings.BANK_PERSONAL_CAP_KEY));
        assertEquals(10, defaults.get(ProfileSettings.BANK_INTEREST_KEY));
        assertEquals(24, defaults.get(ProfileSettings.BANK_INTEREST_INTERVAL_KEY));
        // Every MultiProfile key the settings read has its row (and the other way round).
        for (final String path : ProfileSettings.KEYS) assertNotNull(AuroraKeys.byPath(path), path + " has no row in AuroraKeys");
        // The parsed defaults are the defaults.
        assertEquals(ProfileSettings.DEFAULT_BANK, ProfileSettings.parse(defaults).settings().bank());
        assertEquals(ProfileSettings.DEFAULT_TYPE_REGISTRY, ProfileSettings.parse(defaults).settings().typeRegistry());
    }
}
