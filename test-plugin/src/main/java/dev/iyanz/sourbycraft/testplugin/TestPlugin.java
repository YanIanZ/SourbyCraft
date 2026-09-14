package dev.iyanz.sourbycraft.testplugin;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
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
        final StressCommands stress = new StressCommands(this);
        for (final String name : new String[]{"massspawn", "flyspeed"}) {
            final var command = this.getCommand(name);
            if (command == null) {
                throw new IllegalStateException("command " + name + " missing from paper-plugin.yml");
            }
            command.setExecutor(stress);
            command.setTabCompleter(stress);
        }
        this.getLogger().info("SOURBY_STRESS_COMMANDS_OK");
    }

    private void requireMetrics(final String marker) {
        final SourbyMetrics metrics = this.getServer().getServicesManager().load(SourbyMetrics.class);
        if (metrics == null || metrics.snapshot() == null) {
            throw new IllegalStateException("SourbyMetrics service unavailable");
        }
        this.getLogger().info(marker);
    }
}
