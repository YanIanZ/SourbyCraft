package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;

/**
 * A world bound to a profile.
 *
 * @param world AWF world folder name
 * @param role {@code PRIMARY}, {@code NETHER}, {@code END}, {@code SHARED} or {@code AUXILIARY}
 * @param loaded whether the world is loaded right now (a hint, not a guarantee for later)
 */
public record ProfileWorldRef(String world, String role, boolean loaded) {

    public ProfileWorldRef {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(role, "role");
    }
}
