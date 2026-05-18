package dev.iyanz.sourbycraft.swm.api;

public class SlimePropertyMap {
    private boolean readOnly;
    private boolean savePoi = true;
    private boolean saveBlockTicks = true;
    private boolean saveFluidTicks = true;
    private String defaultBiome = "minecraft:plains";
    private int seaLevel = 63;
    private int spawnX;
    private int spawnY;
    private int spawnZ;

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public boolean savePoi() { return savePoi; }
    public void setSavePoi(boolean savePoi) { this.savePoi = savePoi; }

    public boolean saveBlockTicks() { return saveBlockTicks; }
    public void setSaveBlockTicks(boolean saveBlockTicks) { this.saveBlockTicks = saveBlockTicks; }

    public boolean saveFluidTicks() { return saveFluidTicks; }
    public void setSaveFluidTicks(boolean saveFluidTicks) { this.saveFluidTicks = saveFluidTicks; }

    public String getDefaultBiome() { return defaultBiome; }
    public void setDefaultBiome(String defaultBiome) { this.defaultBiome = defaultBiome; }

    public int getSeaLevel() { return seaLevel; }
    public void setSeaLevel(int seaLevel) { this.seaLevel = seaLevel; }

    public int getSpawnX() { return spawnX; }
    public void setSpawnX(int spawnX) { this.spawnX = spawnX; }

    public int getSpawnY() { return spawnY; }
    public void setSpawnY(int spawnY) { this.spawnY = spawnY; }

    public int getSpawnZ() { return spawnZ; }
    public void setSpawnZ(int spawnZ) { this.spawnZ = spawnZ; }
}
