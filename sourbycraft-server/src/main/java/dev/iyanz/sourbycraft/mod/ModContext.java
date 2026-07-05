package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.core.SourbyModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Runtime context given to every mod during {@link SourbyMod#onLoad}.
 * Package-private constructor: only {@link ModLoader} constructs this.
 *
 * <p>Mods receive a prefixed {@link Logger}, lazy data directory, and the ability
 * to buffer additional {@link SourbyModule} instances. Buffered modules are enrolled
 * into the MT1 ModuleRegistry by {@link ModLoader#enrollInto()} (called from
 * SWPlugin.onEnable after the reload-guard clear), giving them the same lifecycle
 * isolation as first-party modules.
 */
public final class ModContext {

    private final String modId;
    private final String name;
    private final String version;
    private final Path dataDirectory;
    private final Logger logger;

    /** Modules buffered via registerModule during onLoad; drained by ModLoader.enrollInto(). */
    private final List<SourbyModule> pendingModules = new ArrayList<>();

    ModContext(String modId, String name, String version) {
        this.modId = modId;
        this.name = name;
        this.version = version;
        this.dataDirectory = Path.of("mods", modId);
        this.logger = Logger.getLogger("SourbyMod/" + modId);
    }

    /** The mod's unique identifier as declared in {@code sourbymod.yml}. */
    public String modId() {
        return modId;
    }

    /** The mod's display name as declared in {@code sourbymod.yml}. */
    public String name() {
        return name;
    }

    /** The mod's version string as declared in {@code sourbymod.yml}. */
    public String version() {
        return version;
    }

    /**
     * Returns this mod's data directory ({@code mods/<id>/}), creating it lazily on first access.
     * A creation failure is logged as a warning; the path is returned regardless so the mod can
     * decide how to handle missing storage.
     */
    public Path dataDirectory() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warning("Could not create data directory for mod '" + modId + "': " + e.getMessage());
        }
        return dataDirectory;
    }

    /**
     * A {@link Logger} prefixed with {@code SourbyMod/<id>} for scoped console output.
     * Mods should use this rather than raw {@code System.out} or a bare logger.
     */
    public Logger logger() {
        return logger;
    }

    /**
     * Buffer a {@link SourbyModule} for enrollment into the MT1 ModuleRegistry.
     * Must be called during {@link SourbyMod#onLoad}; the module is enrolled by
     * {@link ModLoader#enrollInto()} before {@code enableAll}, so it participates in
     * the standard enable/disable lifecycle alongside first-party SourbyCraft modules.
     */
    public void registerModule(SourbyModule module) {
        if (module != null) pendingModules.add(module);
    }

    /**
     * Package-private: called by {@link ModLoader#enrollInto()} to enroll modules registered
     * via {@link #registerModule} during onLoad. NON-destructive — the buffer persists so a
     * same-classloader plugin reload (which re-runs enrollInto without re-running onLoad)
     * re-enrolls the same sub-modules.
     */
    List<SourbyModule> pendingModules() {
        return new ArrayList<>(pendingModules);
    }
}
