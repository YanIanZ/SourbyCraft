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
    // ASP dev/26.2 parity: default true so mob spawning, weather, time tick
    // work on SWM worlds. Previously SLI hardcoded setSpawnSettings(false,false)
    // + Difficulty=PEACEFUL — broke `/is` mob spawn, breeding, raids, etc.
    private boolean allowMonsters = true;
    private boolean allowAnimals = true;
    private boolean pvp = true;
    private String difficulty = ""; // empty = inherit from server
    private boolean worldType = true; // dragon battle / structure gen flags

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

    public boolean allowMonsters() { return allowMonsters; }
    public void setAllowMonsters(boolean allowMonsters) { this.allowMonsters = allowMonsters; }

    public boolean allowAnimals() { return allowAnimals; }
    public void setAllowAnimals(boolean allowAnimals) { this.allowAnimals = allowAnimals; }

    public boolean pvp() { return pvp; }
    public void setPvp(boolean pvp) { this.pvp = pvp; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty == null ? "" : difficulty; }

    public boolean isWorldType() { return worldType; }
    public void setWorldType(boolean worldType) { this.worldType = worldType; }
}
