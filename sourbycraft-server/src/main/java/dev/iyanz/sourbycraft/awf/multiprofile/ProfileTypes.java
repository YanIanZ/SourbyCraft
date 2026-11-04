package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileNaming;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileRule;
import dev.iyanz.sourbycraft.api.profile.ProfileRuleSet;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeSource;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;

/**
 * The profile type registry (Build 54): the built-in types, the types plugins register through
 * {@link ProfileTypeRegistry}, and the last definitions read back from the profile store.
 *
 * <p><b>Engine contract.</b> The engine calls {@link #get()} and checks each action it owns with
 * {@link #require(AuroraProfile, String)} (or {@link #allows(AuroraProfile, String)}) before it writes anything;
 * a refusal is {@link ProfileErrorCode#ACTION_FORBIDDEN_BY_TYPE} naming the type and the action. A profile's type id
 * is {@link #typeIdOf(AuroraProfile)}: the {@link ProfileTypeRegistry#TYPE_METADATA_KEY} metadata value for a
 * registered type, else the built-in policy type's id.</p>
 *
 * <p><b>Resolution.</b> For a type id the definition in force is, in order: a definition a plugin registered since
 * this server started ({@link ProfileTypeSource#REGISTERED}); else the last definition stored for it
 * ({@link ProfileTypeSource#STORED}, kept when its plugin unregisters, disables or is absent); else the built-in
 * definition ({@link ProfileTypeSource#BUILTIN}); else none (unknown). A registered or stored definition of a built-in
 * id is used only while {@code types.registry.allow-override-builtins} is true.</p>
 *
 * <p><b>Unknown type, fail-safe.</b> With {@code types.unknown-type-policy: deny} (default) a profile of an unknown
 * type is refused every action, the engine's own included, and one WARN names the type. A definition is unknown only
 * when its plugin never registered it on this server and its stored copy is missing or unreadable; allowing in that
 * case would silently lift whatever the lost definition denied (for example an Ironman-style "no bank"), so the
 * default refuses until the plugin registers the type again.</p>
 *
 * <p><b>Persistence.</b> {@link #register} puts the definition in force at once and writes it to the store on this
 * registry's own background thread, never on the caller's thread: FILE stores it as {@code types/<id>.awpt} below the
 * store directory (atomic rename, forced directory); Redis as one field of the hash {@code <prefix>mp:types}. Neither
 * is a record type of the profile store format: Build 53 does not read them, and they do not change the store's
 * minimum build. Startup reads them before the engine opens the store. A failed write is logged; the definition stays
 * in force until restart, and the store keeps the previous copy. Nothing here deletes a stored definition.</p>
 *
 * <p><b>Threads.</b> Every method except {@link #open} and {@link #close} is free of I/O and may run on any thread;
 * the state is a concurrent map of immutable entries.</p>
 */
public final class ProfileTypes implements ProfileTypeRegistry {

    // Engine-owned action ids (mirrors of ProfileTypeRegistry, for engine call sites).
    public static final String BANK_DEPOSIT = ProfileTypeRegistry.BANK_DEPOSIT;
    public static final String BANK_WITHDRAW = ProfileTypeRegistry.BANK_WITHDRAW;
    public static final String BANK_PERSONAL = ProfileTypeRegistry.BANK_PERSONAL;
    public static final String BANK_TRANSFER = ProfileTypeRegistry.BANK_TRANSFER;
    public static final String COOP_CONVERT = ProfileTypeRegistry.COOP_CONVERT;
    public static final String COOP_INVITE = ProfileTypeRegistry.COOP_INVITE;
    public static final String COOP_ACCEPT = ProfileTypeRegistry.COOP_ACCEPT;
    public static final String SALVAGE_CLAIM = ProfileTypeRegistry.SALVAGE_CLAIM;
    public static final String COLLECTIONS_CONTRIBUTE = ProfileTypeRegistry.COLLECTIONS_CONTRIBUTE;
    public static final String PROFILE_SWITCH = ProfileTypeRegistry.PROFILE_SWITCH;
    public static final String COOP_KICK = ProfileTypeRegistry.COOP_KICK;
    public static final String COOP_LABEL = ProfileTypeRegistry.COOP_LABEL;

