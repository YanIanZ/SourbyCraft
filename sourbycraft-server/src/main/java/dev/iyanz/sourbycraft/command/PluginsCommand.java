package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.bridge.CompatibilityClassifier;
import dev.iyanz.sourbycraft.bridge.CompatibilityState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.regex.Pattern;
import static net.kyori.adventure.text.Component.text;

/** One roster and detail view for /plugins and /pl, including captured load failures. */
public class PluginsCommand extends Command {
    private static final Pattern JAR_VERSION = Pattern.compile("[-_ ]v?\\d[\\w.+-]*$");
    private static final List<String> STATES = List.of("native", "bridged", "failed", "disabled");

    public PluginsCommand(String name) {
        super(name);
        description = "Plugin roster, search and Aurora compatibility details";
        usageMessage = "/plugins [page|plugin|search <name> [page]|filter <state> [page]]";
        setAliases(List.of("pl"));
        setPermission("sourbycraft.command.plugins");
    }

    static String failureName(String jarName) {
        String name = jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName;
        String stripped = JAR_VERSION.matcher(name).replaceFirst("");
        return stripped.isEmpty() ? name : stripped;
    }

    static CompatibilityState stateOf(Plugin plugin) {
        return CompatibilityClassifier.classify(new CompatibilityClassifier.Evidence(
            plugin.getPluginMeta().isFoliaSupported(), plugin.isEnabled(),
            PluginLoadDiagnostics.enableFailed(plugin.getName()), AuroraBridge.bridgeInitialized(plugin),
            AuroraBridge.fatalViolation(plugin)));
    }

    static TextColor colorOf(CompatibilityState state) {
        return switch (state) {
            case NATIVE -> SourbyCraftColors.PLUGIN_NATIVE;
            case BRIDGED -> SourbyCraftColors.PLUGIN_BRIDGED;
            case FAILED -> SourbyCraftColors.PLUGIN_FAILED;
            case DISABLED -> SourbyCraftColors.PLUGIN_DISABLED;
        };
    }

    record Row(String name, CompatibilityState state, String reason, String jar) {}
    record Request(int page, String query, CompatibilityState filter, String detail) {}

    /** Parsing is shared by both aliases; invalid pages are never silently treated as plugin names. */
    static Request parse(String[] args) {
        if (args.length == 0) return new Request(1, "", null, null);
        if (args[0].equalsIgnoreCase("search")) {
            if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Use /plugins search <name> [page]");
            return new Request(args.length == 3 ? page(args[2]) : 1, args[1], null, null);
        }
        if (args[0].equalsIgnoreCase("filter")) {
            if (args.length < 2 || args.length > 3 || !STATES.contains(args[1].toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Filter: native, bridged, failed or disabled");
            return new Request(args.length == 3 ? page(args[2]) : 1, "",
                CompatibilityState.valueOf(args[1].toUpperCase(Locale.ROOT)), null);
        }
        if (args.length != 1) throw new IllegalArgumentException("Use /plugins <plugin> or /plugins search <name>");
        if (args[0].matches("[+-]?\\d+")) return new Request(page(args[0]), "", null, null);
        return new Request(1, "", null, args[0]);
    }

    private static int page(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page > 0) return page;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Page must be a positive whole number");
    }

    static List<Row> select(List<Row> rows, Request request) {
        String query = request.query().toLowerCase(Locale.ROOT);
        return rows.stream().filter(row -> row.name().toLowerCase(Locale.ROOT).contains(query))
            .filter(row -> request.filter() == null || row.state() == request.filter())
            .sorted(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::jar))
            .toList();
    }

