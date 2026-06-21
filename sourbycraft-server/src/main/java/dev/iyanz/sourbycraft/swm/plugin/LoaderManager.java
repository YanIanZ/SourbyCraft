package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;

import java.util.*;
import java.util.concurrent.*;

public final class LoaderManager {

    private final Map<String, SlimeLoader> loaders = new ConcurrentHashMap<>();
    private SlimeLoader defaultLoader;

    public LoaderManager() {
        this("slime_worlds");
    }

    public LoaderManager(String fileDir) {
        register("file", new FileLoader(fileDir));
    }

    public void register(String key, SlimeLoader loader) {
        loaders.put(key.toLowerCase(java.util.Locale.ROOT), loader);
        if (defaultLoader == null) defaultLoader = loader;
    }

    public SlimeLoader getLoader(String key) {
        return loaders.getOrDefault(key.toLowerCase(java.util.Locale.ROOT), defaultLoader);
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
