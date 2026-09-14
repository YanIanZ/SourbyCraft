package dev.iyanz.sourbycraft.spark;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.lucko.spark.paper.common.platform.serverconfig.ExcludedConfigFilter;
import me.lucko.spark.paper.common.platform.serverconfig.PropertiesConfigParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourbyServerConfigProviderTest {
    @TempDir Path directory;

    private ExcludedConfigFilter filter() {
        return new ExcludedConfigFilter(SourbyServerConfigProvider.HIDDEN_PATHS);
    }

    @Test
    void v10RetainsCurrentSparkManagementSecretExclusions() throws Exception {
        final Path path = directory.resolve("server.properties");
        Files.writeString(path, "management-server-secret=synthetic-management-secret\n"
            + "management-server-tls-keystore-password=synthetic-keystore-password\n"
            + "rcon.password=synthetic-rcon-password\nmax-players=20\n");
        final JsonObject out = PropertiesConfigParser.INSTANCE.load(path.toString(), filter()).getAsJsonObject();
        assertFalse(out.toString().contains("synthetic-"));
        assertTrue(out.has("max-players"));
    }

    @Test
    void v10RemovesNestedTomlSecretsIncludingArrayTablesAndLiteralKeys() throws Exception {
        final Path path = directory.resolve("global.toml");
        Files.writeString(path, "[integration]\napiKey='synthetic-api-key'\nrefresh_token='synthetic-refresh'\n"
            + "enabled=true\n'credentials.password'='synthetic-dotted'\n"
            + "[[integration.clients]]\nname='safe-client'\naccess-token='synthetic-client-token'\n"
            + "[[integration.clients]]\nname='second-client'\nprivate_key='synthetic-private-key'\n");
        final JsonElement out = SourbyServerConfigProvider.TomlConfigParser.INSTANCE.load(path.toString(), filter());
        assertFalse(out.toString().contains("synthetic-"));
        assertTrue(out.toString().contains("safe-client"));
        assertTrue(out.toString().contains("second-client"));
        assertTrue(out.getAsJsonObject().getAsJsonObject("integration").get("enabled").getAsBoolean());
    }

    @Test
    void v10RemovesYamlSecretsInsideListsWithoutRemovingLimits() throws Exception {
        final Path path = directory.resolve("security.yml");
        Files.writeString(path, "integrations:\n  - name: example\n    webhook-url: synthetic-webhook\n"
            + "    credentials:\n      username: synthetic-user\n    PASSWORD: synthetic-password\n"
            + "packet-limit: 42\ntoken-bucket-size: 64\n");
        final JsonObject out = SourbyServerConfigProvider.YamlConfigParser.INSTANCE.load(path.toString(), filter()).getAsJsonObject();
        assertFalse(out.toString().contains("synthetic-"));
        assertEquals(42, out.get("packet-limit").getAsInt());
        assertEquals(64, out.get("token-bucket-size").getAsInt());
    }

    @Test
    void configuredHiddenPathsStillApply() throws Exception {
        final Path path = directory.resolve("custom.toml");
        Files.writeString(path, "[custom]\ninternal='synthetic-internal'\nvisible=42\n");
        final JsonObject out = SourbyServerConfigProvider.TomlConfigParser.INSTANCE.load(path.toString(),
            new ExcludedConfigFilter(List.of("custom.internal"))).getAsJsonObject();
        assertFalse(out.toString().contains("synthetic-"));
        assertEquals(42, out.getAsJsonObject("custom").get("visible").getAsInt());
    }

    @Test
    void composedGroupPreservesLabelsAndNeverWritesFiles() throws Exception {
        final Path config = directory.resolve("sourbycraft_config/sourbycraft_global_config.toml");
        Files.createDirectories(config.getParent());
        final String original = "# operator comment\n[service]\nsecret='synthetic-group-secret'\nenabled=true\n";
        Files.writeString(config, original);
        Files.writeString(directory.resolve("sourbycraft-security.yml"), "packet-limit: 42\n");
        final JsonObject out = new SourbyServerConfigProvider.SourbyCraftSplitParser(directory)
            .load("sourbycraft/", filter()).getAsJsonObject();
        assertEquals(2, out.size());
        assertTrue(out.has("global.toml"));
        assertTrue(out.has("security.yml"));
        assertFalse(out.toString().contains("synthetic-"));
        assertEquals(original, Files.readString(config));
    }

    @Test
    void composedGroupReportsAuroraNamespacesWithoutRewritingOperatorFile() throws Exception {
        final Path config = directory.resolve("sourbycraft_config/sourbycraft_global_config.toml");
        Files.createDirectories(config.getParent());
        final String original = "# operator comment: aurora keys win, legacy stays for reference\n"
            + "[perf.ai]\nasync-pathfinding=true\n"
            + "[aurora.entity]\nasync-pathfinding=false\n"
            + "[aurora.diagnostics]\nhud-refresh-ticks=20\nspark-include-sourby-config=true\n"
            + "[aurora.diagnostics.export]\nenabled=true\nwebhook-url='synthetic-aurora-webhook'\n"
            + "api_key='synthetic-aurora-key'\n";
        Files.writeString(config, original);
        final byte[] before = Files.readAllBytes(config);

        final JsonObject out = new SourbyServerConfigProvider.SourbyCraftSplitParser(directory)
            .load("sourbycraft/", filter()).getAsJsonObject();

        assertEquals(1, out.size());
        final JsonObject aurora = out.getAsJsonObject("global.toml").getAsJsonObject("aurora");
        assertFalse(aurora.getAsJsonObject("entity").get("async-pathfinding").getAsBoolean());

        final JsonObject diagnostics = aurora.getAsJsonObject("diagnostics");
        assertEquals(20, diagnostics.get("hud-refresh-ticks").getAsInt());
        assertTrue(diagnostics.get("spark-include-sourby-config").getAsBoolean());

        final JsonObject export = diagnostics.getAsJsonObject("export");
        assertTrue(export.get("enabled").getAsBoolean());
        assertFalse(export.has("webhook-url"));
        assertFalse(export.has("api_key"));
        assertFalse(out.toString().contains("synthetic-"));

        // The legacy key is still reported verbatim: the report shows what the operator wrote,
        // it does not resolve aurora-over-legacy precedence for them.
        assertTrue(out.getAsJsonObject("global.toml").getAsJsonObject("perf")
            .getAsJsonObject("ai").get("async-pathfinding").getAsBoolean());

        assertArrayEquals(before, Files.readAllBytes(config));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of("sourbycraft_config"),
                entries.map(entry -> entry.getFileName().toString()).toList());
        }
    }

    @Test
    void missingGroupCreatesNothing() throws Exception {
        assertNull(new SourbyServerConfigProvider.SourbyCraftSplitParser(directory).load("sourbycraft/", filter()));
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }
}
