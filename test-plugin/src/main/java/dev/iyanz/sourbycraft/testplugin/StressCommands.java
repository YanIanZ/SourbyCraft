package dev.iyanz.sourbycraft.testplugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Load-generation commands for stress testing.
 *
 * <p>Spawning is scheduled on the region that owns the target location and split across
 * ticks. A region-threaded server may only touch world state from the owning region
 * thread, and dropping ten thousand entities into one tick would stall that region badly
 * enough to make the very measurement the load is for meaningless.
 */
public final class StressCommands implements BasicCommand {

    /** Entities created per tick. Enough to build load quickly, small enough not to stall. */
    private static final int BATCH = 200;
    private static final int MAX_COUNT = 200_000;

    private final Plugin plugin;
    private final String name;

    public StressCommands(final Plugin plugin, final String name) {
        this.plugin = plugin;
        this.name = name;
    }

    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        final CommandSender sender = source.getSender();
        switch (this.name) {
            case "massspawn" -> this.massSpawn(sender, args);
            case "flyspeed" -> this.flySpeed(sender, args);
            default -> sender.sendMessage("Unknown stress command: " + this.name);
        }
    }

    /**
     * {@code /massspawn <type> <x> <y> <z> <count> [loop]} — coordinates accept {@code ~}.
     *
     * <p>Spawns the requested count once and stops. Passing a loop interval in ticks repeats
     * it until {@code /massspawn stop}, for sustained pressure rather than a single burst.
     */
    private void massSpawn(final CommandSender sender, final String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            final int cancelled = cancelLoops();
            sender.sendMessage(cancelled == 0 ? "No repeating spawn running."
                : "Stopped " + cancelled + " repeating spawn(s).");
            return;
        }
        if (args.length < 5 || args.length > 6) {
            sender.sendMessage("Usage: /massspawn <type> <x> <y> <z> <count> [loopTicks]");
            sender.sendMessage("  once:   /massspawn arrow ~ ~ ~ 100");
            sender.sendMessage("  repeat: /massspawn arrow ~ ~ ~ 100 40      (every 40 ticks)");
            sender.sendMessage("  stop:   /massspawn stop");
            return;
        }
        final EntityType type;
        try {
            type = EntityType.valueOf(args[0].toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
        } catch (final IllegalArgumentException unknown) {
            sender.sendMessage("Unknown entity type: " + args[0]);
            return;
        }
        if (!type.isSpawnable()) {
            sender.sendMessage(type.name() + " cannot be spawned directly.");
            return;
        }

        final Location origin = sender instanceof Player player ? player.getLocation()
            : Bukkit.getWorlds().getFirst().getSpawnLocation();
        final double x, y, z;
        final int count;
        final int loopTicks;
        try {
            x = relative(args[1], origin.getX());
            y = relative(args[2], origin.getY());
            z = relative(args[3], origin.getZ());
            count = Integer.parseInt(args[4]);
            loopTicks = args.length == 6 ? Integer.parseInt(args[5]) : 0;
        } catch (final NumberFormatException bad) {
            sender.sendMessage("Coordinates must be numbers or ~offset, and count must be an integer.");
            return;
        }
        if (count < 1 || count > MAX_COUNT) {
            sender.sendMessage("Count must be between 1 and " + MAX_COUNT + ".");
            return;
        }

        final World world = origin.getWorld();
        final Location target = new Location(world, x, y, z);
        sender.sendMessage("Spawning " + count + " " + type.name() + " at "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z) + " in batches of " + BATCH + ".");

        // getRegionScheduler runs the task on whichever region owns the target chunk, which
        // is the only thread allowed to create entities there.
        final int[] remaining = {count};
        final var task = Bukkit.getRegionScheduler().runAtFixedRate(this.plugin, world,
            target.getBlockX() >> 4, target.getBlockZ() >> 4, handle -> {
                final int batch = Math.min(BATCH, remaining[0]);
                for (int i = 0; i < batch; i++) {
                    world.spawnEntity(target, type);
                }
                remaining[0] -= batch;
                if (remaining[0] <= 0) {
                    if (loopTicks <= 0) {
                        handle.cancel();
                        LOOPS.remove(handle);
                        sender.sendMessage("Spawned " + count + " " + type.name() + ".");
                    } else {
                        remaining[0] = count;      // Repeat: refill and keep going.
                    }
                }
            }, 1L, loopTicks > 0 ? Math.max(1L, loopTicks) : 1L);
        if (loopTicks > 0) {
            LOOPS.add(task);
            sender.sendMessage("Repeating every " + loopTicks + " ticks. Stop with /massspawn stop.");
        }
    }

    /** Repeating spawns, so they can be stopped without restarting the server. */
    private static final java.util.Set<io.papermc.paper.threadedregions.scheduler.ScheduledTask> LOOPS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static int cancelLoops() {
        int cancelled = 0;
        for (final var task : java.util.Set.copyOf(LOOPS)) {
            task.cancel();
            LOOPS.remove(task);
            cancelled++;
        }
        return cancelled;
    }

    /** {@code /flyspeed <0.0-1.0> [player]} — raises travel speed for extreme-range testing. */
    private void flySpeed(final CommandSender sender, final String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("Usage: /flyspeed <0.0-1.0> [player]");
            return;
        }
        final float speed;
        try {
            speed = Float.parseFloat(args[0]);
        } catch (final NumberFormatException bad) {
            sender.sendMessage("Speed must be a number between 0.0 and 1.0.");
            return;
        }
        if (!(speed >= 0.0F) || speed > 1.0F) {         // Rejects NaN as well as out of range.
            sender.sendMessage("Speed must be between 0.0 and 1.0 (vanilla default is 0.1).");
            return;
        }
        final Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1])
            : sender instanceof Player self ? self : null;
        if (target == null) {
            sender.sendMessage(args.length == 2 ? "No such player online." : "Console must name a player.");
            return;
        }
        // A player's own scheduler owns that player's state wherever their region moves.
        target.getScheduler().run(this.plugin, task -> {
            target.setAllowFlight(true);
            target.setFlySpeed(speed);
            target.sendMessage("Fly speed set to " + speed + ".");
            if (sender != target) {
                sender.sendMessage("Fly speed for " + target.getName() + " set to " + speed + ".");
            }
        }, null);
    }

    private static double relative(final String token, final double origin) {
        if (token.startsWith("~")) {
            return token.length() == 1 ? origin : origin + Double.parseDouble(token.substring(1));
        }
        return Double.parseDouble(token);
    }

    @Override
    public Collection<String> suggest(final CommandSourceStack source, final String[] args) {
        if (this.name.equals("flyspeed") && args.length <= 1) {
            return List.of("0.1", "0.5", "1.0");
        }
        if (this.name.equals("massspawn")) {
            if (args.length == 1) {
                final List<String> types = new ArrayList<>();
                for (final EntityType type : EntityType.values()) {
                    if (type.isSpawnable()) {
                        types.add(type.name().toLowerCase(Locale.ROOT));
                    }
                }
                return types;
            }
            if (args.length >= 2 && args.length <= 4) {
                return List.of("~");
            }
            if (args.length == 5) {
                return List.of("100", "1000", "10000");
            }
            if (args.length == 6) {
                return List.of("0", "20", "40", "100");
            }
        }
        return List.of();
    }
}
