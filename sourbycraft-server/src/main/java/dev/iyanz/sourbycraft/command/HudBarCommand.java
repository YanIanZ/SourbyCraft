package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.hud.HudBars;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /tpsbar} + {@code /rambar} — per-player HUD toggles backed by the shared
 * {@link HudBars} bars (TPS/MSPT from the perf sensor's worst-region aggregate; RAM heap +
 * panel allocation).
 *
 * <p>Each player chooses how their bar is delivered: {@code bossbar} (default) or
 * {@code actionbar}. The choice is stored in the player's data, so it survives a rejoin.
 */
public class HudBarCommand extends Command {

    private final boolean tps;

    public HudBarCommand(String name, boolean tps) {
        super(name);
        this.tps = tps;
        this.description = tps ? "Toggle the TPS/MSPT bar" : "Toggle the RAM bar";
        this.usageMessage = "/" + name + " [on|off|bossbar|actionbar]";
        this.setPermission("sourbycraft.command." + name);
    }

    /**
     * Toggles the bar for the invoking player, or shows it in the requested display mode.
     * Players only.
     */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        if (!(s instanceof Player p)) {
            s.sendMessage(text("Players only — the bar is a client HUD.", SourbyCraftColors.DANGER));
            return true;
        }
        if (args.length > 1) {
            s.sendMessage(text(this.usageMessage, SourbyCraftColors.DIM));
            return true;
        }
        final boolean shown;
        String suffix = "";
        if (args.length == 0) {
            shown = HudBars.toggle(p, this.tps);
        } else {
            final String argument = args[0].toLowerCase(Locale.ROOT);
            final HudBars.DisplayMode mode = HudBars.DisplayMode.parse(argument);
            if (mode != null) {
                shown = HudBars.setMode(p, this.tps, mode);
                suffix = " (" + argument + ")";
            } else if (argument.equals("on") || argument.equals("off")) {
                shown = HudBars.setPreference(p, this.tps, argument.equals("on"));
            } else {
                s.sendMessage(text(this.usageMessage, SourbyCraftColors.DIM));
                return true;
            }
        }
        s.sendMessage(text()
            .append(text((this.tps ? "TPS" : "RAM") + " bar ", SourbyCraftColors.LABEL))
            .append(text(shown ? "shown" : "hidden", shown ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM))
            .append(text(suffix, SourbyCraftColors.DIM))
            .build());
        return true;
    }
}
