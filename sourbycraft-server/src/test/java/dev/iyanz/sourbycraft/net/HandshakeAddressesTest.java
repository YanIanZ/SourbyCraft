package dev.iyanz.sourbycraft.net;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1/H4/H5 of the Netty event loop audit (docs/architecture/region-io-audit.md). The two listener
 * classes need a running server to execute, so their fixes are pinned at the bytecode level.
 */
class HandshakeAddressesTest {

    @Test
    void acceptsIpv4AndIpv6LiteralsWithoutResolution() {
        final InetSocketAddress v4 = HandshakeAddresses.literalSocketAddress("203.0.113.7", 25565);
        assertNotNull(v4);
        assertFalse(v4.isUnresolved());
        assertEquals("203.0.113.7", v4.getAddress().getHostAddress());
        assertEquals(25565, v4.getPort());

        final InetSocketAddress v6 = HandshakeAddresses.literalSocketAddress("2001:db8::1", 0);
        assertNotNull(v6);
        assertEquals("2001:db8:0:0:0:0:0:1", v6.getAddress().getHostAddress());

        final InetSocketAddress bracketed = HandshakeAddresses.literalSocketAddress("[::1]", 7);
        assertNotNull(bracketed);
        assertTrue(bracketed.getAddress().isLoopbackAddress());
        assertEquals(7, bracketed.getPort());
    }

    @Test
    void refusesHostNamesAndMalformedInput() {
        // "cafe.dead" matches the BungeeCord HOST_PATTERN ([0-9a-f.:]{0,45}) but is a host name.
        for (final String value : new String[] {
            "cafe.dead", "localhost", "example.invalid", "1.2", "256.1.1.1", "::g", "fe80::1%eth0",
            "", " ", "[]", "[", "[203.0.113.7", "203.0.113.7 ", "a.b.c.d",
        }) {
            assertNull(HandshakeAddresses.literalSocketAddress(value, 1), () -> "must refuse: '" + value + "'");
        }
        assertNull(HandshakeAddresses.literalSocketAddress(null, 1));
    }

    @Test
    void handshakeListenerNeverBuildsAnAddressFromAString() throws IOException {
        // new InetSocketAddress(String, int) performs a DNS lookup for host names; Connection.address
        // must only ever be built from a parsed literal (InetAddress) or the Netty remote address.
        final List<String> offenders = new ArrayList<>();
        for (final MethodModel method : parse(ServerHandshakePacketListenerImpl.class).methods()) {
            for (final CodeElement element : method.code().map(code -> (Iterable<CodeElement>)code).orElse(List.of())) {
                if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals("java/net/InetSocketAddress")
                    && invoke.name().stringValue().equals("<init>")
                    && invoke.type().stringValue().equals("(Ljava/lang/String;I)V")) {
                    offenders.add(method.methodName().stringValue());
                }
                if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals("java/net/InetAddress")
                    && (invoke.name().stringValue().equals("getByName") || invoke.name().stringValue().equals("getAllByName"))) {
                    offenders.add(method.methodName().stringValue() + " (InetAddress lookup)");
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "DNS-capable address construction in the handshake listener: " + offenders);
    }

    @Test
    void handshakeListenerUsesTheLiteralOnlyHelperForBothPaths() throws IOException {
        int helperCalls = 0;
        for (final MethodModel method : parse(ServerHandshakePacketListenerImpl.class).methods()) {
            for (final CodeElement element : method.code().map(code -> (Iterable<CodeElement>)code).orElse(List.of())) {
                if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals("dev/iyanz/sourbycraft/net/HandshakeAddresses")
                    && invoke.name().stringValue().equals("literalSocketAddress")) {
                    ++helperCalls;
                }
            }
        }
        // PlayerHandshakeEvent path (1) + BungeeCord condition (1) + BungeeCord assignment (1)
        assertEquals(3, helperCalls);
    }

    @Test
    void codeOfConductAcceptanceHopsToTheGlobalThreadBeforeFinishingTheTask() throws IOException {
        final List<String> calls = new ArrayList<>();
        for (final MethodModel method : parse(ServerConfigurationPacketListenerImpl.class).methods()) {
            if (!method.methodName().stringValue().equals("handleAcceptCodeOfConduct")) continue;
            for (final CodeElement element : method.code().orElseThrow()) {
                if (element instanceof InvokeInstruction invoke) {
                    calls.add(invoke.name().stringValue());
                }
            }
        }
        final int hop = calls.indexOf("ensureRunningOnSameThread");
        final int finish = calls.indexOf("finishCurrentTask");
        assertTrue(hop >= 0, "handleAcceptCodeOfConduct must call PacketUtils.ensureRunningOnSameThread: " + calls);
        assertTrue(finish > hop, "the thread hop must come before finishCurrentTask: " + calls);
    }

    private static java.lang.classfile.ClassModel parse(final Class<?> type) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
