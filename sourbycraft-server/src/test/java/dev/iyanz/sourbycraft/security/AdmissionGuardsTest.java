package dev.iyanz.sourbycraft.security;

import io.netty.channel.socket.SocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionGuardsTest {
    @TempDir Path directory;
    private static boolean login(SecurityGuards guard, io.netty.channel.Channel channel) {
        return guard.loginStarted(channel, channel.remoteAddress());
    }
    private SocketChannel peer(int n) throws Exception {
        var channel = mock(SocketChannel.class);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, (byte)n}), 23456));
        return channel;
    }
    private Path config(String mode, String group, String key) throws Exception {
        Path config = directory.resolve("guards.yml");
        Files.writeString(config, "connection-guard:\n  " + group + ":\n    mode: " + mode + "\n    " + key + ": 1\n");
        return config;
    }

    @ParameterizedTest @ValueSource(strings={"global", "per-address"})
    void connectionLimitIsLogFirstAndRejectsOnlyWhenKickIsConfigured(String group) throws Exception {
        var logs = new ArrayList<String>();
        var guard = new SecurityGuards(logs::add, () -> 0L);
        guard.load(config("LOG", group, "max-connections-per-second"));
        var first = peer(1);
        var second = group.equals("global") ? peer(2) : peer(1);
        assertTrue(guard.connectionOpened(first));
        assertTrue(guard.connectionOpened(second));
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("peer=10.0.0."));
        guard.load(config("KICK", group, "max-connections-per-second"));
        assertFalse(guard.connectionOpened(second));
        assertFalse(guard.inPacket());
    }

    @ParameterizedTest @ValueSource(strings={"global", "per-address"})
    void loginStartLimitIsSeparateFromSocketAttemptsAndResetsWithTime(String group) throws Exception {
        var clock = new AtomicLong();
        var guard = new SecurityGuards(ignored -> {}, clock::get);
        guard.load(config("KICK", group, "max-login-starts-per-second"));
        var first = peer(1);
        var second = group.equals("global") ? peer(2) : peer(1);
        assertTrue(guard.connectionOpened(first));
        assertTrue(login(guard, first));
        assertFalse(login(guard, second));
        clock.set(1_000_000_000L);
        assertTrue(login(guard, second));
    }

    private SocketChannel peer(byte[] address) throws Exception {
        var channel = mock(SocketChannel.class);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByAddress(address), 23456));
        return channel;
    }

    @Test void loopbackSkipsAddressBudgetButStillCountsGlobally() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        Path file = directory.resolve("loopback.yml");
        Files.writeString(file, "connection-guard:\n  global:\n    mode: KICK\n    max-connections-per-second: 3\n  per-address:\n    mode: KICK\n    max-connections-per-second: 1\n");
        guard.load(file);
        var local = peer(new byte[]{127, 0, 0, 1});
        assertTrue(guard.connectionOpened(local));
        assertTrue(guard.connectionOpened(local));
        byte[] ipv6 = new byte[16]; ipv6[15] = 1;
        assertTrue(guard.connectionOpened(peer(ipv6)));
        assertFalse(guard.connectionOpened(local));
        assertEquals(0, guard.violations(SecurityGuards.Limit.ADDRESS_CONNECTIONS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADMISSION_CONNECTIONS));
    }

    @Test void rejectedAddressCannotSpendOtherPeersGlobalBudget() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        Path file = directory.resolve("admission.yml");
        Files.writeString(file, "connection-guard:\n  global:\n    mode: KICK\n    max-connections-per-second: 2\n    max-login-starts-per-second: 2\n  per-address:\n    mode: KICK\n    max-connections-per-second: 1\n    max-login-starts-per-second: 1\n");
        guard.load(file);
        assertTrue(guard.connectionOpened(peer(1)));
        assertTrue(login(guard, peer(1)));
        for (int i = 0; i < 10; i++) {
            assertFalse(guard.connectionOpened(peer(1)));
            assertFalse(login(guard, peer(1)));
        }
        assertTrue(guard.connectionOpened(peer(2)));
        assertTrue(login(guard, peer(2)));
        assertFalse(guard.connectionOpened(peer(3)));
        assertFalse(login(guard, peer(3)));
        assertEquals(10, guard.violations(SecurityGuards.Limit.ADDRESS_CONNECTIONS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADMISSION_CONNECTIONS));
    }

    @Test void defaultIpv6PrefixAggregatesDifferentInterfaceAddresses() throws Exception {
        var logs = new ArrayList<String>();
        var guard = new SecurityGuards(logs::add, () -> 0L);
        guard.load(config("KICK", "per-address", "max-connections-per-second"));
        byte[] first = new byte[16]; first[0] = 0x20; first[1] = 1; first[7] = 1;
        byte[] second = first.clone(); second[15] = 2;
        assertTrue(guard.connectionOpened(peer(first)));
        assertFalse(guard.connectionOpened(peer(second)));
        assertTrue(logs.getFirst().contains("/64"));
        byte[] otherPrefix = first.clone(); otherPrefix[7] = 2;
        assertTrue(guard.connectionOpened(peer(otherPrefix)));
    }

    private static java.net.SocketAddress forwarded(int n) throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte)198, 51, 100, (byte)n}), 40000 + n);
    }
    private Path proxyConfig() throws Exception {
        Path file = directory.resolve("proxy.yml");
        Files.writeString(file, "connection-guard:\n  global:\n    mode: KICK\n    max-connections-per-second: 3\n"
            + "    max-login-starts-per-second: 4\n  per-address:\n    mode: KICK\n    max-connections-per-second: 1\n"
            + "    max-login-starts-per-second: 1\n");
        return file;
    }

    @ParameterizedTest @ValueSource(strings={"bungee", "proxy-protocol"})
    void proxyTransportSkipsSocketLimitAndKeysHelloByForwardedAddress(String kind) throws Exception {
        var logs = new ArrayList<String>();
        var guard = new SecurityGuards(logs::add, () -> 0L);
        guard.load(proxyConfig());
        guard.configureProxyMode(kind.equals("bungee"), false, kind.equals("proxy-protocol"));
        var proxy = peer(1); // every TCP peer is the proxy
        assertTrue(guard.connectionOpened(proxy));
        assertTrue(guard.connectionOpened(proxy)); // per-address socket limit 1 is not applied to the proxy
        assertTrue(guard.loginStarted(proxy, forwarded(1)));
        assertTrue(guard.loginStarted(proxy, forwarded(2))); // different players behind one proxy
        assertFalse(guard.loginStarted(proxy, forwarded(1))); // same forwarded player exceeds its own budget
        assertTrue(guard.loginForwarded(proxy, forwarded(2))); // no Velocity: the late hook is inert
        assertEquals(0, guard.violations(SecurityGuards.Limit.ADDRESS_CONNECTIONS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADDRESS_LOGINS));
        assertTrue(logs.getLast().contains("peer=198.51.100.1"), logs.toString());
        assertTrue(logs.getFirst().contains("at login Hello"), logs.toString());
    }

    @Test void velocityCountsHelloGloballyAndAppliesAddressLimitAfterForwardingOnce() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        guard.load(proxyConfig());
        guard.configureProxyMode(false, true, false);
        var proxy = peer(1);
        for (int i = 0; i < 3; i++) assertTrue(guard.loginStarted(proxy, proxy.remoteAddress())); // proxy address at Hello
        assertTrue(guard.loginForwarded(proxy, forwarded(1)));
        assertTrue(guard.loginForwarded(proxy, forwarded(2)));
        assertFalse(guard.loginForwarded(proxy, forwarded(1))); // per forwarded player
        assertEquals(0, guard.violations(SecurityGuards.Limit.ADMISSION_LOGINS)); // forwarding did not count globally again
        assertTrue(guard.loginStarted(proxy, proxy.remoteAddress())); // fourth global login start
        assertFalse(guard.loginStarted(proxy, proxy.remoteAddress())); // global limit 4 still applies
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADMISSION_LOGINS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADDRESS_LOGINS));
    }

    @Test void forwardedLoopbackIsExemptAndDirectModeAppliesHelloLimitToTheSocketPeer() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        guard.load(proxyConfig());
        var local = new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 1);
        guard.configureProxyMode(true, false, false);
        assertTrue(guard.loginStarted(peer(1), local));
        assertTrue(guard.loginStarted(peer(1), local));
        guard.configureProxyMode(false, false, false);
        assertTrue(login(guard, peer(2)));
        assertFalse(login(guard, peer(2)));
        assertTrue(guard.loginForwarded(peer(2), forwarded(1))); // inert without Velocity
    }

    @Test void proxyPolicyLogsOncePerChangeAndSurvivesReloadWithGlobalWindow() throws Exception {
        var logs = new ArrayList<String>();
        var guard = new SecurityGuards(logs::add, () -> 0L);
        Path file = proxyConfig();
        guard.load(file);
        guard.configureProxyMode(false, true, false);
        guard.configureProxyMode(false, true, false); // second listener start, same policy
        assertEquals(1, logs.size());
        assertTrue(logs.getFirst().contains("after Velocity forwarding"), logs.getFirst());
        assertTrue(guard.connectionOpened(peer(1)));
        assertTrue(guard.connectionOpened(peer(1)));
        guard.load(file); // reload preserves the proxy policy AND the global window, without re-logging
        assertEquals(1, logs.size());
        assertTrue(guard.connectionOpened(peer(1)));
        assertFalse(guard.connectionOpened(peer(1)));
        assertEquals(0, guard.violations(SecurityGuards.Limit.ADDRESS_CONNECTIONS));
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADMISSION_CONNECTIONS));
    }

    @Test void prefixChangesAreLiveAndInvalidReloadKeepsCompletePreviousSettings() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        Path file = directory.resolve("prefix.yml");
        byte[] first = new byte[16]; first[0] = 0x20; first[1] = 1;
        byte[] second = first.clone(); second[15] = 2;
        Files.writeString(file, "connection-guard:\n  global:\n    mode: KICK\n    max-connections-per-second: 4\n  per-address:\n    mode: KICK\n    ipv6-prefix-length: 128\n    max-connections-per-second: 1\n");
        guard.load(file);
        assertTrue(guard.connectionOpened(peer(first)));
        assertTrue(guard.connectionOpened(peer(second))); // /128 independent hosts
        String valid = Files.readString(file);
        for (String invalid : new String[]{"0", "129", "64.5", "not-a-number"}) {
            Files.writeString(file, valid.replace("128", invalid).replace("max-connections-per-second: 1", "max-connections-per-second: 100"));
            assertThrows(Exception.class, () -> guard.load(file));
            assertFalse(guard.connectionOpened(peer(second))); // old complete snapshot, cap1, stays active
        }
        Files.writeString(file, valid.replace("128", "64"));
        guard.load(file);
        assertTrue(guard.connectionOpened(peer(first))); // newly keyed /64 budget, global third admission
        assertFalse(guard.connectionOpened(peer(second))); // same /64, does not count globally
        byte[] third = first.clone(); third[7] = 3;
        assertTrue(guard.connectionOpened(peer(third))); // fourth global admission
        byte[] fourth = first.clone(); fourth[7] = 4;
        assertFalse(guard.connectionOpened(peer(fourth))); // global still survived reload
        assertEquals(1, guard.violations(SecurityGuards.Limit.ADMISSION_CONNECTIONS));
    }

    @Test void prefixLengthOneIsAcceptedAndGroupsAddressesByItsLeadingBit() throws Exception {
        var guard = new SecurityGuards(ignored -> {}, () -> 0L);
        Path file = directory.resolve("prefix-one.yml");
        Files.writeString(file, "connection-guard:\n  per-address:\n    mode: KICK\n    ipv6-prefix-length: 1\n    max-connections-per-second: 1\n");
        guard.load(file);
        byte[] first = new byte[16]; first[0] = 0x20;
        byte[] second = new byte[16]; second[0] = 0x60; second[15] = 5;
        byte[] other = new byte[16]; other[0] = (byte)0xa0;
        assertTrue(guard.connectionOpened(peer(first)));
        assertFalse(guard.connectionOpened(peer(second)));
        assertTrue(guard.connectionOpened(peer(other)));
    }

    @Test void offModeDoesNotRejectAndLocalOrUnresolvedChannelsAreIgnoredWithoutDns() throws Exception {
        var guard = new SecurityGuards(ignored -> { throw new AssertionError("OFF must not log"); }, () -> 0L);
        Path config = config("OFF", "per-address", "max-login-starts-per-second");
        guard.load(config);
        for (int i = 0; i < 6; i++) assertTrue(login(guard, peer(1)));
        var local = mock(io.netty.channel.Channel.class);
        assertTrue(guard.connectionOpened(local));
        assertTrue(login(guard, local));
        var unresolved = mock(SocketChannel.class);
        when(unresolved.remoteAddress()).thenReturn(InetSocketAddress.createUnresolved("never-resolve.invalid", 1));
        assertTrue(guard.connectionOpened(unresolved));
    }
}
