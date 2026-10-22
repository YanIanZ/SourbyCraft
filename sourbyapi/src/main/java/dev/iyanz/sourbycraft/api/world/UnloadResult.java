package dev.iyanz.sourbycraft.api.world;

/** Outcome of {@link AuroraWorlds#unload(String, boolean)}. */
public enum UnloadResult {
    /** The world was unloaded. */
    SUCCESS,
    /** An unload of this world is already running. */
    ALREADY_UNLOADING,
    /** The main overworld cannot be unloaded. */
    IS_OVERWORLD,
    /** The server is shutting down. */
    SERVER_STOPPING,
    /** Players are joining the world. */
    PLAYERS_JOINING,
    /** Players are in the world. */
    PLAYERS_PRESENT,
    /** A plugin cancelled the unload. */
    CANCELLED_BY_EVENT,
    /** Anything else; see the server log. */
    FAILED
}
