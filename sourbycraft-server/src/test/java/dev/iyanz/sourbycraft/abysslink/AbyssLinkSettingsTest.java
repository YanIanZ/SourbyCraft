package dev.iyanz.sourbycraft.abysslink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbyssLinkSettingsTest {
    @TempDir Path dir;

    @Test
    void defaultsApplyWithoutWritingTheOperatorsFile() throws Exception {
        final Path file = this.dir.resolve("aurora.yml");
        final AbyssLinkSettings settings = AbyssLinkSettings.load(file);
        assertFalse(settings.enabled());
        assertFalse(Files.exists(file), "the loader never writes the operator's file");
        assertTrue(settings.timeoutMillis() <= 2000);
        assertTrue(settings.maxPendingRequests() <= 256);
        assertEquals(25580, settings.port());
    }

    @Test
    void readsTheConfiguredValues() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("aurora.yml"), """
            aurora:
              abysslink:
                enabled: true
                host: leviathan.internal
                port: 25581
                node-id: node-a
                tls: true
                authenticated-identity: leviathan.internal
                trust-certificate: ""
                client-certificate: backend.pem
                client-key: backend-key.pem
                reconnect-min-ms: 500
                reconnect-max-ms: 30000
                heartbeat-ms: 5000
                timeout-ms: 750
                ping-cache-ms: 250
                max-pending-requests: 32
                telemetry-ms: 10000
                threat-reporting: true
                max-sample-age-ms: 2000
            """);
        final AbyssLinkSettings settings = AbyssLinkSettings.load(file);
        assertTrue(settings.enabled());
        assertEquals("leviathan.internal", settings.host());
        assertEquals(25581, settings.port());
        assertEquals("node-a", settings.nodeId());
        assertEquals("backend.pem", settings.clientCertificate());
    }

    @Test
    void refusesPlaintextAndUnboundedRequests() throws Exception {
        final Path file = this.dir.resolve("aurora.yml");
        Files.writeString(file, "aurora:\n  abysslink:\n    enabled: true\n    tls: false\n");
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkSettings.load(file));
        Files.writeString(file, "aurora:\n  abysslink:\n    max-pending-requests: 0\n");
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkSettings.load(file));
    }

    @Test
    void refusesUnknownKeysAndHalfConfiguredClientIdentity() throws Exception {
        final Path file = this.dir.resolve("aurora.yml");
        Files.writeString(file, "aurora:\n  abysslink:\n    max-pendng-requests: 10\n");
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkSettings.load(file));
        Files.writeString(file, "aurora:\n  abysslink:\n    client-certificate: backend.pem\n");
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkSettings.load(file));
    }

    @Test
    void aLegacyTomlSiblingIsReadUntilItIsConverted() throws Exception {
        Files.writeString(this.dir.resolve("aurora.toml"),
            "[aurora.abysslink]\nhost = \"legacy.internal\"\nport = 25582\n");
        final AbyssLinkSettings settings = AbyssLinkSettings.load(this.dir.resolve("aurora.yml"));
        assertEquals("legacy.internal", settings.host());
        assertEquals(25582, settings.port());
        assertFalse(Files.exists(this.dir.resolve("aurora.yml")), "reading the TOML writes nothing");
    }
}
