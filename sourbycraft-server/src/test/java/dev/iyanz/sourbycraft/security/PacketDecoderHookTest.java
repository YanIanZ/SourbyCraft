package dev.iyanz.sourbycraft.security;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the native decoder dispatch; the framing codec supplies already-decoded native packets. */
@org.bukkit.support.environment.VanillaFeature
class PacketDecoderHookTest {
    @TempDir Path directory;
    private final SecurityGuards guards = SecurityGuards.INSTANCE;

    @BeforeAll static void bootstrapConnectionDependencies() {
        // Registries come from @VanillaFeature (shared across the suite; a second setup throws "Registry already loaded").
        io.papermc.paper.configuration.GlobalConfigTestingBase.setupGlobalConfigForTest(org.bukkit.support.RegistryHelper.registryAccess());
    }

    @AfterEach void restoreDefaults() throws Exception {
        configure("");
        assertFalse(guards.inPacket(), "network scope leaked beyond decode");
    }

    private void configure(String yaml) throws Exception {
        Path file = directory.resolve("guards.yml");
        Files.writeString(file, yaml);
        guards.load(file);
    }

    @Test void bookSignAndAnvilDispatchObserveContentAndForwardPacketsInLogMode() {
        verifyObserved(new ServerboundEditBookPacket(0, java.util.Collections.nCopies(65, "x".repeat(1024)), Optional.empty()), SecurityGuards.Limit.BOOK_CHARS);
        verifyObserved(new ServerboundSignUpdatePacket(BlockPos.ZERO, true, "x".repeat(257), "x".repeat(256), "x".repeat(256), "x".repeat(256)),
            SecurityGuards.Limit.SIGN_LINE, SecurityGuards.Limit.SIGN_TOTAL);
        verifyObserved(new ServerboundRenameItemPacket("x".repeat(129)), SecurityGuards.Limit.ANVIL_NAME);
    }

    @Test void everyRecipeVariantDispatchesPacketByteLimit() throws Exception {
        configure("crash-prevention:\n  recipe-book:\n    max-packet-size: 2\n");
        for (Packet<ServerGamePacketListener> packet : List.<Packet<ServerGamePacketListener>>of(
            new ServerboundRecipeBookChangeSettingsPacket(RecipeBookType.CRAFTING, true, false),
            new ServerboundRecipeBookSeenRecipePacket(new RecipeDisplayId(0)),
            new ServerboundPlaceRecipePacket(0, new RecipeDisplayId(0), false))) {
            long before = guards.violations(SecurityGuards.Limit.RECIPE_BYTES);
            try (TestChannel channel = channel(input -> packet)) {
                assertTrue(channel.writeInbound(Unpooled.buffer().writeZero(3)));
                assertSame(packet, channel.readInbound());
                assertEquals(before + 1, guards.violations(SecurityGuards.Limit.RECIPE_BYTES));
            }
        }
    }

