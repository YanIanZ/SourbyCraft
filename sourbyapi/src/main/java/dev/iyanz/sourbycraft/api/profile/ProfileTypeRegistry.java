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
     * Added in Build 55. Switching into a profile of this type (engine-owned, checked on the target at admission). A
     * type that denies it can still be created, read and administered; nobody plays it. Unlike the other engine-owned
     * actions, a profile whose type has <em>no definition</em> on the server is not gated by this rule whatever
     * {@code types.unknown-type-policy} says: Build 54 stores hold profiles with free-form type ids that were always
     * playable, and a deny policy must not lock their owners out of them. Like {@link #COOP_KICK} and
     * {@link #COOP_LABEL} it is allowed unless the definition names it in {@link ProfileTypeDefinition#rules()}: the
     * definition's {@link ProfileTypeDefinition#defaultRule() defaultRule} does not apply to it
     * ({@link #allowedUnlessNamed}).
     */
    String PROFILE_SWITCH = "profile.switch";
    /**
     * Added in Build 55. Opening a kick vote, or casting a ballot, in a co-op of this type (engine-owned; also refused
     * when the type allows no co-op). Allowed unless the definition names it in {@link ProfileTypeDefinition#rules()};
     * the definition's default rule does not apply to it ({@link #allowedUnlessNamed}).
     */
    String COOP_KICK = "coop.kick";
    /**
     * Added in Build 55. Setting the shared display label of a co-op of this type (engine-owned). Allowed unless the
     * definition names it in {@link ProfileTypeDefinition#rules()}; the definition's default rule does not apply to it
     * ({@link #allowedUnlessNamed}).
     */
    String COOP_LABEL = "coop.label";
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

    /**
     * Added in Build 55. Whether {@code action} is one of the action ids added in Build 55 ({@link #PROFILE_SWITCH},
     * {@link #COOP_KICK}, {@link #COOP_LABEL}) that a definition's {@link ProfileTypeDefinition#defaultRule()} does not
     * decide: without an explicit entry in {@link ProfileTypeDefinition#rules()} they are allowed. A type written for
     * Build 54 with a deny default rule never named them, and upgrading must not make its profiles unplayable or refuse
     * its kick votes and labels; a type denies them by naming them. {@link ProfileTypeDefinition#coopAllowed()} false
     * still denies the two co-op ids.
     */
    static boolean allowedUnlessNamed(final String action) {
        return PROFILE_SWITCH.equals(action) || COOP_KICK.equals(action) || COOP_LABEL.equals(action);
    }

    /** Whether {@code action} is one of the engine-owned co-op actions ({@code coop.convert/invite/accept/kick/label}). */
    static boolean coopAction(final String action) {
        return COOP_CONVERT.equals(action) || COOP_INVITE.equals(action) || COOP_ACCEPT.equals(action)
            || COOP_KICK.equals(action) || COOP_LABEL.equals(action);
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
