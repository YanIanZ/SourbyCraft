package dev.iyanz.sourbycraft.security;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pins admission in executable native code without initializing a live MinecraftServer. */
class AdmissionHookBytecodeTest {
    private static final String GUARDS = "dev/iyanz/sourbycraft/security/SecurityGuards";
    private static final String CONNECTION = "net/minecraft/network/Connection";
    private static final String TCP = "net/minecraft/server/network/ServerConnectionListener";
    private static final String LOGIN = "net/minecraft/server/network/ServerLoginPacketListenerImpl";
    private static final String HELLO_PACKET = "net/minecraft/network/protocol/login/ServerboundHelloPacket";

    @Test void tcpAdmissionRunsBeforeCodecsListenersAndConnectionRegistration() throws IOException {
        // Select the Channel overload by descriptor, rather than a synthetic bridge if one exists.
        var code = elements(method(parse(TCP + "$1"), "initChannel", "(Lio/netty/channel/Channel;)V"));
        int guard = uniqueCall(code, GUARDS, "connectionOpened", "(Lio/netty/channel/Channel;)Z");
        assertEquals(guard, first(code, element -> element instanceof InvokeInstruction),
            "TCP admission must run before any pipeline/connection setup call");
        assertRejectsFalseBeforeAccepting(code, guard, "io/netty/channel/Channel", "close");
        assertBeforeCall(code, guard, CONNECTION, "configureSerialization");
        assertBeforeCall(code, guard, CONNECTION, "setListenerForServerboundHandshake");
        assertBeforeCall(code, guard, CONNECTION, "configurePacketHandler");
        assertBeforeCall(code, guard, CONNECTION, "<init>");
        assertBeforeCall(code, guard, "io/papermc/paper/network/ChannelInitializeListenerHolder", "callListeners");
        assertBeforeCall(code, guard, "io/papermc/paper/threadedregions/RegionizedServer", "addConnection");
    }

    @Test void loginAdmissionFollowsHelloStateValidationAndPrecedesIdentityAndAuthentication() throws IOException {
        var code = elements(method(parse(LOGIN), "handleHello", "(L" + HELLO_PACKET + ";)V"));
        int guard = uniqueCall(code, GUARDS, "loginStarted", "(Lio/netty/channel/Channel;Ljava/net/SocketAddress;)Z");
        assertArgumentIsRemoteAddress(code, guard);
        int state = first(code, element -> element instanceof FieldInstruction field
            && field.owner().asInternalName().equals(LOGIN + "$State") && field.name().equalsString("HELLO"));
        int validate = first(code, element -> element instanceof InvokeInstruction call
            && call.owner().asInternalName().equals("org/apache/commons/lang3/Validate") && call.name().equalsString("validState"));
        assertTrue(state >= 0 && state < validate && validate < guard,
            "HELLO state must be validated before counting login admission");
        assertRejectsFalseBeforeAccepting(code, guard, LOGIN, "disconnect");
        assertBeforeCall(code, guard, HELLO_PACKET, "name");
        assertBeforeCall(code, guard, HELLO_PACKET, "profileId");
        assertBeforeCall(code, guard, "net/minecraft/util/StringUtil", "isReasonablePlayerName");
        assertBeforeCall(code, guard, "net/minecraft/server/MinecraftServer", "getSingleplayerProfile");
        assertBeforeCall(code, guard, "net/minecraft/server/MinecraftServer", "usesAuthentication");
        assertBeforeCall(code, guard, LOGIN, "startClientVerification");
        int executor = first(code, element -> element instanceof InvokeInstruction call && call.name().equalsString("execute"));
        assertTrue(executor > guard, "authenticator submission must follow admission");
    }

    @Test void proxyModeIsConfiguredBeforeTheTcpInitializerAndBind() throws IOException {
        var code = elements(method(parse(TCP), "startTcpServerListener", "(Ljava/net/SocketAddress;)V"));
        int configure = uniqueCall(code, GUARDS, "configureProxyMode", "(ZZZ)V");
        assertBeforeCall(code, configure, TCP + "$1", "<init>");
        assertBeforeCall(code, configure, "io/netty/bootstrap/ServerBootstrap", "<init>");
        int bind = first(code, element -> element instanceof InvokeInstruction call && call.name().equalsString("bind"));
        assertTrue(bind > configure, "proxy exemption must be published before accepting sockets");
        for (String setting : List.of("bungee", "velocity", "proxyProtocol")) {
            int field = first(code, element -> element instanceof FieldInstruction access && access.name().equalsString(setting));
            assertTrue(field >= 0 && field < configure, "proxy configuration must read " + setting + " before publication");
        }
    }

