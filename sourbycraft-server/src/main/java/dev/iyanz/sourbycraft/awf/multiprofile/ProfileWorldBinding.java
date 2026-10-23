package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Which AWF worlds belong to a profile, and in what role.
 *
 * <p>A profile may own no world, one world, or several worlds of different roles; several profiles may
 * also reference one {@link ProfileWorldRole#SHARED} world. The binding therefore records roles, not a
 * single {@code world} column: "one profile = one world" is a consumer assumption the core refuses to
 * bake in.</p>
 *
 * @param profileId the profile
 * @param worlds world folder name to role, in binding order
 * @param template optional template used to create a missing world
 */
public record ProfileWorldBinding(UUID profileId, Map<String, ProfileWorldRole> worlds, String template) {

    public static final int MAX_WORLDS = 16;

    public ProfileWorldBinding {
        Objects.requireNonNull(profileId, "profileId");
        if (worlds == null) worlds = Map.of();
        if (worlds.size() > MAX_WORLDS) throw new IllegalArgumentException("a profile binds at most " + MAX_WORLDS + " worlds");
        final Map<String, ProfileWorldRole> copy = new LinkedHashMap<>();
        worlds.forEach((world, role) -> {
            if (world == null || !world.matches("[a-z0-9_-]{1,48}")) {
                throw new IllegalArgumentException("world binding name: " + world);
            }
            copy.put(world, Objects.requireNonNull(role, "role"));
        });
        worlds = java.util.Collections.unmodifiableMap(copy);
        if (template != null && !template.matches("[a-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("template name: " + template);
        }
    }

    public static ProfileWorldBinding empty(final UUID profileId) {
        return new ProfileWorldBinding(profileId, Map.of(), null);
    }

    public boolean isEmpty() {
        return this.worlds.isEmpty();
    }

    public ProfileWorldBinding withWorld(final String world, final ProfileWorldRole role) {
        final Map<String, ProfileWorldRole> copy = new LinkedHashMap<>(this.worlds);
        copy.put(world, role);
        return new ProfileWorldBinding(this.profileId, copy, this.template);
    }

    public ProfileWorldBinding withoutWorld(final String world) {
        final Map<String, ProfileWorldRole> copy = new LinkedHashMap<>(this.worlds);
        copy.remove(world);
        return new ProfileWorldBinding(this.profileId, copy, this.template);
    }
}
