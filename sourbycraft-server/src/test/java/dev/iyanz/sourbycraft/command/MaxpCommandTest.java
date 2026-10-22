package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfigTesting;
import dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /maxp}: permission before any side effect, argument validation, and persistence through
 * {@link SourbyCraftConfig} (a temp file, never the working directory).
 */
class MaxpCommandTest {

    private static Server server(final int max, final int online) {
        final Server server = mock(Server.class);
        when(server.getMaxPlayers()).thenReturn(max);
        final List<Player> players = new java.util.ArrayList<>();
        for (int i = 0; i < online; i++) players.add(mock(Player.class));
        doReturnPlayers(server, players);
        return server;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void doReturnPlayers(final Server server, final List<Player> players) {
        when((java.util.Collection) server.getOnlinePlayers()).thenReturn(players);
    }

    private static int onDisk(final Path file) {
        try (CommentedFileConfig config = CommentedFileConfig.of(file)) {
            config.load();
            final Object v = config.get(MaxPlayersConfig.KEY);
            return v == null ? -1 : ((Number) v).intValue();
        }
    }

    @Test
    void deniedSenderTouchesNeitherTheServerNorTheConfig(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("g.toml");
        final Server server = server(20, 0);
        final ReplyProbe probe = new ReplyProbe();
        final MaxpCommand command = quietDenial(new MaxpCommand("maxp"));

        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            for (final Class<? extends CommandSender> type : List.of(
                Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
                assertTrue(command.execute(probe.sender(type, false), "maxp", new String[] {"50"}));
                assertTrue(command.execute(probe.sender(type, false), "maxp", new String[0]));
            }
        }));

