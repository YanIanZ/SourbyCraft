package dev.iyanz.sourbycraft.api.profile;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. The value of one action rule of a {@link ProfileTypeDefinition}: allow or deny, with optional
 * string parameters the consumer interprets itself (for example a cap).
 *
 * <p>The server reads only {@link #allowed()}. Parameters are stored and returned unchanged; no engine action reads
 * them in Build 54.</p>
 *
 * @param allowed whether the action is allowed
 * @param params at most {@value #MAX_PARAMS} parameters, keys {@code [a-z0-9_.-]{1,32}}, values at most
 *     {@value #MAX_PARAM_VALUE} characters without control characters; iteration order is the key order
 */
@NullMarked
public record ProfileRule(boolean allowed, Map<String, String> params) {

    /** Most parameters of one rule. */
    public static final int MAX_PARAMS = 8;
    /** Longest parameter value. */
    public static final int MAX_PARAM_VALUE = 128;
    /** An allow rule without parameters. */
    public static final ProfileRule ALLOW = new ProfileRule(true, Map.of());
    /** A deny rule without parameters. */
    public static final ProfileRule DENY = new ProfileRule(false, Map.of());

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,32}");

    public ProfileRule {
        Objects.requireNonNull(params, "params");
        if (params.size() > MAX_PARAMS) throw new IllegalArgumentException("a rule has at most " + MAX_PARAMS + " parameters");
        final Map<String, String> copy = new TreeMap<>();
        for (final Map.Entry<String, String> entry : params.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("rule parameter key: " + key);
            if (value == null || value.length() > MAX_PARAM_VALUE || hasControl(value)) {
                throw new IllegalArgumentException("rule parameter " + key + " value");
            }
            copy.put(key, value);
        }
        params = java.util.Collections.unmodifiableMap(copy);
    }

    /** {@link #ALLOW}. */
    public static ProfileRule allow() {
        return ALLOW;
    }

    /** {@link #DENY}. */
    public static ProfileRule deny() {
        return DENY;
    }

    /** The same decision with these parameters. */
    public ProfileRule withParams(final Map<String, String> next) {
        return new ProfileRule(this.allowed, next);
    }

    /** One parameter, or {@code null}. */
    public @Nullable String param(final String key) {
        return this.params.get(key);
    }

    /**
     * Parses {@code allow} or {@code deny} (also {@code true}/{@code false}), ignoring case and surrounding white
     * space; anything else is refused.
     */
    public static ProfileRule parse(final String text) {
        final String value = Objects.requireNonNull(text, "text").strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "allow", "true" -> ALLOW;
            case "deny", "false" -> DENY;
            default -> throw new IllegalArgumentException("a rule is allow or deny, not " + text);
        };
    }

    static boolean hasControl(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) return true;
        }
        return false;
    }
}
