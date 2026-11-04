package dev.iyanz.sourbycraft.update;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Test-only redirection of the updater's release endpoint, used by the local updater rollback
 * fixture ({@code scripts/updater_rollback_fixture.py}, {@code docs/development/updater-rollback-fixture-2026-10-22.md}).
 * <b>Disabled unless a JVM system property is set; no config key, no default, never read from the
 * TOML</b>, so only whoever starts the JVM can use it.
 *
 * <ul>
 *   <li>{@value #API_BASE_PROPERTY}: base URL that replaces {@code https://api.github.com} for the
 *       releases listing ({@code <base>/repos/<owner>/<repo>/releases}). Only {@code http}/{@code https}
 *       on a loopback host ({@code 127.0.0.1}, {@code localhost}, {@code [::1]}) with no user info,
 *       query or fragment is accepted, so a stray property can never point a production server at a
 *       remote host. A value that is set but not acceptable is an error: the check does nothing
 *       (it does not fall back to GitHub). Asset URLs come from the listing itself, exactly as on
 *       GitHub.</li>
 *   <li>While {@value #API_BASE_PROPERTY} is set, release assets may also be fetched over plain
 *       {@code http} from a loopback host ({@link #assetUrlAllowed}); otherwise every asset URL must be
 *       {@code https}.</li>
 *   <li>{@value #KEY_FILE_PROPERTY}: PEM/base64 Ed25519 public key file that turns signature and
 *       manifest enforcement ON for a build that ships no {@code META-INF/sourbycraft-release.pub}.
 *       It can only add enforcement: when the build ships its own key the property is ignored
 *       (see {@link ReleaseSignatureVerifier#resolve}).</li>
 * </ul>
 */
final class UpdateOverrides {

    static final String API_BASE_PROPERTY = "sourbycraft.update.api-base";
    static final String KEY_FILE_PROPERTY = "sourbycraft.update.release-key-file";
    /** Production releases endpoint prefix; {@code <owner>/<repo>/releases} is appended. */
    static final String DEFAULT_REPOS_BASE = "https://api.github.com/repos/";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    private UpdateOverrides() {}

    /** The repos endpoint prefix for this JVM: GitHub, or the validated override. */
    static String reposBase() {
        return reposBase(System.getProperty(API_BASE_PROPERTY));
    }

    /**
     * {@link #DEFAULT_REPOS_BASE} when {@code override} is null or blank, else the override
     * normalised to {@code scheme://host[:port]/repos/}.
     *
     * @throws IllegalArgumentException when the override is not a plain loopback http(s) base URL
     */
    static String reposBase(@Nullable String override) {
        if (override == null || override.isBlank()) return DEFAULT_REPOS_BASE;
        final URI uri;
        try {
            uri = new URI(override.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(API_BASE_PROPERTY + " is not a URL: " + e.getReason());
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(API_BASE_PROPERTY + " must be an http or https URL");
        }
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new IllegalArgumentException(API_BASE_PROPERTY + " must name a loopback host (127.0.0.1, localhost, [::1]), not '"
                + (uri.getHost() == null ? uri.getAuthority() : uri.getHost()) + "'");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(API_BASE_PROPERTY + " must be scheme://host[:port] only");
        }
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException(API_BASE_PROPERTY + " must not carry a path");
        }
        return scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + "/repos/";
    }

    /** Whether a release asset may be downloaded from {@code url} in this JVM; see {@link #assetUrlAllowed(String, String)}. */
    static boolean assetUrlAllowed(String url) {
        return assetUrlAllowed(url, System.getProperty(API_BASE_PROPERTY));
    }

    /**
     * {@code https} always; plain {@code http} only on a loopback host and only while the
     * {@value #API_BASE_PROPERTY} override is set ({@code override} non-blank). Anything else,
     * including an unparseable URL, is refused.
     */
    static boolean assetUrlAllowed(String url, @Nullable String override) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return true;
        if (!scheme.equals("http") || override == null || override.isBlank()) return false;
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return LOOPBACK_HOSTS.contains(host);
    }
}
