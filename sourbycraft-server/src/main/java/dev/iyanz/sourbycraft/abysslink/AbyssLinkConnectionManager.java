package dev.iyanz.sourbycraft.abysslink;

import dev.iyanz.sourbycraft.util.VirtualExecutor;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.*;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/** One control-plane event loop. DNS, PKIX trust/key loading and entropy run on bounded I/O. */
final class AbyssLinkConnectionManager implements AutoCloseable {
    private final AbyssLinkClient client;
    private final NioEventLoopGroup group=new NioEventLoopGroup(1, (java.util.concurrent.ThreadFactory)task -> {
        Thread thread=new Thread(task,"Aurora-AbyssLink");thread.setDaemon(true);return thread;
    });
    private volatile boolean stopped;
    private volatile Channel channel;
    private volatile java.util.concurrent.CompletableFuture<?> preparation;
    private volatile io.netty.util.concurrent.ScheduledFuture<?> retry;
    private int failures;
    /** Consecutive failed attempts since the last authentication; event-loop confined like {@link #failures}. */
    private int failedAttempts;
    /** The first failure is logged, then every Nth while it keeps failing. */
    private static final int LOG_EVERY=10;
    /** An authenticated session shorter than this counts as a flap; repeated flaps are logged like failed attempts. */
    private static final long BRIEF_SESSION_NANOS=TimeUnit.SECONDS.toNanos(60);
    /**
     * Configured host, resolved address and port of the attempt in progress, for server log lines only (never for
     * {@code /ping}); empty until an attempt has resolved its address. Event-loop confined like {@link #failures}.
     */
    private String target="";
    /** Consecutive authenticated sessions that ended within {@link #BRIEF_SESSION_NANOS}. */
    private int briefSessions;
    /** The next link-up is logged: true at start and after every logged link-down, so up/down lines always pair. */
    private boolean announceUp=true;
    AbyssLinkConnectionManager(AbyssLinkClient client){this.client=client;}
    void start(){prepare();}
    /** On the event loop, once a session has authenticated: logs the link coming up and resets the failure counts. */
    void authenticated(String node,java.util.Set<String> capabilities){
        if(announceUp){
            dev.iyanz.sourbycraft.util.SourbyLogger.info("AbyssLink: link up as node '"+node+"' to "+target
                +", capabilities "+new java.util.TreeSet<>(capabilities)
                +(failedAttempts>0?", after "+failedAttempts+" failed attempt"+(failedAttempts==1?"":"s"):""));
            announceUp=false;
        }
        failures=0;failedAttempts=0;
    }
    /**
     * On the event loop, when an authenticated session ends while the client still runs. A session that lasted at
     * least a minute is always logged; brief ones (a flapping link) on the first and then every Nth in a row.
     */
    void linkDown(String reason,long upNanos){
        boolean brief=upNanos<BRIEF_SESSION_NANOS;
        briefSessions=brief?briefSessions+1:0;
        if(brief && briefSessions!=1 && briefSessions%LOG_EVERY!=0)return;
        dev.iyanz.sourbycraft.util.SourbyLogger.warn("AbyssLink: link down after "+duration(upNanos)+": "+reason+"; reconnecting"
            +(!brief?"":briefSessions==1?" (links that drop within a minute again are logged every "+LOG_EVERY+")"
                :" ("+briefSessions+" links in a row dropped within a minute)"));
        announceUp=true;
    }
    static String duration(long nanos){
        long seconds=Math.max(0,TimeUnit.NANOSECONDS.toSeconds(nanos));
        if(seconds<120)return seconds+" s";
        if(seconds<7200)return seconds/60+" min";
        return seconds/3600+" h "+seconds%3600/60+" min";
    }
    /** On the event loop: records the failed stage for status and logs the first and every Nth failure. */
    private void attemptFailed(String stage,Throwable cause){
        attemptFailed(stage,cause==null?"no cause reported":AbyssLinkClient.describe(cause));
    }
    /** As above with a prepared, already sanitized detail (an authentication failure reported by the session). */
    void attemptFailed(String stage,String detail){
        client.failed(stage);
        failedAttempts++;
        if(failedAttempts==1 || failedAttempts%LOG_EVERY==0){
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AbyssLink: "+stage+(target.isEmpty()?"":" to "+target)
                +" failed (attempt "+failedAttempts+"): "+detail+"; retrying with backoff"
                +(failedAttempts==1?", logged again every "+LOG_EVERY+" failed attempts":""));
        }
    }
    /**
     * A TLS handshake failure in words. When the peer only closed the socket or never answered, the exception
     * ("SslHandler removed before handshake completed", a closed channel, a handshake timeout) does not say why, so
     * the likely causes are named: something other than the Leviathan AbyssLink listener at this address, or a proxy
     * that refused this source address or client certificate.
     */
    static String tlsFailure(Throwable cause){
        if(cause==null)return "no cause reported";
        Throwable root=cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null?cause.getCause():cause;
        String text=AbyssLinkClient.describe(root);
        String message=String.valueOf(root.getMessage());
        boolean closed=root instanceof java.nio.channels.ClosedChannelException
            || root.getCause() instanceof java.nio.channels.ClosedChannelException
            || message.contains("SslHandler removed before handshake completed")
            || message.contains("Connection closed while SSL/TLS handshake was in progress");
        if(closed)return "the peer closed the connection during the TLS handshake ("+text+"); the address may not be the"
            +" Leviathan AbyssLink listener, or the proxy refused this source address or client certificate";
        if(root instanceof SslHandshakeTimeoutException)return "no TLS answer in time ("+text+"); the address may not be"
            +" the Leviathan AbyssLink listener";
        return text;
    }
    private void prepare(){
        if(stopped)return;
        target="";
        var preparing=VirtualExecutor.supply(() -> {
            var settings=client.settings;
            SslContextBuilder builder=SslContextBuilder.forClient().sslProvider(SslProvider.JDK).protocols("TLSv1.3","TLSv1.2");
            if(!settings.trustCertificate().isEmpty())builder.trustManager(new File(settings.trustCertificate()));
            builder.keyManager(new File(settings.clientCertificate()),new File(settings.clientKey()));
            SslContext tls=builder.build();
            InetAddress address=InetAddress.getByName(settings.host());
            byte[] nonce=new byte[16];new java.security.SecureRandom().nextBytes(nonce);
            return new Prepared(tls,new InetSocketAddress(address,settings.port()),nonce);
        });
        preparation=preparing;
        if(stopped){preparing.cancel(true);return;}
        preparing.whenComplete((prepared,failure) -> {
            if(stopped)return;
            try {group.next().execute(() -> {
                if(stopped)return;
                if(failure!=null){attemptFailed("TLS/DNS setup",failure);scheduleRetry();return;}
                connect(prepared);
            });}catch(java.util.concurrent.RejectedExecutionException shuttingDown){ /* terminal */ }
        });
    }
    private record Prepared(SslContext tls,InetSocketAddress address,byte[] nonce){}
    private void connect(Prepared prepared){
        var settings=client.settings;
        String address=prepared.address.getAddress().getHostAddress();
        target=settings.host()+":"+settings.port()+(settings.host().equals(address)?"":" ("+address+")");
        new Bootstrap().group(group).channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,settings.timeoutMillis())
            .option(ChannelOption.TCP_NODELAY,true)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK,new WriteBufferWaterMark(32768,131072))
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel socket){
                    // This peerHost is the expected identity, independently of the network address.
                    SslHandler ssl=prepared.tls.newHandler(socket.alloc(),settings.identity(),settings.port(),dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
                    var parameters=ssl.engine().getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");ssl.engine().setSSLParameters(parameters);
                    ssl.setHandshakeTimeoutMillis(settings.timeoutMillis());
                    socket.pipeline().addLast("tls",ssl);
                    socket.pipeline().addLast("frames",new AbyssLinkFrameCodec(AbyssLinkFrame.MAX_PAYLOAD));
                    // Reject application bytes/errors before authentication; session is never installed early.
                    socket.pipeline().addLast("preauth-errors",new ChannelInboundHandlerAdapter(){
                        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable failure){
                            client.metrics.rejected.increment();
                            // Frame/TLS decode errors stop here, before the session handler; it still closes, with the reason logged.
                            if(ctx.pipeline().get("session") instanceof AbyssLinkSession session){session.failed("connection error: "+AbyssLinkClient.describe(failure));return;}
                            ctx.close();
                        }
                    });
                    ssl.handshakeFuture().addListener(handshake -> {
                        if(!handshake.isSuccess() && !stopped)attemptFailed("TLS handshake",tlsFailure(handshake.cause()));
                        if(!handshake.isSuccess() || stopped){socket.close();return;}
                        // Past every recorded stage: an earlier attempt's label would now be stale.
                        client.failed(null);
                        socket.pipeline().addLast("session",new AbyssLinkSession(client,prepared.nonce));
                    });
                }
            }).connect(prepared.address).addListener((ChannelFutureListener)connected -> {
                if(!connected.isSuccess()){if(!stopped)attemptFailed("connect",connected.cause());scheduleRetry();return;}
                channel=connected.channel();
                if(stopped){channel.close();return;}
                channel.closeFuture().addListener(closed -> {
                    scheduleRetry();
                });
            });
    }
    private void scheduleRetry(){
        if(stopped || retry!=null && !retry.isDone())return;
        client.metrics.reconnects.increment();
        long minimum=client.settings.reconnectMinMillis(),maximum=client.settings.reconnectMaxMillis();
        long cap=Math.min(maximum,minimum << failures);
        failures=Math.min(failures+1,16);
        long delay=ThreadLocalRandom.current().nextLong(minimum,Math.max(minimum+1,cap+1));
        retry=group.next().schedule(() -> {retry=null;prepare();},delay,TimeUnit.MILLISECONDS);
    }
    @Override public void close(){
        stopped=true;
        var preparing=preparation;if(preparing!=null)preparing.cancel(true);
        var scheduled=retry;if(scheduled!=null)scheduled.cancel(false);
        var open=channel;if(open!=null)open.close();
        group.shutdownGracefully(0,2,TimeUnit.SECONDS);
    }
}
