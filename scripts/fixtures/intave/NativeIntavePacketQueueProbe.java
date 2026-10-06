import dev.yanianz.intave.NativeIntave;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.resources.Identifier;

/** V27: actual Connection queue/readiness/filter/finish semantics on Netty EmbeddedChannel. */
public final class NativeIntavePacketQueueProbe {
    static final List<String> writes = new ArrayList<>();
    static final List<String> dispatch = new ArrayList<>();
    static final List<String> finishes = new ArrayList<>();
    static final List<String> results = new ArrayList<>();
    static final class TestPacket implements Packet<PacketListener> {
        final String name;
        boolean ready = true;
        List<Packet<?>> extras = null;
        TestPacket(String name) { this.name = name; }
        public PacketType<TestPacket> type() { return new PacketType<>(PacketFlow.CLIENTBOUND, Identifier.fromNamespaceAndPath("intave", name)); }
        public void handle(PacketListener listener) {}
        public boolean isReady() { return ready; }
        public List<Packet<?>> getExtraPackets() { return extras; }
        public boolean hasFinishListener() { return true; }
        public void onPacketDispatch(net.minecraft.server.level.ServerPlayer player) { dispatch.add(name); }
        public void onPacketDispatchFinish(net.minecraft.server.level.ServerPlayer player, ChannelFuture future) {
            finishes.add(name);
            results.add(name + ":" + (future == null ? "cancelled" : future.isSuccess() ? "sent" : "failed"));
        }
    }
    static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        config.packetLimiter = config.new PacketLimiter();
        var set = GlobalConfiguration.class.getDeclaredMethod("set", GlobalConfiguration.class);
        set.setAccessible(true); set.invoke(null, config);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                TestPacket packet = (TestPacket) message;
                writes.add(packet.name + ":" + NativeIntave.packetFiltersEnabled());
                if (packet.name.equals("failed")) {
                    promise.setFailure(new IllegalStateException("controlled write failure"));
                    return;
                }
                ctx.write(message, promise);
            }
        }, connection);
        if (args[0].equals("clear")) {
            TestPacket blocked = new TestPacket("blocked"); blocked.ready = false;
            blocked.extras = List.of(new TestPacket("extra"));
            connection.sendNative(blocked, false);
            connection.clearPacketQueue();
            connection.clearPacketQueue();
            blocked.ready = true; connection.flushChannel(); channel.runPendingTasks();
            check(writes.isEmpty(), "cleared packets must not be written");
            check(dispatch.equals(List.of("blocked")), "dispatch before cancellation: " + dispatch);
            check(finishes.equals(List.of("blocked", "extra")), "cancelled finish callbacks: " + finishes);
            check(results.equals(List.of("blocked:cancelled", "extra:cancelled")), "cancelled futures: " + results);
            check(NativeIntave.packetFiltersEnabled(), "clear leaked filter scope");
            channel.finishAndReleaseAll();
            System.out.println("Native Connection clear probe passed");
            return;
        }
        if (args[0].equals("filtered")) {
            connection.sendNative(new TestPacket("filtered"), true);
            channel.runPendingTasks();
            check(writes.equals(List.of("filtered:true")), "native filter=true was lost: " + writes);
            check(finishes.equals(List.of("filtered")), "finish listener must complete once: " + finishes);
            check(results.equals(List.of("filtered:sent")), "successful future: " + results);
            check(NativeIntave.packetFiltersEnabled(), "filtered send leaked scope");
            channel.finishAndReleaseAll();
            System.out.println("Native Connection filtered probe passed");
            return;
        }
        if (args[0].equals("write-failure")) {
            connection.sendNative(new TestPacket("failed"), false);
            connection.send(new TestPacket("regular"));
            channel.runPendingTasks();
            check(writes.equals(List.of("failed:false", "regular:true")), "failure leaked filter scope: " + writes);
            check(finishes.equals(List.of("failed", "regular")), "failure finish callbacks: " + finishes);
            check(results.equals(List.of("failed:failed", "regular:sent")), "failed future not preserved: " + results);
            check(NativeIntave.packetFiltersEnabled(), "failed write leaked scope");
            channel.finishAndReleaseAll();
            System.out.println("Native Connection write failure probe passed");
            return;
        }
        check(args[0].equals("ordered"), "unknown scenario");
        TestPacket blocked = new TestPacket("blocked"); blocked.ready = false;
        TestPacket nativePacket = new TestPacket("native");
        TestPacket extra = new TestPacket("extra");
        TestPacket nested = new TestPacket("nested");
        extra.extras = List.of(nested); nativePacket.extras = List.of(extra);
        TestPacket regular = new TestPacket("regular");
        connection.sendNative(blocked, false);
        connection.sendNative(nativePacket, false);
        connection.send(regular);
        check(writes.isEmpty(), "not-ready head must retain every later packet: " + writes);
        blocked.ready = true; connection.flushChannel(); channel.runPendingTasks();
        check(writes.equals(List.of("blocked:false", "native:false", "extra:false", "nested:false", "regular:true")), "queue/filter order: " + writes);
        check(dispatch.equals(List.of("blocked", "native", "regular")), "dispatch callbacks: " + dispatch);
        check(finishes.equals(List.of("blocked", "native", "extra", "nested", "regular")), "finish callbacks: " + finishes);
        check(results.equals(List.of("blocked:sent", "native:sent", "extra:sent", "nested:sent", "regular:sent")), "finish futures: " + results);
        check(NativeIntave.packetFiltersEnabled(), "send filter scope leaked");
        check(channel.finishAndReleaseAll(), "expected retained outbound packets");
        System.out.println("Native Connection queue probe passed");
    }
}
