package dev.yanianz.intave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Feature patch 0024: Connection.sendNative on the real patched Connection and a Netty EmbeddedChannel. */
final class NativeIntaveConnectionTest {
    private final List<String> writes = new ArrayList<>();
    private final List<String> dispatch = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private GlobalConfiguration previous;
    private Connection connection;
    private EmbeddedChannel channel;

    private final class TestPacket implements Packet<PacketListener> {
        final String name;
        boolean ready = true;
        boolean finishListener = true;
        List<Packet<?>> extras;
        TestPacket(String name) { this.name = name; }
        @Override public PacketType<TestPacket> type() {
            return new PacketType<>(PacketFlow.CLIENTBOUND, Identifier.fromNamespaceAndPath("intave", this.name));
        }
        @Override public void handle(PacketListener listener) {}
        @Override public boolean isReady() { return this.ready; }
        @Override public List<Packet<?>> getExtraPackets() { return this.extras; }
        @Override public boolean hasFinishListener() { return this.finishListener; }
        @Override public void onPacketDispatch(net.minecraft.server.level.ServerPlayer player) { dispatch.add(this.name); }
        @Override public void onPacketDispatchFinish(net.minecraft.server.level.ServerPlayer player, ChannelFuture future) {
            results.add(this.name + ":" + (future == null ? "cancelled" : future.isSuccess() ? "sent" : "failed"));
        }
    }

    private static void setGlobal(GlobalConfiguration value) throws Exception {
        Method set = GlobalConfiguration.class.getDeclaredMethod("set", GlobalConfiguration.class);
        set.setAccessible(true);
        set.invoke(null, value);
    }

    @BeforeEach void setUp() throws Exception {
        this.previous = GlobalConfiguration.get();
        GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        config.packetLimiter = config.new PacketLimiter();
        setGlobal(config);
        this.connection = new Connection(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                TestPacket packet = (TestPacket) message;
                writes.add(packet.name + ":" + NativeIntave.packetFiltersEnabled());
                if (packet.name.equals("failed")) {
                    promise.setFailure(new IllegalStateException("controlled write failure"));
                    return;
                }
                ctx.write(message, promise);
            }
        }, this.connection);
    }

    @AfterEach void tearDown() throws Exception {
        this.channel.finishAndReleaseAll();
        setGlobal(this.previous);
    }

    @Test void nativePacketsKeepQueueOrderReadinessAndExtras() {
        TestPacket blocked = new TestPacket("blocked");
        blocked.ready = false;
        TestPacket nativePacket = new TestPacket("native");
        TestPacket extra = new TestPacket("extra");
        TestPacket nested = new TestPacket("nested");
        extra.extras = List.of(nested);
        nativePacket.extras = List.of(extra);
        this.connection.sendNative(blocked, false);
        this.connection.sendNative(nativePacket, false);
        this.connection.send(new TestPacket("regular"));
        assertTrue(this.writes.isEmpty(), "a not-ready head retains every later packet: " + this.writes);
        blocked.ready = true;
        this.connection.flushChannel();
        this.channel.runPendingTasks();
        assertEquals(List.of("blocked:false", "native:false", "extra:false", "nested:false", "regular:true"), this.writes);
        assertEquals(List.of("blocked", "native", "regular"), this.dispatch);
        assertEquals(List.of("blocked:sent", "native:sent", "extra:sent", "nested:sent", "regular:sent"), this.results);
        assertTrue(NativeIntave.packetFiltersEnabled());
    }

    @Test void nativeFilterTrueKeepsFilters() {
        this.connection.sendNative(new TestPacket("filtered"), true);
        this.channel.runPendingTasks();
        assertEquals(List.of("filtered:true"), this.writes);
        assertEquals(List.of("filtered:sent"), this.results);
    }

    @Test void failedNativeWriteReportsFailureOnceAndRestoresFilters() {
        this.connection.sendNative(new TestPacket("failed"), false);
        this.connection.send(new TestPacket("regular"));
        this.channel.runPendingTasks();
        assertEquals(List.of("failed:false", "regular:true"), this.writes);
        assertEquals(List.of("failed:failed", "regular:sent"), this.results);
        assertTrue(NativeIntave.packetFiltersEnabled());
    }

    @Test void clearedNativePacketsAreCancelledNotWritten() {
        TestPacket blocked = new TestPacket("blocked");
        blocked.ready = false;
        blocked.extras = List.of(new TestPacket("extra"));
        this.connection.sendNative(blocked, false);
        this.connection.clearPacketQueue();
        this.connection.clearPacketQueue();
        blocked.ready = true;
        this.connection.flushChannel();
        this.channel.runPendingTasks();
        assertTrue(this.writes.isEmpty());
        assertEquals(List.of("blocked:cancelled", "extra:cancelled"), this.results);
    }

    @Test void packetsWithoutFinishListenerStillUseTheVoidPromisePath() {
        TestPacket plain = new TestPacket("plain");
        plain.finishListener = false;
        this.connection.send(plain);
        this.channel.runPendingTasks();
        assertEquals(List.of("plain:true"), this.writes);
        assertTrue(this.results.isEmpty(), "no finish callback without a listener");
    }
}
