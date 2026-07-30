package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.hud.HudBars;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /tpsbar} + {@code /rambar} — per-player boss-bar HUD toggles backed by the shared
 * {@link HudBars} bars (TPS/MSPT from the perf sensor's worst-region aggregate; RAM heap +
 * panel allocation).
 */
public class HudBarCommand extends Command {

    private final boolean tps;

    public HudBarCommand(String name, boolean tps) {
        super(name);
        this.tps = tps;
        this.description = tps ? "Toggle the TPS/MSPT boss bar" : "Toggle the RAM boss bar";
        this.usageMessage = "/" + name;
        this.setPermission("sourbycraft.command." + name);
    }

    /** Toggles the bar for the invoking player via {@link HudBars#toggle}. Players only. */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        if (!(s instanceof Player p)) {
            s.sendMessage(text("Players only — the bar is a client HUD.", SourbyCraftColors.DANGER));
            return true;
        }
        final boolean shown = HudBars.toggle(p, this.tps);
        s.sendMessage(text()
            .append(text((this.tps ? "TPS" : "RAM") + " bar ", SourbyCraftColors.LABEL))
            .append(text(shown ? "shown" : "hidden", shown ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM))
            .build());
        return true;
    }
}
