package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldFiles;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldsService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
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
 * /awf import &lt;file.awf&gt; &lt;name&gt; [autoload]                 (file in awf/imports)
 * /awf import &lt;file.slime&gt; &lt;name&gt; [normal|nether|end] [vanilla|generator=Plugin[:id]] [autoload]
 * /awf export &lt;world&gt; &lt;file.awf&gt;                            (file written to awf/exports)
 * /awf convert &lt;file.slime&gt; &lt;file.awf&gt; [normal|nether|end]   (awf/imports to awf/exports)
 * /awf template list
 * /awf template save &lt;world&gt; &lt;template&gt;
 * /awf template delete &lt;template&gt; confirm
 * /awf load|save &lt;name&gt;
 * /awf unload &lt;name&gt; [nosave]
 * /awf delete &lt;name&gt; confirm
 * /awf autoload &lt;name&gt; on|off
 * /awf compact &lt;world|all&gt;      (force every open store of the world into a full index; prints full: yes/no per store)
 * </pre>
 *
 * <p>Every world operation is asynchronous; the result is sent when it completes, through
 * {@link SourbyReply#run} so a command block gets it on the thread that owns it.</p>
 *
 * <p>File arguments are names inside {@code awf/imports} (read) and {@code awf/exports} (written)
 * under the server directory, see {@link AuroraWorldFiles#confine}: an operator with the command
 * permission can move worlds, not read or write arbitrary files on the host.</p>
 */
public class AwfCommand extends Command {

    private static final List<String> SUBCOMMANDS =
        List.of("list", "info", "create", "load", "save", "unload", "delete", "autoload", "template", "import", "export",
            "convert", "clone", "set", "compact", "multiprofile", "redis");

    private final Supplier<AuroraWorldsService> service;
    private final BiConsumer<CommandSender, Runnable> reply;
    /** {@code /awf compact}: world name or "all" to the per-storage reports, run by the engine on its storage lane. */
    private final java.util.function.Function<String, CompletableFuture<List<dev.iyanz.sourbycraft.awf.AwfEngine.CompactionReport>>> compactor;

    public AwfCommand(final String name) {
        this(name, AuroraWorldsRuntime::service, SourbyReply::run);
    }

    /** The service and the reply hop injectable, so the command's own rules can be tested without a server. */
    AwfCommand(final String name, final Supplier<AuroraWorldsService> service, final BiConsumer<CommandSender, Runnable> reply) {
        this(name, service, reply, dev.iyanz.sourbycraft.awf.AwfEngine::compact);
    }

    /** As above, with the compaction injectable too. */
    AwfCommand(final String name, final Supplier<AuroraWorldsService> service, final BiConsumer<CommandSender, Runnable> reply,
               final java.util.function.Function<String, CompletableFuture<List<dev.iyanz.sourbycraft.awf.AwfEngine.CompactionReport>>> compactor) {
        super(name);
        this.description = "Manage Aurora World Fabric worlds";
        this.usageMessage = "/awf <list|info|create|load|save|unload|delete|autoload|template|import|export|convert"
            + "|compact|multiprofile|redis>";
        this.setPermission("sourbycraft.command.awf");
        this.service = service;
        this.reply = reply;
        this.compactor = compactor;
    }

    @Override
    public boolean execute(final CommandSender s, final String alias, final String[] args) {
        if (!testPermission(s)) return true;
        if (args.length > 0) {
            final String early = args[0].toLowerCase(Locale.ROOT);
            // These do not need the world service: the fabric can be diagnosed even if AWF worlds failed.
            if (early.equals("multiprofile")) {
                multiprofile(s, args);
                return true;
            }
            if (early.equals("redis")) {
                redis(s, args);
                return true;
            }
        }
        final AuroraWorldsService worlds = this.service.get();
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
        if (sub.equals("compact")) {
            if (args.length < 2) {
                usage(s);
            } else {
                compact(s, args[1]);
            }
            return true;
        }
        if (sub.equals("export")) {
            if (args.length < 3) {
                usage(s);
            } else {
                final Path file = exportTarget(s, args[2]);
                if (file != null) {
                    report(s, "Exported " + args[1].toLowerCase(Locale.ROOT) + " to " + file,
                        worlds.exportWorld(args[1].toLowerCase(Locale.ROOT), file));
                }
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
                worlds.unload(name, save).whenComplete((result, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) fail(s, failed);
                    else if (result == dev.iyanz.sourbycraft.api.world.UnloadResult.SUCCESS) ok(s, "Unloaded " + name);
                    else s.sendMessage(text("Could not unload " + name + ": " + result, SourbyCraftColors.DANGER));
                }));
            }
            case "delete" -> {
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    s.sendMessage(text("This permanently deletes " + name + ". Run /awf delete " + name
                        + " confirm", SourbyCraftColors.WARNING));
                } else {
                    report(s, "Deleted " + name, worlds.delete(name));
                }
            }
            case "clone" -> {
                if (args.length < 3) {
                    usage(s);
                } else {
                    final String target = args[2].toLowerCase(Locale.ROOT);
                    final boolean autoload = args.length > 3 && args[3].equalsIgnoreCase("autoload");
                    report(s, "Cloned " + name + " to " + target, worlds.cloneWorld(name, target, autoload));
                }
            }
            case "set" -> setProperty(s, worlds, name, args);
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

    private static String orDefault(final Object value) {
        return value == null ? "default" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static Boolean onOff(final String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true" -> Boolean.TRUE;
            case "off", "false" -> Boolean.FALSE;
            case "none", "default" -> null;
            default -> throw new IllegalArgumentException("expected on, off or none, not " + value);
        };
    }

    /** /awf set &lt;world&gt; &lt;key&gt; &lt;value...&gt; — one property at a time; "none" resets it. */
    private void setProperty(final CommandSender s, final AuroraWorldsService worlds, final String name, final String[] args) {
        if (args.length < 4) {
            usage(s);
            return;
        }
        final String key = args[2].toLowerCase(Locale.ROOT);
        final boolean none = args[3].equalsIgnoreCase("none") || args[3].equalsIgnoreCase("default");
        final dev.iyanz.sourbycraft.api.world.WorldProperties next;
        try {
            final dev.iyanz.sourbycraft.api.world.WorldProperties p = worlds.properties(name);
            next = switch (key) {
                case "spawn" -> p.withSpawn(none ? null : new dev.iyanz.sourbycraft.api.world.WorldProperties.Spawn(
                    Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]),
                    args.length > 6 ? Float.parseFloat(args[6]) : 0f));
                case "difficulty" -> p.withDifficulty(none ? null : org.bukkit.Difficulty.valueOf(args[3].toUpperCase(Locale.ROOT)));
                case "pvp" -> p.withPvp(onOff(args[3]));
                case "monsters" -> p.withSpawning(onOff(args[3]), p.allowAnimals());
                case "animals" -> p.withSpawning(p.allowMonsters(), onOff(args[3]));
                case "biome" -> p.withDefaultBiome(none ? null : args[3].toLowerCase(Locale.ROOT));
                case "bounds" -> p.withSaveBounds(none ? null : new dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds(
                    Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6])));
                case "prune" -> p.withPruneEmptyChunks(onOff(args[3]));
                default -> throw new IllegalArgumentException("unknown property " + key);
            };
        } catch (final RuntimeException bad) {
            fail(s, bad instanceof ArrayIndexOutOfBoundsException ? new IllegalArgumentException("missing values for " + key) : bad);
            return;
        }
        report(s, "Set " + key + " of " + name, worlds.setProperties(name, next));
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
        final dev.iyanz.sourbycraft.api.world.WorldProperties p = worlds.properties(name);
        s.sendMessage(UiPanel.row("Spawn", p.spawn() == null ? "default" : p.spawn().x() + ", " + p.spawn().y() + ", "
            + p.spawn().z() + " yaw " + p.spawn().yaw()));
        s.sendMessage(UiPanel.row("Difficulty / PvP", orDefault(p.difficulty()) + " / " + orDefault(p.pvp())));
        s.sendMessage(UiPanel.row("Monsters / animals", orDefault(p.allowMonsters()) + " / " + orDefault(p.allowAnimals())));
        s.sendMessage(UiPanel.row("Void biome", orDefault(p.defaultBiome())));
        s.sendMessage(UiPanel.row("Save bounds", p.saveBounds() == null ? "none" : p.saveBounds().minChunkX() + ","
            + p.saveBounds().minChunkZ() + " to " + p.saveBounds().maxChunkX() + "," + p.saveBounds().maxChunkZ() + " (chunks)"));
        s.sendMessage(UiPanel.row("Prune empty chunks", p.pruneEmptyChunks() == null ? "default" : (p.pruneEmptyChunks() ? "on" : "off")));
        for (final dev.iyanz.sourbycraft.awf.AwfRegionStorage storage : worlds.storages(name)) {
            s.sendMessage(UiPanel.row("Stored " + java.nio.file.Path.of(storage.name()).getFileName(),
                storage.storedChunks() + " chunks; since load " + storage.pruned() + " empty pruned, "
                    + storage.outOfBounds() + " outside bounds"));
        }
        s.sendMessage(UiPanel.hint("Change: /awf set " + name + " <spawn|difficulty|pvp|monsters|animals|biome|bounds|prune> <value|none>"));
        s.sendMessage(UiPanel.hint("Storage detail: /perf awf"));
        s.sendMessage(UiPanel.footer());
    }

    private void create(final CommandSender s, final AuroraWorldsService worlds, final String name,
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

    private static String withSuffix(final String path) {
        return path.endsWith(dev.iyanz.sourbycraft.awf.AwfWorldFile.SUFFIX) ? path
            : path + dev.iyanz.sourbycraft.awf.AwfWorldFile.SUFFIX;
    }

    /** The {@code .awf} file to write inside {@code awf/exports}, or {@code null} after telling the sender why not. */
    private static Path exportTarget(final CommandSender s, final String typed) {
        return confined(s, AuroraWorldFiles.EXPORTS, typed.isBlank() ? typed : withSuffix(typed));
    }

    /** The file to read inside {@code awf/imports}, or {@code null} after telling the sender why not. */
    private static Path importSource(final CommandSender s, final String typed) {
        return confined(s, AuroraWorldFiles.IMPORTS, typed);
    }

    private static Path confined(final CommandSender s, final Path folder, final String typed) {
        try {
            return AuroraWorldFiles.confine(folder, typed);
        } catch (final IllegalArgumentException refused) {
            s.sendMessage(text(refused.getMessage(), SourbyCraftColors.DANGER));
            return null;
        }
    }

    /** Imports an .awf world file, or a Slime file, by what the file starts with. */
    private void importFile(final CommandSender s, final AuroraWorldsService worlds, final String[] args) {
        if (args.length < 3) {
            usage(s);
            return;
        }
        final Path file = importSource(s, args[1]);
        if (file == null) return;
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
        final Path source = importSource(s, args[1]);
        if (source == null) return;
        final Path target = exportTarget(s, args[2]);
        if (target == null) return;
        report(s, "Converted " + source + " to " + target, worlds.convertSlime(source, target, environment));
    }

    /** Imports a Slime file; chunks it does not hold are void unless told otherwise. */
    private void importSlime(final CommandSender s, final AuroraWorldsService worlds, final Path file,
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

    /**
     * Acknowledges now and reports the outcome when the operation completes. The completion runs
     * on whichever thread finished the work, so the report takes the sender's hop: a command
     * block's {@code sendMessage} writes block-entity state and throws off its region thread, and
     * this class used to call it from the storage lane.
     */
    /**
     * Forces every open store of a world (or of every world) into a full index, clean stores
     * included, and prints per store whether its current generation is one — the check before a
     * downgrade to a build without the delta parser. A store is named by its folder.
     */
    private void compact(final CommandSender s, final String world) {
        final String target = world.equalsIgnoreCase("all") ? "all" : world.toLowerCase(Locale.ROOT);
        s.sendMessage(text("Working...", SourbyCraftColors.DIM));
        this.compactor.apply(target).whenComplete((reports, failed) -> this.reply.accept(s, () -> {
            if (failed != null) {
                fail(s, failed);
                return;
            }
            if (reports.isEmpty()) {
                s.sendMessage(text("No AWF storage is open for " + target + " (a world must be loaded to be compacted)",
                    SourbyCraftColors.DANGER));
                return;
            }
            boolean every = true;
            for (final dev.iyanz.sourbycraft.awf.AwfEngine.CompactionReport r : reports) {
                every &= r.fullAfter() && r.error() == null;
                final String state = r.error() != null ? "full: " + (r.fullAfter() ? "yes" : "no") + ", failed: " + r.error()
                    : "generation " + r.generation() + ", full: " + (r.fullAfter() ? "yes" : "no")
                        + (r.fullBefore() ? " (already)" : " (compacted)");
                s.sendMessage(text("  " + PerfCommand.shortStorageName(r.storage()) + ": " + state,
                    r.fullAfter() && r.error() == null ? SourbyCraftColors.VALUE : SourbyCraftColors.DANGER));
            }
            if (every) {
                ok(s, "Every open store of " + target + " is a full index (" + reports.size() + " store(s));"
                    + " a build before AWF-7 can open them. Retained delta generations are skipped and their"
                    + " objects deleted by such a build: back up the .awf folders first if those rollback points matter.");
            } else {
                s.sendMessage(text("Not every store of " + target + " is a full index; do not downgrade yet", SourbyCraftColors.DANGER));
            }
        }));
    }


    // ------------------------------------------------------------------ AWF MultiProfile

    private void multiprofile(final CommandSender s, final String[] args) {
        final dev.iyanz.sourbycraft.awf.multiprofile.ProfileEngine engine =
            dev.iyanz.sourbycraft.awf.multiprofile.ProfileRuntime.engineOrNull();
        if (engine == null) {
            s.sendMessage(text("AWF MultiProfile is unavailable; check aurora.awf.multiprofile.enabled and startup errors",
                SourbyCraftColors.DANGER));
            return;
        }
        if (!s.hasPermission("sourbycraft.command.awf.multiprofile")) {
            s.sendMessage(text("You do not have sourbycraft.command.awf.multiprofile", SourbyCraftColors.DANGER));
            return;
        }
        final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> {
                final dev.iyanz.sourbycraft.awf.multiprofile.ProfileSettings settings = engine.settings();
                s.sendMessage(UiPanel.header("AWF MultiProfile"));
                s.sendMessage(UiPanel.row("mode", settings.mode().name().toLowerCase(Locale.ROOT)
                    + ", node " + engine.nodeId()));
                s.sendMessage(UiPanel.row("ownership", engine.ownership().describe()));
                s.sendMessage(UiPanel.row("switching", settings.switching().enabled()
                    ? settings.switching().timeoutMillis() + " ms budget"
                    : "disabled"));
                s.sendMessage(UiPanel.row("isolation", isolationSummary(settings.isolation())));
                s.sendMessage(UiPanel.row("worlds", "idle unload " + (settings.worlds().idleUnloadSeconds() == 0
                    ? "off" : settings.worlds().idleUnloadSeconds() + " s")));
                engine.pendingTransitions().whenComplete((pending, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    s.sendMessage(UiPanel.row("pending transitions", Integer.toString(pending.size())
                        + (pending.isEmpty() ? "" : " (run /awf multiprofile recover <id>)")));
                    for (final dev.iyanz.sourbycraft.awf.multiprofile.ProfileTransition transition : pending) {
                        s.sendMessage(text("  " + transition.transitionId() + "  " + transition.phase()
                            + "  player " + transition.playerId() + "  -> " + transition.toProfile(),
                            SourbyCraftColors.WARNING));
                    }
                }));
            }
            case "list" -> {
                final java.util.UUID player = playerId(s, args);
                if (player == null) return;
                engine.list(player).whenComplete((profiles, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    s.sendMessage(UiPanel.header("Profiles of " + player));
                    if (profiles.isEmpty()) s.sendMessage(UiPanel.row("(none)", "no profiles"));
                    for (final var profile : profiles) {
                        s.sendMessage(UiPanel.row(profile.id().toString(),
                            profile.type() + ", revision " + profile.revision() + ", " + profile.status()
                                + (profile.active() ? ", ACTIVE" : "")
                                + (profile.worlds().isEmpty() ? "" : ", worlds " + String.join(",", profile.worlds()))));
                    }
                }));
            }
            case "create" -> {
                if (!s.hasPermission("sourbycraft.command.awf.multiprofile.admin")) {
                    s.sendMessage(text("You do not have sourbycraft.command.awf.multiprofile.admin",
                        SourbyCraftColors.DANGER));
                    return;
                }
                final java.util.UUID player = playerId(s, args);
                if (player == null) return;
                final String type = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "default";
                engine.create(dev.iyanz.sourbycraft.api.profile.ProfileRequest.of(player, type))
                    .whenComplete((created, failed) -> this.reply.accept(s, () -> {
                        if (failed != null) {
                            fail(s, failed);
                            return;
                        }
                        s.sendMessage(text("Created profile " + created.id() + " (" + created.type() + ")",
                            SourbyCraftColors.SUCCESS));
                    }));
            }
            case "switch" -> {
                if (!s.hasPermission("sourbycraft.command.awf.multiprofile.admin")) {
                    s.sendMessage(text("You do not have sourbycraft.command.awf.multiprofile.admin",
                        SourbyCraftColors.DANGER));
                    return;
                }
                final java.util.UUID player = playerId(s, args);
                if (player == null) return;
                if (args.length < 4) {
                    s.sendMessage(text("Usage: /awf multiprofile switch <player> <profile-uuid>",
                        SourbyCraftColors.WARNING));
                    return;
                }
                final java.util.UUID profile;
                try {
                    profile = java.util.UUID.fromString(args[3]);
                } catch (final IllegalArgumentException notUuid) {
                    s.sendMessage(text("Not a profile uuid: " + args[3], SourbyCraftColors.DANGER));
                    return;
                }
                s.sendMessage(text("Switching...", SourbyCraftColors.DIM));
                engine.switchProfile(player, profile).whenComplete((result, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    final String detail = (result.success() ? "Switched" : "Refused: " + result.errorCode())
                        + " — " + result.message() + " (" + result.durationMillis() + " ms"
                        + (result.transitionId() == null ? "" : ", transition " + result.transitionId()) + ")";
                    s.sendMessage(text(detail, result.success() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.WARNING));
                }));
            }
            case "inspect" -> {
                final java.util.UUID profile = profileId(s, args);
                if (profile == null) return;
                engine.get(profile).whenComplete((found, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    if (found.isEmpty()) {
                        s.sendMessage(text("No profile " + profile, SourbyCraftColors.DANGER));
                        return;
                    }
                    final var info = found.get();
                    s.sendMessage(UiPanel.header("Profile " + info.id()));
                    s.sendMessage(UiPanel.row("account", info.accountId().toString()));
                    s.sendMessage(UiPanel.row("type", info.type()));
                    s.sendMessage(UiPanel.row("revision", Long.toString(info.revision()) + ", " + info.status()
                        + (info.active() ? ", active" : "")));
                    s.sendMessage(UiPanel.row("worlds", info.worlds().isEmpty() ? "none"
                        : String.join(",", info.worlds())));
                    s.sendMessage(UiPanel.row("metadata", info.metadata().toString()));
                }));
            }
            case "bind", "unbind" -> {
                if (!s.hasPermission("sourbycraft.command.awf.multiprofile.admin")) {
                    s.sendMessage(text("You do not have sourbycraft.command.awf.multiprofile.admin", SourbyCraftColors.DANGER));
                    return;
                }
                final java.util.UUID profile = profileId(s, args);
                if (profile == null) return;
                if (action.equals("bind") && (args.length < 4 || args.length > 5)) {
                    s.sendMessage(text("Usage: /awf multiprofile bind <profile-uuid> <world> [template]"
                        + " (replaces the entire binding with one PRIMARY world)", SourbyCraftColors.WARNING));
                    return;
                }
                try {
                    final var request = new dev.iyanz.sourbycraft.api.profile.ProfileWorldBindingRequest(profile,
                        action.equals("unbind") ? java.util.Map.of() : java.util.Map.of(args[3],
                            dev.iyanz.sourbycraft.api.profile.ProfileWorldRole.PRIMARY),
                        action.equals("bind") && args.length == 5 ? args[4] : null);
                    engine.bindWorlds(request).whenComplete((ignored, failed) -> this.reply.accept(s, () -> {
                        if (failed != null) { fail(s, failed); return; }
                        s.sendMessage(text("Replaced world binding of " + profile
                            + "; existing worlds were not deleted", SourbyCraftColors.SUCCESS));
                    }));
                } catch (final IllegalArgumentException invalid) {
                    s.sendMessage(text(invalid.getMessage(), SourbyCraftColors.DANGER));
                }
            }
            case "recover" -> {
                if (!s.hasPermission("sourbycraft.command.awf.multiprofile.admin")) {
                    s.sendMessage(text("You do not have sourbycraft.command.awf.multiprofile.admin",
                        SourbyCraftColors.DANGER));
                    return;
                }
                final java.util.UUID transition = uuidArg(s, args, "transition uuid");
                if (transition == null) return;
                s.sendMessage(text("Recovering...", SourbyCraftColors.DIM));
                engine.recover(transition).whenComplete((result, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    s.sendMessage(text((result.success() ? "Recovered: " : "Recovery result: ") + result.errorCode()
                            + " — " + result.message(),
                        result.success() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.WARNING));
                }));
            }
            default -> s.sendMessage(text("Usage: /awf multiprofile <status|list <player>|create <player> [type]|"
                + "switch <player> <profile-uuid>|bind <profile> <world> [template]|unbind <profile>|inspect <profile>|recover <transition>>", SourbyCraftColors.WARNING));
        }
    }

    private static String isolationSummary(final dev.iyanz.sourbycraft.awf.multiprofile.ProfileIsolation isolation) {
        final java.util.List<String> on = new java.util.ArrayList<>();
        if (isolation.inventory()) on.add("inventory");
        if (isolation.armor()) on.add("armor/offhand");
        if (isolation.enderChest()) on.add("ender-chest");
        if (isolation.experience()) on.add("experience");
        if (isolation.health()) on.add("health");
        if (isolation.food()) on.add("food");
        if (isolation.potionEffects()) on.add("effects");
        if (isolation.location()) on.add("location");
        return on.isEmpty() ? "none (active-profile reference only)" : String.join(", ", on);
    }

    private java.util.UUID playerId(final CommandSender s, final String[] args) {
        if (args.length < 3) {
            s.sendMessage(text("Usage: /awf multiprofile list <player>", SourbyCraftColors.WARNING));
            return null;
        }
        final org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayerExact(args[2]);
        if (online != null) return online.getUniqueId();
        try {
            return java.util.UUID.fromString(args[2]);
        } catch (final IllegalArgumentException notUuid) {
            s.sendMessage(text("No online player " + args[2] + "; pass the UUID for an offline player",
                SourbyCraftColors.DANGER));
            return null;
        }
    }

    private java.util.UUID profileId(final CommandSender s, final String[] args) {
        return uuidArg(s, args, "profile uuid");
    }

    private java.util.UUID uuidArg(final CommandSender s, final String[] args, final String what) {
        if (args.length < 3) {
            s.sendMessage(text("Expected a " + what, SourbyCraftColors.WARNING));
            return null;
        }
        try {
            return java.util.UUID.fromString(args[2]);
        } catch (final IllegalArgumentException notUuid) {
            s.sendMessage(text("Not a " + what + ": " + args[2], SourbyCraftColors.DANGER));
            return null;
        }
    }

    // ------------------------------------------------------------------ Aurora Redis Fabric

    private void redis(final CommandSender s, final String[] args) {
        if (!s.hasPermission("sourbycraft.command.awf.redis")) {
            s.sendMessage(text("You do not have sourbycraft.command.awf.redis", SourbyCraftColors.DANGER));
            return;
        }
        final dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric fabric =
            dev.iyanz.sourbycraft.awf.redis.fabric.FabricRuntime.running();
        if (fabric == null) {
            s.sendMessage(text("The Aurora Redis Fabric is disabled or was not reachable at startup"
                + " (aurora.awf.redis.fabric.enabled)", SourbyCraftColors.WARNING));
            return;
        }
        final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        final java.util.concurrent.Executor io =
            dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.lane(
                dev.iyanz.sourbycraft.execution.ResourceGovernor.Lane.STORAGE);
        s.sendMessage(text("Working...", SourbyCraftColors.DIM));
        final java.util.concurrent.CompletableFuture<
            dev.iyanz.sourbycraft.awf.redis.fabric.FabricHealth> health =
            java.util.concurrent.CompletableFuture.supplyAsync(fabric::probe, io);
        health.whenComplete((probe, probeFailed) -> this.reply.accept(s, () -> {
            if (probeFailed != null) {
                fail(s, probeFailed);
                return;
            }
            switch (action) {
                case "status", "health" -> {
                    s.sendMessage(UiPanel.header("Aurora Redis Fabric"));
                    s.sendMessage(UiPanel.row("node", probe.nodeId()));
                    s.sendMessage(UiPanel.row("redis", probe.reachable()
                        ? "reachable, " + probe.redisVersion() + ", " + probe.latencyMicros() + " us" : "UNREACHABLE"));
                    s.sendMessage(UiPanel.row("keys", probe.prefix() + "*"));
                    s.sendMessage(UiPanel.row("pools", "storage " + probe.pools().get(
                            dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload.STORAGE).size()
                        + ", coordination " + probe.pools().get(
                            dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload.COORDINATION).size()
                        + ", events " + probe.pools().get(
                            dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload.EVENTS).size()
                        + ", control " + probe.pools().get(
                            dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload.CONTROL).size()));
                    if (!fabric.settings().services().telemetry()) {
                        s.sendMessage(UiPanel.row("telemetry", "disabled"
                            + " (aurora.awf.redis.fabric.services.telemetry)"));
                        break;
                    }
                    final var metrics = probe.metrics();
                    s.sendMessage(UiPanel.row("operations", metrics.operations() + " (" + metrics.failures()
                        + " failed, " + metrics.timeouts() + " timed out), mean "
                        + String.format(Locale.ROOT, "%.2f", metrics.meanLatencyMillis()) + " ms"));
                    s.sendMessage(UiPanel.row("cache", metrics.cacheHitRatio() < 0 ? "no lookups"
                        : String.format(Locale.ROOT, "%.1f%% hit", metrics.cacheHitRatio() * 100.0)
                            + ", " + metrics.cacheEvictions() + " evictions"));
                    s.sendMessage(UiPanel.row("fences", metrics.ownershipConflicts() + " conflicts, "
                        + metrics.staleFenceRejections() + " stale rejections, "
                        + metrics.leaseRenewFailures() + " renew failures"));
                }
                case "pools" -> {
                    probe.pools().forEach((kind, stat) -> s.sendMessage(UiPanel.row(kind.name().toLowerCase(Locale.ROOT),
                        stat.inUse() + " in use of " + stat.size())));
                }
                case "events" -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!fabric.settings().services().events()) return -1L;
                        return fabric.events().backlog();
                    } catch (final java.io.IOException failed) {
                        throw new java.util.concurrent.CompletionException(failed);
                    }
                }, io).whenComplete((backlog, failed) -> this.reply.accept(s, () -> {
                    if (failed != null) {
                        fail(s, failed);
                        return;
                    }
                    s.sendMessage(UiPanel.row("stream", backlog < 0 ? "service disabled"
                        : backlog + " entries, retention " + fabric.settings().events().retention()));
                    s.sendMessage(UiPanel.row("consumers", "retry budget "
                        + fabric.settings().events().maxDeliveries() + " deliveries"
                        + (fabric.settings().services().events()
                            ? ", dead-letter at " + fabric.events().deadStream() : ", events service disabled")));
                    final var metrics = probe.metrics();
                    s.sendMessage(UiPanel.row("events", metrics.eventsPublished() + " published, "
                        + metrics.eventsConsumed() + " consumed, " + metrics.eventsRetried() + " retried, "
                        + metrics.deadLettered() + " dead-lettered, " + metrics.duplicatesSuppressed() + " duplicates"));
                }));
                case "sessions", "ownership" -> {
                    final java.util.UUID id = uuidArg(s, args, action.equals("sessions") ? "profile uuid" : "object uuid");
                    if (id == null) return;
                    final String kind = action.equals("sessions") ? "session" : "profile";
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return kind.equals("session") ? fabric.sessions().holder(id)
                                : fabric.coordination().current("profile", id.toString());
                        } catch (final java.io.IOException failed) {
                            throw new java.util.concurrent.CompletionException(failed);
                        }
                    }, io).whenComplete((claim, failed) -> this.reply.accept(s, () -> {
                        if (failed != null) {
                            fail(s, failed);
                            return;
                        }
                        if (claim == null) s.sendMessage(UiPanel.row(id.toString(), "unowned"));
                        else s.sendMessage(UiPanel.row(id.toString(), "node " + claim.node() + ", epoch/fence "
                            + claim.fence() + ", expires " + claim.expiresAtMillis()));
                    }));
                }
                default -> s.sendMessage(text("Usage: /awf redis <status|pools|sessions <profile>|events|"
                    + "ownership <profile>|health>", SourbyCraftColors.WARNING));
            }
        }));
    }

    private void report(final CommandSender s, final String success, final CompletableFuture<?> future) {
        s.sendMessage(text("Working...", SourbyCraftColors.DIM));
        future.whenComplete((ignored, failed) -> this.reply.accept(s, () -> {
            if (failed != null) fail(s, failed);
            else ok(s, success);
        }));
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
            + " [generator=Plugin[:id]|generator=void:<biome>] [seed] [autoload]", "new AWF world"));
        s.sendMessage(UiPanel.row("/awf create <name> from <template> [autoload]", "copy-on-write instance"));
        s.sendMessage(UiPanel.row("/awf import <file.awf> <name> [autoload]", "new world from awf/imports/<file.awf>"));
        s.sendMessage(UiPanel.row("/awf import <file.slime> <name> [normal|nether|end] [vanilla|generator=Plugin[:id]]"
            + " [autoload]", "new world from awf/imports/<file.slime>"));
        s.sendMessage(UiPanel.row("/awf export <world> <file.awf>", "unloaded world to awf/exports/<file.awf>"));
        s.sendMessage(UiPanel.row("/awf convert <file.slime> <file.awf> [normal|nether|end]",
            "awf/imports/<file.slime> to awf/exports/<file.awf>"));
        s.sendMessage(UiPanel.row("/awf template list | save <world> <template> | delete <template> confirm", "templates"));
        s.sendMessage(UiPanel.row("/awf load | save <name>", "load, or save and commit"));
        s.sendMessage(UiPanel.row("/awf unload <name> [nosave]", "unload"));
        s.sendMessage(UiPanel.row("/awf delete <name> confirm", "delete permanently"));
        s.sendMessage(UiPanel.row("/awf autoload <name> on|off", "load at startup"));
        s.sendMessage(UiPanel.row("/awf clone <world> <new> [autoload]", "copy an unloaded world (an instance keeps its template)"));
        s.sendMessage(UiPanel.row("/awf set <world> <spawn|difficulty|pvp|monsters|animals|biome|bounds|prune> <value|none>", "world properties"));
        s.sendMessage(UiPanel.row("/awf compact <world|all>", "force every open store into a full index; prints full: yes/no (before a downgrade)"));
        s.sendMessage(UiPanel.footer());
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        final AuroraWorldsService worlds = this.service.get();
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
