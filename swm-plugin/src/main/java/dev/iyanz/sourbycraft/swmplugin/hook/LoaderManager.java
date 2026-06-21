package dev.iyanz.sourbycraft.swmplugin.hook;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import dev.iyanz.sourbycraft.swm.loader.MysqlLoader;
import dev.iyanz.sourbycraft.swm.loader.MongoLoader;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoaderManager {
    private final Map<String, SlimeLoader> loaders = new ConcurrentHashMap<>();
    private final String defaultLoaderType;
    private final Logger logger;

    public LoaderManager(ConfigurationSection config, Logger logger) {
        this.logger = logger;
        ConfigurationSection loaderSection = config.getConfigurationSection("swm.loader");
        this.defaultLoaderType = loaderSection != null
                ? loaderSection.getString("type", "file")
                : "file";

        registerFileLoader(config);
        registerMysqlLoader(config);
        registerMongoLoader(config);

        logger.info("Loaded " + loaders.size() + " SWM loader(s), default: " + defaultLoaderType);
    }

    private void registerFileLoader(ConfigurationSection config) {
        try {
            String path = config.getString("swm.loader.file.path", "slime_worlds");
            loaders.put("file", new FileLoader(path));
        } catch (Throwable e) {
            logger.log(Level.WARNING, "Failed to initialize FileLoader: " + e.getMessage());
        }
    }

    private void registerMysqlLoader(ConfigurationSection config) {
        // SourbyCraft v10.3 — skip silently if MySQL driver / HikariCP not on classpath
        try {
            Class.forName("com.zaxxer.hikari.HikariDataSource");
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            String host = config.getString("swm.loader.mysql.host", "127.0.0.1");
            int port = config.getInt("swm.loader.mysql.port", 3306);
            String database = config.getString("swm.loader.mysql.database", "slime_worlds");
            String username = config.getString("swm.loader.mysql.username", "");
            String password = config.getString("swm.loader.mysql.password", "");
            boolean useSsl = config.getBoolean("swm.loader.mysql.use-ssl", false);
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?autoReconnect=true&useSSL=" + useSsl;

            if (!username.isEmpty() && !database.isEmpty()) {
                loaders.put("mysql", new MysqlLoader(url, host, port, database, useSsl, username, password));
                logger.info("MySQL loader registered");
            }
        } catch (Throwable e) {
            logger.log(Level.WARNING, "Failed to initialize MysqlLoader: " + e.getMessage());
        }
    }

    private void registerMongoLoader(ConfigurationSection config) {
        // SourbyCraft v10.3 — skip silently if MongoDB driver not on classpath
        try {
            Class.forName("com.mongodb.MongoException");
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            String host = config.getString("swm.loader.mongo.host", "127.0.0.1");
            int port = config.getInt("swm.loader.mongo.port", 27017);
            String database = config.getString("swm.loader.mongo.database", "slime_worlds");
            String collection = config.getString("swm.loader.mongo.collection", "worlds");
            String username = config.getString("swm.loader.mongo.username", "");
            String password = config.getString("swm.loader.mongo.password", "");
            String authSource = config.getString("swm.loader.mongo.auth-source", "admin");

            if (!database.isEmpty()) {
                loaders.put("mongo", new MongoLoader(database, collection, username, password,
                        authSource, host, port, null));
                logger.info("MongoDB loader registered");
            }
        } catch (Throwable e) {
            logger.log(Level.WARNING, "Failed to initialize MongoLoader: " + e.getMessage());
        }
    }

    public SlimeLoader getDefaultLoader() {
        SlimeLoader loader = loaders.get(defaultLoaderType);
        if (loader == null && !loaders.isEmpty()) {
            return loaders.values().iterator().next();
        }
        return loader;
    }

    public SlimeLoader getLoader(String type) {
        return loaders.get(type);
    }

    public Map<String, SlimeLoader> getLoaders() {
        return Collections.unmodifiableMap(loaders);
    }
}
