package dev.iyanz.sourbycraft.api.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Replaces a profile's complete world binding. An empty map removes the binding's worlds without
 * deleting them. A template creates missing worlds on the next switch, not during binding.
 * Names use AWF's portable lower-case folder syntax. At most sixteen worlds may be bound.
 * The caller must enforce its own player permissions; this is a trusted server-plugin API.
 */
@NullMarked
public record ProfileWorldBindingRequest(UUID profileId, Map<String, ProfileWorldRole> worlds,
                                         @Nullable String template) {
    public ProfileWorldBindingRequest {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(worlds, "worlds");
        if (worlds.size() > 16) throw new IllegalArgumentException("at most 16 worlds may be bound");
        final Map<String, ProfileWorldRole> copy = new LinkedHashMap<>();
        worlds.forEach((world, role) -> {
            if (world == null || !world.matches("[a-z0-9_-]{1,48}")) {
                throw new IllegalArgumentException("invalid AWF world name: " + world);
            }
            copy.put(world, Objects.requireNonNull(role, "role"));
        });
        worlds = Collections.unmodifiableMap(copy);
        if (template != null && !template.matches("[a-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("invalid AWF template name: " + template);
        }
    }
}
