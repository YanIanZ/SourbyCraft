package dev.iyanz.sourbycraft.api.world;

/**
 * Added in Build 54. Which kind of backend holds a world's AWF stores ({@code aurora.awf.backend},
 * RESTART_REQUIRED). One backend serves every AWF world of a server.
 */
public enum AwfBackendKind {
    /** The built-in FILE backend: a {@code <folder>.awf} directory beside each storage folder. */
    FILE,
    /** The built-in Redis backend. */
    REDIS,
    /** A backend registered by other code; {@link AwfWorldSummary#backendName()} names it. */
    OTHER
}
