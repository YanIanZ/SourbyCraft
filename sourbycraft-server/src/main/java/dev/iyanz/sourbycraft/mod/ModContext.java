package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.core.ModuleRegistry;
import dev.iyanz.sourbycraft.core.SourbyModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Runtime context given to every mod during {@link SourbyMod#onLoad}.
 *
 * <p>Mods receive a prefixed {@link Logger}, lazy data directory, and the ability
 * to enroll additional {@link SourbyModule} instances into the MT1 ModuleRegistry
 * so their runtime features get the same isolation and lifecycle as first-party ones.
 */
public final class ModContext {

    private final String modId;
    private final String version;
    private final Path dataDirectory;
    private final Logger logger;

    ModContext(String modId, String version) {
        this.modId = modId;
        this.version = version;
        this.dataDirectory = Path.of("mods", modId);
        this.logger = Logger.getLogger("SourbyMod/" + modId);
    }

    /** The mod's unique identifier as declared in {@code sourbymod.yml}. */
    public String modId() {
        return modId;
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
     * Enroll a {@link SourbyModule} into the MT1 ModuleRegistry.
     * The module's {@code enable(Plugin)} and {@code disable()} will be called by
     * {@link ModuleRegistry#enableAll} and {@link ModuleRegistry#disableAll} respectively,
     * giving mod-registered features the same lifecycle isolation as first-party modules.
     *
     * <p>Must be called during {@link SourbyMod#onLoad}. Modules registered after
     * {@code enableAll} has already run will not be enabled automatically.
     */
    public void registerModule(SourbyModule module) {
        ModuleRegistry.addPersistent(module);
    }
}
