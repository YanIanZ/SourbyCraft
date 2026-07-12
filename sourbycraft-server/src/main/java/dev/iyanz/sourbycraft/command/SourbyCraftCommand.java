package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /sourbycraft} root command. Currently:
 * <ul>
 *   <li>{@code /sourbycraft reload} — re-read the unified TOML from disk and re-apply it live
 *       (perf knobs, sensor, anti-xray toggles, lag limits + caps) without a restart.</li>
 *   <li>{@code /sourbycraft} / {@code /sourbycraft version} — build + reload hint.</li>
 * </ul>
 */
public class SourbyCraftCommand extends Command {

    public SourbyCraftCommand(String name) {
        super(name);
        this.description = "SourbyCraft admin (reload config live)";
        this.usageMessage = "/sourbycraft [reload|version]";
        this.setPermission("sourbycraft.command.admin");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        final String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "version";
        switch (sub) {
            case "reload" -> {
                s.sendMessage(text("Reloading SourbyCraft config from disk...", SourbyCraftColors.LABEL));
                final String result = SourbyCraftConfig.reload();
                final boolean ok = result.startsWith("reloaded");
                s.sendMessage(text()
                    .append(text(ok ? "\u2714 " : "\u2716 ", ok ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER))
                    .append(text(result, ok ? SourbyCraftColors.VALUE : SourbyCraftColors.DANGER))
                    .build());
            }
            case "version", "ver" -> {
                BuildInfo info = BuildInfo.load();
                s.sendMessage(text()
                    .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
                    .append(text(info.version() + " build " + info.build(), SourbyCraftColors.VALUE))
                    .build());
                s.sendMessage(text("  /sourbycraft reload — re-apply the config without a restart.", SourbyCraftColors.DIM));
            }
            default -> s.sendMessage(text("Usage: " + this.usageMessage, SourbyCraftColors.DANGER));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "version");
        return List.of();
    }
}
