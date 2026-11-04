package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.SourbyAPI;
import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example plugin for {@code docs/guides/developing-with-sourbyapi.md}. It shows the SourbyAPI entry point and the
 * services behind it; it is a teaching fixture, not a feature. Read the classes in this order:
 * {@link WorldsDemo}, {@link ProfilesDemo}, {@link IronmanType}, {@link BankRetry}, {@link SchedulerDemo},
 * {@link EventsDemo}.
 *
 * <p>Threading: this plugin declares {@code folia-supported: true}, so a command runs on the region thread of the
 * sender (the global thread for the console) and must not touch another region's world, entity or player state. Every
 * future from SourbyAPI completes on a storage thread; the reply goes back through {@link AuroraScheduler}.</p>
 */
public final class SourbyApiExamplePlugin extends JavaPlugin {

    /** The first build that has {@code SourbyAPI.scheduler()}, the AWF events and the profile events. */
    static final int REQUIRED_BUILD = 55;

    private WorldsDemo worlds;
    private ProfilesDemo profiles;
    private SchedulerDemo schedulerDemo;

    @Override
    public void onEnable() {
        // 1. Is this SourbyCraft at all, and a new enough one? A jar compiled against sourbyapi still loads on plain Paper;
        //    the SourbyAPI class is then absent from the server, so keep the first touch of it behind a guard that the
        //    plugin can survive (for example catch NoClassDefFoundError in a real plugin that supports both servers).
        if (!SourbyAPI.isSourbyCraft()) {
            getLogger().warning("not a SourbyCraft server; disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!SourbyAPI.build().atLeast(REQUIRED_BUILD)) {
            getLogger().warning("needs SourbyCraft Build " + REQUIRED_BUILD + " or later, this is Build "
                + SourbyAPI.build().buildNumber() + "; disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("running on Build " + SourbyAPI.build().buildNumber() + " (" + SourbyAPI.build().codename() + ")");

        // 2. The scheduler facade is always present on SourbyCraft; the other services are optional.
        final AuroraScheduler scheduler = SourbyAPI.scheduler();
        this.schedulerDemo = new SchedulerDemo(this, scheduler);
        this.worlds = new WorldsDemo(this, scheduler);
        this.profiles = new ProfilesDemo(this, scheduler);

        // 3. Events and the profile type registration. The registry exists only while MultiProfile runs, which is off by
        //    default, so the registration waits for the service when it is not there yet.
        getServer().getPluginManager().registerEvents(new EventsDemo(this, scheduler), this);
        getServer().getPluginManager().registerEvents(new IronmanType.Registration(this), this);
        IronmanType.registerIfAvailable(this);

        final PluginCommand command = Objects.requireNonNull(getCommand("sbexample"), "sbexample is in plugin.yml");
        command.setExecutor(this);
        this.schedulerDemo.startHeartbeat();
    }

    @Override
    public void onDisable() {
        // Tasks of this plugin are cancelled by the server when it is disabled; a type registered here stays in force
        // as STORED (see PROFILE-TYPES.md), which is what a deny rule needs.
        getLogger().log(Level.INFO, "disabled");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "worlds" -> this.worlds.list(sender);
            case "instance" -> {
                if (args.length < 2) {
                    sender.sendMessage("usage: /sbexample instance <template>");
                } else {
                    this.worlds.createInstance(sender, args[1]);
                }
            }
            case "profile" -> this.profiles.describe(sender);
            case "types" -> this.profiles.describeTypes(sender);
            case "tick" -> this.schedulerDemo.tickDemo(sender);
            case "async" -> this.schedulerDemo.asyncDemo(sender);
            default -> sender.sendMessage("usage: /sbexample <worlds|instance <template>|profile|types|tick|async>");
        }
        return true;
    }
}
