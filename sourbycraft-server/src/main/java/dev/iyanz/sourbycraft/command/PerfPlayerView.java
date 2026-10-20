package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * {@code /perf player <name>}: the player's own view, gathered on the thread that owns the
 * player (their entity scheduler): a command thread may not read an entity it does not own.
 * Always takes its argument; any other arity shows its own usage.
 */
final class PerfPlayerView implements PerfView {

    @Override public String name() { return "player"; }

    /** Never reached through the common path: {@link #execute} answers every form. */
    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {}

    @Override
    public boolean execute(final CommandSender sender, final String command, final String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Component.text("/" + command + " player <name>", SourbyCraftColors.DIM));
        } else {
            renderPlayer(sender, args[1]);
        }
        return true;
    }

    private static void renderPlayer(final CommandSender sender, final String name) {
        final org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text(name + " is not online", SourbyCraftColors.DIM));
            return;
        }
        renderPlayer(sender, player, dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin.INSTANCE, SourbyReply::run);
    }

    /**
     * Gathers the view on {@code player}'s entity thread and sends it back through {@code reply}.
     * When the sender is the player, that thread already owns them both and the lines go straight
     * out. Any other sender -- the console, another player, a command block -- is not owned by
     * that thread, and messaging a command block from it throws (OBS-8); so the view takes the
     * sender's own hop, as every other off-thread reply does.
     */
    static void renderPlayer(final CommandSender sender, final org.bukkit.entity.Player player,
                             final org.bukkit.plugin.Plugin owner,
                             final java.util.function.BiConsumer<CommandSender, Runnable> reply) {
        final String name = player.getName();
        final var scheduled = player.getScheduler().run(owner,
            task -> {
                final List<Component> lines = new ArrayList<>();
                final org.bukkit.Location at = player.getLocation();
                add(lines, "Player", name);
                add(lines, "World / block", at.getWorld().getName() + " / " + at.getBlockX() + ", " + at.getBlockY()
                    + ", " + at.getBlockZ());
                add(lines, "Chunk", (at.getBlockX() >> 4) + ", " + (at.getBlockZ() >> 4));
                add(lines, "Ping", player.getPing() + " ms");
                add(lines, "View / simulation distance", player.getViewDistance() + " / "
                    + player.getSimulationDistance());
                add(lines, "Owning thread", Thread.currentThread().getName());
                if (sender == player) {
                    lines.forEach(sender::sendMessage);
                } else {
                    reply.accept(sender, () -> lines.forEach(sender::sendMessage));
                }
            }, () -> reply.accept(sender,
                () -> sender.sendMessage(Component.text(name + " left before the view was taken", SourbyCraftColors.DIM))));
        if (scheduled == null) {
            sender.sendMessage(Component.text(name + " is being removed", SourbyCraftColors.DIM));
        }
    }
}