    private static List<Row> roster() {
        List<Row> rows = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            CompatibilityState state = stateOf(plugin);
            rows.add(new Row(plugin.getName(), state, switch (state) {
                case NATIVE -> "Declares region-threading support; this is not a safety certification.";
                case BRIDGED -> "Aurora Bridge admitted this plugin; inspect details for rejected work.";
                case FAILED -> "Enable failure or fatal ownership violation observed; inspect logs.";
                case DISABLED -> "Loaded but currently disabled; this alone does not indicate a failure.";
            }, ""));
        }
        // One recent failure per jar, keeping the latest reason. Different jars remain visible.
        Map<String, PluginLoadDiagnostics.Entry> failures = new LinkedHashMap<>();
        for (var failure : PluginLoadDiagnostics.recent()) failures.put(failure.pluginJar(), failure);
        for (var failure : failures.values()) rows.add(new Row(failureName(failure.pluginJar()),
            CompatibilityState.FAILED, failure.reason(), failure.pluginJar()));
        return rows;
    }

    private boolean detail(CommandSender sender, String name) {
        Plugin plugin = Arrays.stream(Bukkit.getPluginManager().getPlugins())
            .filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        sender.sendMessage(UiPanel.header("Plugin details"));
        if (plugin == null) {
            var failures = roster().stream().filter(row -> !row.jar().isEmpty()
                && (row.name().equalsIgnoreCase(name) || row.jar().equalsIgnoreCase(name))).toList();
            if (failures.isEmpty()) sender.sendMessage(UiPanel.row("Result", "No plugin found: " + name));
            for (Row failure : failures) {
                sender.sendMessage(UiPanel.row("Plugin", failure.name()));
                sender.sendMessage(UiPanel.row("State", "FAILED — did not load"));
                sender.sendMessage(UiPanel.row("Jar", failure.jar()));
                sender.sendMessage(UiPanel.row("Reason", failure.reason()));
            }
        } else {
            CompatibilityState state = stateOf(plugin);
            sender.sendMessage(UiPanel.row("Plugin", plugin.getName()));
            sender.sendMessage(UiPanel.row("Version", plugin.getPluginMeta().getVersion()));
            sender.sendMessage(text("  State: ", SourbyCraftColors.LABEL).append(text(state.display(), colorOf(state))));
            sender.sendMessage(UiPanel.row("Enabled", plugin.isEnabled() ? "yes" : "no"));
            sender.sendMessage(UiPanel.row("Declares region support", plugin.getPluginMeta().isFoliaSupported() ? "yes" : "no"));
            var stats = AuroraBridge.stats(plugin);
            if (stats == null) sender.sendMessage(UiPanel.row("Bridge telemetry", "not available for this plugin"));
            else {
                sender.sendMessage(UiPanel.section("Aurora Bridge"));
                sender.sendMessage(UiPanel.row("Scheduler redirects / owner handoffs", stats.schedulerRedirects() + " / " + stats.ownerHandoffs()));
                sender.sendMessage(UiPanel.row("Rejected / fatal operations", stats.rejectedOperations() + " / " + stats.fatalViolations()));
                sender.sendMessage(UiPanel.row("Quarantined", stats.quarantined() ? "yes" : "no"));
                sender.sendMessage(UiPanel.row("Startup cache", stats.startupCacheState()));
                sender.sendMessage(UiPanel.row("Last failure", stats.lastFailure() == null ? "none" : stats.lastFailure()));
            }
        }
        sender.sendMessage(UiPanel.actions(List.of("/plugins", "/plugins filter failed")));
        sender.sendMessage(UiPanel.footer());
        return true;
    }

    static List<Component> render(List<Row> all, Request request, int requestedSize) {
        int size = Math.clamp(requestedSize, 1, 40);
        List<Row> rows = select(all, request);
        int pages = Math.max(1, (rows.size() + size - 1) / size);
        List<Component> lines = new ArrayList<>();
        lines.add(UiPanel.header("Plugins"));
        Map<CompatibilityState, Long> counts = new EnumMap<>(CompatibilityState.class);
        all.forEach(row -> counts.merge(row.state(), 1L, Long::sum));
        lines.add(UiPanel.row("Native / Bridged", counts.getOrDefault(CompatibilityState.NATIVE, 0L)
            + " / " + counts.getOrDefault(CompatibilityState.BRIDGED, 0L)));
        lines.add(UiPanel.row("Failed / Disabled", counts.getOrDefault(CompatibilityState.FAILED, 0L)
            + " / " + counts.getOrDefault(CompatibilityState.DISABLED, 0L)));
        lines.add(UiPanel.hint("Counts include recent load failures. Disabled does not mean failed."));
        lines.add(UiPanel.section("Matches " + rows.size() + "  |  Page " + request.page() + "/" + pages
            + (request.query().isEmpty() ? "" : "  |  Search: " + request.query())
            + (request.filter() == null ? "" : "  |  " + request.filter().display())));
        if (request.page() > pages) lines.add(UiPanel.hint("Page does not exist. Last page: " + pages));
        else {
            int start = (request.page() - 1) * size;
            for (Row row : rows.subList(start, Math.min(start + size, rows.size()))) {
                lines.add(text("  " + row.state().display() + "  ", colorOf(row.state()))
                    .append(text(row.name(), SourbyCraftColors.VALUE))
                    .append(row.jar().isEmpty() ? Component.empty() : text("  (load failed)", SourbyCraftColors.DIM))
                    .hoverEvent(text(row.reason() + (row.jar().isEmpty() ? "" : "\nJar: " + row.jar())))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/plugins " + (row.jar().isEmpty() ? row.name() : row.jar()))));
            }
            if (rows.isEmpty()) lines.add(UiPanel.hint("No matching plugins."));
        }
        String base = request.filter() != null ? "/plugins filter " + request.filter().name().toLowerCase(Locale.ROOT)
            : request.query().isEmpty() ? "/plugins" : "/plugins search " + request.query();
        Component navigation = text("  ");
        if (request.page() > 1) navigation = navigation.append(UiPanel.action("Previous", base + " " + Math.min(pages, request.page() - 1), "Previous page")).append(text("  "));
        if (request.page() < pages) navigation = navigation.append(UiPanel.action("Next", base + " " + (request.page() + 1), "Next page"));
        lines.add(navigation);
        lines.add(UiPanel.hint("/plugins <plugin> · /plugins search <name> · /plugins filter <state>"));
        lines.add(UiPanel.footer());
        return List.copyOf(lines);
    }

    @Override public boolean execute(CommandSender sender, String alias, String[] args) {
        if (!testPermission(sender)) return true;
        try {
            Request request = parse(args);
            if (request.detail() != null) return detail(sender, request.detail());
            render(roster(), request, SourbyCraftConfig.cfgInt("ui.plugins-page-size", 12)).forEach(sender::sendMessage);
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage(text(invalid.getMessage(), SourbyCraftColors.DANGER));
            sender.sendMessage(UiPanel.hint(usageMessage));
        }
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender)) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("filter"))
            return STATES.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("search", "filter", "1"));
        Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).forEach(options::add);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
