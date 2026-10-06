package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/**
 * /awf — operator front end for {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}.
 *
 * <pre>
 * /awf list
 * /awf info &lt;name&gt;
 * /awf create &lt;name&gt; [normal|nether|end] [seed] [autoload]
 * /awf load|save &lt;name&gt;
 * /awf unload &lt;name&gt; [nosave]
 * /awf delete &lt;name&gt; confirm
 * /awf autoload &lt;name&gt; on|off
 * </pre>
 *
 * <p>Every world operation is asynchronous; the result is sent when it completes.</p>
 */
public class AwfCommand extends Command {

    private static final List<String> SUBCOMMANDS =
        List.of("list", "info", "create", "load", "save", "unload", "delete", "autoload");

    public AwfCommand(final String name) {
        super(name);
        this.description = "Manage Aurora World Fabric worlds";
        this.usageMessage = "/awf <list|info|create|load|save|unload|delete|autoload>";
        this.setPermission("sourbycraft.command.awf");
    }

    @Override
    public boolean execute(final CommandSender s, final String alias, final String[] args) {
        if (!testPermission(s)) return true;
        final AuroraWorldsService worlds = AuroraWorldsRuntime.service();
        if (worlds == null) {
            s.sendMessage(text("Aurora World Fabric worlds are not available on this server", SourbyCraftColors.DANGER));
            return true;
        }
        if (args.length == 0 || !SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            usage(s);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            list(s, worlds);
            return true;
        }
        if (args.length < 2) {
            usage(s);
            return true;
        }
        final String name = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(s, worlds, name);
            case "create" -> create(s, worlds, name, args);
            case "load" -> report(s, "Loaded " + name, worlds.load(name));
            case "save" -> report(s, "Saved " + name + " (committed)", worlds.save(name));
            case "unload" -> {
                final boolean save = !(args.length > 2 && args[2].equalsIgnoreCase("nosave"));
                worlds.unload(name, save).whenComplete((result, failed) -> {
                    if (failed != null) fail(s, failed);
                    else if (result == dev.iyanz.sourbycraft.api.world.UnloadResult.SUCCESS) ok(s, "Unloaded " + name);
                    else s.sendMessage(text("Could not unload " + name + ": " + result, SourbyCraftColors.DANGER));
                });
            }
            case "delete" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    s.sendMessage(text("This permanently deletes " + name + ". Run /awf delete " + name
                        + " confirm", SourbyCraftColors.WARNING));
                } else {
                    report(s, "Deleted " + name, worlds.delete(name));
                }
            }
            case "autoload" -> {
                if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
                    usage(s);
                } else {
                    try {
                        worlds.setAutoload(name, args[2].equalsIgnoreCase("on"));
                        ok(s, name + " autoload " + args[2].toLowerCase(Locale.ROOT));
                    } catch (final RuntimeException failed) {
                        fail(s, failed);
                    }
                }
            }
            default -> usage(s);
        }
        return true;
    }

    private static void list(final CommandSender s, final AuroraWorldsService worlds) {
        s.sendMessage(UiPanel.header("Aurora World Fabric worlds"));
        if (worlds.list().isEmpty()) {
            s.sendMessage(UiPanel.hint("No AWF worlds yet. /awf create <name>"));
        }
        for (final String name : worlds.list()) {
            s.sendMessage(UiPanel.row(name, (worlds.isLoaded(name) ? "loaded" : "unloaded")
                + (worlds.autoload(name) ? ", autoload" : "")));
        }
        s.sendMessage(UiPanel.footer());
    }

    private static void info(final CommandSender s, final AuroraWorldsService worlds, final String name) {
        if (!worlds.exists(name)) {
            s.sendMessage(text("No AWF world named " + name, SourbyCraftColors.DANGER));
            return;
        }
        s.sendMessage(UiPanel.header("AWF world " + name));
        s.sendMessage(UiPanel.row("State", worlds.isLoaded(name) ? "loaded" : "unloaded"));
        s.sendMessage(UiPanel.row("Autoload", worlds.autoload(name) ? "on" : "off"));
        s.sendMessage(UiPanel.hint("Storage detail: /perf awf"));
        s.sendMessage(UiPanel.footer());
    }

    private static void create(final CommandSender s, final AuroraWorldsService worlds, final String name,
                               final String[] args) {
        final WorldCreator creator = new WorldCreator(name);
        boolean autoload = false;
        for (int i = 2; i < args.length; i++) {
            final String arg = args[i].toLowerCase(Locale.ROOT);
            switch (arg) {
                case "normal" -> creator.environment(World.Environment.NORMAL);
                case "nether" -> creator.environment(World.Environment.NETHER);
                case "end" -> creator.environment(World.Environment.THE_END);
                case "autoload" -> autoload = true;
                default -> {
                    try {
                        creator.seed(Long.parseLong(arg));
                    } catch (final NumberFormatException notSeed) {
                        s.sendMessage(text("Unknown option " + args[i], SourbyCraftColors.DANGER));
                        return;
                    }
                }
            }
        }
        report(s, "Created " + name + (autoload ? " (autoload)" : ""), worlds.create(creator, autoload));
    }

    private static void report(final CommandSender s, final String success, final CompletableFuture<?> future) {
        s.sendMessage(text("Working...", SourbyCraftColors.DIM));
        future.whenComplete((ignored, failed) -> {
            if (failed != null) fail(s, failed);
            else ok(s, success);
        });
    }

    private static void ok(final CommandSender s, final String message) {
        s.sendMessage(text(message, SourbyCraftColors.SUCCESS));
    }

    private static void fail(final CommandSender s, final Throwable failed) {
        final Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
        s.sendMessage(text("Failed: " + cause.getMessage(), SourbyCraftColors.DANGER));
    }

    private void usage(final CommandSender s) {
        s.sendMessage(UiPanel.header("Aurora World Fabric"));
        s.sendMessage(UiPanel.row("/awf list", "managed worlds"));
        s.sendMessage(UiPanel.row("/awf create <name> [normal|nether|end] [seed] [autoload]", "new AWF world"));
        s.sendMessage(UiPanel.row("/awf load | save <name>", "load, or save and commit"));
        s.sendMessage(UiPanel.row("/awf unload <name> [nosave]", "unload"));
        s.sendMessage(UiPanel.row("/awf delete <name> confirm", "delete permanently"));
        s.sendMessage(UiPanel.row("/awf autoload <name> on|off", "load at startup"));
        s.sendMessage(UiPanel.footer());
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        final AuroraWorldsService worlds = AuroraWorldsRuntime.service();
        if (args.length == 2 && worlds != null && !args[0].equalsIgnoreCase("create")) {
            return filter(new ArrayList<>(worlds.list()), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }
}
