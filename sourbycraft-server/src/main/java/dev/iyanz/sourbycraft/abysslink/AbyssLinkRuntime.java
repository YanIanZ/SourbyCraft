package dev.iyanz.sourbycraft.abysslink;

import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bootstrap-owned optional service. Availability/authentication never changes gameplay forwarding. */
public final class AbyssLinkRuntime implements Listener {
    private static volatile AbyssLinkClient client;
    private static io.papermc.paper.threadedregions.scheduler.ScheduledTask telemetry;
    private static final ConcurrentHashMap<UUID,Long> players=new ConcurrentHashMap<>();
    private static final AtomicLong generation=new AtomicLong();
    private static AbyssLinkRuntime listener;
    private AbyssLinkRuntime(){}
    public static AbyssLinkClient client(){return client;}
    public static String status(){var current=client;return current==null?"disabled":current.status();}
    /** Token checked without any Bukkit access by delayed replies. */
    public static long playerToken(UUID player){return players.getOrDefault(player,0L);}
    public static boolean current(UUID player,long token){return token!=0 && players.getOrDefault(player,0L)==token;}
    public static synchronized void start(){
        if(listener!=null)return;
        listener=new AbyssLinkRuntime();
        Bukkit.getPluginManager().registerEvents(listener,MinecraftInternalPlugin.INSTANCE);
        try {
            var settings=AbyssLinkSettings.load(AbyssLinkSettings.FILE);
            client=new AbyssLinkClient(settings);
            if(!settings.enabled())return;
            client.start();
            var collector=new AbyssLinkTelemetry(client);
            long interval=Math.max(20,settings.telemetryMillis()/50);
            telemetry=Bukkit.getGlobalRegionScheduler().runAtFixedRate(MinecraftInternalPlugin.INSTANCE,
                task -> collector.sample(Bukkit.getOnlinePlayers().size()),interval,interval);
            SourbyLogger.info("AbyssLink: enabled; connecting asynchronously with verified mTLS");
        }catch(Exception invalid){SourbyLogger.warn("AbyssLink: unavailable (configuration or TLS setup); gameplay remains available");}
    }
    @EventHandler public void joined(PlayerJoinEvent event){players.put(event.getPlayer().getUniqueId(),generation.incrementAndGet());}
    @EventHandler public void quit(PlayerQuitEvent event){players.remove(event.getPlayer().getUniqueId());}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event){players.computeIfPresent(event.getPlayer().getUniqueId(),(id,old)->generation.incrementAndGet());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void teleported(PlayerTeleportEvent event){players.computeIfPresent(event.getPlayer().getUniqueId(),(id,old)->generation.incrementAndGet());}
    public static synchronized void close(){
        if(telemetry!=null){telemetry.cancel();telemetry=null;}
        var current=client;client=null;if(current!=null)current.close();
        if(listener!=null){HandlerList.unregisterAll(listener);listener=null;}
        players.clear();
    }
}
