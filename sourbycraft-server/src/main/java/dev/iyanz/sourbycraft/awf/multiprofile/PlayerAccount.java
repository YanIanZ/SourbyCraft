package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A player's immutable identity, with the profiles it owns and the one currently active.
 *
 * <p>{@code revision} increments on every change and is the compare-and-set value: a writer holding
 * an older revision is refused, so two asynchronous operations cannot silently overwrite each
 * other. The slot limit is stored on the account, not inferred from the number of profiles.</p>
 *
 * @param playerId the Minecraft account id; never a name
 * @param profileIds owned profiles, in creation order
 * @param activeProfile the currently active profile, or {@code null}
 * @param slotLimit how many profiles this account may own
 * @param revision monotonically increasing account revision
 */
public record PlayerAccount(UUID playerId, java.util.List<UUID> profileIds, UUID activeProfile, int slotLimit,
                            long revision) {

    public static final int DEFAULT_SLOTS = 3;
    public static final int MAX_SLOTS = 64;

    public PlayerAccount {
        Objects.requireNonNull(playerId, "playerId");
        profileIds = java.util.List.copyOf(profileIds == null ? java.util.List.of() : profileIds);
        if (slotLimit < 1 || slotLimit > MAX_SLOTS) throw new IllegalArgumentException("slot limit " + slotLimit);
        if (revision < 0) throw new IllegalArgumentException("revision " + revision);
        if (activeProfile != null && !profileIds.contains(activeProfile)) {
            throw new IllegalArgumentException("the active profile must be an owned profile");
        }
    }

    public static PlayerAccount empty(final UUID playerId, final int slots) {
        return new PlayerAccount(playerId, java.util.List.of(), null, slots, 0L);
    }

    public boolean owns(final UUID profileId) {
        return this.profileIds.contains(profileId);
    }

    public boolean full() {
        return this.profileIds.size() >= this.slotLimit;
    }

    public PlayerAccount withProfile(final UUID profileId) {
        if (owns(profileId)) return this;
        final java.util.List<UUID> ids = new java.util.ArrayList<>(this.profileIds);
        ids.add(profileId);
        return new PlayerAccount(this.playerId, ids, this.activeProfile, this.slotLimit, this.revision + 1);
    }

    public PlayerAccount withoutProfile(final UUID profileId) {
        if (!owns(profileId)) return this;
        final java.util.List<UUID> ids = new java.util.ArrayList<>(this.profileIds);
        ids.remove(profileId);
        return new PlayerAccount(this.playerId, ids, profileId.equals(this.activeProfile) ? null : this.activeProfile,
            this.slotLimit, this.revision + 1);
    }

    public PlayerAccount withActive(final UUID profileId) {
        if (profileId != null && !owns(profileId)) throw new IllegalArgumentException("not an owned profile");
        return new PlayerAccount(this.playerId, this.profileIds, profileId, this.slotLimit, this.revision + 1);
    }

    /** Immutable metadata view for diagnostics. */
    public Map<String, String> describe() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("player", this.playerId.toString());
        values.put("profiles", Integer.toString(this.profileIds.size()));
        values.put("slots", Integer.toString(this.slotLimit));
        values.put("active", this.activeProfile == null ? "none" : this.activeProfile.toString());
        values.put("revision", Long.toString(this.revision));
        return values;
    }
}
