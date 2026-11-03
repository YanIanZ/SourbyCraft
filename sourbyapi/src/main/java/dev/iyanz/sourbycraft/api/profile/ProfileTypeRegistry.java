package dev.iyanz.sourbycraft.api.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. The profile types of this server and their action rules.
 *
 * <p>Obtain it from Bukkit's services manager, or through {@link AuroraProfiles#typeRegistry()}:</p>
 * <pre>{@code
 * ProfileTypeRegistry types = Bukkit.getServicesManager().load(ProfileTypeRegistry.class);
 * }</pre>
 * <p>It is registered only while AWF MultiProfile is enabled; a missing registration is what a server without
 * MultiProfile looks like.</p>
 *
 * <p><b>Built-in types.</b> {@code classic}, {@code ironman}, {@code stranded} and {@code bingo} are always known. A
 * plugin may replace one with its own definition of the same id while
 * {@code aurora.awf.multiprofile.types.registry.allow-override-builtins} is true (the default).</p>
 *
 * <p><b>Persistence.</b> {@link #register} stores the definition in the profile store (FILE: {@code types/} below the
 * store directory; Redis: one hash), so profiles of that type keep their rules while the plugin is absent:
 * {@link #unregister} and disabling the plugin leave the last registered definition in force
 * ({@link ProfileTypeSource#STORED}). Nothing in this API deletes a stored definition; registering the id again
 * replaces it.</p>
 *
 * <p><b>Unknown types.</b> A profile whose type id has no definition here (neither built in, registered nor stored) is
 * answered by {@code types.unknown-type-policy}: {@code deny} (default) refuses every action, the engine's own included,
 * with {@link ProfileErrorCode#ACTION_FORBIDDEN_BY_TYPE}, and logs one WARN per type id.</p>
 *
 * <p><b>Threads.</b> {@link #register}, {@link #unregister}, {@link #definition}, {@link #definitions}, {@link #source}
 * and {@link #allows(String, String)} do no I/O and may be called from any thread; the store write of
 * {@link #register} runs on a background thread afterwards. {@link #rules(UUID)} and {@link #allows(UUID, String)}
 * read the profile record and complete asynchronously; never join them on a region, entity or global-tick thread.</p>
 */
@NullMarked
public interface ProfileTypeRegistry {

    /** Shared bank deposit (engine-owned). */
    String BANK_DEPOSIT = "bank.deposit";
    /** Shared bank withdrawal (engine-owned). */
    String BANK_WITHDRAW = "bank.withdraw";
    /** Any operation on a member's personal bank (engine-owned). */
    String BANK_PERSONAL = "bank.personal";
    /** A bank-only transfer (engine-owned). */
    String BANK_TRANSFER = "bank.transfer";
    /** Converting a solo profile to co-op (engine-owned). */
    String COOP_CONVERT = "coop.convert";
    /** Inviting a player into a co-op (engine-owned). */
    String COOP_INVITE = "coop.invite";
    /** Accepting a co-op invitation (engine-owned). */
    String COOP_ACCEPT = "coop.accept";
    /** Claiming salvage (engine-owned). */
    String SALVAGE_CLAIM = "salvage.claim";
    /**
     * Adding to a co-op collection and its per-member contribution ledger (engine-owned since Build 54; allowed unless
     * the type denies it).
     */
    String COLLECTIONS_CONTRIBUTE = "collections.contribute";

    /**
     * The profile metadata key that holds a registered (non-built-in) type id. A profile of a built-in type stores
     * its type as {@link ProfileType} and has no such key; a profile of a registered type stores
     * {@link ProfileType#CLASSIC} plus this key.
     */
    String TYPE_METADATA_KEY = "aurora:type";

    /** The ids of the built-in types, in {@link ProfileType} order. */
    static Collection<String> builtinIds() {
        return java.util.Arrays.stream(ProfileType.values()).map(ProfileType::id).toList();
    }

    /** Whether {@code action} is one of the engine-owned co-op actions ({@code coop.convert/invite/accept}). */
    static boolean coopAction(final String action) {
        return COOP_CONVERT.equals(action) || COOP_INVITE.equals(action) || COOP_ACCEPT.equals(action);
    }

    /**
     * The type id of a profile: {@link #TYPE_METADATA_KEY} from its metadata when present, else its
     * {@link ProfileType#id()}. A metadata value that is not a well-formed type id is returned as it is, so it
     * resolves to no definition (an unknown type), never to {@code classic}.
     */
    static String typeId(final ProfileType profileType, final Map<String, String> metadata) {
        final String stored = metadata.get(TYPE_METADATA_KEY);
        return stored != null ? stored : profileType.id();
    }

    /**
     * Registers or replaces a type. The definition is in force when this returns and is written to the profile store
     * in the background (a failed write is logged; the definition stays in force until restart).
     *
     * @throws ProfileOperationException {@link ProfileErrorCode#TYPE_NOT_ALLOWED} for a built-in id while
     *     {@code allow-override-builtins} is false; {@link ProfileErrorCode#INVALID_REQUEST} when another plugin
     *     registered (or stored) this id, or when the registry already holds its maximum of types;
     *     {@link ProfileErrorCode#DISABLED} when MultiProfile is not running
     */
    void register(Plugin owner, ProfileTypeDefinition definition);

    /**
     * Ends {@code owner}'s live registration of {@code typeId}. The last definition stays in force as
     * {@link ProfileTypeSource#STORED}; this never lifts a deny. Returns whether {@code owner} had registered it.
     */
    boolean unregister(Plugin owner, String typeId);

    /** The definition in force for a type id. */
    Optional<ProfileTypeDefinition> definition(String typeId);

    /** Every definition in force, built-ins first, then by id. */
    Collection<ProfileTypeDefinition> definitions();

    /** Where the definition of a type id comes from, or empty when it is unknown. */
    Optional<ProfileTypeSource> source(String typeId);

    /** The plugin name that registered (or last stored) a type id; empty for a built-in or unknown id. */
    Optional<String> owner(String typeId);

    /**
     * Whether the type allows the action ({@link ProfileTypeDefinition#allows}); an unknown type id answers by
     * {@code types.unknown-type-policy}. No I/O.
     */
    boolean allows(String typeId, String action);

    /** The rules in force for one profile. Fails with {@link ProfileErrorCode#PROFILE_NOT_FOUND} for no such profile. */
    CompletableFuture<ProfileRuleSet> rules(UUID profileId);

    /** {@code rules(profileId)} answering one action. */
    default CompletableFuture<Boolean> allows(final UUID profileId, final String action) {
        return rules(profileId).thenApply(rules -> rules.allows(action));
    }

    /** The definition for a profile's type id, by {@link #typeId}. */
    default Optional<ProfileTypeDefinition> definitionOf(final ProfileType profileType,
                                                         final @Nullable Map<String, String> metadata) {
        return definition(typeId(profileType, metadata == null ? Map.of() : metadata));
    }
}
