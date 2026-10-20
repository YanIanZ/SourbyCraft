package dev.iyanz.sourbycraft.security;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SecurityGuardsTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(SecurityGuards.Limit.class)
    void everyLimitObservesWithoutRejectingInLogMode(SecurityGuards.Limit limit) {
        var logs = new ArrayList<String>();
        var guard = new SecurityGuards(logs::add, () -> 0L);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            guard.check(limit, limit.fallback);
            assertEquals(0, guard.violations(limit));
            guard.check(limit, limit.fallback + 1);
            guard.check(limit, limit.fallback + 2);
            assertEquals(1, guard.violations(limit)); // once per packet
        }
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) { guard.check(limit, limit.fallback + 1); }
        assertEquals(2, guard.violations(limit));
        assertEquals(1, logs.size()); // bounded across connections
    }

    @Test void kickIsExplicitAndInvalidReloadKeepsLastGoodConfig() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        Path config = directory.resolve("guards.yml");
        Files.writeString(config, "packet-guard:\n  book-edit:\n    mode: KICK\n    max-total-chars: 5\n");
        guard.load(config);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            assertThrows(SecurityGuards.Rejected.class, () -> guard.check(SecurityGuards.Limit.BOOK_CHARS, 6));
        }
        Files.writeString(config, "packet-guard:\n  book-edit:\n    mode: misspelled\n");
        assertThrows(Exception.class, () -> guard.load(config));
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            assertThrows(SecurityGuards.Rejected.class, () -> guard.check(SecurityGuards.Limit.BOOK_CHARS, 6));
        }
    }

    @Test void rateWindowsArePerConnectionAndAdvanceWithoutServerTick() {
        var clock = new AtomicLong();
        var guard = new SecurityGuards(ignored -> {}, clock::get);
        var connection = new SecurityGuards.ConnectionState();
        for (int i = 0; i <= SecurityGuards.Limit.CONTAINER_RATE.fallback; i++) {
            try (var scope = guard.open(connection)) { guard.operation(SecurityGuards.Limit.CONTAINER_RATE); }
        }
        assertEquals(1, guard.violations(SecurityGuards.Limit.CONTAINER_RATE));
        clock.set(50_000_000L);
        try (var scope = guard.open(connection)) { guard.operation(SecurityGuards.Limit.CONTAINER_RATE); }
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) { guard.operation(SecurityGuards.Limit.CONTAINER_RATE); }
        assertEquals(1, guard.violations(SecurityGuards.Limit.CONTAINER_RATE));
    }

    @Test void networkScopeAlwaysClosesAndOffModeDoesNotCount() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        guard.check(SecurityGuards.Limit.NBT_DEPTH, Long.MAX_VALUE);
        assertEquals(0, guard.violations(SecurityGuards.Limit.NBT_DEPTH));
        Path config = directory.resolve("guards.yml");
        Files.writeString(config, "crash-prevention:\n  nbt:\n    mode: OFF\n");
        guard.load(config);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) { guard.check(SecurityGuards.Limit.NBT_DEPTH, 999); }
        assertEquals(0, guard.violations(SecurityGuards.Limit.NBT_DEPTH));
        assertFalse(guard.inPacket());
    }

    @Test void codecDepthBiasDoesNotCountAsNbtContainerDepth() {
        var guard = SecurityGuards.INSTANCE;
        long before = guard.violations(SecurityGuards.Limit.NBT_DEPTH);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            var account = net.minecraft.nbt.NbtAccounter.defaultQuota();
            account.pushDepth(90); // Paper's component-codec bias is not NBT nesting
            assertEquals(before, guard.violations(SecurityGuards.Limit.NBT_DEPTH));
            for (int i = 0; i < 64; i++) account.pushDepth();
            assertEquals(before, guard.violations(SecurityGuards.Limit.NBT_DEPTH));
            account.pushDepth();
            assertEquals(before + 1, guard.violations(SecurityGuards.Limit.NBT_DEPTH));
        }
    }

    @Test void nativeNbtQuotaRejectsBeforeEqualDefaultObservationThreshold() {
        var guard = SecurityGuards.INSTANCE;
        long before = guard.violations(SecurityGuards.Limit.NBT_BYTES);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            var account = net.minecraft.nbt.NbtAccounter.defaultQuota();
            account.accountBytes(net.minecraft.nbt.NbtAccounter.DEFAULT_NBT_QUOTA);
            assertThrows(net.minecraft.nbt.NbtAccounterException.class, () -> account.accountBytes(1));
        }
        assertEquals(before, guard.violations(SecurityGuards.Limit.NBT_BYTES));
    }

    @Test void aSingleMaximumSizeDragBurstDoesNotExceedDefaultContainerBudget() {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        var state = new SecurityGuards.ConnectionState();
        // One start, up to 128 per-slot continue packets, then one end.
        for (int i = 0; i < 130; i++) {
            try (var scope = guard.open(state)) { guard.operation(SecurityGuards.Limit.CONTAINER_RATE); }
        }
        assertEquals(0, guard.violations(SecurityGuards.Limit.CONTAINER_RATE));
    }

    @Test void nativeNbtAndPayloadHooksAreScopedToNetworkDecode() throws Exception {
        var production = SecurityGuards.INSTANCE;
        long depthBefore = production.violations(SecurityGuards.Limit.NBT_DEPTH);
        var account = net.minecraft.nbt.NbtAccounter.unlimitedHeap();
        for (int i = 0; i < 65; i++) account.pushDepth();
        assertEquals(depthBefore, production.violations(SecurityGuards.Limit.NBT_DEPTH));
        try (var scope = production.open(new SecurityGuards.ConnectionState())) {
            var network = net.minecraft.nbt.NbtAccounter.unlimitedHeap();
            for (int i = 0; i < 65; i++) network.pushDepth();
        }
        assertEquals(depthBefore + 1, production.violations(SecurityGuards.Limit.NBT_DEPTH));
        long payloadBefore = production.violations(SecurityGuards.Limit.PAYLOAD_BYTES);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeZero(8193);
            try (var scope = production.open(new SecurityGuards.ConnectionState())) {
                var decoded = net.minecraft.network.protocol.common.custom.DiscardedPayload
                    .<net.minecraft.network.FriendlyByteBuf>codec(net.minecraft.resources.Identifier.parse("sourbycraft:test"), 32767).decode(buffer);
                assertEquals(8193, decoded.data().length); // LOG observes without dropping content
            }
            assertEquals(payloadBefore + 1, production.violations(SecurityGuards.Limit.PAYLOAD_BYTES));
        } finally { buffer.release(); }
        assertFalse(production.inPacket());
    }

    @Test void bookAndSignGuardsAggregateAllFields() {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        try (var scope = guard.open(new SecurityGuards.ConnectionState())) {
            guard.book(java.util.List.of("x".repeat(65536)), java.util.Optional.of("title"));
            guard.sign(new String[] {"x".repeat(257), "x".repeat(256), "x".repeat(256), "x".repeat(256)});
        }
        assertEquals(1, guard.violations(SecurityGuards.Limit.BOOK_CHARS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.SIGN_LINE));
        assertEquals(1, guard.violations(SecurityGuards.Limit.SIGN_TOTAL));
    }

    @Test void everyShippedYamlLeafHasAConsumerAndEveryGuardDefaultsToLog() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("build-data/sourbycraft-security.yml"))) root = root.getParent();
        assertNotNull(root);
        var yaml = new YamlConfiguration();
        yaml.load(root.resolve("build-data/sourbycraft-security.yml").toFile());
        Set<String> leaves = new TreeSet<>();
        for (String key : yaml.getKeys(true)) if (!yaml.isConfigurationSection(key)) leaves.add(key);
        assertEquals(SecurityGuards.consumedKeys(), leaves);
        for (var limit : SecurityGuards.Limit.values()) assertEquals("LOG", yaml.getString(limit.group + ".mode"));
    }
}