    @Test void velocityForwardedAddressIsAdmittedBeforeProfileAndAuthenticatorWork() throws IOException {
        var code = elements(method(parse(LOGIN), "handleCustomQueryPacket0",
            "(Lnet/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket;)V"));
        int guard = uniqueCall(code, GUARDS, "loginForwarded", "(Lio/netty/channel/Channel;Ljava/net/SocketAddress;)Z");
        assertArgumentIsRemoteAddress(code, guard);
        int forwardedStore = first(code, element -> element instanceof FieldInstruction field && field.opcode() == Opcode.PUTFIELD
            && field.owner().asInternalName().equals(CONNECTION) && field.name().equalsString("address"));
        assertTrue(forwardedStore >= 0 && forwardedStore < guard, "the forwarded address must be stored before admission keys it");
        assertRejectsFalseBeforeAccepting(code, guard, LOGIN, "disconnect");
        assertBeforeCall(code, guard, "com/destroystokyo/paper/proxy/VelocityProxy", "createProfile");
        int executor = first(code, element -> element instanceof InvokeInstruction call && call.name().equalsString("execute"));
        assertTrue(executor > guard, "authenticator submission must follow forwarded admission");
    }

    /** The address argument is the value of Connection.getRemoteAddress(), i.e. the forwarded one. */
    private static void assertArgumentIsRemoteAddress(List<CodeElement> code, int guard) {
        int previous = -1;
        for (int i = guard - 1; i >= 0; i--) if (code.get(i) instanceof Instruction) { previous = i; break; }
        assertTrue(previous >= 0 && code.get(previous) instanceof InvokeInstruction call
            && call.owner().asInternalName().equals(CONNECTION) && call.name().equalsString("getRemoteAddress"),
            "admission must key Connection.getRemoteAddress()");
    }

    /** A false boolean must fall through to close/disconnect and RETURN; true jumps past rejection. */
    private static void assertRejectsFalseBeforeAccepting(List<CodeElement> code, int guard, String owner, String rejection) {
        int next = nextInstruction(code, guard + 1);
        BranchInstruction branch = assertInstanceOf(BranchInstruction.class, code.get(next));
        assertEquals(Opcode.IFNE, branch.opcode(), "admission true must jump past the rejection path");
        int accepted = first(code, element -> element instanceof LabelTarget label && label.label().equals(branch.target()));
        assertTrue(accepted > next, "accepted path must follow the rejection block");
        int reject = -1;
        int returns = 0;
        for (int i = next + 1; i < accepted; i++) {
            CodeElement element = code.get(i);
            assertFalse(element instanceof BranchInstruction, "rejected admission must not branch into normal setup");
            if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals(owner) && call.name().equalsString(rejection)) reject = i;
            if (element instanceof ReturnInstruction) {
                assertTrue(reject >= 0 && reject < i, "false admission must call " + rejection + " before returning");
                returns++;
            }
        }
        assertEquals(1, returns, "rejected admission must return before any accepted setup");
    }

    private static void assertBeforeCall(List<CodeElement> code, int before, String owner, String name) {
        int call = first(code, element -> element instanceof InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(name));
        assertTrue(call > before, "missing or prematurely executed call " + owner + "." + name);
    }

    private static int uniqueCall(List<CodeElement> code, String owner, String name, String descriptor) {
        int found = -1;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof InvokeInstruction call && call.owner().asInternalName().equals(owner)
                && call.name().equalsString(name) && call.type().equalsString(descriptor)) {
                assertEquals(-1, found, "duplicate admission hook " + name);
                found = i;
            }
        }
        assertTrue(found >= 0, "missing admission hook " + name + descriptor);
        return found;
    }

    private static int first(List<CodeElement> code, Predicate<CodeElement> predicate) {
        for (int i = 0; i < code.size(); i++) if (predicate.test(code.get(i))) return i;
        return -1;
    }

    private static int nextInstruction(List<CodeElement> code, int from) {
        for (int i = from; i < code.size(); i++) if (code.get(i) instanceof Instruction) return i;
        return fail("missing instruction after admission hook");
    }

    private static MethodModel method(ClassModel type, String name, String descriptor) {
        return type.methods().stream().filter(method -> method.methodName().equalsString(name)
            && method.methodType().equalsString(descriptor)).findFirst().orElseThrow(() -> new AssertionError("missing method " + name + descriptor));
    }

    private static List<CodeElement> elements(MethodModel method) {
        var result = new ArrayList<CodeElement>();
        method.code().orElseThrow().forEach(result::add);
        return result;
    }

    private static ClassModel parse(String owner) throws IOException {
        try (InputStream input = AdmissionHookBytecodeTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input, "missing native class " + owner);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
