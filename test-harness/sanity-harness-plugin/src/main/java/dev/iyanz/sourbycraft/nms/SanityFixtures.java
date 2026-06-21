package dev.iyanz.sourbycraft.nms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Per-plugin sanity invocations. Each branch uses reflection (avoids hard compile-time
 * deps on the target plugins, which are downloaded at test time). A throw propagates
 * up to the caller and is captured as a fail row.
 */
public final class SanityFixtures {
    private SanityFixtures() {}

    public static String invoke(String pluginName, JavaPlugin harness) throws Throwable {
        switch (pluginName) {
            case "NBTAPI":             return invokeNbtApi();
            case "Citizens":           return invokeCitizens(harness);
            case "DecentHolograms":    return invokeDecentHolograms(harness);
            case "FastAsyncWorldEdit": return invokeFawe();
            default:                   return "unknown plugin: " + pluginName;
        }
    }

    private static String invokeNbtApi() throws Throwable {
        // de.tr7zw.changeme.nbtapi.NBT.parseNBT("{Foo:1b}") -> NBTContainer
        Class<?> nbtClass = Class.forName("de.tr7zw.changeme.nbtapi.NBT");
        Method parseNBT = nbtClass.getMethod("parseNBT", String.class);
        Object container = parseNBT.invoke(null, "{Foo:1b}");
        if (container == null) {
            throw new IllegalStateException("NBT.parseNBT returned null");
        }
        return "NBTContainer: " + container.toString();
    }

    private static String invokeCitizens(JavaPlugin harness) throws Throwable {
        Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
        Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
        if (registry == null) {
            throw new IllegalStateException("CitizensAPI.getNPCRegistry returned null");
        }
        Class<?> entityType = Class.forName("org.bukkit.entity.EntityType");
        Object villager = entityType.getMethod("valueOf", String.class).invoke(null, "VILLAGER");
        Method createNPC = registry.getClass().getMethod("createNPC", entityType, String.class);
        Object npc = createNPC.invoke(registry, villager, "TestNPC");
        if (npc == null) {
            throw new IllegalStateException("createNPC returned null");
        }
        try {
            Method despawn = npc.getClass().getMethod("destroy");
            despawn.invoke(npc);
        } catch (NoSuchMethodException ignored) {}
        return "NPC created + destroyed";
    }

    private static String invokeDecentHolograms(JavaPlugin harness) throws Throwable {
        World world = Bukkit.getWorlds().get(0);
        Location loc = new Location(world, 0.5, 100.0, 0.5);
        Class<?> dhApi = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
        Method create = dhApi.getMethod("createHologram", String.class, Location.class, java.util.List.class);
        Object holo = create.invoke(null, "sanity-test-" + System.nanoTime(), loc, java.util.List.of("sanity"));
        if (holo == null) {
            throw new IllegalStateException("DHAPI.createHologram returned null");
        }
        try {
            Method delete = holo.getClass().getMethod("delete");
            delete.invoke(holo);
        } catch (NoSuchMethodException ignored) {}
        return "Hologram created + deleted";
    }

    private static String invokeFawe() throws Throwable {
        Class<?> we = Class.forName("com.sk89q.worldedit.WorldEdit");
        Object instance = we.getMethod("getInstance").invoke(null);
        if (instance == null) {
            throw new IllegalStateException("WorldEdit.getInstance returned null");
        }
        Method getVersion = instance.getClass().getMethod("getVersion");
        Object version = getVersion.invoke(instance);
        return "WorldEdit version: " + version;
    }
}
