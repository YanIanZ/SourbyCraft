package dev.iyanz.sourbycraft.api.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. A profile type: its id, how it is shown and named, whether it may be created and become a co-op,
 * and one {@link ProfileRule} per action id.
 *
 * <p>The four built-in types ({@code classic}, {@code ironman}, {@code stranded}, {@code bingo}) are defined by the
 * server; a consumer may register further types, or (when the server allows it) replace a built-in definition, with
 * {@link ProfileTypeRegistry#register}.</p>
 *
 * <p><b>Action ids</b> are free strings {@code [a-z0-9][a-z0-9_.:-]{0,63}}. The server enforces the ids it owns
 * ({@link ProfileTypeRegistry#BANK_DEPOSIT} and the other constants there) at its own action boundaries; every other id
 * ({@code trade}, {@code auction}, {@code island-bank}, ...) is the consumer's to enforce, using
 * {@link ProfileTypeRegistry#allows}. An action without its own rule uses {@link #defaultRule()}, except the Build 55
 * ids {@link ProfileTypeRegistry#PROFILE_SWITCH}, {@link ProfileTypeRegistry#COOP_KICK} and
 * {@link ProfileTypeRegistry#COOP_LABEL}, which are allowed unless {@link #rules()} names them
 * ({@link ProfileTypeRegistry#allowedUnlessNamed}).</p>
 *
 * @param id {@code [a-z0-9_-]{1,32}}; the stable key stored with a profile
 * @param displayName 1-{@value #MAX_DISPLAY_NAME} characters, no control characters
 * @param description at most {@value #MAX_DESCRIPTION} characters, no control characters ({@code ""} for none)
 * @param coopAllowed whether a profile of this type may become or stay a co-op; {@code false} denies
 *     {@link ProfileTypeRegistry#COOP_CONVERT}, {@link ProfileTypeRegistry#COOP_INVITE} and
 *     {@link ProfileTypeRegistry#COOP_ACCEPT} whatever {@link #rules()} say
 * @param creatable whether new profiles of this type may be created; existing profiles keep their type either way
 * @param maxMembers co-op member cap for this type (owner included), 2-8, or {@code null} for the server's
 *     {@code coop.max-members}
 * @param naming how profiles of this type are named
 * @param rules at most {@value #MAX_RULES} action rules; iteration order is the action id order
 * @param defaultRule the rule of an action that has none in {@link #rules()} (not of the Build 55 ids named above)
 */
@NullMarked
public record ProfileTypeDefinition(String id, String displayName, String description, boolean coopAllowed,
                                    boolean creatable, @Nullable Integer maxMembers, ProfileNaming naming,
                                    Map<String, ProfileRule> rules, ProfileRule defaultRule, boolean seasonal) {

    /**
     * The Build 54 shape: {@code seasonal} false. Kept so code compiled against Build 54 still links and so
     * existing calls compile unchanged.
     */
    public ProfileTypeDefinition(final String id, final String displayName, final String description, final boolean coopAllowed,
                                 final boolean creatable, final @Nullable Integer maxMembers, final ProfileNaming naming,
                                 final Map<String, ProfileRule> rules, final ProfileRule defaultRule) {
        this(id, displayName, description, coopAllowed, creatable, maxMembers, naming, rules, defaultRule, false);
    }

    /** Longest display name. */
    public static final int MAX_DISPLAY_NAME = 32;
    /** Longest description. */
    public static final int MAX_DESCRIPTION = 256;
    /** Most action rules of one type. */
    public static final int MAX_RULES = 64;
    /** Smallest co-op cap override. */
    public static final int MIN_MEMBERS = 2;
    /** Largest co-op cap override (the server's own upper bound). */
    public static final int MAX_MEMBERS = 8;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern ACTION = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");

    public ProfileTypeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(naming, "naming");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(defaultRule, "defaultRule");
        if (!validId(id)) throw new IllegalArgumentException("profile type id: " + id);
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME || ProfileRule.hasControl(displayName)
            || !displayName.equals(displayName.strip())) {
            throw new IllegalArgumentException("profile type display name: " + displayName);
        }
        if (description.length() > MAX_DESCRIPTION || ProfileRule.hasControl(description)) {
            throw new IllegalArgumentException("profile type description of " + id);
        }
        if (maxMembers != null && (maxMembers < MIN_MEMBERS || maxMembers > MAX_MEMBERS)) {
            throw new IllegalArgumentException("profile type max-members " + maxMembers + " is not " + MIN_MEMBERS + "-" + MAX_MEMBERS);
        }
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("a profile type has at most " + MAX_RULES + " rules");
        final Map<String, ProfileRule> copy = new TreeMap<>();
        for (final Map.Entry<String, ProfileRule> entry : rules.entrySet()) {
            if (!validAction(entry.getKey())) throw new IllegalArgumentException("action id: " + entry.getKey());
            copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "rule " + entry.getKey()));
        }
        rules = Collections.unmodifiableMap(copy);
    }

    /** Whether {@code id} is a well-formed type id. */
    public static boolean validId(final @Nullable String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Whether {@code action} is a well-formed action id. */
    public static boolean validAction(final @Nullable String action) {
        return action != null && ACTION.matcher(action).matches();
    }

    /**
     * The rule of an action: its own rule, else {@link ProfileRule#ALLOW} for an id
     * {@link ProfileTypeRegistry#allowedUnlessNamed} (Build 55 review N2: a Build 54 type's default rule never covered
     * them), else {@link #defaultRule()}. Ignores {@link #coopAllowed()}.
     */
    public ProfileRule rule(final String action) {
        final ProfileRule own = this.rules.get(action);
        if (own != null) return own;
        return ProfileTypeRegistry.allowedUnlessNamed(action) ? ProfileRule.ALLOW : this.defaultRule;
    }

    /**
     * Whether the action is allowed: {@code false} for a co-op action ({@link ProfileTypeRegistry#COOP_CONVERT},
     * {@link ProfileTypeRegistry#COOP_INVITE}, {@link ProfileTypeRegistry#COOP_ACCEPT}) when {@link #coopAllowed()} is
     * false, else {@link #rule(String)}'s decision.
     */
    public boolean allows(final String action) {
        if (!this.coopAllowed && ProfileTypeRegistry.coopAction(action)) return false;
        return rule(action).allowed();
    }

    /** A builder for a new definition; every field but the id has a default (allow everything, server names). */
    public static Builder builder(final String id) {
        return new Builder(id);
    }

    /** A builder initialised from this definition. */
    public Builder toBuilder() {
        final Builder builder = new Builder(this.id).displayName(this.displayName).description(this.description)
            .coopAllowed(this.coopAllowed).creatable(this.creatable).maxMembers(this.maxMembers).naming(this.naming)
            .defaultRule(this.defaultRule).seasonal(this.seasonal);
        this.rules.forEach(builder::rule);
        return builder;
    }

    /** Builds a {@link ProfileTypeDefinition}. Not thread-safe. */
    public static final class Builder {

        private final String id;
        private String displayName;
        private String description = "";
        private boolean coopAllowed = true;
        private boolean creatable = true;
        private boolean seasonal;
        private @Nullable Integer maxMembers;
        private ProfileNaming naming = ProfileNaming.SERVER;
        private final Map<String, ProfileRule> rules = new LinkedHashMap<>();
        private ProfileRule defaultRule = ProfileRule.ALLOW;

        private Builder(final String id) {
            this.id = Objects.requireNonNull(id, "id");
            this.displayName = id;
        }

        public Builder displayName(final String value) {
            this.displayName = value;
            return this;
        }

        public Builder description(final String value) {
            this.description = value;
            return this;
        }

        public Builder coopAllowed(final boolean value) {
            this.coopAllowed = value;
            return this;
        }

        /**
         * Added in Build 55. A seasonal type (the built-in {@code bingo}) is one whose profiles are meant to be
         * archived read-only when its event ends: the consumer that runs the event archives them through
         * {@link AuroraProfiles#archiveProfile(java.util.UUID)}; the server never deletes or archives them on its own.
         * The flag is informational for the server (shown in {@link ProfileDetails#seasonal()} and the registry) and
         * changes no rule. A definition with the flag is stored in a Build 55 record shape; a Build 54 server reads
         * such a stored definition as unknown (deny), never as a classic type.
         */
        public Builder seasonal(final boolean value) {
            this.seasonal = value;
            return this;
        }

        public Builder creatable(final boolean value) {
            this.creatable = value;
            return this;
        }

        public Builder maxMembers(final @Nullable Integer value) {
            this.maxMembers = value;
            return this;
        }

        public Builder naming(final ProfileNaming value) {
            this.naming = value;
            return this;
        }

        /** Sets (or replaces) the rule of one action. */
        public Builder rule(final String action, final ProfileRule rule) {
            this.rules.put(action, rule);
            return this;
        }

        /** {@code rule(action, ProfileRule.ALLOW)}. */
        public Builder allow(final String action) {
            return rule(action, ProfileRule.ALLOW);
        }

        /** {@code rule(action, ProfileRule.DENY)}. */
        public Builder deny(final String action) {
            return rule(action, ProfileRule.DENY);
        }

        public Builder defaultRule(final ProfileRule value) {
            this.defaultRule = value;
            return this;
        }

        /** @throws IllegalArgumentException when a field is out of its bounds */
        public ProfileTypeDefinition build() {
            return new ProfileTypeDefinition(this.id, this.displayName, this.description, this.coopAllowed, this.creatable,
                this.maxMembers, this.naming, this.rules, this.defaultRule, this.seasonal);
        }
    }
}
