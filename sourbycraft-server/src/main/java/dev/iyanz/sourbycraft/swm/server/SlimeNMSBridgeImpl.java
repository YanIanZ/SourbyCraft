package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;

public class SlimeNMSBridgeImpl implements SlimeNMSBridge {

    private SlimeWorld defaultWorld;
    private SlimeWorld defaultNetherWorld;
    private SlimeWorld defaultEndWorld;

    public static SlimeNMSBridgeImpl instance() {
        return (SlimeNMSBridgeImpl) SlimeNMSBridge.instance();
    }

    @Override
    public SlimeWorldInstance loadOverworldOverride(MinecraftServer server) {
        if (defaultWorld == null) return null;
        SlimeLevelInstance instance = ((SlimeInMemoryWorld) loadInstance(defaultWorld, server, Level.OVERWORLD)).getInstance();
        DimensionDataStorage worldpersistentdata = instance.getDataStorage();
        instance.getCraftServer().scoreboardManager = new org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager(instance.getServer(), instance.getScoreboard());
        // commandStorage skipped — private field, minimal impact on SWM worlds
        return instance.getSlimeInstance();
    }

    @Override
    public SlimeWorldInstance loadNetherOverride(MinecraftServer server) {
        if (defaultNetherWorld == null) return null;
        return loadInstance(defaultNetherWorld, server, Level.NETHER);
    }

    @Override
    public SlimeWorldInstance loadEndOverride(MinecraftServer server) {
        if (defaultEndWorld == null) return null;
        return loadInstance(defaultEndWorld, server, Level.END);
    }

    @Override
    public void setDefaultWorlds(SlimeWorld normal, SlimeWorld nether, SlimeWorld end) {
        this.defaultWorld = normal;
        this.defaultNetherWorld = nether;
        this.defaultEndWorld = end;
    }

    @Override
    public SlimeWorldInstance loadInstance(SlimeWorld world, MinecraftServer server, ResourceKey<Level> dimensionKey) {
        String worldName = world.getName();
        if (Bukkit.getWorld(worldName) != null) {
            throw new IllegalArgumentException("World " + worldName + " already exists!");
        }

        SlimeLevelInstance levelInstance = createCustomWorld(world, server, dimensionKey);
        registerWorld(levelInstance);
        return levelInstance.getSlimeInstance();
    }

    @Override
    public int getCurrentVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Override
    public CompoundTag extractCraftPDC(SlimeWorld world) {
        CompoundTag extraData = world.getExtraData();
        if (extraData != null && extraData.contains("BukkitValues")) {
            return extraData.getCompound("BukkitValues").orElse(new CompoundTag());
        }
        return new CompoundTag();
    }

    private void registerWorld(SlimeLevelInstance levelInstance) {
        MinecraftServer mcServer = MinecraftServer.getServer();
        mcServer.initWorld(levelInstance, levelInstance.serverLevelData, levelInstance.serverLevelData.worldGenOptions());
        mcServer.addLevel(levelInstance);
    }

    private SlimeLevelInstance createCustomWorld(SlimeWorld world, MinecraftServer server, ResourceKey<Level> dimensionKey) {
        SlimeBootstrap bootstrap = new SlimeBootstrap(world);
        PrimaryLevelData worldData = createWorldData(world, server);
        World.Environment environment = getEnvironment(world);
        ResourceKey<LevelStem> stemKey = switch (environment) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> throw new IllegalArgumentException("Unknown dimension: " + environment);
        };

        // Create the SlimeLevelGenerator with the configured biome
        String biomeStr = world.getPropertyMap().getDefaultBiome();
        ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, Identifier.parse(biomeStr));
        net.minecraft.core.Holder<Biome> biomeHolder = server.registryAccess().lookupOrThrow(Registries.BIOME).get(biomeKey).orElseThrow();
        SlimeLevelGenerator generator = new SlimeLevelGenerator(biomeHolder, world.getPropertyMap());

        // Create a custom LevelStem with our generator but standard dimension type
        LevelStem originalStem = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).get(stemKey).orElseThrow().value();
        LevelStem customStem = new LevelStem(originalStem.type(), generator);

        SlimeLevelInstance level;
        try {
            level = new SlimeLevelInstance(bootstrap, worldData, dimensionKey, customStem, environment);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        return level;
    }

    private World.Environment getEnvironment(SlimeWorld world) {
        // Default to NORMAL if no environment is stored in the property map
        // The property map doesn't have an explicit environment field, so we default
        return World.Environment.NORMAL;
    }

    private PrimaryLevelData createWorldData(SlimeWorld world, MinecraftServer server) {
        String worldName = world.getName();
        DedicatedServerProperties serverProps = ((DedicatedServer) server).getProperties();
        WorldLoader.DataLoadContext context = server.worldLoaderContext;

        LevelSettings worldsettings = new LevelSettings(worldName, serverProps.gameMode.get(), false,
                serverProps.difficulty.get(), true,
                new GameRules(context.dataConfiguration().enabledFeatures(), GameRuleMap.of()),
                server.worldLoaderContext.dataConfiguration());

        WorldOptions worldoptions = new WorldOptions(0, false, false);

        PrimaryLevelData data = new PrimaryLevelData(worldsettings, worldoptions,
                PrimaryLevelData.SpecialWorldProperty.FLAT, Lifecycle.stable());
        data.checkName(worldName);
        data.setModdedInfo(server.getServerModName(), server.getModdedStatus().shouldReportAsModified());
        data.setInitialized(true);

        return data;
    }
}
