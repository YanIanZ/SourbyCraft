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
    private long helloId,startedNanos,windowNanos,activeSinceNanos;
    private int frameCount;
    /** Why this side closed the connection, for the link-down log line; the first reason wins. */
    private volatile String closeReason;
    /** An authentication failure of this attempt was already reported (at most one per connection). */
    private boolean reported;
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
                // Every rejection names what the proxy answered; the text fields are sanitized before they are logged.
                if(frame.type()==255){
                    int code=unsignedShort(fields,1);String text=string(fields,2,256);AbyssLinkSecurityBridge.sanitized(text);
                    throw new IllegalArgumentException("the proxy answered HELLO with error "+code+(text.isEmpty()?"":" ("+text+")"));
                }
                if(frame.type()!=2 || frame.requestId()!=helloId)throw new IllegalArgumentException("expected the answer to HELLO, the proxy sent frame type "+frame.type());
                boolean ok=bool(fields,1);int code=u8(fields,2);
                String text=fields.containsKey(4)?string(fields,4,256):"";AbyssLinkSecurityBridge.sanitized(text);
                if(!ok || code!=0)throw new IllegalArgumentException("the proxy refused HELLO (code "+code+(text.isEmpty()?"":", "+text)+")");
                String accepted=string(fields,3,1024);
                Set<String> negotiated=new HashSet<>();
                for(String token:accepted.split(",",-1)) {
                    if(!OFFERED.contains(token) || !negotiated.add(token))throw new IllegalArgumentException("Invalid capability");
                }
                if(!negotiated.contains("heartbeat"))throw new IllegalArgumentException("the proxy did not accept the heartbeat capability");
                capabilities=Set.copyOf(negotiated);active=true;activeSinceNanos=now;heartbeat.received(now);client.authenticated(this);
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
                case 255 -> {
                    int code=unsignedShort(fields,1);String text=string(fields,2,256);AbyssLinkSecurityBridge.sanitized(text);
                    client.error(this,frame.requestId());
                    close("the proxy sent error "+code+(text.isEmpty()?"":" ("+text+")")+" for request "+Long.toUnsignedString(frame.requestId()));
                }
                default -> throw new IllegalArgumentException("Unexpected message direction");
            }
        }catch(RuntimeException invalid){
            client.metrics.rejected.increment();
            String why=invalid.getMessage()==null || invalid.getMessage().isBlank()?invalid.getClass().getSimpleName():invalid.getMessage();
            if(!active)authenticationFailed(why);
            close("protocol error: "+why);
        }
    }
    /** Reports this attempt's authentication failure once; later closes of the same connection stay silent. */
    private void authenticationFailed(String detail){
        if(reported)return;
        reported=true;client.authenticationFailed(detail);
    }
    private void require(String token){if(!capabilities.contains(token))throw new IllegalArgumentException("Capability required");}
    private void tick(){
        long now=clock.getAsLong();
        if(!active){
            if(now-startedNanos>=TimeUnit.MILLISECONDS.toNanos(client.settings.timeoutMillis())){
                client.metrics.timeouts.increment();
                authenticationFailed("no answer to HELLO within "+client.settings.timeoutMillis()+" ms");
                close("HELLO timed out");
            }
            return;
        }
        // Proxy owns the heartbeat stream in v1 (no request/response flag). Never create an echo loop.
        if(heartbeat.expired(now,client.settings)){
            client.metrics.timeouts.increment();
            close("no heartbeat from the proxy for "+(3L*client.settings.heartbeatMillis()+client.settings.timeoutMillis())+" ms");
        }
    }
    boolean send(AbyssLinkFrame frame){
        if(!ctx.channel().isActive())return false;
        if(!ctx.channel().isWritable()){client.metrics.backpressure.increment();close("write buffer full, the proxy is not reading");return false;}
        client.metrics.framesOut.increment();client.metrics.bytesOut.add(20L+frame.wirePayload().length);
        ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);return true;
    }
    void close(){close("closed by this server");}
    /** Closes the connection, keeping the first reason for the link-down log line. */
    void close(String reason){
        if(closeReason==null)closeReason=reason;
        if(ctx!=null)ctx.close();
    }
    @Override public void channelInactive(ChannelHandlerContext ctx){
        boolean wasActive=active;
        active=false;if(tick!=null)tick.cancel(false);
        // Leviathan refuses a HELLO (node not allowed for this client certificate, node already connected, no
        // AbyssLink entitlement) by closing the connection without an answer, so name those causes here.
        if(!wasActive)authenticationFailed(closeReason!=null?"closed before the proxy answered HELLO: "+closeReason
            :"the proxy closed the connection without answering HELLO as node '"+client.settings.nodeId()
            +"' (node id not accepted for this client certificate, the same node id already connected, or AbyssLink not enabled or licensed on the proxy)");
        client.lost(this,wasActive?(closeReason==null?"the proxy closed the connection":closeReason):null,wasActive?clock.getAsLong()-activeSinceNanos:0L);
        ctx.fireChannelInactive();
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause){
        client.metrics.rejected.increment();
        failed("connection error: "+AbyssLinkClient.describe(cause));
    }
    /** Closes on a transport or decode error, reported as an authentication failure while not yet authenticated. */
    void failed(String why){
        if(!active)authenticationFailed(why);
        close(why);
    }
    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx){if(!ctx.channel().isWritable()){client.metrics.backpressure.increment();close("write buffer full, the proxy is not reading");}ctx.fireChannelWritabilityChanged();}
}
