package dev.iyanz.sourbycraft.testplugin;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {

    @Override
    public void onLoad() {
        this.requireMetrics("SOURBY_METRICS_ONLOAD_OK");
    }

    @Override
    public void onEnable() {
        this.requireMetrics("SOURBY_METRICS_ONENABLE_OK");
        Bukkit.getPluginManager().registerEvents(this, this);
        // A Paper plugin must register commands through the lifecycle COMMANDS event.
        // JavaPlugin#getCommand throws UnsupportedOperationException during startup here.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();
            registrar.register("massspawn", "Spawn many entities at a position, batched across ticks.",
                new StressCommands(this, "massspawn"));
            registrar.register("flyspeed", "Set a player's fly speed for extreme-range testing.",
                new StressCommands(this, "flyspeed"));
            this.getLogger().info("SOURBY_STRESS_COMMANDS_OK");
        });
    }

    private void requireMetrics(final String marker) {
        final SourbyMetrics metrics = this.getServer().getServicesManager().load(SourbyMetrics.class);
        if (metrics == null || metrics.snapshot() == null) {
            throw new IllegalStateException("SourbyMetrics service unavailable");
        }
        this.getLogger().info(marker);
    }
}