    /** Every action id the engine enforces itself (Build 55 adds profile.switch, coop.kick and coop.label). */
    public static final Set<String> ENGINE_ACTIONS = Set.of(BANK_DEPOSIT, BANK_WITHDRAW, BANK_PERSONAL, BANK_TRANSFER,
        COOP_CONVERT, COOP_INVITE, COOP_ACCEPT, SALVAGE_CLAIM, COLLECTIONS_CONTRIBUTE, PROFILE_SWITCH, COOP_KICK, COOP_LABEL);

    /** Consumer action ids the built-in definitions name (enforced by the consumer, not by the engine). */
    public static final String TRADE = "trade";
    public static final String AUCTION = "auction";
    public static final String BAZAAR = "bazaar";

    /** Most non-built-in type ids one server holds (registered and stored together). */
    public static final int MAX_TYPES = 64;
    /** Most distinct unknown type ids that get their own WARN; later ones are counted only. */
    private static final int MAX_WARNED = 256;

    /** The built-in definitions (Hypixel SkyBlock parity, see docs/awf/multiprofile/PROFILE-TYPES.md). */
    public static final Map<String, ProfileTypeDefinition> BUILTINS = builtins();

    private static final ProfileTypes BUILTINS_ONLY = new ProfileTypes(ProfileSettings.DEFAULT_TYPE_REGISTRY, null, null);
    private static volatile ProfileTypes current = BUILTINS_ONLY;

    /** Reads a profile record for {@link #rules(UUID)}; supplied by the engine. */
    @FunctionalInterface
    public interface ProfileLookup {
        /** The profile, read off the caller's thread; empty when there is none. */
        CompletableFuture<Optional<AuroraProfile>> profile(UUID profileId);
    }

    /** One definition in force: where it came from and which plugin name owns it ({@code null} for a built-in). */
    record Entry(ProfileTypeDefinition definition, ProfileTypeSource source, @Nullable String owner) {}

    private final ProfileSettings.TypeRegistry config;
    private final @Nullable ProfileTypesStore store;
    private final @Nullable ExecutorService writer;
    /** Non-built-in definitions and built-in overrides, by id. */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedIgnoredOverride = ConcurrentHashMap.newKeySet();
    private volatile @Nullable ProfileLookup lookup;
    /** Called with the type id when a built-in override that denies an action is registered (review P3-8); the engine raises the store level. */
    private volatile java.util.function.Consumer<String> builtinOverrideDenied = id -> {};
    private volatile @Nullable Plugin servicePlugin;
    private volatile @Nullable Listener disableListener;

