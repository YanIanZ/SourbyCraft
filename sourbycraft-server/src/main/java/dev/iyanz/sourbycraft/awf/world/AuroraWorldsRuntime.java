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

/**
 * Publishes {@link AuroraWorlds} and loads autoload worlds once the server has started. Since Build 55 it also
 * registers the {@link dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler} facade, first, so the scheduler is
 * published even when the AWF world registry cannot be read.
 */
public final class AuroraWorldsRuntime {

    private static volatile AuroraWorldsService service;

    private AuroraWorldsRuntime() {}

    /** The running service, or {@code null} before startup or if the registry could not be read. */
    public static AuroraWorldsService service() {
        return service;
    }

    /** Reports world operations stuck for minutes; see {@link AuroraWorldsService#watchOperations}. */
    public static void watchOperations() {
        final AuroraWorldsService current = service;
        if (current != null) current.watchOperations();
    }

    public static synchronized void start(final Plugin owner) throws IOException {
        if (service != null) return;
        // Build 54: SourbyAPI caches service lookups only while it hears of (un)registrations. A failure
        // here must not keep AuroraWorlds from starting: SourbyAPI then simply does not cache.
        try {
            dev.iyanz.sourbycraft.api.SourbyAPI.bindServiceEvents(owner);
        } catch (final RuntimeException failed) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("SourbyAPI service lookups stay uncached: " + failed.getMessage());
        }
        // Build 55: the AuroraScheduler facade (SourbyAPI.scheduler()). Before the AWF registry is read, so a
        // world registry that cannot be read does not take the scheduler with it; it needs no setting.
        try {
            dev.iyanz.sourbycraft.scheduler.AuroraSchedulerService.register(owner);
        } catch (final RuntimeException failed) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("The AuroraScheduler service could not be registered"
                + " (SourbyAPI.scheduler() will throw): " + failed.getMessage());
        }
        final AuroraWorldsService created = new AuroraWorldsService(new AuroraWorldRegistry(AwfEngine.MANAGED_FILE),
            new AuroraTemplates(AwfEngine.TEMPLATES));
        Bukkit.getServicesManager().register(AuroraWorlds.class, created, owner, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onLoad(final ServerLoadEvent event) {
                if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
                    created.scheduleAutoload();
                }
            }
        }, owner);
        Bukkit.getPluginManager().registerEvents(created, owner);
        service = created;
    }
}
