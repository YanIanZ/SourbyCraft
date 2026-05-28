package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.combat.KnockbackManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

public class KbCommand extends Command {
    public KbCommand(String n) {
        super(n);
        this.description = "Knockback manipulation";
        this.usageMessage = "/kb [global on|off] | [player <name> <multi>] | [reset <name>]";
        this.setPermission("sourbycraft.command.kb");
    }

    @Override
    public boolean execute(CommandSender s, String a, String[] args) {
        if (!testPermission(s)) return true;

        if (args.length == 0) {
            s.sendMessage(text().append(text("Global: ", SourbyCraftColors.LABEL))
                .append(text(SourbyCraftConfig.knockbackGlobalEnabled ? "on" : "off",
                    SourbyCraftConfig.knockbackGlobalEnabled ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER)));
            if (s instanceof Player p) {
                double m = KnockbackManager.getMultiplier(p.getUniqueId());
                s.sendMessage(text().append(text("Your multiplier: ", SourbyCraftColors.LABEL))
                    .append(text(String.format("%.2f", m), SourbyCraftColors.VALUE)));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("global")) {
            if (args.length < 2) {
                s.sendMessage(text("Usage: /kb global on|off", SourbyCraftColors.DIM));
                return true;
            }
            if (!s.hasPermission("sourbycraft.command.kb.admin")) {
                s.sendMessage(text("No permission for admin actions", SourbyCraftColors.DANGER));
                return true;
            }
            boolean on = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
            SourbyCraftConfig.knockbackGlobalEnabled = on;
            s.sendMessage(text().append(text("Global knockback ", SourbyCraftColors.LABEL))
                .append(text(on ? "enabled" : "disabled",
                    on ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER)));
            return true;
        }

        if (args[0].equalsIgnoreCase("player") && args.length >= 3) {
            if (!s.hasPermission("sourbycraft.command.kb.admin")) {
                s.sendMessage(text("No permission for admin actions", SourbyCraftColors.DANGER));
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                s.sendMessage(text("Player not found: " + args[1], SourbyCraftColors.DANGER));
                return true;
            }
            double m;
            try { m = Double.parseDouble(args[2]); }
            catch (NumberFormatException e) {
                s.sendMessage(text("Invalid number: " + args[2], SourbyCraftColors.DANGER));
                return true;
            }
            KnockbackManager.setMultiplier(target.getUniqueId(), m);
            s.sendMessage(text().append(text("Set ", SourbyCraftColors.LABEL))
                .append(text(target.getName(), SourbyCraftColors.VALUE))
                .append(text(" multiplier to ", SourbyCraftColors.LABEL))
                .append(text(String.format("%.2f", KnockbackManager.getMultiplier(target.getUniqueId())), SourbyCraftColors.SUCCESS)));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") && args.length >= 2) {
            if (!s.hasPermission("sourbycraft.command.kb.admin")) {
                s.sendMessage(text("No permission for admin actions", SourbyCraftColors.DANGER));
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                s.sendMessage(text("Player not found: " + args[1], SourbyCraftColors.DANGER));
                return true;
            }
            KnockbackManager.setMultiplier(target.getUniqueId(), 1.0);
            s.sendMessage(text("Reset " + target.getName() + " to 1.0", SourbyCraftColors.SUCCESS));
            return true;
        }

        s.sendMessage(text("Usage: " + this.usageMessage, SourbyCraftColors.DIM));
        return true;
    }
}
