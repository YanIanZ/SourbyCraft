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
    AbyssLinkConnectionManager(AbyssLinkClient client){this.client=client;}
    void start(){prepare();}
    void authenticated(){failures=0;}
    private void prepare(){
        if(stopped)return;
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
                if(failure!=null){scheduleRetry();return;}
                connect(prepared);
            });}catch(java.util.concurrent.RejectedExecutionException shuttingDown){ /* terminal */ }
        });
    }
    private record Prepared(SslContext tls,InetSocketAddress address,byte[] nonce){}
    private void connect(Prepared prepared){
        var settings=client.settings;
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
                        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable failure){client.metrics.rejected.increment();ctx.close();}
                    });
                    ssl.handshakeFuture().addListener(handshake -> {
                        if(!handshake.isSuccess() || stopped){socket.close();return;}
                        socket.pipeline().addLast("session",new AbyssLinkSession(client,prepared.nonce));
                    });
                }
            }).connect(prepared.address).addListener((ChannelFutureListener)connected -> {
                if(!connected.isSuccess()){scheduleRetry();return;}
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
