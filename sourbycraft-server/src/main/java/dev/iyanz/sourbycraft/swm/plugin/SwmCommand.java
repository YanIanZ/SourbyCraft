package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
            sendMessage(sender, Component.text("Usage: /swm list|load|save|info", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "load" -> handleLoad(sender, args);
            case "save" -> handleSave(sender, args);
            case "info" -> handleInfo(sender);
            default -> sendMessage(sender, Component.text("Unknown subcommand. Use /swm list|load|save|info", NamedTextColor.RED));
        }
        return true;
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
            return List.of("list", "load", "save", "info").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
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
