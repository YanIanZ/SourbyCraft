package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.GeoUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /ping. Renders a coloured latency bar (0-500 ms maps to 100-0 %
 * — lower latency is higher fill) so the readout reads like the rest
 * of the SourbyCraft suite. Geo lookup keeps its async path.
 */
public class PingCommand extends Command {


    private final java.util.concurrent.Executor executor;
    private final java.util.function.Function<Player, java.util.function.Supplier<String>> geoSnapshot;
    private final java.util.function.BiConsumer<CommandSender, Runnable> reply;
    private final java.util.function.BiConsumer<Player, Runnable> targetHop;
    private final java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>>> resolver;
    private final boolean nativeOwnership;
    private record Ticket(long expiresNanos) {}
    private static final java.util.Map<CommandSender,Ticket> ACTIVE = new java.util.IdentityHashMap<>();
    private static synchronized Ticket acquire(CommandSender sender) {
        long now=System.nanoTime();
        ACTIVE.values().removeIf(ticket -> now-ticket.expiresNanos()>=0);
        if(ACTIVE.size()>=128 || ACTIVE.containsKey(sender))return null;
        Ticket ticket=new Ticket(now+java.util.concurrent.TimeUnit.SECONDS.toNanos(30));ACTIVE.put(sender,ticket);return ticket;
    }
    private static synchronized boolean active(CommandSender sender,Ticket ticket) {
        return ticket==null || ACTIVE.get(sender)==ticket && System.nanoTime()-ticket.expiresNanos()<0;
    }
    private static synchronized void release(CommandSender sender,Ticket ticket) { if(ticket!=null)ACTIVE.remove(sender,ticket); }


    public PingCommand(String name) {
        this(name, VirtualExecutor::run, player -> {
            final var address = player.getAddress();
            return () -> GeoUtil.lookup(address);
        }, SourbyReply::run, PingCommand::onTarget,
            dev.iyanz.sourbycraft.abysslink.AbyssLinkPingResolver::resolve, true);
    }

