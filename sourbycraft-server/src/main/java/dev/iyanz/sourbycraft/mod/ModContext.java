package dev.iyanz.sourbycraft.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Runtime context given to every mod during {@link SourbyMod#onLoad}.
 *
 * <p>Package-private constructor: only {@link ModLoader} creates it. The data directory is
 * {@code mods/<id>/} and is created lazily on first access; a creation failure is logged but the
 * path is still returned so the mod can decide how to proceed.</p>
 */
public final class ModContext {

    private final String modId;
    private final String name;
    private final String version;
    private final Path dataDirectory;
    private final Logger logger;

    ModContext(final ModDescriptor descriptor, final Path modsRoot) {
        this.modId = descriptor.id;
        this.name = descriptor.name;
        this.version = descriptor.version;
        this.dataDirectory = modsRoot.resolve(descriptor.id);
        this.logger = Logger.getLogger("SourbyMod/" + descriptor.id);
    }

    /** The mod's unique identifier as declared in {@code sourbymod.yml}. */
    public String modId() {
        return this.modId;
    }

    /** The mod's display name as declared in {@code sourbymod.yml}. */
    public String name() {
        return this.name;
    }

    /** The mod's version string as declared in {@code sourbymod.yml}. */
    public String version() {
        return this.version;
    }

    /** This mod's data directory ({@code mods/<id>/}), created lazily on first access. */
    public Path dataDirectory() {
        try {
            Files.createDirectories(this.dataDirectory);
        } catch (final IOException failed) {
            this.logger.warning("Could not create data directory for mod '" + this.modId + "': "
                + failed.getMessage());
        }
        return this.dataDirectory;
    }

    /** A logger prefixed {@code SourbyMod/<id>} for scoped console output. */
    public Logger logger() {
        return this.logger;
    }
}