    @Test void containerAndEveryRecipeVariantDispatchTheirPerConnectionRateGuard() throws Exception {
        configure("packet-guard:\n  container-click:\n    max-per-tick: 1\n  recipe-book:\n    max-per-tick: 1\n");
        for (Packet<ServerGamePacketListener> packet : List.<Packet<ServerGamePacketListener>>of(click((short) 0, (byte) 0),
            new ServerboundRecipeBookChangeSettingsPacket(RecipeBookType.CRAFTING, true, false),
            new ServerboundRecipeBookSeenRecipePacket(new RecipeDisplayId(0)),
            new ServerboundPlaceRecipePacket(0, new RecipeDisplayId(0), false))) {
            var limit = packet instanceof ServerboundContainerClickPacket ? SecurityGuards.Limit.CONTAINER_RATE : SecurityGuards.Limit.RECIPE_RATE;
            long before = guards.violations(limit);
            try (TestChannel channel = channel(input -> packet)) {
                // A large finite burst avoids depending on which 50 ms bucket contains the first write.
                for (int i = 0; i < 100; i++) {
                    assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(0)));
                    assertSame(packet, channel.readInbound());
                }
                assertTrue(guards.violations(limit) > before, "missing rate dispatch for " + packet.getClass());
            }
        }
    }

    @Test void defaultContainerGuardAllows128SlotQuickCraftBurstIncludingStartAndEnd() {
        long before = guards.violations(SecurityGuards.Limit.CONTAINER_RATE);
        try (TestChannel channel = channel(input -> {
            int phase = input.getUnsignedByte(0);
            return phase == 0 ? click((short) -999, (byte) 0)
                : phase == 129 ? click((short) -999, (byte) 2)
                : click((short) (phase - 1), (byte) 1);
        })) {
            for (int i = 0; i < 130; i++) {
                assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(i)));
                assertInstanceOf(ServerboundContainerClickPacket.class, channel.readInbound());
            }
        }
        assertEquals(before, guards.violations(SecurityGuards.Limit.CONTAINER_RATE));
    }

    @Test void connectionPacketAndByteLimitsAreInvokedBeforeCodecAndScopeCloses() throws Exception {
        configure("packet-guard:\n  connection:\n    mode: KICK\n    max-bytes-per-second: 1\n");
        long before = guards.violations(SecurityGuards.Limit.CONNECTION_BYTES);
        try (TestChannel channel = channel(input -> { fail("byte guard must reject before codec"); return null; })) {
            installConnection(channel);
            assertFalse(channel.writeInbound(Unpooled.buffer().writeZero(2)));
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(guards.inPacket());
        }
        assertEquals(before + 1, guards.violations(SecurityGuards.Limit.CONNECTION_BYTES));
        configure("packet-guard:\n  connection:\n    max-per-second: 1\n");
        before = guards.violations(SecurityGuards.Limit.CONNECTION_RATE);
        try (TestChannel channel = channel(input -> new ServerboundRenameItemPacket("ok"))) {
            for (int i = 0; i < 100; i++) {
                channel.writeInbound(Unpooled.buffer().writeByte(0));
                assertNotNull(channel.readInbound());
            }
        }
        assertTrue(guards.violations(SecurityGuards.Limit.CONNECTION_RATE) > before);
    }

    @Test void explicitKickFlowsThroughRealConnectionExceptionHandlerAndClosesChannel() throws Exception {
        configure("packet-guard:\n  book-edit:\n    mode: KICK\n    max-total-chars: 1\n");
        try (TestChannel channel = channel(input -> new ServerboundEditBookPacket(0, List.of("xx"), Optional.empty()))) {
            installConnection(channel);
            assertFalse(channel.writeInbound(Unpooled.buffer().writeByte(0)));
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(guards.inPacket());
        }
    }

    @Test void codecFailureAlsoClearsScope() {
        try (TestChannel channel = channel(input -> { throw new IllegalArgumentException("bad frame"); })) {
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> channel.writeInbound(Unpooled.buffer().writeByte(0)));
            assertFalse(guards.inPacket());
        }
    }

    private static void installConnection(TestChannel channel) {
        var connection = new Connection(PacketFlow.SERVERBOUND);
        connection.setListenerForServerboundHandshake(new PacketListener() {
            public PacketFlow flow() { return PacketFlow.SERVERBOUND; }
            public ConnectionProtocol protocol() { return ConnectionProtocol.HANDSHAKING; }
            public void onDisconnect(DisconnectionDetails details) {}
            public boolean isAcceptingMessages() { return true; }
        });
        channel.pipeline().addLast("connection", connection);
        // Added after activation: deliver the lifecycle notification the native pipeline receives.
        channel.pipeline().fireChannelActive();
    }

    private void verifyObserved(Packet<ServerGamePacketListener> packet, SecurityGuards.Limit... limits) {
        long[] before = java.util.Arrays.stream(limits).mapToLong(guards::violations).toArray();
        try (TestChannel channel = channel(input -> packet)) {
            assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(0)));
            assertSame(packet, channel.readInbound(), "LOG must preserve the decoded packet");
            assertFalse(guards.inPacket());
        }
        for (int i = 0; i < limits.length; i++) assertEquals(before[i] + 1, guards.violations(limits[i]), limits[i].name());
    }

    private static ServerboundContainerClickPacket click(short slot, byte button) {
        return new ServerboundContainerClickPacket(0, 0, slot, button, ContainerInput.QUICK_CRAFT, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
    }

    private static final class TestChannel implements AutoCloseable {
        private final EmbeddedChannel delegate;
        TestChannel(io.netty.channel.ChannelHandler handler) { delegate = new EmbeddedChannel(handler); }
        boolean writeInbound(Object message) { return delegate.writeInbound(message); }
        Object readInbound() { return delegate.readInbound(); }
        io.netty.channel.ChannelPipeline pipeline() { return delegate.pipeline(); }
        void runPendingTasks() { delegate.runPendingTasks(); }
        boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.finishAndReleaseAll(); }
    }

    private static TestChannel channel(Function<ByteBuf, Packet<ServerGamePacketListener>> decode) {
        ProtocolInfo<ServerGamePacketListener> protocol = new ProtocolInfo<>() {
            public ConnectionProtocol id() { return ConnectionProtocol.PLAY; }
            public PacketFlow flow() { return PacketFlow.SERVERBOUND; }
            public BundlerInfo bundlerInfo() { return null; }
            public StreamCodec<ByteBuf, Packet<? super ServerGamePacketListener>> codec() {
                return new StreamCodec<>() {
                    public Packet<? super ServerGamePacketListener> decode(ByteBuf input) {
                        try { return decode.apply(input); }
                        finally { input.skipBytes(input.readableBytes()); }
                    }
                    public void encode(ByteBuf output, Packet<? super ServerGamePacketListener> packet) { throw new UnsupportedOperationException(); }
                };
            }
        };
        return new TestChannel(new PacketDecoder<>(protocol));
    }
}