    /** Legacy test seam; production always snapshots immutable address data on the target owner. */
    PingCommand(String name, java.util.concurrent.Executor executor,
                java.util.function.Function<Player, String> geo,
                java.util.function.BiConsumer<CommandSender, Runnable> reply) {
        this(name, executor, player -> () -> geo.apply(player), reply, (player, task) -> task.run(),
            id -> java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty()), false);
    }

    PingCommand(String name, java.util.concurrent.Executor executor,
                java.util.function.Function<Player, java.util.function.Supplier<String>> geoSnapshot,
                java.util.function.BiConsumer<CommandSender, Runnable> reply,
                java.util.function.BiConsumer<Player, Runnable> targetHop,
                java.util.function.Function<java.util.UUID, java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>>> resolver,
                boolean nativeOwnership) {
        super(name);
        this.executor=executor;this.geoSnapshot=geoSnapshot;this.reply=reply;this.targetHop=targetHop;
        this.resolver=resolver;this.nativeOwnership=nativeOwnership;
        this.description="Player latency";this.usageMessage="/ping [player]";
    }

    private record TargetTask(Runnable action,Runnable retired) implements Runnable {
        @Override public void run(){action.run();}
    }

    private static void onTarget(Player player, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(player)) { task.run(); return; }
        Runnable retired=() -> {if(task instanceof TargetTask owned)owned.retired().run();};
        var accepted=player.getScheduler().run(dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin.INSTANCE,
            scheduled -> task.run(), retired);
        if(accepted==null)retired.run();
    }

    private record Snapshot(java.util.UUID id, String name, int backendPing, String brand, int protocol,
                            long token, java.util.function.Supplier<String> geo) {}

    @Override
    public boolean execute(CommandSender sender, String alias, String[] args) {
        boolean other=args.length>0 && !args[0].equalsIgnoreCase(sender.getName());
        if (other && !sender.hasPermission("sourbycraft.command.ping.other")) {
            sender.sendMessage(text("No permission", SourbyCraftColors.DANGER));return true;
        }
        Player target=args.length>0?Bukkit.getPlayer(args[0]):sender instanceof Player player?player:null;
        if(target==null){sender.sendMessage(text("Player not found", SourbyCraftColors.DANGER));return true;}
        final Ticket ticket=nativeOwnership?acquire(sender):null;
        if(nativeOwnership && ticket==null){sender.sendMessage(text("Ping request already pending or at capacity",SourbyCraftColors.DIM));return true;}
        // Read sender state before leaving its owner; no Player is carried into network/GeoIP work.
        final java.util.UUID senderId=sender instanceof Player player?player.getUniqueId():null;
        final long senderToken=senderId==null?0:dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.playerToken(senderId);
        try {
            targetHop.accept(target, new TargetTask(() -> {
                if(nativeOwnership && !target.isOnline()){release(sender,ticket);return;}
                String brand=target.getClientBrandName();
                final var snapshot=new Snapshot(target.getUniqueId(),target.getName(),target.getPing(),
                    brand==null?"vanilla":brand,target.getProtocolVersion(),
                    nativeOwnership?dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.playerToken(target.getUniqueId()):0,
                    geoSnapshot.apply(target));
                java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>> future;
                try { future=resolver.apply(snapshot.id()); }
                catch (RuntimeException failed) { future=java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty()); }
                future.whenComplete((sample,failure) -> {
                    Runnable render=() -> {
                        if(!active(sender,ticket) || !current(snapshot,senderId,senderToken)){release(sender,ticket);return;}
                        final var resolved=failure==null && sample!=null?sample.filter(value -> !nativeOwnership || dev.iyanz.sourbycraft.abysslink.AbyssLinkPingResolver.current(value)):java.util.Optional.<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>empty();
                        render(sender,snapshot,resolved);
                        lookupLocation(sender,snapshot,senderId,senderToken,ticket);
                    };
                    // The test seam models an inline snapshot; native rendering always belongs to the sender.
                    if(nativeOwnership)reply.accept(sender,render);else render.run();
                });
            },() -> release(sender,ticket)));
        }catch(java.util.concurrent.RejectedExecutionException retired){release(sender,ticket); /* no unsafe fallback read */ }
        return true;
    }

    private boolean current(Snapshot snapshot, java.util.UUID senderId,long senderToken) {
        return !nativeOwnership || dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.current(snapshot.id(),snapshot.token())
            && (senderId==null || dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.current(senderId,senderToken));
    }

    private static void render(CommandSender sender, Snapshot target,
                               java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample> proxy) {
        int ping=proxy.map(dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample::latencyMillis).orElse(target.backendPing());
        TextColor color=ping<50?SourbyCraftColors.SUCCESS:ping<100?SourbyCraftColors.PRIMARY:ping<200?SourbyCraftColors.WARNING:SourbyCraftColors.DANGER;
        double percent=Math.max(0.0,100.0-Math.min(ping,500)/5.0);
        sender.sendMessage(UiPanel.header("Connection / "+target.name()));
        sender.sendMessage(text(proxy.isPresent()?"  Client → Leviathan ping: ":"  Backend latency: ",SourbyCraftColors.LABEL)
            .append(text(BarUtil.bar(percent,BarUtil.DEFAULT_WIDTH)+"  "+ping+"ms",color)));
        if(proxy.isPresent())sender.sendMessage(text("  Backend measurement: "+target.backendPing()+"ms",SourbyCraftColors.DIM));
        sender.sendMessage(UiPanel.hint("Gauge is full at 0ms and empty at 500ms or more."));
        sender.sendMessage(text("  AbyssLink: "+dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.status(),SourbyCraftColors.DIM));
        if(proxy.isPresent())sender.sendMessage(text("  Proxy: Leviathan (authenticated control peer)",SourbyCraftColors.DIM));
        sender.sendMessage(text("  Client: ",SourbyCraftColors.LABEL).append(text(target.brand(),SourbyCraftColors.VALUE))
            .append(text("  (protocol "+target.protocol()+")",SourbyCraftColors.DIM)));
        sender.sendMessage(text("  Location: ",SourbyCraftColors.LABEL).append(text("looking up...",SourbyCraftColors.DIM)));
    }

    private void lookupLocation(CommandSender sender,Snapshot snapshot,java.util.UUID senderId,long senderToken,Ticket ticket) {
        try {
            executor.execute(() -> {
                String location;
                try { location=snapshot.geo().get(); }catch(Throwable failed){location=null;}
                final String result=location;
                reply.accept(sender,() -> {try {if(active(sender,ticket) && current(snapshot,senderId,senderToken))sendLocation(sender,result);}finally{release(sender,ticket);}});
            });
        }catch(java.util.concurrent.RejectedExecutionException rejected){try{sendLocation(sender,null);}finally{release(sender,ticket);}}
    }

    private static void sendLocation(CommandSender s, String geo) {
        if (geo != null && !geo.isEmpty()) {
            s.sendMessage(text()
                .append(text("  Location: ", SourbyCraftColors.LABEL))
                .append(text(geo, SourbyCraftColors.VALUE))
                .build());
        } else {
            s.sendMessage(text()
                .append(text("  Location: ", SourbyCraftColors.LABEL))
                .append(text("unavailable", SourbyCraftColors.DIM))
                .append(text("  (proxy-stripped IP, RFC1918, or local geo db unavailable)", SourbyCraftColors.DIM))
                .build());
        }
    }
}
