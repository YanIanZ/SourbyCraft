package dev.iyanz.sourbycraft.api.profile;

/** Added in Build 54. Where the definition of a profile type in force on this server comes from. */
public enum ProfileTypeSource {
    /** The server's own built-in definition ({@code classic}, {@code ironman}, {@code stranded}, {@code bingo}). */
    BUILTIN,
    /** Registered by an enabled plugin since this server started. */
    REGISTERED,
    /**
     * The last definition a plugin registered, read back from the profile store because that plugin has not
     * registered it since this server started (it is absent, disabled, or unregistered the type). It stays in force,
     * so a deny is never lifted because its plugin is gone.
     */
    STORED
}
