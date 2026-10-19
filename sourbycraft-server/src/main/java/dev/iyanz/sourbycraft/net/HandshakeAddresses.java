package dev.iyanz.sourbycraft.net;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.jspecify.annotations.Nullable;

/**
 * Builds client addresses for the handshake without ever touching DNS.
 *
 * <p>{@code new InetSocketAddress(String, int)} resolves a non-literal host name with a synchronous
 * lookup. The handshake runs on the Netty event loop, so a host name taken from a
 * {@code PlayerHandshakeEvent} listener or from a BungeeCord-forwarded address must never reach that
 * constructor. This class accepts IP literals only (Guava {@link InetAddresses#forString}, which parses
 * and never resolves) and returns {@code null} for anything else; the caller decides whether to refuse
 * or ignore the value.</p>
 */
public final class HandshakeAddresses {

    private HandshakeAddresses() {
    }

    /**
     * @param host an IPv4/IPv6 literal, optionally an IPv6 literal in square brackets
     * @param port the port to attach
     * @return a resolved address built from the literal, or {@code null} if {@code host} is not an IP
     *         literal (a host name, a scoped IPv6 address, blank, or {@code null}); no lookup happens
     */
    public static @Nullable InetSocketAddress literalSocketAddress(final @Nullable String host, final int port) {
        if (host == null) {
            return null;
        }
        String literal = host;
        if (literal.length() > 2 && literal.charAt(0) == '[' && literal.charAt(literal.length() - 1) == ']') {
            literal = literal.substring(1, literal.length() - 1);
        }
        // A scope id ("fe80::1%eth0") makes the JDK look up a local network interface by name; refuse it.
        if (literal.isEmpty() || literal.indexOf('%') >= 0 || !InetAddresses.isInetAddress(literal)) {
            return null;
        }
        final InetAddress address;
        try {
            address = InetAddresses.forString(literal);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
        return new InetSocketAddress(address, port);
    }
}
