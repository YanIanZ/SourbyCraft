package dev.iyanz.sourbycraft.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * DEV-H "preserve packet/security limits": the limit table is a security surface, so loosening a
 * default must show up as a reviewed diff of this file, not slip in with a performance change.
 * {@link SecurityGuardsTest#everyShippedYamlLeafHasAConsumerAndEveryGuardDefaultsToLog} pins the
 * key set and the LOG modes; this pins the numbers, in the code and in the shipped YAML.
 */
class SecurityLimitPinTest {

    /** The documented defaults (`sourbycraft-security.yml`). Changing one is a security decision. */
    private static final Map<String, Long> DOCUMENTED = documented();

    private static Map<String, Long> documented() {
        final Map<String, Long> m = new LinkedHashMap<>();
        m.put("crash-prevention.nbt.max-bytes", 2_097_152L);
        m.put("crash-prevention.nbt.max-depth", 64L);
        m.put("crash-prevention.nbt.max-string-length", 4_096L);
        m.put("crash-prevention.nbt.max-list-size", 65_536L);
        m.put("crash-prevention.sign.max-line-length", 256L);
        m.put("crash-prevention.sign.max-total-chars", 1_024L);
        m.put("crash-prevention.anvil.max-item-name-length", 128L);
        m.put("crash-prevention.recipe-book.max-packet-size", 20_480L);
        m.put("crash-prevention.creative-item.max-nbt-size", 16_384L);
        m.put("packet-guard.book-edit.max-total-chars", 65_536L);
        m.put("packet-guard.custom-payload.max-bytes", 8_192L);
        m.put("packet-guard.container-click.max-per-tick", 256L);
        m.put("packet-guard.recipe-book.max-per-tick", 16L);
        m.put("packet-guard.connection.max-per-second", 1_000L);
        m.put("packet-guard.connection.max-bytes-per-second", 8_388_608L);
        m.put("connection-guard.global.max-connections-per-second", 1_000L);
        m.put("connection-guard.global.max-login-starts-per-second", 100L);
        m.put("connection-guard.per-address.max-connections-per-second", 20L);
        m.put("connection-guard.per-address.max-login-starts-per-second", 5L);
        return m;
    }

    private static final String IPV6_PREFIX_KEY = "connection-guard.per-address.ipv6-prefix-length";
    private static final long IPV6_PREFIX_DEFAULT = 64L;

    private static YamlConfiguration shippedYaml() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("sourbycraft-security.yml"))) root = root.getParent();
        assertNotNull(root, "sourbycraft-security.yml is checked in at the repository root");
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(root.resolve("sourbycraft-security.yml").toFile());
        return yaml;
    }

    @Test
    void theCodeDefaultsAreTheDocumentedLimitsAndNothingElse() {
        final Map<String, Long> inCode = new LinkedHashMap<>();
        for (final SecurityGuards.Limit limit : SecurityGuards.Limit.values()) inCode.put(limit.key, limit.fallback);
        assertEquals(DOCUMENTED, inCode, "a limit was added, removed, renamed or its default changed: review it as a security change");
        for (final SecurityGuards.Limit limit : SecurityGuards.Limit.values()) {
            assertTrue(limit.fallback > 0, limit.key + ": a non-positive default would reject or log every packet");
        }
    }

    @Test
    void theShippedYamlCarriesEveryCodeDefault() throws Exception {
        final YamlConfiguration yaml = shippedYaml();
        for (final SecurityGuards.Limit limit : SecurityGuards.Limit.values()) {
            assertTrue(yaml.isSet(limit.key), limit.key + " is missing from the shipped YAML");
            assertEquals(limit.fallback, yaml.getLong(limit.key),
                limit.key + ": the shipped YAML and the code default must agree, or a fresh server and an operator who deletes the file get different limits");
        }
        assertEquals(IPV6_PREFIX_DEFAULT, yaml.getLong(IPV6_PREFIX_KEY));
    }

    /** A guard built with no file (the operator deleted it) enforces exactly the documented numbers. */
    @Test
    void aGuardWithoutAFileObservesAtTheDocumentedBoundary() {
        final java.util.List<String> logs = new java.util.ArrayList<>();
        final SecurityGuards guard = new SecurityGuards(logs::add, () -> 0L);
        for (final Map.Entry<String, Long> expected : DOCUMENTED.entrySet()) {
            final SecurityGuards.Limit limit = byKey(expected.getKey());
            try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
                guard.check(limit, expected.getValue());
                assertEquals(0, guard.violations(limit), limit.key + ": the documented maximum itself passes");
                guard.check(limit, expected.getValue() + 1);
                assertEquals(1, guard.violations(limit), limit.key + ": one over the documented maximum is a violation");
            }
        }
    }

    private static SecurityGuards.Limit byKey(final String key) {
        for (final SecurityGuards.Limit limit : SecurityGuards.Limit.values()) if (limit.key.equals(key)) return limit;
        throw new AssertionError("no limit for " + key);
    }
}
