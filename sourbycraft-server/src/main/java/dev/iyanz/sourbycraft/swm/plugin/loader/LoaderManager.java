package dev.iyanz.sourbycraft.swm.plugin.loader;

import java.util.*;
import java.util.concurrent.*;

public final class LoaderManager {

    private static final Map<String, SlimeLoader> loaders = new ConcurrentHashMap<>();
    private static SlimeLoader defaultLoader;

    public static void register(String key, SlimeLoader loader) {
        loaders.put(key.toLowerCase(), loader);
        if (defaultLoader == null) defaultLoader = loader;
    }

    public static SlimeLoader get(String key) {
        return loaders.getOrDefault(key.toLowerCase(), defaultLoader);
    }

    public static SlimeLoader getDefault() {
        return defaultLoader;
    }

    public static void setDefault(SlimeLoader loader) {
        defaultLoader = loader;
    }

    public static Collection<SlimeLoader> all() {
        return loaders.values();
    }

    public static void init() {
        register("file", new FileLoader("slime_worlds"));
    }

    private LoaderManager() {}
}