        verifyNoInteractions(server);
        assertFalse(Files.exists(file), "a denied /maxp must not even create the config file");
        probe.received().forEach(r -> assertEquals("no permission", r.text()));
        assertEquals("sourbycraft.command.maxp", command.getPermission());
    }

    @Test
    void withoutArgumentsItShowsOnlineOverMaxForAnySender() throws Exception {
        final Server server = server(20, 3);
        withServer(server, () -> {
            for (final Class<? extends CommandSender> type : List.of(
                Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
                final ReplyProbe probe = new ReplyProbe();
                assertTrue(new MaxpCommand("maxp").execute(probe.sender(type, true), "maxp", new String[0]));
                final String all = plainReceived(probe.received());
                assertTrue(all.contains("3 / 20"), type.getSimpleName() + ": " + all);
                assertTrue(all.contains("Set with /maxp <n>"), all);
            }
        });
        verify(server, never()).setMaxPlayers(anyInt());
    }

    @Test
    void theReadoutSurvivesAZeroMaxAndAnOvercrowdedServer() throws Exception {
        for (final int[] case_ : new int[][] {{0, 0}, {0, 5}, {10, 25}}) {
            final ReplyProbe probe = new ReplyProbe();
            withServer(server(case_[0], case_[1]), () ->
                assertDoesNotThrow(() -> new MaxpCommand("maxp").execute(
                    probe.sender(ConsoleCommandSender.class, true), "maxp", new String[0])));
            final String all = plainReceived(probe.received());
            assertTrue(all.contains(case_[1] + " / " + case_[0]), all);
            assertFalse(all.contains("NaN") || all.contains("Infinity"), all);
        }
    }

    @Test
    void invalidArgumentsAreRejectedWithoutApplyingOrPersisting(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("g.toml");
        final Server server = server(20, 0);
        final String[][] notNumbers = {{"abc"}, {"1.5"}, {""}, {" "}, {"0x10"}, {"5e1"}, {"99999999999"},
            {"-99999999999"}, {"ten"}};
        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            for (final String[] args : notNumbers) {
                final ReplyProbe probe = new ReplyProbe();
                assertTrue(new MaxpCommand("maxp").execute(probe.sender(ConsoleCommandSender.class, true), "maxp", args),
                    java.util.Arrays.toString(args));
                final String all = plainReceived(probe.received());
                assertTrue(all.contains("Not a number: " + args[0]) && all.contains("Usage: /maxp [n]"),
                    "'" + args[0] + "': " + all);
            }
            assertEquals(0, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0));
        }));
        verify(server, never()).setMaxPlayers(anyInt());
        assertFalse(Files.exists(file), "nothing was persisted");
    }

    @Test
    void zeroNegativeAndOversizedCountsAreRangeErrors(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("g.toml");
        final Server server = server(20, 0);
        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            for (final String bad : new String[] {"0", "-1", "-5", String.valueOf(Integer.MIN_VALUE)}) {
                final ReplyProbe probe = new ReplyProbe();
                new MaxpCommand("maxp").execute(probe.sender(ConsoleCommandSender.class, true), "maxp", new String[] {bad});
                assertTrue(plainReceived(probe.received()).contains("Max players must be positive (got " + bad + ")"), bad);
            }
            for (final String big : new String[] {"100001", String.valueOf(Integer.MAX_VALUE)}) {
                final ReplyProbe probe = new ReplyProbe();
                new MaxpCommand("maxp").execute(probe.sender(ConsoleCommandSender.class, true), "maxp", new String[] {big});
                assertTrue(plainReceived(probe.received()).contains("Max players too large (max 100000)"), big);
            }
        }));
        verify(server, never()).setMaxPlayers(anyInt());
        assertFalse(Files.exists(file));
    }

    @Test
    void aValidCountIsAppliedLiveAndPersistedThroughTheConfig(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("g.toml");
        final Server server = server(20, 0);
        final ReplyProbe probe = new ReplyProbe();

        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            assertTrue(new MaxpCommand("maxp").execute(
                probe.sender(ConsoleCommandSender.class, true), "maxp", new String[] {" 50 "}));
            assertEquals(50, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0), "live snapshot sees it");
            assertEquals(50, onDisk(file));
        }));

        verify(server).setMaxPlayers(50);
        final String all = plainReceived(probe.received());
        assertTrue(all.contains("Max players 20 -> 50"), all);
        assertTrue(all.contains("persisted (players.max-players)"), all);
    }

    @Test
    void theBoundariesOneAndOneHundredThousandAreAccepted(@TempDir final Path dir) throws Exception {
        final Server server = server(20, 0);
        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), dir.resolve("g.toml"), () -> {
            for (final String ok : new String[] {"1", "100000", "+7"}) {
                new MaxpCommand("maxp").execute(
                    new ReplyProbe().sender(ConsoleCommandSender.class, true), "maxp", new String[] {ok});
            }
            assertEquals(7, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0));
        }));
        verify(server).setMaxPlayers(1);
        verify(server).setMaxPlayers(100_000);
        verify(server).setMaxPlayers(7);
    }

    @Test
    void aCommandBlockCanSetTheCountToo(@TempDir final Path dir) throws Exception {
        final Server server = server(20, 0);
        final ReplyProbe probe = new ReplyProbe();
        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), dir.resolve("g.toml"), () ->
            assertTrue(new MaxpCommand("maxp").execute(
                probe.sender(BlockCommandSender.class, true), "maxp", new String[] {"30"}))));
        verify(server).setMaxPlayers(30);
        assertTrue(plainReceived(probe.received()).contains("20 -> 30"));
    }

    @Test
    void anUnwritableConfigStillAppliesLiveAndSaysItWillNotSurviveARestart(@TempDir final Path dir) throws Exception {
        final Path blocked = dir.resolve("is-a-directory.toml");
        Files.createDirectory(blocked);
        final Server server = server(20, 0);
        final ReplyProbe probe = new ReplyProbe();

        withServer(server, () -> SourbyCraftConfigTesting.withConfigFile(Map.of(), blocked, () ->
            assertTrue(new MaxpCommand("maxp").execute(
                probe.sender(ConsoleCommandSender.class, true), "maxp", new String[] {"64"}))));

        verify(server).setMaxPlayers(64);
        final String all = plainReceived(probe.received());
        assertTrue(all.contains("20 -> 64"), all);
        assertTrue(all.contains("could NOT persist"), all);
        assertFalse(all.contains("persisted (players.max-players)"), all);
    }
}
