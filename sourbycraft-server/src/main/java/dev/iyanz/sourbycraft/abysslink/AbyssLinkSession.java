package dev.iyanz.sourbycraft.abysslink;

import io.netty.channel.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;

/** Installed only after verified TLS. All transport mutation is event-loop confined. */
final class AbyssLinkSession extends SimpleChannelInboundHandler<AbyssLinkFrame> {
    private static final Set<String> OFFERED=Set.of("heartbeat","ping","health","threat");
    private final AbyssLinkClient client;
    private final AbyssLinkHeartbeat heartbeat=new AbyssLinkHeartbeat();
    private final byte[] nonce;
    private final java.util.function.LongSupplier clock;
    private volatile Set<String> capabilities=Set.of();
    private volatile boolean active;
    private ChannelHandlerContext ctx;
    private long helloId,startedNanos,windowNanos;
    private int frameCount;
    private io.netty.util.concurrent.ScheduledFuture<?> tick;
    AbyssLinkSession(AbyssLinkClient client, byte[] nonce) { this(client,nonce,System::nanoTime); }
    AbyssLinkSession(AbyssLinkClient client,byte[] nonce,java.util.function.LongSupplier clock) {
        this.client=client;this.nonce=nonce.clone();this.clock=clock;
        if(nonce.length!=16)throw new IllegalArgumentException("Nonce length");
    }
    ChannelHandlerContext context(){return ctx;}
    boolean active(){return active && ctx!=null && ctx.channel().isActive();}
    Set<String> capabilities(){return capabilities;}
    void execute(Runnable task){if(ctx.executor().inEventLoop())task.run();else ctx.executor().execute(task);}
    @Override public void handlerAdded(ChannelHandlerContext ctx) { this.ctx=ctx;if(ctx.channel().isActive())begin(); }
    @Override public void channelActive(ChannelHandlerContext ctx){begin();ctx.fireChannelActive();}
    private void begin(){
        if(tick!=null)return;
        startedNanos=windowNanos=clock.getAsLong();helloId=client.nextId();
        send(new AbyssLinkFrame(1,helloId,encode(Map.of(1,value("BACKEND"),2,value(client.settings.nodeId()),3,value("heartbeat,ping,health,threat"),4,nonce))));
        tick=ctx.executor().scheduleAtFixedRate(this::tick,client.settings.timeoutMillis(),Math.min(1000,client.settings.timeoutMillis()),TimeUnit.MILLISECONDS);
    }
    @Override protected void channelRead0(ChannelHandlerContext ctx,AbyssLinkFrame frame){
        client.metrics.framesIn.increment();client.metrics.bytesIn.add(20L+frame.wirePayload().length);
        try {
            long now=clock.getAsLong();
            if(now-windowNanos>=TimeUnit.SECONDS.toNanos(1)){windowNanos=now;frameCount=0;}
            if(++frameCount>512)throw new IllegalArgumentException("Peer rate exceeded");
            if(frame.minor()!=0)throw new IllegalArgumentException("Unnegotiated minor");
            Map<Integer,byte[]> fields=decode(frame.wirePayload());
            if(!active){
                if(frame.type()!=2 || frame.requestId()!=helloId || !bool(fields,1) || u8(fields,2)!=0)throw new IllegalArgumentException("Handshake rejected");
                if(fields.containsKey(4))AbyssLinkSecurityBridge.sanitized(string(fields,4,256));
                String accepted=string(fields,3,1024);
                Set<String> negotiated=new HashSet<>();
                for(String token:accepted.split(",",-1)) {
                    if(!OFFERED.contains(token) || !negotiated.add(token))throw new IllegalArgumentException("Invalid capability");
                }
                if(!negotiated.contains("heartbeat"))throw new IllegalArgumentException("Heartbeat required");
                capabilities=Set.copyOf(negotiated);active=true;heartbeat.received(now);client.authenticated(this);
                return;
            }
            switch(frame.type()) {
                case 3 -> {
                    if(fields.containsKey(1))i64(fields,1);
                    heartbeat.received(now);
                    send(new AbyssLinkFrame(3,frame.requestId(),encode(fields.containsKey(1)?Map.of(1,fields.get(1)):Map.of())));
                }
                case 5 -> { require("ping");client.result(this,frame,fields); }
                case 7 -> { require("threat");AbyssLinkSecurityBridge.validate(fields); }
                case 255 -> { unsignedShort(fields,1);AbyssLinkSecurityBridge.sanitized(string(fields,2,256));client.error(this,frame.requestId());ctx.close(); }
                default -> throw new IllegalArgumentException("Unexpected message direction");
            }
        }catch(RuntimeException invalid){client.metrics.rejected.increment();ctx.close();}
    }
    private void require(String token){if(!capabilities.contains(token))throw new IllegalArgumentException("Capability required");}
    private void tick(){
        long now=clock.getAsLong();
        if(!active){if(now-startedNanos>=TimeUnit.MILLISECONDS.toNanos(client.settings.timeoutMillis())){client.metrics.timeouts.increment();ctx.close();}return;}
        // Proxy owns the heartbeat stream in v1 (no request/response flag). Never create an echo loop.
        if(heartbeat.expired(now,client.settings)){
            client.metrics.timeouts.increment();ctx.close();
        }
    }
    boolean send(AbyssLinkFrame frame){
        if(!ctx.channel().isActive())return false;
        if(!ctx.channel().isWritable()){client.metrics.backpressure.increment();ctx.close();return false;}
        client.metrics.framesOut.increment();client.metrics.bytesOut.add(20L+frame.wirePayload().length);
        ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);return true;
    }
    void close(){if(ctx!=null)ctx.close();}
    @Override public void channelInactive(ChannelHandlerContext ctx){active=false;if(tick!=null)tick.cancel(false);client.lost(this);ctx.fireChannelInactive();}
    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause){client.metrics.rejected.increment();ctx.close();}
    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx){if(!ctx.channel().isWritable()){client.metrics.backpressure.increment();ctx.close();}ctx.fireChannelWritabilityChanged();}
}
