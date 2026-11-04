package dev.iyanz.sourbycraft.awf.multiprofile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Settings of {@code aurora.awf.multiprofile.*}.
 *
 * <p>Every key is RESTART_REQUIRED. The engine creates its repository, its ownership provider and
 * its worker when the server starts; a reload cannot move an open profile repository or change which
 * ownership service is authoritative. The feature is disabled by default: disabled means no
 * directory is created, no thread is started and no player is touched.</p>
 */
public record ProfileSettings(boolean enabled, Mode mode, int defaultSlots, int maxSlots, Switching switching,
                              ProfileIsolation isolation, Worlds worlds, Coordination coordination, String directory,
                              Repository repository, Types types, Names names, Migration migration, Coop coop,
                              Shutdown shutdown, Login login, Adapters adapters, TypeRegistry typeRegistry, Bank bank,
                              Audit audit, Io io) {

    public enum Mode {
        STANDALONE,
        DISTRIBUTED;

        public static Mode parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("mode must be standalone or distributed, not " + text);
            }
        }
    }

    public enum Provider {
        LOCAL,
        REDIS;

        public static Provider parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("provider must be local or redis, not " + text);
            }
        }
    }

    /**
     * @param enabled whether switching is allowed at all
     * @param timeoutMillis one switch's budget; a switch that exceeds it is failed and recovered
     * @param requireDurableSave refuse to apply a target unless the old state's snapshot was forced to disk
     * @param cooldownSeconds Build 55: seconds after a <em>committed</em> switch during which the player's next
     *     switch is refused ({@code SWITCH_COOLDOWN}); {@code 0} = no cooldown (the Build 54 behaviour; Hypixel parity
     *     is 90). A refused, vetoed or failed switch starts none. In memory per node; cleared by a restart.
     */
    public record Switching(boolean enabled, int timeoutMillis, boolean requireDurableSave, int cooldownSeconds) {
        /** Longest cooldown: one day. */
        public static final int MAX_COOLDOWN_SECONDS = 86_400;

        public Switching {
            if (timeoutMillis < 1000 || timeoutMillis > 600_000) {
                throw new IllegalArgumentException("switching.timeout-ms must be 1000-600000");
            }
            if (cooldownSeconds < 0 || cooldownSeconds > MAX_COOLDOWN_SECONDS) {
                throw new IllegalArgumentException("switching.cooldown-seconds must be 0-" + MAX_COOLDOWN_SECONDS);
            }
        }

        /** The Build 51-54 shape: no cooldown. */
        public Switching(final boolean enabled, final int timeoutMillis, final boolean requireDurableSave) {
            this(enabled, timeoutMillis, requireDurableSave, 0);
        }
    }

    /**
     * Build 55: the audit log ({@link ProfileAudit}).
     *
     * @param enabled whether entries are recorded at all; {@code false} = no ring, no files, {@code AuroraProfiles.audit}
     *     fails {@code DISABLED}
     * @param retentionDays days a daily audit file below {@code <directory>/audit} is kept, 1-3660
     */
    public record Audit(boolean enabled, int retentionDays) {
        public static final int MAX_RETENTION_DAYS = 3660;

        public Audit {
            if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
                throw new IllegalArgumentException("audit.retention-days must be 1-" + MAX_RETENTION_DAYS);
            }
        }
    }

    /**
     * Build 55: the profile I/O queue ({@link ProfileIoQueue}, PM-091).
     *
     * @param partitions partitions of the queue, 1-64. {@code 1} (default) = the Build 54 behaviour: one profile step at
     *     a time, FIFO over all accounts. More = steps of accounts in different partitions may run at once (one lane
     *     slot each) while each account's own steps stay FIFO; store-wide steps are barriers. Relief only appears with
     *     more than one storage lane thread. Unqualified above 1 until the owner's soak.
     */
    public record Io(int partitions) {
        public Io {
            if (partitions < 1 || partitions > ProfileIoQueue.MAX_PARTITIONS) {
                throw new IllegalArgumentException("io.partitions must be 1-" + ProfileIoQueue.MAX_PARTITIONS);
            }
        }
    }

    /**
     * Profile worlds are always created/loaded when a profile becomes active (never at boot: a node
     * with 10,000 profiles cannot load 10,000 worlds), so the only knob is idle unloading.
     *
     * @param idleUnloadSeconds unload a profile world after this long with no player and no operation;
     *     {@code 0} disables idle unloading
     */
    public record Worlds(int idleUnloadSeconds) {
        public Worlds {
            if (idleUnloadSeconds < 0 || idleUnloadSeconds > 86_400) {
                throw new IllegalArgumentException("worlds.idle-unload-seconds must be 0-86400");
            }
        }
    }

    /**
     * @param provider where ownership lives; {@code local} is process-local and cannot coordinate nodes
     * @param fencingEnabled validate the ownership fence before every durable profile write
     * @param singleActiveSession refuse a second live session for one profile (duplicate login)
     * @param handoffWaitMillis Build 55.1: how long a join whose session claim ({@code Q}) or active-profile node
     *     claim is refused only because another live session still holds it keeps retrying the claim (bounded
     *     backoff, off the tick threads) before it is refused as before. {@code 0} (default) is the single attempt
     *     of Builds 51–55; {@value #HANDOFF_WAIT_MAX_MILLIS} is the ceiling. The retry never takes a claim that is
     *     still live: it succeeds only once the holder released it (the quit save runs before the release on the
     *     holder) or its lease lapsed under the existing epoch rules
     */
    public record Coordination(Provider provider, boolean fencingEnabled, boolean singleActiveSession, int handoffWaitMillis) {

        /** The Build 51–55 shape: no handoff wait. */
        public Coordination(final Provider provider, final boolean fencingEnabled, final boolean singleActiveSession) {
            this(provider, fencingEnabled, singleActiveSession, 0);
        }

        public Coordination {
            if (handoffWaitMillis < 0 || handoffWaitMillis > HANDOFF_WAIT_MAX_MILLIS) {
                throw new IllegalArgumentException("coordination.handoff-wait-ms must be 0-" + HANDOFF_WAIT_MAX_MILLIS);
            }
        }
    }

    /** Ceiling of {@code coordination.handoff-wait-ms}: well below the 45 s fabric lease and the 60 s local lease. */
    public static final int HANDOFF_WAIT_MAX_MILLIS = 15_000;

    /** Where profiles, snapshots and journals are stored. */
    public enum Backend {
        FILE,
        REDIS;

        public static Backend parse(final String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("repository must be file or redis, not " + text);
            }
        }
    }

    /**
     * @param backend {@code file} (the directory, one server) or {@code redis} (the Aurora Redis
     *     Fabric, fenced commits)
     * @param requireAof {@code redis} only: refuse to start when Redis reports {@code appendonly} off
     * @param waitForAof {@code redis} only: confirm the writes a later step relies on with
     *     {@code WAITAOF} before acknowledging them
     */
    public record Repository(Backend backend, boolean requireAof, boolean waitForAof) {
        public Repository {
            Objects.requireNonNull(backend, "backend");
        }
    }

    /**
     * Policy types a profile may be created with (Build 51). {@code allowed} and {@code defaultType} are about the
     * {@linkplain dev.iyanz.sourbycraft.api.profile.ProfileType policy type}, not the free consumer namespace
     * ({@code ProfileRequest.type()}), which is not restricted by this list. The type is stored and returned;
     * its rules are not enforced in Build 51.
     *
     * @param allowed the types a create request may name; never empty, iteration order is the declaration order of the enum
     * @param defaultType used when a request names none; always a member of {@code allowed}
     */
    public record Types(Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowed,
                        dev.iyanz.sourbycraft.api.profile.ProfileType defaultType) {
        public Types {
            Objects.requireNonNull(defaultType, "defaultType");
            Objects.requireNonNull(allowed, "allowed");
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("types.allowed must name at least one type");
            }
            allowed = Collections.unmodifiableSet(EnumSet.copyOf(allowed));
            if (!allowed.contains(defaultType)) {
                throw new IllegalArgumentException("types.default " + defaultType.id() + " is not in types.allowed");
            }
        }

        /** Whether a create request may name {@code type}. */
        public boolean allows(final dev.iyanz.sourbycraft.api.profile.ProfileType type) {
            return this.allowed.contains(type);
        }
    }

    /** How a profile's name is chosen. */
    public enum NamePolicy {
        /**
         * The server assigns a name from the {@link CuteNames} pool when the profile is created; it is unique per
         * account (ignoring case) and never changes. A create request may ask for a specific pool name instead
         * of a rolled one; any other text is refused.
         */
        CUTE;

        public static NamePolicy parse(final String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("names.policy must be cute, not " + text);
            }
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param policy how names are chosen */
    public record Names(NamePolicy policy) {
        public Names {
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** What the startup migration of a format-1 store (pre-Build-51) does. */
    public enum MigrationMode {
        /** Touch nothing. A format-1 store stays format 1 and profile writes that need format 2 are refused. */
        OFF("off"),
        /** Read the store and report what a migration would do; write nothing. */
        DRY_RUN("dry-run"),
        /** Back the store up to {@code migration.backup-dir}, then migrate it to format 2. One way. */
        APPLY("apply");

        private final String id;

        MigrationMode(final String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        /**
         * Parses {@code off}, {@code dry-run} (or {@code dry_run}) and {@code apply}. An unquoted {@code off} in
         * YAML is read as the boolean {@code false}; {@link #parse(Object)} accepts that as {@code off}.
         */
        public static MigrationMode parse(final Object value) {
            if (Boolean.FALSE.equals(value)) return OFF;
            final String text = String.valueOf(value).strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (final MigrationMode mode : values()) {
                if (mode.id.equals(text)) return mode;
            }
            throw new IllegalArgumentException("migration.mode must be off, dry-run or apply, not " + value);
        }
    }

    /**
     * @param mode what to do with a format-1 store at startup
     * @param backupDir where {@link MigrationMode#APPLY} writes the backup first: a path relative to the server
     *     directory, or absolute; empty when not configured. Non-empty values never contain {@code ..} and never
     *     textually overlap the store {@code directory} (neither inside it nor containing it). The overlap check is
     *     textual only; whoever opens the path must resolve it and check it again.
     */
    public record Migration(MigrationMode mode, String backupDir) {
        public Migration {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(backupDir, "backupDir");
            if (mode == MigrationMode.APPLY && backupDir.isBlank()) {
                throw new IllegalArgumentException("migration.mode apply requires migration.backup-dir");
            }
        }

        /** Whether the migration is asked to write anything. */
        public boolean applies() {
            return this.mode == MigrationMode.APPLY;
        }
    }

    /**
     * Co-op membership settings (Build 52). Every key is RESTART_REQUIRED. {@code enabled} defaults to false: with
     * it false every co-op operation is refused with {@code COOP_DISABLED}, nothing co-op is written and no co-op
     * record is created (a store already holding co-op profiles stays readable). It has no effect while
     * {@code aurora.awf.multiprofile.enabled} is false; use {@link ProfileSettings#coopEnabled()} for the effective
     * value.
     *
     * @param enabled whether co-op membership operations are accepted
     * @param maxMembers members of one co-op, owner included; {@value #MIN_MEMBERS} to {@value #MAX_MEMBERS_LIMIT}.
     *     Applies to new invites and accepts; a co-op already above a lowered cap keeps its members.
     * @param inviteExpiryMinutes lifetime of an invitation, 1 to {@value #MAX_EXPIRY_MINUTES} minutes
     * @param kickVoteExpiryMinutes lifetime of a kick vote, 1 to {@value #MAX_EXPIRY_MINUTES} minutes
     * @param kickVoteInactiveDays in a two-member co-op, how long the target must have been inactive before a kick
     *     vote may start, 1 to {@value #MAX_INACTIVE_DAYS} days
     * @param allowedTypes the {@linkplain dev.iyanz.sourbycraft.api.profile.ProfileType policy types} whose solo
     *     profile may be converted to co-op; never empty. Checked at conversion only.
     * @param crossNode Build 56: members of one co-op may be on different nodes at once. {@code false} (default) is
     *     owner decision OD-1 of Builds 53–55.1: one node per co-op, {@code WRONG_NODE} elsewhere. {@code true}
     *     needs {@code mode: distributed} and {@code repository: redis} (refused at start otherwise): every member
     *     save is fenced by the member's own session claim {@code Q} and the stream's revisions, and every shared
     *     record (bank, collections, membership, label) by the profile transaction claim {@code P} and its
     *     revision CAS, so the node claim is only the marker of the node that hosts the profile's bound worlds.
     * @param worldHost Build 56, read with {@code crossNode} only: whether this node prepares and claims the bound
     *     worlds of co-op profiles. {@code true} (default) = a world host, which behaves exactly as Build 55.1 (one
     *     world host per co-op, {@code WRONG_NODE} on a second). {@code false} = a <em>satellite</em> (a spawn or
     *     lobby node): it takes no node claim, never prepares, loads or creates a bound world, refuses no join or
     *     shared operation as {@code WRONG_NODE}, and needs {@code switching.enabled: false} (refused at start
     *     otherwise). {@code false} with {@code crossNode} off is refused at start.
     */
    public record Coop(boolean enabled, int maxMembers, int inviteExpiryMinutes, int kickVoteExpiryMinutes,
                       int kickVoteInactiveDays, Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowedTypes,
                       boolean crossNode, boolean worldHost) {

        public static final int MIN_MEMBERS = 2;
        public static final int MAX_MEMBERS_LIMIT = 8;
        public static final int MAX_EXPIRY_MINUTES = 1440;
        public static final int MAX_INACTIVE_DAYS = 3650;

        /** The Build 52–55.1 shape: one node per co-op (OD-1), this node a world host. */
        public Coop(final boolean enabled, final int maxMembers, final int inviteExpiryMinutes, final int kickVoteExpiryMinutes,
                    final int kickVoteInactiveDays, final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowedTypes) {
            this(enabled, maxMembers, inviteExpiryMinutes, kickVoteExpiryMinutes, kickVoteInactiveDays, allowedTypes, false, true);
        }

        public Coop {
            Objects.requireNonNull(allowedTypes, "allowedTypes");
            if (maxMembers < MIN_MEMBERS || maxMembers > MAX_MEMBERS_LIMIT) {
                throw new IllegalArgumentException("coop.max-members must be " + MIN_MEMBERS + "-" + MAX_MEMBERS_LIMIT);
            }
            if (inviteExpiryMinutes < 1 || inviteExpiryMinutes > MAX_EXPIRY_MINUTES
                || kickVoteExpiryMinutes < 1 || kickVoteExpiryMinutes > MAX_EXPIRY_MINUTES) {
                throw new IllegalArgumentException("coop invite/vote expiry must be 1-" + MAX_EXPIRY_MINUTES + " minutes");
            }
            if (kickVoteInactiveDays < 1 || kickVoteInactiveDays > MAX_INACTIVE_DAYS) {
                throw new IllegalArgumentException("coop.kick-vote.inactive-days must be 1-" + MAX_INACTIVE_DAYS);
            }
            if (allowedTypes.isEmpty()) {
                throw new IllegalArgumentException("coop.allowed-types must name at least one type");
            }
            allowedTypes = Collections.unmodifiableSet(EnumSet.copyOf(allowedTypes));
        }

        public long inviteExpiryMillis() {
            return this.inviteExpiryMinutes * 60_000L;
        }

        public long kickVoteExpiryMillis() {
            return this.kickVoteExpiryMinutes * 60_000L;
        }

        public long inactiveMillis() {
            return this.kickVoteInactiveDays * 86_400_000L;
        }

        /** Whether a solo profile of this policy type may be converted to co-op. */
        public boolean allows(final dev.iyanz.sourbycraft.api.profile.ProfileType type) {
            return this.allowedTypes.contains(type);
        }

        /** Build 56: a satellite node (cross-node on, not a world host); see {@link #worldHost}. */
        public boolean satellite() {
            return this.crossNode && !this.worldHost;
        }
    }

    /**
     * Shutdown capture (Build 53). RESTART_REQUIRED. At server shutdown MultiProfile captures and saves the online
     * players before the server removes them, then closes; this is the bound on that work.
     *
     * @param captureDeadlineMillis how long shutdown waits for the quit captures and saves, {@value #MIN_MILLIS} to
     *     {@value #MAX_MILLIS} ms. When it passes, players not yet saved are left with their pending intent (or
     *     their last committed snapshot) for recovery, and shutdown continues.
     */
    public record Shutdown(int captureDeadlineMillis) {

        public static final int MIN_MILLIS = 1000;
        public static final int MAX_MILLIS = 60_000;

        public Shutdown {
            if (captureDeadlineMillis < MIN_MILLIS || captureDeadlineMillis > MAX_MILLIS) {
                throw new IllegalArgumentException("shutdown.capture-deadline-ms must be " + MIN_MILLIS + "-" + MAX_MILLIS);
            }
        }
    }

    /**
     * Login restore (Build 53). RESTART_REQUIRED.
     *
     * @param authoritative {@link Authoritative#TRUE}: on join the committed state of the player's active profile (the
     *     member head in a co-op) is restored, every vanilla player save publishes a profile snapshot, and a head
     *     that cannot be read refuses the join ({@code HEAD_UNREADABLE}) instead of loading vanilla {@code playerdata}.
     *     {@link Authoritative#FALSE}: the Build 52 behaviour, in which the join keeps whatever vanilla playerdata
     *     loaded unless a switch is recovered; co-op member play is refused while it does not apply.
     *     {@link Authoritative#AUTO} (default): authoritative exactly when {@code coop.enabled} is true, so a server
     *     without co-op keeps the Build 52 solo behaviour unchanged.
     */
    public record Login(Authoritative authoritative) {

        public Login {
            Objects.requireNonNull(authoritative, "authoritative");
        }

        /** {@code true} = {@link Authoritative#TRUE}, {@code false} = {@link Authoritative#FALSE}. */
        public Login(final boolean authoritative) {
            this(authoritative ? Authoritative.TRUE : Authoritative.FALSE);
        }

        /** Whether authoritative login applies given whether co-op is enabled. */
        public boolean applies(final boolean coopEnabled) {
            return switch (this.authoritative) {
                case TRUE -> true;
                case FALSE -> false;
                case AUTO -> coopEnabled;
            };
        }
    }

    /** The three values of {@code login.authoritative}. */
    public enum Authoritative {
        AUTO("auto"), TRUE("true"), FALSE("false");

        private final String id;

        Authoritative(final String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        /** Parses {@code auto}, {@code true} or {@code false} (a YAML boolean included); anything else is refused. */
        public static Authoritative parse(final Object value) {
            if (value instanceof Boolean bool) return bool ? TRUE : FALSE;
            final String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            for (final Authoritative mode : values()) {
                if (mode.id.equals(text)) return mode;
            }
            throw new IllegalArgumentException("login.authoritative must be auto, true or false: " + value);
        }
    }

    /**
     * Adapters an operator forces to REQUIRED (Build 53). RESTART_REQUIRED.
     *
     * @param required adapter ids ({@code [a-z0-9_.:-]{1,64}}, at most {@value #MAX_REQUIRED}) that are treated as
     *     {@code REQUIRED} whatever their descriptor declares. An operator can only raise strictness: an adapter
     *     that declares REQUIRED stays REQUIRED when it is not listed.
     * @param preserveUnregistered Build 55.1: adapter ids (same form and cap) whose stored REQUIRED data is carried
     *     through this node unchanged when <em>no</em> plugin registers the id here, instead of refusing the apply
     *     with {@code ADAPTER_MISSING}. For a node that by design runs without the plugin that owns the data (a lobby
     *     without the island plugin): the bytes are never applied, never captured and never dropped on this node, and
     *     the stored required record keeps naming them. An id that <em>is</em> registered here is not affected (an
     *     UNSUPPORTED or older registration still refuses). Both lists may name the same id; the preserve rule only
     *     matters while the id is unregistered
     */
    public record Adapters(Set<String> required, Set<String> preserveUnregistered) {

        public static final int MAX_REQUIRED = 32;
        private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9_.:-]{1,64}");

        /** The Build 53 shape: nothing preserved. */
        public Adapters(final Set<String> required) {
            this(required, Set.of());
        }

        public Adapters {
            Objects.requireNonNull(required, "required");
            Objects.requireNonNull(preserveUnregistered, "preserveUnregistered");
            if (required.size() > MAX_REQUIRED) {
                throw new IllegalArgumentException("adapters.required lists more than " + MAX_REQUIRED + " ids");
            }
            if (preserveUnregistered.size() > MAX_REQUIRED) {
                throw new IllegalArgumentException("adapters.preserve-unregistered lists more than " + MAX_REQUIRED + " ids");
            }
            for (final String id : required) {
                if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("adapter id: " + id);
            }
            for (final String id : preserveUnregistered) {
                if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("adapter id: " + id);
            }
            required = Collections.unmodifiableSet(new java.util.TreeSet<>(required));
            preserveUnregistered = Collections.unmodifiableSet(new java.util.TreeSet<>(preserveUnregistered));
        }

        /** Whether an operator forces this adapter id to REQUIRED. */
        public boolean forces(final String adapterId) {
            return this.required.contains(adapterId);
        }

        /** Build 55.1: whether stored REQUIRED data of this id passes through unchanged while no plugin registers it here. */
        public boolean preserves(final String adapterId) {
            return this.preserveUnregistered.contains(adapterId);
        }

        static boolean validId(final Object id) {
            return id instanceof String text && ID.matcher(text).matches();
        }
    }

    /** What a profile whose type has no known definition is allowed (Build 54). */
    public enum UnknownTypePolicy {
        /** Every action is refused (ACTION_FORBIDDEN_BY_TYPE); the default. */
        DENY,
        /** Every action is allowed, as for a classic profile. */
        ALLOW;

        public static UnknownTypePolicy parse(final Object value) {
            if (value instanceof Boolean) throw new IllegalArgumentException("unknown-type-policy is deny or allow");
            try {
                return valueOf(String.valueOf(value).strip().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException invalid) {
                throw new IllegalArgumentException("unknown-type-policy is deny or allow, not " + value);
            }
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The profile type registry (Build 54, {@link ProfileTypes}).
     *
     * @param allowOverrideBuiltins whether a plugin may register its own definition for a built-in type id
     *     ({@code classic}, {@code ironman}, {@code stranded}, {@code bingo}); with {@code false} such a registration
     *     is refused and a stored override is ignored with a WARN
     * @param unknownTypePolicy what a profile of a type with no known definition is allowed
     */
    public record TypeRegistry(boolean allowOverrideBuiltins, UnknownTypePolicy unknownTypePolicy) {
        public TypeRegistry {
            Objects.requireNonNull(unknownTypePolicy, "unknownTypePolicy");
        }
    }

    /**
     * The co-op bank policy (Build 54, COOP-DESIGN §8; RESTART_REQUIRED). {@link #policy()} is what the engine uses.
     *
     * @param currencyProvider provider id of the bank currency, {@code [a-z0-9_.-]{1,64}}
     * @param currencyId currency id, {@code [a-z0-9_.-]{1,64}}
     * @param currencyScale decimal places of one minor unit, 0-9 (0 = whole coins)
     * @param sharedCapacity cap of a new shared bank record in minor units, {@code 0} = no cap
     * @param personalCapPercent personal subaccount cap as a percentage of the shared record's capacity, 0-100
     * @param interestPercent interest an interest posting credits, 0-100
     * @param interestIntervalHours the least number of hours between two interest postings of one profile on the store
     *     clock (review P3-22), 0 = no minimum, at most {@link #MAX_INTEREST_INTERVAL_HOURS}
     */
    public record Bank(String currencyProvider, String currencyId, int currencyScale, long sharedCapacity, int personalCapPercent,
                       int interestPercent, int interestIntervalHours) {

        public static final int MAX_SCALE = 9;
        /** One year. */
        public static final int MAX_INTEREST_INTERVAL_HOURS = 24 * 366;
        public static final int DEFAULT_INTEREST_INTERVAL_HOURS = 24;

        /** A bank policy with the default interest interval (24 hours). */
        public Bank(final String currencyProvider, final String currencyId, final int currencyScale, final long sharedCapacity,
                    final int personalCapPercent, final int interestPercent) {
            this(currencyProvider, currencyId, currencyScale, sharedCapacity, personalCapPercent, interestPercent, DEFAULT_INTEREST_INTERVAL_HOURS);
        }

        public Bank {
            if (interestIntervalHours < 0 || interestIntervalHours > MAX_INTEREST_INTERVAL_HOURS) {
                throw new IllegalArgumentException("interest interval hours " + interestIntervalHours);
            }
            policyOf(currencyProvider, currencyId, currencyScale, sharedCapacity, personalCapPercent, interestPercent, interestIntervalHours);
        }

        /** The engine's policy value. */
        public ProfileBank.Policy policy() {
            return policyOf(this.currencyProvider, this.currencyId, this.currencyScale, this.sharedCapacity, this.personalCapPercent,
                this.interestPercent, this.interestIntervalHours);
        }

        private static ProfileBank.Policy policyOf(final String provider, final String id, final int scale, final long capacity,
                                                   final int personalCap, final int interest, final int intervalHours) {
            return new ProfileBank.Policy(new ProfileSharedState.Currency(provider, id, scale), capacity, personalCap, interest,
                intervalHours * 60L * 60L * 1000L);
        }

        static Bank of(final ProfileBank.Policy policy) {
            return new Bank(policy.currency().providerId(), policy.currency().currencyId(), policy.currency().scale(),
                policy.sharedCapacity(), policy.personalCapPercent(), policy.interestPercent(),
                (int) (policy.interestIntervalMillis() / (60L * 60L * 1000L)));
        }

        static boolean validCurrencyText(final Object value) {
            return value instanceof String text && text.matches("[a-z0-9_.-]{1,64}");
        }
    }

    /** The record format of stores written before Build 51. */
    public static final int LEGACY_STORE_FORMAT = 1;
    /** The record format Build 51 writes. A store in this format is refused by older builds. */
    public static final int CURRENT_STORE_FORMAT = 2;

    public static final String TYPES_ALLOWED_KEY = "aurora.awf.multiprofile.types.allowed";
    public static final String TYPES_DEFAULT_KEY = "aurora.awf.multiprofile.types.default";
    public static final String NAMES_POLICY_KEY = "aurora.awf.multiprofile.names.policy";
    public static final String MIGRATION_MODE_KEY = "aurora.awf.multiprofile.migration.mode";
    public static final String MIGRATION_BACKUP_DIR_KEY = "aurora.awf.multiprofile.migration.backup-dir";
    public static final String COOP_ENABLED_KEY = "aurora.awf.multiprofile.coop.enabled";
    public static final String COOP_MAX_MEMBERS_KEY = "aurora.awf.multiprofile.coop.max-members";
    public static final String COOP_INVITE_EXPIRY_KEY = "aurora.awf.multiprofile.coop.invite-expiry-minutes";
    public static final String COOP_VOTE_EXPIRY_KEY = "aurora.awf.multiprofile.coop.kick-vote.expiry-minutes";
    public static final String COOP_INACTIVE_DAYS_KEY = "aurora.awf.multiprofile.coop.kick-vote.inactive-days";
    public static final String COOP_ALLOWED_TYPES_KEY = "aurora.awf.multiprofile.coop.allowed-types";
    /** Build 56: co-op members on different nodes at once ({@link Coop#crossNode}). */
    public static final String COOP_CROSS_NODE_KEY = "aurora.awf.multiprofile.coop.cross-node";
    /** Build 56: whether this node hosts the bound worlds of co-op profiles ({@link Coop#worldHost}). */
    public static final String COOP_WORLD_HOST_KEY = "aurora.awf.multiprofile.coop.world-host";
    public static final String SHUTDOWN_CAPTURE_DEADLINE_KEY = "aurora.awf.multiprofile.shutdown.capture-deadline-ms";
    public static final String LOGIN_AUTHORITATIVE_KEY = "aurora.awf.multiprofile.login.authoritative";
    public static final String ADAPTERS_REQUIRED_KEY = "aurora.awf.multiprofile.adapters.required";
    /** Build 55.1 (RESTART_REQUIRED): see {@link Adapters#preserveUnregistered()}. */
    public static final String ADAPTERS_PRESERVE_KEY = "aurora.awf.multiprofile.adapters.preserve-unregistered";
    public static final String TYPES_OVERRIDE_BUILTINS_KEY = "aurora.awf.multiprofile.types.registry.allow-override-builtins";
    public static final String TYPES_UNKNOWN_POLICY_KEY = "aurora.awf.multiprofile.types.unknown-type-policy";
    public static final String BANK_CURRENCY_PROVIDER_KEY = "aurora.awf.multiprofile.bank.currency.provider";
    public static final String BANK_CURRENCY_ID_KEY = "aurora.awf.multiprofile.bank.currency.id";
    public static final String BANK_CURRENCY_SCALE_KEY = "aurora.awf.multiprofile.bank.currency.scale";
    public static final String BANK_SHARED_CAPACITY_KEY = "aurora.awf.multiprofile.bank.shared-capacity";
    public static final String BANK_PERSONAL_CAP_KEY = "aurora.awf.multiprofile.bank.personal-cap-percent";
    public static final String BANK_INTEREST_KEY = "aurora.awf.multiprofile.bank.interest-percent";
    public static final String BANK_INTEREST_INTERVAL_KEY = "aurora.awf.multiprofile.bank.interest-interval-hours";

    public static final String ENABLED_KEY = "aurora.awf.multiprofile.enabled";
    public static final String MODE_KEY = "aurora.awf.multiprofile.mode";
    public static final String DEFAULT_SLOTS_KEY = "aurora.awf.multiprofile.default-slots";
    public static final String MAX_SLOTS_KEY = "aurora.awf.multiprofile.max-slots";
    public static final String SWITCHING_ENABLED_KEY = "aurora.awf.multiprofile.switching.enabled";
    public static final String SWITCHING_TIMEOUT_KEY = "aurora.awf.multiprofile.switching.timeout-ms";
    public static final String SWITCHING_DURABLE_KEY = "aurora.awf.multiprofile.switching.require-durable-save";
    public static final String WORLDS_IDLE_KEY = "aurora.awf.multiprofile.worlds.idle-unload-seconds";
    public static final String COORDINATION_PROVIDER_KEY = "aurora.awf.multiprofile.coordination.provider";
    public static final String COORDINATION_FENCING_KEY = "aurora.awf.multiprofile.coordination.fencing-enabled";
    public static final String COORDINATION_SINGLE_SESSION_KEY = "aurora.awf.multiprofile.coordination.single-active-session";
    /** Build 55.1 (RESTART_REQUIRED): see {@link Coordination#handoffWaitMillis()}. */
    public static final String COORDINATION_HANDOFF_WAIT_KEY = "aurora.awf.multiprofile.coordination.handoff-wait-ms";
    public static final String DIRECTORY_KEY = "aurora.awf.multiprofile.directory";
    public static final String DIRECTORY_DEFAULT = "awf/profiles";
    public static final String REPOSITORY_KEY = "aurora.awf.multiprofile.repository";
    public static final String REDIS_REQUIRE_AOF_KEY = "aurora.awf.multiprofile.redis.require-aof";
    public static final String REDIS_WAIT_FOR_AOF_KEY = "aurora.awf.multiprofile.redis.wait-for-aof";
    public static final String SWITCHING_COOLDOWN_KEY = "aurora.awf.multiprofile.switching.cooldown-seconds";
    public static final String AUDIT_ENABLED_KEY = "aurora.awf.multiprofile.audit.enabled";
    public static final String AUDIT_RETENTION_KEY = "aurora.awf.multiprofile.audit.retention-days";
    public static final String IO_PARTITIONS_KEY = "aurora.awf.multiprofile.io.partitions";

    public static final List<String> KEYS = List.of(ENABLED_KEY, MODE_KEY, DEFAULT_SLOTS_KEY, MAX_SLOTS_KEY,
        SWITCHING_ENABLED_KEY, SWITCHING_TIMEOUT_KEY, SWITCHING_DURABLE_KEY,
        ProfileIsolation.INVENTORY_KEY, ProfileIsolation.ARMOR_KEY, ProfileIsolation.ENDER_CHEST_KEY,
        ProfileIsolation.EXPERIENCE_KEY, ProfileIsolation.HEALTH_KEY, ProfileIsolation.FOOD_KEY,
        ProfileIsolation.POTION_EFFECTS_KEY, ProfileIsolation.LOCATION_KEY,
        WORLDS_IDLE_KEY,
        COORDINATION_PROVIDER_KEY, COORDINATION_FENCING_KEY, COORDINATION_SINGLE_SESSION_KEY, COORDINATION_HANDOFF_WAIT_KEY,
        DIRECTORY_KEY,
        REPOSITORY_KEY, REDIS_REQUIRE_AOF_KEY, REDIS_WAIT_FOR_AOF_KEY,
        TYPES_ALLOWED_KEY, TYPES_DEFAULT_KEY, NAMES_POLICY_KEY, MIGRATION_MODE_KEY, MIGRATION_BACKUP_DIR_KEY,
        COOP_ENABLED_KEY, COOP_MAX_MEMBERS_KEY, COOP_INVITE_EXPIRY_KEY, COOP_VOTE_EXPIRY_KEY, COOP_INACTIVE_DAYS_KEY,
        COOP_ALLOWED_TYPES_KEY, SHUTDOWN_CAPTURE_DEADLINE_KEY, LOGIN_AUTHORITATIVE_KEY, ADAPTERS_REQUIRED_KEY, ADAPTERS_PRESERVE_KEY,
        TYPES_OVERRIDE_BUILTINS_KEY, TYPES_UNKNOWN_POLICY_KEY, COOP_CROSS_NODE_KEY, COOP_WORLD_HOST_KEY,
        BANK_CURRENCY_PROVIDER_KEY, BANK_CURRENCY_ID_KEY, BANK_CURRENCY_SCALE_KEY, BANK_SHARED_CAPACITY_KEY, BANK_PERSONAL_CAP_KEY,
        BANK_INTEREST_KEY, BANK_INTEREST_INTERVAL_KEY,
        SWITCHING_COOLDOWN_KEY, AUDIT_ENABLED_KEY, AUDIT_RETENTION_KEY, IO_PARTITIONS_KEY);

    /** The file repository; a server that sets no repository key keeps the behaviour of earlier builds. */
    public static final Repository DEFAULT_REPOSITORY = new Repository(Backend.FILE, true, true);

    /** classic, ironman, stranded and bingo allowed; classic when a request names none. */
    public static final Types DEFAULT_TYPES = new Types(EnumSet.allOf(dev.iyanz.sourbycraft.api.profile.ProfileType.class),
        dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC);
    public static final Names DEFAULT_NAMES = new Names(NamePolicy.CUTE);
    /** No migration: a format-1 store is left alone. */
    public static final Migration DEFAULT_MIGRATION = new Migration(MigrationMode.OFF, "");
    /**
     * Co-op off; up to 5 members; invitations last 5 minutes, kick votes 10 minutes; a two-member co-op can remove the
     * other member after 35 days of inactivity; only classic profiles convert.
     */
    public static final Coop DEFAULT_COOP = new Coop(false, 5, 5, 10, 35,
        EnumSet.of(dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC));

    /** Quit captures at shutdown get 10 s. */
    public static final Shutdown DEFAULT_SHUTDOWN = new Shutdown(10_000);
    /** Authoritative login {@code auto}: on exactly when co-op is enabled. */
    public static final Login DEFAULT_LOGIN = new Login(Authoritative.AUTO);
    /** No adapter is forced to REQUIRED by configuration. */
    public static final Adapters DEFAULT_ADAPTERS = new Adapters(Set.of());
    /** Built-in types may be overridden by a plugin; an unknown type is denied every action. */
    public static final TypeRegistry DEFAULT_TYPE_REGISTRY = new TypeRegistry(true, UnknownTypePolicy.DENY);
    /** Hypixel parity ({@link ProfileBank.Policy#DEFAULT}): aurora:coins at scale 0, no shared cap, personal 20 %, interest 10 %. */
    public static final Bank DEFAULT_BANK = Bank.of(ProfileBank.Policy.DEFAULT);
    /** Build 55: the audit log on, daily files kept 30 days. */
    public static final Audit DEFAULT_AUDIT = new Audit(true, 30);
    /** Build 55: one partition (the Build 54 queue behaviour). */
    public static final Io DEFAULT_IO = new Io(1);

    public static final ProfileSettings DEFAULT = new ProfileSettings(false, Mode.STANDALONE, 3, 8,
        new Switching(true, 15_000, true, 0), ProfileIsolation.ALL, new Worlds(300),
        new Coordination(Provider.LOCAL, true, true), DIRECTORY_DEFAULT, DEFAULT_REPOSITORY,
        DEFAULT_TYPES, DEFAULT_NAMES, DEFAULT_MIGRATION, DEFAULT_COOP, DEFAULT_SHUTDOWN, DEFAULT_LOGIN, DEFAULT_ADAPTERS,
        DEFAULT_TYPE_REGISTRY, DEFAULT_BANK, DEFAULT_AUDIT, DEFAULT_IO);

    /** Settings with {@link #DEFAULT_REPOSITORY} and the Build 51 defaults. */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            DEFAULT_REPOSITORY);
    }

    /** Settings with the Build 51 defaults ({@link #DEFAULT_TYPES}, {@link #DEFAULT_NAMES}, {@link #DEFAULT_MIGRATION}) and co-op off. */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, DEFAULT_TYPES, DEFAULT_NAMES, DEFAULT_MIGRATION);
    }

    /** Settings with the Build 52 co-op defaults ({@link #DEFAULT_COOP}: co-op off). */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository,
                           final Types types, final Names names, final Migration migration) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, types, names, migration, DEFAULT_COOP);
    }

    /** Settings with the Build 53 defaults ({@link #DEFAULT_SHUTDOWN}, {@link #DEFAULT_LOGIN}, {@link #DEFAULT_ADAPTERS}). */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository,
                           final Types types, final Names names, final Migration migration, final Coop coop) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, types, names, migration, coop, DEFAULT_SHUTDOWN, DEFAULT_LOGIN, DEFAULT_ADAPTERS);
    }

    /** Settings with the Build 54 type-registry default ({@link #DEFAULT_TYPE_REGISTRY}). */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository,
                           final Types types, final Names names, final Migration migration, final Coop coop,
                           final Shutdown shutdown, final Login login, final Adapters adapters) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, types, names, migration, coop, shutdown, login, adapters, DEFAULT_TYPE_REGISTRY);
    }

    /** Settings with the Build 54 bank default ({@link #DEFAULT_BANK}). */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository,
                           final Types types, final Names names, final Migration migration, final Coop coop,
                           final Shutdown shutdown, final Login login, final Adapters adapters, final TypeRegistry typeRegistry) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, types, names, migration, coop, shutdown, login, adapters, typeRegistry, DEFAULT_BANK);
    }

    /** Settings with the Build 55 defaults ({@link #DEFAULT_AUDIT}, {@link #DEFAULT_IO}). */
    public ProfileSettings(final boolean enabled, final Mode mode, final int defaultSlots, final int maxSlots,
                           final Switching switching, final ProfileIsolation isolation, final Worlds worlds,
                           final Coordination coordination, final String directory, final Repository repository,
                           final Types types, final Names names, final Migration migration, final Coop coop,
                           final Shutdown shutdown, final Login login, final Adapters adapters, final TypeRegistry typeRegistry,
                           final Bank bank) {
        this(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, directory,
            repository, types, names, migration, coop, shutdown, login, adapters, typeRegistry, bank, DEFAULT_AUDIT, DEFAULT_IO);
    }

    public ProfileSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(switching, "switching");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(coordination, "coordination");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(migration, "migration");
        Objects.requireNonNull(coop, "coop");
        Objects.requireNonNull(shutdown, "shutdown");
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(adapters, "adapters");
        Objects.requireNonNull(typeRegistry, "typeRegistry");
        Objects.requireNonNull(bank, "bank");
        if (defaultSlots < 1 || maxSlots < 1 || defaultSlots > maxSlots || maxSlots > PlayerAccount.MAX_SLOTS) {
            throw new IllegalArgumentException("default-slots and max-slots must satisfy 1 <= default <= max <= "
                + PlayerAccount.MAX_SLOTS);
        }
        if (directory == null || directory.isBlank() || directory.length() > 128
            || directory.contains("..") || directory.startsWith("/") || directory.contains("\\")) {
            throw new IllegalArgumentException("multiprofile.directory must be a relative path without '..': " + directory);
        }
        if (!migration.backupDir().isBlank() && overlaps(directory, migration.backupDir())) {
            throw new IllegalArgumentException("migration.backup-dir must not be inside or contain the store directory "
                + directory + ": " + migration.backupDir());
        }
    }

    /**
     * Textual overlap of the store directory (relative) and a backup directory. A relative backup directory is
     * compared to the store directory directly; an absolute one cannot be compared without the server directory,
     * so it is accepted here and must be checked by whoever resolves it.
     */
    static boolean overlaps(final String directory, final String backupDir) {
        try {
            final Path store = Path.of(directory).normalize();
            final Path backup = Path.of(backupDir).normalize();
            if (store.isAbsolute() != backup.isAbsolute()) return false;
            return backup.startsWith(store) || store.startsWith(backup);
        } catch (final InvalidPathException unusable) {
            return true;
        }
    }

    /** Whether a backup directory text is usable: no NUL, no {@code ..} segment, at most 512 characters. */
    static boolean validBackupDir(final String text) {
        if (text.isBlank() || text.length() > 512 || text.indexOf('\0') >= 0) return false;
        try {
            for (final Path part : Path.of(text)) {
                if (part.toString().equals("..")) return false;
            }
            return true;
        } catch (final InvalidPathException unusable) {
            return false;
        }
    }

    /** Whether distributed mode was requested. Distributed additionally requires the Redis repository. */
    public boolean distributed() {
        return this.mode == Mode.DISTRIBUTED;
    }

    /**
     * Whether co-op membership operations are accepted: MultiProfile and {@code coop.enabled} are both on. The
     * single check every co-op entry point makes; {@code coop().enabled()} alone ignores the master switch.
     */
    public boolean coopEnabled() {
        return this.enabled && this.coop.enabled();
    }

    /**
     * Whether join restores the committed state of the active profile ({@code login.authoritative}) with the
     * feature on. Co-op member play needs it; {@code coopEnabled()} with this false means members cannot switch in.
     */
    public boolean authoritativeLogin() {
        return this.enabled && this.login.applies(this.coop.enabled());
    }

    /**
     * Build 56: whether members of one co-op may be on different nodes at once ({@code coop.cross-node} with the
     * feature on). Independent of {@code coop.enabled}: a satellite that accepts no co-op operation still admits a
     * co-op member's join without the node claim.
     */
    public boolean crossNodeCoop() {
        return this.enabled && this.coop.crossNode();
    }

    /**
     * Build 56: whether this node is a satellite ({@code coop.cross-node: true}, {@code coop.world-host: false}): no
     * node claim on any profile, no bound world prepared, no {@code WRONG_NODE}. {@code false} on every other node,
     * which behaves as Build 55.1 (a world host, or OD-1 with cross-node off).
     */
    public boolean satellite() {
        return crossNodeCoop() && !this.coop.worldHost();
    }

    /** Whether profiles are stored in the Aurora Redis Fabric instead of the directory. */
    public boolean redisRepository() {
        return this.repository.backend() == Backend.REDIS;
    }

    /** Settings and the keys that were present but unusable (each falls back to its default). */
    public record Parsed(ProfileSettings settings, List<String> invalidKeys) {
        public Parsed {
            invalidKeys = List.copyOf(invalidKeys);
        }
    }

    public static Parsed parse(final Map<String, Object> values) {
        final List<String> invalid = new ArrayList<>();
        final boolean enabled = flag(values, ENABLED_KEY, DEFAULT.enabled(), invalid);
        Mode mode = DEFAULT.mode();
        if (values.get(MODE_KEY) != null) {
            try {
                mode = Mode.parse(String.valueOf(values.get(MODE_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(MODE_KEY);
            }
        }
        final int defaultSlots = whole(values, DEFAULT_SLOTS_KEY, DEFAULT.defaultSlots(), 1, PlayerAccount.MAX_SLOTS, invalid);
        int maxSlots = whole(values, MAX_SLOTS_KEY, DEFAULT.maxSlots(), 1, PlayerAccount.MAX_SLOTS, invalid);
        if (maxSlots < defaultSlots) {
            invalid.add(MAX_SLOTS_KEY);
            maxSlots = Math.max(defaultSlots, DEFAULT.maxSlots());
        }
        final Switching switching = new Switching(
            flag(values, SWITCHING_ENABLED_KEY, DEFAULT.switching().enabled(), invalid),
            whole(values, SWITCHING_TIMEOUT_KEY, DEFAULT.switching().timeoutMillis(), 1000, 600_000, invalid),
            flag(values, SWITCHING_DURABLE_KEY, DEFAULT.switching().requireDurableSave(), invalid),
            whole(values, SWITCHING_COOLDOWN_KEY, DEFAULT.switching().cooldownSeconds(), 0, Switching.MAX_COOLDOWN_SECONDS, invalid));
        final ProfileIsolation isolation = new ProfileIsolation(
            flag(values, ProfileIsolation.INVENTORY_KEY, DEFAULT.isolation().inventory(), invalid),
            flag(values, ProfileIsolation.ARMOR_KEY, DEFAULT.isolation().armor(), invalid),
            flag(values, ProfileIsolation.ENDER_CHEST_KEY, DEFAULT.isolation().enderChest(), invalid),
            flag(values, ProfileIsolation.EXPERIENCE_KEY, DEFAULT.isolation().experience(), invalid),
            flag(values, ProfileIsolation.HEALTH_KEY, DEFAULT.isolation().health(), invalid),
            flag(values, ProfileIsolation.FOOD_KEY, DEFAULT.isolation().food(), invalid),
            flag(values, ProfileIsolation.POTION_EFFECTS_KEY, DEFAULT.isolation().potionEffects(), invalid),
            flag(values, ProfileIsolation.LOCATION_KEY, DEFAULT.isolation().location(), invalid));
        final Worlds worlds = new Worlds(
            whole(values, WORLDS_IDLE_KEY, DEFAULT.worlds().idleUnloadSeconds(), 0, 86_400, invalid));
        Provider provider = DEFAULT.coordination().provider();
        if (values.get(COORDINATION_PROVIDER_KEY) != null) {
            try {
                provider = Provider.parse(String.valueOf(values.get(COORDINATION_PROVIDER_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(COORDINATION_PROVIDER_KEY);
            }
        }
        final Coordination coordination = new Coordination(provider,
            flag(values, COORDINATION_FENCING_KEY, DEFAULT.coordination().fencingEnabled(), invalid),
            flag(values, COORDINATION_SINGLE_SESSION_KEY, DEFAULT.coordination().singleActiveSession(), invalid),
            whole(values, COORDINATION_HANDOFF_WAIT_KEY, DEFAULT.coordination().handoffWaitMillis(), 0, HANDOFF_WAIT_MAX_MILLIS, invalid));
        String directory = DEFAULT.directory();
        if (values.get(DIRECTORY_KEY) instanceof String text) {
            try {
                new ProfileSettings(true, mode, defaultSlots, maxSlots, switching, isolation, worlds, coordination, text);
                directory = text;
            } catch (final IllegalArgumentException bad) {
                invalid.add(DIRECTORY_KEY);
            }
        } else if (values.containsKey(DIRECTORY_KEY)) {
            invalid.add(DIRECTORY_KEY);
        }
        Backend backend = DEFAULT_REPOSITORY.backend();
        if (values.get(REPOSITORY_KEY) != null) {
            try {
                backend = Backend.parse(String.valueOf(values.get(REPOSITORY_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(REPOSITORY_KEY);
            }
        }
        final Repository repository = new Repository(backend,
            flag(values, REDIS_REQUIRE_AOF_KEY, DEFAULT_REPOSITORY.requireAof(), invalid),
            flag(values, REDIS_WAIT_FOR_AOF_KEY, DEFAULT_REPOSITORY.waitForAof(), invalid));
        final Types types = types(values, invalid);
        final Names names = names(values, invalid);
        final Migration migration = migration(values, directory, invalid);
        final Coop coop = coop(values, invalid);
        final Shutdown shutdown = new Shutdown(whole(values, SHUTDOWN_CAPTURE_DEADLINE_KEY,
            DEFAULT_SHUTDOWN.captureDeadlineMillis(), Shutdown.MIN_MILLIS, Shutdown.MAX_MILLIS, invalid));
        Login login = DEFAULT_LOGIN;
        if (values.get(LOGIN_AUTHORITATIVE_KEY) != null) {
            try {
                login = new Login(Authoritative.parse(values.get(LOGIN_AUTHORITATIVE_KEY)));
            } catch (final IllegalArgumentException bad) {
                invalid.add(LOGIN_AUTHORITATIVE_KEY);
            }
        }
        final Adapters adapters = adapters(values, invalid);
        UnknownTypePolicy unknownTypePolicy = DEFAULT_TYPE_REGISTRY.unknownTypePolicy();
        if (values.get(TYPES_UNKNOWN_POLICY_KEY) != null) {
            try {
                unknownTypePolicy = UnknownTypePolicy.parse(values.get(TYPES_UNKNOWN_POLICY_KEY));
            } catch (final IllegalArgumentException bad) {
                invalid.add(TYPES_UNKNOWN_POLICY_KEY);
            }
        }
        final TypeRegistry typeRegistry = new TypeRegistry(
            flag(values, TYPES_OVERRIDE_BUILTINS_KEY, DEFAULT_TYPE_REGISTRY.allowOverrideBuiltins(), invalid), unknownTypePolicy);
        final Bank bank = bank(values, invalid);
        final Audit audit = new Audit(flag(values, AUDIT_ENABLED_KEY, DEFAULT_AUDIT.enabled(), invalid),
            whole(values, AUDIT_RETENTION_KEY, DEFAULT_AUDIT.retentionDays(), 1, Audit.MAX_RETENTION_DAYS, invalid));
        final Io io = new Io(whole(values, IO_PARTITIONS_KEY, DEFAULT_IO.partitions(), 1, ProfileIoQueue.MAX_PARTITIONS, invalid));
        return new Parsed(new ProfileSettings(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds,
            coordination, directory, repository, types, names, migration, coop, shutdown, login, adapters, typeRegistry, bank,
            audit, io), invalid);
    }

    /** The bank policy; each unusable key falls back to its own default and is reported. */
    private static Bank bank(final Map<String, Object> values, final List<String> invalid) {
        String provider = DEFAULT_BANK.currencyProvider();
        if (values.get(BANK_CURRENCY_PROVIDER_KEY) != null) {
            if (Bank.validCurrencyText(values.get(BANK_CURRENCY_PROVIDER_KEY))) {
                provider = (String) values.get(BANK_CURRENCY_PROVIDER_KEY);
            } else {
                invalid.add(BANK_CURRENCY_PROVIDER_KEY);
            }
        }
        String currency = DEFAULT_BANK.currencyId();
        if (values.get(BANK_CURRENCY_ID_KEY) != null) {
            if (Bank.validCurrencyText(values.get(BANK_CURRENCY_ID_KEY))) {
                currency = (String) values.get(BANK_CURRENCY_ID_KEY);
            } else {
                invalid.add(BANK_CURRENCY_ID_KEY);
            }
        }
        final int scale = whole(values, BANK_CURRENCY_SCALE_KEY, DEFAULT_BANK.currencyScale(), 0, Bank.MAX_SCALE, invalid);
        long capacity = DEFAULT_BANK.sharedCapacity();
        final Object rawCapacity = values.get(BANK_SHARED_CAPACITY_KEY);
        if (rawCapacity != null) {
            final long parsed = wholeLong(rawCapacity);
            if (parsed >= 0) {
                capacity = parsed;
            } else {
                invalid.add(BANK_SHARED_CAPACITY_KEY);
            }
        }
        final int personal = whole(values, BANK_PERSONAL_CAP_KEY, DEFAULT_BANK.personalCapPercent(), 0, ProfileBank.Policy.MAX_PERCENT, invalid);
        final int interest = whole(values, BANK_INTEREST_KEY, DEFAULT_BANK.interestPercent(), 0, ProfileBank.Policy.MAX_PERCENT, invalid);
        final int interval = whole(values, BANK_INTEREST_INTERVAL_KEY, DEFAULT_BANK.interestIntervalHours(), 0, Bank.MAX_INTEREST_INTERVAL_HOURS,
            invalid);
        return new Bank(provider, currency, scale, capacity, personal, interest, interval);
    }

    /** A non-negative whole number up to {@link Long#MAX_VALUE}, or {@code -1} when the value is not one. */
    private static long wholeLong(final Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            final long whole = ((Number) value).longValue();
            return whole >= 0 ? whole : -1L;
        }
        if (value instanceof java.math.BigInteger big) return big.signum() >= 0 && big.bitLength() < Long.SIZE ? big.longValue() : -1L;
        return -1L;
    }

    /**
     * The listed adapter ids. A value that is not a list or a comma-separated string, more than
     * {@value Adapters#MAX_REQUIRED} entries, or an entry that is not a well-formed id is reported; the well-formed
     * entries (up to the cap) are kept, so a typo never silently relaxes the other adapters.
     */
    private static Adapters adapters(final Map<String, Object> values, final List<String> invalid) {
        return new Adapters(adapterIds(values, ADAPTERS_REQUIRED_KEY, DEFAULT_ADAPTERS.required(), invalid),
            adapterIds(values, ADAPTERS_PRESERVE_KEY, DEFAULT_ADAPTERS.preserveUnregistered(), invalid));
    }

    /** One adapter-id list key (the rule above), {@code fallback} when absent or not a list/string. */
    private static Set<String> adapterIds(final Map<String, Object> values, final String key, final Set<String> fallback,
                                          final List<String> invalid) {
        final Object raw = values.get(key);
        if (raw == null) return fallback;
        final List<?> items;
        if (raw instanceof Collection<?> collection) {
            items = List.copyOf(collection);
        } else if (raw instanceof String text) {
            items = text.isBlank() ? List.of() : List.of(text.split(","));
        } else {
            invalid.add(key);
            return fallback;
        }
        final Set<String> ids = new java.util.TreeSet<>();
        boolean bad = false;
        for (final Object item : items) {
            final Object id = item instanceof String text ? text.trim() : item;
            if (!Adapters.validId(id)) {
                bad = true;
            } else if (ids.size() < Adapters.MAX_REQUIRED || ids.contains((String) id)) {
                ids.add((String) id);
            } else {
                bad = true;
            }
        }
        if (bad) invalid.add(key);
        return ids;
    }

    /** Each unusable co-op key falls back to its own default and is reported; a bad key never enables co-op. */
    private static Coop coop(final Map<String, Object> values, final List<String> invalid) {
        Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowed = DEFAULT_COOP.allowedTypes();
        final Object raw = values.get(COOP_ALLOWED_TYPES_KEY);
        if (raw != null) {
            final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> parsed = typeSet(raw);
            if (parsed == null) {
                invalid.add(COOP_ALLOWED_TYPES_KEY);
            } else {
                allowed = parsed;
            }
        }
        return new Coop(
            flag(values, COOP_ENABLED_KEY, DEFAULT_COOP.enabled(), invalid),
            whole(values, COOP_MAX_MEMBERS_KEY, DEFAULT_COOP.maxMembers(), Coop.MIN_MEMBERS, Coop.MAX_MEMBERS_LIMIT, invalid),
            whole(values, COOP_INVITE_EXPIRY_KEY, DEFAULT_COOP.inviteExpiryMinutes(), 1, Coop.MAX_EXPIRY_MINUTES, invalid),
            whole(values, COOP_VOTE_EXPIRY_KEY, DEFAULT_COOP.kickVoteExpiryMinutes(), 1, Coop.MAX_EXPIRY_MINUTES, invalid),
            whole(values, COOP_INACTIVE_DAYS_KEY, DEFAULT_COOP.kickVoteInactiveDays(), 1, Coop.MAX_INACTIVE_DAYS, invalid),
            allowed,
            flag(values, COOP_CROSS_NODE_KEY, DEFAULT_COOP.crossNode(), invalid),
            flag(values, COOP_WORLD_HOST_KEY, DEFAULT_COOP.worldHost(), invalid));
    }

    private static Types types(final Map<String, Object> values, final List<String> invalid) {
        Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowed = DEFAULT_TYPES.allowed();
        final Object raw = values.get(TYPES_ALLOWED_KEY);
        if (raw != null) {
            final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> parsed = typeSet(raw);
            if (parsed == null) {
                invalid.add(TYPES_ALLOWED_KEY);
            } else {
                allowed = parsed;
            }
        }
        dev.iyanz.sourbycraft.api.profile.ProfileType defaultType = DEFAULT_TYPES.defaultType();
        final Object rawDefault = values.get(TYPES_DEFAULT_KEY);
        if (rawDefault != null) {
            final var parsed = dev.iyanz.sourbycraft.api.profile.ProfileType.fromId(String.valueOf(rawDefault));
            if (parsed.isPresent()) {
                defaultType = parsed.get();
            } else {
                invalid.add(TYPES_DEFAULT_KEY);
            }
        }
        if (!allowed.contains(defaultType)) {
            // the default must be creatable: use the first allowed type and say which key was wrong
            if (!invalid.contains(TYPES_DEFAULT_KEY)) invalid.add(TYPES_DEFAULT_KEY);
            defaultType = allowed.iterator().next();
        }
        return new Types(allowed, defaultType);
    }

    /** The named types, or {@code null} when the value is not a non-empty list/comma list of known type ids. */
    private static Set<dev.iyanz.sourbycraft.api.profile.ProfileType> typeSet(final Object raw) {
        final List<?> items;
        if (raw instanceof Collection<?> collection) {
            items = List.copyOf(collection);
        } else if (raw instanceof String text) {
            items = List.of(text.split(","));
        } else {
            return null;
        }
        final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> result =
            EnumSet.noneOf(dev.iyanz.sourbycraft.api.profile.ProfileType.class);
        for (final Object item : items) {
            if (!(item instanceof String id)) return null;
            final var type = dev.iyanz.sourbycraft.api.profile.ProfileType.fromId(id);
            if (type.isEmpty()) return null;
            result.add(type.get());
        }
        return result.isEmpty() ? null : result;
    }

    private static Names names(final Map<String, Object> values, final List<String> invalid) {
        final Object raw = values.get(NAMES_POLICY_KEY);
        if (raw == null) return DEFAULT_NAMES;
        try {
            return new Names(NamePolicy.parse(String.valueOf(raw)));
        } catch (final IllegalArgumentException bad) {
            invalid.add(NAMES_POLICY_KEY);
            return DEFAULT_NAMES;
        }
    }

    /**
     * An invalid mode, or {@code apply} without a usable backup directory, falls back to {@code off}: a migration
     * that rewrites the store must never start from a half-valid configuration.
     */
    private static Migration migration(final Map<String, Object> values, final String directory,
                                       final List<String> invalid) {
        MigrationMode mode = DEFAULT_MIGRATION.mode();
        final Object rawMode = values.get(MIGRATION_MODE_KEY);
        if (rawMode != null) {
            try {
                mode = MigrationMode.parse(rawMode);
            } catch (final IllegalArgumentException bad) {
                invalid.add(MIGRATION_MODE_KEY);
            }
        }
        String backupDir = DEFAULT_MIGRATION.backupDir();
        final Object rawDir = values.get(MIGRATION_BACKUP_DIR_KEY);
        if (rawDir instanceof String text) {
            final String stripped = text.strip();
            if (stripped.isEmpty()) {
                backupDir = "";
            } else if (validBackupDir(stripped) && !overlaps(directory, stripped)) {
                backupDir = stripped;
            } else {
                invalid.add(MIGRATION_BACKUP_DIR_KEY);
            }
        } else if (rawDir != null) {
            invalid.add(MIGRATION_BACKUP_DIR_KEY);
        }
        if (mode == MigrationMode.APPLY && backupDir.isEmpty()) {
            if (!invalid.contains(MIGRATION_BACKUP_DIR_KEY) && !invalid.contains(MIGRATION_MODE_KEY)) {
                invalid.add(MIGRATION_MODE_KEY);
            }
            mode = MigrationMode.OFF;
        }
        return new Migration(mode, backupDir);
    }

    /** The configuration rows for {@code AuroraKeys}, in file order. */
    public static Map<String, Object> defaultsAsMap() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put(ENABLED_KEY, DEFAULT.enabled());
        values.put(MODE_KEY, DEFAULT.mode().name().toLowerCase(Locale.ROOT));
        values.put(DEFAULT_SLOTS_KEY, DEFAULT.defaultSlots());
        values.put(MAX_SLOTS_KEY, DEFAULT.maxSlots());
        values.put(SWITCHING_ENABLED_KEY, DEFAULT.switching().enabled());
        values.put(SWITCHING_TIMEOUT_KEY, DEFAULT.switching().timeoutMillis());
        values.put(SWITCHING_DURABLE_KEY, DEFAULT.switching().requireDurableSave());
        values.put(ProfileIsolation.INVENTORY_KEY, DEFAULT.isolation().inventory());
        values.put(ProfileIsolation.ARMOR_KEY, DEFAULT.isolation().armor());
        values.put(ProfileIsolation.ENDER_CHEST_KEY, DEFAULT.isolation().enderChest());
        values.put(ProfileIsolation.EXPERIENCE_KEY, DEFAULT.isolation().experience());
        values.put(ProfileIsolation.HEALTH_KEY, DEFAULT.isolation().health());
        values.put(ProfileIsolation.FOOD_KEY, DEFAULT.isolation().food());
        values.put(ProfileIsolation.POTION_EFFECTS_KEY, DEFAULT.isolation().potionEffects());
        values.put(ProfileIsolation.LOCATION_KEY, DEFAULT.isolation().location());
        values.put(WORLDS_IDLE_KEY, DEFAULT.worlds().idleUnloadSeconds());
        values.put(COORDINATION_PROVIDER_KEY, DEFAULT.coordination().provider().name().toLowerCase(Locale.ROOT));
        values.put(COORDINATION_FENCING_KEY, DEFAULT.coordination().fencingEnabled());
        values.put(COORDINATION_SINGLE_SESSION_KEY, DEFAULT.coordination().singleActiveSession());
        values.put(COORDINATION_HANDOFF_WAIT_KEY, DEFAULT.coordination().handoffWaitMillis());
        values.put(DIRECTORY_KEY, DEFAULT.directory());
        values.put(REPOSITORY_KEY, DEFAULT.repository().backend().name().toLowerCase(Locale.ROOT));
        values.put(REDIS_REQUIRE_AOF_KEY, DEFAULT.repository().requireAof());
        values.put(REDIS_WAIT_FOR_AOF_KEY, DEFAULT.repository().waitForAof());
        values.put(TYPES_ALLOWED_KEY, typeIds(DEFAULT.types().allowed()));
        values.put(TYPES_DEFAULT_KEY, DEFAULT.types().defaultType().id());
        values.put(NAMES_POLICY_KEY, DEFAULT.names().policy().id());
        values.put(MIGRATION_MODE_KEY, DEFAULT.migration().mode().id());
        values.put(MIGRATION_BACKUP_DIR_KEY, DEFAULT.migration().backupDir());
        values.put(COOP_ENABLED_KEY, DEFAULT.coop().enabled());
        values.put(COOP_MAX_MEMBERS_KEY, DEFAULT.coop().maxMembers());
        values.put(COOP_INVITE_EXPIRY_KEY, DEFAULT.coop().inviteExpiryMinutes());
        values.put(COOP_VOTE_EXPIRY_KEY, DEFAULT.coop().kickVoteExpiryMinutes());
        values.put(COOP_INACTIVE_DAYS_KEY, DEFAULT.coop().kickVoteInactiveDays());
        values.put(COOP_ALLOWED_TYPES_KEY, typeIds(DEFAULT.coop().allowedTypes()));
        values.put(SHUTDOWN_CAPTURE_DEADLINE_KEY, DEFAULT.shutdown().captureDeadlineMillis());
        values.put(LOGIN_AUTHORITATIVE_KEY, DEFAULT.login().authoritative().id());
        values.put(ADAPTERS_REQUIRED_KEY, List.copyOf(DEFAULT.adapters().required()));
        values.put(ADAPTERS_PRESERVE_KEY, List.copyOf(DEFAULT.adapters().preserveUnregistered()));
        values.put(TYPES_OVERRIDE_BUILTINS_KEY, DEFAULT.typeRegistry().allowOverrideBuiltins());
        values.put(TYPES_UNKNOWN_POLICY_KEY, DEFAULT.typeRegistry().unknownTypePolicy().id());
        values.put(BANK_CURRENCY_PROVIDER_KEY, DEFAULT.bank().currencyProvider());
        values.put(BANK_CURRENCY_ID_KEY, DEFAULT.bank().currencyId());
        values.put(BANK_CURRENCY_SCALE_KEY, DEFAULT.bank().currencyScale());
        // As the YAML reader returns it: an int when it fits (the default 0), so it equals the AuroraKeys row.
        final long capacity = DEFAULT.bank().sharedCapacity();
        values.put(BANK_SHARED_CAPACITY_KEY, capacity <= Integer.MAX_VALUE ? (Object) (int) capacity : (Object) capacity);
        values.put(BANK_PERSONAL_CAP_KEY, DEFAULT.bank().personalCapPercent());
        values.put(BANK_INTEREST_KEY, DEFAULT.bank().interestPercent());
        values.put(BANK_INTEREST_INTERVAL_KEY, DEFAULT.bank().interestIntervalHours());
        values.put(SWITCHING_COOLDOWN_KEY, DEFAULT.switching().cooldownSeconds());
        values.put(AUDIT_ENABLED_KEY, DEFAULT.audit().enabled());
        values.put(AUDIT_RETENTION_KEY, DEFAULT.audit().retentionDays());
        values.put(IO_PARTITIONS_KEY, DEFAULT.io().partitions());
        values.put(COOP_CROSS_NODE_KEY, DEFAULT.coop().crossNode());
        values.put(COOP_WORLD_HOST_KEY, DEFAULT.coop().worldHost());
        return values;
    }

    /** The identifiers of a type set, in enum order (the form written to the configuration file). */
    public static List<String> typeIds(final Set<dev.iyanz.sourbycraft.api.profile.ProfileType> types) {
        final List<String> ids = new ArrayList<>();
        for (final dev.iyanz.sourbycraft.api.profile.ProfileType type : dev.iyanz.sourbycraft.api.profile.ProfileType.values()) {
            if (types.contains(type)) ids.add(type.id());
        }
        return List.copyOf(ids);
    }

    private static boolean flag(final Map<String, Object> values, final String key, final boolean fallback,
                                final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        invalid.add(key);
        return fallback;
    }

    private static int whole(final Map<String, Object> values, final String key, final int fallback, final int min,
                             final int max, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= max) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }
}
