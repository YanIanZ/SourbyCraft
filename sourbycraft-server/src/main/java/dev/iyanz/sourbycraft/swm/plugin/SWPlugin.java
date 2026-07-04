package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.exceptions.CorruptedWorldException;
import dev.iyanz.sourbycraft.swm.api.exceptions.NewerFormatException;
import dev.iyanz.sourbycraft.swm.api.exceptions.UnknownWorldException;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.swm.server.AdvancedSlimePaperImpl;
import dev.iyanz.sourbycraft.swm.server.SwmIoExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SWPlugin extends JavaPlugin {

    private static final AdvancedSlimePaperAPI ASP = AdvancedSlimePaperAPI.instance();

    private final Map<String, SlimeWorld> worldsToLoad = new HashMap<>();
    private LoaderManager loaderManager;

    private static SwmIoExecutor ioExecutor;

    public static SwmIoExecutor ioExecutor() {
        return ioExecutor;
    }

    public LoaderManager getLoaderManager() {
        return loaderManager;
    }

    @Override
    public void onLoad() {
        ioExecutor = new SwmIoExecutor();

        this.loaderManager = new LoaderManager(SourbyCraftConfig.swmFileDir);
        registerOptionalLoaders(this.loaderManager);

        List<String> erroredWorlds = loadWorlds();

        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream("server.properties")) {
                props.load(fis);
            }
            String defaultWorldName = props.getProperty("level-name");

            if (erroredWorlds.contains(defaultWorldName)) {
                getSLF4JLogger().error("Shutting down server, as the default world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowNether() && erroredWorlds.contains(defaultWorldName + "_nether")) {
                getSLF4JLogger().error("Shutting down server, as the default nether world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowEnd() && erroredWorlds.contains(defaultWorldName + "_the_end")) {
                getSLF4JLogger().error("Shutting down server, as the default end world could not be loaded.");
                Bukkit.getServer().shutdown();
            }

            SlimeWorld defaultWorld = worldsToLoad.get(defaultWorldName);
            SlimeWorld netherWorld = getServer().getAllowNether() ? worldsToLoad.get(defaultWorldName + "_nether") : null;
            SlimeWorld endWorld = getServer().getAllowEnd() ? worldsToLoad.get(defaultWorldName + "_the_end") : null;

            SlimeNMSBridge.instance().setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            getSLF4JLogger().error("Failed to retrieve default world name", ex);
        }
    }

    @Override
    public void onEnable() {
        PluginCommand command = getCommand("swm");
        if (command != null) {
            SwmCommand cmd = new SwmCommand(loaderManager);
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

        // Heap-leak fix: AdvancedSlimePaperImpl#loadedWorlds (and the entire
        // chunkStorage of each SlimeWorldInstance) was retained forever because
        // Bukkit's WorldUnloadEvent was never bridged into onWorldUnload. SS2
        // unloads SWM worlds aggressively when islands go cold; without this
        // listener every unloaded island stayed pinned in heap.
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorldUnload(WorldUnloadEvent event) {
                AdvancedSlimePaperImpl.instance().onWorldUnload(event.getWorld().getName());
            }
        }, this);

        // SourbyCraft - emoji shortcode chat translator. Disabled at runtime
        // via `emoji.shortcodes.enabled: false` in sourbycraft.yml; the
        // listener itself stays registered so toggling the config takes effect
        // on the next chat message rather than requiring a server restart.
        Bukkit.getPluginManager().registerEvents(new dev.iyanz.sourbycraft.chat.EmojiChatListener(), this);
        dev.iyanz.sourbycraft.chat.SignSanitizer.register(this);

        // SourbyCraft - entity stacker. Merges same-type living mob spawns
        // within a configured radius into a stacked representative with PDC
        // count + on-death drops/xp multiplication. Off by default; enable via
        // `stacker.enabled: true` in sourbycraft.yml.
        dev.iyanz.sourbycraft.wildstacker.EntityStacker.register(this);
        dev.iyanz.sourbycraft.antixray.OreReveal.register(this);
        dev.iyanz.sourbycraft.perf.ConfigBridge.register(this);
        dev.iyanz.sourbycraft.perf.LagLimits.register(this);
        dev.iyanz.sourbycraft.perf.OwnerProtection.register(this);

        worldsToLoad.values().stream()
                .filter(slimeWorld -> Objects.isNull(Bukkit.getWorld(slimeWorld.getName())))
                .forEach(slimeWorld -> {
                    try {
                        ASP.loadWorld(slimeWorld, true);
                    } catch (RuntimeException exception) {
                        getSLF4JLogger().error("Failed to load world: {}", slimeWorld.getName(), exception);
                    }
                });

        worldsToLoad.clear();
    }

    @Override
    public void onDisable() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            if (!world.isReadOnly()) {
                saves.add(ASP.saveWorldAsync(world));
            }
        }
        // Wait for all saves to complete with per-world timeout so a stuck save
        // cannot hang server shutdown indefinitely.
        for (CompletableFuture<Void> save : saves) {
            try {
                save.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                getSLF4JLogger().warn("Slime world save did not complete within 30s; abandoning to allow shutdown");
                save.cancel(true);
            } catch (Exception ex) {
                getLogger().severe("Failed to save world: " + ex.getMessage());
            }
        }
        // Unload all worlds
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            try {
                Bukkit.unloadWorld(world.getName(), false);
            } catch (Exception e) {
                getSLF4JLogger().warn("Failed to unload world {}: {}", world.getName(), e.getMessage());
            }
        }

        if (ioExecutor != null) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }
    }

    /**
     * Register optional MySQL / MongoDB / API / Redis SlimeLoaders if the operator
     * has filled credentials in {@code sourbycraft.yml swm.loader.*}. Each loader is
     * registered under its canonical short name so SSB-SWM (and any other bridge
     * plugin) can resolve them by type via {@link LoaderManager#getLoader(String)}.
     *
     * <p>If {@code swm.loader.type} names a non-file loader, the corresponding
     * registered loader becomes the default (replacing the file loader installed
     * by {@code LoaderManager}'s ctor). Boot order: this runs in {@code onLoad}
     * so even {@code worldsToLoad} sees the right default.
     */
    private static void registerOptionalLoaders(LoaderManager manager) {
        org.bukkit.configuration.file.YamlConfiguration cfg = SourbyCraftConfig.config;
        if (cfg == null) return;
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("SourbyCraftSWM");

        org.bukkit.configuration.ConfigurationSection mysql = cfg.getConfigurationSection("swm.loader.mysql");
        if (mysql != null && !mysql.getString("username", "").isEmpty()) {
            try {
                String url = mysql.getString("url",
                        "jdbc:mysql://{host}:{port}/{database}?autoReconnect=true&allowMultiQueries=true&useSSL={usessl}");
                manager.register("mysql", new dev.iyanz.sourbycraft.swm.loader.MysqlLoader(
                        url,
                        mysql.getString("host", "127.0.0.1"),
                        mysql.getInt("port", 3306),
                        mysql.getString("database", "slime_worlds"),
                        mysql.contains("use-ssl") ? mysql.getBoolean("use-ssl") : mysql.getBoolean("useSSL", false),
                        mysql.getString("username"),
                        mysql.getString("password", "")));
                log.info("[SWP] Registered mysql SlimeLoader from sourbycraft.yml swm.loader.mysql.");
            } catch (Throwable t) {
                log.warn("[SWP] Failed to register mysql SlimeLoader: {}", t.getMessage());
            }
        }

        org.bukkit.configuration.ConfigurationSection mongo = cfg.getConfigurationSection("swm.loader.mongo");
        // Accept legacy `swm.loader.mongodb` sub-key for back-compat.
        if (mongo == null) mongo = cfg.getConfigurationSection("swm.loader.mongodb");
        if (mongo != null && (!mongo.getString("username", "").isEmpty() || !mongo.getString("uri", "").isEmpty())) {
            try {
                manager.register("mongo", new dev.iyanz.sourbycraft.swm.loader.MongoLoader(
                        mongo.getString("database", "slime_worlds"),
                        mongo.getString("collection", "worlds"),
                        mongo.getString("username", null),
                        mongo.getString("password", null),
                        mongo.getString("auth-source", mongo.getString("auth", "admin")),
                        mongo.getString("host", "127.0.0.1"),
                        mongo.getInt("port", 27017),
                        mongo.getString("uri", "")));
                log.info("[SWP] Registered mongo SlimeLoader from sourbycraft.yml swm.loader.mongo.");
            } catch (Throwable t) {
                log.warn("[SWP] Failed to register mongo SlimeLoader: {}", t.getMessage());
            }
        }

        org.bukkit.configuration.ConfigurationSection api = cfg.getConfigurationSection("swm.loader.api");
        if (api != null && !api.getString("uri", api.getString("url", "")).isEmpty()) {
            try {
                manager.register("api", new dev.iyanz.sourbycraft.swm.loader.APILoader(
                        api.getString("uri", api.getString("url", "")),
                        api.getString("username", ""),
                        api.getString("token", ""),
                        api.getBoolean("ignore-ssl-certificate", false)));
                log.info("[SWP] Registered api SlimeLoader from sourbycraft.yml swm.loader.api.");
            } catch (Throwable t) {
                log.warn("[SWP] Failed to register api SlimeLoader: {}", t.getMessage());
            }
        }

        // Redis loader: SWP ships the config block but no loader class yet.
        // Log a notice so operators know type=redis falls back to default.
        org.bukkit.configuration.ConfigurationSection redis = cfg.getConfigurationSection("swm.loader.redis");
        if (redis != null && !redis.getString("uri", "").isEmpty()) {
            log.warn("[SWP] Redis loader is configured ({}) but the SWP redis backend is not implemented yet; "
                    + "requests for type=redis will fall through to the default loader.", redis.getString("uri"));
        }

        // Set default based on swm.loader.type. Accept `mongodb` alias.
        String type = cfg.getString("swm.loader.type", "file").toLowerCase(java.util.Locale.ROOT);
        if (type.equals("mongodb")) type = "mongo";
        if (!type.equals("file")) {
            dev.iyanz.sourbycraft.swm.api.SlimeLoader picked = manager.getLoader(type);
            if (picked != null && picked != manager.getDefault()) {
                manager.setDefault(picked);
                log.info("[SWP] Default SlimeLoader set to '{}' per sourbycraft.yml swm.loader.type.", type);
            } else if (picked == null) {
                log.warn("[SWP] swm.loader.type='{}' requested but no matching loader registered; "
                        + "default stays as file. Check the corresponding sub-block credentials.", type);
            }
        }
    }

    private List<String> loadWorlds() {
        List<String> erroredWorlds = new ArrayList<>();
        SlimeLoader loader = loaderManager.getDefault();

        if (loader == null) return erroredWorlds;

        try {
            // SourbyCraft - parallel SWM world read at boot. Previously each
            // listWorlds() entry was readWorld'd sequentially on main thread —
            // disk/db latency × N worlds. Now: virtual-thread pool reads all
            // worlds concurrently; main thread waits for all + collects into
            // worldsToLoad. Read itself is pure IO + deserialize, no Bukkit
            // API → thread-safe.
            java.util.List<String> names = loader.listWorlds();
            java.util.Map<String, SlimeWorld> readResults =
                    new java.util.concurrent.ConcurrentHashMap<>();
            java.util.Map<String, Throwable> readErrors =
                    new java.util.concurrent.ConcurrentHashMap<>();
            try (java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (String worldName : names) {
                    futures.add(pool.submit(() -> {
                        try {
                            SlimePropertyMap pm = new SlimePropertyMap();
                            SlimeWorld w = ASP.readWorld(loader, worldName, false, pm);
                            readResults.put(worldName, w);
                        } catch (Throwable t) {
                            readErrors.put(worldName, t);
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try { f.get(); } catch (Throwable ignored) {}
                }
            }
            for (java.util.Map.Entry<String, SlimeWorld> e : readResults.entrySet()) {
                worldsToLoad.put(e.getKey(), e.getValue());
            }
            for (java.util.Map.Entry<String, Throwable> e : readErrors.entrySet()) {
                Throwable ex = e.getValue();
                String worldName = e.getKey();
                String message;
                if (ex instanceof UnknownWorldException) {
                    message = "world does not exist";
                } else if (ex instanceof NewerFormatException) {
                    message = "world is serialized in a newer Slime Format version";
                } else if (ex instanceof CorruptedWorldException) {
                    message = "world seems to be corrupted";
                } else if (ex.getMessage() != null) {
                    message = ex.getMessage();
                } else {
                    message = ex.getClass().getSimpleName();
                }
                getSLF4JLogger().error("Failed to load world {}{}", worldName, message.isEmpty() ? "." : ": " + message);
                erroredWorlds.add(worldName);
            }
        } catch (IOException e) {
            getSLF4JLogger().error("Failed to list worlds", e);
        }

        return erroredWorlds;
    }
}
