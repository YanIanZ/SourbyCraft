package dev.iyanz.sourbycraft.startup;

import java.util.Objects;

/**
 * The compatibility-sensitive half of every startup-cache key: what must match for any cached
 * output to be reusable at all.
 *
 * <p>From {@code docs/architecture/aurora-instant-startup.md}: Minecraft version, SourbyCraft
 * ABI, Aurora Bridge ABI, cache-format version and Java major. A difference in any of them
 * invalidates the whole file, not individual entries.</p>
 *
 * @param minecraftVersion the running Minecraft version, e.g. {@code 26.2}
 * @param sourbyAbi {@link #SOURBY_ABI} unless a test says otherwise
 * @param bridgeAbi {@link #BRIDGE_ABI} unless a test says otherwise
 * @param formatVersion {@link #FORMAT_VERSION} unless a test says otherwise
 * @param javaMajor the running Java feature release
 */
public record CacheEnvironment(String minecraftVersion, int sourbyAbi, int bridgeAbi,
                               int formatVersion, int javaMajor) {

    /** Bump when a cached output's meaning changes on the SourbyCraft side. */
    public static final int SOURBY_ABI = 1;
    /** Bump when the Aurora Bridge's compatibility analysis changes. */
    public static final int BRIDGE_ABI = 1;
    /** Bump when the on-disk layout changes. */
    public static final int FORMAT_VERSION = 1;

    public CacheEnvironment {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (minecraftVersion.isBlank() || minecraftVersion.indexOf('|') >= 0
            || minecraftVersion.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("unusable minecraft version: " + minecraftVersion);
        }
    }

    /** The environment of this process for the given Minecraft version. */
    public static CacheEnvironment current(final String minecraftVersion) {
        return new CacheEnvironment(minecraftVersion, SOURBY_ABI, BRIDGE_ABI, FORMAT_VERSION,
            Runtime.version().feature());
    }

    String encode() {
        return this.minecraftVersion + "|" + this.sourbyAbi + "|" + this.bridgeAbi + "|"
            + this.formatVersion + "|" + this.javaMajor;
    }
}
