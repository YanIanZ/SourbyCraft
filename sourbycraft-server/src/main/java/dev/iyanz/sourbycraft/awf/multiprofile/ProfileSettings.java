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
                              Shutdown shutdown, Login login, Adapters adapters) {

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
     */
    public record Switching(boolean enabled, int timeoutMillis, boolean requireDurableSave) {
        public Switching {
            if (timeoutMillis < 1000 || timeoutMillis > 600_000) {
                throw new IllegalArgumentException("switching.timeout-ms must be 1000-600000");
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
     */
    public record Coordination(Provider provider, boolean fencingEnabled, boolean singleActiveSession) {}

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
     */
    public record Coop(boolean enabled, int maxMembers, int inviteExpiryMinutes, int kickVoteExpiryMinutes,
                       int kickVoteInactiveDays, Set<dev.iyanz.sourbycraft.api.profile.ProfileType> allowedTypes) {

        public static final int MIN_MEMBERS = 2;
        public static final int MAX_MEMBERS_LIMIT = 8;
        public static final int MAX_EXPIRY_MINUTES = 1440;
        public static final int MAX_INACTIVE_DAYS = 3650;

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
     */
    public record Adapters(Set<String> required) {

        public static final int MAX_REQUIRED = 32;
        private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9_.:-]{1,64}");

        public Adapters {
            Objects.requireNonNull(required, "required");
            if (required.size() > MAX_REQUIRED) {
                throw new IllegalArgumentException("adapters.required lists more than " + MAX_REQUIRED + " ids");
            }
            for (final String id : required) {
                if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("adapter id: " + id);
            }
            required = Collections.unmodifiableSet(new java.util.TreeSet<>(required));
        }

        /** Whether an operator forces this adapter id to REQUIRED. */
        public boolean forces(final String adapterId) {
            return this.required.contains(adapterId);
        }

        static boolean validId(final Object id) {
            return id instanceof String text && ID.matcher(text).matches();
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
    public static final String SHUTDOWN_CAPTURE_DEADLINE_KEY = "aurora.awf.multiprofile.shutdown.capture-deadline-ms";
    public static final String LOGIN_AUTHORITATIVE_KEY = "aurora.awf.multiprofile.login.authoritative";
    public static final String ADAPTERS_REQUIRED_KEY = "aurora.awf.multiprofile.adapters.required";

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
    public static final String DIRECTORY_KEY = "aurora.awf.multiprofile.directory";
    public static final String DIRECTORY_DEFAULT = "awf/profiles";
    public static final String REPOSITORY_KEY = "aurora.awf.multiprofile.repository";
    public static final String REDIS_REQUIRE_AOF_KEY = "aurora.awf.multiprofile.redis.require-aof";
    public static final String REDIS_WAIT_FOR_AOF_KEY = "aurora.awf.multiprofile.redis.wait-for-aof";

    public static final List<String> KEYS = List.of(ENABLED_KEY, MODE_KEY, DEFAULT_SLOTS_KEY, MAX_SLOTS_KEY,
        SWITCHING_ENABLED_KEY, SWITCHING_TIMEOUT_KEY, SWITCHING_DURABLE_KEY,
        ProfileIsolation.INVENTORY_KEY, ProfileIsolation.ARMOR_KEY, ProfileIsolation.ENDER_CHEST_KEY,
        ProfileIsolation.EXPERIENCE_KEY, ProfileIsolation.HEALTH_KEY, ProfileIsolation.FOOD_KEY,
        ProfileIsolation.POTION_EFFECTS_KEY, ProfileIsolation.LOCATION_KEY,
        WORLDS_IDLE_KEY,
        COORDINATION_PROVIDER_KEY, COORDINATION_FENCING_KEY, COORDINATION_SINGLE_SESSION_KEY, DIRECTORY_KEY,
        REPOSITORY_KEY, REDIS_REQUIRE_AOF_KEY, REDIS_WAIT_FOR_AOF_KEY,
        TYPES_ALLOWED_KEY, TYPES_DEFAULT_KEY, NAMES_POLICY_KEY, MIGRATION_MODE_KEY, MIGRATION_BACKUP_DIR_KEY,
        COOP_ENABLED_KEY, COOP_MAX_MEMBERS_KEY, COOP_INVITE_EXPIRY_KEY, COOP_VOTE_EXPIRY_KEY, COOP_INACTIVE_DAYS_KEY,
        COOP_ALLOWED_TYPES_KEY, SHUTDOWN_CAPTURE_DEADLINE_KEY, LOGIN_AUTHORITATIVE_KEY, ADAPTERS_REQUIRED_KEY);

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

    public static final ProfileSettings DEFAULT = new ProfileSettings(false, Mode.STANDALONE, 3, 8,
        new Switching(true, 15_000, true), ProfileIsolation.ALL, new Worlds(300),
        new Coordination(Provider.LOCAL, true, true), DIRECTORY_DEFAULT, DEFAULT_REPOSITORY,
        DEFAULT_TYPES, DEFAULT_NAMES, DEFAULT_MIGRATION, DEFAULT_COOP, DEFAULT_SHUTDOWN, DEFAULT_LOGIN, DEFAULT_ADAPTERS);

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
            flag(values, SWITCHING_DURABLE_KEY, DEFAULT.switching().requireDurableSave(), invalid));
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
            flag(values, COORDINATION_SINGLE_SESSION_KEY, DEFAULT.coordination().singleActiveSession(), invalid));
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
        return new Parsed(new ProfileSettings(enabled, mode, defaultSlots, maxSlots, switching, isolation, worlds,
            coordination, directory, repository, types, names, migration, coop, shutdown, login, adapters), invalid);
    }

    /**
     * The listed adapter ids. A value that is not a list or a comma-separated string, more than
     * {@value Adapters#MAX_REQUIRED} entries, or an entry that is not a well-formed id is reported; the well-formed
     * entries (up to the cap) are kept, so a typo never silently relaxes the other adapters.
     */
    private static Adapters adapters(final Map<String, Object> values, final List<String> invalid) {
        final Object raw = values.get(ADAPTERS_REQUIRED_KEY);
        if (raw == null) return DEFAULT_ADAPTERS;
        final List<?> items;
        if (raw instanceof Collection<?> collection) {
            items = List.copyOf(collection);
        } else if (raw instanceof String text) {
            items = text.isBlank() ? List.of() : List.of(text.split(","));
        } else {
            invalid.add(ADAPTERS_REQUIRED_KEY);
            return DEFAULT_ADAPTERS;
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
        if (bad) invalid.add(ADAPTERS_REQUIRED_KEY);
        return new Adapters(ids);
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
            allowed);
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
