package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A player's account record ({@code A}, format-2 record type 1): the profiles it can access, their
 * account-local aliases, the active profile and membership epoch, the session generation and the
 * cleanup obligations of revoked memberships.
 *
 * <p>{@code revision} is the account revision {@code A}: it advances exactly once per committed change
 * of this record (profile list, alias, slot limit, active profile or epoch, session generation,
 * reservation, cleanup obligation) and never for another member's save or a profile rename. A write
 * passes the revision it read in its guard and the repository refuses the write when the stored
 * account has moved on. A missing account is revision {@code 0}. The slot limit is stored, not
 * inferred from the number of profiles.</p>
 *
 * <p><b>Aliases</b> are the names the account's profile list shows: {@link CuteNames#alias} form,
 * unique within the account under lower-case comparison, one per accessible profile; absent for a
 * format-1 record (the engine then shows the profile's own name). A co-op profile keeps one shared
 * name but may carry a different alias in each member's account; renaming an alias changes only this
 * account. <b>Session generation</b> advances when a new or reconnected player session is admitted
 * under its {@code Q} claim, before capture or restore, so a capture ticket minted for an earlier
 * session cannot commit. <b>Cleanup obligations</b> record a revoked membership whose isolated state
 * must be replaced before the player's next gameplay (COOP-DESIGN §7 step 2); Build 51 stores and
 * reads them, no path creates them yet. {@code legacyAdoptionClosed} is set permanently once the
 * store migrated or the account's first authoritative activation happened: vanilla player data is
 * never adopted after that.</p>
 *
 * @param playerId the Minecraft account id; never a name
 * @param profileIds accessible profiles (owned or member), in admission order
 * @param aliases account-local alias per profile id; may lack entries for format-1 records
 * @param activeProfile the currently active profile, or {@code null}
 * @param activeEpoch the player's membership epoch in the active profile, or {@code null} (none, or format 1)
 * @param slotLimit how many profiles this account may access (each co-op counts)
 * @param revision account revision {@code A}
 * @param sessionGeneration advances per admitted player session
 * @param pendingIntent operation holding a reservation on this record, or {@code null}
 * @param cleanups unresolved cleanup obligations, oldest first
 * @param legacyAdoptionClosed whether vanilla player data may never again be adopted for this account
 */
public record PlayerAccount(UUID playerId, List<UUID> profileIds, Map<UUID, String> aliases, UUID activeProfile,
                            UUID activeEpoch, int slotLimit, long revision, long sessionGeneration, UUID pendingIntent,
                            List<Cleanup> cleanups, boolean legacyAdoptionClosed) {

    public static final int DEFAULT_SLOTS = 3;
    public static final int MAX_SLOTS = 64;
    /** Unresolved cleanup obligations an account may carry; more refuses the removal that would add one. */
    public static final int MAX_CLEANUPS = 64;

    public PlayerAccount {
        Objects.requireNonNull(playerId, "playerId");
        profileIds = List.copyOf(profileIds == null ? List.of() : profileIds);
        if (profileIds.size() > MAX_SLOTS) throw new IllegalArgumentException("more than " + MAX_SLOTS + " profiles");
        for (int i = 0; i < profileIds.size(); i++) {
            for (int j = i + 1; j < profileIds.size(); j++) {
                if (profileIds.get(i).equals(profileIds.get(j))) throw new IllegalArgumentException("profile listed twice");
            }
        }
        if (slotLimit < 1 || slotLimit > MAX_SLOTS) throw new IllegalArgumentException("slot limit " + slotLimit);
        CoopWriteGuard.counter(revision, "account revision");
        CoopWriteGuard.counter(sessionGeneration, "session generation");
        if (activeProfile != null && !profileIds.contains(activeProfile)) {
            throw new IllegalArgumentException("the active profile must be an accessible profile");
        }
        if (activeEpoch != null && activeProfile == null) throw new IllegalArgumentException("an epoch needs an active profile");
        final Map<UUID, String> copy = new LinkedHashMap<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final List<UUID> accessible = profileIds;
        (aliases == null ? Map.<UUID, String>of() : aliases).forEach((profile, alias) -> {
            if (!accessible.contains(profile)) throw new IllegalArgumentException("alias for a profile the account cannot access");
            if (!CuteNames.validAlias(alias)) throw new IllegalArgumentException("alias: " + alias);
            if (!seen.add(alias)) throw new IllegalArgumentException("alias " + alias + " used twice in one account");
            copy.put(profile, alias);
        });
        aliases = Collections.unmodifiableMap(copy);
        cleanups = List.copyOf(cleanups == null ? List.of() : cleanups);
        if (cleanups.size() > MAX_CLEANUPS) throw new IllegalArgumentException("more than " + MAX_CLEANUPS + " cleanup obligations");
    }

    /** A format-1 account (no aliases, epoch, generation, reservation or cleanups). */
    public PlayerAccount(final UUID playerId, final List<UUID> profileIds, final UUID activeProfile, final int slotLimit,
                         final long revision) {
        this(playerId, profileIds, Map.of(), activeProfile, null, slotLimit, revision, 0L, null, List.of(), false);
    }

    public static PlayerAccount empty(final UUID playerId, final int slots) {
        return new PlayerAccount(playerId, List.of(), Map.of(), null, null, slots, 0L, 0L, null, List.of(), false);
    }

    /** Whether the account can access {@code profileId} (owned or as a member). */
    public boolean owns(final UUID profileId) {
        return this.profileIds.contains(profileId);
    }

    public boolean full() {
        return this.profileIds.size() >= this.slotLimit;
    }

    /** The alias of a profile, or {@code null} when none is stored (format-1 record). */
    public String alias(final UUID profileId) {
        return this.aliases.get(profileId);
    }

    /** The alias keys already used, for {@link CuteNames#allocateAlias}. */
    public java.util.Set<String> takenAliases() {
        return java.util.Set.copyOf(this.aliases.values());
    }

    // ------------------------------------------------------------------ mutations (each advances A once)

    /** Adds an accessible profile without an alias (format-1 solo path). */
    public PlayerAccount withProfile(final UUID profileId) {
        if (owns(profileId)) return this;
        final List<UUID> ids = new ArrayList<>(this.profileIds);
        ids.add(profileId);
        return copy(ids, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(), this.sessionGeneration,
            this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Adds an accessible profile with its account-local alias (format-2 path); the alias must be free. */
    public PlayerAccount withProfile(final UUID profileId, final String alias) {
        if (owns(profileId)) throw new IllegalArgumentException("profile already accessible");
        final List<UUID> ids = new ArrayList<>(this.profileIds);
        ids.add(profileId);
        final Map<UUID, String> names = new LinkedHashMap<>(this.aliases);
        names.put(profileId, alias);
        return copy(ids, names, this.activeProfile, this.activeEpoch, this.slotLimit, next(), this.sessionGeneration,
            this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Removes a profile and its alias; clears the active profile and epoch when it was the active one. */
    public PlayerAccount withoutProfile(final UUID profileId) {
        if (!owns(profileId)) return this;
        final List<UUID> ids = new ArrayList<>(this.profileIds);
        ids.remove(profileId);
        final Map<UUID, String> names = new LinkedHashMap<>(this.aliases);
        names.remove(profileId);
        final boolean wasActive = profileId.equals(this.activeProfile);
        return copy(ids, names, wasActive ? null : this.activeProfile, wasActive ? null : this.activeEpoch, this.slotLimit,
            next(), this.sessionGeneration, this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Format-1 solo path: sets the active profile with no epoch. */
    public PlayerAccount withActive(final UUID profileId) {
        if (profileId != null && !owns(profileId)) throw new IllegalArgumentException("not an accessible profile");
        return copy(this.profileIds, this.aliases, profileId, null, this.slotLimit, next(), this.sessionGeneration,
            this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Format-2 path: sets the active profile and the player's membership epoch in it. */
    public PlayerAccount withActive(final UUID profileId, final UUID epoch) {
        if (profileId != null && !owns(profileId)) throw new IllegalArgumentException("not an accessible profile");
        if (profileId != null) Objects.requireNonNull(epoch, "epoch");
        return copy(this.profileIds, this.aliases, profileId, profileId == null ? null : epoch, this.slotLimit, next(),
            this.sessionGeneration, this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Renames the alias of one accessible profile; the new alias must be free in this account. */
    public PlayerAccount withAlias(final UUID profileId, final String alias) {
        if (!owns(profileId)) throw new IllegalArgumentException("not an accessible profile");
        final Map<UUID, String> names = new LinkedHashMap<>(this.aliases);
        names.put(profileId, alias);
        return copy(this.profileIds, names, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            this.sessionGeneration, this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** A new slot limit; profiles beyond a lowered limit are kept, only adding more is refused. */
    public PlayerAccount withSlotLimit(final int slots) {
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, slots, next(), this.sessionGeneration,
            this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    /** A new player session was admitted: the generation and {@code A} advance. */
    public PlayerAccount nextSession() {
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            CoopWriteGuard.next(this.sessionGeneration, "session generation of " + this.playerId), this.pendingIntent,
            this.cleanups, this.legacyAdoptionClosed);
    }

    /** Installs or clears ({@code null}) the reservation of an intent. */
    public PlayerAccount withPendingIntent(final UUID operationId) {
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            this.sessionGeneration, operationId, this.cleanups, this.legacyAdoptionClosed);
    }

    /** Adds a cleanup obligation (refused beyond {@link #MAX_CLEANUPS}). */
    public PlayerAccount withCleanup(final Cleanup cleanup) {
        final List<Cleanup> next = new ArrayList<>(this.cleanups);
        next.add(Objects.requireNonNull(cleanup, "cleanup"));
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            this.sessionGeneration, this.pendingIntent, next, this.legacyAdoptionClosed);
    }

    /** Removes the resolved cleanup obligation of {@code removalOperationId}. */
    public PlayerAccount withoutCleanup(final UUID removalOperationId) {
        final List<Cleanup> next = new ArrayList<>(this.cleanups);
        if (!next.removeIf(cleanup -> cleanup.removalOperationId().equals(removalOperationId))) return this;
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            this.sessionGeneration, this.pendingIntent, next, this.legacyAdoptionClosed);
    }

    /** Closes legacy adoption permanently. */
    public PlayerAccount closingLegacyAdoption() {
        if (this.legacyAdoptionClosed) return this;
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, next(),
            this.sessionGeneration, this.pendingIntent, this.cleanups, true);
    }

    /** The same record at an explicit revision (decoders and after-images only). */
    public PlayerAccount atRevision(final long revision) {
        return copy(this.profileIds, this.aliases, this.activeProfile, this.activeEpoch, this.slotLimit, revision,
            this.sessionGeneration, this.pendingIntent, this.cleanups, this.legacyAdoptionClosed);
    }

    private long next() {
        return CoopWriteGuard.next(this.revision, "account " + this.playerId);
    }

    private PlayerAccount copy(final List<UUID> profileIds, final Map<UUID, String> aliases, final UUID activeProfile,
                               final UUID activeEpoch, final int slotLimit, final long revision, final long sessionGeneration,
                               final UUID pendingIntent, final List<Cleanup> cleanups, final boolean legacyAdoptionClosed) {
        return new PlayerAccount(this.playerId, profileIds, aliases, activeProfile, activeEpoch, slotLimit, revision,
            sessionGeneration, pendingIntent, cleanups, legacyAdoptionClosed);
    }

    /** Immutable metadata view for diagnostics. */
    public Map<String, String> describe() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("player", this.playerId.toString());
        values.put("profiles", Integer.toString(this.profileIds.size()));
        values.put("slots", Integer.toString(this.slotLimit));
        values.put("active", this.activeProfile == null ? "none" : this.activeProfile.toString());
        values.put("active-epoch", this.activeEpoch == null ? "none" : this.activeEpoch.toString());
        values.put("revision", Long.toString(this.revision));
        values.put("session-generation", Long.toString(this.sessionGeneration));
        values.put("pending-intent", this.pendingIntent == null ? "none" : this.pendingIntent.toString());
        values.put("cleanups", Integer.toString(this.cleanups.size()));
        values.put("legacy-adoption-closed", Boolean.toString(this.legacyAdoptionClosed));
        return values;
    }

    /**
     * A durable obligation to replace the player's isolated state before its next gameplay, created
     * by the commit that removed the membership (COOP-DESIGN §7 step 2).
     *
     * @param profileId the profile the membership was revoked from
     * @param epoch the revoked membership epoch
     * @param removalOperationId the removal intent; also the key for {@link #withoutCleanup}
     * @param categoryMask bit set over {@link StateCategory#ordinal()} of the isolated categories emptied
     * @param cleanupSnapshotId the snapshot (source state with extracted sections emptied) to apply
     */
    public record Cleanup(UUID profileId, UUID epoch, UUID removalOperationId, long categoryMask, UUID cleanupSnapshotId) {

        public Cleanup {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(removalOperationId, "removalOperationId");
            Objects.requireNonNull(cleanupSnapshotId, "cleanupSnapshotId");
        }
    }
}
