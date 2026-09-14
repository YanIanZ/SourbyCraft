package dev.iyanz.sourbycraft.testplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
public final class StressCommands implements CommandExecutor, TabCompleter {

    /** Entities created per tick. Enough to build load quickly, small enough not to stall. */
    private static final int BATCH = 200;
    private static final int MAX_COUNT = 200_000;

    private final Plugin plugin;

    public StressCommands(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "massspawn" -> this.massSpawn(sender, args);
            case "flyspeed" -> this.flySpeed(sender, args);
            default -> false;
        };
    }

    /** {@code /massspawn <type> <x> <y> <z> <count>} — coordinates accept {@code ~}. */
    private boolean massSpawn(final CommandSender sender, final String[] args) {
        if (args.length != 5) {
            sender.sendMessage("Usage: /massspawn <type> <x> <y> <z> <count>   (~ allowed, e.g. arrow ~ ~ ~ 100)");
            return true;
        }
        final EntityType type;
        try {
            type = EntityType.valueOf(args[0].toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
        } catch (final IllegalArgumentException unknown) {
            sender.sendMessage("Unknown entity type: " + args[0]);
            return true;
        }
        if (!type.isSpawnable()) {
            sender.sendMessage(type.name() + " cannot be spawned directly.");
            return true;
        }

        final Location origin = sender instanceof Player player ? player.getLocation()
            : Bukkit.getWorlds().getFirst().getSpawnLocation();
        final double x, y, z;
        final int count;
        try {
            x = relative(args[1], origin.getX());
            y = relative(args[2], origin.getY());
            z = relative(args[3], origin.getZ());
            count = Integer.parseInt(args[4]);
        } catch (final NumberFormatException bad) {
            sender.sendMessage("Coordinates must be numbers or ~offset, and count must be an integer.");
            return true;
        }
        if (count < 1 || count > MAX_COUNT) {
            sender.sendMessage("Count must be between 1 and " + MAX_COUNT + ".");
            return true;
        }

        final World world = origin.getWorld();
        final Location target = new Location(world, x, y, z);
        sender.sendMessage("Spawning " + count + " " + type.name() + " at "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z) + " in batches of " + BATCH + ".");

        // getRegionScheduler runs the task on whichever region owns the target chunk, which
        // is the only thread allowed to create entities there.
        final int[] remaining = {count};
        Bukkit.getRegionScheduler().runAtFixedRate(this.plugin, world,
            target.getBlockX() >> 4, target.getBlockZ() >> 4, task -> {
                final int batch = Math.min(BATCH, remaining[0]);
                for (int i = 0; i < batch; i++) {
                    world.spawnEntity(target, type);
                }
                remaining[0] -= batch;
                if (remaining[0] <= 0) {
                    task.cancel();
                    sender.sendMessage("Spawned " + count + " " + type.name() + ".");
                }
            }, 1L, 1L);
        return true;
    }

    /** {@code /flyspeed <0.0-1.0> [player]} — raises travel speed for extreme-range testing. */
    private boolean flySpeed(final CommandSender sender, final String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("Usage: /flyspeed <0.0-1.0> [player]");
            return true;
        }
        final float speed;
        try {
            speed = Float.parseFloat(args[0]);
        } catch (final NumberFormatException bad) {
            sender.sendMessage("Speed must be a number between 0.0 and 1.0.");
            return true;
        }
        if (!(speed >= 0.0F) || speed > 1.0F) {         // Rejects NaN as well as out of range.
            sender.sendMessage("Speed must be between 0.0 and 1.0 (vanilla default is 0.1).");
            return true;
        }
        final Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1])
            : sender instanceof Player self ? self : null;
        if (target == null) {
            sender.sendMessage(args.length == 2 ? "No such player online." : "Console must name a player.");
            return true;
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
        return true;
    }

    private static double relative(final String token, final double origin) {
        if (token.startsWith("~")) {
            return token.length() == 1 ? origin : origin + Double.parseDouble(token.substring(1));
        }
        return Double.parseDouble(token);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (command.getName().equalsIgnoreCase("flyspeed") && args.length == 1) {
            return List.of("0.1", "0.5", "1.0");
        }
        if (command.getName().equalsIgnoreCase("massspawn")) {
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
        }
        return List.of();
    }
}
