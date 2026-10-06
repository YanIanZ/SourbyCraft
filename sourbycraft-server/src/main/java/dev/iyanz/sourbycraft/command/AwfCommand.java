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
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/**
 * /awf — operator front end for {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}.
 *
 * <pre>
 * /awf list
 * /awf info &lt;name&gt;
 * /awf create &lt;name&gt; [normal|nether|end] [void|flat|amplified|large_biomes] [generator=Plugin[:id]] [seed] [autoload]
 * /awf create &lt;name&gt; from &lt;template&gt; [autoload]
 * /awf import &lt;file.awf&gt; &lt;name&gt; [autoload]
 * /awf import &lt;file.slime&gt; &lt;name&gt; [normal|nether|end] [vanilla|generator=Plugin[:id]] [autoload]
 * /awf export &lt;world&gt; &lt;file.awf&gt;
 * /awf convert &lt;file.slime&gt; &lt;file.awf&gt; [normal|nether|end]
 * /awf template list
 * /awf template save &lt;world&gt; &lt;template&gt;
 * /awf template delete &lt;template&gt; confirm
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
        List.of("list", "info", "create", "load", "save", "unload", "delete", "autoload", "template", "import", "export", "convert");

    public AwfCommand(final String name) {
        super(name);
        this.description = "Manage Aurora World Fabric worlds";
        this.usageMessage = "/awf <list|info|create|load|save|unload|delete|autoload|template|import|export|convert>";
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
        if (sub.equals("template")) {
            template(s, worlds, args);
            return true;
        }
        if (sub.equals("import")) {
            importFile(s, worlds, args);
            return true;
        }
        if (sub.equals("convert")) {
            convert(s, worlds, args);
            return true;
        }
        if (sub.equals("export")) {
            if (args.length < 3) {
                usage(s);
            } else {
                final java.nio.file.Path file = withSuffix(args[2]);
                report(s, "Exported " + args[1].toLowerCase(Locale.ROOT) + " to " + file,
                    worlds.exportWorld(args[1].toLowerCase(Locale.ROOT), file));
            }
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
        s.sendMessage(UiPanel.hint("Storage: " + worlds.storage()));
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
        s.sendMessage(UiPanel.row("Template", worlds.template(name).orElse("none")));
        s.sendMessage(UiPanel.row("Storage", worlds.storage()));
        s.sendMessage(UiPanel.hint("Storage detail: /perf awf"));
        s.sendMessage(UiPanel.footer());
    }

    private static void create(final CommandSender s, final AuroraWorldsService worlds, final String name,
                               final String[] args) {
        if (args.length >= 4 && args[2].equalsIgnoreCase("from")) {
            final boolean autoload = args.length > 4 && args[4].equalsIgnoreCase("autoload");
            final String template = args[3].toLowerCase(Locale.ROOT);
            report(s, "Created " + name + " from template " + template + (autoload ? " (autoload)" : ""),
                worlds.createFromTemplate(template, name, autoload));
            return;
        }
        final WorldCreator creator = new WorldCreator(name);
        boolean autoload = false;
        String generator = null;
        for (int i = 2; i < args.length; i++) {
            final String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.startsWith("generator=")) {
                // Plugin names are case sensitive.
                generator = args[i].substring("generator=".length());
                continue;
            }
            switch (arg) {
                case "normal" -> creator.environment(World.Environment.NORMAL);
                case "nether" -> creator.environment(World.Environment.NETHER);
                case "end" -> creator.environment(World.Environment.THE_END);
                case "void" -> generator = "void";
                case "flat" -> creator.type(WorldType.FLAT);
                case "amplified" -> creator.type(WorldType.AMPLIFIED);
                case "large_biomes" -> creator.type(WorldType.LARGE_BIOMES);
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
        report(s, "Created " + name + (autoload ? " (autoload)" : ""), worlds.create(creator, generator, autoload));
    }

    private static java.nio.file.Path withSuffix(final String path) {
        return java.nio.file.Path.of(path.endsWith(dev.iyanz.sourbycraft.awf.AwfWorldFile.SUFFIX) ? path
            : path + dev.iyanz.sourbycraft.awf.AwfWorldFile.SUFFIX);
    }

    /** Imports an .awf world file, or a Slime file, by what the file starts with. */
    private void importFile(final CommandSender s, final AuroraWorldsService worlds, final String[] args) {
        if (args.length < 3) {
            usage(s);
            return;
        }
        final java.nio.file.Path file = java.nio.file.Path.of(args[1]);
        final String name = args[2].toLowerCase(Locale.ROOT);
        byte[] head = new byte[0];
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            head = in.readNBytes(4);
        } catch (final java.io.IOException unreadable) {
            // importSlime reports the missing file.
        }
        if (dev.iyanz.sourbycraft.awf.AwfWorldFile.looksLike(head)) {
            final boolean autoload = args.length > 3 && args[3].equalsIgnoreCase("autoload");
            report(s, "Imported " + file.getFileName() + " as " + name + (autoload ? " (autoload)" : ""),
                worlds.importWorld(file, name, autoload));
            return;
        }
        importSlime(s, worlds, file, name, args);
    }

    private void convert(final CommandSender s, final AuroraWorldsService worlds, final String[] args) {
        if (args.length < 3) {
            usage(s);
            return;
        }
        World.Environment environment = World.Environment.NORMAL;
        if (args.length > 3) {
            switch (args[3].toLowerCase(Locale.ROOT)) {
                case "normal" -> environment = World.Environment.NORMAL;
                case "nether" -> environment = World.Environment.NETHER;
                case "end" -> environment = World.Environment.THE_END;
                default -> {
                    s.sendMessage(text("Unknown environment " + args[3], SourbyCraftColors.DANGER));
                    return;
                }
            }
        }
        final java.nio.file.Path target = withSuffix(args[2]);
        report(s, "Converted " + args[1] + " to " + target, worlds.convertSlime(java.nio.file.Path.of(args[1]), target, environment));
    }

    /** Imports a Slime file; chunks it does not hold are void unless told otherwise. */
    private void importSlime(final CommandSender s, final AuroraWorldsService worlds, final java.nio.file.Path file,
                             final String name, final String[] args) {
        final WorldCreator creator = new WorldCreator(name);
        String generator = "void";
        boolean autoload = false;
        for (int i = 3; i < args.length; i++) {
            final String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.startsWith("generator=")) {
                generator = args[i].substring("generator=".length());
                continue;
            }
            switch (arg) {
                case "normal" -> creator.environment(World.Environment.NORMAL);
                case "nether" -> creator.environment(World.Environment.NETHER);
                case "end" -> creator.environment(World.Environment.THE_END);
                case "vanilla" -> generator = null;
                case "autoload" -> autoload = true;
                default -> {
                    s.sendMessage(text("Unknown option " + args[i], SourbyCraftColors.DANGER));
                    return;
                }
            }
        }
        report(s, "Imported " + file.getFileName() + " as " + name + (autoload ? " (autoload)" : ""),
            worlds.importSlime(file, creator, generator, autoload));
    }

    private void template(final CommandSender s, final AuroraWorldsService worlds, final String[] args) {
        final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                s.sendMessage(UiPanel.header("AWF templates"));
                final var names = worlds.templates();
                if (names.isEmpty()) s.sendMessage(UiPanel.hint("No templates yet. /awf template save <world> <template>"));
                for (final String template : names) {
                    final long instances = worlds.list().stream()
                        .filter(world -> worlds.template(world).filter(template::equals).isPresent()).count();
                    s.sendMessage(UiPanel.row(template, instances + (instances == 1 ? " instance" : " instances")));
                }
                s.sendMessage(UiPanel.footer());
            }
            case "save" -> {
                if (args.length < 4) {
                    usage(s);
                    return;
                }
                final String world = args[2].toLowerCase(Locale.ROOT);
                final String template = args[3].toLowerCase(Locale.ROOT);
                report(s, "Saved " + world + " as template " + template, worlds.saveTemplate(world, template));
            }
            case "delete" -> {
                if (args.length < 3) {
                    usage(s);
                } else if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    s.sendMessage(text("This permanently deletes template " + args[2] + ". Run /awf template delete "
                        + args[2] + " confirm", SourbyCraftColors.WARNING));
                } else {
                    final String template = args[2].toLowerCase(Locale.ROOT);
                    report(s, "Deleted template " + template, worlds.deleteTemplate(template));
                }
            }
            default -> usage(s);
        }
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
        s.sendMessage(UiPanel.row("/awf create <name> [normal|nether|end] [void|flat|amplified|large_biomes]"
            + " [generator=Plugin[:id]] [seed] [autoload]", "new AWF world"));
        s.sendMessage(UiPanel.row("/awf create <name> from <template> [autoload]", "copy-on-write instance"));
        s.sendMessage(UiPanel.row("/awf import <file.awf> <name> [autoload]", "new world from an .awf file"));
        s.sendMessage(UiPanel.row("/awf import <file.slime> <name> [normal|nether|end] [vanilla|generator=Plugin[:id]]"
            + " [autoload]", "new world from a Slime file"));
        s.sendMessage(UiPanel.row("/awf export <world> <file.awf>", "unloaded world to one .awf file"));
        s.sendMessage(UiPanel.row("/awf convert <file.slime> <file.awf> [normal|nether|end]", "Slime file to .awf"));
        s.sendMessage(UiPanel.row("/awf template list | save <world> <template> | delete <template> confirm", "templates"));
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
        if (worlds == null) return List.of();
        if (args[0].equalsIgnoreCase("template")) {
            if (args.length == 2) return filter(List.of("list", "save", "delete"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("save")) return filter(new ArrayList<>(worlds.list()), args[2]);
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) return filter(new ArrayList<>(worlds.templates()), args[2]);
            return List.of();
        }
        if (args[0].equalsIgnoreCase("create")) {
            if (args.length == 4 && args[2].equalsIgnoreCase("from")) return filter(new ArrayList<>(worlds.templates()), args[3]);
            if (args.length >= 3) {
                return filter(List.of("from", "normal", "nether", "end", "void", "flat", "amplified", "large_biomes",
                    "generator=", "autoload"), args[args.length - 1]);
            }
            return List.of();
        }
        if (args.length == 2) {
            return filter(new ArrayList<>(worlds.list()), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }
}
