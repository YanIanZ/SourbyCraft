package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/** Publishes {@link AuroraWorlds} and loads autoload worlds once the server has started. */
public final class AuroraWorldsRuntime {

    private static volatile AuroraWorldsService service;

    private AuroraWorldsRuntime() {}

    /** The running service, or {@code null} before startup or if the registry could not be read. */
    public static AuroraWorldsService service() {
        return service;
    }

    public static synchronized void start(final Plugin owner) throws IOException {
        if (service != null) return;
        final AuroraWorldsService created = new AuroraWorldsService(new AuroraWorldRegistry(AwfEngine.MANAGED_FILE));
        Bukkit.getServicesManager().register(AuroraWorlds.class, created, owner, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onLoad(final ServerLoadEvent event) {
                if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
                    created.scheduleAutoload();
                }
            }
        }, owner);
        service = created;
    }
}
