package dev.iyanz.sourbycraft.swm.api;

import net.kyori.adventure.util.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

public interface SlimeNMSBridge {
    SlimeWorldInstance loadOverworldOverride(MinecraftServer server);
    SlimeWorldInstance loadNetherOverride(MinecraftServer server);
    SlimeWorldInstance loadEndOverride(MinecraftServer server);
    void setDefaultWorlds(SlimeWorld normal, SlimeWorld nether, SlimeWorld end);
    SlimeWorldInstance loadInstance(SlimeWorld world, MinecraftServer server, ResourceKey<Level> dimensionKey);
    int getCurrentVersion();
    CompoundTag extractCraftPDC(SlimeWorld world);

    static SlimeNMSBridge instance() {
        return SlimeNMSBridge.Holder.INSTANCE;
    }

    class Holder {
        private static final SlimeNMSBridge INSTANCE = Services.service(SlimeNMSBridge.class).orElseThrow();
    }
}
