package dev.iyanz.sourbycraft.abysslink;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLinkClientTest {
    static AbyssLinkSettings settings() {
        return new AbyssLinkSettings(false,"localhost",25580,"backend","localhost","","","",100,1000,1000,100,250,2,1000,true,2000);
    }
    record Fixture(AbyssLinkClient client, EmbeddedChannel channel) implements AutoCloseable {
        public void close() { client.close(); channel.finishAndReleaseAll(); }
    }
    static Fixture connected() {
        var client = new AbyssLinkClient(settings());
        var channel = new EmbeddedChannel(new AbyssLinkSession(client,new byte[16]));
        AbyssLinkFrame hello = channel.readOutbound();
        assertEquals(1, hello.type());
        channel.writeInbound(new AbyssLinkFrame(2,hello.requestId(),encode(Map.of(1,value(true),2,u8(0),3,value("heartbeat,ping,health,threat")))));
        assertTrue(client.connected());
        return new Fixture(client,channel);
    }
    @Test void disconnectedFallbackIsImmediateAndDoesNotQueue() {
        try(var client = new AbyssLinkClient(settings())) {
            assertTrue(client.resolve(UUID.randomUUID()).join().isEmpty());
            assertEquals(0,client.pendingCount());
        }
    }
    @Test void correlatesPlayerRequestAndCachesOnlyCurrentSession() {
        try(var f = connected()) {
            UUID player = UUID.randomUUID();
            var result = f.client.resolve(player);
            AbyssLinkFrame query = f.channel.readOutbound();
            assertEquals(4,query.type());
            f.channel.writeInbound(new AbyssLinkFrame(5,query.requestId(),encode(Map.of(1,value(player),2,value(24),3,value(System.currentTimeMillis()),4,value(true)))));
            assertEquals(24,result.join().orElseThrow().latencyMillis());
            assertEquals(24,f.client.resolve(player).join().orElseThrow().latencyMillis());
            assertNull(f.channel.readOutbound());
            f.channel.close();
            assertTrue(f.client.resolve(player).join().isEmpty());
        }
    }
    @Test void deduplicatesAndBoundsOutstandingRequests() {
        try(var f = connected()) {
            UUID player=UUID.randomUUID();
            var first=f.client.resolve(player); var second=f.client.resolve(player);
            assertNotNull(f.channel.readOutbound()); assertNull(f.channel.readOutbound());
            f.client.resolve(UUID.randomUUID()); assertNotNull(f.channel.readOutbound());
            assertTrue(f.client.resolve(UUID.randomUUID()).join().isEmpty());
            assertEquals(2,f.client.pendingCount());
            f.channel.close();
            assertTrue(first.join().isEmpty()); assertTrue(second.join().isEmpty());
            assertEquals(0,f.client.pendingCount());
        }
    }
    @Test void rejectsMismatchedUuidAndExpiresSamples() {
        try(var f = connected()) {
            var future=f.client.resolve(UUID.randomUUID()); AbyssLinkFrame q=f.channel.readOutbound();
            f.channel.writeInbound(new AbyssLinkFrame(5,q.requestId(),encode(Map.of(1,value(UUID.randomUUID()),2,value(24),3,value(System.currentTimeMillis()),4,value(true)))));
            assertTrue(future.join().isEmpty()); assertFalse(f.client.connected());
        }
        try(var f = connected()) {
            UUID id=UUID.randomUUID();var future=f.client.resolve(id); AbyssLinkFrame q=f.channel.readOutbound();
            f.channel.writeInbound(new AbyssLinkFrame(5,q.requestId(),encode(Map.of(1,value(id),2,value(24),3,value(System.currentTimeMillis()-5000),4,value(true)))));
            assertTrue(future.join().isEmpty());
        }
    }
    @Test void heartbeatEchoesProxyHighHalfAndTimesOutHandshake() {
        try(var f=connected()) {
            f.channel.writeInbound(new AbyssLinkFrame(3,Long.MIN_VALUE,new byte[0]));
            AbyssLinkFrame echoed=f.channel.readOutbound();assertEquals(Long.MIN_VALUE,echoed.requestId());
        }
        try(var client=new AbyssLinkClient(settings())) {
            var clock=new java.util.concurrent.atomic.AtomicLong();
            var channel=new EmbeddedChannel(new AbyssLinkSession(client,new byte[16],clock::get));channel.readOutbound();
            clock.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200));
            channel.advanceTimeBy(200,java.util.concurrent.TimeUnit.MILLISECONDS);channel.runScheduledPendingTasks();
            assertFalse(channel.isActive()); channel.finishAndReleaseAll();
        }
    }
    @Test void duplicateCancellationCannotCreateUnboundedDependents() {
        try(var f=connected()) {
            UUID player=UUID.randomUUID();
            for(int i=0;i<4;i++)assertTrue(f.client.resolve(player).cancel(false));
            for(int i=0;i<10000;i++)assertTrue(f.client.resolve(player).join().isEmpty());
            assertEquals(1,f.client.pendingCount());assertNotNull(f.channel.readOutbound());assertNull(f.channel.readOutbound());
        }
    }
    @Test void timeoutClearsRequestsAndLateResultCannotResurrectThem() {
        try(var f=connected()) {
            UUID player=UUID.randomUUID();var pending=f.client.resolve(player);AbyssLinkFrame query=f.channel.readOutbound();
            f.channel.advanceTimeBy(150,java.util.concurrent.TimeUnit.MILLISECONDS);f.channel.runScheduledPendingTasks();
            assertTrue(pending.join().isEmpty());assertEquals(0,f.client.pendingCount());
            f.channel.writeInbound(new AbyssLinkFrame(5,query.requestId(),encode(Map.of(1,value(player),2,value(22),3,value(System.currentTimeMillis()),4,value(true)))));
            assertTrue(pending.join().isEmpty());
        }
    }
    @Test void noPingCapabilityAndRejectedAckFallBack() {
        for(boolean accepted:new boolean[]{true,false})try(var client=new AbyssLinkClient(settings())) {
            var channel=new EmbeddedChannel(new AbyssLinkSession(client,new byte[16]));AbyssLinkFrame hello=channel.readOutbound();
            channel.writeInbound(new AbyssLinkFrame(2,hello.requestId(),encode(Map.of(1,value(accepted),2,u8(0),3,value("heartbeat")))));
            assertTrue(client.resolve(UUID.randomUUID()).join().isEmpty());
            assertEquals(accepted,channel.isActive());channel.finishAndReleaseAll();
        }
    }
    @Test void proxyHeartbeatLivenessExpiresWithoutInventingControlRtt() {
        var clock=new java.util.concurrent.atomic.AtomicLong();
        try(var client=new AbyssLinkClient(settings())) {
            var channel=new EmbeddedChannel(new AbyssLinkSession(client,new byte[16],clock::get));AbyssLinkFrame hello=channel.readOutbound();
            channel.writeInbound(new AbyssLinkFrame(2,hello.requestId(),encode(Map.of(1,value(true),2,u8(0),3,value("heartbeat")))));
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            channel.advanceTimeBy(4,java.util.concurrent.TimeUnit.SECONDS);channel.runScheduledPendingTasks();
            assertFalse(channel.isActive());assertEquals(0,client.metrics.rttNanos);channel.finishAndReleaseAll();
        }
    }

    @Test void previousGenerationSampleAndHandlerCannotSatisfyNewSession() {
        try(var f=connected()) {
            UUID player=UUID.randomUUID();var result=f.client.resolve(player);AbyssLinkFrame query=f.channel.readOutbound();
            f.channel.writeInbound(new AbyssLinkFrame(5,query.requestId(),encode(Map.of(1,value(player),2,value(24),3,value(System.currentTimeMillis()),4,value(true)))));
            var sample=result.join().orElseThrow();assertTrue(f.client.current(sample));
            var oldSession=f.channel.pipeline().get(AbyssLinkSession.class);f.channel.close();assertFalse(f.client.current(sample));
            var replacement=new EmbeddedChannel(new AbyssLinkSession(f.client,new byte[16]));AbyssLinkFrame hello=replacement.readOutbound();
            replacement.writeInbound(new AbyssLinkFrame(2,hello.requestId(),encode(Map.of(1,value(true),2,u8(0),3,value("heartbeat,ping")))));
            assertFalse(f.client.current(sample));
            var pending=f.client.resolve(player);AbyssLinkFrame next=replacement.readOutbound();
            var fields=Map.of(1,value(player),2,value(1),3,value(System.currentTimeMillis()),4,value(true));
            assertThrows(IllegalArgumentException.class,() -> f.client.result(oldSession,new AbyssLinkFrame(5,next.requestId(),encode(fields)),fields));
            assertFalse(pending.isDone());replacement.close();assertTrue(pending.join().isEmpty());replacement.finishAndReleaseAll();
        }
    }

    @Test void peerErrorInvalidatesSessionAndCompletesAllRequests() {
        try(var f=connected()) {
            var first=f.client.resolve(UUID.randomUUID());var second=f.client.resolve(UUID.randomUUID());
            AbyssLinkFrame query=f.channel.readOutbound();f.channel.readOutbound();
            f.channel.writeInbound(new AbyssLinkFrame(255,query.requestId(),encode(Map.of(1,u16(3),2,value("Busy")))));
            assertFalse(f.client.connected());assertTrue(first.join().isEmpty());assertTrue(second.join().isEmpty());
            assertEquals(0,f.client.pendingCount());
        }
    }

}