    private ProfileTypes(final ProfileSettings.TypeRegistry config, final @Nullable ProfileTypesStore store,
                         final @Nullable ExecutorService writer) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = store;
        this.writer = writer;
        for (final ProfileTypeDefinition builtin : BUILTINS.values()) admitPool(builtin);
    }

    /**
     * The running registry; a built-ins-only instance (no store, no plugin types, unknown types denied) while
     * MultiProfile is not running.
     */
    public static ProfileTypes get() {
        return current;
    }

    /**
     * Opens the registry for a starting MultiProfile: reads the stored definitions (blocking; startup only, before
     * the engine opens the store, so stored profiles named from a type pool decode), registers the
     * {@link ProfileTypeRegistry} Bukkit service and a plugin-disable listener, and makes it {@link #get()}.
     *
     * @param plugin the plugin the service and listener are registered for
     * @param settings MultiProfile settings (the store backend and directory, {@link ProfileSettings#typeRegistry()})
     * @param fabric the running Redis fabric; required when {@code settings.redisRepository()}
     * @throws IOException when the store cannot be listed (a single unreadable definition is skipped with a WARN)
     */
    public static ProfileTypes open(final Plugin plugin, final ProfileSettings settings, final @Nullable RedisFabric fabric)
        throws IOException {
        final ProfileTypesStore store;
        if (settings.redisRepository()) {
            if (fabric == null) throw new IllegalStateException("repository: redis needs the Aurora Redis Fabric");
            store = ProfileTypesStore.redis(fabric.storageClient(), fabric.prefix() + "mp:types");
        } else {
            store = ProfileTypesStore.file(java.nio.file.Path.of(settings.directory()).resolve("types"));
        }
        return open(plugin, settings.typeRegistry(), store);
    }

    /** {@link #open(Plugin, ProfileSettings, RedisFabric)} with an explicit store (tests). {@code plugin} may be null. */
    public static ProfileTypes open(final @Nullable Plugin plugin, final ProfileSettings.TypeRegistry config,
                                    final ProfileTypesStore store) throws IOException {
        // One writer thread with a bounded queue (UnboundedQueueAuditTest): definitions are few (MAX_TYPES), so 256
        // pending writes is far above any real backlog; a full queue is a RejectedExecutionException that persist()
        // already reports.
        final ExecutorService writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256), task -> {
                final Thread thread = new Thread(task, "SourbyCraft-AWF-profile-types");
                thread.setDaemon(true);
                return thread;
            });
        final ProfileTypes types = new ProfileTypes(config, store, writer);
        try {
            types.loadStored(store.load(MAX_TYPES + BUILTINS.size()));
        } catch (final IOException | RuntimeException failed) {
            writer.shutdownNow();
            throw failed;
        }
        if (plugin != null) {
            final Listener listener = new Listener() {
                @EventHandler(priority = EventPriority.MONITOR)
                public void onDisable(final PluginDisableEvent event) {
                    types.ownerGone(event.getPlugin().getName());
                }
            };
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            types.disableListener = listener;
            Bukkit.getServicesManager().register(ProfileTypeRegistry.class, types, plugin, ServicePriority.Normal);
            types.servicePlugin = plugin;
        }
        current = types;
        return types;
    }

    /** Attaches the profile reader {@link #rules(UUID)} uses. */
    public void attachLookup(final ProfileLookup profiles) {
        this.lookup = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Attaches the hook run (on the registering thread, so it must not block) when a built-in override that denies at
     * least one action is registered or found stored (review P3-8): the engine raises the store's minimum build to 54 so a
     * downgrade to Build 53, which has no registry, cannot lift the deny.
     */
    public void onBuiltinOverrideDenied(final java.util.function.Consumer<String> hook) {
        this.builtinOverrideDenied = Objects.requireNonNull(hook, "hook");
        // Overrides read from the store before the engine attached are reported now.
        for (final Map.Entry<String, Entry> entry : this.entries.entrySet()) {
            if (BUILTINS.containsKey(entry.getKey()) && denies(entry.getValue().definition())) hook.accept(entry.getKey());
        }
    }

    /** Whether the definition denies any action (its default rule or one of its rules). */
    static boolean denies(final ProfileTypeDefinition definition) {
        if (!definition.defaultRule().allowed()) return true;
        for (final ProfileRule rule : definition.rules().values()) if (!rule.allowed()) return true;
        return false;
    }

    /**
     * The definition a stored record this build cannot read stands for (review P2-3): every action denied, no co-op, not
     * creatable, until the owning plugin registers the type again. A deny is never lifted by a corrupt or unreadable file.
     */
    static ProfileTypeDefinition unreadableDefinition(final String id) {
        return ProfileTypeDefinition.builder(id).displayName(id.length() > ProfileTypeDefinition.MAX_DISPLAY_NAME
                ? id.substring(0, ProfileTypeDefinition.MAX_DISPLAY_NAME) : id)
            .description("The stored definition of this type is unreadable; every action is refused until its plugin registers it again.")
            .coopAllowed(false).creatable(false).defaultRule(ProfileRule.deny())
            // The Build 55 ids are not decided by the default rule (review N2), so they are denied by name here.
            .rule(PROFILE_SWITCH, ProfileRule.deny()).rule(COOP_KICK, ProfileRule.deny()).rule(COOP_LABEL, ProfileRule.deny())
            .build();
    }

    /**
     * Unregisters the service and listener, waits up to {@code waitMillis} for pending store writes, and makes
     * {@link #get()} the built-ins-only instance again. Pending writes not finished by then are abandoned (the store
     * keeps the previous copy; the plugin registers again at the next start).
     */
    public void close(final long waitMillis) {
        final Plugin plugin = this.servicePlugin;
        if (plugin != null) {
            Bukkit.getServicesManager().unregister(ProfileTypeRegistry.class, this);
            this.servicePlugin = null;
        }
        final Listener listener = this.disableListener;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            this.disableListener = null;
        }
        if (current == this) current = BUILTINS_ONLY;
        if (this.writer != null) {
            this.writer.shutdown();
            try {
                if (!this.writer.awaitTermination(Math.max(0L, waitMillis), TimeUnit.MILLISECONDS)) {
                    SourbyLogger.warn("AWF MultiProfile: profile type definitions were still being written at shutdown;"
                        + " the store keeps the previous copy of those types");
                    this.writer.shutdownNow();
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                this.writer.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ engine contract

    /** The type id of a profile ({@link ProfileTypeRegistry#typeId}). */
    public static String typeIdOf(final AuroraProfile profile) {
        return ProfileTypeRegistry.typeId(profile.profileType(), profile.metadata());
    }

    /** Whether the profile's type allows the action; an unknown type answers by {@code types.unknown-type-policy}. */
    public boolean allows(final AuroraProfile profile, final String action) {
        return allows(typeIdOf(profile), action);
    }

    /**
     * Refuses an action the profile's type does not allow.
     *
     * @throws ProfileOperationException {@link ProfileErrorCode#ACTION_FORBIDDEN_BY_TYPE}; the message names the
     *     type and the action
     */
    public void require(final AuroraProfile profile, final String action) throws ProfileOperationException {
        final String typeId = typeIdOf(profile);
        final Optional<ProfileTypeDefinition> definition = definition(typeId);
        if (definition.isEmpty()) {
            if (unknownAllows(typeId)) return;
            throw new ProfileOperationException(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, "profile type '" + typeId
                + "' has no definition on this server, so '" + action + "' is refused (types.unknown-type-policy: deny);"
                + " the plugin that defines this type must register it. Nothing was written");
        }
        final ProfileTypeDefinition type = definition.get();
        if (type.allows(action)) return;
        final boolean coop = !type.coopAllowed() && ProfileTypeRegistry.coopAction(action);
        throw new ProfileOperationException(ProfileErrorCode.ACTION_FORBIDDEN_BY_TYPE, "profile type '" + typeId + "' ("
            + type.displayName() + ") does not allow '" + action + "'" + (coop ? " (the type does not allow co-op)" : "")
            + ". Nothing was written");
    }

    /**
     * {@link #require} for an action that must not lock out profiles of a type this server has no definition for
     * (review P2-7): with a definition the type decides; without one the action is <em>allowed</em> whatever
     * {@code types.unknown-type-policy} says. Used for {@code profile.switch} only: a Build 54 store may hold profiles
     * whose free-form {@code aurora:type} never had a definition, and Build 54 let their owners play them; the other
     * engine actions (bank, co-op, salvage) keep the deny policy for unknown types.
     */
    public void requireIfDefined(final AuroraProfile profile, final String action) throws ProfileOperationException {
        if (definition(typeIdOf(profile)).isEmpty()) return;
        require(profile, action);
    }

    /**
     * The co-op member cap of a profile: the type's {@link ProfileTypeDefinition#maxMembers()} when set, else
     * {@code configured} ({@code coop.max-members}); never above {@link ProfileSettings.Coop#MAX_MEMBERS_LIMIT}.
     */
    public int maxMembers(final AuroraProfile profile, final int configured) {
        final Integer own = definition(typeIdOf(profile)).map(ProfileTypeDefinition::maxMembers).orElse(null);
        return Math.min(own != null ? own : configured, ProfileSettings.Coop.MAX_MEMBERS_LIMIT);
    }

    /**
     * What a create request resolves to.
     *
     * @param typeId the type id the profile gets
     * @param policyType the {@link dev.iyanz.sourbycraft.api.profile.ProfileType} to store: the built-in type, or
     *     {@code CLASSIC} for a registered type
     * @param metadata the metadata to store: the request's, with {@link ProfileTypeRegistry#TYPE_METADATA_KEY} set to
     *     {@code typeId} for a registered type and removed for a built-in one
     * @param definition the definition in force
     */
    public record CreatePlan(String typeId, dev.iyanz.sourbycraft.api.profile.ProfileType policyType,
                             Map<String, String> metadata, ProfileTypeDefinition definition) {}

    /**
     * Resolves the type of a create request. A request names a type by {@code profileType} (built-in) or by the
     * {@link ProfileTypeRegistry#TYPE_METADATA_KEY} metadata value ({@link ProfileRequest#withTypeId}); with neither,
     * {@code types.default}.
     *
     * @throws ProfileOperationException {@link ProfileErrorCode#TYPE_NOT_ALLOWED} (a built-in type outside
     *     {@code types.allowed}, or a type that is not {@link ProfileTypeDefinition#creatable()}),
     *     {@link ProfileErrorCode#TYPE_UNKNOWN} (no definition), {@link ProfileErrorCode#INVALID_REQUEST} (a malformed
     *     id, a {@code profileType} that contradicts the metadata id, or a metadata key only the engine writes,
     *     {@link AuroraProfile#ENGINE_METADATA_KEYS})
     */
    public CreatePlan planCreate(final ProfileRequest request, final ProfileSettings.Types types)
        throws ProfileOperationException {
        return planCreate(request, types, false);
    }

    /**
     * {@link #planCreate(ProfileRequest, ProfileSettings.Types)}; with {@code evacuation} the type's
     * {@link ProfileTypeDefinition#creatable()} is not checked (review P3-10): the engine's fresh profile for a member leaving
     * or removed from a co-op is not a player's create, and a {@code creatable: false} default type must not keep the member in.
     */
    public CreatePlan planCreate(final ProfileRequest request, final ProfileSettings.Types types, final boolean evacuation)
        throws ProfileOperationException {
        final Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        // Build 55 review N4: the archive markers are written by archive/unarchive only; a request carrying one would
        // create a profile that reads as archived (read-only) while its status is ACTIVE.
        for (final String reserved : AuroraProfile.ENGINE_METADATA_KEYS) {
            if (metadata.containsKey(reserved)) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "metadata key '" + reserved
                    + "' is written by the server only (archive and unarchive); a create request may not set it. Nothing was written");
            }
        }
        final String named = metadata.get(TYPE_METADATA_KEY);
        final dev.iyanz.sourbycraft.api.profile.ProfileType policy;
        final String typeId;
        if (named != null) {
            if (!ProfileTypeDefinition.validId(named)) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "profile type id '" + named
                    + "' is not [a-z0-9_-]{1,32}");
            }
            final Optional<dev.iyanz.sourbycraft.api.profile.ProfileType> builtin =
                dev.iyanz.sourbycraft.api.profile.ProfileType.fromId(named);
            if (builtin.isPresent()) {
                if (request.profileType() != null && request.profileType() != builtin.get()) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the request names type "
                        + request.profileType().id() + " and type id " + named);
                }
                metadata.remove(TYPE_METADATA_KEY);
                policy = builtin.get();
                typeId = policy.id();
            } else {
                if (request.profileType() != null && request.profileType() != dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a registered type ('" + named
                        + "') is stored with policy type classic; the request names " + request.profileType().id());
                }
                policy = dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC;
                typeId = named;
            }
        } else {
            policy = request.profileType() != null ? request.profileType() : types.defaultType();
            typeId = policy.id();
        }
        if (typeId.equals(policy.id()) && !types.allows(policy)) {
            throw new ProfileOperationException(ProfileErrorCode.TYPE_NOT_ALLOWED, "profile type " + policy.id()
                + " is not in types.allowed " + ProfileSettings.typeIds(types.allowed()));
        }
        final Optional<ProfileTypeDefinition> definition = definition(typeId);
        if (definition.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.TYPE_UNKNOWN, "profile type '" + typeId
                + "' is not registered on this server");
        }
        if (!evacuation && !definition.get().creatable()) {
            throw new ProfileOperationException(ProfileErrorCode.TYPE_NOT_ALLOWED, "profile type '" + typeId
                + "' does not allow new profiles");
        }
        return new CreatePlan(typeId, policy, Map.copyOf(metadata), definition.get());
    }

    /**
     * Chooses the name of a new profile of {@code definition}'s type: the requested name when it is a name of the
     * type's pool (the server pool for {@link ProfileNaming.Policy#SERVER}) and free in the account, else a rolled
     * free name. The result is {@link CuteNames#valid} (so {@link AuroraProfile} accepts it).
     *
     * @param takenKeys {@link CuteNames#key keys} of the account's existing names
     * @throws ProfileOperationException {@link ProfileErrorCode#NAME_INVALID} or {@link ProfileErrorCode#NAME_TAKEN}
     */
    public String chooseName(final ProfileTypeDefinition definition, final @Nullable String requested,
                             final Set<String> takenKeys) throws ProfileOperationException {
        final boolean own = definition.naming().policy() == ProfileNaming.Policy.POOL;
        final List<String> pool = definition.naming().pool();
        if (own) admitPool(definition);
        final String name;
        if (requested != null) {
            final String wanted = requested.strip();
            final boolean valid = own ? CuteNames.validIn(wanted, pool) : CuteNames.validIn(wanted, CuteNames.NAMES)
                && CuteNames.valid(wanted);
            if (!valid) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_INVALID, "'" + requested + "' is not a name of "
                    + (own ? "profile type '" + definition.id() + "''s pool" : "the server's fruit and vegetable pool")
                    + ", optionally followed by a space and a number 1-999");
            }
            name = own ? CuteNames.normalizeIn(wanted, pool) : CuteNames.normalize(wanted);
            if (takenKeys.contains(CuteNames.key(name))) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, "the account already has a profile named "
                    + name + " (names are compared ignoring case)");
            }
        } else {
            try {
                name = own ? CuteNames.generate(takenKeys, pool) : CuteNames.generate(takenKeys);
            } catch (final IllegalStateException exhausted) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, "no free profile name is left for this account");
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ ProfileTypeRegistry

    @Override
    public void register(final Plugin owner, final ProfileTypeDefinition definition) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(definition, "definition");
        if (this == BUILTINS_ONLY) {
            throw new ProfileOperationException(ProfileErrorCode.DISABLED, "AWF MultiProfile is not running; no profile"
                + " type can be registered");
        }
        final String id = definition.id();
        final String ownerName = owner.getName();
        final boolean builtin = BUILTINS.containsKey(id);
        if (builtin && !this.config.allowOverrideBuiltins()) {
            throw new ProfileOperationException(ProfileErrorCode.TYPE_NOT_ALLOWED, "profile type '" + id + "' is built in and "
                + ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY + " is false");
        }
        final ExecutorService executor = this.writer;
        if (executor != null && executor.isShutdown()) {
            // Review P3-19: a registry that MultiProfile closed stores nothing; say so instead of keeping a definition in memory only.
            throw new ProfileOperationException(ProfileErrorCode.DISABLED, "AWF MultiProfile has stopped; profile type '" + id
                + "' was not registered or stored");
        }
        try {
            admitPool(definition);
        } catch (final IllegalArgumentException invalid) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, invalid.getMessage());
        }
        synchronized (this.entries) {
            final Entry existing = this.entries.get(id);
            if (existing != null && existing.owner() != null && !existing.owner().equals(ownerName)) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "profile type '" + id + "' belongs to "
                    + existing.owner() + " (" + existing.source().name().toLowerCase(java.util.Locale.ROOT) + ")");
            }
            if (existing == null && !builtin && nonBuiltinCount() >= MAX_TYPES) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "this server already holds "
                    + MAX_TYPES + " profile types");
            }
            final boolean changed = existing == null || !existing.definition().equals(definition);
            final byte[] encoded = ProfileTypesStore.encode(definition, ownerName);
            this.entries.put(id, new Entry(definition, ProfileTypeSource.REGISTERED, ownerName));
            // Queued under the lock (review P3-11): two registrations of one id reach the single writer thread in the order
            // their definitions took effect, so the store never keeps the older one.
            if (changed) persist(id, encoded);
        }
        this.warnedUnknown.remove(id);
        if (builtin && denies(definition)) this.builtinOverrideDenied.accept(id);
    }

    @Override
    public boolean unregister(final Plugin owner, final String typeId) {
        Objects.requireNonNull(owner, "owner");
        synchronized (this.entries) {
            final Entry existing = this.entries.get(typeId);
            if (existing == null || existing.source() != ProfileTypeSource.REGISTERED
                || !owner.getName().equals(existing.owner())) {
                return false;
            }
            this.entries.put(typeId, new Entry(existing.definition(), ProfileTypeSource.STORED, existing.owner()));
            return true;
        }
    }

    /** A plugin was disabled: its live registrations become stored ones (their definitions stay in force). */
    void ownerGone(final String ownerName) {
        synchronized (this.entries) {
            for (final Map.Entry<String, Entry> entry : List.copyOf(this.entries.entrySet())) {
                final Entry value = entry.getValue();
                if (value.source() == ProfileTypeSource.REGISTERED && ownerName.equals(value.owner())) {
                    this.entries.put(entry.getKey(), new Entry(value.definition(), ProfileTypeSource.STORED, value.owner()));
                }
            }
        }
    }

    @Override
    public Optional<ProfileTypeDefinition> definition(final String typeId) {
        return Optional.ofNullable(entry(typeId)).map(Entry::definition);
    }

    @Override
    public Collection<ProfileTypeDefinition> definitions() {
        final List<ProfileTypeDefinition> result = new ArrayList<>();
        for (final String id : BUILTINS.keySet()) result.add(Objects.requireNonNull(entry(id)).definition());
        final List<String> others = new ArrayList<>();
        for (final String id : this.entries.keySet()) if (!BUILTINS.containsKey(id)) others.add(id);
        others.sort(Comparator.naturalOrder());
        for (final String id : others) {
            final Entry entry = this.entries.get(id);
            if (entry != null) result.add(entry.definition());
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<ProfileTypeSource> source(final String typeId) {
        return Optional.ofNullable(entry(typeId)).map(Entry::source);
    }

    @Override
    public Optional<String> owner(final String typeId) {
        return Optional.ofNullable(entry(typeId)).map(Entry::owner);
    }

    @Override
    public boolean allows(final String typeId, final String action) {
        final Entry entry = entry(typeId);
        if (entry == null) return unknownAllows(typeId);
        return entry.definition().allows(action);
    }

    @Override
    public CompletableFuture<ProfileRuleSet> rules(final UUID profileId) {
        final ProfileLookup profiles = this.lookup;
        if (profiles == null) {
            return CompletableFuture.failedFuture(new ProfileOperationException(ProfileErrorCode.DISABLED,
                "AWF MultiProfile is not running"));
        }
        return profiles.profile(profileId).thenApply(found -> {
            final AuroraProfile profile = found.orElseThrow(() -> new ProfileOperationException(
                ProfileErrorCode.PROFILE_NOT_FOUND, "no profile " + profileId));
            final String typeId = typeIdOf(profile);
            final ProfileTypeDefinition definition = definition(typeId).orElse(null);
            final boolean unknownAllows = definition == null ? unknownAllows(typeId)
                : this.config.unknownTypePolicy() == ProfileSettings.UnknownTypePolicy.ALLOW;
            return new ProfileRuleSet(profileId, typeId, definition, unknownAllows);
        });
    }

    // ------------------------------------------------------------------ internals

    private @Nullable Entry entry(final String typeId) {
        if (typeId == null) return null;
        final Entry own = this.entries.get(typeId);
        final ProfileTypeDefinition builtin = BUILTINS.get(typeId);
        if (own != null && (builtin == null || this.config.allowOverrideBuiltins())) return own;
        return builtin == null ? null : new Entry(builtin, ProfileTypeSource.BUILTIN, null);
    }

    private boolean unknownAllows(final String typeId) {
        final boolean allow = this.config.unknownTypePolicy() == ProfileSettings.UnknownTypePolicy.ALLOW;
        if (this.warnedUnknown.size() < MAX_WARNED && this.warnedUnknown.add(String.valueOf(typeId))) {
            SourbyLogger.warn("AWF MultiProfile: profile type '" + typeId + "' has no definition on this server (not built in,"
                + " never registered here and no readable stored definition); under " + ProfileSettings.TYPES_UNKNOWN_POLICY_KEY
                + " " + this.config.unknownTypePolicy().id() + " every action of its profiles is "
                + (allow ? "allowed" : "refused (ACTION_FORBIDDEN_BY_TYPE)") + " until its plugin registers it");
        }
        return allow;
    }

    private int nonBuiltinCount() {
        int count = 0;
        for (final String id : this.entries.keySet()) if (!BUILTINS.containsKey(id)) count++;
        return count;
    }

    private static void admitPool(final ProfileTypeDefinition definition) {
        if (definition.naming().policy() == ProfileNaming.Policy.POOL) CuteNames.admitTypePool(definition.naming().pool());
    }

    private void loadStored(final Map<String, ProfileTypesStore.Stored> stored) {
        int kept = 0;
        for (final Map.Entry<String, ProfileTypesStore.Stored> item : stored.entrySet()) {
            final ProfileTypesStore.Stored value = item.getValue();
            if (value.definition() == null) {
                // Review P2-3: the store holds a record this build cannot read. It may be a plugin type or a built-in
                // override with denies; forgetting it would lift those denies, so the id is denied until re-registered.
                final String id = item.getKey();
                if (BUILTINS.containsKey(id) && !this.config.allowOverrideBuiltins()) {
                    if (this.warnedIgnoredOverride.add(id)) {
                        SourbyLogger.warn("AWF MultiProfile: an unreadable stored override of the built-in profile type '" + id
                            + "' is ignored because " + ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY + " is false; the built-in applies");
                    }
                    continue;
                }
                if (!BUILTINS.containsKey(id) && kept >= MAX_TYPES) continue;
                SourbyLogger.warn("AWF MultiProfile: the stored definition of profile type '" + id + "' is unreadable (" + value.unreadable()
                    + "); every action of its profiles is refused until its plugin registers it again, so no deny is lifted by accident");
                this.entries.put(id, new Entry(unreadableDefinition(id), ProfileTypeSource.STORED, null));
                if (!BUILTINS.containsKey(id)) kept++;
                continue;
            }
            final String id = value.definition().id();
            if (!id.equals(item.getKey())) {
                SourbyLogger.warn("AWF MultiProfile: stored profile type '" + item.getKey() + "' holds the definition of '"
                    + id + "'; it is ignored");
                continue;
            }
            final boolean builtin = BUILTINS.containsKey(id);
            if (!builtin && kept >= MAX_TYPES) {
                SourbyLogger.warn("AWF MultiProfile: more than " + MAX_TYPES + " stored profile types; '" + id + "' is ignored");
                continue;
            }
            if (builtin && !this.config.allowOverrideBuiltins() && this.warnedIgnoredOverride.add(id)) {
                SourbyLogger.warn("AWF MultiProfile: a stored override of the built-in profile type '" + id + "' (by "
                    + value.owner() + ") is ignored because " + ProfileSettings.TYPES_OVERRIDE_BUILTINS_KEY + " is false;"
                    + " the built-in definition applies");
            }
            try {
                admitPool(value.definition());
            } catch (final IllegalArgumentException invalid) {
                SourbyLogger.warn("AWF MultiProfile: stored profile type '" + id + "' has an invalid name pool ("
                    + invalid.getMessage() + "); it is ignored");
                continue;
            }
            this.entries.put(id, new Entry(value.definition(), ProfileTypeSource.STORED, value.owner()));
            if (!builtin) kept++;
        }
        if (!this.entries.isEmpty()) {
            SourbyLogger.info("AWF MultiProfile: " + this.entries.size() + " stored profile type definition(s) in force until"
                + " their plugins register them again: " + String.join(", ", new java.util.TreeSet<>(this.entries.keySet())));
        }
    }

    private void persist(final String id, final byte[] encoded) {
        final ProfileTypesStore target = this.store;
        final ExecutorService executor = this.writer;
        if (target == null || executor == null) return;
        try {
            executor.execute(() -> {
                try {
                    target.save(id, encoded);
                } catch (final IOException | RuntimeException failed) {
                    SourbyLogger.warn("AWF MultiProfile: could not store profile type '" + id + "' in " + target.describe()
                        + " (" + failed.getMessage() + "); it stays in force until restart, and the store keeps its"
                        + " previous copy");
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException closed) {
            SourbyLogger.warn("AWF MultiProfile: profile type '" + id + "' was not stored: MultiProfile is closing or the"
                + " type store's write queue is full; it stays in force until restart");
        }
    }

    private static Map<String, ProfileTypeDefinition> builtins() {
        final Map<String, ProfileTypeDefinition> map = new LinkedHashMap<>();
        map.put("classic", ProfileTypeDefinition.builder("classic").displayName("Classic")
            .description("The normal profile: every feature available.").build());
        map.put("ironman", ProfileTypeDefinition.builder("ironman").displayName("Ironman")
            .description("No trading, auction house or bazaar.")
            .deny(TRADE).deny(AUCTION).deny(BAZAAR).build());
        map.put("stranded", ProfileTypeDefinition.builder("stranded").displayName("Stranded")
            .description("One island; no trading, auction house or bazaar.")
            .deny(TRADE).deny(AUCTION).deny(BAZAAR).build());
        map.put("bingo", ProfileTypeDefinition.builder("bingo").displayName("Bingo")
            .description("A seasonal profile, archived at the end of its event.").seasonal(true).build());
        return java.util.Collections.unmodifiableMap(map);
    }

    /** Test hook: the live entry map (copy). */
    Map<String, Entry> entriesForTest() {
        return new HashMap<>(this.entries);
    }
}
