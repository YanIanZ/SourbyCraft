package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three Build 53 MultiProfile keys ({@code shutdown.capture-deadline-ms}, {@code login.authoritative},
 * {@code adapters.required}): defaults, parsing with per-key fallback, record bounds, and their rows in the
 * {@link AuroraKeys} table (RESTART_REQUIRED, same defaults as {@link ProfileSettings#defaultsAsMap()}).
 */
class ProfileSettingsBuild53Test {

    private static final List<String> KEYS = List.of(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY,
        ProfileSettings.LOGIN_AUTHORITATIVE_KEY, ProfileSettings.ADAPTERS_REQUIRED_KEY);

    @Test
    void defaults() {
        assertEquals("aurora.awf.multiprofile.shutdown.capture-deadline-ms", ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY);
        assertEquals("aurora.awf.multiprofile.login.authoritative", ProfileSettings.LOGIN_AUTHORITATIVE_KEY);
        assertEquals("aurora.awf.multiprofile.adapters.required", ProfileSettings.ADAPTERS_REQUIRED_KEY);
        assertEquals(10_000, ProfileSettings.DEFAULT.shutdown().captureDeadlineMillis());
        assertEquals(ProfileSettings.Authoritative.AUTO, ProfileSettings.DEFAULT.login().authoritative());
        assertTrue(ProfileSettings.DEFAULT.adapters().required().isEmpty());
        assertTrue(ProfileSettings.KEYS.containsAll(KEYS));
        // The shorter constructors (Build 51 and 52 callers) take the Build 53 defaults.
        final ProfileSettings legacy = CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES);
        assertEquals(ProfileSettings.DEFAULT_SHUTDOWN, legacy.shutdown());
        assertEquals(ProfileSettings.DEFAULT_LOGIN, legacy.login());
        assertEquals(ProfileSettings.DEFAULT_ADAPTERS, legacy.adapters());
        // auto: authoritative exactly when co-op is enabled (the legacy settings have co-op off: Build 52 solo joins).
        assertFalse(legacy.authoritativeLogin(), "auto applied authoritative login with co-op off");
        assertTrue(CoopMembershipFixtures.coopSettings().authoritativeLogin());
        assertFalse(ProfileSettings.DEFAULT.authoritativeLogin(), "authoritative login reports on while MultiProfile is off");
        assertTrue(new ProfileSettings.Login(ProfileSettings.Authoritative.TRUE).applies(false));
        assertFalse(new ProfileSettings.Login(ProfileSettings.Authoritative.FALSE).applies(true));
        assertTrue(new ProfileSettings.Login(ProfileSettings.Authoritative.AUTO).applies(true));
        assertFalse(new ProfileSettings.Login(ProfileSettings.Authoritative.AUTO).applies(false));
    }

    @Test
    void parsesValidValues() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY, 2_500);
        values.put(ProfileSettings.LOGIN_AUTHORITATIVE_KEY, false);
        values.put(ProfileSettings.ADAPTERS_REQUIRED_KEY, List.of("skills", "minions.v2"));
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertEquals(2_500, parsed.settings().shutdown().captureDeadlineMillis());
        assertEquals(ProfileSettings.Authoritative.FALSE, parsed.settings().login().authoritative());
        assertEquals(Set.of("skills", "minions.v2"), parsed.settings().adapters().required());
        assertTrue(parsed.settings().adapters().forces("skills"));
        assertFalse(parsed.settings().adapters().forces("other"));

        // A comma-separated string is a list too.
        final ProfileSettings.Parsed text = ProfileSettings.parse(Map.of(ProfileSettings.ADAPTERS_REQUIRED_KEY, "a, b.c"));
        assertTrue(text.invalidKeys().isEmpty(), String.valueOf(text.invalidKeys()));
        assertEquals(Set.of("a", "b.c"), text.settings().adapters().required());
        assertTrue(ProfileSettings.parse(Map.of(ProfileSettings.ADAPTERS_REQUIRED_KEY, "")).settings().adapters().required().isEmpty());
    }

    @Test
    void invalidValuesFallBackPerKey() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY, 500);
        values.put(ProfileSettings.LOGIN_AUTHORITATIVE_KEY, "yes");
        values.put(ProfileSettings.ADAPTERS_REQUIRED_KEY, List.of("good.id", "Bad Id"));
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().containsAll(KEYS), String.valueOf(parsed.invalidKeys()));
        assertEquals(10_000, parsed.settings().shutdown().captureDeadlineMillis());
        assertEquals(ProfileSettings.Authoritative.AUTO, parsed.settings().login().authoritative());
        assertEquals(Set.of("good.id"), parsed.settings().adapters().required(), "a typo relaxed the well-formed ids");

        assertTrue(ProfileSettings.parse(Map.of(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY, 60_001)).invalidKeys()
            .contains(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY));
        assertTrue(ProfileSettings.parse(Map.of(ProfileSettings.ADAPTERS_REQUIRED_KEY, 7)).invalidKeys()
            .contains(ProfileSettings.ADAPTERS_REQUIRED_KEY));
        final List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i <= ProfileSettings.Adapters.MAX_REQUIRED; i++) many.add("id" + i);
        final ProfileSettings.Parsed capped = ProfileSettings.parse(Map.of(ProfileSettings.ADAPTERS_REQUIRED_KEY, many));
        assertTrue(capped.invalidKeys().contains(ProfileSettings.ADAPTERS_REQUIRED_KEY));
        assertEquals(ProfileSettings.Adapters.MAX_REQUIRED, capped.settings().adapters().required().size());
    }

    @Test
    void recordBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Shutdown(ProfileSettings.Shutdown.MIN_MILLIS - 1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Shutdown(ProfileSettings.Shutdown.MAX_MILLIS + 1));
        assertEquals(1_000, new ProfileSettings.Shutdown(1_000).captureDeadlineMillis());
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Adapters(Set.of("Bad Id")));
        final Set<String> tooMany = new java.util.HashSet<>();
        for (int i = 0; i <= ProfileSettings.Adapters.MAX_REQUIRED; i++) tooMany.add("id" + i);
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Adapters(tooMany));
        assertThrows(UnsupportedOperationException.class, () -> new ProfileSettings.Adapters(Set.of("a")).required().add("b"));
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
        }
        assertEquals(10_000, defaults.get(ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY));
        assertEquals("auto", defaults.get(ProfileSettings.LOGIN_AUTHORITATIVE_KEY));
        assertEquals(ProfileSettings.Authoritative.TRUE, ProfileSettings.parse(Map.of(ProfileSettings.LOGIN_AUTHORITATIVE_KEY, "true"))
            .settings().login().authoritative());
        assertEquals(ProfileSettings.Authoritative.AUTO, ProfileSettings.parse(Map.of(ProfileSettings.LOGIN_AUTHORITATIVE_KEY, "AUTO"))
            .settings().login().authoritative());
        assertEquals(List.of(), defaults.get(ProfileSettings.ADAPTERS_REQUIRED_KEY));
    }
}
