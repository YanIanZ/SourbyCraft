package dev.iyanz.sourbycraft.swmplugin.hook;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoaderManager {
    private final Map<String, SlimeLoader> loaders = new ConcurrentHashMap<>();

    public LoaderManager(String fileDir) {
        loaders.put("file", new FileLoader(fileDir));
    }

    public SlimeLoader getLoader(String type) { return loaders.get(type); }
    public void register(String type, SlimeLoader loader) { loaders.put(type, loader); }
}
