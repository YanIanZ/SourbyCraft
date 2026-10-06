package dev.iyanz.sourbycraft.world;

import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import java.util.List;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world metadata that the global tick owns and may therefore save from the global tick during autosave and
 * {@code save-all}.
 *
 * <p>{@link PaperLevelOverrides} ({@code paper:level_overrides}) is the load path for game time, spawn, game type,
 * difficulty, the initialized flag and the per-world distance override; {@code level.dat} is only the fallback when
 * that file does not exist yet. The global tick advances game time and marks the data dirty every tick, and
 * {@code SavedDataStorage#saveSpecific} encodes it on the calling (global) thread and writes the file on the dimension
 * data I/O pool, the same lane the existing map/weather/gamerule autosave uses.</p>
 *
 * <p>Region-owned {@link SavedData} (wandering trader data, raids, structure indexes, the dragon fight) must never be
 * listed here. Day time, weather and gamerules are already in Folia's whitelist.</p>
 */
public final class WorldMetadataAutosave {

    public static final List<Class<? extends SavedData>> GLOBAL_OWNED_TYPES = List.of(PaperLevelOverrides.class);

    private WorldMetadataAutosave() {
    }

    public static void addGlobalOwnedTypes(final List<Class<? extends SavedData>> toSave) {
        toSave.addAll(GLOBAL_OWNED_TYPES);
    }
}
