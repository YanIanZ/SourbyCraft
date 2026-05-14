package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;

import java.util.*;
import java.util.concurrent.*;

public final class LoaderManager {

    private final Map<String, SlimeLoader> loaders = new ConcurrentHashMap<>();
    private SlimeLoader defaultLoader;

    public LoaderManager() {
        register("file", new FileLoader("slime_worlds"));
    }

    public void register(String key, SlimeLoader loader) {
        loaders.put(key.toLowerCase(), loader);
        if (defaultLoader == null) defaultLoader = loader;
    }

    public SlimeLoader getLoader(String key) {
        return loaders.getOrDefault(key.toLowerCase(), defaultLoader);
    }

    public SlimeLoader getDefault() {
        return defaultLoader;
    }

    public void setDefault(SlimeLoader loader) {
        defaultLoader = loader;
    }

    public Collection<SlimeLoader> all() {
        return loaders.values();
    }
}
