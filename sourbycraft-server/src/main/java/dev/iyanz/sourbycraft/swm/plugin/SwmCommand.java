package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SwmCommand implements CommandExecutor, TabCompleter {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[SWM]", NamedTextColor.DARK_GRAY, net.kyori.adventure.text.format.TextDecoration.BOLD))
            .append(Component.text(" » ", NamedTextColor.GRAY))
            .build();

    private final AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();
    private final LoaderManager loaderManager;

    public SwmCommand(LoaderManager loaderManager) {
        this.loaderManager = loaderManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendMessage(sender, Component.text("Usage: /swm list|load|save|info|inspect|delete|platform", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> handleList(sender);
            case "load" -> handleLoad(sender, args);
            case "save" -> handleSave(sender, args);
            case "info" -> handleInfo(sender);
            case "inspect" -> handleInspect(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "platform" -> handlePlatform(sender, args);
            default -> sendMessage(sender, Component.text("Unknown subcommand. Use /swm list|load|save|info|inspect|delete|platform", NamedTextColor.RED));
        }
        return true;
    }

    /**
     * Emergency platform builder for empty worlds. Plants a square slab of safe
     * blocks beneath the operator (or at given coords) so they can recover from
     * a "no safe blocks" / void-spawn situation without WorldEdit. Cleans up
     * any non-air blocks in the 3-block headspace above the platform so the
     * teleport-target spot is clear.
     */
    private void handlePlatform(CommandSender sender, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("sourbycraft.swm.platform")) {
            sendMessage(sender, Component.text("Insufficient permission.", NamedTextColor.RED));
            return;
        }
        Location target;
        int radius = 4;
        Material material = Material.GRASS_BLOCK;
        if (args.length >= 5) {
            World w = Bukkit.getWorld(args[1]);
            if (w == null) {
                sendMessage(sender, Component.text("Unknown world: " + args[1], NamedTextColor.RED));
                return;
            }
            try {
                target = new Location(w,
                        Integer.parseInt(args[2]),
                        Integer.parseInt(args[3]),
                        Integer.parseInt(args[4]));
            } catch (NumberFormatException e) {
                sendMessage(sender, Component.text("Usage: /swm platform <world> <x> <y> <z> [radius] [material]", NamedTextColor.YELLOW));
                return;
            }
            if (args.length >= 6) {
                try { radius = Math.max(1, Math.min(32, Integer.parseInt(args[5]))); }
                catch (NumberFormatException ignored) {}
            }
            if (args.length >= 7) {
                Material m = Material.matchMaterial(args[6]);
                if (m != null && m.isBlock()) material = m;
            }
        } else if (sender instanceof Player p) {
            target = p.getLocation();
            if (args.length >= 2) {
                try { radius = Math.max(1, Math.min(32, Integer.parseInt(args[1]))); }
                catch (NumberFormatException ignored) {}
            }
            if (args.length >= 3) {
                Material m = Material.matchMaterial(args[2]);
                if (m != null && m.isBlock()) material = m;
            }
        } else {
            sendMessage(sender, Component.text(
                    "Usage from console: /swm platform <world> <x> <y> <z> [radius] [material]",
                    NamedTextColor.YELLOW));
            return;
        }

        int cx = target.getBlockX();
        int cy = target.getBlockY();
        int cz = target.getBlockZ();
        int placed = 0;
        World world = target.getWorld();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Block floor = world.getBlockAt(cx + dx, cy - 1, cz + dz);
                if (floor.getType() != material) {
                    floor.setType(material, false);
                    placed++;
                }
                // Clear the 3-block headspace above the floor so the teleport
                // target spot satisfies SS2's safe-block check.
                for (int dy = 0; dy < 3; dy++) {
                    Block above = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (above.getType() != Material.AIR) above.setType(Material.AIR, false);
                }
            }
        }
        sendMessage(sender, Component.text("Built " + (radius * 2 + 1) + "x" + (radius * 2 + 1)
                + " " + material.name().toLowerCase(java.util.Locale.ROOT) + " platform at "
                + world.getName() + " " + cx + "," + (cy - 1) + "," + cz + " (" + placed + " blocks placed).",
                NamedTextColor.GREEN));
    }

    private void handleInspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, Component.text("Usage: /swm inspect <world>", NamedTextColor.YELLOW));
            return;
        }

        String worldName = args[1];
        SlimeLoader loader = loaderManager.getDefault();
        if (loader == null) {
            sendMessage(sender, Component.text("No loader configured.", NamedTextColor.RED));
            return;
        }

        // First check if already loaded so we can report the live in-memory state
        // including any chunks that were promoted from NMS via loadCallback().
        SlimeWorldInstance loaded = api.getLoadedWorld(worldName);
        if (loaded instanceof dev.iyanz.sourbycraft.swm.server.SlimeInMemoryWorld imw) {
            int live = imw.getChunkStorage().size();
            sendMessage(sender, Component.text("Loaded world '" + worldName + "':", NamedTextColor.GREEN));
            sendMessage(sender, Component.text("  In-memory chunks: " + live, NamedTextColor.WHITE));
            sendMessage(sender, Component.text("  read-only: " + imw.isReadOnly(), NamedTextColor.GRAY));
            return;
        }

        try {
            if (!loader.worldExists(worldName)) {
                sendMessage(sender, Component.text("World '" + worldName + "' does not exist on the loader.", NamedTextColor.GRAY));
                return;
            }
            SlimePropertyMap props = new SlimePropertyMap();
            SlimeWorld world = api.readWorld(loader, worldName, true, props);
            int onDisk = world.getChunks() == null ? 0 : world.getChunks().size();
            sendMessage(sender, Component.text("On-disk world '" + worldName + "':", NamedTextColor.GREEN));
            sendMessage(sender, Component.text("  Persisted chunks: " + onDisk, NamedTextColor.WHITE));
            if (onDisk == 0) {
                sendMessage(sender, Component.text("  ⚠ World is empty — use `/swm delete " + worldName
                        + "` and re-trigger the originating plugin (e.g. /is admin schematic …) to repopulate.",
                        NamedTextColor.YELLOW));
            }
        } catch (Exception e) {
            sendMessage(sender, Component.text("Failed to inspect world: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, Component.text("Usage: /swm delete <world>", NamedTextColor.YELLOW));
            return;
        }
        String worldName = args[1];
        if (!sender.isOp() && !sender.hasPermission("sourbycraft.swm.delete")) {
            sendMessage(sender, Component.text("Insufficient permission.", NamedTextColor.RED));
            return;
        }
        // Refuse to delete a currently-loaded world. The operator must first
        // unload via Bukkit (`/swm` does not own world lifecycle) or remove via
        // SS2 (`/is admin delete <player>`) to release the world reference.
        if (api.getLoadedWorld(worldName) != null) {
            sendMessage(sender, Component.text(
                    "World '" + worldName + "' is still loaded. Unload it first (e.g. /is admin delete) before deletion.",
                    NamedTextColor.RED));
            return;
        }
        SlimeLoader loader = loaderManager.getDefault();
        if (loader == null) {
            sendMessage(sender, Component.text("No loader configured.", NamedTextColor.RED));
            return;
        }
        try {
            if (!loader.worldExists(worldName)) {
                sendMessage(sender, Component.text("World '" + worldName + "' does not exist on the loader.", NamedTextColor.GRAY));
                return;
            }
            loader.deleteWorld(worldName);
            sendMessage(sender, Component.text("Deleted '" + worldName + "' from loader. Re-trigger creation via the owning plugin to rebuild.", NamedTextColor.GREEN));
        } catch (Exception e) {
            sendMessage(sender, Component.text("Failed to delete world: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleList(CommandSender sender) {
        SlimeLoader loader = loaderManager.getDefault();
        if (loader == null) {
            sendMessage(sender, Component.text("No loader configured.", NamedTextColor.RED));
            return;
        }

        try {
            List<String> worlds = loader.listWorlds();
            if (worlds.isEmpty()) {
                sendMessage(sender, Component.text("No .slime worlds found.", NamedTextColor.GRAY));
                return;
            }

            sendMessage(sender, Component.text("Available worlds:", NamedTextColor.GREEN));
            for (String name : worlds) {
                boolean loaded = api.getLoadedWorld(name) != null;
                Component status = loaded
                        ? Component.text(" [loaded]", NamedTextColor.GREEN)
                        : Component.text(" [unloaded]", NamedTextColor.GRAY);
                sendMessage(sender, Component.text("  - " + name, NamedTextColor.WHITE).append(status));
            }
        } catch (IOException e) {
            sendMessage(sender, Component.text("Error listing worlds: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleLoad(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, Component.text("Usage: /swm load <world>", NamedTextColor.YELLOW));
            return;
        }

        String worldName = args[1];
        SlimeLoader loader = loaderManager.getDefault();

        if (loader == null) {
            sendMessage(sender, Component.text("No loader configured.", NamedTextColor.RED));
            return;
        }

        try {
            SlimePropertyMap propertyMap = new SlimePropertyMap();
            SlimeWorld world = api.readWorld(loader, worldName, false, propertyMap);
            api.loadWorld(world, true);
            sendMessage(sender, Component.text("World '" + worldName + "' loaded.", NamedTextColor.GREEN));
        } catch (Exception e) {
            sendMessage(sender, Component.text("Failed to load world: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleSave(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, Component.text("Usage: /swm save <world>", NamedTextColor.YELLOW));
            return;
        }

        String worldName = args[1];
        SlimeWorldInstance instance = api.getLoadedWorld(worldName);

        if (instance == null) {
            sendMessage(sender, Component.text("World '" + worldName + "' is not loaded.", NamedTextColor.RED));
            return;
        }

        try {
            api.saveWorld(instance);
            sendMessage(sender, Component.text("World '" + worldName + "' saved.", NamedTextColor.GREEN));
        } catch (IOException e) {
            sendMessage(sender, Component.text("Failed to save world: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleInfo(CommandSender sender) {
        List<SlimeWorldInstance> loaded = api.getLoadedWorlds();
        sendMessage(sender, Component.text("SWM Status:", NamedTextColor.GREEN));
        sendMessage(sender, Component.text("  Loaded worlds: " + loaded.size(), NamedTextColor.WHITE));
        for (SlimeWorldInstance world : loaded) {
            sendMessage(sender, Component.text("    - " + world.getName()
                    + " (readOnly: " + world.isReadOnly() + ")", NamedTextColor.GRAY));
        }
        sendMessage(sender, Component.text("  Default loader: " +
                (loaderManager.getDefault() != null ? "file" : "none"), NamedTextColor.WHITE));
    }

    private void sendMessage(CommandSender sender, Component message) {
        sender.sendMessage(PREFIX.append(message));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("list", "load", "save", "info", "inspect", "delete", "platform").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("inspect") || args[0].equalsIgnoreCase("delete"))) {
            try {
                SlimeLoader loader = loaderManager.getDefault();
                if (loader != null) {
                    return loader.listWorlds().stream()
                            .filter(s -> s.startsWith(args[1]))
                            .collect(Collectors.toList());
                }
            } catch (IOException ignored) {}
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("load")) {
            try {
                SlimeLoader loader = loaderManager.getDefault();
                if (loader != null) {
                    return loader.listWorlds().stream()
                            .filter(s -> s.startsWith(args[1]))
                            .collect(Collectors.toList());
                }
            } catch (IOException e) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("SWM tab-complete: failed to list worlds: " + e.getMessage());
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("save")) {
            return api.getLoadedWorlds().stream()
                    .map(SlimeWorld::getName)
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }

        return List.of();
    }
}
