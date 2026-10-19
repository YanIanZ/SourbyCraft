package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import java.util.List;
import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** {@code /ver}: permission first, any sender gets the build identity. */
class VerCommandTest {

    private static ServerBuildInfo paperBuild() {
        final ServerBuildInfo info = mock(ServerBuildInfo.class);
        when(info.minecraftVersionId()).thenReturn("26.2");
        when(info.minecraftVersionName()).thenReturn("26.2");
        when(info.gitBranch()).thenReturn(java.util.Optional.of("26.2"));
        when(info.gitCommit()).thenReturn(java.util.Optional.of("abc1234"));
        return info;
    }

    @Test
    void deniedSendersGetOnlyThePermissionMessage() {
        final ReplyProbe probe = new ReplyProbe();
        final VerCommand command = quietDenial(new VerCommand("ver", VerCommandTest::paperBuild));
        for (final Class<? extends CommandSender> type : List.of(
            Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
            assertTrue(command.execute(probe.sender(type, false), "ver", new String[0]));
        }
        final var received = probe.received();
        assertEquals(3, received.size());
        received.forEach(r -> assertEquals("no permission", r.text()));
        assertEquals("sourbycraft.command.ver", command.getPermission());
        assertTrue(command.getAliases().containsAll(List.of("version", "about")));
    }

    @Test
    void everyPermittedSenderGetsTheBuildIdentity() throws Exception {
        final Server server = mock(Server.class);
        when(server.getBukkitVersion()).thenReturn("26.2-R0.1-SNAPSHOT");
        final BuildInfo build = BuildInfo.load();
        withServer(server, () -> {
            for (final Class<? extends CommandSender> type : List.of(
                Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
                final ReplyProbe probe = new ReplyProbe();

                assertTrue(new VerCommand("ver", VerCommandTest::paperBuild).execute(probe.sender(type, true), "ver", new String[0]));

                final String all = plainReceived(probe.received());
                assertTrue(all.contains("SourbyCraft / Version"), all);
                assertTrue(all.contains("Release: " + build.releaseIdentity()), type.getSimpleName() + ": " + all);
                assertTrue(all.contains("Engine: Aurora Engine"), all);
                assertTrue(all.contains("Bukkit API: 26.2-R0.1-SNAPSHOT"), all);
                assertTrue(all.contains("Java: " + Runtime.version()), all);
                assertTrue(all.contains("Aurora runtime: "), all);
                assertTrue(all.contains("Aurora Bridge: "), all);
                assertTrue(all.contains("Update channel: "), all);
                assertTrue(all.contains("Uptime: "), all);
                assertTrue(all.contains("Minecraft: 26.2  (26.2)"), all);
                assertTrue(all.contains("Git: 26.2@abc1234"), all);
                assertFalse(all.contains("null"), all);
            }
        });
    }

    @Test
    void anUnparseableBuildTimestampReadsUnavailableNotAnException() throws Exception {
        // BuildInfo is cached from the classpath resource; whichever timestamp the test classpath
        // carries, the line is either a formatted date or the placeholder, never blank.
        final Server server = mock(Server.class);
        when(server.getBukkitVersion()).thenReturn("x");
        final ReplyProbe probe = new ReplyProbe();
        withServer(server, () -> new VerCommand("ver", VerCommandTest::paperBuild).execute(probe.sender(ConsoleCommandSender.class, true), "ver", new String[0]));
        final String all = plainReceived(probe.received());
        final String built = all.lines().filter(l -> l.contains("Built (Asia/Jakarta):")).findFirst().orElseThrow();
        assertFalse(built.trim().endsWith(":"), built);
    }
}
