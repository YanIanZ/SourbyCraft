package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.spark.SparkBridge;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/**
 * /spark — SourbyCraft spark entry / status shim.
 *
 * <h2>Why this shim exists</h2>
 * SourbyCraft is a Folia line built on Luminol/Leaves, where the <b>bundled</b>
 * spark profiler ({@code io.papermc.paper.SparksFly}) is force-disabled upstream
 * (patch {@code features/0004-Force-disable-builtin-spark-plugin.patch} flips its
 * {@code if (false)} guards; {@code CraftServer.spark} is left {@code null}). So the
 * real Paper {@code /spark} command is never registered by the base, and re-enabling
 * the bundled module would mean un-guarding a large, null-field-fragile set of NMS
 * patch sites — out of scope for an authored-source feature.
 *
 * <p>The supported path to <b>full</b> spark is therefore the external plugin:
 * {@code SparksFly.isPluginPreferred()} returns {@code true}, so the guard in
 * {@code FileProviderSource} never blocks an operator-supplied {@code spark-*.jar}
 * in {@code plugins/}. When that plugin loads it registers the genuine {@code /spark}
 * command and calls {@code SparkProvider.set(...)} — which simultaneously lights up
 * SourbyCraft's {@code /sparkview} quick view (both read the same singleton).
 *
 * <h2>What this command does</h2>
 * It never shadows a real {@code /spark}: {@link SourbyCraftCommands} only registers
 * this shim on the bare name when no other command already owns it (otherwise only the
 * {@code sourbycraft:spark} alias is bound). When run, it reports whether the spark API
 * is live (via {@link SparkBridge}) and, if not, prints the exact steps to enable full
 * spark. This gives operators a discoverable {@code /spark} entry point matching the
 * {@code /perf} / {@code /ver} panel styling, plus a pointer to {@code /sparkview} for
 * the built-in quick view.
 */
public class SparkCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);
    private static final String SPARK_JAR_URL = "https://spark.lucko.me/download";

    public SparkCommand(String name) {
        super(name);
        this.description = "Spark profiler status / entry point for SourbyCraft";
        this.usageMessage = "/spark";
        this.setPermission("sourbycraft.command.spark");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("Spark", SourbyCraftColors.LABEL))
            .build());

        boolean bridgeEnabled = SparkBridge.enabled();
        boolean apiReady = bridgeEnabled && SparkBridge.isReady();

        s.sendMessage(status("  Bridge (spark.enabled)", bridgeEnabled));
        s.sendMessage(status("  Spark API live       ", apiReady));

        if (apiReady) {
            s.sendMessage(text("  Full spark is active. Common commands:", SourbyCraftColors.LABEL));
            cmd(s, "/spark profiler", "start/stop a sampling profiler");
            cmd(s, "/spark health", "server health + TPS/MSPT/CPU summary");
            cmd(s, "/spark tps", "detailed TPS/MSPT readout");
            cmd(s, "/spark gc", "GC statistics");
            s.sendMessage(text()
                .append(text("  Quick view: ", SourbyCraftColors.DIM))
                .append(text("/sparkview", SourbyCraftColors.VALUE))
                .append(text("  (SourbyCraft one-shot TPS/MSPT/CPU/GC panel)", SourbyCraftColors.DIM))
                .build());
        } else {
            if (!bridgeEnabled) {
                s.sendMessage(text("  The spark bridge is disabled (spark.enabled = false in", SourbyCraftColors.DIM));
                s.sendMessage(text("  sourbycraft_config/sourbycraft_global_config.toml). Set it true to use spark.", SourbyCraftColors.DIM));
            }
            s.sendMessage(text("  Full spark is not currently loaded on this server.", SourbyCraftColors.WARNING));
            s.sendMessage(text("  The bundled profiler is disabled on this Folia build; to enable full", SourbyCraftColors.DIM));
            s.sendMessage(text("  spark drop the standalone spark plugin jar into plugins/ and restart:", SourbyCraftColors.DIM));
            s.sendMessage(text()
                .append(text("    1. Download spark for Bukkit/Paper: ", SourbyCraftColors.DIM))
                .append(text(SPARK_JAR_URL, SourbyCraftColors.ACCENT)
                    .clickEvent(ClickEvent.openUrl(SPARK_JAR_URL)))
                .build());
            s.sendMessage(text("    2. Place spark-*.jar in plugins/  (it is honoured — the server prefers", SourbyCraftColors.DIM));
            s.sendMessage(text("       an external spark plugin), then restart.", SourbyCraftColors.DIM));
            s.sendMessage(text("    3. /spark and /sparkview then work against the loaded profiler.", SourbyCraftColors.DIM));
            s.sendMessage(text()
                .append(text("  Meanwhile: ", SourbyCraftColors.DIM))
                .append(text("/tps", SourbyCraftColors.VALUE))
                .append(text(" and ", SourbyCraftColors.DIM))
                .append(text("/perf", SourbyCraftColors.VALUE))
                .append(text(" give live TPS / MSPT / tier without spark.", SourbyCraftColors.DIM))
                .build());
        }

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static Component status(String label, boolean ok) {
        TextColor c = ok ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER;
        return text()
            .append(text(label + ": ", SourbyCraftColors.DIM))
            .append(text(ok ? "yes" : "no", c))
            .build();
    }

    private static void cmd(CommandSender s, String command, String desc) {
        s.sendMessage(text()
            .append(text("    " + command, SourbyCraftColors.VALUE))
            .append(text("  " + desc, SourbyCraftColors.DIM))
            .build());
    }
}
